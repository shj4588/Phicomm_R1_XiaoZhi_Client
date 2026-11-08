package cn.workbuddy.r1web;

import android.content.Context;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.Parcel;

import com.phicomm.speaker.player.helpers.SPHelper;
import com.phicomm.speaker.player.model.EasyPlayer;
import com.phicomm.speaker.player.model.PlayPositionInfo;
import com.phicomm.speaker.player.model.PlayRequestInfo;
import com.phicomm.speaker.player.model.PlaySong;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 播放器控制面：把原厂 EasyPlayer 包一层，顺带维护「队列真相」。
 *
 * <p>★ 队列不是我们自己猜的：小智（device）推播放列表走消息总线 →
 * 原厂 MsgHandler → EasyPlayer.play(PlayRequestInfo) → 我们在 play() 开头插桩
 * 拿到 {@code pri}，这里的 {@link #noteQueue} 就是那条插桩的下游。
 * 所以队列永远是 player 实际手里那张表，顺序也和 player 一致。</p>
 *
 * <p>★ 跳播用 {@code playIndex(i, true)}：只改播放位置，列表顺序一动不动
 * （原厂 case 1 的 setIndex(0) 只影响 play()，不影响 playIndex）。
 * 但它有状态机闸门（只在 STATUS_BUSY 下执行）⇒ 推流后非 BUSY 时
 * 由 {@link #ensureBusy} 先重推恢复，见那儿的长注释。</p>
 */
public final class PCtl {

    private static EasyPlayer ep;
    private static List<PlaySong> queue = new ArrayList<PlaySong>();
    private static int curIdx = -1;

    /* ★ pl-1.6.124：节目行判据 —— 标题带「第N集/回/话/章」就算（与网页 abLikely 同口径，
       不要求书名号）。引擎 HandlePlayError 的 smali 闸门（patch_abnoskip.py）调它决定
       「失败后跳下一首」还是「原地停」。 */
    private static final Pattern AB_EP_PAT =
            Pattern.compile("第\\s*[0-9０-９一二三四五六七八九十百千零两]+\\s*[集回话章]");
    private static long queueTs = 0;

    /* ★★ pl-1.6.126：「当前是不是节目行」跨进程标记（铁律级，别再退回纯静态）。
       r1web/PCtl 跑在 **EchoService 主进程**，而 HandlePlayError 的 smali 闸门
       （patch_abnoskip.py）跑在 **NetPlayerService 引擎进程** —— 两个进程各有各的
       静态字段副本 ⇒ 引擎侧的 queue 永远是空的 ⇒ abNoSkipOnErr() 一律 false
       ⇒ pl-1.6.124 的「节目失败不跳集」从落地起就没生效过（实测 1630 失败后
       仍然 "Going to next track"，一路连跳到 1634，全是没有源的空集）。
       现在：主进程每次换队列/换 index 把「是否节目行」写进 files/r1_abnow，
       引擎进程读不到队列时读这个文件兜底。内容 "1 <毫秒时间戳>"，4 小时视为过期。 */
    private static final String AB_FLAG_NAME = "r1_abnow";

    private static java.io.File abFlagFile() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return new java.io.File(((Context) app).getFilesDir(), AB_FLAG_NAME);
            }
        } catch (Throwable t) {
        }
        return new java.io.File("/data/data/com.phicomm.speaker.player/files/" + AB_FLAG_NAME);
    }

    private static void writeAbFlag(boolean on) {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(abFlagFile(), false);
            fo.write(((on ? "1 " : "0 ") + System.currentTimeMillis()).getBytes("UTF-8"));
            fo.flush();
            fo.close();
        } catch (Throwable t) {
            Boot.log("writeAbFlag: " + t);
        }
    }

    private static boolean readAbFlag() {
        try {
            java.io.File f = abFlagFile();
            if (!f.exists()) {
                return false;
            }
            byte[] b = new byte[32];
            java.io.FileInputStream fi = new java.io.FileInputStream(f);
            int n = fi.read(b);
            fi.close();
            if (n <= 0) {
                return false;
            }
            String s = new String(b, 0, n, "UTF-8").trim();
            int sp = s.indexOf(' ');
            if (sp <= 0) {
                return false;
            }
            long ts = Long.parseLong(s.substring(sp + 1).trim());
            if (System.currentTimeMillis() - ts > 4L * 3600L * 1000L) {
                return false;   // 太久没更新 ⇒ 当作残留，别把音乐也拦成"停"
            }
            return s.charAt(0) == '1';
        } catch (Throwable t) {
            return false;
        }
    }

    /** 第 i 行是不是节目行（标题带「第N集/回/话/章」）。 */
    private static boolean isProgRow(int i) {
        try {
            if (i < 0 || i >= queue.size()) {
                return false;
            }
            PlaySong s = queue.get(i);
            String t = (s == null) ? "" : nz(s.getTitle());
            return t.length() > 0 && AB_EP_PAT.matcher(t).find();
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- 收藏占位地址（device v1.3.228 起 / pl-1.6.67） ----------------
       收藏里「本地曲库没有、导入时也没搜到源」的那批，device 排进播放列表时给的是
       占位地址 http://127.0.0.1:8080/r/fav?k=<key>（device PlayerBridge.PENDING_URL）。
       播到它时 WebSrv.favResolveRedirect 才回头问 device 要真地址（op=fav_resolve）
       并 302 过去 —— 那里顺手把「key → 真名 / 真地址」记进 sFavReal：
         · /api/status 用它把播放栏的 curTitle / curArtist 换成**真名**
           （device 推进来的曲名是收藏里的自定义原名，用户要求播放栏显示真实歌曲信息）；
         · /api/queue 用它把还没解析成功的行标成 miss（页面红底 + 点她去在线搜）。
       纯内存态：player 重启后重新解析一遍就有（播到就解析，代价一次 device 搜索）。 */
    private static final Map<String, String[]> sFavReal
            = new ConcurrentHashMap<String, String[]>();

    /** 占位地址解析**失败**（device fav_resolve 回 ERR）的次数表，key → 失败次数。
     * 与 sFavReal（成功）区分开：没进过这里的占位歌只是「还没播到、待解析」，
     * 不该标红；只有尝试过且确实没配上的才红 —— 即「尝试匹配以后没匹配上才红」。
     *
     * <p>pl-1.6.128：值从 Boolean 改成**次数**。页面点过「✕ 忽略红标」后只是本会话
     * 不看这个红；如果用户又播了一次还是失败，次数会 +1 ⇒ 页面据此把「忽略」作废、
     * 重新标红（旧版用 1/0 记，忽略了就再也红不回来，用户实测报的 bug）。</p> */
    private static final Map<String, Integer> sFavFail
            = new ConcurrentHashMap<String, Integer>();

    /** 占位地址里的收藏 key（不是占位地址 ⇒ null）。 */
    public static String favPendingKey(String url) {
        if (url == null) {
            return null;
        }
        int i = url.indexOf("/r/fav?");
        if (i < 0) {
            return null;
        }
        String qs = url.substring(i + "/r/fav?".length());
        for (int j = 0; j < qs.length(); j++) {           // 截到下一个 & 之前（防御）
            if (qs.charAt(j) == '&') {
                qs = qs.substring(0, j);
                break;
            }
        }
        int e = qs.indexOf('=');
        if (e <= 0 || !"k".equals(qs.substring(0, e))) {
            return null;
        }
        try {
            return URLDecoder.decode(qs.substring(e + 1), "UTF-8");
        } catch (Throwable t) {
            return qs.substring(e + 1);
        }
    }

    /** 这一条占位地址解析成功过吗（⇒ {真名, 真歌手, 真地址}；没有过 ⇒ null）。 */
    public static String[] favReal(String k) {
        if (k == null || k.length() == 0) {
            return null;
        }
        return sFavReal.get(k);
    }

    /** 解析成功：记下真名 / 真地址，并动一下队列版本号（页面据此重画、撤掉红底）。 */
    public static synchronized void noteFavResolved(String k, String title,
            String artist, String url) {
        if (k == null || k.length() == 0) {
            return;
        }
        sFavReal.put(k, new String[]{
                title == null ? "" : title,
                artist == null ? "" : artist,
                url == null ? "" : url});
        queueTs = System.currentTimeMillis();
        Boot.log("fav resolve: " + k + " -> " + (title == null ? "" : title));
    }

    /** 占位地址解析失败（device fav_resolve 回 ERR ⇒ player 回 404）：记下 key，
     * 收藏 / 队列据此把这一条标红。它跟 sFavReal 是两套状态，互不影响。 */
    public static synchronized void noteFavFailed(String k) {
        if (k == null || k.length() == 0) {
            return;
        }
        Integer old = sFavFail.get(k);
        int n = (old == null ? 0 : old.intValue()) + 1;
        sFavFail.put(k, Integer.valueOf(n));
        queueTs = System.currentTimeMillis();
        Boot.log("fav resolve failed: " + k + "（第 " + n + " 次）");
    }

    /** 这一条占位地址是否「已尝试且确实没配上」（⇒ 红；没尝试过的只是待解析，不红）。 */
    public static boolean favFailed(String k) {
        return k != null && k.length() > 0 && sFavFail.containsKey(k);
    }

    /** 这一条已失败过几次（0 = 从没试过）。页面拿它判「✕ 忽略之后是不是又失败了一次」。 */
    public static int favFailN(String k) {
        if (k == null || k.length() == 0) {
            return 0;
        }
        Integer n = sFavFail.get(k);
        return n == null ? 0 : n.intValue();
    }

    /* ================= pl-1.6.127：节目占位行（/r/prog?aid=&ep=）的同款状态 =================
       设计要求（用户定调，与收藏预编辑歌单一致）：**占位行只是「播到才解析」，
       解析不到就什么都不播** —— 绝不把「非音频」的响应喂给 MediaPlayer。
       旧版 /r/prog 解析失败回 `HTTP/1.0 404 OK` + 9 字节 "no source"，mediaserver 的
       NuCachedSource2 当成可重试错误每 5s 重试一次共 10 次；用户在这 ~50s 里点别的集
       ⇒ player reset 打断重试中的 source ⇒ mediaserver 卡死（之后每次 setDataSource 都
       `error(-38)`，最后 SIGSEGV），表现为「点一下 1631 再点 1630 就播不了」。
       现在：失败 ⇒ 断连（连接阶段就失败，不产生 NuCachedSource2）+ 这一行标 miss（页面红底），
       用户点红行走重链接挑版本。 */

    /** 节目占位行「已尝试解析且确实没源」的次数表，key = {@code aid|ep} → 失败次数。
     *  与收藏的 sFavFail 同语义：没尝试过的不红，尝试过且失败才红；
     *  值存次数是为了让页面「✕ 忽略红标」在**下一次再失败**时自动失效（重新红）。 */
    private static final Map<String, Integer> sProgFail
            = new ConcurrentHashMap<String, Integer>();

    /** 占位地址里的节目 {@code {aid, ep}}（不是 /r/prog 占位 ⇒ null）。 */
    public static String[] progPendingKey(String url) {
        if (url == null) {
            return null;
        }
        int i = url.indexOf("/r/prog?");
        if (i < 0) {
            return null;
        }
        String qs = url.substring(i + "/r/prog?".length());
        String aid = paramOf(qs, "aid");
        String ep = paramOf(qs, "ep");
        if (aid == null || ep == null || aid.length() == 0 || ep.length() == 0) {
            return null;
        }
        return new String[]{aid, ep};
    }

    private static String paramOf(String qs, String name) {
        String[] parts = qs.split("&");
        for (int i = 0; i < parts.length; i++) {
            int e = parts[i].indexOf('=');
            if (e <= 0 || !name.equals(parts[i].substring(0, e))) {
                continue;
            }
            try {
                return URLDecoder.decode(parts[i].substring(e + 1), "UTF-8");
            } catch (Throwable t) {
                return parts[i].substring(e + 1);
            }
        }
        return null;
    }

    /** 节目占位行解析失败（真的没源）：记下来 ⇒ 队列那一行标红（miss）。 */
    public static synchronized void noteProgFailed(String aid, String ep) {
        if (aid == null || aid.length() == 0 || ep == null || ep.length() == 0) {
            return;
        }
        String key = aid + "|" + ep;
        Integer old = sProgFail.get(key);
        int n = (old == null ? 0 : old.intValue()) + 1;
        sProgFail.put(key, Integer.valueOf(n));
        queueTs = System.currentTimeMillis();
        Boot.log("prog resolve failed: " + aid + " ep=" + ep + "（第 " + n + " 次）⇒ 标红，不硬播");
    }

    /** 节目占位行解析成功：撤掉失败标记（之前红过的话）。 */
    public static synchronized void noteProgResolved(String aid, String ep) {
        if (aid == null || aid.length() == 0 || ep == null || ep.length() == 0) {
            return;
        }
        if (sProgFail.remove(aid + "|" + ep) != null) {
            queueTs = System.currentTimeMillis();
            Boot.log("prog resolve ok: " + aid + " ep=" + ep + " ⇒ 撤红");
        }
    }

    /** 这一集是不是「试过、确实没源」。 */
    public static boolean progFailed(String aid, String ep) {
        return aid != null && ep != null && aid.length() > 0 && ep.length() > 0
                && sProgFail.containsKey(aid + "|" + ep);
    }

    /** 这一集已失败过几次（0 = 从没试过）。页面拿它判「✕ 忽略之后是不是又失败了一次」。 */
    public static int progFailN(String aid, String ep) {
        if (aid == null || ep == null || aid.length() == 0 || ep.length() == 0) {
            return 0;
        }
        Integer n = sProgFail.get(aid + "|" + ep);
        return n == null ? 0 : n.intValue();
    }

    /**
     * 手动绑定成功（网页 🔗 → device {@code op=fav_link} / {@code fav_bind}）：
     * 这一条现在有确定的源了 ⇒ 把「已尝试且没配上」的失败标记撤掉。
     *
     * <p>不然页面会一直挂着红底（用户实测：绑好了还是红、✕ 也退不掉）——
     * 收藏列表和队列行的 miss 都是这条规则算出来的
     * （见 {@code /api/fav} 的补标 与 {@link #queueJson} 的 {@code it.put("miss", ...)}），
     * 只改 device 的收藏主本是不够的：player 这边记着「上次试过没配上」。</p>
     *
     * <p>顺带把旧的「真名/真地址」也清掉：绑定换了版本，旧缓存里那首真名是错的（
     * 正是被翻唱顶掉的那一版），播到它时让 device 重新解析一次。</p>
     */
    public static synchronized void noteFavBound(String k) {
        if (k == null || k.length() == 0) {
            return;
        }
        boolean hit = sFavFail.remove(k) != null;
        sFavReal.remove(k);
        if (hit) {
            queueTs = System.currentTimeMillis();
        }
        Boot.log("fav bound: " + k + (hit ? "（撤掉失败标记）" : ""));
    }

    public static synchronized void bind(Context c) {
        try {
            ep = EasyPlayer.getInstance(c);
            Boot.log("EasyPlayer 绑定 " + (ep != null ? "OK" : "null"));
        } catch (Throwable t) {
            Boot.log("bind: " + t);
        }
    }

    /** 惰性取实例：getInstance 是幂等单例，随时取都安全。 */
    static synchronized EasyPlayer ep() {
        try {
            if (ep == null && Boot.app != null) {
                ep = EasyPlayer.getInstance(Boot.app);
            }
        } catch (Throwable t) {
            // ignore
        }
        return ep;
    }

    /**
     * 反射取对象的私有字段（注入层编译期引用不到原厂的私有成员）。
     * 拿不到就返回 null，不抛 —— 调用方自己判断。
     */
    static Object field(Object o, String name) {
        try {
            if (o == null) {
                return null;
            }
            /* ★ 必须沿 superclass 往上找：getDeclaredField 只查**本类**，
               而 RemotePlayer 有可能是匿名子类 ⇒ 直接查会 NoSuchField */
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean ready() {
        return ep() != null;
    }

    public static synchronized void noteQueue(PlayRequestInfo pri) {
        noteResume();                  // pl-1.6.27：小智推列表开播 ⇒ 主动暂停记账作废
        try {
            List<PlaySong> l = pri.getItemList();
            if (l == null || l.isEmpty()) {
                return;
            }
            queue = new ArrayList<PlaySong>(l);
            curIdx = pri.getIndex();
            queueTs = System.currentTimeMillis();
            writeAbFlag(isProgRow(curIdx));      // pl-1.6.126：给引擎进程留标记
            Boot.log("队列更新 " + queue.size() + " 首, index=" + curIdx
                    + ", 首曲=" + (queue.get(0).getTitle()));
        } catch (Throwable t) {
            Boot.log("noteQueue: " + t);
        }
    }

    public static synchronized void noteIndex(int i) {
        noteResume();                  // pl-1.6.27：换 index 就是要出声 ⇒ 清暂停记账
        curIdx = i;
        writeAbFlag(isProgRow(i));     // pl-1.6.126：切一集就更新跨进程标记
    }

    /* ---------------- 控制 ---------------- */

    /**
     * 反射读原厂 {@code EasyPlayer.mPlayerStatus}（私有字段）：
     * <pre>
     *   0 = STATUS_IDLE
     *   1 = STATUS_WAITING_EXTERNAL_CONNECT   （DLNA/AirPlay/蓝牙推流接管中）
     *   2 = STATUS_BUSY                       （正常在播/可控制）
     * </pre>
     * 取不到返回 -1，调用方按「非 BUSY」处理（走重推恢复，最稳）。
     */
    static int playerStatus(EasyPlayer p) {
        try {
            java.lang.reflect.Field f = EasyPlayer.class.getDeclaredField("mPlayerStatus");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 当前生效的 OnlinePlayer 是不是**远程源**（DLNA/AirPlay/蓝牙）。
     *
     * <p>原厂结构（{@code EasyPlayer.smali}）：</p>
     * <pre>
     *   mOnlinePlayer : interfaces/Player   ← 当前接管的播放器（可被换成远程的）
     *   mNetPlayer    : RemotePlayer        ← 本地网络播放器
     *   isExternalConnectPlayer(p) ≡ p != null &amp;&amp; p != mNetPlayer
     * </pre>
     *
     * <p>★ pl-1.6.1 实锤（logcat）：DLNA 推流**结束后** mPlayerStatus 仍然是
     * {@code STATUS_BUSY(2)}，但 {@code mOnlinePlayer} 一直挂着
     * {@code RemotePlayer{mRemoteServiceClass=DlnaPlayerService}} —— 此时
     * playIndex/start/next 全部**透传给已停掉的 DLNA 引擎**，静默 false。
     * 这就是「推流后点列表/播放键全死、必须重新载入列表」的真凶：
     * 光看状态机会误判为 BUSY 放行。</p>
     */
    static boolean onlineIsRemote(EasyPlayer p) {
        try {
            java.lang.reflect.Field fo = EasyPlayer.class.getDeclaredField("mOnlinePlayer");
            java.lang.reflect.Field fn = EasyPlayer.class.getDeclaredField("mNetPlayer");
            fo.setAccessible(true);
            fn.setAccessible(true);
            Object o = fo.get(p);
            Object n = fn.get(p);
            return o != null && o != n;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 需不需要走「重推恢复」：非 BUSY，或 BUSY 但 OnlinePlayer 挂着远程源。
     */
    private static boolean needsRecover(EasyPlayer p) {
        return playerStatus(p) != 2 || onlineIsRemote(p);
    }

    /**
     * ★★ 把 player 拉回 {@code STATUS_BUSY} —— 「点播放列表没反应」的根治。
     *
     * <p>原厂 {@code playIndex / start / pause / playNext / playPre} 是同一个
     * 状态机闸门（{@code packed-switch mPlayerStatus}）：只有 2=BUSY 才往下走，
     * 0=IDLE / 1=WAITING_EXTERNAL_CONNECT 一律<b>直接 return false，静默失败</b>。
     * logcat 实锤：{@code EasyPlayer playIndex 1 ... PlayerStatus: STATUS_IDLE}。</p>
     *
     * <p>DLNA/AirPlay 推流时 device 经总线发 {@code startWaitingExternalConnect}
     * ⇒ 主 player 进 1；推流结束停在 0。这之后点队列 / 按 ▶ 全部没反应 ——
     * 只有重新推一次列表（{@code EasyPlayer.play(PlayRequestInfo,true)}，
     * 这个方法任何状态都进得去：1→WaitingConnectToStopped（内部
     * shutdownAllExternalConnectPlayer 会把 DLNA/AirPlay/蓝牙引擎停掉）、
     * 0→StoppedToPlaying）才能恢复。这就是用户说的
     * 「要重新载入列表才能播」。</p>
     *
     * <p>这里在非 BUSY 时自动用当前队列重推一次（顺序一点不变，从 index 首开播），
     * 用户无需自己去 reload。</p>
     *
     * @param index 起始下标（0-based）
     * @return true = 本来就在 BUSY，或重推成功
     */
    private static boolean ensureBusy(EasyPlayer p, int index) {
        try {
            int st0 = playerStatus(p);
            boolean remote = onlineIsRemote(p);
            if (st0 == 2 && !remote) {
                return true;
            }
            int n;
            synchronized (PCtl.class) {
                if (queue.isEmpty()) {
                    return false;
                }
                n = queue.size();
            }
            if (remote) {
                // ★★ 远程源还在场（BUSY 但 mOnlinePlayer 挂着 DlnaPlayerService 等）：
                // 先调原厂 exitWaitingExternalConnect()V（private，内部
                // shutdownAllExternalConnectPlayer()）把推流引擎停掉，交接回本地，
                // 否则 play(info) 重建的命令又被透传给死掉的远程引擎。
                try {
                    java.lang.reflect.Method m = EasyPlayer.class.getDeclaredMethod(
                            "exitWaitingExternalConnect");
                    m.setAccessible(true);
                    m.invoke(p);
                    Boot.log("ensureBusy: 远程源在场，已 exitWaitingExternalConnect 交接回本地");
                } catch (Throwable t) {
                    Boot.log("ensureBusy: exitWaitingExternalConnect 失败（继续重推）：" + t);
                }
                // ★ pl-1.6.2：上面那一下会把 DLNA/AirPlay 引擎一起停掉 ⇒ 按用户
                // 手动意图延迟补开（手动开过的才补；手动关/没开过的不动）
                Svc.autoReopenLater("ensureBusy 本地接管");
            }
            int ix = index < 0 ? 0 : (index >= n ? n - 1 : index);
            PlayRequestInfo info = new PlayRequestInfo();
            info.setItemList(new ArrayList<PlaySong>(queue));
            info.setIndex(ix);
            PlaySong cur = queue.get(ix);
            if (cur != null && cur.getItemId() != null) {
                info.setCurItemId(cur.getItemId());
            }
            boolean r = p.play(info, true);
            Boot.log("ensureBusy: 触发时 状态=" + st0 + " 远程源=" + remote
                    + "，重推 " + n + " 首 index=" + ix + " => " + r
                    + "（now 状态=" + playerStatus(p) + "）"
                    + (remote ? "，推流引擎已停、本地接管" : ""));
            return r;
        } catch (Throwable t) {
            Boot.log("ensureBusy: " + t);
            return false;
        }
    }

    public static String play() {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        alarmYield();                 // pl-1.6.89：先让 device 还原铃声音量，再出声
        noteResume();                 // pl-1.6.27：起播 ⇒ 主动暂停记账作废
        if (needsRecover(p)) {
            // ★ start() 在非 BUSY / 远程源在场时是空转（透传给死引擎）⇒ 重推恢复
            return ok(ensureBusy(p, curIdx), "继续播放");
        }
        return ok(p.start(), "继续播放");
    }

    public static String pause() {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        /* ★ pl-1.6.23：空闲态按「暂停」别再弹 ERR。
           EasyPlayer.pause() 是个 packed-switch mPlayerStatus 闸门：
           0=IDLE / 1=WAITING_EXTERNAL_CONNECT 直接 return false（连引擎都不问），
           只有 2=BUSY 才下派。所以「本来就没在播」的时候界面拿到 false，
           文案就成了 ERR 暂停 —— 可它什么错都没有。
           没在播 ≡ 已经是暂停态，按幂等处理，直接回 OK（此时 p.pause() 本来也是空转）。 */
        /* ★ pl-1.6.27：无论走哪条路，先把「用户按了暂停」记下来 ——
           phase()/playingUi() 见到它就立刻 idle，不等引擎表态（见 sUserPaused 注释）。 */
        sUserPaused = true;
        sUserPausedTs = System.currentTimeMillis();
        if (playerStatus(p) != 2) {
            return ok(true, "暂停");
        }
        return ok(p.pause(), "暂停");
    }

    public static String stop() {
        boolean r = ep() != null && ep().stop();
        forgetRadio();                 // pl-1.6.25：停播 ⇒ 「电台」记账作废
        noteResume();                  // pl-1.6.27：主动暂停记账一并作废
        return ok(r, "停止");
    }

    public static String next() {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        alarmYield();                  // pl-1.6.89：先让路（音量先设好，再出声）
        noteResume();                  // pl-1.6.27：下一曲 ⇒ 清暂停记账
        if (needsRecover(p)) {
            return ok(ensureBusy(p, curIdx < 0 ? 0 : curIdx + 1), "下一曲");
        }
        return ok(p.playNext(true), "下一曲");
    }

    public static String prev() {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        alarmYield();                  // pl-1.6.89：先让路
        noteResume();                  // pl-1.6.27：上一曲 ⇒ 清暂停记账
        if (needsRecover(p)) {
            return ok(ensureBusy(p, curIdx < 0 ? 0 : curIdx - 1), "上一曲");
        }
        return ok(p.playPre(true), "上一曲");
    }

    /**
     * pl-1.6.89：起播音乐<b>之前</b>先跟 device 说一声「让路」。
     *
     * <p>解决的是音量跳变：闹钟响的时候 device 把媒体流设成了「闹钟音量」，
     * 用户这时点播放，音乐会先按那个音量响一下、再被还原 —— 听感就是「突然变大/变小」。
     * 所以规矩是<b>先设音量，再出声</b>：这一步让 device 把铃声音量还回去、
     * 顺手关掉正在响的闹钟（只关闹钟，不动音乐），返回之后才真正起播。</p>
     *
     * <p>没在响铃也不亏：device 那边是幂等的（没动过音量就什么都不做）。
     * 失败静默 —— 回环不通不该拦着用户听歌。</p>
     */
    private static void alarmYield() {
        try {
            Proxy.post("/api/act", "op=alarm_yield", 1500);
        } catch (Throwable t) {
            Boot.log("alarmYield: " + t);
        }
    }

    /** 跳到队列第 i 首（0-based）。列表顺序完全不变。 */
    public static String playAt(int i) {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        alarmYield();                  // pl-1.6.89：先让路
        noteResume();                  // pl-1.6.27：指定曲目 ⇒ 清暂停记账
        synchronized (PCtl.class) {
            if (queue.isEmpty()) {
                return "ERR 队列是空的（先让小智放一首，或用小智推一次列表）";
            }
            if (i < 0 || i >= queue.size()) {
                return "ERR 序号超出范围，共 " + queue.size() + " 首";
            }
        }
        if (needsRecover(p)) {
            // ★★ 非 BUSY 或 OnlinePlayer 挂着远程源时，playIndex 会被状态机吞掉 /
            //    被透传给死掉的推流引擎（静默 false）⇒ 走重推恢复：
            //    play(info,true) 任何状态都能进，从第 i 首直接开播。
            String who0 = titleAt(i);
            boolean r0 = ensureBusy(p, i);
            Boot.log("playAt #" + (i + 1) + " " + who0 + "（重推恢复）=> " + r0);
            return ok(r0, "播放第 " + (i + 1) + " 首：" + who0);
        }
        boolean r = p.playIndex(i, true);
        String who = titleAt(i);
        Boot.log("playAt #" + (i + 1) + " " + who + " => " + r);
        return ok(r, "播放第 " + (i + 1) + " 首：" + who);
    }

    /**
     * pl-1.6.137：反射造一个**原厂 PlaySong**（本地有声书整批入列要用）。
     *
     * <p>★ 为什么只能反射：注入类编译期用的是 stub（{@code stub/.../PlaySong.java}
     * 只有 getter），真实类在 player APK 里。真实类只有一个
     * {@code public PlaySong(Parcel)}：拿空 Parcel 过一遍（readString 全 null、
     * readInt=0），再用反射把字段填上。字段名取自原厂 smali：
     * itemId/title/url/artist/album/albumUrl/itemType。</p>
     */
    public static PlaySong newSong(String itemId, String title, String url, String artist) {
        try {
            Class<?> c = Class.forName("com.phicomm.speaker.player.model.PlaySong");
            Object o = c.getDeclaredConstructor(Parcel.class).newInstance(Parcel.obtain());
            setStr(o, "itemId", itemId);
            setStr(o, "title", title);
            setStr(o, "url", url);
            setStr(o, "artist", artist);
            setStr(o, "album", "");
            setStr(o, "albumUrl", "");
            return (PlaySong) o;
        } catch (Throwable t) {
            Boot.log("newSong: " + t);
            return null;
        }
    }

    private static void setStr(Object o, String f, String v) {
        try {
            java.lang.reflect.Field x = o.getClass().getDeclaredField(f);
            x.setAccessible(true);
            x.set(o, v);
        } catch (Throwable t) {
            // ignore：字段名对不上时这一项为空，不影响起播
        }
    }

    /**
     * ★★ pl-1.6.137：本地有声书「整批入列」—— 点一集就把它和后面的一起推进 player。
     *
     * <p>以前的毛病（用户原话「点击下一曲的按钮跳到别的书去了」）：本地节目只发一条
     * {@code play_url}（单曲）⇒ player 手里只有这一集，按下一曲它去轮**上一张列表**的
     * 下一个，于是跳到别的书。在线节目走 device 的 ab 加载器（一次 50 集入列）所以不串；
     * 这里把同样的语义补在 player 侧：自己造 PlaySong 列表 + {@code play(info, true)}。</p>
     *
     * <p>注意 {@code play(info,true)} 是「任何状态都能进」的入口（见 ensureBusy 注释），
     * 所以不用先判 BUSY；起播后顺手 {@link #noteQueue} 同步镜像队列 + 跨进程节目标记，
     * 下一曲/上一曲/网页队列就都跟着这张表走。</p>
     */
    public static String playList(List<PlaySong> items, int index) {
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        if (items == null || items.isEmpty()) {
            return "ERR 播放列表是空的";
        }
        alarmYield();
        noteResume();
        int n = items.size();
        int ix = index < 0 ? 0 : (index >= n ? n - 1 : index);
        PlayRequestInfo info = new PlayRequestInfo();
        info.setItemList(new ArrayList<PlaySong>(items));
        info.setIndex(ix);
        PlaySong cur = items.get(ix);
        if (cur != null && cur.getItemId() != null) {
            info.setCurItemId(cur.getItemId());
        }
        boolean r;
        try {
            r = p.play(info, true);
        } catch (Throwable t) {
            return "ERR 起播失败: " + t;
        }
        noteQueue(info);
        Boot.log("playList 入列 " + n + " 首 index=" + ix + " => " + r
                + "（状态=" + playerStatus(p) + "）");
        if (!r) {
            return "ERR 播放器拒绝起播（状态=" + playerStatus(p) + "）";
        }
        return "OK 播放第 " + (ix + 1) + " 首：" + (cur == null ? "" : cur.getTitle());
    }

    /**
     * 设播放模式。
     *
     * <p>★ pl-1.2.0：以前页面发的是 0/1，而原厂 {@code AbsPlayer.setPlayMode()} 开头
     * 就是 {@code if (mode != 1 && != 2 && != 3) → Log "setPlayMode error, mode = N";
     * return false} —— **0 压根不收**，所以「顺序播放」点了多少次都没用。</p>
     *
     * <p>真值（原厂 {@code util/Constant}）：</p>
     * <pre>
     *   1 = PLAY_MODE_LIST_SHUFFLE  列表随机
     *   2 = PLAY_MODE_LIST_CYCLE    列表循环（=「顺序播放」，也是 PLAY_MODE_DEFAULT）
     *   3 = PLAY_MODE_SINGLE_CYCLE  单曲循环
     * </pre>
     */
    public static String setMode(int m) {
        if (m != 1 && m != 2 && m != 3) {
            return "ERR 播放模式只能是 1=随机 / 2=顺序 / 3=单曲循环（收到 " + m + "）";
        }
        String name = (m == 1) ? "随机" : ((m == 2) ? "顺序播放" : "单曲循环");
        return ok(ep() != null && ep().setPlayMode(m), "播放模式 " + m + "（" + name + "）");
    }

    /* ---------------- 状态 ---------------- */

    public static boolean isPlaying() {
        try {
            EasyPlayer p = ep();
            return p != null && p.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ pl-1.6.27：给**页面**用的「在不在播」。
     *
     * <p>跟 {@link #isPlaying()} 的区别：多压一条「用户主动暂停」，并且沿用
     * {@link #phase()} 里那条直播流假阴性的补判（进度最近在走 ⇒ 算在播）。
     * 页面图标/进度条认这个，不认裸的 {@code isPlaying()}。</p>
     */
    public static boolean playingUi() {
        if (sUserPaused) return false;
        return !"idle".equals(phase());
    }

    /* ------------------------------------------------------------------
     * ★★ 假播检测（pl-1.6.9，2026-10-17）
     *
     * 背景（用户实测反馈）：在线歌取链失败时，播放器照样停在 STATUS_BUSY，
     * `isPlaying()` 恒为 true ⇒ 页面一直显示「播放中」，卡片还会继续走时间 ——
     * 但音箱一声不出，且它自己不会跳下一首，就这么干挂着。
     *
     * 判据（两条，任一成立即判「卡住」）：
     *   ① 起播后总时长一直是 0 超过 STALL_NODUR(12s) —— 真在播的流（本地文件、
     *      能取到链的在线流）几秒内就会给出 total_time；拿不到 = 流都没打开。
     *      ★ 这条同时覆盖「进度在涨但没声音」的形态（那种也拿不到总时长）。
     *   ② 进度超过 STALL_FROZEN(15s) 一动不动 —— 缓冲卡死。
     *
     * 采样是懒的：每次 /api/status 顺手采一次（页面 8s 轮询、脚本按需打）。
     * 不给它单独起线程 —— 注入层每多一个常驻线程就多一分拖垮原厂 player 的风险。
     * ------------------------------------------------------------------ */
    private static final long STALL_NODUR = 12000;
    private static final long STALL_FROZEN = 15000;
    private static long sPos = -1;
    private static long sSameSince = 0;
    private static long sNoDurSince = 0;
    /** 最近一次看到「进度真的变了」的墙钟（直播流上 isPlaying() 假阴性时靠它判活）。 */
    private static long sAdvanceTs = 0;
    private static String sSongId = null;
    /** 电台粘性判定（pl-1.6.25）：一旦确认是直播流就锁住，直到换曲 / 停播 / 时长变真。 */
    private static boolean sRadio = false;
    private static String sRadioKey = "";
    /** pl-1.6.44：第一次看到「在播但没时长」的时刻 —— 持续 1.2s 才敢认电台。 */
    private static long sRadioCandTs = 0;
    /**
     * pl-1.6.44：**电台名册**（URL 集合）—— 电台是 NAS 里写死的固定条目，
     * 认它该查名单，不该靠「没有时长 / 像直播流」去猜（猜就是把在线歌误标电台的根源）。
     * 名单来自 device status 的 {@code music.radioList}（player 本来就要拉 status，零额外请求）。
     */
    private static volatile String[] sRadioUrls = new String[0];
    private static long sRadioUrlsTs = 0;

    /**
     * ★ pl-1.6.27：用户**主动暂停**的记账。
     *
     * <p>症状（用户实测）：点了暂停，声音立刻停了，但网页上的按钮图标还停在「暂停」、
     * 进度条还在走，长的要十几秒才变。原因两条叠加：</p>
     * <ol>
     *   <li>页面状态靠 8 秒一次的 {@code /api/status} 轮询刷新，点暂停那一刻没人重新渲染；</li>
     *   <li>就算轮询到了，{@link #phase()} 里那条「进度 4 秒内变过 ⇒ 当在播」的宽限
     *       （为 mp 引擎直播流 {@code isPlaying()} 假阴性加的）会让暂停后仍报 playing；
     *       某些引擎上 {@code isPlaying()} 更是暂停了还返 true。</li>
     * </ol>
     *
     * <p>解法：把「用户按了暂停」当成**最高优先级的意图**，{@code phase()} 见到它就
     * 直接 idle，再也不去问引擎。任何起播动作（play / next / prev / playAt /
     * 小智推列表 / 换 index）都调 {@link #noteResume()} 把它清掉。</p>
     */
    private static volatile boolean sUserPaused = false;
    /* ★ pl-1.6.111：记账时间戳 —— 外部恢复自愈判据用（见 phase() 开头）。 */
    private static volatile long sUserPausedTs = 0;

    /** 任何「要出声」的动作都调一下：把「主动暂停」记账作废。 */
    static void noteResume() {
        sUserPaused = false;
    }

    /* ★ pl-1.6.92：device 顶部实体键的暂停/恢复记账入口。
       实体键的音频动作走 device → 原厂消息总线（msg(2)/msg(3)），**不经过**本类的
       pause()/play()，所以 sUserPaused 一直没人置位 —— phase() 只能靠「进度 4 秒内
       变过 ⇒ 当在播」那条宽限撑着，暂停后图标/假进度还要跑 ~4 秒才停。
       这两个方法**只记账、不碰引擎**（音频由 device 那条路驱动一次，这里再来一遍
       p.pause()/p.start() 就是双动作），让 SSE 推送前的 /api/status 立刻反映真实意图。 */
    /** 实体键暂停：与 {@link #pause()} 的记账部分等同（置 sUserPaused），但不下发引擎。 */
    public static synchronized void markPaused() {
        sUserPaused = true;
        sUserPausedTs = System.currentTimeMillis();
    }

    /** 实体键恢复：与 {@link #noteResume()} 等同（清 sUserPaused），但不下发引擎。 */
    public static synchronized void markResumed() {
        sUserPaused = false;
    }

    /** 每次吐 /api/status 时调一次：更新「卡住」判据的时间戳。 */
    public static synchronized void samplePos() {
        try {
            long now = System.currentTimeMillis();
            PlayPositionInfo p = pos();
            long ps = (p == null) ? 0 : p.getPlay_time();
            long du = (p == null) ? 0 : p.getTotal_time();

            /* ★ 换曲要重新计时：切歌那一刻 playing 一直是 true、新歌的 total_time
               还没出来，不重置的话新歌会继承上一首的「卡住」时长 ⇒ 一上来就误报
               （实测本地歌切过来头 6 秒被判 stalled）。 */
            PlaySong sg = song();
            String id = (sg == null) ? "" : String.valueOf(sg.getItemId());
            if (sSongId == null || !sSongId.equals(id)) {
                sSongId = id;
                sPos = -1; sSameSince = 0;
                sNoDurSince = (du > 0) ? 0 : now;
                return;
            }

            if (du > 0) sNoDurSince = 0;
            else if (sNoDurSince == 0) sNoDurSince = now;
            if (ps != sPos) { sPos = ps; sSameSince = 0; sAdvanceTs = now; }
            else if (sSameSince == 0) sSameSince = now;
        } catch (Throwable t) {
            // 采样失败不影响主流程
        }
    }

    /** 当前是不是「显示播放中但其实没出声」。 */
    public static boolean stalled() {
        return stallMs() > 0;
    }

    /**
     * ★ pl-1.6.9（二）：卡片该显示什么态 —— 把「缓冲中」和「没出声」分开，
     * 别一上来就把十几秒的起播缓冲说成失败。
     * <pre>
     *   idle    —— 没在播
     *   loading —— 已起播但还没出声（在线歌取链 / 缓冲的那十几秒属这类）
     *   playing —— 真在出声（拿到总时长或进度在走）
     *   stalled —— 起播超过 STALL_NODUR 仍没出声，或进度冻住超过 STALL_FROZEN
     * </pre>
     * 注意：本方法依赖 {@link #samplePos()} 采到的时间戳，调用前必须先采一次。
     */
    public static synchronized String phase() {
        /* ★ pl-1.6.27：用户按了暂停 ⇒ 无条件 idle。
           不去问引擎（isPlaying() 在某些引擎上暂停了还返 true），
           也不吃下面那条「进度 4 秒内变过 ⇒ 当在播」的宽限 ——
           那两条一起会让「点了暂停、声音停了、页面还在跑」持续好几秒。 */
        /* ★ pl-1.6.111：外部恢复自愈 —— 语音「继续播放」走 device 总线，不经过
           本类 play()/markResumed()，sUserPaused 会永远挂着：进度在走、图标
           还是暂停。判据：记账 4 秒后仍有进度推进点、且推进点晚于记账点 4 秒
           以上（sAdvanceTs 只在位置真变化时刷新 —— 暂停瞬间的假进度尾巴到不了
           记账点 +4s）⇒ 判为外部已恢复，记账自动作废。 */
        if (sUserPaused && sUserPausedTs > 0) {
            long nt0 = System.currentTimeMillis();
            if (nt0 - sUserPausedTs > 4000 && sAdvanceTs - sUserPausedTs > 4000) {
                sUserPaused = false;
                sUserPausedTs = 0;
            }
        }
        if (sUserPaused) return "idle";
        /* ★ pl-1.6.24：EasyPlayer.isPlaying() 在**直播流**上是假阴性（mp 引擎实测：
           电台明明在放，它返回 false ⇒ 页面卡片显示「未播放」、按钮变回 ▶）。
           补一条判据：进度最近 4 秒内变过，就当作在播。 */
        long now0 = System.currentTimeMillis();
        boolean run = isPlaying();
        if (!run) {
            PlayPositionInfo p0 = pos();
            long ps0 = (p0 == null) ? 0 : p0.getPlay_time();
            run = (ps0 > 0 && sAdvanceTs > 0 && now0 - sAdvanceTs < 4000);
        }
        if (!run) return "idle";
        PlayPositionInfo p = pos();
        long ps = (p == null) ? 0 : p.getPlay_time();
        long du = (p == null) ? 0 : p.getTotal_time();
        long now = System.currentTimeMillis();
        if (du > 0 || ps > 0) {
            return (sSameSince > 0 && now - sSameSince >= STALL_FROZEN) ? "stalled" : "playing";
        }
        return (sNoDurSince > 0 && now - sNoDurSince >= STALL_NODUR) ? "stalled" : "loading";
    }

    /** 已卡住多少毫秒（0 = 没卡）。 */
    public static long stallMs() {
        if (sUserPaused) return 0;     // pl-1.6.27：主动暂停不是「卡住」
        if (!isPlaying()) return 0;
        long now = System.currentTimeMillis();
        long a = (sNoDurSince > 0 && now - sNoDurSince >= STALL_NODUR) ? (now - sNoDurSince) : 0;
        long b = (sSameSince > 0 && now - sSameSince >= STALL_FROZEN) ? (now - sSameSince) : 0;
        return Math.max(a, b);
    }

    /* ★ pl-1.6.41：本进程**有没有真正拿到过一首歌**。
       用途：区分「player 刚重启、从来没播过」和「播过又停了」。
       queue 是本进程的内存态、PCtl.song() 也是进程内的 —— player 一重启它们全空；
       而 device 侧的 curTitle/curPic 是**落盘的**（跨重启存活）⇒ 播放页会挂着
       上一首的曲名+封面+歌词，看着像还在播，其实一声都没有。 */
    private static volatile boolean sEverSong = false;

    public static boolean everSong() {
        return sEverSong;
    }

    public static PlaySong song() {
        try {
            EasyPlayer p = ep();
            PlaySong s = (p == null) ? null : p.getCurrentSong();
            if (s != null) sEverSong = true;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    public static PlayPositionInfo pos() {
        try {
            EasyPlayer p = ep();
            return p == null ? null : p.getPlayPosition();
        } catch (Throwable t) {
            return null;
        }
    }

    /* ★ pl-1.6.12：拖动进度条 --------------------------------------------------
       AIDL 接口名（seek 事务里 enforceInterface 校验要用，必须和原厂一致）。 */
    private static final String IPLAYER = "com.phicomm.speaker.player.IPlayer";
    /* ★ seek 的新事务码：原厂 0x1~0x14 已被 20 个方法占满 ⇒ 取 0x15。
         接线见 patch_seek.py：Stub 分派 → PlayerBinderWrapper$2 → AbsPlayer
         → NetPlayer4 → MultiMediaPlayer.seek → MediaPlayer.seekTo。 */
    private static final int TX_SEEK = 0x15;

    /**
     * 摸到 :NetPlayer 进程里那个 AIDL 的 Binder。
     * <pre>
     *   EasyPlayer.mOnlinePlayer  (RemotePlayer，当前接管的播放器)
     *     └─ RemotePlayer.mIPlayer (IPlayer$Stub$Proxy)
     *          └─ Proxy.mRemote    (android.os.IBinder)
     * </pre>
     * 拿不到就返回 null（播放器还没起来 / 服务还没绑上 / 字段被改名）。
     */
    static IBinder playerBinder() {
        EasyPlayer p = ep();
        if (p == null) {
            return null;
        }
        Object proxy = field(field(p, "mOnlinePlayer"), "mIPlayer");
        if (proxy == null) {
            return null;
        }
        Object r = field(proxy, "mRemote");
        if (r instanceof IBinder) {
            return (IBinder) r;
        }
        return (proxy instanceof IBinder) ? (IBinder) proxy : null;
    }

    /**
     * 当前这首拖得动吗 = 本地播放态（状态机 BUSY 且没挂远程源）+ 拿得到 Binder。
     * 页面据此决定要不要显示拖动把手。
     */
    public static boolean seekReady() {
        EasyPlayer p = ep();
        if (p == null || needsRecover(p)) {
            return false;
        }
        return playerBinder() != null;
    }

    /**
     * ★★★ pl-1.6.129：续播定位用的严格判据 ——「**现在真能拖**了吗」。
     *
     * <p>{@link #seekReady()} 只判「状态机 BUSY + 拿得到 binder」，但 player 收到
     * 换歌后<b>先置 BUSY、再 prepareAsync</b>：这中间几百毫秒同样满足 seekReady，
     * 此时拖一把就会打到还没 prepare 的 MediaPlayer 上（实锤 logcat）：</p>
     *
     * <pre>
     * 20:08:25.707 E/MediaPlayer: Attempt to call getDuration without a valid mediaplayer
     * 20:08:25.707 E/MediaPlayer: Attempt to perform seekTo in wrong state: mCurrentState=0
     * 20:08:25.708 E/MediaPlayer: Error (-38,0)   ⇒ 整条播放被判失败
     * </pre>
     *
     * <p>所以再加三条「真在播」的证据：有总时长（prepare 已回来）、播过 &gt;0 秒、
     * 相位是 playing（不是 loading/idle/stalled）。三条都满足时 seek 才是安全的。</p>
     */
    public static boolean seekReadyNow() {
        if (!seekReady()) {
            return false;
        }
        if (!"playing".equals(phase())) {
            return false;
        }
        PlayPositionInfo p = pos();
        if (p == null) {
            return false;
        }
        return p.getTotal_time() > 0 && p.getPlay_time() > 0;
    }

    /**
     * 拖动进度条：跳到指定毫秒。
     *
     * <p>走的是 smali 上新增的 AIDL 事务 0x15（原厂没有 seek）——
     * 客户端这一半自己拼 Parcel + transact，省得再改 IPlayer 接口和 Proxy
     * （Proxy 实现了 IPlayer，少一个方法就 VerifyError）。</p>
     */
    public static String seekTo(int ms) {
        if (ms < 0) {
            ms = 0;
        }
        EasyPlayer p = ep();
        if (p == null) {
            return "ERR 播放器还没起来";
        }
        int st = playerStatus(p);
        if (st != 2 || onlineIsRemote(p)) {
            /* 非 BUSY / 挂着远程源：底层根本没有可读可拖的 MediaPlayer。
               先试一次重推，成了再 seek —— 推流结束后那一下卡死就是这么解的。 */
            if (ensureBusy(p, curIdx)) {
                st = playerStatus(p);
            }
            if (st != 2 || onlineIsRemote(p)) {
                return "ERR 现在不是本地播放态（状态=" + st
                        + " 远程源=" + onlineIsRemote(p) + "），拖不动进度";
            }
        }
        try {
            IBinder b = playerBinder();
            if (b == null) {
                /* ★ 播放服务还没绑上（刚重启 / 进程被回收后常见）。
                   RemotePlayer.connectRemoteService() 是 public，内部
                   bindService + CountDownLatch 等 2s ⇒ 主动补一次再取 */
                Object online = field(p, "mOnlinePlayer");
                if (online != null) {
                    try {
                        java.lang.reflect.Method cm =
                                online.getClass().getMethod("connectRemoteService");
                        cm.invoke(online);
                    } catch (Throwable t2) {
                        Boot.log("seekTo: connectRemoteService " + t2);
                    }
                    b = playerBinder();
                }
            }
            if (b == null) {
                return "ERR 播放服务还没连上（状态=" + st + "）";
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            int pos;
            try {
                data.writeInterfaceToken(IPLAYER);
                data.writeInt(ms);
                b.transact(TX_SEEK, data, reply, 0);
                reply.readException();
                pos = reply.readInt();
            } finally {
                reply.recycle();
                data.recycle();
            }
            if (pos < 0) {
                /* ★ pl-1.6.13：这行以前写「这个音源不支持拖进度」，误导排查 ——
                   走到这里的真因几乎都是「状态还是 2，但 MultiMediaPlayer 已经被
                   stop/释放了」（典型：stop 之后直接拖），跟音源支持不支持没关系。 */
                return "ERR 现在没在播，拖不动（状态=" + st + ", ret=" + pos + "）";
            }
            return "OK 跳到 " + fmtSec(pos);
        } catch (Throwable t) {
            Boot.log("seekTo: " + t);
            return "ERR " + t;
        }
    }

    /**
     * seek 通路自检串 —— 「拖了没反应」时一眼看出卡在哪一环：
     * ep / status / remote / binder 四段逐段报。
     */
    public static String diag() {
        try {
            EasyPlayer p = ep();
            if (p == null) {
                return "ep=null";
            }
            Object online = field(p, "mOnlinePlayer");
            Object proxy = field(online, "mIPlayer");
            Object rm = field(proxy, "mRemote");
            return "status=" + playerStatus(p)
                    + " online=" + (online == null ? "null" : online.getClass().getSimpleName())
                    + " iplayer=" + (proxy == null ? "null" : proxy.getClass().getSimpleName())
                    + " mRemote=" + (rm == null ? "null"
                            : (rm.getClass().getSimpleName()
                               + (rm instanceof IBinder ? "(IBinder)" : "(!IBinder)")));
        } catch (Throwable t) {
            return "diag ERR " + t;
        }
    }

    /** 毫秒 → "3:38" */
    private static String fmtSec(long ms) {
        long s = Math.max(0, ms / 1000);
        long ss = s % 60;
        return (s / 60) + ":" + (ss < 10 ? "0" : "") + ss;
    }

    public static int mode() {
        try {
            EasyPlayer p = ep();
            return p == null ? -1 : p.getPlayMode();
        } catch (Throwable t) {
            return -1;
        }
    }

    /* ------------------------------------------------------------------
     * 直播流 / 电台识别（pl-1.6.25）
     *
     * 背景：以前 qRadio 只由 device 侧给（它推电台列表时自己记 lastIsRadio），
     * 但 player 直供的 music 段一覆盖，页面就只看到 curId=local_1 / qTotal=1，
     * 于是电台被标成「本地曲库」，还把 durMs 当真的拿去算百分比。
     *
     * ★ 判据只认一条：**总时长无效**。
     *   Exo 对没有终点的流（HLS 直播 / ICY mp3 电台）报 C.TIME_UNSET，
     *   long-to-int 之后落到 MultiExoPlayer.duration() 的值**恒为 1**（实测），
     *   原厂 MediaPlayer 对直播流报 -1 或 0 ⇒ 「<= 1 秒」一律当无效。
     *   本地/在线歌曲 0.5 秒内必给出真实时长（实测本地 MP3 = 188839），不会误判。
     *
     * ★★ pl-1.6.25：原来还要求「进度 > 2000ms」才认电台（怕起播瞬间时长也是 0 误判）。
     *    实测那是 3 秒误显示的根源：Exo 起播 1.3s 出声、进度到 2191 时已经过去 2.8s，
     *    这 3 秒里页面一直挂「本地曲库」。现在改成：
     *      ① 「是否在播」用 isPlaying() **或**「进度最近在走」（mp 引擎直播流假阴性）；
     *      ② 认出来就**锁住**（sRadio），暂停时不会掉回「本地曲库」；
     *      ③ 换曲 / 停播 / 拿到真时长才解锁。
     *    ⇒ 确认时间从 2.8s 缩到 ~0.6s。
     * ------------------------------------------------------------------ */
    /**
     * 电台名册维护（pl-1.6.44）。
     *
     * <p>电台是固定条目，名单在 NAS 的 index.json 里（device 转手给 player）。
     * 这里只是把它摘出来存着 —— 每 10 分钟刷一次就够（电台名单几乎不变）。</p>
     */
    public static void noteRadioUrls(JSONObject d) {
        try {
            if (d == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (sRadioUrls.length > 0 && now - sRadioUrlsTs < 600000) {
                return;                       // 10 分钟内不重复解析
            }
            JSONObject mu = d.optJSONObject("music");
            if (mu == null) {
                return;
            }
            JSONArray rl = mu.optJSONArray("radioList");
            if (rl == null || rl.length() == 0) {
                return;
            }
            List<String> us = new ArrayList<String>();
            for (int i = 0; i < rl.length(); i++) {
                JSONObject r = rl.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                String u = r.optString("url", "");
                if (u.length() > 0) {
                    us.add(u);
                }
            }
            if (us.size() > 0) {
                sRadioUrls = us.toArray(new String[us.size()]);
                sRadioUrlsTs = now;
            }
        } catch (Throwable t) {
            Boot.log("noteRadioUrls: " + t);
        }
    }

    /** 这个地址在电台名册里吗（精确比）。 */
    public static boolean isRadioUrl(String u) {
        if (u == null || u.length() == 0) {
            return false;
        }
        String[] a = sRadioUrls;
        for (int i = 0; i < a.length; i++) {
            if (u.equals(a[i])) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRadio() {
        /* ★★ pl-1.6.109 only-roster-radio-v109：**只认名册**，旧的「猜直播流」兜底整个删掉。
           背景：pl-1.6.44 加名册时保留了兜底——「在播 + 无时长持续 1.2s ⇒ 电台」。
           但缓冲中的在线歌（取链慢 / NAS 慢 / 网络差）正是这个形态：Exo 的
           isPlaying() 在缓冲期照样 true、total_time 一直空着，1.2s 一到就被
           锁成「电台」（用户实测：缓冲中的音乐被标电台）⇒ 名册只挡住了
           取链快的歌，没挡住取链慢的。
           电台是 NAS index.json 的固定条目（名册来自 device status 的
           music.radioList，与播放推流同源同值）：名单查得到才是电台，
           查不到的一律不是 —— 宁可暂时显示「在线搜到的」，也不能把歌标成电台。 */
        try {
            PlaySong cur0 = song();
            if (cur0 != null && isRadioUrl(nz(cur0.getUrl()))) {
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 停播 / 换引擎时把电台记账清零（页面上不该再挂「直播」）。 */
    public static synchronized void forgetRadio() {
        sRadio = false;
        sRadioKey = "";
        sRadioCandTs = 0;
        sAdvanceTs = 0;
    }

    /* ------------------------------------------------------------------
     * 播放引擎切换（pl-1.6.24）：ExoPlayer 2.18 ↔ 原厂 MediaPlayer
     *
     * 引擎实例是 NetPlayerService.onCreate 里建的，而 :NetPlayer 进程一辈子
     * 只 onCreate 一次 ⇒ 运行时**换不了实例**，只能「写 flag + 重启那个进程」。
     *
     * 重启手法：killProcess 同 uid（system）的 :NetPlayer。它不是我们起的 ——
     * 是 EasyPlayer.bindService 拉起来的；进程一死，系统会把它重建并把新的
     * binder 重新交付给存活的客户端（实测：3~6 秒内自己回来）。
     *
     * 开关 = SharedPreferences「com.phicomm.speaker.player」里的 key r1_engine_mp
     *   （1 ⇒ 原厂 NetPlayer4）。不用 files/flag：实测 :NetPlayer 进程对主进程刚建的
     *   files/engine_mp 恒 exists()=false（同 uid、主进程 canRead=true，子进程却读不到），
     *   换成原厂自己的 SPHelper/PreferenceUtil 后两个进程口径一致。
     * ------------------------------------------------------------------ */
    private static final String ENG_KEY = "r1_engine_mp";

    public static String engine() {
        try {
            if (Boot.app == null) {
                return "exo";
            }
            return SPHelper.getIntValue(Boot.app, ENG_KEY, 0) != 0 ? "mp" : "exo";
        } catch (Throwable t) {
            return "exo";
        }
    }

    /** 排障用：把开关的真实读数摊开（切换后不生效时先看这个）。 */
    public static String engineInfo() {
        try {
            if (Boot.app == null) {
                return "app=null（Application 还没起来）";
            }
            int v = SPHelper.getIntValue(Boot.app, ENG_KEY, 0);
            return "SP com.phicomm.speaker.player/" + ENG_KEY + "=" + v;
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    public static synchronized String setEngine(String v) {
        String want = ("mp".equalsIgnoreCase(v) || "media".equalsIgnoreCase(v)
                || "mediaplayer".equalsIgnoreCase(v)) ? "mp" : "exo";
        try {
            if (Boot.app == null) {
                return "ERR 还没绑到 Application";
            }
            SPHelper.setIntValue(Boot.app, ENG_KEY, "mp".equals(want) ? 1 : 0);
        } catch (Throwable t) {
            return "ERR 写引擎开关失败：" + t;
        }
        int n = restartNetPlayer();
        forgetRadio();                 // pl-1.6.25：换引擎 = 换进程，旧结论作废
        return "OK 播放引擎 → " + ("mp".equals(want) ? "MediaPlayer（原厂）" : "ExoPlayer 2.18")
                + (n > 0 ? "，正在重启播放进程（约 5 秒）" : "，没找到播放进程（下次重启生效）");
    }

    /** 杀掉 :NetPlayer，让它重建 —— 引擎开关在它的 onCreate 里读。 */
    static int restartNetPlayer() {
        int n = 0;
        try {
            java.util.List<Integer> ps = Sys.pidsOf(":NetPlayer");
            for (Integer p : ps) {
                try {
                    android.os.Process.killProcess(p.intValue());
                    n++;
                } catch (Throwable t) {
                    Boot.log("kill :NetPlayer " + p + " 失败: " + t);
                }
            }
            Boot.log("重启 :NetPlayer x" + n);
        } catch (Throwable t) {
            Boot.log("restartNetPlayer: " + t);
        }
        return n;
    }

    public static int sort() {
        try {
            EasyPlayer p = ep();
            return p == null ? -1 : p.getPlayerSort();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean netOnline() {
        try {
            EasyPlayer p = ep();
            return p != null && p.isNetPlayerOnline();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean btOnline() {
        try {
            EasyPlayer p = ep();
            return p != null && p.isBluetoothPlayerOnline();
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- 音量：系统 STREAM_MUSIC ----------------
       原厂 player 走系统音乐流（实测 /api/set?vol 改的是 STREAM_MUSIC 的
       80000(spdif) 那一路，对它有效），所以这里直接调 AudioManager。 */

    public static int volMax() {
        try {
            AudioManager am = am();
            return am == null ? 15 : am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return 15;
        }
    }

    public static int vol() {
        try {
            AudioManager am = am();
            return am == null ? -1 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String setVol(int v) {
        try {
            AudioManager am = am();
            if (am == null) {
                return "ERR 拿不到 AudioManager";
            }
            int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (v < 0) {
                v = 0;
            }
            if (v > mx) {
                v = mx;
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            Boot.log("音量 -> " + v + "/" + mx);
            return "OK 音量 " + v + "/" + mx;
        } catch (Throwable t) {
            return "ERR 设音量失败：" + t;
        }
    }

    private static AudioManager am() {
        if (Boot.app == null) {
            return null;
        }
        return (AudioManager) Boot.app.getSystemService(Context.AUDIO_SERVICE);
    }

    /* ---------------- 队列 ---------------- */

    /**
     * ★ pl-1.1.5：用「player 当前实际在放的那一首」校正 curIdx。
     * 原厂 player 的自动连播、以及我们自己的 next()/prev()（走 playNext/playPre）
     * **都不会**经过 playIndex(i,true) ⇒ 钩子 noteIndex 不会触发，
     * curIdx 会一直停在推表那一刻的值（random 推的是 index=0 ⇒ 永远是 0），
     * 页面上「正在播放」的高亮就再也不动了。
     * 这里在每次出队列 / 出状态时按 itemId 反查一次（退化用 歌名+歌手），最准。
     */
    public static synchronized void syncCur() {
        try {
            if (queue.isEmpty()) {
                return;
            }
            PlaySong s = song();
            if (s == null) {
                return;
            }
            String id = nz(s.getItemId());
            String t = nz(s.getTitle());
            String a = nz(s.getArtist());
            if (id.length() == 0 && t.length() == 0) {
                return;
            }
            int hit = -1;
            if (id.length() > 0) {
                for (int i = 0; i < queue.size(); i++) {
                    PlaySong q = queue.get(i);
                    if (q != null && id.equals(nz(q.getItemId()))) { hit = i; break; }
                }
            }
            if (hit < 0 && t.length() > 0) {
                for (int i = 0; i < queue.size(); i++) {
                    PlaySong q = queue.get(i);
                    if (q == null) { continue; }
                    if (t.equals(nz(q.getTitle()))
                            && (a.length() == 0 || a.equals(nz(q.getArtist())))) { hit = i; break; }
                }
            }
            if (hit >= 0 && hit != curIdx) {
                curIdx = hit;
                Boot.log("syncCur: 当前曲 -> #" + (hit + 1) + " " + t);
            }
        } catch (Throwable t2) {
            Boot.log("syncCur: " + t2);
        }
    }

    public static synchronized JSONObject queueJson(int limit) {
        JSONObject o = new JSONObject();
        try {
            syncCur();
            JSONArray arr = new JSONArray();
            int n = queue.size();
            int lim = limit <= 0 ? n : Math.min(n, limit);
            // ★ pl-1.1.6：当前曲落在窗口外 ⇒ 把窗口挪过去（不然随机模式下
            //   当前曲常常是第 800 首，页面只拿到前 300 首，高亮/定位都找不到它）。
            int start = 0;
            if (limit > 0 && n > limit && curIdx >= limit) {
                start = curIdx - limit / 2;
                if (start < 0) {
                    start = 0;
                }
                if (start > n - limit) {
                    start = n - limit;
                }
            }
            for (int i = start; i < start + lim; i++) {
                PlaySong s = queue.get(i);
                JSONObject it = new JSONObject();
                it.put("n", i + 1);              // 真实曲库序号（1-based）
                it.put("title", s == null ? "" : nz(s.getTitle()));
                it.put("artist", s == null ? "" : nz(s.getArtist()));
                it.put("itemId", s == null ? "" : nz(s.getItemId()));
                /* ★ pl-1.6.42：带上地址 —— 队列行点 ☆ 收藏时要用它当「能直接播」的凭据
                   （不然收藏条目只剩歌名，在线歌回库反查常常查不回来）。 */
                it.put("url", s == null ? "" : nz(s.getUrl()));
                /* ★ pl-1.6.67：占位地址（/r/fav?k=）且还没解析成功 ⇒ miss。
                   页面把这一行标红，点它不再硬播（多半 404 跳走），而是切到音乐页
                   在线搜一遍让你自己挑。解析成功后 miss 自动撤（见 noteFavResolved）。 */
                String fk = favPendingKey(s == null ? "" : nz(s.getUrl()));
                /* ★ v1.3.231：占位地址默认**不红** —— 它只是「还没播到、待解析」。
                   只有「已尝试解析且确实没配上」（sFavFail 命中、且还没解析成功）才标红，
                   即「尝试匹配以后没匹配上才红」；纯在线歌播到时 fav_resolve 会去搜，
                   搜到就撤红、搜不到才红。 */
                boolean miss = fk != null && favFailed(fk) && favReal(fk) == null;
                /* ★ pl-1.6.127：节目行（/r/prog?aid=&ep=）同一套规则 —— 播到才解析，
                   解析不到就标红，点它走重链接，**永远不把占位行硬塞给播放器**。 */
                String[] pk = progPendingKey(s == null ? "" : nz(s.getUrl()));
                if (pk != null && progFailed(pk[0], pk[1])) {
                    miss = true;
                }
                /* ★ pl-1.6.128：把「失败过几次」一并给页面。页面点过「✕ 忽略红标」后
                   记的是当时的次数；用户再播一次还是失败 ⇒ 次数变大 ⇒ 忽略作废、重新标红。
                   （旧版只给布尔 miss，忽略了就再也红不回来 —— 用户实测报的 bug。） */
                int missN = 0;
                if (fk != null) {
                    missN = favFailN(fk);
                }
                if (pk != null) {
                    missN = progFailN(pk[0], pk[1]);
                }
                it.put("missN", missN);
                it.put("miss", miss);
                arr.put(it);
            }
            o.put("total", n);
            o.put("start", start);               // 窗口偏移（页面用它算高亮位置）
            o.put("cur", curIdx);
            o.put("ts", queueTs);
            o.put("list", arr);
        } catch (Throwable t) {
            Boot.log("queueJson: " + t);
        }
        return o;
    }

    /** 队列摘要（给 /api/status 用）：不带列表，避免上千首每轮都传。 */
    public static synchronized JSONObject queueBrief() {
        JSONObject o = new JSONObject();
        try {
            syncCur();
            o.put("total", queue.size());
            o.put("cur", curIdx);
            o.put("ts", queueTs);
        } catch (Throwable t) {
            // ignore
        }
        return o;
    }

    public static synchronized String titleAt(int i) {
        try {
            if (i >= 0 && i < queue.size() && queue.get(i) != null) {
                return nz(queue.get(i).getTitle());
            }
        } catch (Throwable t) {
            // ignore
        }
        return "?";
    }

    /**
     * ★ pl-1.6.124：当前（失败中的）曲目是不是有声书/节目行。
     * 是 ⇒ 引擎 HandlePlayError 不得 gotoNext（连着的故事，跳一集剧情就断档），
     * 改为原地停 + reportPlayEnd；不是（普通歌单）⇒ 保持原厂「失败跳下一首」。
     * 只读本地队列镜像（queue/curIdx），不碰 binder —— 引擎错误线程里调用零死锁风险。
     */
    public static synchronized boolean abNoSkipOnErr() {
        try {
            if (!queue.isEmpty() && curIdx >= 0 && curIdx < queue.size()) {
                PlaySong s = queue.get(curIdx);
                String t = (s == null) ? "" : nz(s.getTitle());
                if (t.length() > 0) {
                    boolean r = AB_EP_PAT.matcher(t).find();
                    Boot.log("abNoSkipOnErr[本进程队列] cur=" + curIdx + " " + t + " => " + r);
                    return r;
                }
            }
        } catch (Throwable t2) {
        }
        /* 引擎进程（NetPlayerService）里上面那份静态队列是空的 —— 读主进程写的标记。
           ★ pl-1.6.126：这一支不加，闸门从 pl-1.6.124 起就一直是 false（节目失败照跳）。 */
        boolean f = readAbFlag();
        Boot.log("abNoSkipOnErr[引擎进程] 队列不可用 ⇒ 标记 => " + f);
        return f;
    }

    /**
     * 列表循环（mode=2）自然播完最后一首时，是否原地停、不回卷到第 0 首。
     *
     * <p>原厂 {@code getNextPosition} 在队尾一律回卷：队列只有一首（语音点一首在线歌、
     * 点名一集故事）时就成了单曲循环，播完一遍又从头放。现在队尾播完即停；
     * 手动「下一首」不经过这里，照旧可以回到第一首。</p>
     *
     * <p>节目行（有声书）例外、保持回卷 —— device 的 tail-kick 靠「回卷」续拉下一批。
     * 节目判据复用 {@link #abNoSkipOnErr()}（引擎进程读主进程写的 r1_abnow 标记）。
     * 由引擎 HandlePlayCompleted / HandlePlayerBufferingCompleted 的 smali 闸门
     * （patch_list_end.py）调用。</p>
     */
    public static boolean stopAtListEnd(int mode, int pos, int size) {
        if (mode != 2 || size <= 0 || pos < size - 1) {
            return false;
        }
        boolean ab = abNoSkipOnErr();
        Boot.log("stopAtListEnd pos=" + pos + "/" + size + " 节目行=" + ab
                + (ab ? " ⇒ 保持回卷" : " ⇒ 队尾停"));
        return !ab;
    }

    private static String ok(boolean r, String what) {
        Boot.log(what + " => " + r);
        return (r ? "OK " : "ERR ") + what;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private PCtl() {
    }
}
