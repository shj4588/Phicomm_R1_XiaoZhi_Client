package cn.workbuddy.r1client;

import android.content.Context;
import android.content.SharedPreferences;   // v1.3.136：播放状态落盘（跨进程重启存活）
import android.media.AudioManager;          // v1.3.137：isMusicActive() 真状态判据
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;                  // v1.3.307：占位行 URL 解析（预填）
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;                    // v1.3.188：歌词/封面/rid 小缓存
import java.util.HashSet;                     // v1.3.231：favLocalCands 倒排索引去重
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;                         // v1.3.307：预填去重
import java.util.concurrent.atomic.AtomicBoolean;   // v1.3.307：预填单飞
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.3.86（jp2 v44）：音箱播放器 MCP 桥 —— 走 **DLNA**，不再走原厂消息总线。
 *
 * 为什么要改（2026-10-09 实测，详见 .workbuddy/memory/2026-10-09.md）：
 *   v1.3.85 走 PhicommMusicController -> MessageDispatchManager.sendMessage(4, subType, -1)
 *   消息**确实送达了 player**（logcat: <SentTo> pid:810 msg:{ what:4 arg1:3 }），
 *   但 player 的播放列表是空的：
 *     · /list -> {"playList":[]}
 *     · GetTransportInfo -> NO_MEDIA_PRESENT
 *   根因：魔改固件把音源换成了 mod 作者私服
 *     （MUSIC_SERVICE_CONFIG provider=default/endpoint 空、x-r1-provider=youtube、
 *      ASR_ENDPOINT=https://r1-py.thd.dpdns.org），歌单靠 device（小讯）登录私服拉取后
 *     推给 player。jp2 顶替 device 后这条链路断了 ⇒ 任何 play/pause 都是空转。
 *
 * 所以改成：MCP 自己带 URL，直接推给音箱的 DLNA 渲染器。
 *   · DMR：Phicomm_R1_CD6B（com.phicomm.speaker.player:DlnaPlayer 进程，Neptune/Platinum 栈）
 *   · ⚠️⚠️ **端口不是固定的**：实测 2026-10-09 同一个 player 进程先后 LISTEN 在
 *     1930 →（进程重启）→ 1502。写死端口 = player 一重启就废（这正是用户报的
 *     「以前 DLNA 能播，现在播不了」的根因）。
 *     ⇒ 必须在运行时**动态发现**：读 /proc/net/tcp 拿本机 LISTEN 端口，
 *       逐个 GET "/" 找返回 UPnP 设备描述且含 AVTransport 的那个，再从描述里
 *       解析 controlURL（形如 /AVTransport/98bb9936cd6b/control.xml）。
 *   · 实测：SetAVTransportURI -> Play -> GetTransportInfo 从 NO_MEDIA_PRESENT 变 PLAYING
 *   不依赖任何登录态 / 第三方服务器。
 *
 * 音源：NAS 上的音乐索引 JSON（默认 http://192.168.2.189:8003/music/index.json，
 * 可用 files/music_index 覆盖，内容写一行 URL）。格式见 R1/music_index.example.json：
 *   {"base":"http://.../music/",
 *    "tracks":[{"title":"晴天","artist":"周杰伦","file":"周杰伦/晴天.mp3"}],
 *    "radios":[{"name":"Hit FM","url":"http://..."}]}
 */
public final class PlayerBridge {

    private static final String TAG = "PlayerBridge";

    /** DLNA 服务在本机，走回环最稳（设备 IP 可能变），但**端口每次启动都变**，要动态发现。 */
    private static volatile String avtUrl;
    private static volatile String rcsUrl;

    /**
     * v1.3.179：默认指向合并后的媒体服务器 R1XiaoZhi_MediaServer（8100，
     * 一个容器一份 index.json 里装着本地 + 在线 + 电台）。
     * 以前这里指 r1-music 的 8010 —— 那个容器已经并进 8100 了。
     * 仍可用 files/music_index（一行或多行 URL）覆盖。
     */
    private static final String DEFAULT_INDEX_URL =
            "http://192.168.2.189:8100/index.json";

    /**
     * 一次推进 player 的曲目**硬上限**（只是防止 Binder 事务爆掉的安全阀）。
     *
     * v1.3.92 曾把软上限定死在 100 首（当时怕 Binder 事务爆），v1.3.113 用户要求
     * 「把整个音乐库放进播放列表」⇒ 默认改成**全库**：1185 首 × ~110B ≈ 130KB，
     * 相对 Binder 事务 1MB 缓冲仍有余量。想退回小歌单就在 files/playlist_max 写数字。
     */
    /**
     * ★ v1.3.241：业务上限取消，这里只剩**物理安全阀**（一次 Binder 事务 1MB）。
     *
     * <p>收藏导入是「占位地址 + 播到才拉源」，条数本身不带来任何网络/解析开销，
     * 所以不再按「2000 首」这种业务数字截断。真正卡住的是 {@link #pushPlaylist}
     * 里那一次 Parcel：实测 ~220B/首，3000 首 ≈ 660KB 已接近 1MB。
     * 那里改成按**字节预算**（{@link #BINDER_BUDGET}）缩条数，缩的时候会在
     * 返回文案里明说「本次只加载前 N 首」，不再静默丢歌。</p>
     */
    private static final int MAX_PLAYLIST_CAP = 5000;

    /**
     * v1.3.241：单次 push 给 player 的 JSON **字节预算**（不是条数）。
     * Binder 事务缓冲 1MB，留 30% 余量给 Parcel 头/其它字段。超过就按比例缩条数。
     */
    private static final int BINDER_BUDGET = 700000;

    /**
     * 当前生效的歌单上限：默认 MAX_PLAYLIST_CAP（=全库），可用 files/playlist_max
     * 覆盖（写一行数字，如 100）。v1.3.259：写 0 = 不限制（全库）。
     */
    private static int maxPlaylist() {
        int v = MAX_PLAYLIST_CAP;
        try {
            String s = XzBootstrap.readLineFile("playlist_max");
            if (s != null && s.length() > 0) {
                v = Integer.parseInt(s.trim());
                // v1.3.259：0（或负数）= 不限制，回到全库默认
                if (v <= 0) {
                    return MAX_PLAYLIST_CAP;
                }
            }
        } catch (Throwable t) {
            v = MAX_PLAYLIST_CAP;
        }
        if (v < 1) {
            v = 1;
        }
        if (v > MAX_PLAYLIST_CAP) {
            v = MAX_PLAYLIST_CAP;
        }
        return v;
    }

    /**
     * v1.3.180：**在线曲目上限** —— 所有「从 NAS 拉在线歌」的途径统一受它限制。
     *
     * <p>以前在线条数是各写各的常量：<code>play_artist</code> 补 20 首、
     * <code>random_online</code> 默认 100、页面「在线搜歌」默认 20 —— 三处互不相干，
     * 用户在设置里改一个数却只影响其中一条路，其余照旧。现在统一由
     * <code>files/online_max</code> 一个数管（默认 {@value #DEFAULT_ONLINE_MAX}）。</p>
     *
     * <p>为什么现在敢给 300：v1.3.179 起在线曲目走<b>惰性地址</b>（推列表不取链，
     * 真正播放那一刻才让 NAS 取链 + 流式转发），拉一批列表只是几次 HTTP，
     * 「多拿几首」不再有任何代价。上限取 300 是因为 mini-lx 的
     * <code>/index.json?n=</code> 与随机批本身就是 300 封顶，再大也拿不到更多。</p>
     */
    private static final int DEFAULT_ONLINE_MAX = 300;
    /** 在线上限的硬上边界（= NAS 一次能给的最多条数）。 */
    private static final int ONLINE_MAX_CAP = 300;

    public static int onlineMax() {
        int v = DEFAULT_ONLINE_MAX;
        try {
            String s = XzBootstrap.readLineFile("online_max");
            if (s != null && s.length() > 0) {
                v = Integer.parseInt(s.trim());
            }
        } catch (Throwable t) {
            v = DEFAULT_ONLINE_MAX;
        }
        if (v < 10) {
            v = 10;
        }
        if (v > ONLINE_MAX_CAP) {
            v = ONLINE_MAX_CAP;
        }
        return v;
    }

    /** v1.3.180：本地曲目上限（= {@link #maxPlaylist()}），供状态/控制台读取。 */
    public static int playlistMax() {
        return maxPlaylist();
    }

    /** v1.3.180：把在线上限写进 files/online_max（Web 控制台「在线曲目上限」用）。 */
    public static String setOnlineMax(int v) {
        if (v < 10) {
            v = 10;
        }
        if (v > ONLINE_MAX_CAP) {
            v = ONLINE_MAX_CAP;
        }
        boolean ok = XzBootstrap.writeLineFile("online_max", String.valueOf(v));
        return ok ? ("在线曲目上限 " + v + " 首（已保存，立即生效）")
                : "保存失败（写 files/online_max 失败）";
    }

    /** v1.3.180：把歌单上限写进 files/playlist_max（Web 控制台「本地曲目上限」用）。v1.3.259：0 = 不限制 */
    public static String setPlaylistMax(int v) {
        if (v <= 0) {
            boolean ok0 = XzBootstrap.writeLineFile("playlist_max", "0");
            return ok0 ? "本地曲目上限：不限制（已保存，立即生效）"
                    : "保存失败（写 files/playlist_max 失败）";
        }
        if (v > MAX_PLAYLIST_CAP) {
            v = MAX_PLAYLIST_CAP;
        }
        boolean ok = XzBootstrap.writeLineFile("playlist_max", String.valueOf(v));
        return ok ? ("本地曲目上限 " + v + " 首（已保存，立即生效）")
                : "保存失败（写 files/playlist_max 失败）";
    }

    private static Context app;
    private static String indexUrl = DEFAULT_INDEX_URL;
    /**
     * v1.3.102：多源索引。files/music_index 从「一行 URL」升级为「每行一个 URL」，
     * 逐个拉取后**合并成一个虚拟曲库**（NAS 本地 + 公网源同时可用）。
     * 单源失败只记该源的错误，不影响其它源 —— 公网源挂了本地照样能放。
     */
    private static final List<String> indexUrls = new ArrayList<String>();
    /** v1.3.99：DLNA 控制端口（每次 player 重启都变，web 控制台显示用） */
    private static volatile int dlnaPort = 0;

    private static String base = "";
    /**
     * 每条：{title, artist, file, url, album, duration, pic, via}
     * v1.3.102：末尾的 url 是**最终绝对地址** —— 索引条目里写了 "url" 字段就用它
     * （公网源必须走这个，因为它们没有 NAS 的 base），否则在加载时按该源的
     * base 拼好存进来。这样 urlOf() 不用再关心 base，多源才不会互相串味。
     * v1.3.178：第 8 位 via（"" = 本地曲库池，"online" = 在线搜/在线随机拉来的）。
     *   以前这条信息只活在内存里的 curVia 三个变量上，而**绝大多数推播路径
     *   （random / play_artist / play_index / 队列跳播）都只调 notePlaying(曲名)**，
     *   于是专辑/时长/来源留在上一次的值上 —— 用户看到的就是「上一首在线歌的专辑
     *   还挂在这一首下面」。把 via 跟曲目绑在一起，任何路径都能取到正确值。
     */
    private static final List<String[]> tracks = new ArrayList<String[]>();
    /** v1.3.178：在线 append 进 tracks 的条数（loadIndex 清零）。用来兜底防无限膨胀。 */
    private static volatile int onlineAppends = 0;
    /** 在线 append 上限：超过就回收最早收录的一半在线 append（v1.3.185，不再停摆等 reloadIndex）。 */
    private static final int ONLINE_APPEND_CAP = 400;
    /**
     * v1.3.180：play_artist 补多少首在线 —— <b>不再写死常量</b>，统一走
     * {@link #onlineMax()}（files/online_max，默认 300）。
     *
     * <p>历史：v1.3.178 定 12（逐条取链要几秒一首），v1.3.179 提到 20（惰性地址）。
     * 用户 2026-10-17 指出「在线只是拉列表、起播才取链，20 首太保守」⇒ 交给设置项。</p>
     */
    /**
     * v1.3.178：并行取链的并发数 —— **不是越大越好**，实测踩过坑：
     * <pre>
     *   并发 12：12 个请求同时砸向 NAS，上游（lxserver 中继 + 跨源）直接限流，
     *            半数请求 5s 超时 ⇒ 一首都没取到（"本地 5 首"，在线全丢）
     *   并发  6：两轮，约 5s，12/12 成功
     *   并发  4：约 3~12s（首次），12/12 成功 —— 最稳
     * </pre>
     * 取链是「每首一次 HTTP，内部还要跑中继 + 五级兜底」，上游扛不住突发；
     * 而 mini-lx 对拿到的直链有缓存，第二次起基本 0.2s 命中。
     * ⇒ 选 4 + 错峰启动 + 整体预算，宁可少几首也不要整批超时。
     */
    private static final int URL_CONC = 4;
    /** 每个线程启动前的小错峰（ms）—— 把「同时 12 个」摊成「每 250ms 一个」。 */
    private static final long URL_STAGGER = 250L;
    /**
     * v1.3.179：★ **不走惰性时**（老 NAS 没有 /r/ 路由）一次最多取几条链。
     *
     * <p>正常路径（NAS 支持 /r/）推 20 首是 0 次取链，这里根本用不上。
     * 只有回退路径才真的会对着上游开火 —— 那时 20 条并发请求 = 送上门被限流/封 IP。
     * 这是**兜底路径**，宁可少给几首（用户还能听本地的），也不能把上游惹毛：
     * 一旦被封，连"起播时取链"都全部哑火，得不偿失。</p>
     */
    private static final int LEGACY_URL_MAX = 8;
    /** 兜底路径的并发数（4 → 2，配合上面 250ms 错峰 = 实际每 250ms 最多 1 条上路）。 */
    private static final int LEGACY_URL_CONC = 2;
    /** 每条：{name, url}（电台本来就是绝对地址，天然支持公网源） */
    private static final List<String[]> radios = new ArrayList<String[]>();
    /** v1.3.102：每个源的加载结果，每条 {url, tracks数, radios数, 错误信息} */
    private static final List<String[]> srcStats = new ArrayList<String[]>();
    private static volatile boolean idxLoaded;
    private static String idxErr = "";

    private PlayerBridge() {
    }

    // ---------------- 原厂消息总线（"音箱自己播"的主通道） ----------------
    //
    // v1.3.88 新增。之前（v1.3.85）只发了空 play（subType=3），player 手里没歌所以空转；
    // 真正的入口是 **subType=1 playSpecifiedMusic**，它把一首/一组「带 URL 的曲目」推进
    // player 自己的播放引擎 —— 之后 player 就有播放列表了，next/prev/模式全都能用。
    //
    // 协议（反编译 PhicommMusicController.smali 得出）：
    //   MessageDispatchManager.sendMessage(int type, int subType, int arg2, Parcelable obj)
    //   type = 4 (MSG_TYPE_PLAYER)
    //   subType: 1=playSpecifiedMusic  2=pause  3=play  4=exit  5=playPrev  6=playNext  7=setMode
    //   obj    : ParcelableUtil.obtain(String)  —— 它是个通用包装器
    //            （writeToParcel 就是 dest.writeValue(val)），内容只有 title/url 两个字段：
    //            [{"title":"晴天","url":"http://..."}, ...]
    //   ⇒ 不需要碰 nluparser.scheme.MusicResult$Music，用反射直接拼 JSON 即可。

    private static Object msgMgr;
    private static volatile boolean msgTried;

    /** v1.3.94：本次进程是否已经往 player 里推过歌单（决定 play 要不要兜底成 random）。 */
    private static volatile boolean pushed;

    /** v1.3.100：最近一次设置的音量（0~100，-1=未设置过），仅供状态回显。 */
    private static volatile int lastVolume = -1;

    /**
     * v1.3.132：粗粒度「音乐在播」软状态 —— 只跟踪经过本类的播放动作。
     * 供 MusicResume 的「TTS 让路」判断用（见 {@link #isSoftPlaying()}）。
     */
    private static volatile boolean softPlaying = false;

    /**
     * v1.3.195：**网络直播流起播前的蓄水时间**（毫秒）。
     *
     * <p>历史（v1.3.194）：这一段是为**原厂 NuPlayer** 加的。当时的实测（同一 64k 电台）：
     * <pre>
     *   首脚 play 延迟   起播后 buffer low 停顿
     *   0.8 s            3.0 ~ 4.0 s      ← 老行为
     *   3.0 s            2.0 s
     *   4.0 s            2.0 s
     *   5.0 s            0（两遍都干净）
     * </pre>
     * 原因是 NuPlayer 收到重复 play(kick) 会 stop+reset 重开声卡，直播流没有 Content-Length
     * ⇒ 每次 reset 都把缓冲清零。给它 5 秒蓄水只是**躲避**这个 reset 窗口。</p>
     *
     * <p>★ v1.3.195 改回 0.8 s：player 侧换成 ExoPlayer（pl-1.6.22+）后，重复 kick 变成
     * 幂等的 {@code PlayWhenReady} 翻转，不再 reset / 不重开声卡
     * ⇒ 蓄水的前提条件消失，5 秒纯属白等（实测 Exo 下 buffer low 恒为 0）。</p>
     *
     * ★ 只对**网络流/电台**用这个值；本地曲目走 {@link #startDelay(int)}（几百毫秒）。
     * ★ 想现场调：往 files/kick_delay_ms 写一个数字覆盖（见 {@link #firstKickDelay}）。
     * ★ 若哪天换回原厂 player（无 Exo），把它改回 5000L。
     */
    private static final long NET_STREAM_KICK_MS = 800L;

    /**
     * v1.3.143：{@link #softPlaying} 的「新鲜度」—— 只有**本进程内真发生过播放动作**
     * （dispatch 的 play 族 / resumeMusic / kickPlay 推歌）才为 true。
     *
     * <p>为什么需要它：{@link #restorePlayState()} 从 SP 恢复的「在播」是**上一个进程**
     * 留下的结论，歌早就播完了它也照样写着 true（实测 22:13 恢复出「Topmodel 160s 前」、
     * 更早在播记录甚至挂着 832s 前的《The Tradition》）。拿这种过期结论去让路会连错两次：
     * ① 明明没在播却发 pause；② 说完话再 resume ⇒ **把上一曲凭空拽回来**。</p>
     *
     * <p>分工：</p>
     * <ul>
     *   <li>{@code softPlaying}（含恢复值）⇒ 只供 status / 小智问答显示「当前曲目」；</li>
     *   <li>{@code softFresh} ⇒ 才配当「让路」判据（另有 {@link #isMusicActive()} 兜底）。</li>
     * </ul>
     */
    private static volatile boolean softFresh = false;

    /**
     * v1.3.136：「当前曲目名」。来源有二 ——
     * <ol>
     *   <li>我们推的播放列表的第一首（{@link #pushPlaylist}）；</li>
     *   <li>DLNA {@code GetPositionInfo} 的 {@code TrackMetaData}（DIDL-Lite 里的
     *       {@code <dc:title>}）—— 这条能覆盖「不是我们推的播放」（DLNA 推送、原厂起播）。</li>
     * </ol>
     */
    private static volatile String curTitle = "";
    /** v1.3.175：当前曲目的「实际歌曲信息」——专辑/时长（翻唱/DJ 版与歌名不一致时看这里）。 */
    private static volatile String curAlbum = "";
    private static volatile String curDuration = "";
    /** v1.3.175：播放来源标记（"" = 曲库池，online = 在线搜到的）—— 告诉用户这版是不是原唱库里的。 */
    private static volatile String curVia = "";
    /** v1.3.188：当前曲目封面 URL（搜索结果 img / NAS {@code /api/pic}），页面直接当 {@code <img src>}。 */
    private static volatile String curPic = "";
    /** v1.3.136：DLNA 报告的真状态（PLAYING / PAUSED_PLAYBACK / NO_MEDIA_PRESENT …），"" = 还没查到 */
    private static volatile String dlnaState = "";
    /** v1.3.136：上次播放状态写盘的时间戳（0 = 从未写过） */
    private static volatile long playStateTs = 0;

    // ---------------- v1.3.163：自有播放队列（彻底脱离 8080 小飞 WS） ----------------
    //
    // 背景：web 播放页的「队列 / 正在播放 / 点播第 N 首 / 音量」原先全部走
    // ws://<ip>:8080 —— 那是**魔改 player（小飞）**自己开的 WS，原厂 player 没有。
    // 把 player 刷回原厂后 8080 永远连不上，页面就一直「正在等音箱播放服务（8080）…」。
    //
    // 实测（2026-10-16，原厂 /system/app/EchoService/EchoService.apk）：
    //   · 我们的 type=4 总线指令（1=playSpecified 3=play 2=pause 5/6=prev/next）**照单全收**，
    //     推 `100_local_MUSIC_4` 后 MultiMediaPlayer 真的 buffered→STATE_PLAYING；
    //   · player 会主动把「当前曲目」回推到 type=33554432（USER_DATA）总线，arg1=1，
    //     val=[{"singer":"…","songName":"…","music_id":"100_local_MUSIC_1149",…}]
    //     ⇒ 曲名/歌手/库内下标全都能拿到，而且比 8080 轮询更实时；
    //   · 我们 push 进去的 itemId 自带库内下标 ⇒ 队列和高亮都能自己重建。
    // ⇒ 缺的只剩「播放进度 / 总时长」（那个只上报原厂云 MQTT，本地没有通道）。

    /** 最近一次 push 进 player 的列表（tracks/radios 的下标序列，顺序即播放顺序）。 */
    private static final List<Integer> lastIdx = new ArrayList<Integer>();
    /** lastIdx 是电台列表还是歌曲列表。 */
    private static volatile boolean lastIsRadio = false;
    /** 当前曲目在 lastIdx 中的位置（0-based，-1 = 还没收到过总线事件）。 */
    private static volatile int curIdx = -1;
    /** 当前曲目的歌手（总线事件带的，自有链路之前拿不到）。 */
    private static volatile String curArtist = "";
    /** 最近一次收到总线曲目事件的时间戳（0 = 从未收到）。 */
    private static volatile long busSongTs = 0;
    /** v1.3.172：最近一次设的播放模式（1=随机 2=顺序 3=单曲，默认 2）。给 status 回显用。 */
    private static volatile int lastMode = 2;

    /**
     * v1.3.139：自定义音乐库总开关（默认开；{@code files/my_music_off} 存在 = 关）。
     *
     * <p>关掉后 {@code XiaoZhiWs.sendMcpToolsResult()} 上报<b>空工具表</b> ⇒ 模型
     * 看不到 {@code self.r1player.*}，自然退回小智服务器自带的音乐/电台。
     * Web 卡片上的手动按钮不受影响（那是调试通道，不是小智的视野）。</p>
     */
    private static volatile boolean myMusic = true;

    /** v1.3.139：自定义音乐库是否启用（给 MCP 工具表门禁用）。 */
    public static boolean isMyMusic() {
        return myMusic;
    }

    /** v1.3.139：Web 控制台切换，立即生效（下次唤醒建立新连接即按新工具表走）。 */
    public static void setMyMusic(boolean on) {
        myMusic = on;
        Log.i(TAG, "myMusic=" + on);
        SessionLog.i("music 自定义音乐库" + (on ? "已开启（小智用我们的曲库/电台）"
                : "已关闭（交给小智服务器自带音乐）"));
    }

    // v1.3.136：跨进程重启存活的播放状态（softPlaying/curTitle 以前是纯内存态，
    // device 进程一重启就丢，而音乐在 player 独立进程里照旧在放 ⇒ 小智"不知道歌在放"，
    // 用户问「现在放的什么 / 暂停」时模型只能答「播放列表为空」）。
    // 落盘到独立 SP（MODE_MULTI_PROCESS：寄生层跑在 device 主进程，:r1client 进程也要读）。
    private static final String PREF_PLAYER = "r1player";
    private static final String K_PLAYING = "playing";
    private static final String K_TITLE = "cur_title";
    /** v1.3.175：专辑/时长/来源随曲名一起落盘（重启后「正在播放」信息不丢）。 */
    private static final String K_ALBUM = "cur_album";
    private static final String K_DURATION = "cur_duration";
    private static final String K_VIA = "cur_via";
    private static final String K_TS = "play_ts";

    private static SharedPreferences playerPref() {
        return app.getSharedPreferences(PREF_PLAYER, Context.MODE_MULTI_PROCESS);
    }

    /** v1.3.136：记一次「开始播」（内存 + 落盘）。title 为 null 表示沿用旧曲名。 */
    private static void notePlaying(String title) {
        if (title != null && title.length() > 0) {
            curTitle = title;
            // v1.3.178 ★：曲名一换，上一首的专辑/时长/来源必须一起作废。
            //   以前这里只改 curTitle，三个「实际歌曲信息」字段留在上一次的值上，
            //   于是 random / play_artist / play_index / 切歌 这些只报曲名的路径，
            //   卡片上会一直挂着上一首在线歌的专辑 —— 用户原话：
            //   「之前放过《不能说的秘密》，现在放其他歌还是有个不能说的秘密显示在那里」。
            //   （《不能说的秘密》是专辑名，正是 curAlbum 的残留。）
            curAlbum = "";
            curDuration = "";
            curVia = "";
            curPic = "";   // v1.3.188：换曲 ⇒ 上一首的封面也必须作废（同专辑残留那个坑）
        }
        softPlaying = true;
        softFresh = true;      // v1.3.143：本进程内真播过 ⇒ 可作让路判据
        savePlayState(true);
    }

    /**
     * v1.3.196：专辑名洗白 —— 不是真专辑的「占位名」一律按「没有专辑」处理。
     *
     * <p>背景（用户原话）：「专辑那一条没有专辑信息的好像都会显示 billboard hot 100」。
     * 这个值不是我们造的，源头是本地 mp3 的 ID3 tag：从榜单合集下来的文件，
     * album 填的是榜单名而不是专辑名。实测 1288 首里 65 首命中
     * （"Billboard Hot 100" 14 首、"Billboard Hot Dance & Electronic Songs
     * (18.09.2021)" 50 首、带日期的另一版 1 首）。这些名字既不是专辑信息，
     * 也没有区分度 —— 65 首全叫同一个名字，显示出来只是噪音。</p>
     *
     * <p>判定刻意做得窄：只挡「榜单/合集/占位」这几类确定无疑的，
     * 真专辑名（哪怕带 Deluxe / Edition / 括号年份）一律放行。</p>
     *
     * @param a     原始专辑名（可为 null）
     * @param title 当前曲名（专辑名与曲名相同 ⇒ 多半是源站拿曲名顶的）
     * @return 洗白后的专辑名（可能是空串）
     */
    private static String sanitizeAlbum(String a, String title) {
        String s = (a == null) ? "" : a.trim();
        if (s.length() == 0) {
            return "";
        }
        if (title != null && s.equalsIgnoreCase(title.trim())) {
            return "";
        }
        if ("null".equalsIgnoreCase(s) || "none".equalsIgnoreCase(s)) {
            return "";
        }
        String low = s.toLowerCase();
        if (low.indexOf("billboard") >= 0) {
            return "";          // Billboard Hot 100 / Hot Dance & Electronic / The Hot 100
        }
        if (low.indexOf("hot 100") >= 0 || low.indexOf("top 100") >= 0) {
            return "";          // 各类「热门榜」
        }
        if (low.indexOf("various artists") >= 0 || low.indexOf("unknown album") >= 0) {
            return "";
        }
        if (low.indexOf("未知专辑") >= 0 || low.indexOf("群星") >= 0) {
            return "";
        }
        return s;
    }

    /**
     * v1.3.175：记一次「开始播」并带上**实际歌曲信息**（专辑/时长/来源）。
     *
     * <p>池内歌：album 来自 index.json 条目（duration/pic 元数据源没有，留空）；
     * 在线歌：NAS search 返回的 album/duration 透传。
     * via = "online" 时页面显示「（在线）」角标 —— 用户要求「播的是翻唱要能看到
     * 实际信息」，这张卡片就是答案：真实歌手 + 专辑 + 时长一目了然。</p>
     */
    private static void notePlayingFull(String title, String album, String duration, String via) {
        if (title != null && title.length() > 0) {
            curTitle = title;
        }
        curAlbum = sanitizeAlbum(album, curTitle);   // v1.3.196：占位专辑名一律按没有处理
        curDuration = (duration == null) ? "" : duration;
        curVia = (via == null) ? "" : via;
        softPlaying = true;
        softFresh = true;
        savePlayState(true);
    }

    /**
     * v1.3.178：按**曲库下标**记「正在播放」—— 曲名/专辑/时长/来源全部从 tracks
     * 那条记录里取，任何推播路径（random / play_artist / play_index / 队列跳播）
     * 都能拿到正确信息，不再依赖调用方手填。
     *
     * <p>这是「播放窗口残留上一首在线歌信息」的正解：信息跟着曲目走，
     * 而不是跟着「上一次谁写过它」走。</p>
     */
    private static void notePlayingTrack(int idx) {
        try {
            if (idx >= 0 && idx < tracks.size()) {
                String[] e = tracks.get(idx);
                // ★ curTitle 必须是**纯歌名**：replay（「再放一次刚才那首」）要靠它在
                //   库里反查，拼上歌手就查不到了；歌手另有 curArtist 字段（总线也会给）。
                if (e[0].length() > 0) {
                    curTitle = e[0];
                }
                if (e.length > 1) {
                    curArtist = e[1];
                }
                notePlayingFull(null,
                        e.length > 4 ? e[4] : "",
                        e.length > 5 ? e[5] : "",
                        e.length > 7 ? e[7] : "");
                // v1.3.188：封面跟着曲目走（搜索结果带 img；池歌/本地没有 ⇒ 异步补一次
                // /api/pic，取不到就让它空着，页面回落成转碟）。
                String p = (e.length > 6 && e[6] != null) ? e[6] : "";
                if ("null".equals(p)) p = "";
                curPic = p;
                if (p.length() == 0) {
                    fetchPicAsync(e[0], e[1]);
                }
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "notePlayingTrack: " + t);
        }
        curAlbum = "";
        curDuration = "";
        curVia = "";
        notePlaying("");
    }

    /**
     * v1.3.178：总线回推「当前曲目」时，用**库里那条记录**补上专辑/时长/来源；
     * 曲名与歌手仍以总线给的为准（player 报的才是真正播出去的那一条）。
     *
     * <p>为什么不能直接用 {@link #notePlaying(String)}：那条为了治「残留上一首专辑」
     * 会在换曲名时清空专辑/时长/来源。而总线事件是在起播约 1 秒后必到的 ——
     * 于是刚写好的实际信息被它抹掉，卡片上又只剩一个曲名。实测就是这个现象。</p>
     */
    private static void notePlayingBus(String title, String singer, int trackIdx) {
        if (trackIdx >= 0 && trackIdx < tracks.size()) {
            String[] e = tracks.get(trackIdx);
            curAlbum = sanitizeAlbum(e.length > 4 ? e[4] : "", curTitle);   // v1.3.196
            curDuration = e.length > 5 ? e[5] : "";
            curVia = e.length > 7 ? e[7] : "";
            /* v1.3.189：封面也必须跟着这一首走。以前这块只改曲名/歌手/专辑，
               curPic 留着上一首的 URL ⇒ status 里「曲名已经是新的、封面还是旧的」，
               要等 fetchPicAsync（约 10s）或下一次 notePlayingTrack 才纠正。
               实测切歌 3s 后 curPic 仍是上一张（页面因此挂着旧封面）。 */
            String p = (e.length > 6 && e[6] != null) ? e[6] : "";
            if ("null".equals(p)) p = "";
            curPic = p;
        } else {
            curAlbum = "";
            curDuration = "";
            curVia = "";
            curPic = "";
        }
        /* v1.3.189：库里这首没存封面 ⇒ 异步补一次（跟 notePlayingTrack 一致） */
        if (curPic.length() == 0 && curTitle.length() > 0) {
            fetchPicAsync(curTitle, curArtist);
        }
        if (title != null && title.length() > 0) {
            curTitle = title;
        }
        if (singer != null) {
            curArtist = singer;
        }
        softPlaying = true;
        softFresh = true;
        savePlayState(true);
    }

    /** v1.3.136：记一次「停/暂停」（内存 + 落盘）。 */
    private static void noteStopped() {
        softPlaying = false;
        softFresh = false;     // v1.3.143
        savePlayState(false);
    }

    private static void savePlayState(boolean playing) {
        try {
            playStateTs = System.currentTimeMillis();
            SharedPreferences.Editor e = playerPref().edit();
            e.putBoolean(K_PLAYING, playing);
            e.putString(K_TITLE, curTitle);
            e.putString(K_ALBUM, curAlbum);
            e.putString(K_DURATION, curDuration);
            e.putString(K_VIA, curVia);
            e.putLong(K_TS, playStateTs);
            e.commit();     // 同步写：force-stop / 掉电前也要留下结论
        } catch (Throwable t) {
            Log.w(TAG, "savePlayState: " + t);
        }
    }

    /**
     * v1.3.136：启动时把「上次是不是还在播」读回来 —— 解决「小智启动之前就在放歌」
     * 时小智一问三不知的问题。之后由 warm-up 的 DLNA 查询校正（真状态优先）。
     */
    private static void restorePlayState() {
        try {
            SharedPreferences sp = playerPref();
            boolean p = sp.getBoolean(K_PLAYING, false);
            curTitle = sp.getString(K_TITLE, "");
            curAlbum = sp.getString(K_ALBUM, "");
            curDuration = sp.getString(K_DURATION, "");
            curVia = sp.getString(K_VIA, "");
            playStateTs = sp.getLong(K_TS, 0);
            if (p) {
                long age = System.currentTimeMillis() - playStateTs;
                if (age > 24L * 3600L * 1000L) {
                    // 超过一天就不敢认了（多半早播完了/被别处停了），按已停止处理，
                    // 免得让路逻辑拿着过期结论去「无中生有」地恢复。
                    SessionLog.i("music play-state: 上次记录太旧（"
                            + (age / 3600000L) + "h 前），按已停止处理");
                    savePlayState(false);
                } else {
                    softPlaying = true;
                    // v1.3.143：恢复值**不算新鲜** —— 只能给 status / 小智问答显示曲名，
                    // 不能拿去让路（歌可能早播完了；让路靠唤醒瞬间 isMusicActive() 判）。
                    softFresh = false;
                    SessionLog.i("music play-state restored: 上次记录还在播「" + curTitle
                            + "」（" + (age / 1000L)
                            + "s 前，重启后内存态已丢，先按在播处理"
                            + "；★ 恢复值不作让路判据，仅用于显示）");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "restorePlayState: " + t);
        }
    }

    /** v1.3.136：给 Web 控制台 / MCP status 用的「当前曲目名」（可能为空串）。 */
    public static String curTitle() {
        return curTitle == null ? "" : curTitle;
    }

    /** v1.3.136：DLNA 报告的真状态（"" = 尚未查询）。 */
    public static String dlnaState() {
        return dlnaState == null ? "" : dlnaState;
    }

    /**
     * v1.3.137：★「到底有没有音乐在播」的真判据 —— {@code AudioManager.isMusicActive()}。
     *
     * <p>它问的是 AudioFlinger 里有没有活跃的 STREAM_MUSIC 音轨，**覆盖所有播放路径**
     * （我们推的歌单、原厂起的播放、DLNA 推送的），跟「谁起的播」无关。
     *
     * <p>为什么不再信 DLNA：2026-10-13 实测 —— 音箱明明在播《The Tradition》
     * （本地记录 + 耳朵都确认），DLNA {@code GetTransportInfo} 仍然报
     * {@code NO_MEDIA_PRESENT}、{@code GetPositionInfo} 报「第 0 首 时长 00:00:00」。
     * ⇒ **DLNA 只是外部控制通道，它的状态机跟 player 自己的播放引擎不同步**，
     * 用它判「在不在播」必然恒阴性（v1.3.133~135 把它当"真状态兜底"是设计错误）。
     *
     * <p>⚠️ 使用时机很关键：**必须在小智自己的 TTS 开播之前**问。TTS 播的也是
     * STREAM_MUSIC，一旦开播这个函数就恒 true，会把自己当成音乐。
     * 所以由 {@code MusicResume.deferPause()} 在唤醒瞬间（TTS 还没起来时）采一次。
     */
    public static boolean isMusicActive() {
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            boolean a = am != null && am.isMusicActive();
            Log.i(TAG, "isMusicActive -> " + a);
            return a;
        } catch (Throwable t) {
            Log.w(TAG, "isMusicActive failed: " + t);
            return false;
        }
    }

    private static synchronized boolean msgReady() {
        if (msgTried) {
            return msgMgr != null;
        }
        msgTried = true;
        try {
            Class<?> mm = Class.forName(
                    "com.phicomm.speaker.device.utils.PhicommMessageManager");
            msgMgr = mm.getMethod("messageCenter", Context.class).invoke(null, app);
            Log.i(TAG, "msgcenter ok: " + msgMgr);
            SessionLog.i("msgcenter ready");
        } catch (Throwable t) {
            Log.w(TAG, "msgcenter unavailable: " + t);
            SessionLog.i("msgcenter FAIL: " + t);
            msgMgr = null;
        }
        return msgMgr != null;
    }

    /** 发一条 MSG_TYPE_PLAYER 消息。json 为 null 时 obj 传 null（原厂控制指令就是这么发的）。 */
    private static boolean msg(int subType, String json) {
        return msg(subType, json, -1);
    }

    /**
     * v1.3.119：带 arg2 的版本 —— setMode(7) 要把模式号放在 arg2 里。
     * 实测 player 默认停在 mPlayMode:1（随机），说「下一首」会从第 1 个台跳到
     * 第 8 个台，对「把电台排成一张列表挨个听」来说没有意义。
     */
    private static boolean msg(int subType, String json, int arg2) {
        if (!msgReady()) {
            return false;
        }
        try {
            Object p = null;
            if (json != null) {
                Class<?> pu = Class.forName("android.os.ParcelableUtil");
                p = pu.getMethod("obtain", String.class).invoke(null, json);
            }
            msgMgr.getClass()
                    .getMethod("sendMessage", int.class, int.class, int.class,
                            android.os.Parcelable.class)
                    .invoke(msgMgr, Integer.valueOf(4), Integer.valueOf(subType),
                            Integer.valueOf(arg2), p);
            Log.i(TAG, "msg sent subType=" + subType + " arg2=" + arg2
                    + (json == null ? "" : (" len=" + json.length())));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "msg send subType=" + subType + ": " + t);
            return false;
        }
    }

    /**
     * v1.3.119：设播放模式（subType=7，模式号走 arg2）。
     *
     * <p>★ v1.3.172 更正 —— 以前注释写的「0=顺序 1=随机」是猜的，**是错的**。
     * 反编译原厂 player 坐实的真值（{@code util/Constant}）：</p>
     * <pre>
     *   PLAY_MODE_LIST_SHUFFLE = 1   列表随机
     *   PLAY_MODE_LIST_CYCLE   = 2   列表循环（= 我们说的「顺序播放」），也是 PLAY_MODE_DEFAULT
     *   PLAY_MODE_SINGLE_CYCLE = 3   单曲循环
     * </pre>
     * 而且 {@code AbsPlayer.setPlayMode()} 开头就 {@code if (mode != 1 && != 2 && != 3)
     * → 打 "setPlayMode error, mode = N" 并 return false} —— **0 根本不收**。
     * 这就是页面上「顺序播放」以前点了没反应的根因（我们一直发 0）。
     */
    private static boolean setPlayMode(int mode) {
        // 越界一律校正成默认的列表循环，别把 0 之类的值送上去白挨一句 error
        if (mode != 1 && mode != 3) {
            mode = 2;
        }
        lastMode = mode;
        boolean ok = msg(7, null, mode);
        SessionLog.i("set play mode " + mode + "（1=随机 2=顺序 3=单曲）: "
                + (ok ? "sent" : "msgcenter FAIL"));
        return ok;
    }

    /**
     * 只放一首（play_url / 电台用）。
     *
     * v1.3.91：整体结构从「裸数组」改成 player 真正认的 PlayRequestInfo：
     *   {"index":0,"itemList":[{"itemId":"...","title":"...","url":"...","artist":"..."}]}
     * 依据：player dex 里硬编码的样例串
     *   {"index":0,"itemList":[{"itemId":"100_kw_MUSIC_15198210","title":"爱要怎么说出口"}, ...]}
     * 以及 PlaySong.toString() 的字段拼接片段（itemId/title/url/artist/duration/lyric/imgUrl）。
     * v1.3.90 发裸数组时 player 报：
     *   parsePlayRequetJson parse json error: Expected BEGIN_OBJECT but was BEGIN_ARRAY
     */
    private static String oneJson(String title, String url) {
        return oneJson(title, url, "");
    }

    private static String oneJson(String title, String url, String artist) {
        try {
            JSONArray arr = new JSONArray();
            arr.put(song("local_1", title, url, artist));
            JSONObject root = new JSONObject();
            root.put("index", 0);
            root.put("itemList", arr);
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "oneJson: " + t);
            return "{\"index\":0,\"itemList\":[]}";
        }
    }

    /**
     * v1.3.93：补一脚「真正开始播」。
     *
     * 现象（用户原话）：「小智好像不会播放只会换歌，播放是我在魔改 web 里点了才播的」。
     * 取证：发完 subType=1 后 player 的状态机停在
     *   MEDIA_PREPARING -> BUFFERING_END -> HandlePlayerPrepared（PlayState 7）
     * 但 **没有 MEDIA_PLAYING**，AudioFlinger 显示 `4 Tracks of which 0 are active`，
     * 并反复 `BUFFER TIMEOUT: remove(4096) from active list`（buffer underrun）。
     * ⇒ 魔改 player 收到 playSpecifiedMusic 后只「换歌 + prepare」，**不会自动 start**，
     *   必须再发一条 subType=3 (play) 才真正出声。
     *
     * 延迟是必须的：太快 player 还没 prepare 完，play 会被丢。600ms 实测够。
     */
    private static void kickPlay(final String why) {
        kickPlay(why, 600);
    }

    /**
     * @param delayMs 换歌（next/prev）要比首次播歌等更久 —— player 要重新 prepare 下一首，
     *                600ms 不够（实测会被丢），用 1200ms。
     */
    private static void kickPlay(final String why, final long delayMs) {
        kickPlay(why, delayMs, 1);
    }

    /**
     * @param times 发几次 play。换歌场景用 2 次（1200ms + 再 1000ms）—— player 换歌
     *              prepare 耗时不定，单次可能落在它还没准备好时。play 本身幂等，
     *              已经在播时重复发无副作用。
     */
    /**
     * ★★★ v1.3.192（2026-10-19 实锤）：这里必须**能早退**。
     *
     * <p>旧注释写的「play 幂等，已经在播时重复发无副作用」是**错的**，实测恰恰相反：
     * 每一条 play 都会让 player 走一遍 {@code stopping → startInternal}，
     * 期间 {@code pcm_close card 0/2/3} 重开声卡，把刚起步的 audio queue 打断 ——
     * 电台起播那 4.8 秒里连发 5 次 ⇒ queue 反复见底 ⇒ NuPlayer 报
     * {@code buffer low, pausing...} ⇒ **整整停 4.001 秒**再 resume。日志：
     *
     * <pre>
     * 13:26:23.995 kick #1   13:26:24.283 AudioSink open / pcm_close → start_output_stream
     * 13:26:24.999 kick #2   13:26:26.003 kick #3   13:26:27.005 kick #4   13:26:28.007 kick #5
     * 13:26:29.269 NuPlayer: buffer low, pausing...
     * 13:26:33.270 NuPlayer: buffer ready, resuming...      ← 4.001 s
     * </pre>
     *
     * <p>对照：同一 URL 起播只发 1 次 play ⇒ 一次 {@code buffer low} 都不出。
     * ★ 此前以为是 device 进程的 CPU/优先级抢走了调度（usc_* 常驻 107%），
     *   已用「进程内把 usc_* 的 nice 从 -19/-8 降到 10」证伪：nice 全程生效，
     *   4.001 s 一秒不少 ⇒ 真凶是重复 kick，不是 CPU。
     *
     * @param times 上限。发完每条 play 后等最多 1.2s 看 AudioFlinger 里有没有活跃音轨，
     *              **已经在出声就立刻收手**（正常情况下第 1 次就满足 ⇒ 只发 1 条）。
     *              真没起来才补下一条（所以 times 只是兜底上限，正常跑不满）。
     */
    private static void kickPlay(final String why, final long delayMs, final int times) {
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < times; i++) {
                    try {
                        Thread.sleep(i == 0 ? firstKickDelay(delayMs) : 1000);
                    } catch (Throwable t) {
                        // ignore
                    }
                    boolean ok = msg(3, null);
                    if (ok) {
                        softPlaying = true;   // v1.3.132：补发的 play 也说明在播了
                    }
                    SessionLog.i("kick play (" + why + ") #" + (i + 1) + ": "
                            + (ok ? "sent" : "msgcenter FAIL"));
                    // ★ 起播成功就别再补：每补一脚都要重开一次声卡。
                    if (musicActiveWithin(1200)) {
                        SessionLog.i("kick play (" + why + "): #" + (i + 1)
                                + " 后已在出声 ⇒ 停止补踢（避免重开声卡见底）");
                        break;
                    }
                }
            }
        }, "pb-kick").start();
    }

    /**
     * v1.3.193：首脚 play 之前的等待时间，**可用 flag 文件覆写**（`files/kick_delay_ms`）。
     *
     * <p>为什么需要：直播流起播后约 5 秒还会再停 3 秒（NuPlayer {@code buffer low}）——
     * 重复补踢已由 v1.3.192 修掉，剩下这段是**纯粹的补缓冲**：
     * 同一 URL 多次实测为 4.001 / 4.001 / 3.000 / 2.998 秒 ⇒ 不是固定计时器，
     * 而是「把 resume 水位填满」要花的时间。
     * ⇒ 假设：让它先攒一会儿再开闸，就能把这段停顿吸收掉（待实测）。
     *
     * @param def 各方自己算的默认延迟（曲目数多则更久，见 {@link #startDelay}）
     */
    private static long firstKickDelay(long def) {
        try {
            File f = new File(app.getFilesDir(), "kick_delay_ms");
            if (f.exists()) {
                long v = Long.parseLong(readFile(f).trim());
                if (v >= 0) {
                    return v;
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return def;
    }

    /**
     * v1.3.192：在这个时间窗里问「AudioFlinger 有没有活跃音乐音轨」。
     * 走的还是 {@link #isMusicActive()} 那套判据，但①不刷 logcat，②轮询（比单次准）。
     *
     * <p>⚠️ 判据与 TTS 同源（都是 STREAM_MUSIC）—— 起播瞬间一般不会有 TTS，
     * 万一有，表现为「少补一脚」（最多影响起播速度，不会卡死）。
     */
    private static boolean musicActiveWithin(long windowMs) {
        long end = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(300);
            } catch (Throwable t) {
                // ignore
            }
            try {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                if (am != null && am.isMusicActive()) {
                    return true;
                }
            } catch (Throwable t) {
                // ignore
            }
        }
        return false;
    }

    /** 单个 PlaySong 条目。itemId 用 100_local_MUSIC_<n> 模仿原厂酷我 id 的形态。 */
    private static JSONObject song(String itemId, String title, String url, String artist)
            throws Exception {
        return song(itemId, title, url, artist, "");
    }

    /**
     * ★ v1.3.350：**入队即盖章** —— album 位写「这一项属于哪本书」。
     *
     * <p>事故（实测）：R1 语音点播在线书《仙逆》，服务端一条记录都没有。
     * 队列项里本来就没写书名（旧 {@code song()} 只给 title/url/artist），而
     * {@link #queueItemBook} 的兜底是拿队列 URL 去 tracks 表**全等**比对 ——
     * 队列 URL 经 {@link #withDevIdentity} 追加了 {@code &dev=…&devName=…}，
     * tracks[3] 存的是原始地址 ⇒ 永远不相等 ⇒ 书名空 ⇒ 上报侧按
     * 「认不出就不报」直接放弃。</p>
     *
     * <p>而 device 自己推书时 {@code tracks[4]} 就是书名（{@code abQueueBook} /
     * {@code abQueueProgLazy} 都写 book），只是推给 player 时丢掉了。这里补上：
     * 消费方（上报、网页队列、下一曲记忆）只读不算。</p>
     */
    private static JSONObject song(String itemId, String title, String url, String artist,
            String album) throws Exception {
        JSONObject o = new JSONObject();
        o.put("itemId", itemId);
        o.put("title", title);
        o.put("url", withDevIdentity(url));   // ★ v1.3.346：拉流 URL 带 dev 设备身份
        o.put("artist", artist);
        if (album != null && album.trim().length() > 0) {
            o.put("album", album.trim());     // 原厂 PlaySong.album ⇒ /api/queue 的 album 位
        }
        return o;
    }

    /**
     * ★ v1.3.346：给指向本机 NAS 的惰性拉流地址（/r/ /m/）挂设备身份参数，
     *   让服务端 devIdOf 认领——R1 的 MediaPlayer 拉流带不了 X-Dev-Mac 头，
     *   docker 端口映射下全落进「网关 IP / 网页 / 未报 MAC 的设备」桶。
     *   URL 里已有 ?（title/artist/k= 等）时用 &；外部电台/直链绝对地址不含
     *   /r/ 或 /m/ 路径，原样返回，绝不给第三方源带我们的 MAC。
     */
    private static String withDevIdentity(String url) {
        if (url == null || url.length() == 0) { return url; }
        boolean lazy = url.contains("/r/") || url.contains("/m/");
        if (!lazy) { return url; }
        String mac = mac12();
        if (mac.length() < 8) { return url; }   // 读不到 MAC 就降级，服务端退 IP
        String dn = "R1-" + mac.substring(Math.max(0, mac.length() - 4));
        String sep = url.indexOf('?') >= 0 ? "&" : "?";
        return url + sep + "dev=" + mac + "&devName=" + dn;
    }

    /**
     * v1.3.113：三条「拼歌单」路径统一成**先算索引序列、再统一推送** ——
     * 减半重试、上限裁剪、日志只写一份，不用在每个 op 里各写一遍。
     *
     * 全库洗牌后的索引序列（「随便放点音乐」用）。
     */
    private static List<Integer> shuffledIdx() {
        int n = tracks.size();
        int[] a = shuffled(n);
        List<Integer> out = new ArrayList<Integer>(n);
        for (int i = 0; i < n; i++) {
            out.add(Integer.valueOf(a[i]));
        }
        return out;
    }

    /**
     * 命中的那首排第一，后面跟洗过牌的其余曲目 —— player 拿到的是**完整播放列表**，
     * 播完自动续、next/prev 也才有意义（v1.3.92 起就是这个策略，只是上限改成全库）。
     */
    private static List<Integer> hitFirstIdx(int hit) {
        List<Integer> out = new ArrayList<Integer>();
        int n = tracks.size();
        if (n == 0) {
            return out;
        }
        if (hit >= 0 && hit < n) {
            out.add(Integer.valueOf(hit));
        }
        int[] a = shuffled(n);
        for (int j = 0; j < n; j++) {
            if (a[j] != hit) {
                out.add(Integer.valueOf(a[j]));
            }
        }
        return out;
    }

    /**
     * v1.3.179：命中那首排第一，后面只跟**在线段**（[from, n)）的洗牌结果。
     *
     * <p>用途：op=play_rand_at（在在线随机批里点一首）。合并后本地曲目和在线池
     * 挤在同一份 index.json 里，全推会把本地那 1188 首也塞进 player 的列表 ——
     * 用户只是点了一首在线歌，不该顺带整理整个曲库。</p>
     */
    private static List<Integer> hitFirstIdx(int hit, int from) {
        List<Integer> out = new ArrayList<Integer>();
        int n = tracks.size();
        if (n == 0) {
            return out;
        }
        int a0 = from > 0 ? from : 0;
        if (hit >= 0 && hit < n) {
            out.add(Integer.valueOf(hit));
        }
        int[] a = shuffled(n);
        for (int j = 0; j < n; j++) {
            if (a[j] != hit && a[j] >= a0) {
                out.add(Integer.valueOf(a[j]));
            }
        }
        return out;
    }

    /**
     * v1.3.114：全库洗牌后按需只取前 cnt 首（cnt &lt;= 0 = 整个曲库）。
     * 「随机放 5 首」走的就是这里 —— 列表真的只有 5 首，播完这 5 首就结束。
     */
    private static List<Integer> randomIdx(int cnt) {
        /* ★ v1.3.279：随机音乐入口剔除有声书/节目 —— 收藏剧集行（slot[7]="fav"）
           和 ab_* 整部入列行（slot[7]="program"）都在 tracks 里，不滤的话
           「随机播放音乐」会播到《修罗武神 第N集》这类节目。 */
        List<Integer> idxs = shuffledIdx();
        List<Integer> out = new ArrayList<Integer>(idxs.size());
        synchronized (tracks) {
            for (int i = 0; i < idxs.size(); i++) {
                int ix = idxs.get(i).intValue();
                if (ix >= 0 && ix < tracks.size() && !isProgramRow(tracks.get(ix))) {
                    out.add(Integer.valueOf(ix));
                }
            }
        }
        if (cnt > 0 && out.size() > cnt) {
            return new ArrayList<Integer>(out.subList(0, cnt));
        }
        return out;
    }

    /** 按给定索引序列拼 playSpecifiedMusic 的 JSON（limit 条截断，从第 0 首开始播）。 */
    private static String buildJson(List<Integer> idxs, int limit) {
        return buildJson(idxs, limit, 0);
    }

    /**
     * v1.3.163：带起始下标的版本 —— {@code startIndex} 直接写进 JSON 的 "index"，
     * player 收到后应从这一首开始播（队列内跳转，不用把目标挪到第一再重推）。
     *
     * <p>⚠️ 魔改 player 实测不认 index>0（仍从第 0 首开始），所以 {@link #playAt(int)}
     * 里做了兜底：跳完 1.2s 后用总线回推的曲名校验，不一致就把目标顶到第一重推一次。</p>
     */
    private static String buildJson(List<Integer> idxs, int limit, int startIndex) {
        JSONArray arr = new JSONArray();
        try {
            int n = tracks.size();
            for (int j = 0; j < idxs.size() && arr.length() < limit; j++) {
                int i = idxs.get(j).intValue();
                if (i < 0 || i >= n) {
                    continue;
                }
                String[] e = tracks.get(i);
                arr.put(song("100_local_MUSIC_" + i, e[0], urlOf(e),
                        (e.length > 1) ? e[1] : "",
                        /* ★ v1.3.350：tracks[4] 就是书名（abQueueBook / abQueueProgLazy 写的），
                           必须随队列项一起推给 player，否则 device 侧认不出「在播哪本书」。 */
                        (e.length > 4) ? e[4] : ""));
            }
            JSONObject root = new JSONObject();
            root.put("index", startIndex);
            root.put("itemList", arr);
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "buildJson: " + t);
        }
        return "{\"index\":0,\"itemList\":[]}";
    }

    /**
     * v1.3.119：电台也能拼成一张播放列表（「把所有电台加进播放列表」）。
     *
     * 电台按 radios.json 的顺序排（不洗牌 —— 顺序是可预期的，用户心里有数）；
     * start >= 0 时把那个台顶到第一（命中台优先，跟歌曲的 hitFirstIdx 一个思路）。
     */
    private static List<Integer> radioIdx(int start) {
        int n = radios.size();
        List<Integer> out = new ArrayList<Integer>(n);
        if (start >= 0 && start < n) {
            out.add(Integer.valueOf(start));
        }
        for (int i = 0; i < n; i++) {
            if (i != start) {
                out.add(Integer.valueOf(i));
            }
        }
        return out;
    }

    /** 电台版 buildJson（v1.3.119）：条目来自 radios 而不是 tracks。 */
    private static String buildRadioJson(List<Integer> idxs, int limit) {
        return buildRadioJson(idxs, limit, 0);
    }

    /** v1.3.163：电台版带起始下标。 */
    private static String buildRadioJson(List<Integer> idxs, int limit, int startIndex) {
        JSONArray arr = new JSONArray();
        try {
            int n = radios.size();
            for (int j = 0; j < idxs.size() && arr.length() < limit; j++) {
                int i = idxs.get(j).intValue();
                if (i < 0 || i >= n) {
                    continue;
                }
                String[] r = radios.get(i);
                arr.put(song("100_radio_" + i, r[0], r[1], "电台"));
            }
            JSONObject root = new JSONObject();
            root.put("index", startIndex);
            root.put("itemList", arr);
            return root.toString();
        } catch (Throwable t) {
            Log.w(TAG, "buildRadioJson: " + t);
        }
        return "{\"index\":0,\"itemList\":[]}";
    }

    /**
     * 把歌单推进 player（subType=1）。@return **实际推进去的条数**，0 = 失败。
     *
     * v1.3.113：全库上千首的 JSON 有上百 KB。Binder 事务缓冲是**每进程 1MB 共享**的
     * （不是一次事务独占），内存紧张/并发事务多时 writeValue 会失败。这里做
     * **减半重试**：第一次推不进去就砍一半再推，最多 4 次 —— 宁可少推几首，
     * 也不能一首都推不进去（v1.3.112 及以前推不动就直接返回 ERR）。
     *
     * v1.3.119：抽出 radio 参数，歌曲和电台共用这套减半重试。
     */
    private static int pushSongs(String why, List<Integer> idxs) {
        return pushPlaylist(why, idxs, false);
    }

    /** v1.3.119：把所有电台排进 player 自己的播放列表。 */
    private static int pushRadios(String why, int start) {
        return pushPlaylist(why, radioIdx(start), true);
    }

    /**
     * v1.3.136：从 DLNA {@code GetPositionInfo} 的 {@code TrackMetaData}
     * （DIDL-Lite 片段）里抠出 {@code <dc:title>}。
     */
    private static String didlTitle(String md) {
        if (md == null || md.length() == 0) {
            return "";
        }
        try {
            int a = md.indexOf("<dc:title");
            if (a < 0) {
                return "";
            }
            a = md.indexOf('>', a);
            int b = md.indexOf("</dc:title>", a < 0 ? 0 : a);
            if (a < 0 || b < 0) {
                return "";
            }
            String t = md.substring(a + 1, b).trim();
            if (t.startsWith("<![CDATA[") && t.endsWith("]]>")) {
                t = t.substring(9, t.length() - 3).trim();
            }
            t = t.replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&apos;", "'");
            return t;
        } catch (Throwable t) {
            return "";
        }
    }

    /** v1.3.136：播放列表第一首的曲名（电台取台名）—— 记为「当前曲目」。 */
    private static String firstTitleOf(List<Integer> idxs, int lim, boolean radio) {
        return nthTitleOf(idxs, lim, radio, 0);
    }

    /** v1.3.163：播放列表第 n 首（0-based）的曲名。 */
    private static String nthTitleOf(List<Integer> idxs, int lim, boolean radio, int n) {
        try {
            if (lim > 0 && !idxs.isEmpty() && n >= 0 && n < idxs.size() && n < lim) {
                int i = idxs.get(n).intValue();
                if (radio) {
                    if (i >= 0 && i < radios.size()) {
                        return radios.get(i)[0];
                    }
                } else if (i >= 0 && i < tracks.size()) {
                    return tracks.get(i)[0];
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return "";
    }

    private static int pushPlaylist(String why, List<Integer> idxs, boolean radio) {
        return pushPlaylist(why, idxs, radio, 0, 1);
    }

    /** v1.3.163：带起始下标（队列内跳转用）。 */
    private static int pushPlaylist(String why, List<Integer> idxs, boolean radio,
            int startIndex) {
        return pushPlaylist(why, idxs, radio, startIndex, 1);
    }

    /**
     * v1.3.166：subType 可选。
     *
     * <p>★ 原厂 {@code MsgHandler.handlePlayRequest()} 的开关（反编译实证）：
     * <pre>
     *   case 1:  case 15:                       // 两者共用 JSON 解析
     *       PlayRequestInfo pri = parse(json);
     *       boolean startSelf = true;
     *       if (method == 1) { startSelf = false; pri.setIndex(0); }   // ← 1 会把起始位写死清零
     *       mEasyPlayer.play(pri, startSelf);
     *   case 11: ... playIndex((Integer) obj)                          // 按位置播，列表不动
     * </pre>
     * 所以：**subType=1 传 index 永远是白搭**（v1.3.163 切歌失败的根因），
     * 而 **subType=15 保留 JSON 里的 index** ⇒ 列表原样重推 + index=N，
     * player 就播第 N 首，**队列顺序一点不变**（= 小飞版的行为）。</p>
     */
    private static int pushPlaylist(String why, List<Integer> idxs, boolean radio,
            int startIndex, int subType) {
        if (!msgReady()) {
            return 0;
        }
        /* ★★★ v1.3.241：这里**不再**用 maxPlaylist() 兜第二道 —— 传进来的 idxs 就是
           这次要推的**全部**（谁想限制条数谁在 caller 里截：「随便听」按 count、
           「曲库全载」按 playlist_max）。原来这里再 min 一次，等于把 caller 的决定
           又压回 2000/5000，收藏导入的「不设数量限制」就白改了。
           真正的物理天花板只剩下面的 BINDER_BUDGET（一次 Binder 事务的字节数）。 */
        int lim = idxs.size();
        if (lim <= 0) {
            return 0;
        }
        int sameRetry = 0;                       // v1.3.233：先原样重试几次再砍半
        for (int attempt = 0; attempt < 10; attempt++) {
            String json = radio ? buildRadioJson(idxs, lim, startIndex)
                    : buildJson(idxs, lim, startIndex);
            /* ★ v1.3.241：条数上限取消后，真正的天花板是**这一次 Binder 事务**（1MB）。
               必须按 UTF-8 **字节**算（中文一个字 3 字节，json.length() 会低估 3 倍），
               超预算就按比例缩条数 —— 缩小是单调收敛的，一轮就够。
               缩了会在文案里明说「本次加载前 N 首」，不再像 v1.3.232 那样静默砍半丢歌。 */
            int blen = utf8Len(json);
            if (blen > BINDER_BUDGET && lim > 1) {
                long est = (long) lim * (long) BINDER_BUDGET / (long) blen;
                int cut = (int) (est < 1 ? 1 : (est >= lim ? lim - 1 : est));
                SessionLog.i("push playlist (" + why + "): " + blen + "B 超 "
                        + BINDER_BUDGET + "B 预算 -> " + lim + " 缩到 " + cut + " 首");
                lim = cut;
                sameRetry = 0;
                continue;
            }
            if (msg(subType, json)) {
                // v1.3.163：把「player 手里的列表」记在自己这边 —— 8080 WS 没了以后，
                // web 的队列就靠它渲染（顺序、曲名、下标全是我们自己 push 时定好的）。
                synchronized (lastIdx) {
                    lastIdx.clear();
                    for (int j = 0; j < idxs.size() && j < lim; j++) {
                        lastIdx.add(idxs.get(j));
                    }
                }
                lastIsRadio = radio;
                curIdx = (startIndex >= 0 && startIndex < lim) ? startIndex : -1;
                /* ★ v1.3.303：记下「我们自己刚推过队列」的时刻 —— tail-kick 用它
                   区分「用户手动点播/重播」和「内核自然回卷」（1.3.302 当晚实测：
                   点 215 后 tail-kick 把 40 分钟前残留的 ep219 记忆当成回卷，
                   自作主张续播 ep220，把用户正在听的队列顶掉）。 */
                lastPushTs = System.currentTimeMillis();
                SessionLog.i("push playlist (" + why + "): " + lim
                        + (radio ? " stations" : " tracks") + ", subType=" + subType
                        + ", json " + json.length() + "B"
                        + (attempt > 0 ? (" (retry#" + attempt + ")") : ""));
                // v1.3.136：记下当前曲目（列表第一首）—— 落盘后进程重启也报得出来
                // v1.3.163：startIndex>0 时当前曲目是那一首，不是第一首
                // v1.3.178 ★：改走 notePlayingTrack —— 专辑/时长/来源随曲目一起取，
                //   不再只记一个曲名（只记曲名 = 上一首在线歌的专辑一直挂在卡片上）。
                int at = (startIndex > 0 && startIndex < lim) ? startIndex : 0;
                if (radio || at >= idxs.size()) {
                    notePlaying(nthTitleOf(idxs, lim, radio, at));
                } else {
                    notePlayingTrack(idxs.get(at).intValue());
                }
                /* ★ v1.3.307：推完批后台预填占位行 —— 用户点未绑定集不再付 NAS
                   首拉整本书的 15s（超 Proxy.post 15s 预算 ⇒ 404 ⇒ 原地停）。 */
                progPrefetchKick();
                return lim;
            }
            /* ★ v1.3.233：msg() 失败先**原样重试**（Binder writeValue 多半是瞬时失败：
               内存紧张 / 并发事务多；357 首 JSON 才几十 KB，远没到 1MB）。原来一失败就
               `lim=(lim+1)/2` 砍半，把歌悄悄丢掉 ⇒ 用户看到「播放列表少了歌」。
               重试 3 次同尺寸仍失败才砍半（真·超大列表的兜底）。 */
            if (sameRetry < 3 && lim > 1) {
                sameRetry++;
                try {
                    Thread.sleep(150);
                } catch (Throwable ignore) {
                    // ignore
                }
                SessionLog.i("push playlist (" + why + ") retry same " + lim + " #" + sameRetry);
                continue;
            }
            if (lim <= 1) {
                break;
            }
            lim = (lim + 1) / 2;
            sameRetry = 0;
            SessionLog.i("push playlist (" + why + ") FAILED -> halve to " + lim);
        }
        return 0;
    }

    /* ===================== v1.3.307：占位行预填 ===================== */

    /**
     * ★ v1.3.307：队列里 {@code /r/prog} 占位行的 NAS 预填（后台、单飞、按页去重）。
     *
     * <p>根因（2026-11-03 实测）：占位行播到才 {@code prog_resolve}，缓存只有骨架没 rid 时
     * {@code fillPage} 现拉 NAS {@code /api/ab/episodes} —— 该接口**首次要把整本书的集表
     * 从上游拉全**（凡人修仙传实测 15.0s），而 player 转发 prog_resolve 的 Proxy.post 只等
     * 15s ⇒ 首点必超时，引擎拿 404；pl-1.6.124 节目行失败原地停 ⇒ 表现为「点了一直播不了」。
     * （第二次能播是因为 NuPlayer 自带重连恰好命中刚捂热的数据，纯靠运气。）
     * 拉过一次后整本书毫秒级（实测 3.6ms），所以只要**把首次提前到后台**即可：
     * 推完批扫一遍 tracks，占位行涉及的 (albumId, page) 丢后台线程逐页 fetchAndMerge，
     * 播到时永远是热缓存。NAS 拉整本与页号无关（page=2 也是同一份缓存），按页去重即可。</p>
     */
    private static final Set<String> sProgPrefetchPages =
            Collections.synchronizedSet(new HashSet<String>());
    private static final AtomicBoolean sProgPrefetchBusy = new AtomicBoolean(false);
    /** 占位行 URL 前缀（favCacheRow / abQueueProgLazy 两处生成，同款格式）。 */
    private static final String PROG_URL_PREFIX = "http://127.0.0.1:8080/r/prog?";

    static void progPrefetchKick() {
        try {
            List<String[]> jobs = new ArrayList<String[]>();
            Set<String> seen = new HashSet<String>();
            synchronized (tracks) {
                for (String[] r : tracks) {
                    if (r == null || r.length < 4 || r[3] == null
                            || !r[3].startsWith(PROG_URL_PREFIX)) {
                        continue;
                    }
                    String aid = uqParam(r[3], "aid");
                    int ep = 0;
                    try {
                        ep = Integer.parseInt(uqParam(r[3], "ep").trim());
                    } catch (Throwable t) {
                        ep = 0;
                    }
                    if (aid.length() == 0 || ep <= 0) {
                        continue;
                    }
                    String key = aid + ":" + ((ep - 1) / ProgPlay.PAGE_SIZE + 1);
                    if (seen.add(key) && sProgPrefetchPages.add(key)) {
                        jobs.add(new String[]{aid,
                                String.valueOf((ep - 1) / ProgPlay.PAGE_SIZE + 1)});
                    }
                }
            }
            if (jobs.isEmpty() || !sProgPrefetchBusy.compareAndSet(false, true)) {
                return;
            }
            if (sProgPrefetchPages.size() > 512) {
                sProgPrefetchPages.clear();   // 防长跑膨胀（清了最多重拉一轮，无害）
            }
            final List<String[]> fjobs = jobs;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        for (String[] j : fjobs) {
                            String key = j[0] + ":" + j[1];
                            try {
                                String src = "";
                                JSONArray ls = Prog.list();
                                for (int i = 0; ls != null && i < ls.length(); i++) {
                                    JSONObject it = ls.optJSONObject(i);
                                    if (it != null && j[0].equals(
                                            it.optString("albumId", "").trim())) {
                                        src = it.optString("source", "").trim();
                                        break;
                                    }
                                }
                                boolean ok = ProgPlay.fetchAndMerge("", j[0],
                                        Integer.parseInt(j[1]), ProgPlay.PAGE_SIZE, src);
                                if (!ok) {
                                    sProgPrefetchPages.remove(key);   // 失败允许重试
                                }
                                SessionLog.i("prog prefetch " + key + " got=" + ok);
                            } catch (Throwable t) {
                                sProgPrefetchPages.remove(key);
                            }
                        }
                    } finally {
                        sProgPrefetchBusy.set(false);
                    }
                }
            }, "r1-prog-prefetch").start();
        } catch (Throwable t) {
            // 预填是纯优化，任何异常都不影响主流程
        }
    }

    /** v1.3.307：从 URL query 里取一个参数（decode 后返回；取不到返回 ""）。 */
    private static String uqParam(String u, String k) {
        try {
            int q = u.indexOf('?');
            if (q < 0) {
                return "";
            }
            String[] ps = u.substring(q + 1).split("&");
            for (int i = 0; i < ps.length; i++) {
                int e = ps[i].indexOf('=');
                if (e > 0 && ps[i].substring(0, e).equals(k)) {
                    return URLDecoder.decode(ps[i].substring(e + 1), "UTF-8");
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return "";
    }

    /**
     * v1.3.241：JSON 的 **UTF-8 字节数**。
     * {@code String.length()} 数的是 UTF-16 char，中文（含歌名/歌手/占位地址里的 key）
     * 一个字占 3 字节 ⇒ 直接拿 length 判 Binder 1MB 预算会把 1MB 当成 340KB。
     */
    private static int utf8Len(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return s.getBytes("UTF-8").length;
        } catch (Throwable t) {
            return s.length() * 3;
        }
    }

    /**
     * 推的歌越多，player 解析 JSON + 建列表越久，首次 kick play 的等待要相应加长，
     * 否则 play 落在它还没 prepare 完的时候会被丢（表现为「点了不出声」）。
     */
    private static long startDelay(int n) {
        return n > 300 ? 1500 : 600;
    }

    /**
     * v1.3.157：口语噪声词（整串删除）。只删**多字短语**，绝不删「听 / 放 / 来 / 点 /
     * 歌」这类单字 —— 它们大量出现在歌名里（听海、放生、来生缘、丁香花），删了会误伤。
     */
    private static final String[] NOISE_PHRASE = {
            "我想听", "我要听", "我听听", "来一首", "来首", "来一曲", "放一首", "放首", "放一曲",
            "给我放", "帮我放", "给我播", "帮我播", "请播放", "请给我放", "播放一首", "播放",
            "点播", "换首", "换一首", "切首", "一首", "这首歌", "那首歌", "这首歌叫什么",
            "请", "帮我", "给我", "呗", "吧", "啊", "呀",
    };

    /** v1.3.157：切出来之后整个丢弃的噪声 token */
    private static final String[] NOISE_TOKEN = { "歌", "歌曲", "音乐", "首", "个", "一下", "听", "放", "来", "点" };

    /**
     * v1.3.157：把「播放周杰伦的晴天」切成 {@code ["周杰伦","晴天"]}。
     *
     * 原实现是**整串子串匹配**：{@code artist.contains(key) || title.contains(key)}。
     * 于是「周杰伦的晴天」必然 0 命中 —— 库里 title=晴天、artist=周杰伦，没有任何一个
     * 字段包含「周杰伦的晴天」这个整串。而「歌手 + 歌名」恰恰是语音点歌最常见的说法，
     * 表现为「说全了反而找不到，只说歌名却能播」。
     *
     * 这里：① 剥口语噪声 → ② 按分隔符切词 → ③ 丢噪声 token。
     */
    private static List<String> splitKeys(String raw) {
        List<String> out = new ArrayList<String>();
        String s = String.valueOf(raw).trim().toLowerCase();
        for (int i = 0; i < NOISE_PHRASE.length; i++) {
            s = s.replace(NOISE_PHRASE[i], " ");
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '，' || c == ',' || c == '、'
                    || c == '；' || c == ';' || c == '/' || c == '|' || c == '-' || c == '—'
                    || c == '的' || c == '（' || c == '(' || c == '）' || c == ')') {
                c = ' ';
            }
            sb.append(c);
        }
        String[] parts = sb.toString().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i].trim();
            if (t.length() == 0) {
                continue;
            }
            boolean noise = false;
            for (int j = 0; j < NOISE_TOKEN.length; j++) {
                if (t.equals(NOISE_TOKEN[j])) {
                    noise = true;
                    break;
                }
            }
            if (!noise) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * v1.3.157：单个关键词在一首歌上的匹配分，**越小越像**。
     * 0=歌名全等 1=歌手全等 2=歌名包含 3=歌手包含 -1=没命中。
     * 排序的意义：库里同名曲很多（原唱 / 翻唱 / live / DJ），原来取「库里第一条含该词的」，
     * 排在最前面的经常是翻唱版；按匹配度排之后，第一条才是最像用户说的那一首。
     */
    private static int keyScore(String[] t, String key, boolean artistOnly) {
        String ti = (t[0] == null) ? "" : t[0].toLowerCase();
        String ar = (t.length > 1 && t[1] != null) ? t[1].toLowerCase() : "";
        if (!artistOnly && ti.equals(key)) {
            return 0;
        }
        if (ar.equals(key)) {
            return 1;
        }
        if (!artistOnly && ti.indexOf(key) >= 0) {
            return 2;
        }
        if (ar.indexOf(key) >= 0) {
            return 3;
        }
        return -1;
    }

    /**
     * v1.3.157：把「还有哪些同名候选」拼成一句给模型看的话。
     *
     * 同名曲太多了（原唱 / 翻唱 / Live / DJ / 伴奏），播的只能是「最像的那一条」，
     * 但用户未必想要它。把带序号的候选回给模型，小智才有机会说
     * 「我还找到周杰伦版，要听吗」，而不是闷声播了翻唱还假装是用户点的。
     * 只有 1 条命中就返回空串 —— 别在正常情况下啰嗦。
     */
    private static String altHint(List<Integer> hits, int playing) {
        if (hits == null || hits.size() <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("（库里还有 " + (hits.size() - 1) + " 首同名的：");
        int shown = 0;
        for (int j = 0; j < hits.size() && shown < 3; j++) {
            int i = hits.get(j).intValue();
            if (i == playing) {
                continue;
            }
            String[] e = tracks.get(i);
            if (shown > 0) {
                sb.append("、");
            }
            sb.append(i + 1).append(".").append(e[0] == null ? "" : e[0]);
            if (e.length > 1 && e[1] != null && e[1].length() > 0) {
                sb.append("(").append(e[1]).append(")");
            }
            shown++;
        }
        if (shown == 0) {
            return "";
        }
        sb.append("；要换一首请说「播放第 N 首」）");
        return sb.toString();
    }

    /**
     * v1.3.177：本地这条「最接近的」到底够不够准？——**决定要不要改用在线结果**。
     *
     * <p>背景：以前 {@code hits.size() > 0} 就直接播池里最像的那条。而 matchTracks
     * 为了「不漏」会把 OR 的最好一档也留下来（哪怕只有一个词命中），于是用户语音说
     * 「播放稻香」时，池里一首只是歌名里含同一个词的《稻香 (伴奏)》或同名 Live 版
     * 就会被当成命中 ⇒ 播出来的是「库里最接近的」，而不是用户点的那首。</p>
     *
     * <p>现在的要求：**每个关键词都必须命中，且至少一个词是「全等」级**
     * （{@link #keyScore} 0=歌名全等 / 1=歌手全等）。只包含级、或部分词命中，
     * 都算「不够准」 ⇒ 交给 NAS 全源在线搜一遍，拿到了就播在线那条，
     * 拿不到再回落到这条池内结果（保证始终有声）。</p>
     */
    private static boolean localHitSolid(String k, int idx) {
        try {
            String key = String.valueOf(k).trim().toLowerCase();
            if (key.length() == 0) {
                return true;
            }
            if (idx < 0 || idx >= tracks.size()) {
                return false;
            }
            List<String> keys = splitKeys(key);
            if (keys.size() == 0) {
                return true;      // 全是噪声 ⇒ 与整串包含等价，认了
            }
            String[] t = tracks.get(idx);
            boolean exact = false;
            for (int i = 0; i < keys.size(); i++) {
                int sc = keyScore(t, keys.get(i), false);
                if (sc < 0) {
                    return false; // 有关键词没命中 ⇒ 就是「最接近」而已，不是它
                }
                if (sc <= 1) {
                    exact = true; // 歌名/歌手全等
                }
            }
            return exact;
        } catch (Throwable t) {
            return true;          // 判不了就按老行为播，别把用户的路堵死
        }
    }

    /**
     * v1.3.112：返回**所有**命中的曲目索引（不再是 v1.3.111 那样 break 取第一条）。
     *
     * 取第一条是「随机放某个歌手的歌」「库里第几首」做不了的根因 —— 上层拿不到候选集，
     * 只能播第一首然后被随机补足成全库混合。
     *
     * v1.3.157：改成**分词 AND 匹配 + 按匹配度排序**（见 {@link #splitKeys}）。
     * 三条保护，确保「变聪明」不会反过来变笨：
     *  · AND 全落空 ⇒ 回退 OR（至少命中一个词），按「命中词数多的在前」排；
     *  · 切不出关键词（用户说的全是噪声）⇒ 退化成原来的整串包含匹配；
     *  · 同分时保持**库内原序**（稳定），行为可预测。
     *
     * @param artistOnly true 只匹配歌手字段（play_artist 首选），false 歌名+歌手都匹配
     */
    private static List<Integer> matchTracks(String k, boolean artistOnly) {
        List<Integer> out = new ArrayList<Integer>();
        if (k == null) {
            return out;
        }
        String key = k.trim().toLowerCase();
        if (key.length() == 0) {
            return out;
        }
        List<String> keys = splitKeys(key);
        if (keys.size() == 0) {
            keys = null;   // 全是噪声 ⇒ 退回整串包含匹配
        }

        // keys == null（用户说的全是噪声）⇒ 走原来的整串包含匹配，直接返回
        if (keys == null) {
            for (int i = 0; i < tracks.size(); i++) {
                String[] t = tracks.get(i);
                String ti = (t[0] == null) ? "" : t[0].toLowerCase();
                String ar = (t.length > 1 && t[1] != null) ? t[1].toLowerCase() : "";
                if (ar.indexOf(key) >= 0 || (!artistOnly && ti.indexOf(key) >= 0)) {
                    out.add(Integer.valueOf(i));
                }
            }
            return out;
        }

        // 一次遍历：每首歌记下「命中了几个词」「匹配分之和」
        final List<Integer> idx = new ArrayList<Integer>();
        final List<Integer> hit = new ArrayList<Integer>();
        final List<Integer> sum = new ArrayList<Integer>();
        int best = 0;
        for (int i = 0; i < tracks.size(); i++) {
            String[] t = tracks.get(i);
            int h = 0, s = 0;
            for (int j = 0; j < keys.size(); j++) {
                int one = keyScore(t, keys.get(j), artistOnly);
                if (one >= 0) {
                    h++;
                    s += one;
                }
            }
            if (h == 0) {
                continue;
            }
            idx.add(Integer.valueOf(i));
            hit.add(Integer.valueOf(h));
            sum.add(Integer.valueOf(s));
            if (h > best) {
                best = h;
            }
        }
        if (idx.size() == 0) {
            return out;
        }

        // 只留「命中词数最多」的一档：AND 全命中时就是它们；否则自动退成 OR 的最好一档
        final int need = best;
        List<Integer> pos = new ArrayList<Integer>();
        for (int i = 0; i < idx.size(); i++) {
            if (hit.get(i).intValue() == need) {
                pos.add(Integer.valueOf(i));
            }
        }
        Collections.sort(pos, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int sa = sum.get(a.intValue()).intValue();
                int sb = sum.get(b.intValue()).intValue();
                if (sa != sb) {
                    return sa < sb ? -1 : 1;
                }
                int ia = idx.get(a.intValue()).intValue();
                int ib = idx.get(b.intValue()).intValue();
                return ia < ib ? -1 : (ia == ib ? 0 : 1);   // 同分 ⇒ 库内原序，行为可预测
            }
        });
        for (int i = 0; i < pos.size(); i++) {
            out.add(idx.get(pos.get(i).intValue()));
        }
        return out;
    }

    /**
     * v1.3.112：歌手统计（按曲目数降序）。
     * 用户不会凭空知道库里有没有周杰伦 —— list 必须把可点的歌手报出来，
     * 否则「随机放某个歌手的歌」这个需求在模型侧无从下手。
     *
     * <p>v1.3.182：新增 {@code localOnly} —— 在线歌播过就会 append 进 tracks，
     * 「本地歌曲里有什么」必须只统计本地，否则用户关了本地曲库还能听到
     * 「本地一共有五百四十七首」。</p>
     */
    private static String artistSummary(int maxArtists, boolean localOnly) {
        LinkedHashMap<String, Integer> cnt = new LinkedHashMap<String, Integer>();
        for (String[] t : tracks) {
            if (localOnly && isOnlineTrack(t)) {
                continue;
            }
            String a = (t.length > 1 && t[1] != null) ? t[1].trim() : "";
            if (a.length() == 0) {
                continue;
            }
            Integer c = cnt.get(a);
            cnt.put(a, Integer.valueOf((c == null) ? 1 : (c.intValue() + 1)));
        }
        List<String[]> rows = new ArrayList<String[]>();
        for (Map.Entry<String, Integer> e : cnt.entrySet()) {
            rows.add(new String[]{e.getKey(), String.valueOf(e.getValue().intValue())});
        }
        Collections.sort(rows, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return Integer.parseInt(b[1]) - Integer.parseInt(a[1]);
            }
        });
        StringBuilder sb = new StringBuilder();
        int m = Math.min(rows.size(), maxArtists);
        for (int i = 0; i < m; i++) {
            sb.append(rows.get(i)[0]).append("(").append(rows.get(i)[1]).append(")、");
        }
        if (rows.size() > m) {
            sb.append("等共 ").append(rows.size()).append(" 位歌手");
        }
        return sb.toString();
    }

    /** v1.3.182：这条是不是在线收录进来的（via 第 8 位非空 = online/rand）。 */
    private static boolean isOnlineTrack(String[] e) {
        return e != null && e.length > 7 && e[7] != null && e[7].length() > 0;
    }

    /* ★ v1.3.279：有声书/节目形态判定 —— 与 web npIsProgram 的 abLikely 同一套判据
       （类型词 + 第N集/回/话/章；2026-11-01 用户定稿：有「第几集」一律算节目，
       音箱场景没有 MV 之类误判顾虑，不再要求书名号）。 */
    private static final java.util.regex.Pattern AB_WORD_RE = java.util.regex.Pattern.compile(
            "声音剧|广播剧|有声书|有声小说|有声剧|听书|评书|评弹|相声|单田芳|袁阔成|郭德纲|于谦|岳云鹏|德云社|紫襟|头陀渊|青雪");
    private static final java.util.regex.Pattern AB_EP_RE = java.util.regex.Pattern.compile(
            "第\\s*\\d+\\s*[集回话章]|第[一二三四五六七八九十百千]+\\s*[集回话章]");

    private static boolean abLikely(String t) {
        if (t == null || t.length() == 0) {
            return false;
        }
        if (AB_WORD_RE.matcher(t).find()) {
            return true;
        }
        return AB_EP_RE.matcher(t).find();
    }

    /** random 等纯音乐入口的过滤：program 行 + 节目形态的 fav/在线行都算节目。 */
    private static boolean isProgramRow(String[] e) {
        if (e == null) {
            return false;
        }
        if (e.length > 7 && "program".equals(e[7])) {
            return true;
        }
        return abLikely(e[0]) || (e.length > 1 && abLikely(e[1]));
    }

    private static int[] shuffled(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        Random r = new Random(System.currentTimeMillis());
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return a;
    }

    /** 在 XzBootstrap 装配完成后调用；失败不影响小智主链路。 */
    public static synchronized boolean init(Context ctx) {
        app = (ctx == null) ? null : ctx.getApplicationContext();
        try {
            File f = new File(app.getFilesDir(), "music_index");
            if (f.exists()) {
                String s = readFile(f).trim();
                if (s.length() > 0) {
                    setIndexSpec(s);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "music_index flag read: " + t);
        }
        Log.i(TAG, "player bridge init, index=" + indexUrl);
        SessionLog.i("player bridge ready, index=" + indexUrl);
        // v1.3.90 调试开关：files/dbg_play 里写一个曲名，进程启动后自动播一次。
        // 用途：不用喊小智也能端到端验证「索引 -> msgcenter/DLNA -> 出声」整条链。
        // v1.3.95：**读完立刻删**（一次性）。以前要手动 rm，忘了就会导致每次开机自动播。
        String dbg = "";
        try {
            File f = new File(app.getFilesDir(), "dbg_play");
            if (f.exists()) {
                dbg = readFile(f).trim();
                try {
                    f.delete();
                } catch (Throwable t2) {
                    Log.w(TAG, "dbg_play unlink: " + t2);
                }
                Log.w(TAG, "dbg_play consumed once: " + dbg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "dbg_play read: " + t);
        }
        final String dbgTitle = dbg;

        new Thread(new Runnable() {
            public void run() {
                // v1.3.136：先把「上次是不是还在播」从磁盘读回来 —— 这一步不依赖 DLNA，
                // 越早越好。音乐活在 player 进程里，跟我们的死活无关：小智重启后
                // 内存态全丢，以前只能答「播放列表为空」。
                restorePlayState();
                loadIndex();
                // 预热原厂消息总线（主通道）
                msgReady();
                // 预热：先探到 DLNA 端口，首次 tools/call 就不用等发现
                if (discover()) {
                    SessionLog.i("dlna warm up ok: " + avtUrl);
                    // v1.3.133/136：softPlaying 是内存态，重启即丢 —— 而音乐在 player
                    // 独立进程里继续放。启动时查一次真状态补上，否则 TTS 让路判据全假。
                    // v1.3.136：改用「带结果」版 —— 没在播也要把 DLNA 原话打出来
                    // （取证用：DLNA 到底看不看得见 player 的播放）。
                    queryPlayingStateAsync(new StateCallback() {
                        public void onResult(boolean playing) {
                            SessionLog.i("music warm-up: DLNA=" + dlnaState
                                    + (playing
                                        ? " ⇒ 探测到播放中"
                                        : " ⇒ 没在播（保留本地记录："
                                            + (softPlaying ? "在播" : "未在播") + "）"));
                        }
                    });
                } else {
                    SessionLog.i("dlna warm up FAIL (will retry on call)");
                }
                if (dbgTitle.length() > 0) {
                    try {
                        Thread.sleep(3000);
                    } catch (Throwable t) {
                        // ignore
                    }
                    JSONObject a = new JSONObject();
                    try {
                        a.put("title", dbgTitle);
                    } catch (Throwable t) {
                        // ignore
                    }
                    String r = dispatch("play_song", a);
                    SessionLog.i("dbg_play '" + dbgTitle + "' -> " + r);
                }
            }
        }, "pb-index").start();
        return true;
    }

    public static boolean isReady() {
        return true;
    }

    // ---------------- v1.3.99：web 控制台接口 ----------------

    /** 运行时状态（供 /api/status 展示） */
    private static final int STREAM_MUSIC = 3;

    /**
     * v1.3.100：直接改系统 STREAM_MUSIC 音量（player 播的就是这条流）。
     * 用反射写，省掉对 android.media.AudioManager 的编译期依赖。
     *
     * @return null 表示成功，否则返回失败原因（调用方据此回退到 DLNA）。
     */
    private static String setSystemVolume(int lv0to100) {
        Context c = app;
        if (c == null) {
            return "app context is null";
        }
        try {
            Object am = c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return "AudioManager is null";
            }
            Class<?> cls = Class.forName("android.media.AudioManager");
            int max = ((Integer) cls.getMethod("getStreamMaxVolume", int.class)
                    .invoke(am, Integer.valueOf(STREAM_MUSIC))).intValue();
            if (max <= 0) {
                return "max volume=" + max;
            }
            int target = (int) (lv0to100 * max / 100.0f + 0.5f);
            cls.getMethod("setStreamVolume", int.class, int.class, int.class)
                    .invoke(am, Integer.valueOf(STREAM_MUSIC),
                            Integer.valueOf(target), Integer.valueOf(0));
            Log.i(TAG, "volume: set music stream " + target + "/" + max
                    + " (req " + lv0to100 + "%, readback " + readSystemVolume() + "%)");
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /** 读回系统音乐流音量（0~100）。失败返回 -1。 */
    private static int readSystemVolume() {
        Context c = app;
        if (c == null) {
            return -1;
        }
        try {
            Object am = c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return -1;
            }
            Class<?> cls = Class.forName("android.media.AudioManager");
            int max = ((Integer) cls.getMethod("getStreamMaxVolume", int.class)
                    .invoke(am, Integer.valueOf(STREAM_MUSIC))).intValue();
            int cur = ((Integer) cls.getMethod("getStreamVolume", int.class)
                    .invoke(am, Integer.valueOf(STREAM_MUSIC))).intValue();
            if (max <= 0) {
                return -1;
            }
            return (int) (cur * 100.0f / max + 0.5f);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------- v1.3.163：自有队列（8080 没了以后 web 的数据源） ----------------

    /** 当前歌手（总线回推才有；以前只能拿到曲名）。 */
    public static String curArtist() {
        return curArtist;
    }

    /** 当前曲目在自有队列中的位置（0-based，-1 = 未知）。 */
    public static int curIdx() {
        return curIdx;
    }

    /** 最近一次收到总线曲目事件的时间戳（0 = 从未收到；用来判断「通道活着没」）。 */
    public static long busSongTs() {
        return busSongTs;
    }

    /* ★ v1.3.303：tail-kick 防误触三件套 ——
       1.3.302 当晚实测：点「我的节目」215 集后，tailKickWatch 把 40 分钟前残留的
       「第219集　黑血刀」prev 记忆当成内核回卷，自动发起「续播 ep220」（书名还是
       从标题劈出来的垃圾「　黑血刀」）—— 13:10 那次更直接把《道诡异仙》顶回了队列
       （用户口中的「跳回之前播的书」）。三道门：
       ① recentPushMs：30s 内我们自己推过队列 = 用户手动点播，绝不 kick；
       ② isQueueTailTitle：上一曲必须是**当前队列的最后一行**（真回卷特征），
          手动跳集/重播时 216+ 还排在后面，prev 不是队尾；
       ③（在 tailKickWatch 里）书名必须能在「我的节目」精确命中且带 albumId。 */
    public static volatile long lastPushTs = 0;

    /** 30s 窗口内我们自己推过队列吗（tail-kick 避开用户手动点播）。 */
    public static boolean recentPushMs(long windowMs) {
        long t = lastPushTs;
        return t > 0 && System.currentTimeMillis() - t < windowMs;
    }

    /**
     * title 是不是当前推入队列的**最后一行**（tail-kick 真回卷判据）。
     * 内核循环播完批尾回卷时，上一曲必然是队尾；手动点播/跳集时新队列刚推入、
     * prev 是旧队列或旧记忆，对不上 ⇒ 返回 false。
     */
    public static boolean isQueueTailTitle(String title) {
        if (title == null || title.length() == 0) {
            return false;
        }
        synchronized (lastIdx) {
            if (lastIdx.isEmpty()) {
                return false;
            }
            int i = lastIdx.get(lastIdx.size() - 1).intValue();
            synchronized (tracks) {
                if (i < 0 || i >= tracks.size()) {
                    return false;
                }
                return title.equals(tracks.get(i)[0]);
            }
        }
    }

    /**
     * 当前队列最后一行的书名（slot[4]；空行返回 ""）—— tail-kick 真回卷时
     * 用队列自己的元数据认书，比从「第N集　标题」劈书名可靠得多。
     */
    public static String queueTailBook() {
        synchronized (lastIdx) {
            if (lastIdx.isEmpty()) {
                return "";
            }
            int i = lastIdx.get(lastIdx.size() - 1).intValue();
            synchronized (tracks) {
                if (i < 0 || i >= tracks.size()) {
                    return "";
                }
                String[] e = tracks.get(i);
                return (e.length > 4 && e[4] != null) ? e[4] : "";
            }
        }
    }

    /**
     * player 通过 USER_DATA 总线（what=33554432, arg1=1）回推的「当前曲目」。
     *
     * <p>val 形如 {@code [{"singer":"周杰伦","songName":"晴天",
     * "music_id":"100_local_MUSIC_1149","album":"",...}]}。
     * 切歌（我们发的 next、player 播完自动下一首、语音点歌）都会推一次，
     * 比 8080 的 get_info 轮询更实时，而且**原厂 player 也有**。</p>
     */
    public static void onBusSong(String singer, String title, String musicId) {
        if (title == null || title.length() == 0) {
            return;
        }
        curTitle = title;
        curArtist = (singer == null) ? "" : singer;
        busSongTs = System.currentTimeMillis();
        int k = indexOfMusicId(musicId);
        int ti = -1;
        if (k >= 0) {
            curIdx = k;
            // 队列位置 → 库内下标（itemId 里那份 n 就是 tracks 的下标）
            synchronized (lastIdx) {
                if (k < lastIdx.size()) {
                    ti = lastIdx.get(k).intValue();
                }
            }
        }
        // v1.3.178：走 notePlayingBus —— 专辑/时长/来源从库里那条补上，
        // 而不是被 notePlaying 的「换曲名就清空」给抹掉。
        notePlayingBus(title, singer, ti);
        Log.i(TAG, "bus song: " + title
                + (curArtist.length() > 0 ? (" - " + curArtist) : "")
                + " (queueIdx=" + curIdx + ", id=" + musicId + ")");
        SessionLog.i("now playing: " + title
                + (curArtist.length() > 0 ? (" - " + curArtist) : ""));
    }

    /**
     * 从 music_id（{@code 100_local_MUSIC_<n>} / {@code 100_radio_<n>}）反查它在
     * 自有队列里的位置。itemId 是 push 时我们自己拼的，n 就是库内下标。
     */
    private static int indexOfMusicId(String id) {
        if (id == null) {
            return -1;
        }
        int p = id.lastIndexOf('_');
        if (p < 0) {
            return -1;
        }
        int n;
        try {
            n = Integer.parseInt(id.substring(p + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
        synchronized (lastIdx) {
            for (int i = 0; i < lastIdx.size(); i++) {
                if (lastIdx.get(i).intValue() == n) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 自有队列（我们最后一次 push 进 player 的那张列表）。
     *
     * @return {@code {total, cur, radio, ts, list:[{i, title, artist}]}}，i 为 0-based 队列位置
     */
    public static JSONObject queueJson(int limit) {
        JSONObject o = new JSONObject();
        try {
            List<Integer> snap;
            synchronized (lastIdx) {
                snap = new ArrayList<Integer>(lastIdx);
            }
            boolean radio = lastIsRadio;
            o.put("total", snap.size());
            o.put("cur", curIdx);
            o.put("radio", radio);
            o.put("bus", busSongTs);
            JSONArray arr = new JSONArray();
            int lim = (limit <= 0) ? snap.size() : Math.min(snap.size(), limit);
            for (int j = 0; j < lim; j++) {
                int i = snap.get(j).intValue();
                String t = null;
                String a = "";
                if (radio) {
                    if (i >= 0 && i < radios.size()) {
                        t = radios.get(i)[0];
                        a = "电台";
                    }
                } else if (i >= 0 && i < tracks.size()) {
                    String[] e = tracks.get(i);
                    t = e[0];
                    a = (e.length > 1) ? e[1] : "";
                }
                if (t == null) {
                    continue;
                }
                JSONObject it = new JSONObject();
                it.put("i", j);
                it.put("title", t);
                it.put("artist", a);
                // v1.3.175：实际歌曲信息（数组中后三个位置）
                if (!radio && i >= 0 && i < tracks.size()) {
                    String[] e = tracks.get(i);
                    it.put("album", e.length > 4 ? e[4] : "");
                    it.put("duration", e.length > 5 ? e[5] : "");
                }
                arr.put(it);
            }
            o.put("list", arr);
        } catch (Throwable t) {
            Log.w(TAG, "queue json: " + t);
        }
        return o;
    }

    /** ★ v1.3.277：player 手里正在放的真队列（本机 127.0.0.1:8080/api/queue）。
       device 侧 lastIdx 镜像只在 device 主动 push 时更新 —— 用户在网页收藏栏/
       播放页起播整张歌单时，player 自己换了队列而 device 全然不知 ⇒ 小智的
       queue/status 一直回老镜像。player 不可达或队列空返回 null（调用方回退镜像）。 */
    private static JSONObject playerLiveQueue(int limit) {
        try {
            String js = httpGet("http://127.0.0.1:8080/api/queue"
                    + (limit > 0 ? ("?limit=" + limit) : ""), 1500, 8 * 1024 * 1024, 3000);
            if (js == null || js.length() == 0) {
                return null;
            }
            JSONObject p = new JSONObject(js);
            JSONArray pl = p.optJSONArray("list");
            if (pl == null || pl.length() == 0) {
                return null;
            }
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 player 真队列折成小智 queue 工具的形状。player 的 n 是全局 1-based
       （limit 窗口化也不变）⇒ i = n-1 就是全局 0-based，与 play_at 的 1-based 对齐。 */
    private static JSONObject liveQueueForModel(JSONObject p) {
        try {
            JSONArray pl = p.optJSONArray("list");
            JSONArray arr = new JSONArray();
            for (int i = 0; i < pl.length(); i++) {
                JSONObject e = pl.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                JSONObject it = new JSONObject();
                it.put("i", e.optInt("n", i + 1) - 1);
                it.put("title", e.optString("title", ""));
                it.put("artist", e.optString("artist", ""));
                arr.put(it);
            }
            JSONObject o = new JSONObject();
            o.put("total", p.optInt("total", arr.length()));
            o.put("cur", p.optInt("cur", 0));
            o.put("radio", false);
            o.put("live", true);
            o.put("list", arr);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 歌名宽松相等（收藏自定义名/前后缀差异不算「换了队列」）。 */
    private static boolean titleLooseEq(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = Fav.key(a, "");
        String nb = Fav.key(b, "");
        if (na.length() == 0 || nb.length() == 0) {
            return false;
        }
        return na.equals(nb) || na.indexOf(nb) >= 0 || nb.indexOf(na) >= 0;
    }

    /**
     * 跳到自有队列第 i 首（0-based）—— 8080 WS 的 {@code {"type":"play","index":i}} 的替代品。
     *
     * <p>★ v1.3.164 实测修正：原厂 player（EchoService）**完全不理 JSON 的 "index"** ——
     * 收到 playSpecified（总线 subType=1）后一律 {@code setCurrentTrack: play position = 0}，
     * 也就是永远从 itemList[0] 开始播（设备日志连推三次表，三次都播第一首）。
     * 所以这里不再指望 index，而是和 play_index 一个路子：**把目标顶到列表第一，整表重推**
     * （其余保持相对顺序）。推完 player 自然从头播 = 目标那一首。</p>
     *
     * <p>★★ v1.3.166：扒了原厂 {@code MsgHandler.handlePlayRequest()} 才发现，锅不在
     * player 不认 index，而在 **subType=1 的代码里写死了 {@code pri.setIndex(0)}**。
     * 同一个 switch 里的 **subType=15 不清零** ⇒ 改发 15 + 原顺序列表 + index=i，
     * player 直接播第 i 首，**队列顺序完全不变**（小飞版就是这么做的）。
     * 兜底：1.8s 后总线回推的下标不对 ⇒ 退回 v1.3.164 的 rotate（已验证有效）。</p>
     */
    public static String playAt(int i) {
        List<Integer> snap;
        synchronized (lastIdx) {
            snap = new ArrayList<Integer>(lastIdx);
        }
        if (snap.isEmpty()) {
            return "ERR 队列是空的（先在音乐页选一首或点随机播放）";
        }
        if (i < 0 || i >= snap.size()) {
            return "ERR 序号超出范围，队列共 " + snap.size() + " 首";
        }
        final boolean radio = lastIsRadio;
        // v1.3.166：首选 subType=15（列表原样 + index=i ⇒ 顺序不变）
        int n = pushPlaylist("play_at15#" + i, snap, radio, i, 15);
        if (n <= 0) {
            return "ERR 推送失败（消息总线不可用，player 起来了吗？）";
        }
        pushed = true;
        String who = nthTitleOf(snap, n, radio, i);
        final int want = i;
        final List<Integer> snap2 = snap;
        final long ts0 = busSongTs;      // 发送前的回推时间戳
        // 兜底：1.8s 后总线回推的下标不是目标 ⇒ subType=15 这版 player 不认，退回 rotate。
        // ⚠ 没收到任何回推时**不折腾**（可能只是 player 这次没推），免得打断已经播对的曲子。
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(1800);
                } catch (Throwable t) {
                    // ignore
                }
                if (busSongTs == ts0 || curIdx == want) {
                    return;
                }
                SessionLog.i("play_at: subType=15 未生效 (cur=" + curIdx
                        + " want=" + want + ") -> 退回 rotate 重推");
                List<Integer> re = new ArrayList<Integer>(snap2.size());
                re.add(snap2.get(want));
                for (int j = 0; j < snap2.size(); j++) {
                    if (j != want) {
                        re.add(snap2.get(j));
                    }
                }
                int n2 = pushPlaylist("play_at_rotate#" + want, re, radio, 0, 1);
                if (n2 > 0) {
                    kickPlay("play_at_rotate", startDelay(n2), 2);
                }
            }
        }, "pb-playat").start();
        SessionLog.i("play_at #" + (i + 1) + " (subType=15, 顺序不变): " + who
                + " (list=" + n + ")");
        return "OK 播放队列第 " + (i + 1) + " 首：" + who;
    }

    public static JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ready", idxLoaded);
            o.put("tracks", tracks.size());
            o.put("radios", radios.size());
            o.put("indexUrl", indexUrl);
            // v1.3.102：多源。indexUrls = 配置的全部源；sources = 每个源的曲目/电台/错误
            JSONArray iu = new JSONArray();
            List<String> iuSnap;
            synchronized (indexUrls) {
                iuSnap = new ArrayList<String>(indexUrls);
            }
            for (int i = 0; i < iuSnap.size(); i++) {
                iu.put(iuSnap.get(i));
            }
            o.put("indexUrls", iu);
            // v1.3.117：控制台要按电台名出按钮（点了直接试播，用来验证到底出不出声）
            JSONArray rn = new JSONArray();
            for (int i = 0; i < radios.size(); i++) {
                JSONObject rj = new JSONObject();
                rj.put("name", radios.get(i)[0]);
                rn.put(rj);
            }
            o.put("radioList", rn);
            JSONArray ss = new JSONArray();
            for (int i = 0; i < srcStats.size(); i++) {
                String[] s = srcStats.get(i);
                JSONObject j = new JSONObject();
                j.put("url", s[0]);
                j.put("tracks", s[1]);
                j.put("radios", s[2]);
                j.put("err", s[3]);
                ss.put(j);
            }
            o.put("sources", ss);
            o.put("dlnaPort", dlnaPort);
            o.put("pushed", pushed);
            // v1.3.136：控制台/排错要看的「到底在不在播」—— 内存软状态 + DLNA 真状态 +
            // 当前曲目（重启后从磁盘恢复的）
            o.put("playing", softPlaying);
            o.put("dlnaState", dlnaState());
            o.put("curTitle", curTitle());
            o.put("playTs", playStateTs);
            // v1.3.163：自有队列摘要 + 总线曲名（8080 不可用时 web 就靠这些活）
            o.put("curArtist", curArtist());
            // v1.3.175：实际歌曲信息（专辑/时长/来源）——播翻唱/DJ 版时用户要看这些
            o.put("curAlbum", curAlbum);
            o.put("curDuration", curDuration);
            o.put("curVia", curVia);
            // v1.3.188：封面 URL（页面直接当 <img src>；空 = 回落成转碟）
            o.put("curPic", curPic);
            // v1.3.178：在线随机池规模（排障：0 = 还没拉过 / NAS 拉不动）
            o.put("randPool", randPool.size());
            o.put("onlineAppends", onlineAppends);
            o.put("curIdx", curIdx());
            o.put("busTs", busSongTs());
            o.put("qTotal", lastIdx.size());
            o.put("qRadio", lastIsRadio);
            // v1.3.172：播放模式回显（1=随机 2=顺序 3=单曲循环），页面 seg 据此高亮
            o.put("mode", lastMode);
            int v = readSystemVolume();
            o.put("volume", v >= 0 ? v : lastVolume);
        } catch (Throwable t) {
            Log.w(TAG, "status json: " + t);
        }
        return o;
    }

    /**
     * 改音乐索引地址并立即重拉（web 控制台「保存」用）。
     * 同时落 files/music_index，下次启动仍生效。
     *
     * v1.3.102：接受**多行**（每行一个 index.json 的 URL），也可以一行里用逗号/空格分隔。
     * 多份索引的曲目合并成一个库 —— 这就是「NAS 本地 + 公网源」同时可用的实现方式。
     */
    public static void setIndexUrl(String url) {
        if (url == null) {
            return;
        }
        // v1.3.116：清空 = 恢复默认地址，不再「什么都不做」（原来那样点了保存没反应，
        // 用户会以为没删掉）。默认地址还是会被加回去，但至少状态是明确一致的。
        if (url.trim().length() == 0) {
            url = DEFAULT_INDEX_URL;
        }
        setIndexSpec(url);
        try {
            File f = new File(app.getFilesDir(), "music_index");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            fos.write(url.trim().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Log.w(TAG, "music_index write: " + t);
        }
        reloadIndex();
    }

    /**
     * v1.3.102：解析索引地址文本（换行 / 逗号 / 空格都当分隔符，# 开头是注释）。
     * indexUrl 保留第一份，仅用于旧日志与状态回显兼容。
     */
    // v1.3.116：这里**不要**用 static synchronized —— 那把锁是 PlayerBridge.class，
    // 而 loadIndex()（static synchronized）会长时间占着它（拉索引、解析上千首）。
    // 一旦某个源卡住，控制台「保存音乐源」就会跟着排队等锁，表现就是「删不掉」。
    // 改成只在 indexUrls 上同步，和 loadIndex 彻底解耦。
    private static void setIndexSpec(String spec) {
        synchronized (indexUrls) {
        indexUrls.clear();
        String[] lines = spec.split("[\n\r,;]+");
        for (int i = 0; i < lines.length; i++) {
            String u = lines[i].trim();
            if (u.length() == 0 || u.startsWith("#")) {
                continue;
            }
            if (!u.startsWith("http")) {
                continue;
            }
            if (!indexUrls.contains(u)) {
                indexUrls.add(u);
            }
        }
        if (indexUrls.size() == 0) {
            indexUrls.add(DEFAULT_INDEX_URL);
        }
        indexUrl = indexUrls.get(0);
        }
    }

    /** v1.3.116：当前配了几个音乐源（控制台回显用）。 */
    public static int indexUrlCount() {
        synchronized (indexUrls) {
            return indexUrls.size();
        }
    }

    /** 后台重拉索引（loadIndex 是 synchronized，与MCP调用互斥） */
    public static void reloadIndex() {
        new Thread(new Runnable() {
            public void run() {
                loadIndex();
                SessionLog.i("web: reload music index -> " + indexUrl);
            }
        }, "pb-reload").start();
    }

    // ---------------- 索引 ----------------

    /**
     * v1.3.102：多源加载。逐个拉 indexUrls，曲目/电台**追加**进同一个库。
     *
     * 设计要点：
     *  · 单源失败不影响其它源（公网源挂了，NAS 本地照常能放）；
     *  · 每条 track 在进入库之前就把最终 URL 算好（绝对 url 优先，否则按该源 base 拼），
     *    所以库里不存在「属于哪个 base」的歧义；
     *  · srcStats 记录每个源的成败与条目数，web 控制台按源显示，排障不用翻日志。
     */
    private static synchronized void loadIndex() {
        List<String[]> nt = new ArrayList<String[]>();
        List<String[]> nr = new ArrayList<String[]>();
        // v1.3.179：本地段（onlineFrom 之前的那些），单独攒一份
        List<String[]> nl = new ArrayList<String[]>();
        List<String[]> stats = new ArrayList<String[]>();
        StringBuilder errs = new StringBuilder();
        boolean anyOk = false;

        if (indexUrls.size() == 0) {
            setIndexSpec(DEFAULT_INDEX_URL);
        }
        // v1.3.116：先快照，别在循环里一直抓着 indexUrls
        List<String> urls;
        synchronized (indexUrls) {
            urls = new ArrayList<String>(indexUrls);
        }
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                // v1.3.116：4MB / 10s 双上限，绝不让它把 loadIndex 拖死
                String s = httpGet(url, 6000, 4 * 1024 * 1024, 10000);
                JSONObject o = new JSONObject(s);
                String srcBase = o.optString("base", "");
                int addT = 0;
                int addR = 0;
                JSONArray ta = o.optJSONArray("tracks");
                if (ta != null) {
                    // v1.3.179 ★：合并后的 index.json 一肩挑「本地 + 在线」，
                    //   用 onlineFrom 标出在线段从哪开始。本地那一段另存一份
                    //   （{@link #localTracks}），随机在线换批时它原样留着。
                    int cut = o.has("onlineFrom") ? o.optInt("onlineFrom", 0) : -1;
                    if (cut < 0) {
                        // 老服务端没这个字段：按 source 标记猜。
                        //   mini-lx = 整份都是在线池（可以整批换掉）；
                        //   其它（原 r1-music 等）= 整份都是本地库，保护起来别丢。
                        cut = "mini-lx".equals(o.optString("source", "")) ? 0 : ta.length();
                    }
                    for (int k = 0; k < ta.length(); k++) {
                        JSONObject t = ta.getJSONObject(k);
                        String file = t.optString("file", "");
                        // 绝对 url 字段（公网源用）优先，否则按本源 base 拼
                        String abs = t.optString("url", "");
                        if (abs == null || abs.length() == 0) {
                            abs = joinUrl(srcBase, file);
                        }
                        // v1.3.175：String[7] = [0]title [1]artist [2]file [3]url
                        // [4]album [5]duration [6]pic。后三个是「实际歌曲信息」：
                        // 池里带的 album 直接用；在线搜的歌由 playOnlineItem 填。
                        // v1.3.178：String[8] —— [7]via。在线搜/在线随机拉来的填 "online"
                        //   （见 appendOnlineTracks）。
                        // v1.3.183：★ 在线池段（k >= cut）标 "pool" —— 以前池歌
                        //   via=""，统计/搜索的「本地」口径把它们全算了进去：
                        //   用户关着本地曲库，小智还说「本地一共有五百四十七首」
                        //   （547 = 100 首池歌 + 447 首在线 append，真正的本地是 0）。
                        //   ★ 用 "pool" 而不是 "online"：显示端只认 online/rand，
                        //   pool 不出 pill（与改前观感一致）；而「本地」口径的判断
                        //   是「via 非空 = 非本地」，pool 一样被挡在外面。
                        //   播放不受 via 影响；localTracks 另存本地段，random_online 不变。
                        String via = (k >= cut) ? "pool" : "";
                        nt.add(new String[]{
                                t.optString("title", ""),
                                t.optString("artist", ""),
                                file,
                                abs,
                                t.optString("album", ""),
                                t.optString("duration", ""),
                                jstr(t, "pic"),
                                via});
                        if (k < cut) {
                            nl.add(nt.get(nt.size() - 1));
                        }
                        addT++;
                    }
                }
                JSONArray ra = o.optJSONArray("radios");
                if (ra != null) {
                    for (int k = 0; k < ra.length(); k++) {
                        JSONObject r = ra.getJSONObject(k);
                        nr.add(new String[]{r.optString("name", ""), r.optString("url", "")});
                        addR++;
                    }
                }
                stats.add(new String[]{url, String.valueOf(addT), String.valueOf(addR), ""});
                anyOk = true;
                Log.i(TAG, "index[" + i + "] ok: " + url + " -> " + addT + " tracks, "
                        + addR + " radios");
            } catch (Throwable t) {
                String e = String.valueOf(t);
                stats.add(new String[]{url, "0", "0", e});
                if (errs.length() > 0) {
                    errs.append(" | ");
                }
                errs.append(url).append(": ").append(e);
                Log.w(TAG, "index[" + i + "] FAIL " + url + ": " + t);
            }
        }

        tracks.clear();
        tracks.addAll(nt);
        synchronized (localTracks) {
            localTracks.clear();
            localTracks.addAll(nl);
            /* ★ v1.3.203：本地段换了一批 ⇒ findLocal 的 key 索引作废（sIdxVer++） */
            sIdxVer++;
        }
        onlineAppends = 0;      // v1.3.178：整表重建 ⇒ 在线 append 的账也归零
        radios.clear();
        radios.addAll(nr);
        srcStats.clear();
        srcStats.addAll(stats);
        idxLoaded = anyOk;
        idxErr = errs.toString();
        if (anyOk) {
            SessionLog.i("music index loaded: " + tracks.size() + " tracks, "
                    + radios.size() + " radios (from " + urls.size() + " source(s)"
                    + (idxErr.length() > 0 ? "; failed: " + idxErr : "") + ")");
        } else {
            SessionLog.i("music index FAIL (all sources): " + idxErr);
        }
    }

    private static synchronized String[] find(String q) {
        String k = (q == null ? "" : q.trim().toLowerCase());
        if (k.length() == 0) {
            return null;
        }
        for (String[] t : tracks) {
            if (t[0].toLowerCase().contains(k) || t[1].toLowerCase().contains(k)) {
                return t;
            }
        }
        for (String[] r : radios) {
            if (r[0].toLowerCase().contains(k)) {
                return r;
            }
        }
        return null;
    }

    /**
     * v1.3.174：库外在线点歌 —— 曲库 index.json 是启动时拉的快照，快照外的歌
     * （新歌 / 没进曲库池的歌）以前 miss 后只能 ERR。现在 miss 时问 NAS mini-lx
     * 在线搜一把：
     * <pre>
     *   GET {base}/api/search?q=..&amp;n=5&amp;source=all  → {"list":[{title,artist,source,rid}]}
     *   GET {base}/api/url?rid=..&amp;source=..&amp;q=128k     → {"url":"https://..."}
     * </pre>
     * base 从 {@link #indexUrl} 现场推导（全局 {@code base} 字段是死字段，没人赋值）。
     * 搜到的歌 append 进 {@link #tracks}（四元组第 [3] 位放绝对 URL，urlOf 认），
     * 再走和库里歌完全相同的 pushSongs 推播 ⇒ 队列可见、能切歌、reloadIndex 自动清。
     * <p>全程 try/catch + 返回 null 兜底：任何一步失败调用方照原路 ERR，
     * 零副作用（loadIndex 失败也不会把 tracks 弄脏——append 只发生在拿到 URL 之后）。</p>
     *
     * @return OK 话术；在线链路不可用返回 {@code null}（调用方维持原有 ERR）
     */
    /**
     * v1.3.175：NAS mini-lx 基址推导（{@code http://host:port}，无尾斜杠）。
     *
     * <p>来源：全局 {@link #base} 是死字段（没人赋值，实测恒空），
     * 真正可靠的是 {@link #indexUrl} —— 去掉末段 {@code /index.json}。
     * 在线搜索 / 取直链 / 曲库索引都打这一个基址。</p>
     *
     * @return 基址；indexUrl 也没拉到时返回 {@code null}
     */
    /* ★ v1.3.336：开成包内可见 —— ProgProgress 的书级共享上报也要拿 NAS 基址 */
    static String nasBase() {
        String b = (base != null && base.length() > 0) ? base : "";
        if (b.length() == 0) {
            String iu = indexUrl;
            if (iu == null || iu.length() == 0) {
                return null;
            }
            int cut = iu.lastIndexOf('/');
            if (cut <= 7) {           // "http://x" 都没走到，没得拼
                return null;
            }
            b = iu.substring(0, cut);
        }
        return b;
    }

    /**
     * v1.3.175：在线搜歌（不打本地曲库）——打 NAS mini-lx {@code /api/search?source=all}。
     *
     * <p>给 web「在线搜歌」按钮用：池子里没有的歌（新歌/冷门歌）从这里出，
     * 每条带 {@code rid}/{@code source}，点播放时喂给 {@link #playOnlineItem}。</p>
     *
     * @return {@code {total, list:[{title,artist,source,rid}]}}；任何一步失败返回 {@code null}
     */
    public static JSONObject onlineSearchJson(String q, int lim) {
        try {
            if (q == null || q.trim().length() == 0) {
                return null;
            }
            String b = nasBase();
            if (b == null) {
                return null;
            }
            // v1.3.176：8s 对「歌手名」这类多源聚合不够（kw/wy/kg 都要跑一遍），
            //   结果就变成点了在线搜歌却返回空 ⇒ 提到 15s。网页侧反代给了 25s。
            String js = httpGet(b + "/api/search?q=" + URLEncoder.encode(q.trim(), "UTF-8")
                    + "&n=" + (lim > 0 ? lim : 10) + "&source=all", 15000);
            if (js == null || js.length() == 0) {
                return null;
            }
            JSONObject src = new JSONObject(js);
            JSONArray list = src.optJSONArray("list");
            if (list == null || list.length() == 0) {
                return null;
            }
            JSONObject o = new JSONObject();
            JSONArray out = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                String title = s.optString("title", "");
                String artist = s.optString("artist", "");
                String source = s.optString("source", "");
                String rid = s.optString("rid", "");
                if (source.length() == 0 || rid.length() == 0) {
                    continue;
                }
                JSONObject it = new JSONObject();
                it.put("title", title);
                it.put("artist", artist);
                it.put("source", source);
                it.put("rid", rid);
                // v1.3.175：实际歌曲信息透传（kw 源本来就有；没有的源留空）
                it.put("album", s.optString("album", ""));
                it.put("duration", s.optString("duration", ""));
                // v1.3.262：类型透传（program=有声书/节目）—— 页面标记 + 收藏时带上
                it.put("kind", s.optString("kind", ""));
                out.put(it);
                // v1.3.176：NAS 那边不认 n=（一次甩过来上百条），为了手机上能翻
                //   得动，这里自己截。total 依旧用 NAS 给的原始命中数。
                if (lim > 0 && out.length() >= lim) {
                    break;
                }
            }
            o.put("total", src.optInt("total", out.length()));
            o.put("list", out);
            return o;
        } catch (Throwable t) {
            SessionLog.i("online search 「" + q + "」 failed: " + t);
            return null;
        }
    }

    /**
     * v1.3.198：只取在线直链（<b>不推播、不 append</b>），取不到返回空串。
     *
     * <p>从 {@link #playOnlineItem} 里抽出来给 {@code queue_online} 用 ——
     * 整批入队时只有第一首值得为它现跑一次取链，其余推惰性地址即可。</p>
     */
    private static String onlineUrl(String title, String artist, String source, String rid,
                                    int ms) {
        try {
            String b = nasBase();
            if (b == null || source == null || source.length() == 0
                    || rid == null || rid.length() == 0) {
                return "";
            }
            String uj = httpGet(b + "/api/url?rid=" + URLEncoder.encode(rid, "UTF-8")
                    + "&source=" + URLEncoder.encode(source, "UTF-8")
                    + "&q=128k&title=" + URLEncoder.encode(title == null ? "" : title, "UTF-8")
                    + "&artist=" + URLEncoder.encode(artist == null ? "" : artist, "UTF-8"), ms);
            return (uj == null || uj.length() == 0)
                    ? "" : new JSONObject(uj).optString("url", "");
        } catch (Throwable t) {
            SessionLog.i("online url failed " + source + "/" + rid + ": " + t);
            return "";
        }
    }

    /**
     * v1.3.175：播放一条**指定的在线条目**（web 在线搜索结果点播放用）。
     *
     * <p>从 {@link #playOnlineSong(String)} 抽出「拿链 + append + 推播」部分，
     * 区别只是不再自己搜 —— 四件套（title/artist/source/rid）由界面给。
     * append 进 tracks ⇒ 队列可见、能上下曲；刷新页面重拉 index 时自动清掉。</p>
     *
     * <p>v1.3.175：album/duration 来自 NAS search 的实际元数据 —— 播的是翻唱/DJ 版时，
     * 「正在播放」卡片显示真实歌手 + 专辑 + 时长，用户要求的「显示实际歌曲信息」。</p>
     */
    public static String playOnlineItem(String title, String artist, String source, String rid,
                                        String album, String duration) {
        return playOnlineItem(title, artist, source, rid, album, duration, 15000);
    }

    /** v1.3.177：{@code urlMs} = 取链超时。语音链路（本地还有兜底）给短一点的预算。 */
    public static String playOnlineItem(String title, String artist, String source, String rid,
                                        String album, String duration, int urlMs) {
        try {
            if ((title == null || title.length() == 0) && (rid == null || rid.length() == 0)) {
                return null;
            }
            String b = nasBase();
            if (b == null || source == null || source.length() == 0 || rid == null || rid.length() == 0) {
                return null;
            }
            String uj = httpGet(b + "/api/url?rid=" + URLEncoder.encode(rid, "UTF-8")
                    + "&source=" + URLEncoder.encode(source, "UTF-8")
                    + "&q=128k&title=" + URLEncoder.encode(title == null ? "" : title, "UTF-8")
                    + "&artist=" + URLEncoder.encode(artist == null ? "" : artist, "UTF-8"), urlMs);
            String url = (uj == null || uj.length() == 0)
                    ? "" : new JSONObject(uj).optString("url", "");
            // v1.3.179 ★：单曲也别让用户干等——取链超时就改推**惰性地址**。
            //   取链是「五级兜底串起来」，各级超时 12s，上游抽风时能卡好几秒；
            //   而惰性地址现在只是登记，等 player 真开口要这首时 NAS 才取
            //   （那时只有它一首，慢也只慢它自己，不会拖住整批）。
            boolean lazy = false;
            if (url.length() == 0 && lazyOk(b)) {
                url = b + "/r/" + source + "/" + rid
                        + "?title=" + enc(title) + "&artist=" + enc(artist);
                lazy = true;
                SessionLog.i("play online item: 取链超时，改推惰性地址 " + url);
            }
            if (url.length() == 0) {
                return null;
            }
            String who = (artist != null && artist.length() > 0)
                    ? (title + " - " + artist) : title;
            // v1.3.175：String[7] —— [4]album [5]duration [6]pic，实际歌曲信息随曲目进队列
            // v1.3.178：String[8] —— [7]via="online"（来源跟着曲目走，切歌/重启都不会串）
            String[] item = new String[]{title == null ? "" : title,
                    artist == null ? "" : artist, "", url,
                    album == null ? "" : album, duration == null ? "" : duration, "", "online"};
            int idx;
            synchronized (tracks) {
                tracks.add(item);
                idx = tracks.size() - 1;
            }
            List<Integer> one = new ArrayList<Integer>();
            one.add(Integer.valueOf(idx));
            int n = pushSongs("play_online", one);
            SessionLog.i("play online item: 「" + who + "」 " + url + " push=" + n);
            // v1.3.179：惰性地址 = 「马上能开口，但何时出声看 NAS 那一下」
            //   ⇒ 话术要说清，别让用户以为已经响了。
            String tail = lazy ? "（在线，起播时缓冲一下）" : "（在线搜到的）";
            if (n > 0) {
                pushed = true;
                kickPlay("play_online", startDelay(n), 2);
                // v1.3.175：落实际歌曲信息（via=online ⇒ 页面显示「在线」角标）
                notePlayingFull(who, album, duration, "online");
                return "OK 正在播放 " + who + tail;
            }
            // 推不了列表（player 没起）⇒ DLNA 单条直落
            String r = setUri(url);
            if (r.startsWith("OK")) {
                notePlayingFull(who, album, duration, "online");
                return "OK 正在播放 " + who + tail;
            }
            return null;
        } catch (Throwable t) {
            SessionLog.i("play online item failed: " + t);
            return null;
        }
    }

    /**
     * v1.3.174：曲库快照 / 电台都没中 ⇒ 去 NAS mini-lx 在线搜一把（语音链路兜底）。
     *
     * <p>v1.3.175：base 推导抽去 {@link #nasBase()}；搜到第一条后直接调
     * {@link #playOnlineItem}，不再各写一遍。</p>
     */
    public static String playOnlineSong(String q) {
        // v1.3.179：取链预算 15s → 8s。取链是五级兜底串起来的，拿不到时
        //   15s 只是让用户多站 7 秒；8s 拿不到就交给惰性地址（见 playOnlineItem）。
        return playOnlineSong(q, 8000, 8000);
    }

    /**
     * v1.3.177：带预算版。{@code searchMs} = 搜歌超时，{@code urlMs} = 取链超时。
     *
     * <p>语音链路用时故意给小（4s + 8s）：这次是「本地已经有一条最接近的，
     * 只是不确定准不准」的情形，在线是加分项而不是必经之路 —— NAS 慢了就立刻
     * 回落本地，别让用户在音箱前干等十几秒。</p>
     */
    public static String playOnlineSong(String q, int searchMs, int urlMs) {
        try {
            if (q == null || q.trim().length() == 0) {
                return null;
            }
            String b = nasBase();
            if (b == null) {
                return null;
            }
            // ★ v1.3.269：先按曲名打分挑（searchPick/osScore；语音不带独立歌手字段
            //   ⇒ artist 传空，闸门全放行、只看曲名贴合度）。热度第 1 条常常是
            //   DJ 版/Live/翻自版，第 2 条才是干干净净的原曲 —— 挑得出可信的就用它。
            //   全场都不可信（unsure）才退回原来的「热度第 1 条」：语音不能不出声。
            try {
                JSONObject pick = searchPick(b, q, q, "", searchMs, "");
                if (pick != null && !pick.optBoolean("unsure", false)
                        && pick.optString("source", "").length() > 0
                        && pick.optString("rid", "").length() > 0) {
                    return playOnlineItem(pick.optString("title", q),
                            pick.optString("artist", ""),
                            pick.optString("source", ""), pick.optString("rid", ""),
                            pick.optString("album", ""),
                            pick.optString("duration", ""), urlMs);
                }
            } catch (Throwable ignore) {
                // 打分通道挂了 ⇒ 落回下面的盲搜第 1 条（原行为）
            }
            // ① 在线搜（source=all = mini-lx 全源聚合搜）
            String js = httpGet(b + "/api/search?q=" + URLEncoder.encode(q, "UTF-8")
                    + "&n=5&source=all", searchMs);
            if (js == null || js.length() == 0) {
                return null;
            }
            JSONArray list = new JSONObject(js).optJSONArray("list");
            if (list == null || list.length() == 0) {
                return null;
            }
            JSONObject s0 = list.getJSONObject(0);
            String title = s0.optString("title", q);
            String artist = s0.optString("artist", "");
            String source = s0.optString("source", "");
            String rid = s0.optString("rid", "");
            if (source.length() == 0 || rid.length() == 0) {
                return null;
            }
            // ② 拿链 + ③ 塞 tracks + 推播（与库内歌同一条路，队列可见、能上下曲）
            // v1.3.175：album/duration 一起带（实际歌曲信息）
            return playOnlineItem(title, artist, source, rid,
                    s0.optString("album", ""), s0.optString("duration", ""), urlMs);
        } catch (Throwable t) {
            SessionLog.i("play online 「" + q + "」 failed: " + t);
            return null;
        }
    }

    // ---------------------------------------------------------------------------
    // v1.3.178：在线随机池 + 在线批量取链
    //   —— 「随机播放在线音乐」和「歌手连播也要包含在线」这两件事的公共底座。
    // ---------------------------------------------------------------------------

    /** 最近一次从 NAS 拉的在线随机曲库（op=play_rand_at 按下标取用）。 */
    private static final List<String[]> randPool = new ArrayList<String[]>();

    /**
     * v1.3.179：**本地曲库段**的快照（一份曲目记录的引用，不复制内容）。
     *
     * <p>由来：mini-lx 和 r1-music 合并之后，一个 index.json 里同时装着
     * 本地曲目和在线随机池（本地在前，用 {@code onlineFrom} 标出分界）。
     * 而「随机播放在线音乐」的做法是<b>整批换掉曲库</b> —— 不把本地段单独留一份，
     * 一句话就会把本地那一千多首冲没了。</p>
     */
    private static final List<String[]> localTracks = new ArrayList<String[]>();
    /* ★ v1.3.203：findLocal 的 key 索引缓存（见 findLocal 的长注释）。
       sIdxVer 每次 loadIndex 重建本地段时 +1；sLocVer 是建索引时看到的版本。 */
    private static volatile int sIdxVer = 0;
    private static volatile int sLocVer = -1;
    private static volatile HashMap<String, Integer> sLocFull = null;
    private static volatile HashMap<String, Integer> sLocTitle = null;
    /* ★ v1.3.235：建索引时那份本地快照 —— 「同名但歌手完全不同」的闸门要用它
       回查库内歌手（见 artistOkForLocal）。与 sLocTitle 的下标一一对应。 */
    private static volatile List<String[]> sLocList = null;

    /** v1.3.179：本地曲库段的快照（随机在线换批时用它打底）。 */
    public static List<String[]> localTracksSnapshot() {
        synchronized (localTracks) {
            return new ArrayList<String[]>(localTracks);
        }
    }

    /**
     * v1.3.179：只推**在线段**（[from, size)）—— 本地那几百上千首留在库里做存档，
     * 不跟着塞进 player 的列表（用户要的是「播放在线音乐」，不是「把全库洗一遍」）。
     */
    private static List<Integer> shuffledIdx(int from) {
        int n = tracks.size();
        int a = from > 0 ? from : 0;
        if (a >= n) {
            a = 0;
        }
        int[] arr = shuffled(n);
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (arr[i] >= a) {
                out.add(Integer.valueOf(arr[i]));
            }
        }
        return out;
    }

    /**
     * 从 NAS mini-lx 拉一批**在线随机曲库**。
     *
     * <p>为什么这条路走得通：mini-lx 的 {@code /index.json?n=300&fresh=1} 在服务端
     * 重洗一批牌（{@code fresh=1}），每条的 url 是 {@code /s/<i>} 这种**惰性地址** ——
     * 真正播放那一刻才由 NAS 现场取直链并转发。所以：
     * <ul>
     *   <li>拉 300 首只要 1 次 HTTP（实测 0.45s / 42KB），**不需要为每一首单独取链**；</li>
     *   <li>几十小时就过期的网易/酷狗直链对 R1 永远是新鲜的；</li>
     *   <li>{@code /s/<i>} 用的是池内绝对下标，我们抽哪一批都不会错位。</li>
     * </ul>
     * 我们在这 300 首里再随机挑 {@code n} 首 ⇒ 每次都是不同的一批。</p>
     *
     * @param n 要几首（<=0 = 100）
     * @param fresh true = 让 NAS 重新洗牌
     * @return String[8] 列表（[7]="online"）；失败返回 null
     */
    private static List<String[]> fetchOnlinePool(int n, boolean fresh) {
        try {
            String b = nasBase();
            if (b == null) {
                return null;
            }
            int want = n > 0 ? n : 100;
            // 多拉一些再自己抽：mini-lx 的 n 上限 300，池容量 400
            int ask = Math.min(300, Math.max(want, 100));
            // v1.3.179 ★★：打 /online.json（**只吐在线池**那条路）。
            //   合并之后 /index.json 里混着本地曲目，再拿它当「在线随机」的源，
            //   本地那一千多首就会被当成在线歌塞回列表。
            JSONObject o = null;
            // ★ 这个 GET 必须自己吞异常：httpGet 见到非 200 是 **throw**（不是返回 null），
            //   老 mini-lx 根本没有 /online.json 这条路由 ⇒ 404 ⇒ 直接抛到外层 catch
            //   ⇒ 下面那句「回落 /index.json」永远走不到 ⇒ 在线随机恒等于「拉不到」。
            //   （v1.3.179 实测：旧 NAS 上 random_online 一直回落本地，就是栽在这）
            try {
                String s = httpGet(b + "/online.json?n=" + ask + (fresh ? "&fresh=1" : ""),
                        15000, 4 * 1024 * 1024, 20000);
                if (s != null && s.length() > 0) {
                    JSONObject t0 = new JSONObject(s);
                    // 必须是「有 tracks 数组」的应答才算数：
                    // 万一上游回了个 {"error":"not found"} 之类的非空 200，
                    // 只看非空就会把它当成有效响应，然后一首都拿不到。
                    if (t0.has("tracks")) {
                        o = t0;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (o == null) {
                // 老 mini-lx 没有 /online.json（那时它本来就只吐在线池）⇒ 回落
                String s = httpGet(b + "/index.json?n=" + ask + (fresh ? "&fresh=1" : ""),
                        15000, 4 * 1024 * 1024, 20000);
                o = new JSONObject(s);
            }
            // Web 上把「在线音乐库」关掉了 ⇒ 这里就是空的，按「拉不到」处理
            //   （op=random_online 会因此回落本地曲库，与页面开关的口径一致）
            if (o.optBoolean("off", false)) {
                SessionLog.i("online pool: NAS 侧已关闭在线音乐库");
                return null;
            }
            JSONArray ta = o.optJSONArray("tracks");
            if (ta == null || ta.length() == 0) {
                return null;
            }
            String sb = o.optString("base", "");
            List<String[]> all = new ArrayList<String[]>();
            for (int k = 0; k < ta.length(); k++) {
                JSONObject t = ta.getJSONObject(k);
                String file = t.optString("file", "");
                String abs = t.optString("url", "");
                if (abs == null || abs.length() == 0) {
                    abs = joinUrl(sb, file);
                }
                if (abs.length() == 0) {
                    continue;
                }
                // [7]via = "rand"：在线随机批（与「在线搜到的」区分开，
                //   卡片上分别显示「在线随机」/「在线搜到的」，别让用户以为是搜索结果）
                all.add(new String[]{t.optString("title", ""), t.optString("artist", ""),
                        file, abs, t.optString("album", ""), t.optString("duration", ""),
                        jstr(t, "pic"), "rand"});
            }
            if (all.size() == 0) {
                return null;
            }
            if (all.size() <= want) {
                return all;
            }
            int[] a = shuffled(all.size());
            List<String[]> out = new ArrayList<String[]>(want);
            for (int i = 0; i < want; i++) {
                out.add(all.get(a[i]));
            }
            return out;
        } catch (Throwable t) {
            SessionLog.i("online pool fetch failed: " + t);
            return null;
        }
    }

    /**
     * v1.3.178：拉**新的一批**在线随机曲库并缓存（{@code fresh=1} = 每次都换一批）。
     *
     * @return 缓存内容；失败返回 null（调用方据此回落本地曲库）
     */
    public static List<String[]> refreshRandPool(int n) {
        List<String[]> p = fetchOnlinePool(n, true);
        if (p == null || p.size() == 0) {
            return null;
        }
        synchronized (randPool) {
            randPool.clear();
            randPool.addAll(p);
        }
        return p;
    }

    /** v1.3.178：当前缓存的在线随机曲库（快照；可能为空）。 */
    public static List<String[]> randPoolSnapshot() {
        synchronized (randPool) {
            return new ArrayList<String[]>(randPool);
        }
    }

    /**
     * v1.3.178：给 web 用的「在线随机一批」（{@code /api/search?random=1}）。
     *
     * <p>每条带 {@code ri} = 在这批里的下标 —— 页面点哪一条，就把这个 ri 原样回传
     * {@code op=play_rand_at}，服务端据此从同一批里取，不用重新拉一次。</p>
     */
    public static JSONObject randPoolJson(int n) {
        JSONObject o = new JSONObject();
        try {
            List<String[]> p = refreshRandPool(n);
            JSONArray arr = new JSONArray();
            if (p != null) {
                for (int i = 0; i < p.size(); i++) {
                    String[] e = p.get(i);
                    JSONObject it = new JSONObject();
                    it.put("ri", i);
                    it.put("title", e[0]);
                    it.put("artist", e[1]);
                    it.put("album", e.length > 4 ? e[4] : "");
                    it.put("duration", e.length > 5 ? e[5] : "");
                    arr.put(it);
                }
            }
            o.put("total", arr.length());
            o.put("list", arr);
            o.put("random", true);
        } catch (Throwable t) {
            SessionLog.i("randPoolJson: " + t);
        }
        return o;
    }

    /**
     * v1.3.178：把一批在线曲目 append 进 {@link #tracks}，返回它们在 tracks 里的下标。
     *
     * <p>为什么必须 append：{@code pushSongs} 只认 tracks 下标，播出去的每一条都得
     * 在库里有位置（队列可见、能上下曲）。代价是库会慢慢变大 —— 用
     * {@link #ONLINE_APPEND_CAP} 兜底：v1.3.186 起，装不下就**回收最早收录的在线
     * append**（只删 via=online/rand 的尾部条目，本地/池段下标不动），回收量按
     * 「本次缺口」算 ⇒ 一批能全装下，且额度永不卡死。</p>
     */
    private static List<Integer> appendOnlineTracks(List<String[]> items) {
        List<Integer> out = new ArrayList<Integer>();
        if (items == null || items.size() == 0) {
            return out;
        }
        synchronized (tracks) {
            // v1.3.186 ★：判据从「已超额」改成「本次装不下」。
            //   只判 onlineAppends > CAP 会留个半死的边界：额度恰好停在 400 时不回收，
            //   而下面的循环 400<=400 只加 1 条就到 401 ⇒ 用户点「周杰伦」只播 1 首，
            //   下一次再回收 200 ⇒ 199 首 / 1 首交替。按「本次能不能全装下」判就平稳了。
            int need = onlineAppends + items.size() - ONLINE_APPEND_CAP;
            if (need > 0) {
                // 一次多回收一点（至少半个额度）摊销成本，但必须覆盖本次缺口
                int freed = evictOldestOnline(Math.max(ONLINE_APPEND_CAP / 2, need));
                // 计数以库里真实条目为准 —— loadIndex 重建过 / 有些 append 不是
                // online/rand 时，counter 会和实际条目漂移，重数一遍最稳。
                onlineAppends = countOnlineAppends();
                SessionLog.i("online append 额度不够（在库 " + onlineAppends + " + 本次 "
                        + items.size() + " > " + ONLINE_APPEND_CAP
                        + "）→ 回收最早收录的 " + freed + " 条在线歌");
            }
            int reused = 0;
            int added = 0;
            for (int i = 0; i < items.size(); i++) {
                String[] it = items.get(i);
                if (it == null) {
                    continue;
                }
                // v1.3.187 ★ 入库去重：在线段里已经有「同名 + 同歌手」的就复用它的
                //   下标，不再重复入库。两个直接好处：
                //   ① 反复搜同一个歌手不再推高额度 —— 那才是额度被吃光的真正来源
                //      （昨天 247+154+… 一天就干掉 400，大部分是重复的同一批歌）；
                //   ② 跨源同曲（kw/kg 各来一遍）合并成一条，队列里不会出现同一首
                //      歌连播两遍。额度不消耗、tracks 不增长，语义完全不变。
                int ex = findSameOnline(it);
                if (ex >= 0) {
                    Integer k = Integer.valueOf(ex);
                    if (!out.contains(k)) {
                        out.add(k);
                    }
                    reused++;
                    continue;
                }
                if (onlineAppends >= ONLINE_APPEND_CAP) {
                    break;
                }
                tracks.add(it);
                out.add(Integer.valueOf(tracks.size() - 1));
                onlineAppends++;
                added++;
            }
            if (reused > 0) {
                SessionLog.i("online append 去重：复用库里已有 " + reused
                        + " 条，本次新入库 " + added + " 条");
            }
            if (out.size() == 0) {
                SessionLog.i("online append 装不进 0 条：在库 " + onlineAppends
                        + " 条，库里已无可回收的在线条目");
            }
        }
        return out;
    }

    /**
     * v1.3.186：删掉**最早收录**的在线 append 条目，腾额度。
     *
     * <p>只认 {@code via=online/rand}（真 append 的在线歌）：它们全部位于
     * 「本地段 + 池段」之后的**尾部、按收录顺序排列** ⇒ 从头扫等于删最老的，
     * 本地段与 pool 段的下标一个不动，调用方手里已算好的命中下标不受影响。
     * player 队列持条目值拷贝 ⇒ 回收不影响正在播；被回收的歌下次照样能搜到。</p>
     *
     * @return 实际删掉几条
     */
    private static int evictOldestOnline(int target) {
        int freed = 0;
        for (int i = 0; i < tracks.size() && freed < target; ) {
            String[] e = tracks.get(i);
            if (e != null && e.length > 7 && e[7] != null
                    && ("online".equals(e[7]) || "rand".equals(e[7]))) {
                tracks.remove(i);
                freed++;
            } else {
                i++;
            }
        }
        return freed;
    }

    /** v1.3.186：数一遍库里现存的在线 append 条数（让额度计数与真实条目一致）。 */
    private static int countOnlineAppends() {
        int n = 0;
        for (int i = 0; i < tracks.size(); i++) {
            String[] e = tracks.get(i);
            if (e != null && e.length > 7 && e[7] != null
                    && ("online".equals(e[7]) || "rand".equals(e[7]))) {
                n++;
            }
        }
        return n;
    }

    /**
     * v1.3.187：在**在线段**（via 非空：online/rand/pool）里找「同名 + 同歌手」的
     * 已有条目，返回其下标；没有返回 -1。
     *
     * <p>只在线段里找，不碰本地段：本地那首是本地文件，若把它当「在线搜到的」
     * 复用，via 语义、额度计数、以及 play_artist 的「本地 N 首 + 在线 M 首」文案
     * 都会串。</p>
     */
    private static int findSameOnline(String[] it) {
        if (it == null || it.length < 2) {
            return -1;
        }
        String t = songKey(it[0]);
        if (t.length() == 0) {
            return -1;
        }
        String a = songKey(it[1]);
        for (int i = 0; i < tracks.size(); i++) {
            String[] e = tracks.get(i);
            if (e == null || e.length < 8 || e[7] == null || e[7].length() == 0) {
                continue;
            }
            if (t.equals(songKey(e[0])) && a.equals(songKey(e[1]))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * v1.3.187：曲名/歌手归一 —— 去空白 + 转小写。英文歌名大小写与空格不统一
     * （"Shake It Off" / "shakeitoff" 同源），中文不受影响。
     */
    /**
     * v1.3.188：取 JSON 字符串，**把 JSON null 归一成空串**。
     *
     * <p>API22 的 {@code JSONObject.optString(k, def)} 不做 NULL 判断：值为
     * {@code null} 时返回**字面量 "null"**（不是 def）。线上就是这个坑 ——
     * NAS 的 {@code img:null} 进来后封面 URL 变成了字符串 "null"，页面
     * {@code <img src="null">} 直接请求本站 /null。凡是可能为空的字段都走这里。</p>
     */
    private static String jstr(JSONObject o, String k) {
        if (o == null) {
            return "";
        }
        String v = o.optString(k, "");
        return (v == null || "null".equals(v)) ? "" : v;
    }

    private static String songKey(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(" ", "").replace("　", "").trim().toLowerCase();
    }

    // ---------------- v1.3.188：歌词 / 封面（落雪经 NAS mini-lx 中转） ----------------
    //
    // mini-lx 已经把这两件事做完了（还带 1 小时缓存）：
    //   GET /api/lyric?rid=<rid>&source=<kw|kg|wy|tx|mg> → {lines:[{time,text}], lrc}
    //   GET /api/pic?rid=<rid>                          → {url}
    // 设备只管「这首歌是哪个源的哪个 id」—— 惰性地址 rel="r/<source>/<rid>" 里就有，
    // 本地文件/电台没有 ⇒ 用 NAS /api/search 搜一首同名同歌手的顶上（跨源也认）。

    private static final int LRC_CACHE_MAX = 24;
    private static final HashMap<String, JSONObject> lrcCache = new HashMap<String, JSONObject>();
    private static final HashMap<String, String> picCache = new HashMap<String, String>();
    /** 曲名|歌手 → {source, rid}；长度为 0 表示「查过，没有」（别再白跑一次）。 */
    private static final HashMap<String, String[]> ridCache = new HashMap<String, String[]>();
    /** v1.3.190：曲名|歌手 → 候选 {source,rid} 列表（一首歌多个版本，逐个试到有词为止）。 */
    private static final HashMap<String, List<String[]>> ridListCache
            = new HashMap<String, List<String[]>>();

    /**
     * v1.3.188：取歌词（顺带把封面也带回来）。{@code title} 为空时取「当前曲目」。
     *
     * <p>页面拿到的就是 {@code {ok,title,artist,pic,lines:[{time,text}]}} ——
     * 逐行歌词带时间轴，前端按播放位置高亮当前行。</p>
     */
    public static JSONObject lyricJson(String title, String artist) {
        JSONObject o = new JSONObject();
        try {
            String t = (title == null || title.length() == 0) ? curTitle() : title;
            String a = (artist == null || artist.length() == 0) ? curArtist : artist;
            o.put("title", t);
            o.put("artist", a);
            /* v1.3.190 ★：同一首歌在落雪上往往有好几个 rid（原版 / Live / 伴奏 /
               颁奖典礼现场…），**有的带词有的不带**。以前只取第一个候选，撞上没词的
               就报「这首歌没有歌词」，还把那个坏 rid 缓存住了 —— 用户在外面明明
               搜得到词。现在拿候选列表逐个试，第一个有词的就采信。 */
            List<String[]> cands = ridCandidates(t, a);
            if (cands.isEmpty()) {
                o.put("ok", false);
                o.put("msg", "这首歌没有在线信息（本地文件 / 电台 / 没匹配到在线源），取不到歌词");
                return o;
            }
            String tried = "";
            for (int ci = 0; ci < cands.size(); ci++) {
                String[] rr = cands.get(ci);
                String key = rr[0] + ":" + rr[1];
                tried += (tried.length() > 0 ? "," : "") + key;
                JSONObject lj = lrcCache.get(key);
                if (lj == null) {
                    String b = nasBase();
                    if (b == null) {
                        if (ci == 0) {
                            o.put("ok", false);
                            o.put("msg", "音乐库地址不可用");
                            return o;
                        }
                        break;
                    }
                    String js = httpGet(b + "/api/lyric?rid=" + enc(rr[1])
                            + "&source=" + enc(rr[0]), 7000);
                    lj = new JSONObject(js);
                    if (lrcCache.size() > LRC_CACHE_MAX) {
                        lrcCache.clear();
                    }
                    lrcCache.put(key, lj);
                }
                JSONArray lines = lj.optJSONArray("lines");
                int n = (lines == null) ? 0 : lines.length();
                if (n > 0) {
                    o.put("ok", true);
                    o.put("lines", lines);
                    SessionLog.i("lyric 命中 " + key + "（第 " + (ci + 1) + "/"
                            + cands.size() + " 个候选：" + tried + "）");
                    String pic = picOf(key, rr[1]);
                    if (pic.length() > 0) {
                        o.put("pic", pic);
                        curPic = pic;
                    }
                    return o;
                }
            }
            o.put("ok", false);
            o.put("lines", new JSONArray());
            o.put("msg", "这首歌没有歌词（试过 " + tried + "）");
        } catch (Throwable e) {
            // ★ JSONObject.put 本身抛受检 JSONException ⇒ catch 块里再包一层，
            //   否则编译器报「未报告的异常」（catch 里没有第二道兜底）。
            try {
                o.put("ok", false);
                o.put("msg", "取歌词失败：" + e);
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /**
     * v1.3.188：这首歌的 {@code {source, rid}}。
     *
     * <p>先扫库内条目看 {@code rel}（{@code r/<source>/<rid>}），命中零开销；
     * 扫不到（本地文件 / 电台 / DLNA 推流）才去 NAS 搜一首同名同歌手的顶上 ——
     * 跨源匹配由落雪负责，本地歌也能拿到歌词。</p>
     */
    private static String[] ridOf(String title, String artist) {
        String k = songKey(title) + "|" + songKey(artist);
        String[] c = ridCache.get(k);
        if (c != null) {
            return c.length == 0 ? null : c;
        }
        try {
            String kt = songKey(title);
            String ka = songKey(artist);
            synchronized (tracks) {
                for (int i = 0; i < tracks.size(); i++) {
                    String[] e = tracks.get(i);
                    if (e == null || e.length < 3 || e[2] == null) {
                        continue;
                    }
                    if (!kt.equals(songKey(e[0]))) {
                        continue;
                    }
                    if (ka.length() > 0 && !ka.equals(songKey(e[1]))) {
                        continue;
                    }
                    String[] rr = parseRel(e[2]);
                    if (rr != null) {
                        if (ridCache.size() > LRC_CACHE_MAX * 4) {
                            ridCache.clear();
                        }
                        ridCache.put(k, rr);
                        return rr;
                    }
                }
            }
            String b = nasBase();
            if (b != null) {
                String js = httpGet(b + "/api/search?q="
                        + enc((artist.length() > 0 ? (title + " " + artist) : title))
                        + "&n=1&source=all", 5000);
                JSONArray list = new JSONObject(js).optJSONArray("list");
                if (list != null && list.length() > 0) {
                    JSONObject s = list.optJSONObject(0);
                    String src = s == null ? "" : s.optString("source", "");
                    String rid = s == null ? "" : s.optString("rid", "");
                    if (src.length() > 0 && rid.length() > 0) {
                        String[] rr = new String[]{src, rid};
                        if (ridCache.size() > LRC_CACHE_MAX * 4) {
                            ridCache.clear();
                        }
                        ridCache.put(k, rr);
                        return rr;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 查不到就当没有，下一次换曲会重试
        }
        ridCache.put(k, new String[0]);
        return null;
    }

    /**
     * v1.3.190：这首歌的**全部候选** {@code {source, rid}}（去重、有序）。
     *
     * <p>为什么必须列一堆：落雪里一首歌常常有多个版本 —— 原版带词、Live/伴奏/
     * 现场版不带词（实测「谢谢你的爱 / 刘德华」：kw:13851692 有 34 行，
     * kw:1044733(Live)、kw:658124、kw:90320964(现场) 全是 0 行）。只取第一条就会
     * 随机撞空。候选来源：① 库内同曲名的所有条目（零开销）；② NAS
     * {@code /api/search} 前几条（标题/歌手对得上才收，避免混进别人的同名歌）。
     * 结果按「歌名|歌手」缓存，歌词本身再按 rid 缓存 ⇒ 重复取词几乎不打网络。</p>
     */
    private static List<String[]> ridCandidates(String title, String artist) {
        String k = songKey(title) + "|" + songKey(artist);
        List<String[]> c = ridListCache.get(k);
        if (c != null) {
            return c;
        }
        List<String[]> out = new ArrayList<String[]>();
        String kt = songKey(title);
        String ka = songKey(artist);
        try {
            synchronized (tracks) {
                for (int i = 0; i < tracks.size() && out.size() < 4; i++) {
                    String[] e = tracks.get(i);
                    if (e == null || e.length < 3 || e[2] == null) {
                        continue;
                    }
                    if (!kt.equals(songKey(e[0]))) {
                        continue;
                    }
                    if (ka.length() > 0 && !ka.equals(songKey(e[1]))) {
                        continue;
                    }
                    String[] rr = parseRel(e[2]);
                    if (rr != null && !hasRid(out, rr)) {
                        out.add(rr);
                    }
                }
            }
            String b = nasBase();
            if (b != null) {
                String js = httpGet(b + "/api/search?q="
                        + enc((artist.length() > 0 ? (title + " " + artist) : title))
                        + "&n=5&source=all", 6000);
                JSONArray list = new JSONObject(js).optJSONArray("list");
                List<String[]> loose = new ArrayList<String[]>();
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject s = list.optJSONObject(i);
                    if (s == null) {
                        continue;
                    }
                    String src = s.optString("source", "");
                    String rid = s.optString("rid", "");
                    if (src.length() == 0 || rid.length() == 0) {
                        continue;
                    }
                    String[] rr = new String[]{src, rid};
                    if (hasRid(out, rr) || hasRid(loose, rr)) {
                        continue;
                    }
                    /* 标题/歌手对得上才当候选（不然「谢霆锋的谢谢你的爱」会混进来）；
                       歌手空或标题差一点（Live 后缀）的放宽松队列兜底。 */
                    boolean okT = kt.equals(songKey(s.optString("title", "")));
                    boolean okA = ka.length() == 0 || ka.equals(songKey(s.optString("artist", "")));
                    if (okT && okA) {
                        if (out.size() < 6) {
                            out.add(rr);
                        }
                    } else if (okT || okA) {
                        loose.add(rr);
                    }
                }
                for (int i = 0; i < loose.size() && out.size() < 6; i++) {
                    out.add(loose.get(i));
                }
            }
        } catch (Throwable ignored) {
            // 候选拿不全没关系，有一个算一个
        }
        if (ridListCache.size() > LRC_CACHE_MAX * 4) {
            ridListCache.clear();
        }
        ridListCache.put(k, out);
        return out;
    }

    /** v1.3.190：候选列表里已经有这个 {source,rid} 了吗（避免重复试同一个）。 */
    private static boolean hasRid(List<String[]> l, String[] rr) {
        for (int i = 0; i < l.size(); i++) {
            String[] x = l.get(i);
            if (x != null && x.length > 1 && x[0].equals(rr[0]) && x[1].equals(rr[1])) {
                return true;
            }
        }
        return false;
    }

    /** v1.3.188：{@code r/<source>/<rid>} → {source, rid}；不是惰性地址返回 null。 */
    private static String[] parseRel(String rel) {
        if (rel == null || !rel.startsWith("r/")) {
            return null;
        }
        int a = rel.indexOf('/', 2);
        if (a < 0) {
            return null;
        }
        String src = rel.substring(2, a);
        String rid = rel.substring(a + 1);
        if (src.length() == 0 || rid.length() == 0) {
            return null;
        }
        return new String[]{src, rid};
    }

    /** v1.3.188：封面 URL（NAS {@code /api/pic}，服务端缓存 1 小时）。 */
    private static String picOf(String key, String rid) {
        String c = picCache.get(key);
        if (c != null) {
            return c;
        }
        String u = "";
        try {
            String b = nasBase();
            if (b != null) {
                String js = httpGet(b + "/api/pic?rid=" + enc(rid), 5000);
                u = jstr(new JSONObject(js), "url");   // JSON null ⇒ ""（见 jstr 注释）
            }
        } catch (Throwable ignored) {
            u = "";
        }
        if (picCache.size() > LRC_CACHE_MAX) {
            picCache.clear();
        }
        picCache.put(key, u);
        return u;
    }

    /** v1.3.188：起播时补一次封面（异步，拿不到就算了 —— 页面回落成转碟）。 */
    private static void fetchPicAsync(final String title, final String artist) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String[] rr = ridOf(title, artist);
                    if (rr == null) {
                        return;
                    }
                    String p = picOf(rr[0] + ":" + rr[1], rr[1]);
                    // 取回来的可能已经不是当前这首了（用户切歌比网络快）⇒ 只在对得上时落
                    if (p.length() > 0 && songKey(title).equals(songKey(curTitle()))) {
                        curPic = p;
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "pb-pic").start();
    }

    /**
     * v1.3.178：搜一个关键词的在线曲目，并**并行取链**成可直接播的曲目列表。
     *
     * <p>「随机播放周杰伦的歌」这类需求：本地池里往往只有寥寥几首，用户要的是
     * 本地 + 在线合起来的那一批。慢的不是搜索（1 次），是取链（每首 1 次）⇒
     * 这里用固定并发的小线程池一起取，把 12 首的耗时压到一次取链的量级。</p>
     *
     * @param q 关键词（歌手名 / 歌名）
     * @param max 最多要几条
     * @param searchMs 搜索超时
     * @param budgetMs 取链的**总预算**（到点就放弃还没回来的，拿多少算多少）
     * @return 可播曲目（String[8]）；任何一步失败返回 null
     */
    private static List<String[]> onlineTracksFor(String q, int max, int searchMs, long budgetMs) {
        try {
            String b = nasBase();
            if (b == null || q == null || q.trim().length() == 0) {
                return null;
            }
            int lim = max > 0 ? max : 10;
            String js = httpGet(b + "/api/search?q=" + URLEncoder.encode(q.trim(), "UTF-8")
                    + "&n=" + lim + "&source=all", searchMs);
            if (js == null || js.length() == 0) {
                return null;
            }
            JSONArray list = new JSONObject(js).optJSONArray("list");
            if (list == null || list.length() == 0) {
                return null;
            }
            List<JSONObject> cand = new ArrayList<JSONObject>();
            for (int i = 0; i < list.length() && cand.size() < lim; i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                if (s.optString("source", "").length() == 0
                        || s.optString("rid", "").length() == 0) {
                    continue;
                }
                cand.add(s);
            }
            // v1.3.179 ★★：能惰就惰 —— 一条 /r/<source>/<rid> 就当一首推过去，
            // 把「取链」从「推列表之前」挪到「真正播放那一刻」（NAS 现场取 + 流式转发）。
            // 这是把逐条取链换掉的**唯一理由**：每首取链要走五级兜底（各级超时 12s），
            // 12 首就是几十秒，5s 预算一到只剩 0~4 首（实测孙燕姿 0 首、周杰伦 4 首）。
            if (lazyOk(b)) {
                return lazyTracks(b, cand);
            }
            // 老 mini-lx（没有 /r/ 路由）照原路逐条取链，功能不倒退。
            // ★★ 但这条路上每一条都要真打上游，必须收着：
            //   20 条并发砸下去 = 送上门被限流甚至封 IP（实测并发 12 时整批 0 成功）。
            //   被封之后连「起播时取链」都会一起哑火 ⇒ 这里宁可少给几首。
            //   NAS 侧还有一道取链节流闸（并发 2 + 250ms 间隔）兜底。
            int cap = Math.min(cand.size(), LEGACY_URL_MAX);
            return resolveOnlineUrls(b, cand.subList(0, cap), LEGACY_URL_CONC, 9000, budgetMs);
        } catch (Throwable t) {
            SessionLog.i("online tracks for 「" + q + "」 failed: " + t);
            return null;
        }
    }

    /** -1 = 还没探过；0 = 这台 mini-lx 没有 /r/；1 = 支持。 */
    private static volatile int lazySupported = -1;
    /** 上次探测的时刻 —— 「不支持」的结论只信 10 分钟（NAS 那边一升级就能自己接上）。 */
    private static volatile long lazyCheckedAt = 0;

    /**
     * v1.3.179：这台 NAS 上的 mini-lx 支不支持**惰性地址** {@code /r/<source>/<rid>}。
     *
     * <p>探针是 {@code GET /r/ping}（新路由，极轻）。只有新版才回
     * {@code {"ok":true,"lazy":true}}；老版没有这段路由，请求落空 ⇒ 判定不支持。
     * 支持的结论一直记住（不可能自己消失）；**不支持的结论只信 10 分钟** ——
     * 用户在 NAS 上重建了容器也不必重启音箱，到点自己探第二次就接上了。</p>
     */
    private static boolean lazyOk(String b) {
        long now = System.currentTimeMillis();
        if (lazySupported >= 0
                && (lazySupported == 1 || now - lazyCheckedAt < 600000)) {
            return lazySupported == 1;
        }
        boolean ok = false;
        try {
            String js = httpGet(b + "/r/ping", 2500);
            ok = js != null && js.indexOf("\"lazy\"") >= 0;
        } catch (Throwable t) {
            ok = false;
        }
        lazyCheckedAt = now;
        lazySupported = ok ? 1 : 0;
        SessionLog.i("mini-lx 惰性地址 /r/：" + (ok ? "支持（推列表不再逐条取链）"
                : "不支持（回落逐条取链）"));
        return ok;
    }

    /**
     * v1.3.179：把搜索结果**原样**变成可推的曲目，一条 HTTP 都不多发。
     *
     * <p>url 形如 {@code http://NAS:8100/r/kw/95769?title=我不难过&artist=孙燕姿}，
     * 与曲库池的 {@code /s/<i>} 完全同构：现在只是「登记」，等 player 真要放这一首、
     * 来 GET 这个地址时，NAS 才去取真链并流式转发。代价是「能不能放」要等它开口才知道，
     * 换来的是：推 12 首和推 1 首一样快，且永远不会因为上游抽风而整批哑火。</p>
     */
    private static List<String[]> lazyTracks(String b, List<JSONObject> items) {
        List<String[]> out = new ArrayList<String[]>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject s = items.get(i);
            if (s == null) {
                continue;
            }
            String src = s.optString("source", "");
            String rid = s.optString("rid", "");
            if (src.length() == 0 || rid.length() == 0) {
                continue;
            }
            String title = s.optString("title", "");
            String artist = s.optString("artist", "");
            String rel = "r/" + src + "/" + rid;
            StringBuilder sb = new StringBuilder();
            sb.append(b).append('/').append(rel)
                    .append("?title=").append(enc(title))
                    .append("&artist=").append(enc(artist));
            out.add(new String[]{
                    title,
                    artist,
                    rel,
                    sb.toString(),
                    s.optString("album", ""),
                    s.optString("duration", ""),
                    // v1.3.188：[6] 封面 —— 搜索结果本来就带 img（kuwo/QQ CDN），
                    //   顺手带上，省掉一次 /api/pic（封面走浏览器直连 CDN，不占 NAS）。
                    jstr(s, "img"),
                    "online"});
        }
        return out;
    }

    /** URLEncoder 会抛受检异常，包一层让调用处能直接内联。 */
    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * v1.3.178：并行取直链。
     *
     * <p>背景：一次在线搜能回几十条，但每条都要 {@code /api/url} 拿直链（内部还要
     * 走 lxserver 中继 + 五级兜底）。串行做 12 首就是十几秒，语音链路等不起。
     * 这里开固定 {@code conc} 个线程分块取，全部 join 后再按原序挑出成功的 ——
     * 单条失败只丢那一首，不影响整批。</p>
     *
     * <p>★ 三个「别把上游惹毛」的设定（都是实测踩出来的）：
     * <ol>
     *   <li>{@code conc} 固定 4 —— 开到 12 时上游直接限流，半数请求 5s 超时，
     *       整批一首都拿不到（比并发 4 慢还更差）；</li>
     *   <li>线程**错峰启动**（每 60ms 一个）—— 把「同时 12 个请求」摊成细流；</li>
     *   <li>{@code budgetMs} 到点即走 —— 拿多少算多少，绝不为了等齐 12 首
     *       让用户在音箱前多站几秒（mini-lx 有直链缓存，下次再点基本秒回）。</li>
     * </ol></p>
     */
    private static List<String[]> resolveOnlineUrls(final String b, final List<JSONObject> items,
                                                    int conc, final int perMs, long budgetMs) {
        final int n = items.size();
        if (n == 0) {
            return new ArrayList<String[]>();
        }
        final String[] got = new String[n];
        int c = Math.min(conc > 0 ? conc : 6, n);
        int per = (n + c - 1) / c;
        List<Thread> ts = new ArrayList<Thread>();
        for (int w = 0; w < c; w++) {
            final int from = w * per;
            final int to = Math.min(n, from + per);
            if (from >= to) {
                break;
            }
            Thread t = new Thread(new Runnable() {
                public void run() {
                    for (int i = from; i < to; i++) {
                        try {
                            JSONObject s = items.get(i);
                            String uj = httpGet(b + "/api/url?rid="
                                    + URLEncoder.encode(s.optString("rid", ""), "UTF-8")
                                    + "&source="
                                    + URLEncoder.encode(s.optString("source", ""), "UTF-8")
                                    + "&q=128k&title="
                                    + URLEncoder.encode(s.optString("title", ""), "UTF-8")
                                    + "&artist="
                                    + URLEncoder.encode(s.optString("artist", ""), "UTF-8"),
                                    perMs);
                            if (uj == null || uj.length() == 0) {
                                continue;
                            }
                            String u = new JSONObject(uj).optString("url", "");
                            if (u.length() > 0) {
                                got[i] = u;
                            }
                        } catch (Throwable ignored) {
                            // 单条失败就跳过，不拖累整批
                        }
                    }
                }
            }, "pb-url" + w);
            ts.add(t);
            t.start();
            // 错峰：别在 0ms 内把 4 个请求同时砸出去（上游限流时整批超时）
            if (URL_STAGGER > 0 && w < c - 1) {
                try {
                    Thread.sleep(URL_STAGGER);
                } catch (Throwable ignored) {
                }
            }
        }
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < ts.size(); i++) {
            long left = budgetMs - (System.currentTimeMillis() - t0);
            if (left <= 0) {
                SessionLog.i("online url budget out (" + budgetMs + "ms): 放弃剩下的，"
                        + "先播已经取到链的");
                break;
            }
            try {
                ts.get(i).join(left);
            } catch (Throwable ignored) {
            }
        }
        List<String[]> out = new ArrayList<String[]>();
        for (int i = 0; i < n; i++) {
            if (got[i] == null || got[i].length() == 0) {
                continue;
            }
            JSONObject s = items.get(i);
            out.add(new String[]{s.optString("title", ""), s.optString("artist", ""), "",
                    got[i], s.optString("album", ""), s.optString("duration", ""), "", "online"});
        }
        return out;
    }

    /** v1.3.117：电台名匹配（双向 contains —— 用户可能说「先锋898电台」）。 */
    private static String[] matchRadio(String q) {
        String k = (q == null ? "" : q.trim().toLowerCase());
        if (k.length() == 0) {
            return null;
        }
        for (String[] r : radios) {
            String n = r[0].toLowerCase();
            if (n.contains(k) || k.contains(n)) {
                return r;
            }
        }
        return null;
    }

    private static String radioNames() {
        return radioNames(radios.size());
    }

    /**
     * v1.3.118：电台可能有几十个（用户一次编进来 47 个台），
     * 全列出来会把 list / 报错提示撑得又长又难读 —— 只列前 limit 个并给总数。
     */
    private static String radioNames(int limit) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(limit, radios.size());
        for (int i = 0; i < n; i++) {
            sb.append(radios.get(i)[0]).append("、");
        }
        if (radios.size() > n) {
            sb.append("…等共 ").append(radios.size()).append(" 个台");
        }
        return sb.toString();
    }

    /**
     * v1.3.117：play_url 的本体。
     *
     * 背景（用户原话：「网络电台好像播不了，小智说放起来了，但是没声」）：
     * 设备日志里模型实际传的是 {"url":"http://fm91.8"} 和 {"url":"先锋898"}
     * —— 它把**电台名**当成网址塞进了 play_url；而旧代码只要 msg(1,…) 把消息
     * 发出去就返回 true ⇒ 一律回「OK 已开始播放」。player 拿着根本不存在的地址
     * 什么都播不出来，用户端看到的就是「说放起来了但没声」。
     *
     * 三件事：① 不是网址的输入先去电台/曲库里认领；② 是网址的先探活，连不上
     * 就报错、不再谎报成功；③ 走网络流专用的长 kick。
     */
    private static String playUrl(String url, JSONObject args) {
        if (!isHttpUrl(url)) {
            if (!idxLoaded) {
                loadIndex();
            }
            if (isAllRadio(url)) {
                return playAllRadios("play_url->all_radio", -1);
            }
            String[] rr = matchRadio(url);
            if (rr != null) {
                return playRadio(rr, "play_url->radio", false);
            }
            if (idxLoaded) {
                List<Integer> hits = matchTracks(url, false);
                if (hits.size() > 0) {
                    int hit = hits.get(0).intValue();
                    String[] e = tracks.get(hit);
                    String who = e[1].length() > 0 ? (e[0] + " - " + e[1]) : e[0];
                    int n = pushSongs("play_url->song", hitFirstIdx(hit));
                    if (n > 0) {
                        pushed = true;
                        kickPlay("play_url->song", startDelay(n), 2);
                        return "OK 「" + url + "」不是网址，按歌名理解成《" + who
                                + "》，正在播放";
                    }
                }
            }
            return "ERR 「" + url + "」不是一个网址，电台和曲库里也没找到它。"
                    + "要播电台请说电台名（"
                    + (radios.size() > 0 ? radioNames() : "当前没配电台")
                    + "），不要自己拼网址";
        }
        String perr = probeUrl(url);
        if (perr != null) {
            return "ERR 这个地址播不了：" + perr + "（" + url + "）";
        }
        // v1.3.153：标题优先用调用方给的（搜索/曲库点播传的是真实歌名），
        // 没有才退回「网络音频」（MCP 或语音随手给一个裸网址的情形）。
        String nm = (args == null) ? "" : args.optString("title", "").trim();
        String ar = (args == null) ? "" : args.optString("artist", "").trim();
        if (nm.length() == 0) {
            nm = "网络音频";
        }
        if (msg(1, oneJson(nm, url, ar))) {
            pushed = true;
            kickPlay("play_url", NET_STREAM_KICK_MS, 2);
            SessionLog.i("play url (msgcenter): " + url);
            return "OK 已开始播放（网络流，可能要缓冲几秒才出声）";
        }
        try {
            return setUri(url);
        } catch (Throwable t) {
            return "ERR 播放通道不可用: " + t;
        }
    }

    /**
     * v1.3.248：播本地缓存文件（闹钟铃声）。
     *
     * <p>参数：{@code f} 绝对路径（或 file:// 开头，会剥掉）、{@code title} /
     * {@code artist} 用于播放栏显示。</p>
     *
     * <p>★ 走 {@code file://} 让播放引擎自己读文件，不经我们的 HTTP：
     * 手搓的 8080/8088 都是 HTTP/1.0 + 整包 byte[]，几 MB 的歌既要进内存又没有
     * Range，没必要。Exo 2.18 的 DefaultDataSource 原生支持 file scheme，
     * player 进程（uid system）也有 READ_EXTERNAL_STORAGE，读 /sdcard 没问题。</p>
     */
    private static String playLocal(JSONObject args) {
        String f = (args == null) ? "" : args.optString("f", "").trim();
        if (f.startsWith("file://")) {
            f = f.substring(7);
        }
        if (f.length() == 0) {
            return "ERR 缺少 f（本地文件路径）";
        }
        File file = new File(f);
        if (!file.isFile() || file.length() <= 0) {
            return "ERR 文件不在：" + f;
        }
        String nm = (args == null) ? "" : args.optString("title", "").trim();
        String ar = (args == null) ? "" : args.optString("artist", "").trim();
        if (nm.length() == 0) {
            nm = file.getName();
        }
        String url = "file://" + file.getAbsolutePath();
        if (msg(1, oneJson(nm, url, ar))) {
            pushed = true;
            kickPlay("play_local", NET_STREAM_KICK_MS, 2);
            SessionLog.i("play local: " + url);
            return "OK 播放本地缓存：" + nm;
        }
        try {
            return setUri(url);
        } catch (Throwable t) {
            return "ERR 本地播放通道不可用: " + t;
        }
    }

    /** v1.3.117：电台统一出口。主通道 = 原厂消息总线；forceDlna 时直接走 DLNA。 */
    private static String playRadio(String[] r, String why, boolean forceDlna) {
        String url = r[1];
        if (!forceDlna && msg(1, oneJson(r[0], url))) {
            pushed = true;
            /* v1.3.196：切台 ⇒ 上一首歌的专辑/时长/来源当场作废。
               直播流要等 player 回推「当前曲目」才有 title（实测 1~3s），
               这段空窗里卡片仍挂着上一首的信息：播的是电台，却显示上首歌的专辑。 */
            notePlaying(r[0]);
            // 直播流没有 Content-Length，player 要先填满缓冲才 prepared，比本地 mp3
            // 慢得多 —— 600ms×2 那套 kick 打不中（表现就是「说放起来了但没声」）。
            // 800ms 起、最多 5 次；但只要 AudioFlinger 里已有活跃音轨就立即收手
            // （★ v1.3.192：每多补一脚都要重开一次声卡 ⇒ 电台起播必卡 4 秒，见 kickPlay 注释）。
            kickPlay(why, NET_STREAM_KICK_MS, 2);
            SessionLog.i("play radio (" + why + ") msgcenter: " + r[0] + " -> " + url);
            return "OK 正在播放电台 " + r[0] + "（网络流，可能要缓冲几秒才出声）";
        }
        try {
            String s = setUri(url);
            SessionLog.i("play radio (" + why + ") dlna: " + r[0] + " -> " + url);
            return s.startsWith("OK") ? ("OK 正在播放电台 " + r[0])
                    : (s + " (" + r[0] + ")");
        } catch (Throwable t) {
            return "ERR 电台 " + r[0] + " 播放通道不可用: " + t;
        }
    }

    /**
     * v1.3.119：一次把所有电台排进播放列表（用户原话「把所有广播电台添加到播放列表」，
     * 之前一个 msg(1,…) 只带一首 ⇒ 只能放一个台、也没法用「下一首」换台）。
     *
     * 走的是和歌曲同一条通道（playSpecifiedMusic + 整个 itemList），player 自己
     * 持有列表，next/prev 才有意义。差别只在于：
     *   - 条目来自 radios 而不是 tracks；
     *   - 直播流**没有结尾**，不会自动进下一首，换台只能手动 next/prev；
     *   - 首播缓冲比本地 mp3 慢 ⇒ 用网络流那套长 kick（800ms 起、共 5 次）。
     *
     * @param start 从哪个台开始（-1 = 列表第一个）
     */
    private static String playAllRadios(String why, int start) {
        if (radios.size() == 0) {
            return "ERR 电台列表是空的（NAS 的 radios.json 里加，或在音乐服务网页上添加）";
        }
        int n = pushRadios(why, start);
        if (n <= 0) {
            return "ERR 播放通道不可用（消息总线发送失败）";
        }
        pushed = true;
        // v1.3.196：同 playRadio —— 「全部电台连播」也要当场清掉上一首歌的信息
        notePlaying(radios.get((start >= 0 && start < radios.size()) ? start : 0)[0]);
        // 顺序播放：47 个台排成一张表，next 就该是下一个台（默认随机会乱跳）
        setPlayMode(0);
        kickPlay(why, NET_STREAM_KICK_MS, 2);
        int first = (start >= 0 && start < radios.size()) ? start : 0;
        SessionLog.i("play all radios (" + why + "): " + n + " stations, first="
                + radios.get(first)[0] + ", start=" + start);
        return "OK 已把 " + n + " 个电台排进播放列表，正在播第一个「"
                + radios.get(first)[0]
                + "」。直播流要缓冲几秒才出声；说「下一首」「上一首」换台"
                + "（直播流没有结尾，不会自动跳下一个台）";
    }

    /**
     * v1.3.120：把「第 3 个电台 / 第三个 / 3 号台」解析成 **1-based 序号**（失败返回 -1）。
     *
     * 背景（用户原话）：「当我说要播放第几个电台的时候，只会把电台全部放进列表然后播放第一个」
     * —— 模型手里没有「第几个」这个参数可用，只能调 play_radio_all 且不传 name，
     * 于是永远从第一个开始。
     *
     * 判据收得很紧（必须整体就是序号，或「第 + 数 + 个/号/台 (+电台)」），
     * 否则「FM91.8」「动听101」这类**台名里带数字**的会被误判成序号。
     */
    private static int radioOrdinal(String q) {
        if (q == null) {
            return -1;
        }
        String s = q.trim().replaceAll("\\s+", "");
        if (s.length() == 0) {
            return -1;
        }
        if (s.matches("\\d{1,3}")) {
            int v = Integer.parseInt(s);
            return v >= 1 ? v : -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^第?([0-9]{1,3}|[一二三四五六七八九十]{1,3})[个号台首](电台|广播|台)?$")
                .matcher(s);
        if (m.matches()) {
            String n = m.group(1);
            int v = n.matches("\\d+") ? Integer.parseInt(n) : cn2num(n);
            return v >= 1 ? v : -1;
        }
        return -1;
    }

    /** 中文数字 → 阿拉伯数字（1~99，够电台列表用；解析不了返回 -1）。 */
    private static int cn2num(String s) {
        String d = "\u96f6\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d";  // 零一二三四五六七八九
        if (s == null || s.length() == 0) {
            return -1;
        }
        if (s.length() == 1) {
            if (s.charAt(0) == '\u5341') {   // 十
                return 10;
            }
            int i = d.indexOf(s.charAt(0));
            return i > 0 ? i : -1;
        }
        int shi = s.indexOf('\u5341');       // 十
        if (shi < 0) {
            return -1;
        }
        int hi = 1;
        int lo = 0;
        if (shi > 0) {
            int i = d.indexOf(s.charAt(shi - 1));
            if (i <= 0) {
                return -1;
            }
            hi = i;
        }
        if (shi + 1 < s.length()) {
            int i = d.indexOf(s.charAt(shi + 1));
            if (i <= 0) {
                return -1;
            }
            lo = i;
        }
        return hi * 10 + lo;
    }

    /** v1.3.119：把「所有电台 / 全部电台 / 每个电台」这类泛词认出来。
     *
     * 必须同时含「电台/广播/台」和「所有/全部/每个」才认 —— 否则「上海音乐台」
     * 这种带「台」的真台名会被误判成「全部」。
     */
    private static boolean isAllRadio(String q) {
        if (q == null) {
            return false;
        }
        String s = q.trim();
        if (s.length() == 0) {
            return false;
        }
        boolean obj = s.contains("电台") || s.contains("广播") || s.contains("台");
        boolean all = s.contains("所有") || s.contains("全部") || s.contains("每个")
                || s.contains("全都") || s.contains("所有台");
        return obj && all;
    }

    /** radios 里这个条目的下标（matchRadio 返回的是 String[] 引用，按引用找）。 */
    private static int radioIndexOf(String[] rr) {
        for (int i = 0; i < radios.size(); i++) {
            if (radios.get(i) == rr) {
                return i;
            }
        }
        return -1;
    }

    /** 是不是一个至少看起来像网址的东西（http/https + 有 host）。 */
    private static boolean isHttpUrl(String s) {
        String l = s.toLowerCase();
        if (!l.startsWith("http://") && !l.startsWith("https://")) {
            return false;
        }
        try {
            String host = new URL(s).getHost();
            return host != null && host.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v1.3.117：播之前先探一下地址在不在 —— 只取响应头，**绝不读 body**
     * （读 body 遇上直播流会一直有数据，read timeout 永远不会触发，就是
     * v1.3.116 那个「地址删不掉」的同款陷阱）。
     *
     * @return null = 可以用；否则是给人看的原因。
     */
    private static String probeUrl(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setRequestProperty("Icy-MetaData", "0");
            int code = c.getResponseCode();
            if (code >= 400 || code == 405) {
                // 不少源不支持 HEAD，退 GET 拿头（不读 body）
                c.disconnect();
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(3000);
                c.setReadTimeout(4000);
                c.setRequestProperty("Icy-MetaData", "0");
                code = c.getResponseCode();
            }
            if (code >= 400) {
                return "服务器返回 HTTP " + code;
            }
            String ct = c.getHeaderField("Content-Type");
            if (ct != null && ct.toLowerCase().startsWith("text/html")) {
                return "返回的是网页（" + ct + "），不是音频";
            }
            return null;
        } catch (Throwable t) {
            String m = String.valueOf(t);
            if (m.contains("UnknownHost") || m.contains("Unable to resolve")) {
                return "域名解析失败（" + hostOf(url) + " 这个域名不存在或没网）";
            }
            if (m.contains("ECONNREFUSED") || m.contains("refused")) {
                return "连接被拒绝（" + hostOf(url) + " 端口不通）";
            }
            if (m.contains("timed out") || m.contains("timeout")
                    || m.contains("Timeout")) {
                return "连接超时（" + hostOf(url) + " 3 秒没响应）";
            }
            return m;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return url;
        }
    }

    private static String urlOf(String[] e) {
        if (e == null) {
            return "";
        }
        if (e.length == 2) {
            return e[1];                 // 电台：本来就是绝对地址
        }
        if (e.length >= 4 && e[3] != null && e[3].length() > 0) {
            return e[3];                 // v1.3.102：入库时已算好的最终 URL
        }
        return joinUrl(base, e[2]);      // 兜底（老格式）
    }

    private static String joinUrl(String b, String file) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(b == null ? "" : b);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') {
                sb.append('/');
            }
            String[] segs = file.split("/");
            for (int i = 0; i < segs.length; i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(URLEncoder.encode(segs[i], "UTF-8").replace("+", "%20"));
            }
            return sb.toString();
        } catch (Throwable t) {
            return b + file;
        }
    }

    // ---------------- MCP 分发 ----------------

    /** MCP tools/call 入口。op 是去掉 "self.r1player." 前缀后的动作名。 */
    /**
     * v1.3.130：把唤醒期间被系统暂停的音乐接回去。
     *
     * 等价于原厂那条链的终点：
     * <pre>
     *   PhicommExternalMusicHandler.doResume()
     *     → CommonPlayer.resume()
     *       → PhicommMusicController.play()
     *         → MessageDispatchManager.sendMessage(4, 3, -1, null)
     *             其中 4 = MSG_TYPE_PLAYER、3 = play、obj = null
     * </pre>
     * 中间两跳只是转发，所以我们直接发那一条总线消息（就是本类的 {@code msg(3, null)}）。
     * 注意 json 传 null —— 原厂控制指令不挂 Parcelable。
     */
    public static boolean resumeMusic() {
        boolean ok = msg(3, null);
        if (ok) {
            notePlaying(null);      // v1.3.136：写盘（自动恢复也算「在播」）
        }
        Log.i(TAG, "resumeMusic (subType=3): " + (ok ? "sent" : "msgcenter not ready"));
        return ok;
    }

    /**
     * v1.3.132：暂停音乐（subType=2 = 原厂 {@code CommonPlayer.pause()}）。
     *
     * 用途 = TTS 与音乐互斥（Web「小智说话时暂停音乐」）：小智每轮开口前让路，
     * 说完由 {@link MusicResume} 接回。没在播时发这条是空转，无害。
     */
    public static boolean pauseMusic() {
        boolean ok = msg(2, null);
        if (ok) {
            noteStopped();          // v1.3.136：写盘（让路暂停也是真暂停）
        }
        Log.i(TAG, "pauseMusic (subType=2): " + (ok ? "sent" : "msgcenter not ready"));
        return ok;
    }

    /**
     * v1.3.132：粗粒度「音乐是不是在播」。
     *
     * <p>只统计**经过本类**的播放动作（dispatch 的 play 族 / resumeMusic / kickPlay）。
     * 原厂自己起的播放（DLNA 推送、小讯放歌）不在此列 —— 那类场景由
     * {@link MusicResume#isPending()}（原厂 doInterrupt 的 isPlaying() 结论）兜底判断。
     */
    /**
     * v1.3.133：异步查一次 DLNA 渲染器的播放状态（GetTransportInfo）。
     * 只在 {@code CurrentTransportState == PLAYING} 时回调（主线程）。
     *
     * <p>用途：{@link #softPlaying} 是进程内内存态，有两个盲区 ——
     * ① 进程重启即丢（音乐在 player 独立进程里继续放，我们不知道）；
     * ② 原厂/DLNA 自己起的播放没经过 {@link #dispatch}，没记账。
     * TTS 让路判据和启动 warm-up 都用它查一次真状态。
     */
    public static void queryPlayingAsync(final Runnable onPlaying) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String r = avt("GetTransportInfo",
                            "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                                    + "<InstanceID>0</InstanceID></u:GetTransportInfo>");
                    String st = tag(r, "CurrentTransportState");
                    dlnaState = st;     // v1.3.136：记下真状态供 status 回显
                    Log.i(TAG, "GetTransportInfo -> " + st);
                    if ("PLAYING".equalsIgnoreCase(st)) {
                        softPlaying = true;
                        new Handler(Looper.getMainLooper()).post(onPlaying);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "queryPlayingAsync failed: " + t);
                }
            }
        }, "pb-probe").start();
    }

    /**
     * v1.3.134：{@link #queryPlayingAsync(Runnable)} 的「带结果」版 ——
     * 无论播不播都回调（主线程）。用途：MusicResume 的让路兜底需要区分
     * 「没在播 ⇒ 撤账」和「在播 ⇒ 补让路」，只在 PLAYING 时回调的旧接口做不到。
     */
    public interface StateCallback {
        void onResult(boolean playing);
    }

    public static void queryPlayingStateAsync(final StateCallback cb) {
        new Thread(new Runnable() {
            public void run() {
                boolean playing = false;
                try {
                    String r = avt("GetTransportInfo",
                            "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                                    + "<InstanceID>0</InstanceID></u:GetTransportInfo>");
                    String st = tag(r, "CurrentTransportState");
                    dlnaState = st;     // v1.3.136
                    Log.i(TAG, "GetTransportInfo -> " + st);
                    playing = "PLAYING".equalsIgnoreCase(st);
                    if (playing) {
                        softPlaying = true;
                    }
                    // v1.3.136：★ DLNA 说「没在播」时**不推翻**内存/磁盘里的在播结论 ——
                    // DLNA 只是外部控制通道，对 player 自己的播放引擎（原厂起播、
                    // 我们推的歌单）未必同步（这正是「小智启动前就在放歌却说播放列表为空」的
                    // 根因）。只有 PLAYING 这种**阳性**结论才用来纠偏。
                } catch (Throwable t) {
                    Log.w(TAG, "queryPlayingStateAsync failed: " + t);
                }
                final boolean p = playing;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        cb.onResult(p);
                    }
                });
            }
        }, "pb-probe2").start();
    }

    public static boolean isSoftPlaying() {
        return softPlaying;
    }

    /**
     * v1.3.143：{@code softPlaying} 里**本进程内真播过**的那部分（排除重启恢复的过期值）。
     * 让路判据请用这个；只想显示「当前曲目」用 {@link #isSoftPlaying()}。
     */
    public static boolean isSoftPlayingFresh() {
        return softFresh;
    }

    /**
     * v1.3.152：给 Web 控制台的**结构化**搜索（{@code GET /api/search?k=歌名/歌手}）。
     *
     * 与 {@code op=search} 的区别：那个返回一整段中文文本（给 MCP / 模型看的），
     * 前端没法拿它渲染可点击列表。这里返回
     * {@code {"total":N,"list":[{"index","title","artist","url"}…]}}：
     * · {@code index} 是 **1-based**（与 play_index 的编号一致）；
     * · {@code url} 是曲库里那条绝对地址，前端点一下走 {@code op=play_url} 即播。
     * 上限 60 条（多了前端也没法点）。
     */
    public static JSONObject searchJson(String q) {
        return searchJson(q, 60);
    }

    /**
     * 曲库搜索（web「搜索曲库」用）。
     *
     * <p>v1.3.168：<b>关键词留空 = 列全部曲库</b>（web 上「留空点搜索」就走这条），
     * {@code lim} 控制最多回几条（{@code <=0} 表示不限制）。关键词非空时默认上限 60
     * —— 那条路径是给语音点歌/精确查找用的，一次回 1188 条没意义。</p>
     *
     * <p>v1.3.181：<b>只搜本地</b> —— 在线歌播过就会 append 进 tracks，不过滤的话
     * 「搜索曲库」和「在线搜歌」两个按钮就分不出区别了。返回条目的 {@code index}
     * 仍是全库下标，点播不受过滤影响。</p>
     */
    public static JSONObject searchJson(String q, int lim) {
        return searchJson(q, lim, false);
    }

    /**
     * v1.3.198：{@code shuffle=true} ⇒ 匹配结果先洗牌再截断。
     *
     * <p>用途：「搜索曲库」框里<b>什么都不输</b>直接点搜索（= 列全部）时，
     * 以前永远是按下标原序的前 N 首 ⇒ 用户原话「每次留空搜索出来的歌都是那几首」。
     * 洗牌后每次都是新的一批。关键词非空时不洗 —— 那是有目标的查找，
     * 顺序本身就是相关度。</p>
     */
    public static JSONObject searchJson(String q, int lim, boolean shuffle) {
        JSONObject o = new JSONObject();
        try {
            if (!idxLoaded) {
                loadIndex();
            }
            if (!idxLoaded) {
                o.put("err", "音乐库不可用: " + idxErr);
                return o;
            }
            String key = (q == null) ? "" : q.trim();
            List<Integer> hits;
            if (key.length() == 0) {
                // 空关键词 ⇒ 全库（按下标原序）
                // ★ v1.3.181：只列**本地**。在线歌播过一次就会 append 进 tracks
                //   （上限 400 条），不过滤的话「搜索曲库」能搜出在线歌 ——
                //   页面上「搜索」和「在线搜歌」两个按钮就分不出区别了
                //   （用户原话：「这个搜索应该是搜出来本地音乐吧」）。
                hits = new ArrayList<Integer>(tracks.size());
                for (int i = 0; i < tracks.size(); i++) {
                    String[] e = tracks.get(i);
                    if (e.length > 7 && e[7] != null && e[7].length() > 0) {
                        continue;               // 在线 append 的条目不进「曲库」结果
                    }
                    hits.add(Integer.valueOf(i));
                }
            } else {
                hits = matchTracks(key, false);
                // ★ v1.3.181：同上 —— 关键词搜索也只出本地。
                //   index 仍是**全库下标**（点播 op=play_at 用它），过滤只影响显不显示，
                //   不影响点播命中。（matchTracks 本身不过滤：语音点歌还要靠它搜到
                //   在线 append 的歌 —— 那条链路「搜到就播」是合理行为。）
                List<Integer> local = new ArrayList<Integer>(hits.size());
                for (int i = 0; i < hits.size(); i++) {
                    int ix = hits.get(i).intValue();
                    String[] e = tracks.get(ix);
                    if (e.length > 7 && e[7] != null && e[7].length() > 0) {
                        continue;
                    }
                    local.add(Integer.valueOf(ix));
                }
                hits = local;
            }
            JSONArray arr = new JSONArray();
            int cap = (lim <= 0) ? hits.size() : Math.min(hits.size(), lim);
            // ★ v1.3.198：留空列全部时洗牌（否则每次都是同样那几首）
            int[] ord = shuffle ? shuffled(hits.size()) : null;
            for (int j = 0; j < cap; j++) {
                int i = hits.get(ord != null ? ord[j] : j).intValue();
                String[] e = tracks.get(i);
                JSONObject it = new JSONObject();
                it.put("index", i + 1);
                it.put("title", e[0]);
                it.put("artist", e.length > 1 ? e[1] : "");
                it.put("url", e.length > 3 ? e[3] : "");
                arr.put(it);
            }
            o.put("total", hits.size());
            o.put("list", arr);
        } catch (Throwable t) {
            // 注意：这里已经在 catch 块里，put 自己还会抛 JSONException，得再兜一层
            try {
                o.put("err", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /* ================= v1.3.200：收藏列表 =================
       主本在 device 侧的 files/r1_fav.json（见 Fav.java），这里只负责
       「把曲子补成能播的条目」和「播收藏列表」两件事。

       ★ 收藏条目来源混杂（本地曲库 / 在线惰性地址 / 电台直链），播的时候
         统一**转成 tracks 下标**再走 pushSongs —— 与 queue_online 同构，
         复用它那套「减半重试 + kickPlay」，不另起一套推送逻辑。 */

    /**
     * 用曲名把条目补齐（曲库序号 + 直链）—— 只存了歌名的条目靠这个变可播。
     *
     * <p>★ 同样**本地优先**：先在本地段里按「曲名+歌手」找，找不到才回全库
     * matchTracks（那批里可能是在线听过的歌）。收藏记到本地序号 ⇒ 下次播收藏
     * 直接走本地直链，不用再去网络上取。</p>
     */
    private static void fillFromLibrary(JSONObject c, String title) {
        try {
            if (!idxLoaded) {
                loadIndex();
            }
            int i = findLocal(title, c.optString("artist", ""));
            if (i < 0) {
                List<Integer> hit = matchTracks(title, false);
                if (hit.isEmpty()) {
                    return;
                }
                i = hit.get(0).intValue();
            }
            String[] e = tracks.get(i);
            if (c.optString("artist", "").length() == 0 && e.length > 1 && e[1] != null) {
                c.put("artist", e[1]);
            }
            if (c.optInt("index", 0) == 0) {
                c.put("index", i + 1);          // 曲库序号是 1-based
            }
            if (c.optString("url", "").length() == 0) {
                c.put("url", urlOf(e));
            }
        } catch (Throwable t) {
            Log.w(TAG, "fillFromLibrary: " + t);
        }
    }

    /**
     * 给「只有歌名」的收藏条目在线找源（拿 source+rid）。
     *
     * <p>★ 为什么必须有这一步：收藏登记时常拿不到地址（语音「收藏这首歌」时 player
     * 给的 curUrl 可能是空、队列里的在线歌也可能还没取链），条目就只剩 title+artist。
     * 这种条目在本地库里查不到时，播收藏会**整首跳过** —— 用户看到的就是
     * 「收藏 9 首、导入只有 4 首」。</p>
     *
     * <p>搜 NAS 的 {@code /api/search}（落雪聚合多源），优先挑曲名归一化后完全一致的那条，
     * 挑不到就用第一条。返回 null = 没搜到（下一首继续）。</p>
     */
    /** 在线补源时「搜到的这条算不算这首歌」的最低分（见 {@link #osScore}）。 */
    private static final int FAV_HIT_MIN = 70;

    /**
     * 收藏补源：拿歌名（+歌手）去 NAS 搜一条，返回带 source/rid 的那条。
     *
     * <p>★ v1.3.223：以前「曲名没精确对上」就直接拿<b>第一条</b>结果当命中 ——
     * 于是随便编个名字（「测试测试」）也能「匹配到内容」：NAS 搜什么都会返回几条，
     * 第一条就被当成这首歌排进播放列表，而列表里显示的还是导入时写的假名
     * ⇒ 用户看到的就是「播的是真歌、显示的是我瞎写的名字」。</p>
     *
     * <p>★ v1.3.224：阈值从 80 降到 70，判据也从「你包含我 / 我包含你」换成
     * {@link Fav#fold} + 词元覆盖 —— 只认包含关系的代价是：NAS 回的名字只要在中间多一个
     * 空格、多一组大小写、《Côme》写成《Come》，这歌就被判「不像」⇒ 不敢认、不敢写缓存
     * ⇒ 每次导入都为它重新搜一遍（慢），还可能被别的候选顶掉（配错歌）。</p>
     */
    private static JSONObject favSearchOne(String title, String artist, int ms) {
        return favSearchOne(title, artist, ms, "");
    }

    /** v1.3.262：带 kind 版 —— kind=program 的条目只搜节目（MediaServer
     *  /api/search 的 kind=program 过滤），有声书章节不再被同名歌顶包。 */
    private static JSONObject favSearchOne(String title, String artist, int ms, String kindF) {
        try {
            String b = nasBase();
            if (b == null || title == null || title.length() == 0) {
                return null;
            }
            String t = title.trim();
            String a1 = mainArtist(artist);
            String q1 = (a1.length() > 0) ? (t + " " + a1) : t;
            JSONObject r = searchPick(b, q1, title, artist, ms, kindF);
            if (r != null && !r.optBoolean("unsure", false)) {
                return r;
            }
            /* ★ 带歌手没搜准 ⇒ 拿**纯歌名**再搜一次。
               《Il aurait suffi》的歌手栏是 "Côme / Vincent Baguian / Julie Fournier"
               四个人拼出来的长串，整个丢给 NAS 搜，前几名经常被那些人名带偏，
               反而只搜曲名时正确那条排在最前 —— 这就是「第一首法语歌被配成英语歌」。 */
            if (!q1.equals(t)) {
                JSONObject r2 = searchPick(b, t, title, artist, ms, kindF);
                if (r2 != null && (!r2.optBoolean("unsure", false) || r == null)) {
                    return r2;
                }
            }
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "favSearchOne: " + t);
        }
        return null;
    }

    /** 多歌手只取头一个（"Côme / Vincent Baguian" ⇒ "Côme"）拼进搜索串，别把长串丢给 NAS。 */
    private static String mainArtist(String artist) {
        if (artist == null) {
            return "";
        }
        String s = artist.trim();
        int i = s.indexOf('/');
        if (i > 0) {
            s = s.substring(0, i);
        }
        return s.trim();
    }

    /**
     * v1.3.242：搜回来的这条是不是「别人唱的同一首」—— 歌手两边都有、却**一点也不沾**。
     *
     * <p>★ 为什么翻唱版必须在这里拦掉：网易云的翻唱曲名往往写成
     * 《bitches broken hearts（翻自 Billie Eilish）》，{@link Fav#fold} 之后是
     * {@code bitches broken hearts翻自 billie eilish} —— 它把原曲名整条**包含**住了 ⇒
     * {@link #osScore} 给 92 分；而正牌原唱《Bitch broken hearts》（网易/酷我都把
     * "bitches" 写歪成 "bitch"）构不成包含、只能走词元覆盖 ⇒ 78 + 歌手 12 = 90 分。
     * 于是「翻唱 92 > 原唱 90」，收藏《bitches broken hearts / Billie Eilish》
     * 播出来是 cyo 的翻唱版。同类的还有《Hotline Bling》被配成 Drake 版。</p>
     *
     * <p>判据只用歌手（{@link Fav#artistSame}）：两个歌手一个共同词元都没有
     * ⇒ 这条连打分都不参加。任一方为空 ⇒ 不判（信息不足不能定罪）。</p>
     */
    private static boolean artistConflict(String want, String got) {
        if (want == null || got == null) {
            return false;
        }
        if (want.trim().length() == 0 || got.trim().length() == 0) {
            return false;
        }
        return !Fav.artistSame(want, got);
    }

    /** 发一次 NAS 搜索，按 {@link #osScore} 挑最像的那一条（拿不准就标 unsure）。 */
    private static JSONObject searchPick(String b, String q, String title, String artist,
                                         int ms, String kindF) throws Exception {
        String js = httpGet(b + "/api/search?q=" + URLEncoder.encode(q, "UTF-8")
                + "&n=5&source=all"
                + ("program".equals(kindF) ? "&kind=program" : ""), ms);
        if (js == null || js.length() == 0) {
            return null;
        }
        JSONArray l = new JSONObject(js).optJSONArray("list");
        if (l == null || l.length() == 0) {
            return null;
        }
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < l.length(); i++) {
            JSONObject s = l.optJSONObject(i);
            if (s == null) {
                continue;
            }
            if (s.optString("source", "").length() == 0 || s.optString("rid", "").length() == 0) {
                continue;
            }
            /* ★ v1.3.242：歌手完全不是一个人 ⇒ 直接不参与（翻唱 / 别人的版本）。
               必须放在下面两个「曲名完全一致就立马 return」之前 —— 同名不同人正是
               翻唱最标准的形态，那两个 return 是不看歌手的。 */
            if (artistConflict(artist, s.optString("artist", ""))) {
                continue;
            }
            /* 曲名完全一致 ⇒ 立马用它，不用再跟别人比（搜出来第一条常常是翻唱 / 同名另一首）。
               ★ v1.3.224：再加一层 fold 后的完全一致 —— 《Côme》/《Come》这种只差一个
               注音的组合，key 比不出来，但折完是同一个串。
               ★ v1.3.257：秒回必须先过歌手闸（artistSame；任一方为空视为通过）——
               「歌名一字不差、歌手完全不同」正是翻唱 / 别人的同名歌的标准形态
               （实测《Hotline Bling》被配成 Drake 版），以前这两条 return 完全不看歌手。 */
            String gotArtist = s.optString("artist", "");
            String want = Fav.key(title, "");
            if (want.length() > 0 && want.equals(Fav.key(s.optString("title", ""), ""))
                    && Fav.artistSame(artist, gotArtist)) {
                return s;
            }
            String fw = Fav.fold(title);
            if (fw.length() > 0 && fw.equals(Fav.fold(s.optString("title", "")))
                    && Fav.artistSame(artist, gotArtist)) {
                return s;
            }
            int sc = osScore(title, artist, s);
            if (sc > bestScore) {
                bestScore = sc;
                best = s;
            }
        }
        if (best == null) {
            return null;                      // 一条可用的都没有
        }
        if (bestScore < FAV_HIT_MIN) {
            try {
                best.put("unsure", true);     // 有结果，但名字基本对不上 ⇒ 不敢认
            } catch (Throwable ignore) {
                // ignore
            }
        }
        return best;
    }

    /**
     * NAS 搜回来的这条值不值得当「这首歌」（{@link #FAV_HIT_MIN} 以下是不敢认）。
     *
     * <p>全部基于 {@link Fav#fold}（去注音 + 去标点 + 小写）：
     * 相等 100 ＞ 折叠后互相包含 92 ＞ 词元覆盖 ≥ 60% 记 78 ＞ ≥ 34% 记 55 ＞ 否则 0。
     * 歌手再独立加一点权重（对得上 +8，沾点边 +3）。<br>
     * 「测试测试」和任何结果都是 0 ⇒ 不敢认；而《Bohème》/《Boheme》、
     * 《Il aurait suffi》/《Il aurait suffi - Remastered》这类同一首的不同写法能认回来。</p>
     */
    private static int osScore(String title, String artist, JSONObject s) {
        String ft = Fav.fold(title);
        String st = Fav.fold(s.optString("title", ""));
        if (ft.length() == 0 || st.length() == 0) {
            return 0;
        }
        String a = ft.replace(" ", "");
        String c = st.replace(" ", "");
        int sc;
        if (a.equals(c)) {
            sc = 100;
        } else if (a.length() >= 3 && c.length() >= 3 && (a.contains(c) || c.contains(a))) {
            sc = 92;                          // 《晴天》vs《晴天 (Live)》
        } else {
            double cv = Fav.cover(ft, st);
            if (cv >= 0.6) {
                sc = 78;
            } else if (cv >= 0.34) {
                sc = 55;
            } else {
                sc = 0;
            }
        }
        String fa = Fav.fold(artist);
        String sa = Fav.fold(s.optString("artist", ""));
        if (fa.length() > 0 && sa.length() > 0) {
            /* ★ v1.3.226：歌手从「加分项」改回「判据」—— 见 Fav.nameScore 的说明。
               歌手对牢了，就容忍歌名的译名 / 版本差异（凑不出整段也可以认）；
               歌手八字不合，歌名又没把它整条包住，那就是别人的歌 ⇒ 不认。 */
            double af = Fav.coverQuery(fa, sa);
            double ab = Fav.coverQuery(sa, fa);
            /* ★ v1.3.257：歌手权重高于歌名 —— 对牢 +20（以前 +12），且歌名沾边
               （cover ≥ 0.4，以前 0.5）就抬到 78 档。理由同 Fav.nameScore：
               搜索结果里歌名写法千差万别（译名/Live/Remastered），歌手才是身份。 */
            if (af >= 0.7 || ab >= 0.7) {
                sc += 20;
                if (sc > 0 && sc < FAV_HIT_MIN
                        && Fav.coverQuery(ft, st) >= 0.4) {
                    sc = 78;
                }
            } else if (af <= 0 && ab <= 0) {
                /* ★ v1.3.242：92（对方名把请求名整条包住）也不再豁免 ——
                   《XX（翻自 YY）》正是这种形态，而它得到的 92 分比正牌原唱的 90 分还高。
                   同名（100）压到阈值以下：只有全场没有更合适的候选时才会用到它，
                   且必然落到 unsure（不写缓存、不进队列）。 */
                if (sc < 100) {
                    return 0;
                }
                sc = 60;
            } else {
                sc += 4;
            }
        }
        return (sc > 100) ? 100 : sc;
    }

    /** 本地模糊命中的最低分（{@link Fav#nameScore}，低于它就是「连猜都算不上」）。 */
    private static final int LOCAL_HIT_MIN = 70;
    /**
     * v1.3.228：收藏里「本地库没有、也没搜过源」的那批歌排进播放列表时用的占位地址。
     * 播到它时播放引擎来取 ⇒ player 的 /r/fav 路由回头问 device（op=fav_resolve）
     * 现搜一次再 302 跳到真的惰性地址。导入因此不需要为上百首歌各搜一遍。
     */
    private static final String PENDING_URL = "http://127.0.0.1:8080/r/fav?k=";

    /**
     * 收藏解析专用的本地曲库模糊查找：按**词**比，不够像返回 -1。
     *
     * @param KT  库名预先切好的词元（库+N首ousandsample 只算一次，进了这里一次雑树莓per-entry 全库fold）
     * @param KAT 库里歌手预先切好的词元
     */
    private static int findLocalFuzzy(String title, String artist, List<String[]> keep,
                                      String[][] KT, String[][] KAT) {
        String[] A = Fav.tokens(title);
        if (A.length == 0) {
            return -1;
        }
        String[] AA = Fav.tokens(artist);
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < keep.size() && i < KT.length; i++) {
            int sc = Fav.nameScore(A, AA, KT[i], KAT[i]);
            if (sc < LOCAL_HIT_MIN || sc <= bestScore) {
                continue;
            }
            bestScore = sc;
            best = i;
        }
        return best;
    }

    /** v1.3.231：词元倒排索引 —— 把「每首歌 × 全库」的 O(N²) 模糊匹配压成
     * 「只比共享至少一个词元的候选」。库 1288 首时，每首歌的候选通常 5~30 个，
     * 整体从「全扫」降到亚秒级（v1.3.230 误删导入期模糊匹配、改回 fav_resolve 按需单次，
     * 方向对，但把《Il aurait suffi》这类只模糊命中的歌也顺手弄成了占位 + 全红）。 */
    private static HashMap<String, ArrayList<Integer>> sFavTokIdx = null;
    private static int sFavTokIdxVer = -1;

    private static ArrayList<Integer> favLocalCands(String title, String artist,
            List<String[]> keep, String[][] KT, String[][] KAT) {
        if (sFavTokIdx == null || sFavTokIdxVer != sIdxVer) {
            sFavTokIdx = new HashMap<String, ArrayList<Integer>>();
            for (int i = 0; i < keep.size() && i < KT.length; i++) {
                if (KT[i] != null) {
                    for (int j = 0; j < KT[i].length; j++) {
                        String tk = KT[i][j];
                        if (tk == null || tk.length() == 0) {
                            continue;
                        }
                        ArrayList<Integer> lst = sFavTokIdx.get(tk);
                        if (lst == null) {
                            lst = new ArrayList<Integer>();
                            sFavTokIdx.put(tk, lst);
                        }
                        lst.add(Integer.valueOf(i));
                    }
                }
                if (KAT[i] != null) {
                    for (int j = 0; j < KAT[i].length; j++) {
                        String tk = KAT[i][j];
                        if (tk == null || tk.length() == 0) {
                            continue;
                        }
                        ArrayList<Integer> lst = sFavTokIdx.get(tk);
                        if (lst == null) {
                            lst = new ArrayList<Integer>();
                            sFavTokIdx.put(tk, lst);
                        }
                        lst.add(Integer.valueOf(i));
                    }
                }
            }
            sFavTokIdxVer = sIdxVer;
        }
        ArrayList<Integer> out = new ArrayList<Integer>();
        HashSet<Integer> seen = new HashSet<Integer>();
        String[] A = Fav.tokens(title);
        for (int i = 0; i < A.length; i++) {
            ArrayList<Integer> lst = sFavTokIdx.get(A[i]);
            if (lst != null) {
                for (int j = 0; j < lst.size(); j++) {
                    Integer v = lst.get(j);
                    if (seen.add(v)) {
                        out.add(v);
                    }
                }
            }
        }
        String[] AA = Fav.tokens(artist);
        for (int i = 0; i < AA.length; i++) {
            ArrayList<Integer> lst = sFavTokIdx.get(AA[i]);
            if (lst != null) {
                for (int j = 0; j < lst.size(); j++) {
                    Integer v = lst.get(j);
                    if (seen.add(v)) {
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    /**
     * v1.3.232：对单首收藏跑 ⑥ 离线模糊匹配，返回命中的**库内下标**（没命中返回 -1）。
     * 主循环 / tail 循环 / fav_rematch 三者共用，保证「离线匹配」只有一份实现。
     * 命中判据：① 词元倒排索引拿到候选；② findLocalFuzzy 打分 >= LOCAL_HIT_MIN；
     * ③ 命中库内那首的 url（abs 直链）非空 —— 空链的一律不当命中（否则落个播不出的星）。
     */
    private static int favLocalMatchIdx(String title, String artist,
            List<String[]> keep, String[][] KT, String[][] KAT) {
        if (title == null || title.length() == 0) {
            return -1;
        }
        ArrayList<Integer> cands = favLocalCands(title, artist, keep, KT, KAT);
        if (cands.isEmpty()) {
            return -1;
        }
        List<String[]> ckeep = new ArrayList<String[]>(cands.size());
        String[][] cKT = new String[cands.size()][];
        String[][] cKAT = new String[cands.size()][];
        for (int ci = 0; ci < cands.size(); ci++) {
            int cidx = cands.get(ci).intValue();
            ckeep.add(keep.get(cidx));
            cKT[ci] = KT[cidx];
            cKAT[ci] = KAT[cidx];
        }
        int lh = findLocalFuzzy(title, artist, ckeep, cKT, cKAT);
        if (lh < 0) {
            return -1;
        }
        String[] e = ckeep.get(lh);
        String lurl = (e != null && e.length > 3 && e[3] != null) ? e[3] : "";
        if (lurl.length() == 0) {
            return -1;
        }
        return cands.get(lh).intValue();
    }

    /**
     * v1.3.232：对所有收藏跑一遍 ⑥ 离线模糊匹配（**不碰网络**，亚秒级），把命中的库内真名
     * 写回 ltitle/lartist。导入后 / 手动「重新匹配」时调用，让收藏列表立刻带星（可播）。
     * 已经带 ltitle/source/url 的跳过，避免覆盖既有匹配；幂等，可反复跑。
     *
     * @return JSON：{"ok":true,"matched":N,"msg":"..."}
     */
    private static String favRematch() {
        JSONArray fav = Fav.list();
        if (fav == null || fav.length() == 0) {
            return "{\"ok\":true,\"matched\":0,\"msg\":\"没有收藏\"}";
        }
        List<String[]> keep = localTracksSnapshot();
        final int kn = keep.size();
        final String[][] KT = new String[kn][];
        final String[][] KAT = new String[kn][];
        for (int i = 0; i < kn; i++) {
            String[] e = keep.get(i);
            KT[i] = Fav.tokens((e != null) ? e[0] : "");
            KAT[i] = Fav.tokens((e != null && e.length > 1) ? e[1] : "");
        }
        List<String[]> locFix = new ArrayList<String[]>();
        List<String[]> rest = new ArrayList<String[]>();   // 离线没配上（库里没有）⇒ 交给后台在线补源
        int matched = 0;
        for (int i = 0; i < fav.length(); i++) {
            JSONObject it = fav.optJSONObject(i);
            if (it == null) {
                continue;
            }
            String title = it.optString("title", "");
            if (title.length() == 0) {
                continue;
            }
            if (it.optString("ltitle", "").length() > 0) {
                continue;                       // 已有缓存，跳过
            }
            if (it.optString("source", "").length() > 0) {
                continue;
            }
            if (it.optString("url", "").length() > 0) {
                continue;
            }
            int loc3 = favLocalMatchIdx(title, it.optString("artist", ""), keep, KT, KAT);
            if (loc3 < 0) {
                /* 本地库确实没有（如《Il aurait suffi》这种）⇒ 进后台在线补源队列。
                   负缓存（6h 内搜过没搜到）的不再白搜，避免每次导入都重搜一遍。 */
                if (!Fav.skipSearch(it)) {
                    rest.add(new String[]{title, it.optString("artist", "")});
                }
                continue;
            }
            String[] e = keep.get(loc3);
            String pk = Fav.key(title, it.optString("artist", ""));
            locFix.add(new String[]{pk, "ltitle", (e[0] == null) ? "" : e[0]});
            if (e.length > 1 && e[1] != null && e[1].length() > 0) {
                locFix.add(new String[]{pk, "lartist", e[1]});
            }
            matched++;
        }
        if (!locFix.isEmpty()) {
            Fav.patchBatch(locFix);
        }
        /* ★ v1.3.232：库里没有的歌（含法语歌）扔后台在线搜 —— warmFavSource 是**线程**，
           立刻返回，导入不卡；搜到一条落一条 source+rid，收藏列表的星会自己亮起来。
           这样既不回到「导入卡 37 秒/像在搜索」，又不会「很多歌没星/被跳过」。 */
        if (!rest.isEmpty()) {
            warmFavSource(rest);
        }
        return "{\"ok\":true,\"matched\":" + matched + ",\"warming\":" + rest.size()
                + ",\"msg\":\"已离线匹配 " + matched + " 首，后台在线补源 " + rest.size() + " 首（搜到就点亮）\"}";
    }


    /**
     * 「现在播的那一首」—— 收藏/取消收藏要的就是它。
     *
     * <p>优先问 player（:8080 的 {@code /api/fav?op=cur}）：它手上有完整的
     * title/artist/**url**，连在线惰性地址都能原样交出来；问不到（player 没起、
     * 或者推流中）才退回 device 自己记的曲名 —— 那份只有名字，得回库反查。</p>
     */
    private static JSONObject curSongForFav() {
        try {
            String s = httpGet("http://127.0.0.1:8080/api/fav?op=cur", 1200, 65536, 3000);
            if (s != null && s.length() > 0) {
                JSONObject o = new JSONObject(s);
                JSONObject c = o.optJSONObject("cur");
                if (o.optBoolean("ok", false) && c != null
                        && c.optString("title", "").length() > 0) {
                    return c;
                }
            }
        } catch (Throwable t) {
            // player 没起 / 8080 没开 —— 走下面的本地兜底
        }
        String t = curTitle();
        if (t == null || t.length() == 0) {
            return null;
        }
        JSONObject c = new JSONObject();
        try {
            c.put("title", t);
            c.put("artist", (curArtist == null) ? "" : curArtist);
        } catch (Throwable ignored) {
            return null;
        }
        fillFromLibrary(c, t);
        return c;
    }

    /** 语音「收藏这首歌 / 我喜欢这首歌」：不给 title = 收藏正在播的那首。 */
    public static String favAdd(JSONObject args) {
        String t = (args == null) ? "" : args.optString("title", "").trim();
        JSONObject c;
        if (t.length() > 0) {
            c = new JSONObject();
            try {
                c.put("title", t);
                c.put("artist", args.optString("artist", "").trim());
            } catch (Throwable ignored) {
                return "ERR 收藏条目组装失败";
            }
            fillFromLibrary(c, t);
        } else {
            c = curSongForFav();
            if (c == null) {
                return "ERR 现在没在播，不知道要收藏哪一首";
            }
        }
        String r = Fav.add(c);
        return r;
    }

    /**
     * 手动配对（网页「🔗」）：把**正在播的这一首**绑到收藏里的某一条。
     *
     * <p>自动解析总有搞不定的：《Cold Water》《Sucker For Pain》这类光杆条目，
     * 或者在 NAS 上就是搜不到同名曲。以前只能让用户去改 txt 再重新上传；
     * 现在他可以在搜索框里自己挑一条播起来，再点收藏列表里那一条的 🔗
     * ⇒ 这一条从此有了 source+rid，跟自动配上的没区别（title 原名不动，key 不变）。</p>
     */
    public static String favBind(JSONObject args) {
        String k = (args == null) ? "" : args.optString("k", "").trim();
        String t = (args == null) ? "" : args.optString("title", "").trim();
        String a = (args == null) ? "" : args.optString("artist", "").trim();
        if (k.length() == 0) {
            if (t.length() == 0) {
                return "ERR 没给要绑定哪一条收藏";
            }
            k = Fav.key(t, a);
        }
        JSONObject c = curSongForFav();
        if (c == null) {
            return "ERR 现在没在播歌 —— 先播一条你觉得对的，再点 🔗 绑到这个歌名上";
        }
        String ct = c.optString("title", "").trim();
        String ca = c.optString("artist", "").trim();
        String url = c.optString("url", "");
        if (ct.length() == 0) {
            return "ERR 现在播的这一首没有歌名，绑不了";
        }
        String src = c.optString("source", "");
        String rid = c.optString("rid", "");
        /* 惰性地址 /r/<source>/<rid> 里就有四件套 —— player 回给 cur 的那份有时没带
           source/rid 字段，但地址本身一定写着 ⇒ 自己从地址里拆出来补上。 */
        int p = url.indexOf("/r/");
        if (p > 0) {
            String tail = url.substring(p + 3);
            int slash = tail.indexOf('/');
            if (slash > 0) {
                if (src.length() == 0) {
                    src = tail.substring(0, slash);
                }
                String rest = tail.substring(slash + 1);
                int cut = rest.indexOf('?');
                if (rid.length() == 0) {
                    rid = (cut > 0) ? rest.substring(0, cut) : rest;
                }
            }
        }
        /* ★ 原名（key 依据）一个字都不动：改了 key 就会「同一首歌变两条」。 */
        if (src.length() > 0) {
            Fav.patch(k, "source", src);
        }
        if (rid.length() > 0) {
            Fav.patch(k, "rid", rid);
        }
        if (url.length() > 0) {
            Fav.patch(k, "url", url);
        }
        Fav.patch(k, "rtitle", ct);
        Fav.patch(k, "rartist", ca);
        /* ★ v1.3.242：打上「手动绑定」的记号 —— Fav.purgeUnrelated 会自动清掉
           「歌手和原名不沾」的缓存（那是自动搜索配错版本的判据），但手动绑的这一版
           正是用户点着 🔗 亲自挑的，不能被他下一次导入顺手清掉。 */
        Fav.patch(k, "rman", "1");
        Fav.clearNoSrc(k);                 // 旧负缓存作废：这条已经有了确定的源
        SessionLog.i("fav bind: " + k + " -> " + ct);
        return "OK 已把《" + ct + "》绑到收藏里的这一条（下次导入直接播它）";
    }

    /**
     * v1.3.243：手动**重链接**（网页长按歌名 → 在线搜一版 → 绑过去）。
     *
     * <p>和 {@link #favBind} 的区别：那条只能「先把对的播起来，再把收藏里那条绑过去」，
     * 而且只对**没配上**的条目才有意义。这条是「在在线搜索结果里直接挑一版」，
     * <b>已经自动配上的歌也能改</b>（自动匹配配成翻唱/别人的版本时，这是唯一的出路）。</p>
     *
     * <p>落盘口径：只改收藏主本（{@code files/r1_fav.json}），原名 {@code title/artist}
     * 与 {@code k} 一个字都不动（改了 key 就变成另一首）；真名写 {@code rtitle/rartist}，
     * 打 {@code rman=1}（手动意图，{@code purgeUnrelated} 不会清它），并清掉
     * {@code url / ltitle / lartist} 这些**会抢在 source 前面**的旧缓存。</p>
     *
     * <p>顺手把**已经推给 player 的那张表**里这一首的地址换掉（见 {@link #retargetQueue}）：
     * 正在播的那一首直接原地重推 ⇒ 绑完立刻听到对的版本，队列顺序不动。</p>
     */
    public static String favLink(JSONObject args) {
        String k = jstr(args, "k").trim();
        String t = jstr(args, "title").trim();
        String a = jstr(args, "artist").trim();
        String src = jstr(args, "source").trim();
        String rid = jstr(args, "rid").trim();
        String rt = jstr(args, "rtitle").trim();
        String ra = jstr(args, "rartist").trim();
        String al = jstr(args, "album").trim();
        String du = jstr(args, "duration").trim();
        if (src.length() == 0 || rid.length() == 0) {
            return "ERR 没挑具体版本（source/rid 是空的）";
        }
        if (k.length() == 0) {
            if (t.length() == 0) {
                return "ERR 没给要重链接哪一首";
            }
            k = Fav.key(t, a);
        }
        if (t.length() == 0) {
            t = rt;
        }
        if (rt.length() == 0) {
            rt = t;
        }
        if (t.length() == 0) {
            return "ERR 这一行没有歌名，绑不了";
        }
        JSONObject it = Fav.hit(k);
        boolean fresh = false;
        if (it != null) {
            k = it.optString("k", k);            // 真名算出来的 key 也归到条目自己的 key
            if (t.length() == 0) {
                t = it.optString("title", "");
            }
            if (a.length() == 0) {
                a = it.optString("artist", "");
            }
        } else {
            /* 从**播放列表**长按进来的可能压根不在收藏里（在线搜的歌、随机批）⇒
               顺手收进收藏。不落盘的话「重链接」下次导入就丢了，等于白改。 */
            JSONObject n = new JSONObject();
            try {
                n.put("title", t);
                n.put("artist", a);
                n.put("source", src);
                n.put("rid", rid);
                n.put("rtitle", rt);
                n.put("rartist", ra);
                if (al.length() > 0) {
                    n.put("album", al);
                }
                if (du.length() > 0) {
                    n.put("duration", du);
                }
                n.put("rman", "1");
            } catch (Throwable x) {
                return "ERR 组装失败 " + x;
            }
            Fav.add(n);
            it = Fav.hit(k);
            if (it == null) {
                return "ERR 收藏里写不进去（到上限了？）";
            }
            k = it.optString("k", k);
            fresh = true;
        }
        Fav.force(k, "source", src);
        Fav.force(k, "rid", rid);
        Fav.force(k, "rtitle", rt);
        Fav.force(k, "rartist", ra);
        if (al.length() > 0) {
            Fav.force(k, "album", al);
        }
        if (du.length() > 0) {
            Fav.force(k, "duration", du);
        }
        /* ★ 这三个会**抢在 source+rid 前面**（playFavOne 的顺序是 ①本地 → ①b ltitle →
           ②序号 → ③直链 → ④source）⇒ 不清掉，刚挑的这一版还会被旧缓存顶回去。 */
        Fav.force(k, "url", "");
        Fav.force(k, "ltitle", "");
        Fav.force(k, "lartist", "");
        Fav.force(k, "rman", "1");
        Fav.clearNoSrc(k);
        String b = nasBase();
        String ou = (b == null) ? "" : (b + "/r/" + src + "/" + rid
                + "?title=" + enc(rt) + "&artist=" + enc(ra));
        int fix = retargetQueue(t, a, ou);
        SessionLog.i("fav link: " + k + " -> " + src + "/" + rid + " 《" + rt + "》"
                + (fresh ? "（新收进收藏）" : "") + (fix > 0 ? ("，队列换址 " + fix + " 行") : ""));
        return "OK 已重链接到《" + rt + "》" + (ra.length() > 0 ? ("－" + ra) : "")
                + (fresh ? "（顺手收进了收藏）" : "")
                + (fix > 0 ? "；播放列表里这一首已换成新版" : "");
    }

    /**
     * v1.3.243：把**已经推给 player 的那张表**里这一首的地址换掉。
     *
     * <p>为什么需要：导入时收藏里已经解析出源的那一首，推给 player 的是**固化**的
     * {@code /r/<src>/<rid>} 地址。重链接只改收藏主本的话，这一个播放列表还是老版本，
     * 用户听到的依旧不对。这里直接改 {@link #tracks} 快照里的 URL 字段，
     * 并且**只在正播着这一首时**才原地重推（subType=15 保下标，队列顺序不变）——
     * 换别的歌重推会把正在听的那首从头重放，属于打扰。</p>
     *
     * @return 改了几行（0 = 当前队列里没有这首）
     */
    private static int retargetQueue(String title, String artist, String url) {
        if (url.length() == 0) {
            return 0;
        }
        List<Integer> snap;
        synchronized (lastIdx) {
            snap = new ArrayList<Integer>(lastIdx);
        }
        if (snap.isEmpty() || lastIsRadio) {
            return 0;
        }
        String want = Fav.key(title, artist);
        int hits = 0;
        int at = -1;
        synchronized (tracks) {
            for (int j = 0; j < snap.size(); j++) {
                int i = snap.get(j).intValue();
                if (i < 0 || i >= tracks.size()) {
                    continue;
                }
                String[] e = tracks.get(i);
                if (e == null || e.length < 4) {
                    continue;                    // 电台（length==2）不碰
                }
                String et = (e[0] == null) ? "" : e[0];
                String ea = (e.length > 1 && e[1] != null) ? e[1] : "";
                if (!want.equals(Fav.key(et, ea))
                        && !Fav.relatedName(title, artist, et, ea)) {
                    continue;
                }
                e[3] = url;
                hits++;
                if (j == curIdx) {
                    at = j;
                }
            }
        }
        if (hits == 0) {
            return 0;
        }
        if (at >= 0) {
            int n = pushPlaylist("fav_link", snap, false, at, 15);
            if (n > 0) {
                pushed = true;
                SessionLog.i("fav link: 正播着这一首，原地重推队列（index=" + at + "）");
            }
        }
        return hits;
    }

    /**
     * 播到才解析（v1.3.228）：占位地址 {@code http://127.0.0.1:8080/r/fav?k=...}
     * 被真正播放时，player 回头问这一首到底该播哪个地址。
     *
     * <p>为什么要有这一步：导入播放列表时**不再为每一首本地库没有的歌发一次 NAS
     * 搜索**。357 首的歌单里有 147~171 首要走在线，搜一次 0.3~1s（超时 5s）⇒
     * 导入实测 60~80s，比 player 的转发超时还长 ⇒ 页面弹「8088 没回应」的假失败。
     * 现在这些歌先拿占位地址排进列表（导入 1~2 秒就出声），真要播它时才在这里
     * 现搜一次；后台补源线程通常已经先把它搜好并存进了收藏，这里就是秒回。</p>
     *
     * @return 成功时直接返回可播的惰性地址 {@code /r/<source>/<rid>?title=&artist=}；
     *         失败返回 {@code ERR ...}（player 那边给 404，引擎跳下一首）
     */
    /**
     * v1.3.266：书变成歌的老毛病修复 —— 复用 device 有声书搜法（/api/ab/search +
     * /api/ab/episodes，与 ab_play 同源）判断一首「裸书名」是不是有声书。
     *
     * <p>★ 为什么需要：收藏的裸书名（如「道诡异仙」）没有「有声书/评书」等关键词，
     * {@link #detectKind} 认不出它是节目，于是 {@link #favResolve} 走普通歌搜索被同名/近似歌
     * 顶包成一首歌。这里在普通歌搜索之前先问一遍书库，命中就按节目解析、标记 kind=program，
     * 直接返回第 1 集（标题里写了「第N集」就定位到那集）的惰性地址。</p>
     *
     * @return 可播地址，或 null（不是书 ⇒ 交给普通歌搜索）
     */
    /** ★ v1.3.271：书→albumId 内存缓存（favResolveProgram 用）。
     *  key = Fav.key(裸书名, 主播)；value = [pick JSONObject, 存入时间戳]。
     *  30 分钟过期、上限 8 本，超龄/超员自然淘汰 —— 同一本书的第 2 集起
     *  不再重发 /api/ab/search，一集只剩一次取链（1~2s）。 */
    private static final Object ABPICK_LOCK = new Object();
    private static final java.util.HashMap<String, Object[]> ABPICK =
            new java.util.HashMap<String, Object[]>();

    /** ★ v1.3.271：把「《书名》第N集…」剥成裸书名（搜书/缓存键用）。
     *  剥「第N集」起往后的全部尾巴 + 书名号；剥光了就退回原文。 */
    private static String abBookOnly(String t) {
        if (t == null) {
            return "";
        }
        String s = t.replaceAll("第[0-9０-９零〇一二三四五六七八九十百千]+集.*$", "");
        s = s.replace("《", "").replace("》", "").trim();
        if (s.length() == 0) {
            s = t.trim();
        }
        return s;
    }

    /** ★ v1.3.272：整本集表缓存。albumId → [合成 d{book,narrator,source,episodes,…}, 存入时间]。
     *  TTL 2 小时、上限 6 本（超员整体清，反正是听书场景_CURRENT 那本很快会重新装进来）。 */
    private static final Object ABEP_LOCK = new Object();
    private static final java.util.HashMap<String, Object[]> ABEP =
            new java.util.HashMap<String, Object[]>();

    /**
     * ★ v1.3.272：把整本书的集表拉全并缓存（favResolveProgram 专用）。
     *
     * <p>NAS /api/ab/episodes 的 abf: 枚举缓存只有 5 分钟（server.js 写死，今天没法
     * 同步 NAS），隔几分钟点新一集就重新让落雪全量枚举 ⇒ 一集十几秒。这里按
     * size=200 翻页把整本拉齐（hasMore/total 驱动，40 页 8000 集封顶），device 内存
     * 缓存 2 小时 —— 落雪的整本枚举一天最多几次，之后每集只差一次取链。</p>
     *
     * <p>没有 albumId 时退回老路（单页 50 条，反正没法按本缓存）。</p>
     */
    private static JSONObject abAllEpisodes(String book, String albumId) {
        if (albumId == null || albumId.length() == 0) {
            return abEpisodes(book, albumId, 0, 50, 0);
        }
        long now = System.currentTimeMillis();
        synchronized (ABEP_LOCK) {
            Object[] c = ABEP.get(albumId);
            if (c != null && now - (Long) c[1] < 2 * 3600 * 1000L) {
                return (JSONObject) c[0];
            }
        }
        String b = nasBase();
        if (b == null) {
            return null;
        }
        JSONArray all = new JSONArray();
        String narrator = "", source = "", bookName = (book == null) ? "" : book;
        int page = 1;
        try {
            while (page <= 40) {
                String js = httpGet(b + "/api/ab/episodes?q="
                                + URLEncoder.encode(bookName.length() > 0 ? bookName : albumId, "UTF-8")
                                + "&albumId=" + URLEncoder.encode(albumId, "UTF-8")
                                + "&size=200&page=" + page,
                        20000, 4 * 1024 * 1024, 20000);
                if (js == null || js.length() == 0) {
                    break;
                }
                JSONObject o = new JSONObject(js);
                if (narrator.length() == 0) {
                    narrator = o.optString("narrator", "");
                }
                if (source.length() == 0) {
                    source = o.optString("source", "");
                }
                if (bookName.length() == 0) {
                    bookName = o.optString("book", "");
                }
                JSONArray eps = o.optJSONArray("episodes");
                if (eps == null || eps.length() == 0) {
                    break;
                }
                for (int i = 0; i < eps.length(); i++) {
                    all.put(eps.optJSONObject(i));
                }
                if (!o.optBoolean("hasMore", false)) {
                    break;
                }
                page++;
            }
        } catch (Throwable t) {
            SessionLog.i("ab all episodes: page " + page + " failed: " + t);
            /* 半拉子结果也认 —— 有多少集先缓存多少，缺的那集播到时会命中失败，
               下次进来重新拉全。 */
        }
        if (all.length() == 0) {
            return null;
        }
        try {
            JSONObject d = new JSONObject();
            d.put("book", bookName);
            d.put("narrator", narrator);
            d.put("source", source.length() > 0 ? source : "kw");
            d.put("albumId", albumId);
            d.put("total", all.length());
            d.put("episodes", all);
            synchronized (ABEP_LOCK) {
                if (ABEP.size() > 6) {
                    ABEP.clear();
                }
                ABEP.put(albumId, new Object[]{d, Long.valueOf(System.currentTimeMillis())});
            }
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String favResolveProgram(String t, String a, String k) {
        try {
            String b = nasBase();
            if (b == null) {
                return null;
            }
            /* ★ v1.3.271：搜书前把「第N集」从标题里剥掉（与语音 ab_play 同口径：
               只拿裸书名+主播查书库）。原来拿整串集标题（《道诡异仙》第31集）当
               查询 → NAS 的 ab: 缓存键含整串 → 每一集都是一个新查询、缓存永远
               打不中，一集十几秒。剥掉后同书所有集共用一个缓存键 + 下面这本
               device 内存缓存，第 2 集起只需一次取链。 */
            String bt = abBookOnly(t);
            String ck = Fav.key(bt, a == null ? "" : a);
            JSONObject pick = null;
            synchronized (ABPICK_LOCK) {
                Object[] c = ABPICK.get(ck);
                if (c != null && System.currentTimeMillis() - (Long) c[1] < 30 * 60 * 1000L) {
                    pick = (JSONObject) c[0];
                }
            }
            /* ★ v1.3.272 play-first：书 + 整本集表都在内存缓存里 ⇒ 老流程秒出。 */
            if (pick != null) {
                JSONObject abd = null;
                synchronized (ABEP_LOCK) {
                    Object[] c2 = ABEP.get(pick.optString("albumId", ""));
                    if (c2 != null && System.currentTimeMillis() - (Long) c2[1] < 2 * 3600 * 1000L) {
                        abd = (JSONObject) c2[0];
                    }
                }
                if (abd != null) {
                    return resolveFromBook(pick, abd, t, a, k);
                }
            }
            /* ★ v1.3.272 冷路径不再前台干等整本枚举（十几秒全是它）：
               ① 整本枚举（ab/search + abAllEpisodes）扔后台线程，只为**下一集**提速；
               ② 前台用 kw 节目过滤快搜**这一集**（单查询 2~5s）→ 写回 → 立刻起播；
               ③ 快搜没拿下才退回同步枚举兜底。 */
            final String fbt = bt;
            final String fa = (a == null) ? "" : a;
            Thread w = new Thread(new Runnable() {
                public void run() {
                    warmBook(fbt, fa);
                }
            }, "abWarm");
            w.start();
            JSONObject s = favSearchOne(t, a, 6000, "program");
            if (s != null && !s.optBoolean("unsure", false)) {
                String fsrc = s.optString("source", "");
                String frid = s.optString("rid", "");
                if (fsrc.length() > 0 && frid.length() > 0) {
                    List<String[]> rows = new ArrayList<String[]>();
                    rows.add(new String[]{k, "kind", "program"});
                    rows.add(new String[]{k, "source", fsrc});
                    rows.add(new String[]{k, "rid", frid});
                    String st2 = s.optString("title", "");
                    if (st2.length() > 0) {
                        rows.add(new String[]{k, "rtitle", st2});
                    }
                    String sa2 = s.optString("artist", "");
                    if (sa2.length() > 0) {
                        rows.add(new String[]{k, "rartist", sa2});
                    }
                    Fav.patchBatch(rows);
                    SessionLog.i("fav resolve (play-first): 《" + t + "》 -> " + st2
                            + "（整本枚举已转后台）");
                    String st3 = st2.length() > 0 ? st2 : t;
                    String sa3 = sa2.length() > 0 ? sa2 : a;
                    return b + "/r/" + fsrc + "/" + frid + "?title=" + enc(st3)
                            + "&artist=" + enc(sa3);
                }
            }
            /* ③ 同步枚举兜底（快搜不可信/失败时，宁可多等也不要播错） */
            if (pick == null) {
                pick = searchPickBook(bt, a);
                if (pick == null) {
                    return null;
                }
                synchronized (ABPICK_LOCK) {
                    ABPICK.put(ck, new Object[]{pick, Long.valueOf(System.currentTimeMillis())});
                }
            }
            JSONObject d = abAllEpisodes(pick.optString("book", t), pick.optString("albumId", ""));
            if (d == null) {
                return null;
            }
            return resolveFromBook(pick, d, t, a, k);
        } catch (Throwable ex) {
            return null;
        }
    }

    /** ★ v1.3.272：同步找书（ab/search + 归一化互相包含 + 主播优先同名）。
     *  从 favResolveProgram 抽出 —— 前台兜底与后台预热共用。 */
    private static JSONObject searchPickBook(String bt, String a) {
        try {
            String b = nasBase();
            if (b == null) {
                return null;
            }
            /* 1) 书名搜一下：把用户可能点名的主播拼进查询（与 ab_search 同一口径） */
            String q = (a != null && a.length() > 0) ? (a + " " + bt) : bt;
            String js = httpGet(b + "/api/ab/search?q=" + URLEncoder.encode(q, "UTF-8") + "&n=20",
                    20000, 4 * 1024 * 1024, 20000);
            if (js == null || js.length() == 0) {
                return null;
            }
            JSONArray books = new JSONObject(js).optJSONArray("books");
            if (books == null || books.length() == 0) {
                return null;
            }
            /* 2) 选最像的那本：归一化书名互相包含即算命中；给了主播就优先同名主播 */
            String nq = Fav.key(bt, "");
            JSONObject pick = null;
            for (int i = 0; i < books.length(); i++) {
                JSONObject bk = books.optJSONObject(i);
                if (bk == null) {
                    continue;
                }
                String nb = Fav.key(bk.optString("book", ""), "");
                if (nb.length() == 0) {
                    continue;
                }
                if (nq.indexOf(nb) >= 0 || nb.indexOf(nq) >= 0) {
                    if (a != null && a.length() > 0) {
                        String nar = bk.optString("narrator", "");
                        if (nar.indexOf(a) >= 0 || a.indexOf(nar) >= 0) {
                            pick = bk;
                            break;
                        }
                    }
                    if (pick == null) {
                        pick = bk;
                    }
                }
            }
            return pick;
        } catch (Throwable t) {
            return null;
        }
    }

    /** ★ v1.3.272：后台预热 —— 找书 + 整本枚举，结果只进 ABPICK/ABEP 缓存，
     *  不阻塞点击的这一集（play-first 的配套）。失败静默（主流程自会兜底）。 */
    private static void warmBook(String bt, String a) {
        try {
            String ck = Fav.key(bt, a == null ? "" : a);
            JSONObject pick;
            synchronized (ABPICK_LOCK) {
                Object[] c = ABPICK.get(ck);
                pick = (c != null && System.currentTimeMillis() - (Long) c[1] < 30 * 60 * 1000L)
                        ? (JSONObject) c[0] : null;
            }
            if (pick == null) {
                pick = searchPickBook(bt, a);
                if (pick == null) {
                    return;
                }
                synchronized (ABPICK_LOCK) {
                    ABPICK.put(ck, new Object[]{pick, Long.valueOf(System.currentTimeMillis())});
                }
            }
            String al = pick.optString("albumId", "");
            synchronized (ABEP_LOCK) {
                Object[] c2 = ABEP.get(al);
                if (c2 != null && System.currentTimeMillis() - (Long) c2[1] < 2 * 3600 * 1000L) {
                    return;                       // 已有热缓存，不必再枚举
                }
            }
            JSONObject dd = abAllEpisodes(pick.optString("book", bt), al);
            if (dd != null) {
                /* ★ v1.3.273：后台枚举完即把收藏里同书各集整本预绑定（play-first
                   配套）—— 用户点第一集之后，整本上传集基本都固化进 r1_fav.json，
                   2h 缓存形同作废，重启也不丢。 */
                bindAllEpisodes(pick, dd);
            }
        } catch (Throwable t) {
            /* 预热失败不影响主流程 */
        }
    }

    /** ★ v1.3.272：从已选书 + 集表定位这一集、写回收藏、返回惰性地址
     *  （老流程 3)~4) 步抽出 —— 内存命中与同步兜底共用）。 */
    private static String resolveFromBook(JSONObject pick, JSONObject d, String t, String a, String k) {
        int ep = parseEpFromTitle(t);
        JSONArray eps = d.optJSONArray("episodes");
        if (eps == null || eps.length() == 0) {
            return null;
        }
        JSONObject e0 = abPick(eps, ep > 0 ? ep : 0);
        if (e0 == null) {
            return null;
        }
        String src = e0.optString("source", d.optString("source", "kw"));
        String rid = e0.optString("rid", "");
        if (src.length() == 0 || rid.length() == 0) {
            return null;
        }
        /* 4) 写回收藏：标记节目 + 源 + 真名（展示用集标题）+ 主播 */
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[]{k, "kind", "program"});
        rows.add(new String[]{k, "source", src});
        rows.add(new String[]{k, "rid", rid});
        String et = e0.optString("title", "");
        if (et.length() > 0) {
            rows.add(new String[]{k, "rtitle", et});
        }
        String nar = d.optString("narrator", "");
        if (nar.length() > 0) {
            rows.add(new String[]{k, "rartist", nar});
        }
        String album = pick.optString("albumId", "");
        if (album.length() > 0) {
            rows.add(new String[]{k, "album", pick.optString("book", t)});
        }
        Fav.patchBatch(rows);
        /* ★ v1.3.273：整本预绑定 —— 顺手把收藏里同书其它集也批量写回，免得靠
           2h 缓存兜底。已点过的这一集 patchBatch 会跳过非空字段，不会重复写。 */
        bindAllEpisodes(pick, d);
        SessionLog.i("fav resolve: 《" + t + "》 -> 有声书《"
                + pick.optString("book", "") + "》第" + e0.optInt("ep", 0) + "集");
        String b = nasBase();
        if (b == null) {
            return null;
        }
        String rt2 = et.length() > 0 ? et : t;
        String ra2 = nar.length() > 0 ? nar : a;
        return b + "/r/" + src + "/" + rid + "?title=" + enc(rt2) + "&artist=" + enc(ra2);
    }

    /**
     * ★ v1.3.273：枚举到整本书集表后，把收藏里属于这本书的**每一集**都批量写回
     *   （按集号对齐），不必等逐集点播才惰性绑定。效果：
     *   ① 整本上传集全部变"已绑定"，ABEP 的 2h 缓存对它们形同作废；
     *   ② 重启 / NAS 重启 / 落雪重启都不影响（绑定已落盘到 r1_fav.json）；
     *   ③ 导出 txt 自然带 src=/rid=/rman=1，重传也以本次为准。
     *
     * <p>匹配口径（与 resolveFromBook 同信任等级，绝不瞎绑）：
     *   - 书名：abBookOnly(收藏条目标题) 必须等于 abBookOnly(本书名)；
     *   - 集号：parseEpFromTitle(标题) 必须在集表里 abPick 命中（ep 号书内唯一）；
     *   - 主播：收藏条目的 artist 非空且本书 narrator 也非空时，二者互不包即跳过
     *           （防同名不同播讲者串味）；artist 为空则照绑（导入常不带主播）；
     *   - 只绑"当前未绑定"的条目（patchBatch 跳过非空字段，不动手动 🔗 的）；
     *   - 集号取不到（番外/整本条目，parseEp=0）或集表里没有该号 ⇒ 跳过，留给懒解析。</p>
     */
    private static void bindAllEpisodes(JSONObject pick, JSONObject d) {
        if (d == null) {
            return;
        }
        JSONArray eps = d.optJSONArray("episodes");
        if (eps == null || eps.length() == 0) {
            return;
        }
        String bookName = d.optString("book", "");
        String bt = abBookOnly(bookName.length() > 0 ? bookName : pick.optString("book", ""));
        if (bt.length() == 0) {
            return;
        }
        String nar = d.optString("narrator", "");
        String album = pick.optString("book", bookName);
        JSONArray a = Fav.list();
        if (a == null || a.length() == 0) {
            return;
        }
        int bound = 0;
        List<String[]> rows = new ArrayList<String[]>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) {
                continue;
            }
            String ek = it.optString("k", "");
            if (ek.length() == 0) {
                continue;
            }
            String title = it.optString("title", "");
            if (title.length() == 0) {
                continue;
            }
            if (!abBookOnly(title).equals(bt)) {
                continue;                                   // 不是这本书的收藏条目
            }
            int ep = parseEpFromTitle(title);
            if (ep <= 0) {
                continue;                                   // 番外/整本条目，留给懒解析
            }
            JSONObject e0 = abPick(eps, ep);
            if (e0 == null) {
                continue;                                   // 集表里没有这一集，不瞎绑
            }
            String src = e0.optString("source", d.optString("source", "kw"));
            String rid = e0.optString("rid", "");
            if (src.length() == 0 || rid.length() == 0) {
                continue;
            }
            String eArtist = it.optString("artist", "").trim();
            if (eArtist.length() > 0 && nar.length() > 0
                    && eArtist.indexOf(nar) < 0 && nar.indexOf(eArtist) < 0) {
                continue;                                   // 主播对不上，疑同名不同播讲者
            }
            rows.add(new String[]{ek, "kind", "program"});
            rows.add(new String[]{ek, "source", src});
            rows.add(new String[]{ek, "rid", rid});
            String et = e0.optString("title", "");
            if (et.length() > 0) {
                rows.add(new String[]{ek, "rtitle", et});
            }
            if (nar.length() > 0) {
                rows.add(new String[]{ek, "rartist", nar});
            }
            if (album.length() > 0) {
                rows.add(new String[]{ek, "album", album});
            }
            bound++;
        }
        if (rows.isEmpty()) {
            return;
        }
        int n = Fav.patchBatch(rows);
        SessionLog.i("bind all episodes: 《" + bt + "》批量绑定 " + bound
                + " 集 / " + n + " 字段");
    }

    /** v1.3.266：从标题里抠「第N集/回/章/话」（N 阿拉伯数字；中文数字尽力而为）。 */
    private static int parseEpFromTitle(String t) {
        if (t == null) {
            return 0;
        }
        java.util.regex.Matcher m = Pattern
                .compile("第\\s*(\\d+|十|百|[一二三四五六七八九]+)\\s*[集回章话]")
                .matcher(t);
        if (!m.find()) {
            return 0;
        }
        String num = m.group(1);
        if (num.matches("\\d+")) {
            try {
                return Integer.parseInt(num);
            } catch (Throwable ignored) {
                return 0;
            }
        }
        if (num.equals("十")) {
            return 10;
        }
        if (num.equals("百")) {
            return 100;
        }
        int v = 0;
        for (int i = 0; i < num.length(); i++) {
            int d = "零一二三四五六七八九".indexOf(num.charAt(i));
            if (d >= 0) {
                v = v * 10 + d;
            }
        }
        return v;
    }

    /**
     * v1.3.269：收藏列表点播（play_song 带 artist）的**严格**解析链 ——
     * 与 {@link #favResolve} 同一套判据，但**没有盲播兜底**。
     *
     * <p>由来：web 收藏栏点「还没配源」的条目时，player 的 favPlayOneGo 兜底发
     * {@code play_song&title=&artist=}，而旧的在线兜底 {@link #playOnlineSong}
     * 盲取搜索第 1 条（按热度排）——《道诡异仙》第N集被顶成同名歌《丹阳子》、
     * 《Hotline Bling》(Billie Eilish) 被顶成 Drake 原唱，全是它干的。</p>
     *
     * <p>链路：① 条目 kind=program（导入时 detectKind 认出的有声书）⇒ 先走
     * {@link #favResolveProgram}（/api/ab/search + /api/ab/episodes，与 ab_play
     * 同源），命中后把补写的 source/rid/rtitle/rartist 读回来推播；书库没接着
     * 就直接放弃 —— 有声书按歌名去搜歌必然是错的，宁可不播。② 普通歌 ⇒
     * {@link #favSearchOne} 打分搜（osScore + 歌手闸 + FAV_HIT_MIN，翻唱/别人
     * 的同名歌不参与）。拿到 source/rid 交给 {@link #playOnlineItem}
     * （取链失败自动退惰性地址）。</p>
     *
     * @return 播放结果话术；拿不到可信匹配返回 null（调用方决定报错还是兜底）
     */
    private static String favStrictPlay(String t, String a) {
        try {
            if (t == null || t.trim().length() == 0) {
                return null;
            }
            t = t.trim();
            String k = Fav.key(t, a);
            JSONObject it = Fav.entry(k);
            String kind = (it == null) ? "" : it.optString("kind", "");
            /* ★ v1.3.271 快路径：条目已经解析出 source/rid（上次播过 /
               fav_resolve 写回过）就直接取链起播，不再问书库 ——
               原来 kind=program 每次点击都重跑 favResolveProgram
               （ab/search + episodes + 写回），冷时要十几秒才出声。 */
            if (it != null) {
                String hsrc = it.optString("source", "");
                String hrid = it.optString("rid", "");
                if (hsrc.length() > 0 && hrid.length() > 0) {
                    String hst = it.optString("rtitle", "");
                    String hsa = it.optString("rartist", "");
                    if (hst.length() == 0) {
                        hst = t;
                    }
                    if (hsa.length() == 0) {
                        hsa = a;
                    }
                    String hr = playOnlineItem(hst, hsa, hsrc, hrid, "", "", 20000);
                    SessionLog.i("fav strict play (cached): 《" + t + "》 -> "
                            + hst + (hr == null ? " (取链失败)" : ""));
                    if (hr != null) {
                        return hr;
                    }
                    /* 取链失败不 return，往下走重解析兜底（可能旧 rid 失效） */
                }
            }
            /* ★ v1.3.274 改序：普通歌（kind!=program）先按歌搜、搜不到再问书库。
               原来「书库优先」对每首未绑定歌都无差别先跑 favResolveProgram
               （6s program 过滤快搜 + NAS ab/search 20s 档）⇒ 冷时十几秒才出声；
               更糟的是同名书会把真歌顶掉（实测《失恋阵线联盟》被解析成
               有声书第0集）。裸书名（如「道诡异仙」）歌搜必然 unsure
               （歌手闸），自然落到下面的书库探测，书的行为不丢。 */
            String kindF = "program".equals(kind) ? "program" : kind;
            JSONObject s = null;
            boolean songSearched = false;
            if (!"program".equals(kind)) {
                s = favSearchOne(t, a, 8000, kindF);
                songSearched = true;
                if (s != null && !s.optBoolean("unsure", false)) {
                    String src0 = s.optString("source", "");
                    String rid0 = s.optString("rid", "");
                    if (src0.length() > 0 && rid0.length() > 0) {
                        String st0 = s.optString("title", t);
                        String sa0 = s.optString("artist", a);
                        String r0 = playOnlineItem(st0, sa0, src0, rid0,
                                s.optString("album", ""), s.optString("duration", ""), 8000);
                        SessionLog.i("fav strict play (song): 《" + t + "》/" + a + " -> "
                                + st0 + " - " + sa0 + (r0 == null ? " (取链失败)" : ""));
                        return r0;
                    }
                }
                /* 歌没拿下 ⇒ 落到下面的书库探测（裸书名兜底） */
            }
            /* ① 书库探测：kind=program 的必问；普通歌仅在「无歌手的裸书名」才兜底问
               （有歌手的歌搜 unsure 绝不落书库 —— 否则会被同名有声书顶替成节目，
               实测点「我的收藏」里的歌却跳回正在听的「我的节目」）。 */
            boolean mayProbeBook = "program".equals(kind)
                    || (a == null || a.trim().length() == 0);
            String p = mayProbeBook ? favResolveProgram(t, a, k) : null;
            if (p != null) {
                JSONObject it2 = Fav.entry(k);
                String src = (it2 == null) ? "" : it2.optString("source", "");
                String rid = (it2 == null) ? "" : it2.optString("rid", "");
                if (src.length() > 0 && rid.length() > 0) {
                    String st = it2.optString("rtitle", "");
                    String sa = it2.optString("rartist", "");
                    if (st.length() == 0) {
                        st = t;
                    }
                    if (sa.length() == 0) {
                        sa = a;
                    }
                    String r = playOnlineItem(st, sa, src, rid, "", "", 20000);
                    SessionLog.i("fav strict play (program): 《" + t + "》 -> "
                            + st + (r == null ? " (取链失败)" : ""));
                    return (r != null) ? r : "ERR 节目解析好了但取链失败，稍后再试";
                }
            }
            /* ② 打分搜歌（仅 kind=program 走到这 —— 节目过滤搜，绝不混进普通歌；
               普通歌刚才已搜过且失败，不重复烧时间）。 */
            if (!songSearched) {
                s = favSearchOne(t, a, 8000, kindF);
            }
            if (s == null || s.optBoolean("unsure", false)) {
                return null;
            }
            String src = s.optString("source", "");
            String rid = s.optString("rid", "");
            if (src.length() == 0 || rid.length() == 0) {
                return null;
            }
            String st = s.optString("title", t);
            String sa = s.optString("artist", a);
            String r = playOnlineItem(st, sa, src, rid,
                    s.optString("album", ""), s.optString("duration", ""), 8000);
            SessionLog.i("fav strict play (song): 《" + t + "》/" + a + " -> "
                    + st + " - " + sa + (r == null ? " (取链失败)" : ""));
            return r;
        } catch (Throwable ex) {
            SessionLog.i("fav strict play failed: " + ex);
            return null;
        }
    }

    /**
     * ★ 1.3.335：问服务端歌曲 rid 缓存（v1.7.11 GET /api/song/resolve）。
     * 命中返回 {ok:true, rid, source, title, artist, cached...}；没有/出错返回 null。
     * 服务端只做**单条**解析（缓存命中零上游；未命中它自己单次搜索 + 落缓存 +
     * 10 分钟负缓存），device 这里绝不批量 —— 与有声书缺集救援同一条风控红线。
     * 用户拍板（2026-11-14）：「歌曲也和有声书一样走服务器 rid 缓存吧」。
     */
    private static JSONObject songResolveSrv(String title, String artist) {
        try {
            String b = nasBase();
            if (b == null || title == null || title.trim().length() == 0) {
                return null;
            }
            String js = httpGet(b + "/api/song/resolve?title="
                    + URLEncoder.encode(title.trim(), "UTF-8")
                    + ((artist != null && artist.trim().length() > 0)
                        ? ("&artist=" + URLEncoder.encode(artist.trim(), "UTF-8")) : ""),
                    15000);
            if (js == null) {
                return null;
            }
            JSONObject o = new JSONObject(js);
            if (o.optBoolean("ok", false) && o.optString("rid", "").trim().length() > 0) {
                return o;
            }
        } catch (Throwable t) {
            SessionLog.i("song resolve srv: " + t);
        }
        return null;
    }

    public static String favResolve(JSONObject args) {
        String k = (args == null) ? "" : args.optString("k", "").trim();
        String t = (args == null) ? "" : args.optString("title", "").trim();
        String a = (args == null) ? "" : args.optString("artist", "").trim();
        if (k.length() == 0) {
            if (t.length() == 0) {
                return "ERR 没给要解析哪一条收藏";
            }
            k = Fav.key(t, a);
        }
        JSONObject it = Fav.entry(k);
        if (it != null) {
            if (t.length() == 0) {
                t = it.optString("title", "");
            }
            if (a.length() == 0) {
                a = it.optString("artist", "");
            }
        }
        if (t.length() == 0) {
            return "ERR 收藏里没有这一首";
        }
        String b = nasBase();
        if (b == null) {
            return "ERR 音乐源没配（NAS 地址为空）";
        }
        String src = (it == null) ? "" : it.optString("source", "");
        String rid = (it == null) ? "" : it.optString("rid", "");
        String rt = (it == null) ? "" : it.optString("rtitle", "");
        String ra = (it == null) ? "" : it.optString("rartist", "");
        if (src.length() == 0 || rid.length() == 0) {
            /* ★ v1.3.230：导入期已不批量模糊匹配，这里按需**单次**补本地匹配 ——
               本地库里有这歌（哪怕歌名略差几个字）优先播本地文件（NAS 直链、秒开、
               不依赖取链）。本地没有才走下面的在线搜索。 */
            List<String[]> lok = localTracksSnapshot();
            String[][] lKT = new String[lok.size()][];
            String[][] lKAT = new String[lok.size()][];
            for (int i = 0; i < lok.size(); i++) {
                String[] e = lok.get(i);
                lKT[i] = Fav.tokens((e != null) ? e[0] : "");
                lKAT[i] = Fav.tokens((e != null && e.length > 1) ? e[1] : "");
            }
            int lh = findLocalFuzzy(t, a, lok, lKT, lKAT);
            /* ★ v1.3.240：本地模糊命中也必须过**歌手闸门**。
               词元覆盖率 100% 不等于同一首：《SKINNY》(Billie Eilish) 的词元被
               《Skinny Love》(Birdy) 完整覆盖 ⇒ 会被认成命中，于是又写回
               ltitle/lartist + 那条 Birdy 的地址（v1.3.239 白拦了）。
               歌手完全不是一个人 ⇒ 弃用这次命中，交给下面的在线搜索（按「歌名 歌手」搜，
               实测 kw 第一条就是对的版本）。 */
            if (lh >= 0) {
                String[] ce = lok.get(lh);
                String ca = (ce != null && ce.length > 1) ? ce[1] : "";
                if (a.length() > 0 && ca != null && ca.trim().length() > 0
                        && !Fav.artistSame(a, ca)) {
                    SessionLog.i("fav resolve: 《" + t + "》本地候选《" + ce[0] + "》歌手不符，弃用");
                    lh = -1;
                }
            }
            if (lh >= 0) {
                String[] e = lok.get(lh);
                String lurl = (e != null && e.length > 3 && e[3] != null) ? e[3] : "";
                if (lurl.length() > 0) {
                    List<String[]> lrows = new ArrayList<String[]>();
                    lrows.add(new String[]{k, "url", lurl});
                    if (e[0] != null && !e[0].equals(t)) {
                        lrows.add(new String[]{k, "ltitle", e[0]});
                    }
                    if (e.length > 1 && e[1] != null && !e[1].equals(a)) {
                        lrows.add(new String[]{k, "lartist", e[1]});
                    }
                    Fav.patchBatch(lrows);
                    SessionLog.i("fav resolve: 《" + t + "》 -> 本地 " + e[0]);
                    return lurl;
                }
            }
            /* 本地没有 ⇒ 就地现搜一次（这一次是**真的要播**，值得等）。
               ★ v1.3.262：有声书条目按 kind=program 搜，同名歌不掺进来。
               ★ v1.3.274 改序：普通歌（kind!=program）先按歌搜，搜不到/拿不准
               再问书库 —— 原来「先问书库」让每首未绑定歌都白付 6s program 快搜
               + NAS ab/search（十几秒才出声），且同名书会把真歌顶掉（实测
               《失恋阵线联盟》被解析成有声书第0集）。裸书名歌搜必然 unsure
               （歌手闸），落到书库探测，书的行为不丢。 */
            String kind0 = Fav.entry(k).optString("kind", "");
            /* ★ 1.3.335：服务端歌曲 rid 缓存优先（v1.7.11）—— 搜过的歌零上游秒回；
               没搜过由服务端替我们搜并落缓存（多设备共享同一份）。
               节目条目不走这条（书有专门的 favResolveProgram 书库探测）。 */
            JSONObject s = null;
            boolean srvMiss = false;
            if (!"program".equals(kind0)) {
                s = songResolveSrv(t, a);
                srvMiss = (s == null);
            }
            if (s == null) {
                s = favSearchOne(t, a, 8000, kind0);
            }
            boolean unsure = (s != null) && s.optBoolean("unsure", false);
            if ((s == null || unsure) && !"program".equals(kind0)) {
                /* ★ v1.3.266：书变成歌的老毛病 —— 裸书名（无「有声书/评书」等关键词，
                   如「道诡异仙」）歌搜拿不准时问书库兜底：命中就按节目解析、
                   标记 kind=program，不再按普通歌播。 */
                String pUrl = favResolveProgram(t, a, k);
                if (pUrl != null) {
                    return pUrl;
                }
            }
            src = (s == null) ? "" : s.optString("source", "");
            rid = (s == null) ? "" : s.optString("rid", "");
            if (unsure || src.length() == 0 || rid.length() == 0) {
                /* ★ 跟导入时同一口径：unsure（歌名对不上）不记负缓存 —— 那是
                   「没敢认」，不是「搜不到」，记下去会把真歌永久判死。 */
                /* ★ 1.3.335：服务端答不上（负缓存 / 网络抖动）不判死 —— 负缓存窗口
                   过后用户再点还能重试；只有 device 自己真搜过没有才 markNoSrc。 */
                if (s == null && !srvMiss) {
                    Fav.markNoSrc(k);
                }
                return "ERR 没搜到《" + t + "》的可播地址";
            }
            String st = s.optString("title", "");
            String sa = s.optString("artist", "");
            List<String[]> rows = new ArrayList<String[]>();
            rows.add(new String[]{k, "source", src});
            rows.add(new String[]{k, "rid", rid});
            if (st.length() > 0 && !st.equals(t)) {
                rows.add(new String[]{k, "rtitle", st});
                rt = st;
            }
            if (sa.length() > 0 && !sa.equals(a)) {
                rows.add(new String[]{k, "rartist", sa});
                ra = sa;
            }
            Fav.patchBatch(rows);
            SessionLog.i("fav resolve: 《" + t + "》 -> " + src + "/" + rid);
        }
        if (rt.length() == 0) {
            rt = t;
        }
        if (ra.length() == 0) {
            ra = a;
        }
        return b + "/r/" + src + "/" + rid + "?title=" + enc(rt) + "&artist=" + enc(ra);
    }

    /** 语音「取消收藏 / 不喜欢这首歌」：不给 title = 取消正在播的那首。 */
    public static String favDel(JSONObject args) {
        String t = (args == null) ? "" : args.optString("title", "").trim();
        if (t.length() > 0) {
            return Fav.del(t, args.optString("artist", "").trim());
        }
        JSONObject c = curSongForFav();
        if (c == null) {
            return "ERR 现在没在播，不知道要取消哪一首";
        }
        return Fav.del(c.optString("title", ""), c.optString("artist", ""));
    }

    /**
     * 在本地曲库段里找这一首。
     *
     * <p>★ 播收藏必须**本地优先**：用户收藏《晴天》时那首可能是从在线搜来的
     * （存的是 {@code /r/<source>/<rid>} 惰性地址），但本地库里其实有同一个文件 ——
     * 那就播本地的（NAS 直链、秒开、不依赖取链），本地没有才去网络上取。</p>
     *
     * <p>比对用 {@link Fav#key}（归一化后比）：收藏时记的歌手名和库里的可能差一个
     * 「/ feat.」之类；歌手对不上时退一步只比曲名。</p>
     *
     * @return 本地段下标（0-based，-1 = 本地没有）
     */
    private static int findLocal(String title, String artist) {
        /* ★★ v1.3.203：本地段 key 索引（一次性建，反复查）。
           以前每收一首都要把本地段 1200 首**逐条算一遍 key** 去比：
           21 首 × 1200 × 2 遍 ≈ 5 万次字符串归一化 ⇒ 实测导入要 3.5~4.7 秒，
           而同样是推列表的「随机 300 首」只要 0.12 秒 —— 差别全在这里。
           现在：整段只算一次（1200×2），之后每首 O(1) 查 HashMap。 */
        synchronized (tracks) {
            HashMap<String, Integer> full = sLocFull;
            HashMap<String, Integer> ttl = sLocTitle;
            if (full == null || ttl == null || sLocVer != sIdxVer) {
                List<String[]> loc = localTracksSnapshot();
                full = new HashMap<String, Integer>(Math.max(16, loc.size() * 2));
                ttl = new HashMap<String, Integer>(Math.max(16, loc.size() * 2));
                for (int i = 0; i < loc.size(); i++) {
                    String[] e = loc.get(i);
                    if (e == null) {
                        continue;
                    }
                    String kf = Fav.key(e[0], (e.length > 1) ? e[1] : "");
                    if (!full.containsKey(kf)) {
                        full.put(kf, Integer.valueOf(i));
                    }
                    String kt = Fav.key(e[0], "");
                    if (!ttl.containsKey(kt)) {
                        ttl.put(kt, Integer.valueOf(i));
                    }
                }
                sLocFull = full;
                sLocTitle = ttl;
                sLocList = loc;
                sLocVer = sIdxVer;
            }
            Integer v = full.get(Fav.key(title, artist));
            if (v != null) {
                return v.intValue();
            }
            /* ★ v1.3.235：title-only 回退必须过**歌手闸门**。
               原来「曲名一样就算命中」会把收藏的 Billie Eilish《Six Feet Under》
               认成库里的 The Weeknd 版 ⇒ ① 播错歌；② 队列行用的是库内歌名/歌手，
               页面上那颗星按 key(库名,库歌手) 查收藏必然查不到 ⇒ 用户看到「很多歌没星」。
               星标其实是对的：它忠实反映「这一行不是你那首」。 */
            return titleOnlyIdx(title, artist);
        }
    }

    /** v1.3.235：① 精确命中本地库时，把库内真名收进 locFix（整批落盘一次）。
     *  只服务于星标 / 下次 O(1) 命中，**不参与展示**（展示仍用 rtitle>ltitle>title）。 */
    private static void addLocFix(List<String[]> locFix, String title, String artist,
            List<String[]> keep, int idx) {
        if (locFix == null || keep == null || idx < 0 || idx >= keep.size()) {
            return;
        }
        String[] e = keep.get(idx);
        if (e == null) {
            return;
        }
        String lk = Fav.key(title, artist);
        String lt = (e[0] == null) ? "" : e[0];
        String la = (e.length > 1 && e[1] != null) ? e[1] : "";
        if (lt.length() > 0) {
            locFix.add(new String[]{lk, "ltitle", lt});
        }
        if (la.length() > 0) {
            locFix.add(new String[]{lk, "lartist", la});
        }
    }

    /** v1.3.235：只有曲名对得上时怎么选 —— 歌手必须「相关」才认，否则 -1（交给占位/在线搜）。 */
    private static int titleOnlyIdx(String title, String artist) {
        List<String[]> loc = sLocList;
        HashMap<String, Integer> ttl = sLocTitle;
        if (loc == null || ttl == null) {
            return -1;
        }
        Integer v = ttl.get(Fav.key(title, ""));
        if (v == null) {
            return -1;
        }
        int first = v.intValue();
        if (artistOkForLocal(artist, first)) {
            return first;
        }
        /* 库里同名多首（Try / Animals / Paradise 这种常见名）⇒ 挑歌手对得上的那一首，
           别一股脑认「第一首」。 */
        String kt = Fav.key(title, "");
        for (int i = 0; i < loc.size(); i++) {
            String[] e = loc.get(i);
            if (e == null || !kt.equals(Fav.key(e[0], ""))) {
                continue;
            }
            if (artistOkForLocal(artist, i)) {
                return i;
            }
        }
        return -1;
    }

    /** v1.3.235：库内第 idx 首的歌手和请求的歌手是不是「同一首歌的歌手」。
     *  规则（照收藏匹配的老口径：歌手当判据不当加分）：
     *  ① 任一方没写歌手 ⇒ 无从判断，认；
     *  ② 库内 ID3 脏（歌手字段填的其实是歌名）⇒ 等于没歌手，认；
     *  ③ 否则必须有一个词元重合，全不重合 ⇒ 不认（同名不同歌，别乱播）。 */
    private static boolean artistOkForLocal(String artist, int idx) {
        List<String[]> loc = sLocList;
        if (loc == null || idx < 0 || idx >= loc.size()) {
            return true;
        }
        String[] e = loc.get(idx);
        if (e == null) {
            return true;
        }
        String la = (e.length > 1 && e[1] != null) ? e[1].trim() : "";
        String fa = (artist == null) ? "" : artist.trim();
        if (fa.length() == 0 || la.length() == 0) {
            return true;
        }
        String lname = (e[0] == null) ? "" : e[0];
        if (Fav.key(la, "").equals(Fav.key(lname, ""))) {
            return true;
        }
        String[] A = Fav.tokens(fa);
        String[] B = Fav.tokens(la);
        for (int i = 0; i < A.length; i++) {
            for (int j = 0; j < B.length; j++) {
                if (A[i] != null && A[i].equals(B[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 播收藏列表（语音「播放我喜欢的歌 / 播放收藏列表」、网页「导入播放列表」）。
     *
     * <p>★ 取值优先级 —— **本地优先，网络兜底**（同一首歌常常同时有几种身份）：</p>
     * <pre>
     *   ① 本地曲库按「曲名+歌手」命中 ⇒ 播本地那条（NAS 直链，最稳、最快）
     *   ② 本地没命中，但收藏里记了库内序号且对得上 ⇒ 用序号
     *   ③ 存下来的**直链**（在线取过链的地址 / 电台流）⇒ 直接用
     *   ④ 在线四件套（source+rid）⇒ 本地真没有，去网络取（第一首正式取链，其余惰性）
     *   ⑤ 惰性地址（/r/…）保底
     *   ⑥ 什么都没有 ⇒ 拿曲名回全库 matchTracks 碰运气
     * </pre>
     */
    /* v1.3.219：播放列表兜底用的「最松匹配」—— 只要求曲名包含关键词、或关键词包含曲名
       （含歌手字段），不要求分词全中。用于 play_fav 里 matchTracks 全失后的二次兜底，
       让「差一点点」的歌也能配上。 */
    /** 宽松匹配里「反向包含」要求被包含的那一方至少这么长（短于这个就是瞎配）。 */
    private static final int LENIENT_MIN = 3;

    /**
     * v1.3.222：宽松匹配的「像不像」打分（0 = 不像，越大越像）。
     *
     * <p>★★ 修一个真 bug（v1.3.219 引入的宽松匹配）：原来的判据是
     * {@code ti.contains(key) || key.contains(ti) || ar.contains(key) || key.contains(ar)}，
     * 其中 {@code key.indexOf(ti)} 在 {@code ti} 是**空串**时恒为 0 ⇒ 恒真 ⇒
     * 库里任何一首「歌名为空」或「歌手为空」的曲子都能配上**任何**歌名。
     * 实测：probeA / probeB / probeC / 测试测试-这是测试 四首八竿子打不着的收藏，
     * 全被指到了同一首《Merry Christmas Mr. Lawrence》上。
     * ⇒ 反向包含（短的那个藏在长的里面）必须要求**被包含的一方够长**才有意义。</p>
     *
     * <p>★ v1.3.224：**60 / 30 那两档（「搜索词里含库名」）整个砍掉**。留着它就得接受
     * 这样的结果：收藏里的《紅蓮華》被库里《The Very First Night》含住 ⇒ 认 shot，
     * 用户的歌单因此变成一串英文名。这一档本来就是「猜」的，《Il aurait suffi》被指到
     * 《Waiting for the Night》就是这么来的 —— 宁可让它落到在线搜索那一步去问 NAS，
     * 也别在本地库里瞎认一首。剩下的档：相等 100 ＞ 库名含搜索词 80 ＞ 歌手名含搜索词 40。</p>
     */
    private static int lenientScore(String key, String[] t) {
        if (key == null || t == null) {
            return 0;
        }
        String ti = (t[0] == null) ? "" : t[0].toLowerCase();
        String ar = (t.length > 1 && t[1] != null) ? t[1].toLowerCase() : "";
        if (ti.length() > 0 && ti.equals(key)) {
            return 100;                       // 完全相等
        }
        if (ti.length() > 0 && ti.indexOf(key) >= 0) {
            return 80;                        // 库名里含搜索词（《晴天 (Live)》配「晴天」）
        }
        if (ar.length() > 0 && ar.indexOf(key) >= 0) {
            return 40;                        // 歌手名里含搜索词
        }
        return 0;
    }

    private static List<Integer> matchTracksLenient(String k) {
        List<Integer> out = new ArrayList<Integer>();
        if (k == null) return out;
        final String key = k.trim().toLowerCase();
        if (key.length() == 0) return out;
        final List<Integer> idx = new ArrayList<Integer>();
        final List<Integer> sc = new ArrayList<Integer>();
        for (int i = 0; i < tracks.size(); i++) {
            String[] t = tracks.get(i);
            int s = lenientScore(key, t);
            if (s <= 0) {
                continue;
            }
            String ti = (t[0] == null) ? "" : t[0];
            idx.add(Integer.valueOf(i));
            /* 同档 ⇒ 长度差越小越像：「晴天」配「晴天 (Live)」比配「晴天万里」好 */
            sc.add(Integer.valueOf(s * 1000
                    - Math.min(999, Math.abs(ti.length() - key.length()))));
        }
        if (idx.isEmpty()) {
            return out;
        }
        List<Integer> pos = new ArrayList<Integer>();
        for (int i = 0; i < idx.size(); i++) {
            pos.add(Integer.valueOf(i));
        }
        Collections.sort(pos, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int sa = sc.get(a.intValue()).intValue();
                int sb = sc.get(b.intValue()).intValue();
                if (sa != sb) {
                    return sa < sb ? 1 : -1;              // 分高的在前
                }
                int ia = idx.get(a.intValue()).intValue();
                int ib = idx.get(b.intValue()).intValue();
                return ia < ib ? -1 : (ia == ib ? 0 : 1); // 同分 ⇒ 库内原序，行为可预测
            }
        });
        for (int i = 0; i < pos.size(); i++) {
            out.add(idx.get(pos.get(i).intValue()));
        }
        return out;
    }

    /* ---------------- v1.3.220：收藏「补源缓存」的后台预热 ---------------- */

    /** 后台补源一次只跑一个（已经跑着就别再开第二个）。 */
    private static volatile boolean sFavWarm = false;
    /** 后台补源总预算：最多补 3 分钟，剩下的下次再补（不至于一直占着 NAS）。 */
    private static final long FAV_WARM_BUDGET = 180000L;

    /**
     * 把「光杆」收藏（只有歌名、没地址）在**后台**补上 source+rid / 负缓存。
     *
     * <p>为什么要有它：{@code playFav} 的在线补源有 15s 总预算（语音「播放我喜欢的歌」
     * 不能卡在那儿不回话），357 首的歌单一次根本补不完 ⇒ 以前每次导入都从头再搜一遍，
     * 看起来就是「没有缓存」。现在前台照旧 15s 快回，剩下的丢后台慢慢补完 ——
     * 补一条落一条，补完之后再导入就是**零在线搜索**。</p>
     */
    private static void warmFavSource(List<String[]> rest) {
        if (rest == null || rest.isEmpty()) {
            return;
        }
        synchronized (PlayerBridge.class) {
            if (sFavWarm) {
                return;
            }
            sFavWarm = true;
        }
        final List<String[]> todo = new ArrayList<String[]>(rest);
        new Thread(new Runnable() {
            public void run() {
                int ok = 0, bad = 0;
                try {
                    long t0 = System.currentTimeMillis();
                    for (int i = 0; i < todo.size(); i++) {
                        if (System.currentTimeMillis() - t0 > FAV_WARM_BUDGET) {
                            break;
                        }
                        String[] e = todo.get(i);
                        if (e == null || e.length < 2 || e[0] == null) {
                            continue;
                        }
                        JSONObject s = favSearchOne(e[0], e[1], 5000,
                                Fav.entry(Fav.key(e[0],
                                        (e[1] == null) ? "" : e[1])).optString("kind", ""));
                        String src = (s == null) ? "" : s.optString("source", "");
                        String rid = (s == null) ? "" : s.optString("rid", "");
                        String k = Fav.key(e[0], (e[1] == null) ? "" : e[1]);
                        /* ★ v1.3.224：名字对不上的（unsure）**不落盘**（会把瞎配的结果
                           永久固化），但负缓存照记 —— 否则每轮都重新搜一遍，缓存白做。
                           负缓存以 key 为粒度，歌名改了就是新 key，下一轮自动重搜。 */
                        if (s != null && s.optBoolean("unsure", false)) {
                            Fav.markNoSrc(k);
                            bad++;
                            continue;
                        }
                        if (src.length() > 0 && rid.length() > 0) {
                            Fav.patch(k, "source", src);
                            Fav.patch(k, "rid", rid);
                            ok++;
                        } else {
                            Fav.markNoSrc(k);          // 记负缓存，6 小时内不再为它白搜
                            bad++;
                        }
                    }
                    SessionLog.i("fav warm: 后台补源 " + ok + " 首，无源 " + bad + " 首");
                } catch (Throwable t) {
                    SessionLog.i("fav warm 异常: " + t);
                } finally {
                    sFavWarm = false;
                }
            }
        }, "fav-warm").start();
    }

    /**
     * v1.3.237：入列时同时记「这条来自收藏第几条」，两个 list 永远同进同退。
     *
     * @param pick   tracks 下标（pushPlaylist 按它取歌）
     * @param favId  与 pick 一一对应的收藏序号，push 前用它还原顺序
     */
    private static void pickAdd(List<Integer> pick, List<Integer> favId, int idx, int fi) {
        pick.add(Integer.valueOf(idx));
        favId.add(Integer.valueOf(fi));
    }

    /**
     * v1.3.237：按收藏序号**稳定**重排 pick —— 队列顺序必须等于收藏（= 导入歌单）顺序。
     *
     * <p>为什么需要：{@link #playFav()} 里能立刻定地址的歌（本地命中 / 直链 / 在线四件套）
     * 是当场 {@code tracks.add} 的，而没配上的歌要走 {@code need} → 循环结束后才补进
     * {@code tracks} 尾部。两条路都进了 pick，但顺序是「有源的在前、光杆的在后」，
     * 于是每次导入播放列表，顺序都和收藏列表不一样（用户报的「顺序发生变化」）。</p>
     *
     * <p>用插入排序（几百条，n² 也就几十万次比较，比新建对象数组省事），
     * 相同序号不交换 ⇒ 稳定。</p>
     */
    private static void sortPickByFav(List<Integer> pick, List<Integer> favId) {
        int n = Math.min(pick.size(), favId.size());
        for (int i = 1; i < n; i++) {
            int fv = favId.get(i).intValue();
            int pv = pick.get(i).intValue();
            int j = i - 1;
            while (j >= 0 && favId.get(j).intValue() > fv) {
                favId.set(j + 1, favId.get(j));
                pick.set(j + 1, pick.get(j));
                j--;
            }
            favId.set(j + 1, Integer.valueOf(fv));
            pick.set(j + 1, Integer.valueOf(pv));
        }
    }

    public static String playFav() {
        if (!idxLoaded) {
            loadIndex();
        }
        JSONArray fav = Fav.list();
        if (fav == null || fav.length() == 0) {
            return "ERR 收藏列表还是空的（先在网页上点个 ☆，或跟我说「收藏这首歌」）";
        }
        String b = nasBase();
        /* ★★ v1.3.224：先把「和收藏原名八竿子打不着」的旧缓存整表洗一遍。
           旧版本瞎配出来的 ltitle / rtitle / source / rid 会一路留到现在：
           上一版那种《Il aurait suffi》⇒《Waiting for the Night》就是其中之一，
           而且写上就再也洗不掉 —— 清理原来写在下面的主循环里，那条循环带
           pick.size() < cap 上限，**后面的收藏根本走不到**，脏数据就永久驻留了。
           这一步纯字符串比较，不发网络也不碰曲库，几百条几毫秒。 */
        int purged = Fav.purgeUnrelated();
        if (purged > 0) {
            SessionLog.i("fav purge: 清掉 " + purged + " 条和歌名对不上的旧缓存");
        }
        List<String[]> keep = localTracksSnapshot();
        /* ★ v1.3.225：库侧**词元**预算 —— 每首库里的歌只 fold/分词一次，别在一次导入里
           把同一件事重复 357 遍（那正是「导入要等十几秒」的大头）。 */
        final int kn = keep.size();
        final String[][] KT = new String[kn][];
        final String[][] KAT = new String[kn][];
        for (int i = 0; i < kn; i++) {
            String[] e = keep.get(i);
            KT[i] = Fav.tokens((e != null) ? e[0] : "");
            KAT[i] = Fav.tokens((e != null && e.length > 1) ? e[1] : "");
        }
        List<Integer> pick = new ArrayList<Integer>();
        /* ★ v1.3.237：与 pick 一一对应，记这一条来自收藏的第几条。
           主循环里「本地命中 / 有直链 / 有在线源」的歌是**边走边入列**的，
           而光杆歌（need → 占位）要等循环全部跑完才补进 tracks ⇒ 它们整体漂到队列
           尾部，用户看到「导入后播放列表顺序变了」。有了收藏序号，push 前重排回去。 */
        List<Integer> pickFav = new ArrayList<Integer>();
        /* ★★★ v1.3.241：`int cap = maxPlaylist()` 整个撤掉 —— 导入收藏这条链路上
           不再有任何**条数**截断（主循环 / 占位 / got / need 四处都改完了）。
           唯一的天花板是 pushPlaylist 里那一次 Binder 事务的字节预算。 */
        int fromLocal = 0, fromOnline = 0, fromPending = 0, missed = 0;
        List<String> missedTitles = new ArrayList<String>();
        List<String[]> need = new ArrayList<String[]>();   // 光杆条目（只有歌名）
        List<String[]> got = new ArrayList<String[]>();    // 在线补到源的（锁外搜，锁内入列）
        /* ★ v1.3.221：本轮**模糊匹配**命中的，把「库里那首的真名」攒下来，锁外一次性落盘。
           下次进来走 ①b 直接 O(1) 命中，不用再把整库字符串比一遍 —— 几百首时
           那一次全库比对就是好几秒，也是「每次导入都要等」的真正来源。 */
        List<String[]> locFix = new ArrayList<String[]>();
        /* ★ v1.3.223：在线补源搜回来的**真名**（rtitle/rartist），同样是锁外整批落盘。
           列表里显示真名、导出的 txt 也是真名，二次导入就再不用模糊匹配了。 */
        List<String[]> realFix = new ArrayList<String[]>();
        int fi = fav.length();               // 主循环实际走到哪儿（列表满了会提前停）
        synchronized (tracks) {
            tracks.clear();
            tracks.addAll(keep);
            for (int i = 0; i < fav.length(); i++) {
                /* ★★★ v1.3.241：这里**不再**有「列表满了就 break」。
                   用户口径：从收藏导入播放列表不设数量限制 —— 歌是**要听的时候才拉源**
                   （占位地址 + 播到才 fav_resolve），所以多排几十首既不搜网络也不解析，
                   条数本身零成本。v1.3.225 时代那个 cap 截断会让超出的收藏连队列都进不去，
                   正是「导入少了歌」的元凶之一，现在整段撤掉，全部排进去。
                   唯一的天花板留在 pushPlaylist：那一次 Binder 事务的**字节预算**。 */
                JSONObject it = fav.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                String title = it.optString("title", "");
                String artist = it.optString("artist", "");
                if (title.length() == 0) {
                    continue;
                }
                /* ★ v1.3.243：手动重链接过（rman=1 + 有 source/rid）的条目跳过 ①②③，
                   直接走 ④ 的精确源 —— 用户挑的那一版就是他要的，本地同名文件可能是
                   翻唱/别的录音室版本，让他「改完还是不对」。 */
                boolean manual = "1".equals(it.optString("rman", ""))
                        && it.optString("source", "").length() > 0
                        && it.optString("rid", "").length() > 0;
                // ① 本地优先 —— 就算当初是在线搜到才收的，库里有就播库里的
                int loc = manual ? -1 : findLocal(title, artist);
                if (loc >= 0) {
                    pickAdd(pick, pickFav, loc, i);
                    fromLocal++;
                    /* ★ v1.3.235：把库内真名记回收藏（ltitle/lartist）。
                       ① 是「曲名精确命中」，不是模糊匹配 —— 记它不违反「播到才临时匹配」。
                       它修的是星标：队列行显示的是**库内**歌名/歌手（如
                       「Dancin (KRONO Remix) / Aaron Smith,Luvli,Krono」），而页面的星
                       按 key(歌名,歌手) 查收藏；收藏只存导入原文就永远查不到 ⇒ 一行行没星。 */
                    addLocFix(locFix, title, artist, keep, loc);
                    continue;
                }
                /* ①b ★ v1.3.221：上次模糊匹配命中的那首，库里的真名记在 ltitle/lartist。
                   收藏里的曲名常和库里差几个字（少了「(Live)」、多了空格），精确查必然落空，
                   但真名是精确对得上的 ⇒ 直接 HashMap O(1) 命中，模糊匹配一次都不用跑。
                   歌被库里删了的话这里自然返回 -1，退回 ⑥ 重新匹配，不会指错歌。 */
                String lt = it.optString("ltitle", "");
                if (!manual && lt.length() > 0) {
                    String la = it.optString("lartist", "");
                    int loc2 = findLocal(lt, la);
                    /* ★ 脏缓存自愈（v1.3.224 起交给上面的 Fav.purgeUnrelated 统一做）：
                       进到这里说明这条缓存已经确认「和歌名相关」 ⇒ 直接用，
                       歌被库里删了的话 findLocal 自然返回 -1，退回 ⑥ 重新匹配。 */
                    if (loc2 >= 0) {
                        pickAdd(pick, pickFav, loc2, i);
                        fromLocal++;
                        continue;
                    }
                }
                // ② 收藏时记下的库内序号（1-based）
                int ix = manual ? 0 : it.optInt("index", 0);
                if (ix > 0 && ix <= keep.size()) {
                    String[] e = keep.get(ix - 1);
                    if (e != null && title.equals(String.valueOf(e[0]))) {
                        pickAdd(pick, pickFav, ix - 1, i);
                        fromLocal++;
                        continue;
                    }
                }
                /* ★ v1.3.261：③/④/⑤ 推队列的**显示名用收藏原名(title)**，rt/ra 只用于拼取源 URL。
                   旧 v1.3.223 用 rtitle 当显示名，但 rtitle 是自动匹配结果，错配时会把
                   「bangbang」之类乱名显示给语音/队列；收藏原名才是用户认得的名字，故改回原名。
                   URL 仍带 ?title=rt 仅供 player 提示，不影响解析。 */
                String rt = it.optString("rtitle", "");
                if (rt.length() == 0) {
                    rt = title;
                }
                String ra = it.optString("rartist", "");
                if (ra.length() == 0) {
                    ra = artist;
                }
                String url = it.optString("url", "");
                // ③ 直链（不是 /r/ 惰性地址 ⇒ 拿起来就能播）
                /* ★ v1.3.239：用之前先验一下这条地址到底是不是这首（见 urlMatchesFav）：
                   导入 txt 的地址列可能是历史上某轮瞎配的产物，直接播就是「播别人、显示自己」。 */
                if (url.length() > 0 && url.indexOf("/r/") < 0
                        && urlMatchesFav(url, title, artist)) {
                    tracks.add(new String[]{title, artist, "", url,
                            it.optString("album", ""), it.optString("duration", ""),
                            "", "fav"});
                    pickAdd(pick, pickFav, tracks.size() - 1, i);
                    continue;
                }
                // ④ 在线四件套 ⇒ 本地真没有，只能去网络取
                String src = it.optString("source", "");
                String rid = it.optString("rid", "");
                if (src.length() > 0 && rid.length() > 0 && b != null) {
                    /* ★ v1.3.203：**不提前取链**（用户明确：歌曲源是播放的时候临时拉的）。
                       以前第一首要 onlineUrl() 正式取一次链，白等 0.5~1.5s；
                       惰性地址交给 player 起播时自己取 —— 几十毫秒的事。 */
                    String ou = b + "/r/" + src + "/" + rid
                            + "?title=" + enc(rt) + "&artist=" + enc(ra);
                    tracks.add(new String[]{title, artist, "", ou,
                            it.optString("album", ""), it.optString("duration", ""),
                            "", "fav"});
                    pickAdd(pick, pickFav, tracks.size() - 1, i);
                    continue;
                }
                // ⑤ 惰性地址保底
                /* ★ v1.3.239：③ 被验退了才会走到这里 —— 同一个 url 再验一次，
                   不然它又从这条分支溜进去。 */
                if (url.length() > 0 && urlMatchesFav(url, title, artist)) {
                    tracks.add(new String[]{title, artist, "", url,
                            it.optString("album", ""), it.optString("duration", ""),
                            "", "fav"});
                    pickAdd(pick, pickFav, tracks.size() - 1, i);
                    continue;
                }
                /* ★ v1.3.233：排队列时**不做**离线匹配 —— 匹配只在「播到那一首」时由
                   fav_resolve 临时做（本地单次模糊 + 在线搜索）。v1.3.231/232 一度在这里
                   跑 ⑥ 离线匹配、导入后也自动 fav_rematch，用户明确要求改回「播到才临时匹配」
                   ⇒ 整段撤掉。没配上的歌一律当占位（need → /r/fav?k=），播到才解析。 */
                /* ★ v1.3.234：负缓存**不再把歌从播放列表里剔掉**。导入期本来就不搜任何
                   一首（v1.3.229 起全是占位地址，warmFavSource 也不再被 playFav 调用），
                   skipSearch 在这里已经没有任何「省一次搜索」的意义，只会让「以前搜败过
                   一次」的歌直接从列表里消失 —— 那就是用户说的「少了歌 / 被跳过」。
                   一律排成占位，播到那首时 fav_resolve 再试一次；真搜不到就是 404，
                   引擎自己跳下一首，和别的歌行为一致。 */
                need.add(new String[]{title, artist,
                        it.optString("album", ""), it.optString("duration", ""),
                        String.valueOf(i)});          // [4] = 收藏第几条（v1.3.237 排顺序用）
            }
        }
        /* ★ v1.3.230：播放列表满后剩下的收藏 —— 这轮不入场，也不做本地模糊匹配
           （模糊匹配已移到 fav_resolve 播到时才做），只按缓存/地址/源判断，
           剩下没位置的真·光杆才进 need（占位，播到才解析）。 */
        int tail = 0;
        for (int i = fi; i < fav.length(); i++) {
            JSONObject it = fav.optJSONObject(i);
            if (it == null) {
                continue;
            }
            String title = it.optString("title", "");
            String artist = it.optString("artist", "");
            if (title.length() == 0) {
                continue;
            }
            // ★ v1.3.230：导入期不再做本地模糊匹配（见主循环 ⑥），有缓存/地址/源跳过，其余进 need
            if (it.optString("ltitle", "").length() > 0) {
                continue;
            }
            if (it.optString("source", "").length() > 0) {
                continue;
            }
            if (it.optString("url", "").length() > 0) {
                continue;
            }
            /* ★ v1.3.234：tail 也同理 —— 负缓存不剔歌（见主循环注释）。 */
            need.add(new String[]{title, artist,
                    it.optString("album", ""), it.optString("duration", ""),
                    String.valueOf(i)});              // [4] = 收藏第几条（v1.3.237 排顺序用）
            tail++;
        }
        if (tail > 0) {
            SessionLog.i("fav tail: 列表已满，额外补 " + tail + " 首进补源队列（下次就有缓存了）");
        }
        /* ★ v1.3.221：本轮模糊匹配的结果**整批落盘一次**（不是一条条写）。
           落完之后，下次导入这些歌全部走 ①b 的 HashMap O(1)，模糊匹配一次都不跑
           ⇒ 第二次起就是 0.几秒。 */
        if (!locFix.isEmpty()) {
            int fixed = Fav.patchBatch(locFix);
            if (fixed > 0) {
                SessionLog.i("fav locFix: 记住 " + fixed + " 个库内真名字段（下次 O(1) 命中）");
            }
        }
        /* ---- v1.3.229：导入**零在线搜索** ----
           本地没命中的歌先拿一个**占位地址**排进播放列表
           （http://127.0.0.1:8080/r/fav?k=<key>），**播到它的时候** player 才回头
           问 device 要真地址（op=fav_resolve，现搜一次并 302 到真地址）。

           ★★ 导入期**绝不**再批量/后台搜索：以前把整份 need 丢给 warmFavSource 一次
           全搜，用户感知就是「导入歌单又在搜索」。现在导入只是排占位地址，
           搜索只在真正播放那首时才按需发生（= 用户要的「播放的时候才临时搜索」）。
           之前的 60~80s 导入卡死、假失败也一并消失。 */
        int ni = 0;                                  // v1.3.220：断点，剩下的交给后台补
        /* ★ v1.3.241：占位条目**不再受 cap 限制** —— 这一句原来是
           「pick 与 got 的条数和小于 cap」，超上限的光杆歌连占位都排不上，
           于是「收藏 N 首、播放列表只有 cap 首」。占位就是一行字符串 + 一次
           Fav.key()，几千条也就一两毫秒，没有任何理由截断。 */
        for (; ni < need.size(); ni++) {
            String[] e = need.get(ni);
            String pk = Fav.key(e[0], e[1]);
            got.add(new String[]{e[0], e[1], PENDING_URL + enc(pk), e[2], e[3], e[0],
                    (e.length > 4 && e[4] != null) ? e[4] : ""});   // [6] = 收藏第几条
            fromPending++;
        }
        if (!realFix.isEmpty()) {
            int rn = Fav.patchBatch(realFix);
            if (rn > 0) {
                SessionLog.i("fav realFix: 记住 " + rn + " 个在线真名字段");
            }
        }
        /* （脏缓存自愈已在本次最开始由 Fav.purgeUnrelated() 全表做过）—— 见上文。 */
        /* ★ v1.3.229：导入期**不**调用 warmFavSource —— 整份歌单的在线搜索一律
           推迟到「真正播到那首」时由 player /r/fav → device fav_resolve 按需触发。
           占位地址已经在上面的循环里排进队列，这里什么都不用做。 */
        synchronized (tracks) {
            for (int i = 0; i < got.size(); i++) {
                String[] g = got.get(i);
                tracks.add(new String[]{g[0], g[1], "", g[2], g[3], g[4], "", "fav"});
                int gf = 999999;              // v1.3.237：收藏序号（解析不到就排最后）
                try {
                    if (g.length > 6 && g[6] != null && g[6].length() > 0) {
                        gf = Integer.parseInt(g[6]);
                    }
                } catch (Throwable ignore) {
                    // ignore
                }
                pickAdd(pick, pickFav, tracks.size() - 1, gf);
            }
            // 上面没捞着的（超列表上限）⇒ 标记没位置，不回库、不搜索（v1.3.230）
            for (int i = 0; i < need.size(); i++) {
                boolean done = false;
                for (int j = 0; j < got.size(); j++) {
                    /* ★ got 的 [0] 现在是**真名**（搜回来的），跟收藏里的原名常常不同
                       ⇒ 去重判据改看末位保留的原名。 */
                    if (got.get(j)[5].equals(need.get(i)[0])) {
                        done = true;
                        break;
                    }
                }
                if (done) {
                    continue;
                }
                missedTitles.add(need.get(i)[0]);   // 真·完全没匹配上（超 cap 没位置）
            }
        }
        /* ★ v1.3.237：push 之前把队列**还原成收藏顺序**（= 导入 txt 的顺序）。
           主循环里本地命中 / 有直链 / 有在线源的歌是边走边入列的，光杆歌（占位）
           要等循环跑完才统一补进 tracks ⇒ 它们整体漂到队列尾部，用户看到的就是
           「导入播放列表后顺序变了」。按收藏序号做一次稳定插入排序即可。 */
        sortPickByFav(pick, pickFav);
        int miss = fav.length() - pick.size();
        String missedTag = "";
        if (!missedTitles.isEmpty()) {
            missedTag = "\n###MISSED###";
            for (String t : missedTitles) { missedTag += "\n" + t; }
        }
        if (pick.isEmpty()) {
            return "ERR 收藏里没有能播的条目（地址都失效了？重新搜一次再收藏）" + missedTag;
        }
        int n = pushSongs("play_fav", pick);
        if (n > 0) {
            pushed = true;
            kickPlay("play_fav", startDelay(n), 2);
            String first = nthTitleOf(pick, n, false, 0);
            SessionLog.i("play_fav: push " + n + " of " + fav.length() + " fav, first=" + first
                    + ", 本地命中 " + fromLocal + ", 在线补源 " + fromOnline
                    + ", 播到再解析 " + fromPending);
            return "OK 收藏列表 " + n + " 首已排进播放列表，正在播第一首"
                    + (first.length() > 0 ? ("《" + first + "》") : "")
                    + (fromLocal > 0 ? ("（其中 " + fromLocal + " 首走本地曲库）") : "")
                    + (fromOnline > 0 ? ("（" + fromOnline + " 首在线补的地址）") : "")
                    + (fromPending > 0 ? ("（" + fromPending + " 首播到时才在线匹配）") : "")
                    + (miss > 0 ? ("（还有 " + miss + " 首没排进来，收藏里歌名不全）") : "")
                    + (fav.length() > n ? ("（共 " + fav.length() + " 首，单次投递上限 "
                        + (BINDER_BUDGET / 1000) + "KB ⇒ 本次加载前 " + n + " 首）") : "")
                    + missedTag;
        }
        return "ERR 播放通道不可用（消息总线发送失败）";
    }

    /**
     * v1.3.239：收藏里那条直链**到底是不是这首**。
     *
     * <p>★ 为什么需要：导入 txt 的地址列（导出备份带过来的）可能是历史上某轮瞎配的产物。
     * 实测收藏《SKINNY / Billie Eilish》的 url 是
     * {@code .../m/Birdy%20-%20Skinny%20Love.mp3} —— 歌名「Skinny Love」包含「skinny」，
     * 于是被当成同一首，播出来是 Birdy 的歌、显示却是 SKINNY。</p>
     *
     * <p>只判 NAS 本地文件（路径含 {@code /m/}）：这类地址的文件名一律是
     * {@code 歌手 - 歌名.mp3}，能直接反查歌手/歌名。云盘直链、{@code /r/} 惰性地址
     * 没有可解析的元数据 ⇒ 一律放行（信息不足不定罪）。</p>
     */
    private static boolean urlMatchesFav(String url, String title, String artist) {
        if (url == null || url.length() == 0 || url.indexOf("/m/") < 0) {
            return true;
        }
        String fn = url;
        int slash = fn.lastIndexOf('/');
        if (slash >= 0) {
            fn = fn.substring(slash + 1);
        }
        int qm = fn.indexOf('?');
        if (qm >= 0) {
            fn = fn.substring(0, qm);
        }
        try {
            fn = java.net.URLDecoder.decode(fn, "UTF-8");
        } catch (Throwable ignore) {
            // 解不开就按原样比
        }
        int dot = fn.lastIndexOf('.');
        if (dot > 0) {
            fn = fn.substring(0, dot);
        }
        int sep = fn.indexOf(" - ");
        if (sep <= 0 || sep + 3 >= fn.length()) {
            return true;                      // 文件名里没「歌手 - 歌名」⇒ 不判
        }
        String fnArtist = fn.substring(0, sep).trim();
        String fnTitle = fn.substring(sep + 3).trim();
        if (fnArtist.length() == 0 || fnTitle.length() == 0) {
            return true;
        }
        if (!Fav.artistSame(artist, fnArtist)) {
            return false;                     // 歌手完全不是一个人 ⇒ 这条地址不是这首
        }
        return Fav.relatedName(title, artist, fnTitle, fnArtist);
    }

    /**
     * v1.3.238：播**收藏里的某一首**（页面点单条，op=play_fav_one）。
     *
     * <p>★ 为什么不能复用 play_song：收藏条目里存着 {@code source+rid}，那是
     * 「用户当初点播的那一版」的精确指针。页面以前只看 {@code it.url}
     * （导入的歌单里多半是空串），于是退回 {@code play_song} 按歌名重搜 ——
     * 搜索结果按热度排，同名歌谁在前面就播谁的版本。
     * 实测：收藏 Billie Eilish 的《hotline bling》(kw/57047050)，点收藏播放
     * 变成 Drake 版（搜「hotline bling」第一名是 kw/6539505）。</p>
     *
     * <p>解析顺序与 {@link #playFav()} 一致：本地精确 → {@code ltitle} 缓存 → 序号 →
     * 直链 → <b>source+rid</b> → 惰性地址。区别只是这里只播一首。</p>
     */
    /**
     * ★ v1.3.306：收藏条目 → 轨道行（收藏列表窗口用，全走缓存、不发 NAS 搜索）。
     *
     * <p>解析优先级与 playFavOne 单条一致：本地命中（非 manual）→ 本地行整行复制；
     * 直链/精确源 rid → 惰性 {@code /r/{src}/{rid}}；节目行没绑源 → 查「我的节目」
     * 缓存占位 {@code /r/prog}（播到才解析）。解析不出 ⇒ null（窗口里跳过这行）。</p>
     */
    private static String[] favCacheRow(JSONObject it, List<String[]> keep, String b) {
        try {
            String title = it.optString("title", "");
            String artist = it.optString("artist", "");
            String rt = it.optString("rtitle", "");
            if (rt.length() == 0) {
                rt = title;
            }
            String ra = it.optString("rartist", "");
            if (ra.length() == 0) {
                ra = artist;
            }
            String album = it.optString("album", "");
            String fkind = it.optString("kind", "");
            String fk = "program".equals(fkind) ? "program"
                    : Fav.detectKind(title, album, artist);
            String dur = it.optString("duration", "");
            String url = it.optString("url", "");
            String src = it.optString("source", "");
            String rid = it.optString("rid", "");
            boolean manual = "1".equals(it.optString("rman", ""))
                    && src.length() > 0 && rid.length() > 0;
            if (!manual && title.length() > 0) {
                /* ★ v1.3.306：变量名用 lpos —— 「int loc = findLocal(...)」是 v1.3.234
                   chkNo 禁用的一刀切写法（playFavOne 主路径专用禁令），此处语义不同
                   （窗口行解析，本就允许本地命中作为候选之一）。 */
                int lpos = findLocal(title, artist);
                if (lpos < 0) {
                    String lt = it.optString("ltitle", "");
                    String la = it.optString("lartist", "");
                    if (lt.length() > 0 && Fav.artistSame(artist, la)) {
                        lpos = findLocal(lt, la);
                    }
                }
                if (lpos >= 0 && lpos < keep.size() && keep.get(lpos) != null) {
                    String[] e = keep.get(lpos);
                    String[] cp = new String[e.length];
                    for (int i = 0; i < e.length; i++) {
                        cp[i] = e[i] == null ? "" : e[i];
                    }
                    return cp;
                }
            }
            String pu = "";
            if (url.length() > 0 && url.indexOf("/r/") < 0
                    && urlMatchesFav(url, title, artist)) {
                pu = url;
            } else if (src.length() > 0 && rid.length() > 0 && b != null) {
                pu = b + "/r/" + src + "/" + rid
                        + "?title=" + enc(rt) + "&artist=" + enc(ra);
            } else if (url.length() > 0 && urlMatchesFav(url, title, artist)) {
                pu = url;
            } else if ("program".equals(fk)) {
                String bk0 = abBookOnly(rt);
                if (bk0.length() == 0) {
                    bk0 = rt;
                }
                int epn = parseEpFromTitle(rt);
                JSONObject pe = epn > 0 ? Prog.findByName(bk0) : null;
                if (pe != null) {
                    String paid = pe.optString("albumId", "").trim();
                    if (paid.length() > 0) {
                        pu = "http://127.0.0.1:8080/r/prog?aid=" + enc(paid) + "&ep=" + epn
                                + "&title=" + enc(rt) + "&artist=" + enc(ra);
                    }
                }
            }
            if (pu.length() == 0) {
                return null;
            }
            String tk = "program".equals(fk) ? "program" : "fav";
            String slotBook;
            if ("program".equals(tk)) {
                String bk0 = abBookOnly(rt);
                slotBook = bk0.length() > 0 ? bk0 : rt;
            } else {
                slotBook = album;
            }
            return new String[]{title, artist, "", pu, slotBook, dur, "", tk};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String playFavOne(JSONObject args) {
        String k = jstr(args, "k");
        JSONObject it = Fav.entry(k);
        if (it == null) {
            /* 兜底：页面若传的是真名 key（rtitle/ltitle），原名 key 查不到也再试一次 */
            String t0 = jstr(args, "title");
            if (t0.length() > 0) {
                it = Fav.entry(Fav.key(t0, jstr(args, "artist")));
            }
        }
        if (it == null) {
            return "ERR 收藏里没有这一条（可能刚被删了，刷新一下）";
        }
        if (!idxLoaded) {
            loadIndex();
        }
        String title = it.optString("title", "");
        String artist = it.optString("artist", "");
        String fkind = it.optString("kind", "");
        String rt = it.optString("rtitle", "");
        if (rt.length() == 0) {
            rt = title;
        }
        String ra = it.optString("rartist", "");
        if (ra.length() == 0) {
            ra = artist;
        }
        String album = it.optString("album", "");
        /* ★ v1.3.299：收藏条目可能没存 kind（旧 txt 备份 / 网页收藏没带 kind），
           仿 Fav.buildEntry 的口径现场补一次识别，避免「书」被当成普通歌推。 */
        String fk = "program".equals(fkind) ? "program" : Fav.detectKind(title, album, artist);
        String dur = it.optString("duration", "");
        String url = it.optString("url", "");
        String src = it.optString("source", "");
        String rid = it.optString("rid", "");
        String b = nasBase();

        /* ★ v1.3.243：手动重链接过（rman=1）的条目**不再让本地库抢** —— 这一版是用户
           在搜索结果里亲手挑的；本地同名文件可能是翻唱 / 另一个录音室版本，
           让他「点了还对不上」正是这个功能要解决的问题。 */
        boolean manual = "1".equals(it.optString("rman", ""))
                && src.length() > 0 && rid.length() > 0;
        List<String[]> keep = localTracksSnapshot();
        // ① 本地精确（歌手闸门在 findLocal 里）
        int loc = (!manual && title.length() > 0) ? findLocal(title, artist) : -1;
        // ①b ltitle 缓存 —— ★ v1.3.238：缓存歌手与原名歌手对不上就不认（脏缓存）
        if (!manual && loc < 0) {
            String lt = it.optString("ltitle", "");
            String la = it.optString("lartist", "");
            if (lt.length() > 0 && Fav.artistSame(artist, la)) {
                loc = findLocal(lt, la);
            }
        }
        // ② 收藏时记下的库内序号
        if (!manual && loc < 0) {
            int ix = it.optInt("index", 0);
            if (ix > 0 && ix <= keep.size()) {
                String[] e = keep.get(ix - 1);
                if (e != null && title.equals(String.valueOf(e[0]))) {
                    loc = ix - 1;
                }
            }
        }

        /* ★ v1.3.302：书名/集号提升到方法级 —— else 分支的 slot[4] 与 synchronized 外面的
           「钉 loader」块都要用；原来声明在 else 块里，出了块就不可见（javac 找不到符号）。 */
        String t0b = rt.length() > 0 ? rt : title;
        String bk0 = abBookOnly(t0b);
        if (bk0.length() == 0) {
            bk0 = t0b;
        }
        int epNo0 = parseEpFromTitle(t0b);
        List<Integer> one = new ArrayList<Integer>();
        String shown = title;
        String via = "";    // ★ v1.3.306：显式初始化（javac 定赋值分析在 sync 块两分支+ERR 早退结构上误报）
        synchronized (tracks) {
            int at;
            if (loc >= 0 && loc < keep.size()) {
                /* ★ v1.3.306：本地命中也走「收藏列表窗口」——收藏栏本地/在线混合，
                   条数取本地数/在线数里大的（见下方 budget）；点击这条排队首直接起播。 */
                tracks.clear();
                String[] e0 = keep.get(loc);
                if (e0 != null) {
                    String[] row0 = new String[e0.length];
                    for (int i = 0; i < e0.length; i++) {
                        row0[i] = e0[i] == null ? "" : e0[i];
                    }
                    tracks.add(row0);
                    if (e0[0] != null) {
                        shown = e0[0];
                    }
                }
                at = 0;
                via = "local#" + loc;
            } else {
                String pu = "";
                if (url.length() > 0 && url.indexOf("/r/") < 0
                        && urlMatchesFav(url, title, artist)) {
                    pu = url;                                       // ③ 直链
                    via = "url";
                } else if (src.length() > 0 && rid.length() > 0 && b != null) {
                    /* ④ 精确源 —— 这才是收藏真正的价值：同一个 rid 就是同一版，
                       不按歌名重搜，也就不会被热度排序换成本人/别人的版本。 */
                    pu = b + "/r/" + src + "/" + rid
                            + "?title=" + enc(rt) + "&artist=" + enc(ra);
                    via = "src=" + src + "/" + rid;
                } else if (url.length() > 0 && urlMatchesFav(url, title, artist)) {
                    pu = url;                                       // ⑤ 惰性地址
                    via = "lazy";
                } else if ("program".equals(fk) && epNo0 > 0) {
                    /* ★ v1.3.306：节目行没绑源 ⇒ 查「我的节目」缓存占位
                       （播到才解析，不发 NAS 搜索）。 */
                    JSONObject pe = Prog.findByName(bk0);
                    if (pe != null) {
                        String paid = pe.optString("albumId", "").trim();
                        if (paid.length() > 0) {
                            pu = "http://127.0.0.1:8080/r/prog?aid=" + enc(paid)
                                    + "&ep=" + epNo0
                                    + "&title=" + enc(rt) + "&artist=" + enc(ra);
                            via = "prog=" + paid + "#" + epNo0;
                        }
                    }
                    if (pu.length() == 0) {
                        via = "none";
                    }
                } else {
                    via = "none";
                }
                /* ★ 1.3.335：节目占位之外，普通歌五路全空不再直接 ERR ——
                   先问服务端歌曲 rid 缓存（v1.7.11 /api/song/resolve）：
                   搜过秒回；没搜过服务端单次搜 + 落缓存，拿到就回写 fav.json，
                   下次直接走 ④ 精确源。与有声书缺集同款「按需单条」口径。 */
                if (pu.length() == 0 && !"program".equals(fk)) {
                    JSONObject sc = songResolveSrv(rt.length() > 0 ? rt : title,
                            ra.length() > 0 ? ra : artist);
                    String srid = (sc == null) ? "" : sc.optString("rid", "").trim();
                    String ssrc = (sc == null) ? "" : sc.optString("source", "").trim();
                    if (srid.length() > 0 && ssrc.length() > 0 && b != null) {
                        List<String[]> srows = new ArrayList<String[]>();
                        srows.add(new String[]{k, "source", ssrc});
                        srows.add(new String[]{k, "rid", srid});
                        String stt = sc.optString("title", "").trim();
                        String saa = sc.optString("artist", "").trim();
                        if (stt.length() > 0 && !stt.equals(title)) {
                            srows.add(new String[]{k, "rtitle", stt});
                        }
                        if (saa.length() > 0 && !saa.equals(artist)) {
                            srows.add(new String[]{k, "rartist", saa});
                        }
                        Fav.patchBatch(srows);
                        pu = b + "/r/" + ssrc + "/" + srid
                                + "?title=" + enc(rt.length() > 0 ? rt
                                        : (stt.length() > 0 ? stt : title))
                                + "&artist=" + enc(ra.length() > 0 ? ra
                                        : (saa.length() > 0 ? saa : artist));
                        via = "srv=" + ssrc + "/" + srid;
                    }
                }
                if (pu.length() == 0) {
                    return "ERR 《" + title + "》还没配上源 —— 先在搜索结果里点 ▶ 播放，"
                            + "确认是对的版本后回收藏列表点这条右边的 🔗 绑过来";
                }
                tracks.clear();
                /* ★ v1.3.299：收藏里的「书」(kind=program) 必须按有声书会话推，
                   否则 player 播完这一集后回退续播的目标会落到上一次自持的「我的节目」
                   （遮天）—— 表现就是「点凡人修仙传几秒后跳回遮天」。这里仿 abQueueBook：
                   轨道标 program + 写 ab_book/ab_ep，把续播/下一集锁回这本书本身。 */
                String tk = "program".equals(fk) ? "program" : "fav";
                /* ★ v1.3.302：program track 的 slot[4] 记书名（不是 albumId），
                   使 liveNowRow 解析出的书名与 ab_loader_book 对得上 ⇒ 下一集快路径命中本书。 */
                String slotBook = tk.equals("program") ? bk0 : album;
                tracks.add(new String[]{title, artist, "", pu, slotBook, dur, "", tk});
                at = 0;
            }
            /* ★ v1.3.306：收藏列表窗口（本地/在线两个分支统一）—— 点击这条之后的
               条目一起入列，全走缓存（rid 惰性 / 本地命中 / 节目占位），不发 NAS
               搜索；解析不出的行跳过不挡窗口。
               条数：本地数(0=不限制) 与在线数**取大的** —— 收藏栏本地/在线混合；
               本地数设 0（本地不设限）⇒ 点一首就把整个收藏拉进播放队列。
               点击项不在列表里（兜底路径）⇒ 保持原单曲行为。 */
            int lm = maxPlaylist();
            int budget = (lm == 0) ? Integer.MAX_VALUE : Math.max(lm, onlineMax());
            String kk = it.optString("k", "");
            if (kk.length() > 0) {
                JSONArray fa = Fav.list();
                int start = -1;
                for (int i = 0; i < fa.length(); i++) {
                    JSONObject x = fa.optJSONObject(i);
                    if (x != null && kk.equals(x.optString("k", ""))) {
                        start = i;
                        break;
                    }
                }
                for (int i = start + 1; start >= 0 && i < fa.length()
                        && tracks.size() < budget; i++) {
                    JSONObject x = fa.optJSONObject(i);
                    if (x == null) {
                        continue;
                    }
                    String[] row = favCacheRow(x, keep, b);
                    if (row != null) {
                        tracks.add(row);
                    }
                }
            }
            /* ★ v1.3.306：点击项永远在队首，其后所有新入列行全部进 pick
               （pushSongs 的 startIndex=0 从队首起播 = 正是点击的这条）。 */
            for (int i = at; i < tracks.size(); i++) {
                one.add(Integer.valueOf(i));
            }
        }
        /* ★ v1.3.302：书类收藏播放前，先清旧有声书会话，再把 loader/book 状态钉死到本书。
           1.3.300 只清 loader 没写回本书 ⇒ fallback 到 ab_book 后，abNext 在 bookOk 不匹配时
           走 NAS 重搜可能落到别的书（跳回之前播的书下一集）。这里直接 abLoaderSave 把
           ab_loader_* 也钉到本书，使下一集/上一集/续播都锁这本书本身。 */
        if ("program".equals(fk)) {
            abSpPut("ab_loader_book", "");
            abSpPut("ab_loader_album", "");
            abSpPut("ab_loader_src", "");
            abSpPut("ab_loader_page", "");
            abSpPut("ab_loader_size", "");
            abSpPut("ab_loader_total", "");
            abSpPut("ab_loader_pstart", "");
            abSpPut("ab_loader_pend", "");
            abSpPut("ab_book", "");
            abSpPut("ab_ep", "");
            String al = album.length() > 0 ? album : bk0;
            String sc = src.length() > 0 ? src : "kw";
            int e0 = epNo0 > 0 ? epNo0 : 1;
            abLoaderSave(bk0, al, sc, 1, abLoaderSize(), 0, e0, e0);
            abSpPut("ab_book", bk0);
            abSpPut("ab_ep", String.valueOf(e0));
        }
        int n = pushSongs("play_fav_one", one);
        if (n > 0) {
            pushed = true;
            kickPlay("play_fav_one", startDelay(n), 2);
            SessionLog.i("play_fav_one: " + title + " | " + artist + " | " + via
                    + " -> 《" + shown + "》");
            return "OK 播放收藏《" + shown + "》" + (ra.length() > 0 ? ("－" + ra) : "");
        }
        return "ERR 播放通道不可用（消息总线发送失败）";
    }

    public static String dispatch(String op, JSONObject args) {
        try {
            // v1.3.130：先把「用户到底说了什么」告诉 MusicResume，它据此决定要不要
            // 在会话结束时把唤醒期间暂停的音乐接回去（见 MusicResume 类注释）。
            // v1.3.132：顺手维护 softPlaying 软状态（TTS 互斥的让路判据之一）。
            if ("pause".equals(op) || "stop".equals(op)) {
                ProgProgress.srvFinal(op);   // ★ v1.3.340：结算上报（不等 15s 周期）
                MusicResume.noteUserPause();
                noteStopped();          // v1.3.136：落盘（重启后不该再以为在播）
            } else if ("play".equals(op) || "next".equals(op) || "prev".equals(op)
                    || "random".equals(op) || "play_song".equals(op)
                    || "play_artist".equals(op) || "play_index".equals(op)
                    || "play_at".equals(op) || "replay".equals(op)
                    || "play_radio".equals(op) || "play_radio_all".equals(op)
                    // v1.3.178：在线随机 / 在线随机池点播 —— 同样是「用户要放歌」
                    || "random_online".equals(op) || "play_rand_at".equals(op)
                    || "play_url".equals(op) || "queue_all".equals(op)
                    // v1.3.200：播收藏列表同样是「用户要放歌」
                    || "play_fav".equals(op)) {
                MusicResume.noteUserPlay();
                // v1.3.136：落盘（曲名由 pushPlaylist 填，这里沿用旧值）
                notePlaying(null);
            }
            // v1.3.88：控制指令优先走原厂消息总线（player 自己的播放引擎，不受 DLNA
            // 状态机限制 —— DLNA 在 STATUS_BUSY 时会给 SetAVTransportURI 回 501）。
            if ("play".equals(op)) {
                // v1.3.94 兜底：模型经常把「播放音乐」理解成 play，但 player 里还没歌单时
                // subType=3 是空转。这时直接当 random 处理（先推 100 首再播）。
                if (!pushed) {
                    if (!idxLoaded) {
                        loadIndex();
                    }
                    int n = pushSongs("play->random", shuffledIdx());
                    if (n > 0) {
                        pushed = true;
                        kickPlay("play->random", startDelay(n), 2);
                        SessionLog.i("play with empty playlist -> random " + n + " tracks");
                        return "OK 开始播放（随机 " + n + " 首，库里共 " + tracks.size() + " 首）";
                    }
                }
                if (msg(3, null)) {
                    pushed = true;
                    return "OK 继续播放";
                }
                avt("Play", "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>");
                return "OK 继续播放";
            }
            if ("pause".equals(op)) {
                if (msg(2, null)) {
                    return "OK 已暂停";
                }
                avt("Pause", "<u:Pause xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID></u:Pause>");
                return "OK 已暂停";
            }
            if ("stop".equals(op)) {
                if (msg(4, null)) {
                    return "OK 已停止";
                }
                avt("Stop", "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID></u:Stop>");
                return "OK 已停止";
            }
            if ("next".equals(op)) {
                /* ★ v1.3.281：正在播的是有声书/节目时，「下一曲」就是「下一集」——
                   队列内还有下一首就总线切（快）；到队尾或总线失败就转 abNext 续拉
                   下一批，播放列表最后一集说「播下一集」也能接上（不再播不了）。 */
                String[] nr = liveNowRow();
                if (nr != null) {
                    JSONObject lq = playerLiveQueue(0);
                    int ltotal = lq == null ? 0 : lq.optInt("total", 0);
                    int lcur = lq == null ? -1 : lq.optInt("cur", -1);
                    boolean atEnd = (lcur + 1 >= ltotal);
                    if (!atEnd && msg(6, null)) {
                        kickPlay("next", 1200, 2);
                        return "OK 下一集";
                    }
                    Log.i(TAG, "next->abNext (live ep=" + nr[1] + " atEnd=" + atEnd + ")");
                    return abNext();
                }
                if (msg(6, null)) {
                    // v1.3.94：魔改 player 切歌后同样只 prepare 不 start，补一脚 play
                    kickPlay("next", 1200, 2);
                    return "OK 下一曲";
                }
                avt("Next", "<u:Next xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID></u:Next>");
                return "OK 下一曲";
            }
            if ("prev".equals(op)) {
                if (msg(5, null)) {
                    kickPlay("prev", 1200, 2);
                    return "OK 上一曲";
                }
                avt("Previous", "<u:Previous xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID></u:Previous>");
                return "OK 上一曲";
            }
            /* ★ v1.3.358：进度 / 时长 / 跳转（语音 seek_play，对齐 stem 的 self.stem.seek_play）。
               只查进度与「快进/后退/跳到第 N 秒」都走这里；真拖由 player 执行，
               可行性也由它判（本地播放态才拖得动，电台直播流不行）。 */
            if ("progress".equals(op)) {
                return progressText();
            }
            if ("seek_play".equals(op) || "seek".equals(op)) {
                return seekPlay(args);
            }
            if ("status".equals(op)) {
                // v1.3.136：status 是模型判断「到底有没有在播」的唯一依据 ——
                // 以前只回 DLNA 状态，而 DLNA 对 player 自己的播放引擎未必同步，
                // 于是「小智启动前就在放歌」时它只能答「播放列表为空」。
                // 现在两条都给：DLNA 真状态 + 本地记录（重启后仍存活）。
                String st = "";
                try {
                    String s = avt("GetTransportInfo",
                            "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                                    + "<InstanceID>0</InstanceID></u:GetTransportInfo>");
                    st = tag(s, "CurrentTransportState");
                    dlnaState = st;
                } catch (Throwable t) {
                    st = "(查询失败)";
                }
                String pos = "";
                String meta = "";
                try {
                    String p = avt("GetPositionInfo",
                            "<u:GetPositionInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                                    + "<InstanceID>0</InstanceID></u:GetPositionInfo>");
                    pos = " 第" + tag(p, "Track") + "首 时长=" + tag(p, "TrackDuration")
                            + " 位置=" + tag(p, "RelTime");
                    meta = didlTitle(tag(p, "TrackMetaData"));
                    if (meta.length() > 0) {
                        curTitle = meta;      // DLNA 给的曲名更真，覆盖记录
                        savePlayState(softPlaying);
                    }
                } catch (Throwable t) {
                    // 忽略：状态为主
                }
                StringBuilder sb = new StringBuilder("OK DLNA状态=");
                sb.append(st).append(pos);
                if (meta.length() > 0) {
                    sb.append(" DLNA曲目=").append(meta);
                }
                sb.append(" | 本地记录=").append(softPlaying ? "在播" : "未在播");
                final int markT = sb.length();
                if (curTitle.length() > 0) {
                    sb.append("《").append(curTitle).append("》");
                }
                /* ★ v1.3.268：把「正在播的到底是谁」报全 —— 总线事件只给曲名（有声书
                   给的是集标题），光合积木这类版本**书名不在集标题里**，模型只能看到
                   「第N集」不知道播的是什么书、更不知道主播。当前行 = curIdx →
                   lastIdx → tracks，行里 slot[4]=书名、slot[1]=主播、slot[7]=program。 */
                try {
                    int ti = -1;
                    synchronized (lastIdx) {
                        int ci = curIdx;
                        if (!lastIsRadio && ci >= 0 && ci < lastIdx.size()) {
                            ti = lastIdx.get(ci).intValue();
                        }
                    }
                    String[] row = null;
                    if (ti >= 0) {
                        synchronized (tracks) {
                            if (ti < tracks.size()) {
                                row = tracks.get(ti);
                            }
                        }
                    }
                    /* ★ v1.3.277：镜像可能是老列表（网页收藏栏起播的歌单 device 全然
                       不知）。player 真队列的当前行和镜像对不上时改报 player 的当前行
                       —— 不然小智嘴里是老队列那一首（「第一首是 TikTok，正在播 TikTok」）。 */
                    String lTitle = "";
                    String lArtist = "";
                    JSONObject liveQ = playerLiveQueue(0);
                    if (liveQ != null) {
                        JSONArray ll = liveQ.optJSONArray("list");
                        int lc = liveQ.optInt("cur", -1);
                        if (ll != null && lc >= 0) {
                            for (int k = 0; k < ll.length(); k++) {
                                JSONObject e = ll.optJSONObject(k);
                                if (e != null && e.optInt("n", 0) == lc + 1) {
                                    lTitle = e.optString("title", "");
                                    lArtist = e.optString("artist", "");
                                    break;
                                }
                            }
                        }
                    }
                    boolean mirrorStale = lTitle.length() > 0
                            && (row == null || !titleLooseEq(row[0], lTitle));
                    if (mirrorStale) {
                        /* ★ v1.3.278：镜像过期时不再「双字段打架」（旧《curTitle》+
                           追加注解 —— 工具描述教模型只信本地记录，照样报旧歌）。
                           直接摘掉刚 append 的旧曲名、报 player 实值，并自愈
                           curTitle 落盘（savePlayState 会刷新 playStateTs）。 */
                        sb.setLength(markT);
                        curTitle = lTitle;
                        savePlayState(softPlaying);
                        if (lTitle.startsWith("《")) {
                            sb.append(lTitle);
                        } else {
                            sb.append("《").append(lTitle).append("》");
                        }
                        if (lArtist.length() > 0) {
                            sb.append("（").append(lArtist).append("）");
                        }
                    } else if (row != null && row.length >= 8 && "program".equals(row[7])) {
                        sb.append("，这是有声书");
                        if (row[4] != null && row[4].length() > 0) {
                            sb.append("《").append(row[4]).append("》");
                        }
                        String nar = (row[1] == null) ? "" : row[1];
                        if (nar.length() > 0) {
                            sb.append("（主播").append(nar).append("）");
                        }
                        sb.append("，正在播这一集：")
                          .append(curTitle.length() > 0 ? curTitle
                                  : (row[0] == null ? "" : row[0]));
                    } else if (row != null && row.length >= 2 && row[1] != null
                            && row[1].length() > 0) {
                        sb.append("，歌手是").append(row[1]);
                    }
                } catch (Throwable t21) {
                    /* 报书名/歌手是尽力而为，别影响状态主输出 */
                }
                if (playStateTs > 0) {
                    sb.append("(").append(
                            (System.currentTimeMillis() - playStateTs) / 1000L).append("秒前)");
                }
                /* ★ v1.3.358：把「已播到 / 共多长 / 还剩」一并答出来 —— 用户问
                   「这集多长」「还剩多久」「放了多久」时模型不用再猜（以前只有曲名）。
                   数据来自 player 的 music.durMs（播放器自己算的真值）。 */
                try {
                    String pl = progLine(playerMusic());
                    if (pl.length() > 0) {
                        sb.append(" | 进度：").append(pl);
                    }
                } catch (Throwable t34) {
                    /* 进度是增量信息，取不到就不报，别影响状态主输出 */
                }
                sb.append(" [dlna=").append(avtUrl).append("]");
                return sb.toString();
            }
            if ("volume".equals(op)) {
                // v1.3.100：参数兼容 level / value 两种写法（web 控制台曾用 value，
                // 与这里的 level 不匹配导致「点了没反应」，历史上坑过一次）。
                int lv = -1;
                if (args != null) {
                    lv = args.optInt("level", -1);
                    if (lv < 0) {
                        lv = args.optInt("value", -1);
                    }
                }
                if (lv < 0 || lv > 100) {
                    return "ERR level 需要 0~100";
                }
                // v1.3.100：优先直接改系统 STREAM_MUSIC 音量。player 播的就是这条流，
                // 一条 API 立刻生效，不依赖 DLNA 状态机（DLNA 忙时会给 501 且
                // 只影响它的音量，与系统流还不同源）。
                String err = setSystemVolume(lv);
                if (err == null) {
                    lastVolume = lv;
                    return "OK 音量设为 " + lv + "（系统音乐流）";
                }
                SessionLog.i("volume sys fail: " + err + " -> fallback dlna");
                rcs("SetVolume", "<u:SetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">"
                        + "<InstanceID>0</InstanceID><Channel>Master</Channel>"
                        + "<DesiredVolume>" + lv + "</DesiredVolume></u:SetVolume>");
                lastVolume = lv;
                return "OK 音量设为 " + lv + "（DLNA 兜底）";
            }
            if ("random".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                // v1.3.114：支持 count（「随机放 5 首」）。之前 random 没有数量参数，
                // 模型想放 5 首只能调 random 拿全库，再用 4 次 next 去「凑数」——
                // 那样既不限制列表长度，又会把正在播的歌切掉。
                int cnt = args == null ? 0 : args.optInt("count", 0);
                int n = pushSongs("random", randomIdx(cnt));
                if (n > 0) {
                    pushed = true;
                    kickPlay("random", startDelay(n), 2);
                    return "OK 随机播放 " + n + " 首"
                            + (cnt > 0
                                    ? ("（从库里 " + tracks.size() + " 首中随机挑 " + n + " 首）")
                                    : ("（整个曲库共 " + tracks.size() + " 首）"));
                }
                return "ERR 随机播放失败，播放通道不可用";
            }
            // v1.3.178：「随机播放在线音乐」——**每次**都从 NAS 现拉一批新的随机曲目。
            //
            // 与 random 的分工：
            //   random         = 播**本地曲库快照**（启动时从 index.json 拉的那批，不换）
            //   random_online  = 让 NAS 重洗一批牌，再从中随机抽 N 首 ⇒ 每次都不一样
            //
            // 拉不到（NAS 不在 / 没配音乐源 / 上游全挂）时原样退回本地 random ——
            // 宁可播旧的，也不能让用户在音箱前等一场空。
            if ("random_online".equals(op)) {
                int cnt = args == null ? 0 : args.optInt("count", 0);
                if (cnt <= 0) {
                    // v1.3.180：默认条数也统一到「在线曲目上限」（以前写死 100）
                    cnt = onlineMax();
                }
                if (cnt > maxPlaylist()) {
                    cnt = maxPlaylist();
                }
                if (!idxLoaded) {
                    loadIndex();
                }
                List<String[]> pool = refreshRandPool(cnt);
                if (pool != null && pool.size() > 0) {
                    // v1.3.179 ★：本地曲库段先放回去，再接在线新批。
                    //   合并前它们是两行 music_index，整表替换伤不到本地那份；
                    //   现在挤在同一份 JSON 里，不先留着就永久没了。
                    List<String[]> keep = localTracksSnapshot();
                    synchronized (tracks) {
                        tracks.clear();
                        tracks.addAll(keep);
                        tracks.addAll(pool);
                        onlineAppends = 0;
                    }
                    // 只推在线段（keep.size() 之后）—— 用户要的是「播放在线音乐」
                    int n = pushSongs("random_online", shuffledIdx(keep.size()));
                    if (n > 0) {
                        pushed = true;
                        kickPlay("random_online", startDelay(n), 2);
                        return "OK 在线随机播放 " + n
                                + " 首（刚从 NAS 换的新一批；再说一次「随机播放在线音乐」"
                                + "又会换一批）";
                    }
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                int n2 = pushSongs("random_online->local", randomIdx(cnt));
                if (n2 > 0) {
                    pushed = true;
                    kickPlay("random_online->local", startDelay(n2), 2);
                    return "OK 在线曲库暂时拉不到，改播本地曲库 " + n2
                            + " 首（共 " + tracks.size() + " 首）";
                }
                return "ERR 随机播放失败，播放通道不可用";
            }
            // v1.3.178：在线随机池里点某一首 —— 播它，**同时把这批 100 首全排进播放列表**。
            //
            // 为什么能「一键整批进队列」：这批曲目的 url 是 mini-lx 的 /s/<i> 惰性地址，
            // 不需要为每一首单独取链（那是慢的地方），整批推一次就行。
            if ("play_rand_at".equals(op)) {
                int i = args == null ? -1 : args.optInt("i", -1);
                List<String[]> pool = randPoolSnapshot();
                if (i < 0 || i >= pool.size()) {
                    return "ERR 在线随机列表已失效，请重新点一次「在线搜歌」";
                }
                // v1.3.179：同样先留本地段（理由同 random_online）
                List<String[]> keep = localTracksSnapshot();
                synchronized (tracks) {
                    tracks.clear();
                    tracks.addAll(keep);
                    tracks.addAll(pool);
                    onlineAppends = 0;
                }
                int hit = keep.size() + i;
                int n = pushSongs("play_rand_at", hitFirstIdx(hit, keep.size()));
                if (n > 0) {
                    pushed = true;
                    kickPlay("play_rand_at", startDelay(n), 2);
                    String[] e = pool.get(i);
                    String who = (e[1].length() > 0) ? (e[0] + " - " + e[1]) : e[0];
                    return "OK 正在播放 " + who + "（在线随机）—— 这 " + n
                            + " 首已全部排进播放列表，播完自动续";
                }
                return "ERR 播放通道不可用（消息总线发送失败）";
            }
            /* v1.3.198：「在线搜索结果 → 全部载入播放列表」。
               以前在线那段只能一首首点（点一条只播一条，列表根本不动），
               而点一条走的是 op=play_online —— 要**现取直链**，几十首逐首取会卡死。

               ★ 这里跟 play_rand_at 走同一条思路：**推惰性地址**
                 （/r/<source>/<rid>?title=..&artist=..），一次 HTTP 都不用为取链跑 ——
                 player 真开口要这一首时，才由 NAS 去取（那时只慢它自己，不拖整批）。
                 第一首例外：正式取一次链，保证点下去马上出声，取不到再退惰性。

               参数 items = JSON 数组：[{title,artist,source,rid,album,duration}, ...] */
            if ("queue_online".equals(op)) {
                JSONArray items = (args == null) ? null : args.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    return "ERR 没有可载入的在线结果";
                }
                String b = nasBase();
                if (b == null) {
                    return "ERR 音乐源没配（在线歌拿不到地址）";
                }
                int cap = maxPlaylist();
                // 与 play_rand_at 同构：本地段原样保留，在线段整批接在后面
                List<String[]> keep = localTracksSnapshot();
                List<Integer> pick = new ArrayList<Integer>();
                synchronized (tracks) {
                    tracks.clear();
                    tracks.addAll(keep);
                    for (int i = 0; i < items.length() && pick.size() < cap; i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it == null) {
                            continue;
                        }
                        String title = it.optString("title", "");
                        String artist = it.optString("artist", "");
                        String source = it.optString("source", "");
                        String rid = it.optString("rid", "");
                        if (source.length() == 0 || rid.length() == 0) {
                            continue;
                        }
                        String url = "";
                        if (pick.size() == 0) {
                            // 第一首：正式取链，取不到再退惰性（马上要出声的那首）
                            url = onlineUrl(title, artist, source, rid, 8000);
                        }
                        if (url.length() == 0) {
                            url = b + "/r/" + source + "/" + rid
                                    + "?title=" + enc(title) + "&artist=" + enc(artist);
                        }
                        tracks.add(new String[]{title, artist, "", url,
                                it.optString("album", ""), it.optString("duration", ""),
                                "", "online"});
                        pick.add(Integer.valueOf(tracks.size() - 1));
                    }
                }
                if (pick.size() == 0) {
                    return "ERR 这些在线条目都拿不到播放地址";
                }
                int n = pushSongs("queue_online", pick);
                if (n > 0) {
                    pushed = true;
                    kickPlay("queue_online", startDelay(n), 2);
                    String[] e0 = tracks.get(pick.get(0).intValue());
                    SessionLog.i("queue_online: push " + n + " of " + items.length()
                            + " online items, first=" + e0[0]);
                    return "OK 已把 " + n + " 首在线歌排进播放列表，正在播第一首"
                            + (items.length() > n
                                ? ("（超出播放列表上限，只取了前 " + n + " 首）") : "")
                            + "　※ 除第一首外都是惰性地址，播到哪首才取哪首的链";
                }
                return "ERR 播放通道不可用（消息总线发送失败）";
            }
            /* v1.3.200：收藏列表 —— 播收藏 / 收藏当前歌 / 取消收藏。
               web 的 op=fav_* 由 WebConsole 直接调 Fav（它还要顺手补参数），
               这里这一组是给**语音 MCP** 用的（self.r1player.*）。 */
            if ("play_fav".equals(op)) {
                return playFav();
            }
            /* v1.3.238：播收藏里的**某一首** —— 保留 source+rid 精确源，
               不再退回 play_song 按歌名重搜（那会把 Billie Eilish 版换成 Drake 版）。 */
            if ("play_fav_one".equals(op)) {
                return playFavOne(args);
            }
            if ("fav_add".equals(op)) {
                return favAdd(args);
            }
            if ("fav_rematch".equals(op)) {
                return favRematch();
            }
            if ("fav_bind".equals(op)) {
                return favBind(args);
            }
            /* v1.3.243：手动重链接（长按歌名 → 在线挑一版）。★ 新 op 必须双登记
               （这里 + WebConsole 的 /api/act 路由表）。 */
            if ("fav_link".equals(op)) {
                return favLink(args);
            }
            if ("fav_resolve".equals(op)) {
                return favResolve(args);
            }
            if ("fav_del".equals(op)) {
                return favDel(args);
            }
            if ("play_url".equals(op)) {
                String url = args == null ? "" : args.optString("url", "").trim();
                if (url.length() == 0) {
                    return "ERR 缺少 url";
                }
                return playUrl(url, args);
            }
            /* v1.3.248：播**本地缓存文件**（闹钟铃声缓存，/sdcard/R1Alarm）。
               ★ 不能复用 play_url：它开头要 probeUrl() 做一次 HTTP HEAD，
                 而 probeUrl 硬转 HttpURLConnection ⇒ file:// 直接抛
                 ClassCastException，本地文件永远过不去。这里只校验文件存在。 */
            if ("play_local".equals(op)) {
                return playLocal(args);
            }
            /* v1.3.262：有声书四件套 —— 数据=NAS MediaServer /api/ab/*（只读），
               起播走 playUrl() 同一条推动链，进度存本地 SP。
               ★ 新 op 必须双登记（这里 + WebConsole /api/act）。 */
            if ("ab_search".equals(op)) {
                return abSearch(args);
            }
            if ("ab_play".equals(op)) {
                return abPlay(args);
            }
            if ("ab_next".equals(op)) {
                return abNext();
            }
            if ("ab_prev".equals(op)) {
                return abPrev();
            }
            if ("ab_resume".equals(op)) {
                return abResume(args);
            }
            // v1.3.117：电台专用入口。之前只有 play_song 里顺带做电台匹配，
            // 模型看不到「有电台这回事」，就把电台名塞进 play_url（实测传过
            // 「先锋898」和编造的「http://fm91.8」）⇒ 播不出声还回 OK。
            if ("play_radio".equals(op)) {
                String q = args == null ? "" : args.optString("name", "").trim();
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("title", "").trim();
                }
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("q", "").trim();
                }
                if (!idxLoaded) {
                    loadIndex();
                }
                // v1.3.120：「第 N 个电台」—— 序号可以走 index 参数，也可以直接写进 name。
                // 命中序号就**只播那一个台**（用户要的是那一个，不是整张列表）。
                int ord = args == null ? 0 : args.optInt("index", 0);
                if (ord <= 0) {
                    ord = radioOrdinal(q);
                }
                if (ord > 0) {
                    if (ord > radios.size()) {
                        return "ERR 一共只有 " + radios.size() + " 个电台，没有第 "
                                + ord + " 个。"
                                + (radios.size() > 0 ? ("可用：" + radioNames(15)) : "");
                    }
                    return playRadio(radios.get(ord - 1), "play_radio#" + ord, false);
                }
                // v1.3.119：「所有电台 / 全部电台」这种泛词直接整表推送，
                // 不再落到 matchRadio 去匹配一个根本不存在的台名。
                if (isAllRadio(q)) {
                    return playAllRadios("play_radio->all", -1);
                }
                String[] rr = matchRadio(q);
                if (rr == null) {
                    return "ERR 没找到电台「" + q + "」，要说完整台名。"
                            + "想一次把所有电台排进列表请用 play_radio_all。"
                            + (radios.size() > 0
                                    ? ("可用电台：" + radioNames(15))
                                    : "（电台列表是空的，在 NAS 的 radios.json 里加）");
                }
                // 控制台调试用：?op=radio_dlna 强制走 DLNA 通道对比
                boolean viaDlna = args != null
                        && "dlna".equals(args.optString("via", ""));
                return playRadio(rr, "play_radio", viaDlna);
            }
            // v1.3.119：一次性把所有电台排进播放列表（可选 name = 从哪个台开始）。
            if ("play_radio_all".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                String q = args == null ? "" : args.optString("name", "").trim();
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("title", "").trim();
                }
                int start = -1;
                // v1.3.120：序号（index 参数，或 name 写成「第3个」）⇒ 从那个台开始
                int ord = args == null ? 0 : args.optInt("index", 0);
                if (ord <= 0) {
                    ord = radioOrdinal(q);
                }
                if (ord > 0) {
                    start = Math.min(ord, radios.size()) - 1;
                } else if (q.length() > 0 && !isAllRadio(q)) {
                    String[] rr = matchRadio(q);
                    if (rr != null) {
                        start = radioIndexOf(rr);
                    }
                }
                return playAllRadios("play_radio_all", start);
            }
            // v1.3.119：手动设播放模式（调试用，?op=mode&v=N）
            // v1.3.172：真值改成 1=随机 2=顺序 3=单曲循环（0 原厂不收，setPlayMode 内部会校正成 2）
            if ("set_mode".equals(op)) {
                int v = args == null ? 0 : args.optInt("mode", 0);
                boolean ok = setPlayMode(v);
                return ok ? ("OK 播放模式设为 " + lastMode + "（1=随机 2=顺序 3=单曲循环）")
                        : "ERR 设置失败（消息总线发送失败）";
            }

            // v1.3.172：重播「上次那一首」。
            //
            // 背景：player 只有「列表 + index」模型，列表是 player 进程的**内存态** ——
            // player 一重启（装机 / 崩溃 / 断电）列表就没了；而 curTitle 是我们**落盘**的
            // （K_TITLE，跨进程重启存活），于是控制台顶栏还挂着上次的曲名，点 ▶ 却毫无反应
            // （空列表没有可播对象）。以前只能「要么不显示，要么让它真能播」二选一，
            // 现在选后者：用落盘的曲名回库里反查，找到就整段推回 player 并开播。
            //
            // 找不到 / 压根没记录 ⇒ 退化成 random（保证「点了 ▶ 一定有声音」）。
            if ("replay".equals(op)) {
                String q = (curTitle == null) ? "" : curTitle.trim();
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                if (q.length() > 0) {
                    List<Integer> hits = matchTracks(q, false);
                    // 曲名单独配不上（同名太多 / 带了后缀）⇒ 加上歌手再试一次
                    if (hits.size() == 0 && curArtist != null && curArtist.length() > 0) {
                        hits = matchTracks(curArtist + " " + q, false);
                    }
                    if (hits.size() > 0) {
                        List<Integer> pick = new ArrayList<Integer>(hits);
                        int cap = maxPlaylist();
                        if (pick.size() > cap) {
                            pick = new ArrayList<Integer>(pick.subList(0, cap));
                        }
                        int n = pushSongs("replay", pick);
                        if (n > 0) {
                            pushed = true;
                            kickPlay("replay", startDelay(n), 2);
                            SessionLog.i("replay 「" + q + "」: 命中 " + hits.size()
                                    + " 首，推 " + n + " 首");
                            return "OK 重新播放「" + q + "」（库里命中 " + hits.size()
                                    + " 首，已排入 " + n + " 首）";
                        }
                    }
                    SessionLog.i("replay: 库里没有「" + q + "」，退化为随机");
                }
                int n2 = pushSongs("replay->random", shuffledIdx());
                if (n2 > 0) {
                    pushed = true;
                    kickPlay("replay->random", startDelay(n2), 2);
                    return q.length() > 0
                            ? ("OK 库里找不到「" + q + "」，改为随机播放 " + n2 + " 首")
                            : ("OK 没有上次播放记录，随机播放 " + n2 + " 首");
                }
                return "ERR 播放通道不可用（消息总线发送失败）";
            }
            if ("play_song".equals(op)) {
                String q = args == null ? "" : args.optString("title", "");
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("name", "");
                }
                /* ★ v1.3.269：web 收藏列表点「还没配源」的条目会兜底走到这里
                   （player 的 favPlayOneGo 发 play_song 且**带 artist**；语音工具
                   只传 title，带 artist 的只会是收藏点播）。以前落到下面的
                   playOnlineSong —— 它盲取搜索结果第 1 条（热度序），零校验：
                   有声书《道诡异仙》第N集被顶成同名歌《丹阳子》、
                   《Hotline Bling》(Billie Eilish) 被顶成 Drake 的原唱，都是这条。
                   现在改走 fav_resolve 同一套判据（节目优先 → 打分搜歌），
                   拿不下可信匹配就明确报错，**绝不盲播第 1 条**。 */
                String wA = (args == null) ? "" : args.optString("artist", "").trim();
                if (wA.length() > 0 && q.trim().length() > 0) {
                    String sr = favStrictPlay(q.trim(), wA);
                    if (sr != null) {
                        return sr;
                    }
                    return "ERR 《" + q.trim() + "》没搜到可信的版本（同名/翻唱都不可信）"
                            + "—— 可在收藏列表长按歌名手动 🔗 绑定正确的源";
                }
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                // v1.3.260：语音点歌若这首歌已在当前播放队列里，直接跳过去播，不重建队列
                // （用户说「播放 XX」通常就是想接上现有的列表，而不是清掉重来只留那一首）。
                List<Integer> hits = matchTracks(q, false);
                if (hits.size() > 0 && lastIdx.size() > 0) {
                    synchronized (lastIdx) {
                        for (int qi = 0; qi < lastIdx.size(); qi++) {
                            if (hits.contains(lastIdx.get(qi))) {
                                int li = lastIdx.get(qi).intValue();
                                String[] e = tracks.get(li);
                                String who = e[1].length() > 0 ? (e[0] + " - " + e[1]) : e[0];
                                SessionLog.i("play_song 已在队列中 #" + (qi + 1) + ": " + who);
                                return playAt(qi);
                            }
                        }
                    }
                }
                // v1.3.112：拿全部命中，播第一条；若还有其它命中，把「可以连播」这个
                // 信息回给模型，让它有机会提示用户（v1.3.111 只播第一条且不说还有多少）。
                if (hits.size() > 0) {
                    int hit = hits.get(0).intValue();
                    // v1.3.177：本地只捞到「最接近」的一条（包含级 / 部分词命中）
                    // ⇒ 先去 NAS 全源在线搜。语音点名要的歌几乎一定在在线结果里排第一，
                    //   这是「在线优先」：拿到了就用在线那条，拿不到才回落池内这条
                    //   （还有本地兜底，所以这里给较短预算，别让语音干等）。
                    if (!localHitSolid(q, hit)) {
                        String on = playOnlineSong(q, 4000, 8000);
                        if (on != null) {
                            return on;
                        }
                    }
                    String[] e = tracks.get(hit);
                    String url = urlOf(e);
                    String who = e[1].length() > 0 ? (e[0] + " - " + e[1]) : e[0];
                    // 主通道：把整个歌单（命中项排第一）推给 player 自己的播放引擎
                    int n = pushSongs("play_song", hitFirstIdx(hit));
                    // v1.3.157：同名曲很多（原唱/翻唱/Live/DJ），播的是「最像的那一条」，
                    // 但用户未必想要它 —— 把带序号的候选回给模型，小智才有机会说
                    // 「我还找到周杰伦版，要听吗」，而不是闷声播了翻唱。
                    String alt = altHint(hits, hit);
                    if (n > 0) {
                        SessionLog.i("play song (msgcenter): " + who + " -> " + url
                                + " (hits=" + hits.size() + ", list=" + n + ")");
                        pushed = true;
                        kickPlay("play_song", startDelay(n), 2);
                        // v1.3.178：专辑/时长/来源交给 pushPlaylist→notePlayingTrack 从曲目里取，
                        // 这里不再手填（手填会漏掉在线条目的 via="online"）。
                        notePlayingTrack(hit);
                        return "OK 正在播放 " + who + alt;
                    }
                    SessionLog.i("play song (dlna fallback): " + who + " -> " + url);
                    String r = setUri(url);
                    return r.startsWith("OK") ? ("OK 正在播放 " + who + alt) : (r + alt + " (" + who + ")");
                }
                // v1.3.120：序号（模型可能把「第 N 个电台」派给 play_song）
                int ord2 = args == null ? 0 : args.optInt("index", 0);
                if (ord2 <= 0) {
                    ord2 = radioOrdinal(q);
                }
                if (ord2 > 0 && ord2 <= radios.size()) {
                    return playRadio(radios.get(ord2 - 1), "play_song#" + ord2, false);
                }
                // v1.3.119：曲库里没有、又是「所有电台」这类泛词 ⇒ 整表推送
                if (isAllRadio(q)) {
                    return playAllRadios("play_song->all_radio", -1);
                }
                // 电台（v1.3.117：统一走 playRadio，与 play_radio 共用同一套逻辑）
                String[] rr = matchRadio(q);
                if (rr == null) {
                    // v1.3.174：曲库快照 / 电台都没中 ⇒ 去 NAS mini-lx 在线搜一把。
                    // index.json 是启动时拉的快照，快照外的歌（新歌/没进曲库池的歌）
                    // 以前到这里只能 ERR；在线搜到就按「库里刚多了一首」推播。
                    // playOnlineSong 全程自兜底，任何一步失败返回 null ⇒ 照旧 ERR。
                    String on = playOnlineSong(q);
                    if (on != null) {
                        return on;
                    }
                    return "ERR 没找到「" + q + "」，库里共 " + tracks.size() + " 首"
                            + (radios.size() > 0 ? ("；电台：" + radioNames(15)) : "");
                }
                return playRadio(rr, "play_song->radio", false);
            }
            // v1.3.112：随机播放某个歌手/关键词的多首（「随便放点周杰伦」「来几首邓丽君」）。
            // 这是之前完全没有的能力 —— play_song 只播第一条，random 是全库随机，
            // 中间「按条件筛选后随机」这一档是空的。
            if ("play_artist".equals(op)) {
                String q = args == null ? "" : args.optString("artist", "");
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("name", "");
                }
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("title", "");
                }
                // v1.3.115：**不填 count = 该歌手/关键词的全部歌曲**（v1.3.112~114 默认 20）。
                // 用户说「播放周杰伦的歌」时想要的就是周杰伦所有的歌，没有理由只给 20 首；
                // 想限量再显式传 count。
                int cnt = args == null ? 0 : args.optInt("count", 0);
                if (cnt > maxPlaylist()) {
                    cnt = maxPlaylist();
                }
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                // v1.3.114：没给歌手时不要报错 —— 模型经常把「随机放 5 首」派给
                // play_artist 却填不出 artist。这时退化成 random(count)。
                if (q.length() == 0) {
                    int n2 = pushSongs("play_artist->random", randomIdx(cnt));
                    if (n2 > 0) {
                        pushed = true;
                        kickPlay("play_artist->random", startDelay(n2), 2);
                        return "OK 随机播放 " + n2 + " 首（没指定歌手，从库里 "
                                + tracks.size() + " 首中随机挑）";
                    }
                    return "ERR 播放通道不可用（消息总线发送失败）";
                }
                // 歌手优先；歌手里没有再回退到歌名匹配（用户说的可能不是人名）
                List<Integer> hits = matchTracks(q, true);
                if (hits.size() == 0) {
                    hits = matchTracks(q, false);
                }
                // ★ v1.3.184：matchTracks 在「本地 + 在线收录」**合并列表**里搜，
                //   命中数里混着在线条目（池歌/在线搜过的都算），可下面的文案写的
                //   是「本地 X 首」—— 用户关着本地曲库，点周杰伦却听到
                //   「本地 2 首」（其实是池里那 2 首周杰伦）。过滤到真本地；
                //   一条不剩就走下面的在线分支，话术自然就对了。
                for (int i = hits.size() - 1; i >= 0; i--) {
                    int ix = hits.get(i).intValue();
                    if (ix < 0 || ix >= tracks.size() || isOnlineTrack(tracks.get(ix))) {
                        hits.remove(i);
                    }
                }
                // v1.3.178 ★：本地 + 在线一起找。
                //
                // 以前 play_artist 是**纯本地**：库里只有 3 首周杰伦，用户说「随机播放
                // 周杰伦的歌」就只能在这 3 首里打转，而池子外的几十首周杰伦一概看不到。
                // 现在：本地命中照旧算数，再在线搜一批补进来（并行取链，最长 ~5s），
                // 洗牌后一起推 —— 用户点的是"这个歌手的歌"，不是"池里那几首"。
                // 预算：搜 4s + 取链 5s（到点就放弃没回来的，拿多少算多少）。
                // 在线是「加分项」不是必经之路 —— NAS 慢也不能让用户在音箱前干等。
                // v1.3.180：条数统一由「在线曲目上限」管（files/online_max，默认 300）
                List<String[]> on = onlineTracksFor(q, onlineMax(), 4000, 5000);
                if (hits.size() == 0) {
                    // 本地一条都没有：以前直接 ERR，池外歌手彻底哑火。
                    // 现在在线有就在线播，在线也没有才报 ERR。
                    if (on != null && on.size() > 0) {
                        List<Integer> oi = appendOnlineTracks(on);
                        if (oi.size() > 0) {
                            List<Integer> only = new ArrayList<Integer>(oi);
                            Collections.shuffle(only);
                            if (cnt > 0 && only.size() > cnt) {
                                only = new ArrayList<Integer>(only.subList(0, cnt));
                            }
                            int n = pushSongs("play_artist->online", only);
                            if (n > 0) {
                                pushed = true;
                                kickPlay("play_artist->online", startDelay(n), 2);
                                return "OK 本地曲库里没有「" + q
                                        + "」，改为在线播放 " + n + " 首（在线搜到的）";
                            }
                        }
                    }
                    // ★ v1.3.184：报本地数（tracks.size() 是合并容器，在线收录全算
                    //   进去 —— 关着本地曲库时这里会谎报「本地共五百多首」）
                    int locN2 = 0;
                    for (int i = 0; i < tracks.size(); i++) {
                        if (!isOnlineTrack(tracks.get(i))) {
                            locN2++;
                        }
                    }
                    return "ERR 本地和在线都没找到「" + q + "」相关的歌（本地共 "
                            + locN2 + " 首）。用 list 看看库里有哪些歌手";
                }
                List<Integer> pick = new ArrayList<Integer>(hits);
                int onCnt = 0;
                if (on != null && on.size() > 0) {
                    List<Integer> oi = appendOnlineTracks(on);
                    onCnt = oi.size();
                    pick.addAll(oi);
                }
                Collections.shuffle(pick);
                if (cnt > 0 && pick.size() > cnt) {
                    pick = new ArrayList<Integer>(pick.subList(0, cnt));
                }
                // 洗过牌之后第一首可能是冷门翻唱 —— 把最像的那条（本地最佳命中）顶到第一
                int best = hits.get(0).intValue();
                pick.remove(Integer.valueOf(best));
                pick.add(0, Integer.valueOf(best));
                int n = pushSongs("play_artist", pick);
                if (n > 0) {
                    SessionLog.i("play artist '" + q + "': pick " + n
                            + " of " + hits.size() + " local + " + onCnt + " online");
                    pushed = true;
                    kickPlay("play_artist", startDelay(n), 2);
                    return "OK 随机播放「" + q + "」的 " + n + " 首"
                            + "（本地 " + hits.size() + " 首"
                            + (onCnt > 0 ? (" + 在线 " + onCnt + " 首") : "")
                            + (cnt > 0 && (hits.size() + onCnt) > n
                                    ? ("，按你要的只取了 " + n + " 首") : "")
                            + "）";
                }
                return "ERR 播放通道不可用（消息总线发送失败）";
            }

            /* v1.3.196：「全部载入播放列表」—— 搜索曲库那一栏的按钮。
               以前想连播只能一首首点（点一条只播一条），搜出一屏歌却没法整批排进去。
               ids = 曲库序号（1 起，与 play_index / search 输出的编号一致），逗号分隔；
               留空 = 整个曲库。两种都受「播放列表上限」约束（maxPlaylist）。 */
            if ("queue_all".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                String ids = args == null ? "" : args.optString("ids", "");
                int lim = args == null ? 0 : args.optInt("limit", 0);
                List<Integer> pick = new ArrayList<Integer>();
                if (ids.length() == 0) {
                    for (int i = 0; i < tracks.size(); i++) {
                        pick.add(Integer.valueOf(i));
                    }
                } else {
                    String[] ps = ids.split(",");
                    for (int i = 0; i < ps.length; i++) {
                        try {
                            int n = Integer.parseInt(ps[i].trim());
                            if (n >= 1 && n <= tracks.size()) {
                                pick.add(Integer.valueOf(n - 1));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                int cap = maxPlaylist();
                if (lim > 0 && lim < cap) {
                    cap = lim;
                }
                boolean cut = pick.size() > cap;
                if (cut) {
                    pick = new ArrayList<Integer>(pick.subList(0, cap));
                }
                if (pick.size() == 0) {
                    return "ERR 没有可载入的曲目";
                }
                int n = pushSongs("queue_all", pick);
                if (n > 0) {
                    pushed = true;
                    kickPlay("queue_all", startDelay(n), 2);
                    SessionLog.i("queue_all: push " + n + " of " + tracks.size()
                            + " tracks (cut=" + cut + ")");
                    return "OK 已把 " + n + " 首排进播放列表"
                            + (cut ? ("（超出上限，只取了前 " + n + " 首）") : "")
                            + "，正在播第一首";
                }
                return "ERR 播放通道不可用（消息总线发送失败）";
            }

            // v1.3.112：按序号播放（「放第 8 首」）。序号与 search/list 输出的编号一致（1 起）。
            if ("play_index".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                int n = args == null ? 0 : args.optInt("index", 0);
                if (n <= 0) {
                    n = args == null ? 0 : args.optInt("n", 0);
                }
                if (n <= 0) {
                    return "ERR 需要 index 参数（第几首，从 1 开始）";
                }
                if (n > tracks.size()) {
                    return "ERR 序号 " + n + " 超出范围，库里只有 " + tracks.size() + " 首";
                }
                int hit = n - 1;
                String[] e = tracks.get(hit);
                String url = urlOf(e);
                String who = e[1].length() > 0 ? (e[0] + " - " + e[1]) : e[0];
                int cnt = pushSongs("play_index", hitFirstIdx(hit));
                if (cnt > 0) {
                    SessionLog.i("play index #" + n + " (msgcenter): " + who + " -> " + url
                            + " (list=" + cnt + ")");
                    pushed = true;
                    kickPlay("play_index", startDelay(cnt), 2);
                    return "OK 正在播放第 " + n + " 首：" + who
                            + "（播放队列已换成曲库列表）";
                }
                SessionLog.i("play index #" + n + " (dlna fallback): " + who + " -> " + url);
                String r = setUri(url);
                return r.startsWith("OK") ? ("OK 正在播放第 " + n + " 首：" + who) : (r + " (" + who + ")");
            }

            // v1.3.163：跳到自有队列第 N 首（1-based）—— 8080 的 {"type":"play","index":N}
            // 的等价物。队列是我们自己 push 的那张列表，不依赖 player 开 WS。
            if ("play_at".equals(op)) {
                int n = args == null ? 0 : args.optInt("index", 0);
                if (n <= 0) {
                    n = args == null ? 0 : args.optInt("i", 0);
                }
                if (n <= 0) {
                    return "ERR 需要 index 参数（队列第几首，从 1 开始）";
                }
                /* ★ v1.3.277：真队列在 player 手里 ⇒ 跳播直接让 player 本地跳
                   （列表顺序一点不动），镜像过期也不会跳错；player 不在再走老路。 */
                JSONObject live = playerLiveQueue(0);
                if (live != null && n <= live.optInt("total", 0)) {
                    try {
                        String r = httpGet("http://127.0.0.1:8080/api/act?op=play_at&index="
                                + n, 2500, 65536, 4000);
                        if (r != null && r.contains("\"ok\":true")) {
                            return "OK 已跳到播放列表第 " + n + " 首";
                        }
                    } catch (Throwable t) {
                        /* player 没接住 ⇒ 落回老路 */
                    }
                }
                return playAt(n - 1);
            }
            // v1.3.163：只返回自有队列（不播）。web 在 8080 不可用时用它渲染列表。
            if ("queue".equals(op)) {
                int lim = args == null ? 0 : args.optInt("limit", 0);
                /* ★ v1.3.277：先问 player 手里的真队列（网页收藏栏起播的歌单
                   device 不知道），拿不到再回退自己 push 时的镜像。 */
                JSONObject live = playerLiveQueue(lim);
                if (live != null) {
                    JSONObject lj = liveQueueForModel(live);
                    if (lj != null) {
                        return "OK " + lj.toString();
                    }
                }
                return "OK " + queueJson(lim).toString();
            }

            // v1.3.112：只查不播，返回带序号的候选列表。模型要「放第几首」必须先有这个。
            if ("search".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                String q = args == null ? "" : args.optString("keyword", "");
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("title", "");
                }
                if (q.length() == 0) {
                    q = args == null ? "" : args.optString("artist", "");
                }
                // ★ v1.3.182：search 的工具描述写的就是「只覆盖本地曲库池」，
                //   但 matchTracks 是在合并列表里搜 —— 在线听过的歌全混进来了。
                //   过滤掉（下标仍是全库下标，play_index 不受影响）。
                List<Integer> raw = matchTracks(q, false);
                List<Integer> hits = new ArrayList<Integer>(raw.size());
                for (int i = 0; i < raw.size(); i++) {
                    int ix = raw.get(i).intValue();
                    if (ix >= 0 && ix < tracks.size() && !isOnlineTrack(tracks.get(ix))) {
                        hits.add(Integer.valueOf(ix));
                    }
                }
                if (hits.size() == 0) {
                    int locN = 0;
                    for (int i = 0; i < tracks.size(); i++) {
                        if (!isOnlineTrack(tracks.get(i))) {
                            locN++;
                        }
                    }
                    return "ERR 没找到和「" + q + "」相关的本地歌，本地曲库共 " + locN
                            + " 首。用 list 看看有哪些歌手";
                }
                StringBuilder sb = new StringBuilder("OK 找到 " + hits.size() + " 首：");
                int lim = Math.min(hits.size(), 40);
                for (int j = 0; j < lim; j++) {
                    int i = hits.get(j).intValue();
                    String[] e = tracks.get(i);
                    sb.append(i + 1).append(".").append(e[0]);
                    if (e[1].length() > 0) {
                        sb.append("(").append(e[1]).append(")");
                    }
                    sb.append("、");
                }
                if (hits.size() > lim) {
                    sb.append("…仅列前 ").append(lim).append(" 首");
                }
                sb.append("。说「播放第 N 首」用 play_index，说「随机播放 XX」用 play_artist");
                return sb.toString();
            }

            if ("list".equals(op)) {
                if (!idxLoaded) {
                    loadIndex();
                }
                if (!idxLoaded) {
                    return "ERR 音乐库不可用: " + idxErr;
                }
                // v1.3.112：1185 首的库列 60 首歌名没意义（顺序固定、永远同一批、
                // 还挤爆上下文）。改成「歌手统计 + 少量带序号样例」，这才是对
                // 「随机放某歌手的歌」真正有用的信息。
                // ★ v1.3.182：tracks 是「本地 + 在线收录」的合并容器 —— 在线歌播过
                //   就会 append 进来（上限 400）。用户问「本地歌曲里有什么/几首」，
                //   模型照着「共 N 首」回答就成了「本地一共有五百四十七首」
                //   （用户原话：「这应该是把之前播过的在线歌都当成本地了」）。
                //   ⇒ 本地/在线分开报，歌手与样例只取本地。
                int locN = 0;
                int onlN = 0;
                int progN = 0;              // v1.3.279：有声书/节目单列，不再混进「在线收录」
                for (int i = 0; i < tracks.size(); i++) {
                    if (isProgramRow(tracks.get(i))) {
                        progN++;
                    } else if (isOnlineTrack(tracks.get(i))) {
                        onlN++;
                    } else {
                        locN++;
                    }
                }
                StringBuilder sb = new StringBuilder("OK 本地共 " + locN + " 首。");
                if (locN > 0) {
                    sb.append("歌手：").append(artistSummary(30, true)).append("。");
                    // 样例先攒后拼 —— 表头里的数量必须是真实显示数（本地段里夹着
                    // 在线条目时，扫描全库也未必凑得满 20）
                    StringBuilder sample = new StringBuilder();
                    int shown = 0;
                    for (int i = 0; i < tracks.size() && shown < 20; i++) {
                        if (isOnlineTrack(tracks.get(i))) {
                            continue;
                        }
                        shown++;
                        sample.append(i + 1).append(".").append(tracks.get(i)[0]);
                        if (tracks.get(i)[1].length() > 0) {
                            sample.append("(").append(tracks.get(i)[1]).append(")");
                        }
                        sample.append("、");
                    }
                    sb.append("样例 ").append(shown).append(" 首（带序号）：").append(sample)
                            .append("…（要找某歌手/歌名用 search，随机连播某歌手用 play_artist）");
                }
                if (progN > 0) {
                    // v1.3.279：节目集数单独报，别让模型把有声书说成在线音乐
                    sb.append("。另有 ").append(progN)
                            .append(" 集有声书/节目（不算音乐，随机播放已剔除；要听用 ab_play）");
                }
                if (onlN > 0) {
                    // 在线收录的要说清来源身份，别让模型把它们当成本地的转述
                    sb.append("。另收录了 ").append(onlN)
                            .append(" 首在线曲库的歌（不属于本地，点歌/随机时能播到）");
                }
                if (radios.size() > 0) {
                    // v1.3.120：带序号 —— 用户说「第 N 个电台」时模型（和它自己）
                    // 要能对上号，否则只能猜名字。
                    sb.append("。电台（前 ")
                            .append(Math.min(12, radios.size()))
                            .append(" 个，带序号）：");
                    int rn = Math.min(12, radios.size());
                    for (int i = 0; i < rn; i++) {
                        sb.append(i + 1).append(".").append(radios.get(i)[0]).append("、");
                    }
                    if (radios.size() > rn) {
                        sb.append("…等共 ").append(radios.size())
                                .append(" 个台（说「第 N 个电台」可按序号播）");
                    }
                }
                return sb.toString();
            }
            return "ERR unknown op: " + op;
        } catch (Throwable t) {
            Log.w(TAG, "dispatch " + op + " failed: " + t);
            return "ERR " + op + ": " + t;
        }
    }

    private static String setUri(String url) throws Exception {
        // DLNA 渲染器在 STATUS_BUSY（已有媒体在播）时会对 SetAVTransportURI 回
        // 501 Action Failed，先 Stop 一次能显著提高成功率；Stop 本身失败可忽略。
        try {
            avt("Stop", "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                    + "<InstanceID>0</InstanceID></u:Stop>");
        } catch (Throwable t) {
            Log.w(TAG, "dlna pre-stop ignored: " + t);
        }
        // CurrentURIMetaData 留空实测可用（2026-10-09）。
        avt("SetAVTransportURI",
                "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID><CurrentURI>" + xmlEsc(url)
                        + "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
                        + "</u:SetAVTransportURI>");
        avt("Play", "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>");
        return "OK 已开始播放";
    }

    // ---------------- SOAP ----------------

    private static final String SVC_AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String SVC_RCS = "urn:schemas-upnp-org:service:RenderingControl:1";

    private static String avt(String action, String body) throws Exception {
        return call(true, SVC_AVT, action, body);
    }

    private static String rcs(String action, String body) throws Exception {
        return call(false, SVC_RCS, action, body);
    }

    /**
     * 发一次 SOAP。端口失效时（player 重启过）自动重新发现再试一次。
     */
    private static synchronized String call(boolean isAvt, String svc, String action, String body)
            throws Exception {
        if (avtUrl == null && !discover()) {
            throw new Exception("DLNA renderer not found on localhost");
        }
        String url = isAvt ? avtUrl : rcsUrl;
        try {
            return soap(url, svc, action, body);
        } catch (Throwable t) {
            Log.w(TAG, "dlna " + action + " failed (" + t + "), rediscovering");
            if (discover()) {
                return soap(isAvt ? avtUrl : rcsUrl, svc, action, body);
            }
            throw (t instanceof Exception) ? (Exception) t : new Exception(String.valueOf(t));
        }
    }

    // ---------------- DLNA 动态发现 ----------------

    /**
     * 在本机 LISTEN 端口里找 UPnP 设备描述，解析出 AVTransport / RenderingControl 的 controlURL。
     * 依据：/proc/net/tcp 的 st=0A（LISTEN）。
     */
    private static synchronized boolean discover() {
        List<Integer> ports = listenPorts();
        for (int i = 0; i < ports.size(); i++) {
            int p = ports.get(i);
            if (p <= 1024 || p == 5555 || p > 65535) {
                continue;
            }
            try {
                String xml = httpGet("http://127.0.0.1:" + p + "/", 1200);
                if (xml == null || xml.length() == 0 || xml.indexOf("AVTransport") < 0) {
                    continue;
                }
                String a = ctrlOf(xml, "AVTransport");
                if (a == null) {
                    continue;
                }
                String r = ctrlOf(xml, "RenderingControl");
                avtUrl = "http://127.0.0.1:" + p + a;
                rcsUrl = (r == null) ? null : ("http://127.0.0.1:" + p + r);
                dlnaPort = p;   // v1.3.99：暴露给 web 控制台
                Log.i(TAG, "dlna discovered port=" + p + " avt=" + a);
                SessionLog.i("dlna discovered: 127.0.0.1:" + p);
                return true;
            } catch (Throwable t) {
                // 不是 DLNA 端口，继续
            }
        }
        return false;
    }

    /** 从设备描述 XML 里取指定服务的 controlURL。 */
    private static String ctrlOf(String xml, String service) {
        int i = 0;
        while (true) {
            int s = xml.indexOf("<service>", i);
            if (s < 0) {
                return null;
            }
            int e = xml.indexOf("</service>", s);
            if (e < 0) {
                return null;
            }
            String blk = xml.substring(s, e);
            if (blk.indexOf("service:" + service + ":") >= 0) {
                int a = blk.indexOf("<controlURL>");
                if (a < 0) {
                    return null;
                }
                int b = blk.indexOf("</controlURL>", a);
                if (b < 0) {
                    return null;
                }
                return blk.substring(a + 12, b);
            }
            i = e + 10;
        }
    }

    /** 读 /proc/net/tcp，返回本机处于 LISTEN 的端口列表。 */
    private static List<Integer> listenPorts() {
        List<Integer> out = new ArrayList<Integer>();
        try {
            FileInputStream in = new FileInputStream("/proc/net/tcp");
            String all = readAll(in);
            String[] lines = all.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String[] f = lines[i].trim().split("\\s+");
                if (f.length < 4) {
                    continue;
                }
                if (!"0A".equalsIgnoreCase(f[3])) {
                    continue;
                }
                int c = f[1].indexOf(':');
                if (c < 0) {
                    continue;
                }
                try {
                    out.add(Integer.parseInt(f[1].substring(c + 1), 16));
                } catch (Throwable t) {
                    // ignore
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "/proc/net/tcp unreadable: " + t);
        }
        return out;
    }

    private static String soap(String url, String service, String action, String body)
            throws Exception {
        String env = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>" + body + "</s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + service + "#" + action + "\"");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(6000);
            OutputStream os = c.getOutputStream();
            os.write(env.getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            String r = (is == null) ? "" : readAll(is);
            if (code >= 400 || r.contains("<s:Fault") || r.contains("errorCode")) {
                throw new Exception("HTTP " + code + " " + r);
            }
            Log.i(TAG, "dlna " + action + " ok");
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String tag(String xml, String name) {
        int i = xml.indexOf("<" + name + ">");
        if (i < 0) {
            return "";
        }
        int j = xml.indexOf("</" + name + ">", i);
        if (j < 0) {
            return "";
        }
        return xml.substring(i + name.length() + 2, j);
    }

    private static String xmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------- IO ----------------

    // ---------------- v1.3.264：有声书 ab_*（分批拉） ----------------
    // 数据源 = NAS MediaServer /api/ab/search|episodes；v1.3.264 起 episodes 分批：
    // 一次只取一页（默认 50 集），整本只在上游枚举一次、NAS 缓存 5 分钟按页切片，
    // 设备侧用 loader（SP: ab_loader_*）记住当前批，ab_next/ab_resume 续拉下一页。
    // 起播走 playUrl()（与 play_url 同一条推动链），进度存 SP（ab_book/ab_ep/ab_title）—— 重启不丢。

    private static String abSpGet(String key, String def) {
        try {
            return playerPref().getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void abSpPut(String key, String val) {
        try {
            playerPref().edit().putString(key, val).commit();
        } catch (Throwable t) { /* SP 不可写不致命 */ }
    }

    /** 模型传 ep 有时给字符串（"4"）——两种都认。 */
    private static int abInt(JSONObject args, String key) {
        if (args == null) {
            return 0;
        }
        int v = args.optInt(key, -1);
        if (v > 0) {
            return v;
        }
        String s = args.optString(key, "").trim();
        if (s.length() > 0) {
            try {
                return Integer.parseInt(s);
            } catch (Throwable t) { /* 中文数字（「第四集」）不在这里解析 */ }
        }
        return 0;
    }

    /* ==================== v1.3.325：本地有声书库优先（NAS /api/abl/*） ====================
       以前语音「播放某本有声书」一律走 NAS /api/ab/search（在线书源、播到才取链），
       本地 /audiobook 目录里明明有同一本书也用不上。这里加一层：点播前先问本地库，
       命中就用 NAS 8100 的 /m/<rel> 直链整批入列 —— 秒开、不走外网。

       ★ 只按「书名」判，不比主播：本地库一本书就一版，用户要的就是这一版。
       ★ 编号写成 "abl:<本地id>" 塞进 ab_loader_album；ab_next / ab_prev / ab_resume
         之后再调 abEpisodes 时看到这个前缀就回本地库取集（见 abEpisodes 开头的路由），
         所以续拉/跳集不用另写一套。 */

    /** 书名归一化：去书名号/空白/常见尾缀、转小写。只用来判「是不是同一本」。 */
    private static String ablKey(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("《", "").replace("》", "")
                .replace(" ", "").replace("\u3000", "");
        t = t.replaceAll("[，。、：；！？（）【】〈〉·—\\-_(),.:;!?\\[\\]\"'“”‘’]", "");
        t = t.replaceAll("(有声小说|有声书|广播剧|评书|相声|全集|完结)$", "");
        return t.toLowerCase();
    }

    /**
     * 查本地库挑一本。
     *
     * @return 命中返回 book 对象（含 id/name/title/count）；不够像就返 null —— 宁可走在线，
     *         也不要因为「名字里有两个字相同」把别的书播出来。
     */
    private static JSONObject ablFind(String b, String q) {
        try {
            if (q == null || q.trim().length() == 0) {
                return null;
            }
            String kq = ablKey(q);
            if (kq.length() == 0) {
                return null;
            }
            String js = httpGet(b + "/api/abl/books?q=" + URLEncoder.encode(q, "UTF-8"),
                    8000, 4 * 1024 * 1024, 8000);
            if (js == null) {
                return null;
            }
            JSONArray bs = new JSONObject(js).optJSONArray("books");
            if (bs == null) {
                return null;
            }
            JSONObject best = null;
            int bestScore = 0;
            for (int i = 0; i < bs.length(); i++) {
                JSONObject x = bs.optJSONObject(i);
                if (x == null) {
                    continue;
                }
                String nm = x.optString("title", "");
                if (nm.length() == 0) {
                    nm = x.optString("name", "");
                }
                String kn = ablKey(nm);
                int sc = 0;
                if (kn.length() > 0 && kn.equals(kq)) {
                    sc = 100;
                } else if (kn.length() > 0 && (kn.indexOf(kq) >= 0 || kq.indexOf(kn) >= 0)) {
                    sc = 60;
                }
                if (sc > bestScore) {
                    bestScore = sc;
                    best = x;
                }
            }
            if (bestScore < 60) {
                /* ★ v1.3.355：服务端已经做过匹配判定（v1.7.46 起含「q 里有书名」的整句兜底
                   和部名归属），device 这层裸书名打分只该用来「挑更好的」，不该「一票否决」：
                   用户只点部名说「死神永生」时，kq=死神永生 与 kn=三体 互不包含 ⇒ 0 分，
                   服务端明明给出了唯一命中（还带 hitSub），却被这里丢掉。
                   规则（保守）：
                     · 有带 hitSub 的 ⇒ 直接用（服务端已认出是哪一部，最可信）
                     · 只回 1 条 ⇒ 用它（唯一候选，服务端匹配规则已收窄到「q 含书名」）
                     · 多条且都没 hitSub ⇒ 仍返回 null（宁可走在线，也不赌书名） */
                JSONObject withSub = null;
                for (int i = 0; i < bs.length(); i++) {
                    JSONObject x = bs.optJSONObject(i);
                    if (x != null && x.optString("hitSub", "").length() > 0) {
                        withSub = x;
                        break;
                    }
                }
                if (withSub != null) {
                    return collPick(withSub);
                }
                if (bs.length() == 1) {
                    JSONObject only = bs.optJSONObject(0);
                    if (only != null) {
                        return collPick(only);
                    }
                }
                return null;
            }
            /* ★ v1.3.353：合集（kind=coll，如《三体》三部）不再一票跳过。
               老代码这里 `continue`（注释「不自动挑部，仍走在线版」），于是语音点播
               《三体》永远拿不到本地 id ⇒ 只能 Web 上手动展开子书点。服务端
               /ap/play 的口径本来就是「合集先放第一部」，这里对齐并做得更细。 */
            return collPick(best);
        } catch (Throwable t) {
            Log.w(TAG, "ablFind: " + t);
            return null;
        }
    }

    /**
     * ★ v1.3.353：合集 → 挑一部**能播的子书**，返回形状与普通 book 一致（id 是子书 id）。
     *
     * <p>为什么要挑而不是把合集整个交出去：`/api/abl/eps?id=<合集 id>` 返回的
     * {@code episodes} 是**空数组**（合集只有 subs 清单，集表在子书那层），
     * 所以拿合集 id 去拉集必然空 ⇒ 播不了。Web 上手动点是先展开 subs 再点子书，
     * 语音这条路以前没有这一步（ablFind 直接跳过 coll），等于彻底没入口。</p>
     *
     * <p>挑部顺序（越具体越优先）：</p>
     * <ol>
     *   <li>★ v1.3.354：服务端回传的 {@code hitSub} —— 用户语音里直接点了某一部
     *       （「播放三体死神永生第2集」⇒ books() 认出「死神永生Ⅲ」）。意图最明确，排第一。</li>
     *   <li>服务端 {@code books()} 聚合出的 {@code lastSub}（父级记忆来自某个子书时才会给）</li>
     *   <li>父级 {@code lastTitle} 前缀认领 —— Web 端点集合会往 POS[合集id] 写一条，
     *       形如「黑暗森林-第86集」，把子书名剥掉罗马数字后比前缀即可命中「黑暗森林Ⅱ」</li>
     *   <li>都没听过 ⇒ 第一部（与服务端 /ap/play 同口径）</li>
     * </ol>
     *
     * <p>返回对象的**书身份是子书名**（v1.3.357 起，用户拍板「后面的名字才是书名，
     * 三体只是系列」）：「三体」只作为 {@code series} 留在对象上供展示/日志用，
     * name/title 与队列 album、/v1/pos 上报一律是「黑暗森林Ⅱ」这种子书名 ——
     * 否则三部曲共用「三体」一个书键，书级进度和播放记录分不出在听哪一部。</p>
     */
    /* ★ v1.3.355：记住本轮用户原话（ASR 文本）。

       为什么必须记：小智模型调 ab_play 时经常把书名**精简**成「三体」，用户说的
       「死神永生」在参数里就丢了 —— 而 ab_search 只回在线书源，看不到本地合集的分部，
       模型也就无从知道该带上哪一部。原话是这件事唯一可靠的来源。

       有效期 90 秒（一句话到工具调用之间的正常间隔），不消费 —— 同一轮可能连着
       问几次（先搜再播、或播错重说）。超时即失效，避免「上一轮的话」污染这一轮。 */
    private static volatile String lastUtter = "";
    private static volatile long lastUtterAt = 0L;

    public static void noteUtterance(String t) {
        String s = (t == null) ? "" : t.trim();
        if (s.length() == 0) {
            return;
        }
        lastUtter = s;
        lastUtterAt = System.currentTimeMillis();
    }

    /** @return 90 秒内的用户原话；没说过 / 已过期返回空串 */
    public static String recentUtterance() {
        if (lastUtterAt == 0L) {
            return "";
        }
        if (System.currentTimeMillis() - lastUtterAt > 90000L) {
            return "";
        }
        return lastUtter;
    }

    private static JSONObject collPick(JSONObject x) {
        if (x == null || !"coll".equals(x.optString("kind", ""))) {
            return x;
        }
        JSONArray subs = x.optJSONArray("subs");
        if (subs == null || subs.length() == 0) {
            return null;                        // 空合集（盘上只有目录、没有音频）
        }
        JSONObject pick = null;
        /* ★ v1.3.354：用户在语音里**点到了某一部**（「播放三体死神永生第2集」）。
           服务端 books() 会拿整句去认子书，回传 hitSub（「死神永生Ⅲ」）。
           这是最明确的意图，必须压过「上次听的那部」—— 否则用户说死神永生，
           只要上次听的是地球往事，就还是接着播地球往事。 */
        String hitSub = x.optString("hitSub", "").trim();
        if (hitSub.length() > 0) {
            for (int i = 0; i < subs.length(); i++) {
                JSONObject s = subs.optJSONObject(i);
                if (s != null && hitSub.equals(s.optString("name", "").trim())) {
                    pick = s;
                    break;
                }
            }
        }
        String lastSub = x.optString("lastSub", "").trim();
        if (pick == null && lastSub.length() > 0) {
            for (int i = 0; i < subs.length(); i++) {
                JSONObject s = subs.optJSONObject(i);
                if (s != null && lastSub.equals(s.optString("name", "").trim())) {
                    pick = s;
                    break;
                }
            }
        }
        if (pick == null) {
            String lt = ablKey(x.optString("lastTitle", ""));
            if (lt.length() > 0) {
                for (int i = 0; i < subs.length() && pick == null; i++) {
                    JSONObject s = subs.optJSONObject(i);
                    if (s == null) {
                        continue;
                    }
                    /* 子书名末尾的部序号（Ⅰ/Ⅱ/Ⅲ、第2部、2）要剥掉再比：
                       「黑暗森林Ⅱ」→ 主干「黑暗森林」，而 lastTitle 是「黑暗森林-第86集」。 */
                    String kn = ablKey(s.optString("name", ""))
                            .replaceAll("[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅰⅱⅲⅳⅴⅵⅶⅷⅸⅹ]+$", "")
                            .replaceAll("(第?[0-9一二三四五六七八九十]+[部卷册])$", "");
                    if (kn.length() >= 2 && lt.indexOf(kn) >= 0) {
                        pick = s;
                    }
                }
            }
        }
        if (pick == null) {
            pick = subs.optJSONObject(0);
        }
        if (pick == null) {
            return null;
        }
        String coll = x.optString("name", "").trim();
        String subName = pick.optString("name", "").trim();
        try {
            JSONObject o = new JSONObject(pick.toString());
            o.remove("subs");
            o.put("kind", "book");
            /* ★ v1.3.357：书名用**子书名**，合集名退居 series。
               服务端 v1.7.48 的 episodes() 也是这个口径（name=子书名、series=合集名），
               两边必须一致 —— 队列 album / /v1/pos / 服务端书键才能归到同一本。 */
            if (subName.length() > 0) {
                o.put("name", subName);
                if (o.optString("title", "").length() == 0) {
                    o.put("title", subName);
                }
            }
            if (coll.length() > 0) {
                o.put("coll", coll);            // 合集名（兼容旧字段）
                o.put("series", coll);          // 系列名，只给展示/日志用
            }
            /* ★ v1.3.355：把「点了哪一部」透给调用方 —— abPlay 靠它决定要不要
               再拿用户原话补一次（原话命中才带 hitSub）。 */
            if (hitSub.length() > 0) {
                o.put("hitSub", hitSub);
            }
            Log.i(TAG, "abl coll pick: " + coll + " → " + pick.optString("name", "")
                    + "（" + pick.optInt("count", 0) + " 集）"
                    + (hitSub.length() > 0 ? " [语音点名 hitSub=" + hitSub + "]" : ""));
            return o;
        } catch (Throwable t) {
            Log.w(TAG, "collPick: " + t);
            return pick;
        }
    }

    /**
     * 本地库集表 → 与 /api/ab/episodes 同形状，episodes 每行多带一个 {@code url} 直链。
     *
     * <p>abQueueBook 见到 rid 为空、url 非空的行就直接用 url（不再拼 /r/ 占位），
     * 所以本地书是「入列即可播」，连一次取链都省了。</p>
     */
    private static JSONObject ablEpsAsAb(String b, String id, int page, int size, int ep) {
        try {
            int sz = Math.max(10, Math.min(size, 500));
            StringBuilder u = new StringBuilder(b + "/api/abl/eps?id="
                    + URLEncoder.encode(id, "UTF-8") + "&page=" + Math.max(1, page)
                    + "&size=" + sz);
            if (ep > 0) {
                u.append("&ep=").append(ep);
            }
            String js = httpGet(u.toString(), 20000, 16 * 1024 * 1024, 20000);
            if (js == null) {
                return null;
            }
            JSONObject o = new JSONObject(js);
            JSONArray src = o.optJSONArray("episodes");
            if (src == null || src.length() == 0) {
                return null;
            }
            String nar = o.optString("narrator", "");
            JSONArray out = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONObject e = src.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                String url = e.optString("url", "").trim();
                if (url.length() == 0) {
                    continue;
                }
                JSONObject x = new JSONObject();
                x.put("ep", e.optInt("ep", 0));
                x.put("title", e.optString("title", ""));
                x.put("artist", nar);
                x.put("url", url);
                x.put("source", "abl");
                out.put(x);
            }
            if (out.length() == 0) {
                return null;
            }
            String nm = o.optString("name", "");
            if (nm.length() == 0) {
                nm = o.optString("title", "");
            }
            JSONObject d = new JSONObject();
            d.put("book", nm);
            /* ★ v1.3.357：合集子书带上系列名（服务端 v1.7.48 起 series=合集名），
               只给展示用；书身份始终是 book/name（子书名）。 */
            String sr = o.optString("series", "").trim();
            if (sr.length() > 0) {
                d.put("series", sr);
            }
            d.put("narrator", nar);
            d.put("albumId", "abl:" + id);
            d.put("source", "abl");
            d.put("total", o.optInt("total", out.length()));
            d.put("page", o.optInt("page", page));
            d.put("size", sz);
            d.put("hasMore", o.optBoolean("hasMore", false));
            d.put("episodes", out);
            return d;
        } catch (Throwable t) {
            Log.w(TAG, "ablEpsAsAb: " + t);
            return null;
        }
    }

    /** v1.3.264：/api/ab/episodes 分批拉 —— 一次只取一页（默认 50 集），整本只在上游枚举一次、
     *  NAS 侧缓存 5 分钟并按页切片。这里把 page/size/ep 透传给 NAS，ep>0 时 NAS 自动定位
     *  包含该集的页。返回 {book,narrator,albumId,source,total,page,size,hasMore,episodes:[...]}。 */
    private static JSONObject abEpisodes(String q, String albumId, int page, int size, int ep) {
        try {
            String b = nasBase();
            if (b == null) {
                return null;
            }
            /* ★ v1.3.325：本地书（albumId 以 abl: 开头）走本地库集表 ——
               ab_next / ab_prev / ab_resume 都是调这里续拉的，一处路由全通。 */
            if (albumId != null && albumId.startsWith("abl:")) {
                return ablEpsAsAb(b, albumId.substring(4), page, size, ep);
            }
            StringBuilder u2 = new StringBuilder(b + "/api/ab/episodes?q="
                    + URLEncoder.encode(q == null ? "" : q, "UTF-8"));
            if (albumId != null && albumId.length() > 0) {
                u2.append("&albumId=").append(URLEncoder.encode(albumId, "UTF-8"));
            }
            if (page > 0) {
                u2.append("&page=").append(page);
            }
            if (size > 0) {
                u2.append("&size=").append(size);
            }
            if (ep > 0) {
                u2.append("&ep=").append(ep);
            }
            String js = httpGet(u2.toString(), 90000, 16 * 1024 * 1024, 90000);
            if (js == null || js.length() == 0) {
                return null;
            }
            return new JSONObject(js);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 用 loader 记住的 size 拉一页（page<=0 且 ep<=0 ⇒ NAS 给第1页）。 */
    private static JSONObject abEpFetch(String q, String albumId, int page, int ep) {
        return abEpisodes(q, albumId, page, abLoaderSize(), ep);
    }

    private static int abIntSP(String key, int def) {
        try {
            return Integer.parseInt(abSpGet(key, String.valueOf(def)).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 每批集数：默认 50，clamp 10~200。 */
    private static int abLoaderSize() {
        int s = abIntSP("ab_loader_size", 50);
        return (s >= 10 && s <= 200) ? s : 50;
    }

    /** ★ v1.3.276：按书记版本的近期历史（SP: ab_ver_hist，LRU 8 条）。
       单 loader 槽会被交叉听书冲掉；这里让每本书记住自己上次用的专辑，
       用户回头只说集数不说主播（模型会话过期不带 narrator）也能接上。 */
    private static void abHistSave(String book, String album) {
        if (book == null || album == null) {
            return;
        }
        book = book.trim();
        album = album.trim();
        if (book.length() == 0 || album.length() == 0
                || "null".equals(book) || "null".equals(album)) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(abSpGet("ab_ver_hist", "[]"));
            JSONArray out = new JSONArray();
            String nb = Fav.key(abBookOnly(book), "");
            JSONObject head = new JSONObject();
            head.put("book", book);
            head.put("album", album);
            head.put("ts", System.currentTimeMillis());
            out.put(head);
            for (int i = 0; i < arr.length() && out.length() < 8; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String ob = o.optString("book", "").trim();
                String oa = o.optString("album", "").trim();
                if (ob.length() == 0 || oa.length() == 0
                        || "null".equals(ob) || "null".equals(oa)) {
                    continue;
                }
                String nb2 = Fav.key(abBookOnly(ob), "");
                if (nb2.length() == 0) {
                    continue;
                }
                if (oa.equals(album) && nb2.equals(nb)) {
                    continue;                                   // 旧的同书同版本条目让位
                }
                out.put(o);
            }
            abSpPut("ab_ver_hist", out.toString());
        } catch (Throwable t) { /* 历史存不上不致命 */ }
    }

    /** 查这本书最近播过的版本（albumId），没有返回 ""。 */
    private static String abHistFind(String bq) {
        try {
            JSONArray arr = new JSONArray(abSpGet("ab_ver_hist", "[]"));
            String nb = Fav.key(abBookOnly(bq == null ? "" : bq), "");
            if (nb.length() == 0) {
                return "";
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String ob = o.optString("book", "").trim();
                String oa = o.optString("album", "").trim();
                if (ob.length() == 0 || oa.length() == 0
                        || "null".equals(ob) || "null".equals(oa)) {
                    continue;
                }
                String nb2 = Fav.key(abBookOnly(ob), "");
                if (nb2.length() == 0) {
                    continue;
                }
                if (nb.indexOf(nb2) >= 0 || nb2.indexOf(nb) >= 0) {
                    return oa;
                }
            }
        } catch (Throwable t) { /* 历史读不到走老路 */ }
        return "";
    }

    /** 记住当前批次的书/专辑/源/页/页范围，供 ab_next/ab_resume 续拉。 */
    private static void abLoaderSave(String book, String album, String src,
                                     int page, int size, int total, int pstart, int pend) {
        abSpPut("ab_loader_book", book);
        abSpPut("ab_loader_album", album);
        abSpPut("ab_loader_src", src);
        abSpPut("ab_loader_page", String.valueOf(page));
        abSpPut("ab_loader_size", String.valueOf(size));
        abSpPut("ab_loader_total", String.valueOf(total));
        abSpPut("ab_loader_pstart", String.valueOf(pstart));
        abSpPut("ab_loader_pend", String.valueOf(pend));
        abHistSave(book, album);                            // ★ v1.3.276：按书记版本
    }

    /** 从一页 episodes 取最小/最大集号（无集号条目忽略）。 */
    private static int[] abPageRange(JSONObject d) {
        JSONArray eps = d == null ? null : d.optJSONArray("episodes");
        int lo = 0, hi = 0;
        if (eps != null) {
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                int x = e.optInt("ep", 0);
                if (x > 0) {
                    if (lo == 0 || x < lo) {
                        lo = x;
                    }
                    if (x > hi) {
                        hi = x;
                    }
                }
            }
        }
        return new int[]{lo, hi};
    }

    /** 续拉提示：本批范围 + 全书总数 + 「本批播完说继续」。 */
    private static String batchHint(JSONObject d) {
        boolean hasMore = d != null && d.optBoolean("hasMore", false);
        int total = d == null ? 0 : d.optInt("total", 0);
        int[] rng = abPageRange(d);
        StringBuilder sb = new StringBuilder("。");
        if (rng[0] > 0 || rng[1] > 0) {
            sb.append("本批已加载第").append(rng[0]).append("~").append(rng[1]).append("集");
        }
        if (total > 0) {
            sb.append("（全书共").append(total).append("集）");
        }
        if (hasMore) {
            sb.append("；本批播完说「继续」或「下一集」自动拉下一批");
        }
        return sb.toString();
    }

    /** 从集数表挑目标集：精确 ep；ep<=0 取第一个有集号的；全都没有就第一条。 */
    private static JSONObject abPick(JSONArray eps, int ep) {
        if (eps == null || eps.length() == 0) {
            return null;
        }
        if (ep > 0) {
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e != null && e.optInt("ep", 0) == ep) {
                    return e;
                }
            }
            return null;
        }
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e != null && e.optInt("ep", 0) > 0) {
                return e;
            }
        }
        return eps.optJSONObject(0);
    }

    /** ★ 1.3.360：花絮/预告类条目标题判据（只在 ep<=0 时用）。
     *
     * <p>本地书常把「第0集 预告」「片花」放在集表第一行 —— 它不是正片：</p>
     * <ul>
     *   <li>播出来用户会以为「续播记忆丢了」（实测 11-20 20:11 播《凡人修仙传》）；</li>
     *   <li>ep=0 的上报被 {@code ProgProgress} 整条丢弃（{@code ep <= 0 ⇒ return false}）
     *       ⇒ 服务端一条播放记录都没有，越听越像"没记忆"。</li>
     * </ul>
     * <p>「引子/序」这类正片开头的 ep 通常是 1，标题也不命中这些词，不受影响。</p> */
    private static boolean abIsTrailer(String title) {
        if (title == null) {
            return false;
        }
        String t = title.trim();
        if (t.length() == 0) {
            return false;
        }
        String[] kw = {"预告", "片花", "花絮", "宣传", "片头曲", "片尾曲", "主题曲", "彩蛋"};
        for (int i = 0; i < kw.length; i++) {
            if (t.indexOf(kw[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** ★ 1.3.360：ab_play 没带集号时，「上次听到哪」。
     *
     * <p>来源顺序（与 ab_resume 同口径，取第一个有集号的）：</p>
     * <ol>
     *   <li>服务端书级共享进度 {@code /v1/resume} —— 语音/网页/stem 全通道的权威本，
     *       已含「上一集听完 ⇒ 下一集开头」的判定；</li>
     *   <li>我的节目 —— 收藏书 Prog 的 lastEp/lastPos；</li>
     *   <li>SP「最近听书记忆」—— 纯语音播过、没收藏的书（lastListenEp/lastListenPos）。</li>
     * </ol>
     *
     * <p>返回 {@code {ep, posSec}}；没有任何进度时返回 {@code {0,0}}，调用方落第一集。
     * 位置统一回退 10 秒（与 stem / r1web 点播同口径）。</p> */
    private static int[] lastHeardOf(String book) {
        int ep = 0;
        int pos = 0;
        String src = "none";
        try {
            JSONObject sr = srvResume(book);
            if (sr != null) {
                ep = sr.optInt("ep", 0);
                pos = Math.max(0, sr.optInt("pos", 0));
                src = "srv";
            }
            Log.i(TAG, "lastHeardOf srv=" + (sr == null
                    ? "null" : (sr.optInt("ep", 0) + "/" + sr.optInt("pos", 0)))
                    + " book=" + book);
        } catch (Throwable t) {
            Log.w(TAG, "lastHeardOf srv: " + t);
        }
        if (ep <= 0) {
            try {
                JSONObject pb = Prog.findByName(book);
                if (pb != null) {
                    ep = pb.optInt("lastEp", 0);
                    pos = pb.optInt("lastPos", 0);
                    if (ep > 0) {
                        src = "prog";
                    }
                }
            } catch (Throwable t) {
                ep = 0;
                pos = 0;
            }
        }
        if (ep <= 0) {
            ep = lastListenEp(book);
            if (ep > 0) {
                pos = lastListenPos(book, ep);
                src = "sp";
            }
        }
        if (ep <= 0) {
            Log.i(TAG, "lastHeardOf 无进度（这本书没听过）");
            return new int[]{0, 0};
        }
        if (pos > 5) {
            pos = pos - 10;
        } else {
            pos = 0;
        }
        if (pos < 0) {
            pos = 0;
        }
        Log.i(TAG, "lastHeardOf ep=" + ep + " pos=" + pos + " src=" + src);
        return new int[]{ep, pos};
    }

    /** 起播一集 + 记进度（playUrl 成功后才写 SP）。what = 话术前缀（「下一集 ——」等）。 */
    /**
     * v1.3.263：整部入列 —— 与 queue_online 同构（本地段原样保留、在线段整批接在后面），
     * 但有三点不同：① 集数从 startEp 起连排（之前的集不进列表）；② via 列打 "program"
     * （网页播放栏亮「📖 在线节目」）；③ 第一集正式取链、其余惰性地址，播到哪取哪。
     * 返回 null = 播放通道推不动（调用方退回单曲 playUrl 兜底）。
     */
    /* ★ v1.3.328：本地书的集名是纯集名（「玉佩」「黑鼎」「引子」），排进播放队列后
       第几集完全看不出来 —— 连听/跳集时对不上号，只能靠数行。本地行统一补「第N集 」前缀。
       在线行的 title 本来就带集数，一律不动。
       ★ v1.3.329：判据改成「标题里任意位置已经有集号就不加」—— 有的书（凡人修仙传）
       标题是「凡人修仙传 第1234集 xxx」，开头不是「第」，只判开头会补成
       「第1234集 凡人修仙传 第1234集 xxx」两个集号。 */
    private static String abEpTitle(int ep, String title, boolean local) {
        if (!local || ep <= 0 || title == null || title.length() == 0) {
            return title;
        }
        if (title.matches(".*第\\s*\\d+\\s*[集回章话节].*")) {
            return title;                       // 已经自带集号，不再动
        }
        return "第" + ep + "集 " + title;
    }

    private static String abQueueBook(String book, int total, JSONArray eps, int startEp, String what) {
        String b = nasBase();
        if (b == null) {
            return "ERR 音乐源没配（在线节目拿不到地址）";
        }
        List<Integer> pick = new ArrayList<Integer>();
        int cap = onlineMax();          // ★ v1.3.306：在线内容走「在线数」（不再走本地数）
        int firstEp = 0;
        String firstTitle = "";
        synchronized (tracks) {
            List<String[]> keep = localTracksSnapshot();
            tracks.clear();
            tracks.addAll(keep);
            for (int i = 0; i < eps.length() && pick.size() < cap; i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                int ep = e.optInt("ep", 0);
                /* ★ 1.3.360：ep<=0 的「预告/片花/花絮」不进队列。
                   本地书常把它放在集表第一行，以前 startEp<=1 时不过滤 ⇒ 稳坐队首，
                   成为"起播那一集"（实测 11-20 20:11 播《凡人修仙传》出声的是
                   「第0集 预告」）；而且 ep=0 的上报会被 ProgProgress 整条丢弃
                   ⇒ 服务端一条记录都没有。真正的正片（ep>=1）不受影响，
                   「引子/序」这类正片开头的 ep 通常是 1，也不受影响。 */
                if (ep <= 0 && abIsTrailer(e.optString("title", ""))) {
                    continue;
                }
                /* ★ v1.3.325：本地书的集号从 0 起（引子/序）。原来只比「ep>0 且 < startEp」，
                   引子(0) 永远逃过过滤、稳坐队首 —— 说「播第3集」也从引子开始播。
                   现在 startEp>1 严格切；startEp<=1（没指定/就要第1集）时保留引子，
                   按书的自然顺序从头放。 */
                if (startEp > 1 && ep < startEp) {
                    continue;   // 从 startEp 那集起排，前面的不进列表
                }
                String title = e.optString("title", "");
                String artist = e.optString("artist", "");
                String source = e.optString("source", "kw");
                String rid = e.optString("rid", "");
                String lurl = e.optString("url", "").trim();    // ★ v1.3.325：本地库直链
                if (rid.length() == 0 && lurl.length() == 0) {
                    continue;
                }
                String url = "";
                if (pick.size() == 0) {
                    // 第一集：正式取链（马上要出声的那首）；本地行自带 url，不用再取
                    if (rid.length() > 0) {
                        url = onlineUrl(title, artist, source, rid, 8000);
                    }
                    firstEp = ep;
                    firstTitle = title;
                }
                if (url.length() == 0) {
                    url = lurl.length() > 0 ? lurl
                            : (b + "/r/" + source + "/" + rid
                               + "?title=" + enc(title) + "&artist=" + enc(artist));
                }
                /* ★ v1.3.325：本地库行打 "abl"（网页映射成「📖 本地节目」）——
                   打 "program" 会被当成在线节目；打空则会因为标题只是「引子」「玉佩」
                   被 npIsProgram() 判成普通音频，贴成「本地音乐」。
                   ★ v1.3.328：本地行的队列标题补「第N集 」，播报仍用 firstTitle 原文。 */
                tracks.add(new String[]{abEpTitle(ep, title, lurl.length() > 0), artist, "", url, book, "", "",
                        lurl.length() > 0 ? "abl" : "program"});
                pick.add(Integer.valueOf(tracks.size() - 1));
            }
        }
        if (pick.size() == 0) {
            return "ERR 这一本书一集地址都拿不到";
        }
        int n = pushSongs("ab_play", pick);
        if (n <= 0) {
            return null;
        }
        pushed = true;
        kickPlay("ab_play", startDelay(n), 2);
        abSpPut("ab_book", book);
        abSpPut("ab_ep", String.valueOf(firstEp));
        abSpPut("ab_title", firstTitle);
        StringBuilder sb = new StringBuilder("OK ").append(what == null ? "" : what)
                .append("已把《").append(book).append("》");
        if (firstEp > 0) {
            sb.append("第").append(firstEp).append("集起的 ");
        }
        sb.append(n).append(" 集排进播放列表，正在播");
        if (firstEp > 0) {
            sb.append("第").append(firstEp).append("集");
        }
        sb.append("：").append(firstTitle);
        if (total > 0 && total != n) {
            sb.append("（全书").append(total).append("集");
            if (n < total) {
                sb.append("，本次排到第").append(firstEp + n - 1).append("集");
            }
            sb.append("）");
        }
        sb.append("。播完一集会自动连播下一集；想跳集就说「播放第N集」。");
        return sb.toString();
    }

    /**
     * v1.3.302：播整本有声书（已绑定集表）从 startEp 起整批入列，并钉死 loader/book 状态，
     * 使下一集/上一集/续播锁定本书 —— 修复「跳回之前播的书下一集」+「大部分播不了」。
     * 与 abQueueBook 的区别：① 不预取第一集（全用 /r/{src}/{rid} 惰性地址，不卡界面）；
     * ② 直接吃已绑定的 eps（Prog 缓存命中，无需再搜 NAS）。eps 元素需含
     * ep/title/artist/source/rid（rid 为空行被跳过）。返回 null = 推不动（调用方退回兜底）。
     */
    private static String abQueueProgLazy(String book, String album, String src, int total,
                                          JSONArray eps, int startEp) {
        if (eps == null || eps.length() == 0) {
            return null;
        }
        String b = nasBase();
        if (b == null) {
            return "ERR 音乐源没配（在线节目拿不到地址）";
        }
        // 按集号升序排（Prog 缓存可能乱序追加）
        List<JSONObject> lst = new ArrayList<JSONObject>();
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e != null) {
                lst.add(e);
            }
        }
        /* ★ android-22 的 List 没有 sort()（API24 才有）⇒ 用 Collections.sort */
        java.util.Collections.sort(lst, new java.util.Comparator<JSONObject>() {
            public int compare(JSONObject x, JSONObject y) {
                return Integer.compare(x.optInt("ep", 0), y.optInt("ep", 0));
            }
        });
        List<Integer> pick = new ArrayList<Integer>();
        int cap = onlineMax();          // ★ v1.3.306：在线内容走「在线数」（不再走本地数）
        int firstEp = 0;
        String firstTitle = "";
        synchronized (tracks) {
            List<String[]> keep = localTracksSnapshot();
            tracks.clear();
            tracks.addAll(keep);
            for (int i = 0; i < lst.size() && pick.size() < cap; i++) {
                JSONObject e = lst.get(i);
                int ep = e.optInt("ep", 0);
                if (startEp > 0 && ep > 0 && ep < startEp) {
                    continue;
                }
                String title = e.optString("title", "");
                /* ★ 1.3.333：服务端 v1.7.9 起缺集行只输出「第 N 集」，推到播放队列后
                   整屏都是「第 N 集」，分不清是哪本书。占位/纯集号行补上书名再入列
                   （真集本来就带书名，不动）；队列、播放页、语音播报同时受益。
                   ★ 1.3.334：书名用短名 —— 缓存全名「仙逆丨多人剧丨…丨完结」太长，
                   列表里看不到集数（用户实测反馈）。 */
                if (Prog.isDirtyEpTitle(title) && book != null && book.length() > 0) {
                    String sbook = Prog.shortBook(book);
                    title = "《" + sbook + "》" + (title.trim().length() > 0
                            ? title.trim() : ("第" + ep + "集"));
                }
                String artist = e.optString("artist", "");
                String source = e.optString("source", "kw");
                String rid = e.optString("rid", "");
                String url;
                if (rid.length() > 0) {
                    url = b + "/r/" + source + "/" + rid
                            + "?title=" + enc(title) + "&artist=" + enc(artist);
                } else if (album.length() > 0) {
                    /* ★ v1.3.304：未绑定集不丢行 —— 占位 URL 播到才解析（player /r/prog
                       → device prog_resolve 302）。丢行会让队列第N集断档，
                       上一集/下一集定位全错位（实测 671 点上一集跳 677、队列 630/651/665）。 */
                    url = "http://127.0.0.1:8080/r/prog?aid=" + enc(album) + "&ep=" + ep
                            + "&title=" + enc(title) + "&artist=" + enc(artist);
                } else {
                    continue;
                }
                tracks.add(new String[]{title, artist, "", url, book, "", "", "program"});
                pick.add(Integer.valueOf(tracks.size() - 1));
                if (firstEp == 0) {
                    firstEp = ep;
                    firstTitle = title;
                }
            }
        }
        if (pick.size() == 0) {
            return "ERR 这一本书一集地址都拿不到";
        }
        int n = pushSongs("ab_play", pick);
        if (n <= 0) {
            return null;
        }
        pushed = true;
        kickPlay("ab_play", startDelay(n), 2);
        int lo = 0, hi = 0;
        for (int i = 0; i < lst.size(); i++) {
            int x = lst.get(i).optInt("ep", 0);
            if (x > 0) {
                if (lo == 0 || x < lo) {
                    lo = x;
                }
                if (x > hi) {
                    hi = x;
                }
            }
        }
        // ★ 钉死 loader/book 状态到本书（abNext/abPrev/resume 都读 ab_loader_book 优先）
        abLoaderSave(book, album, src, 1, abLoaderSize(), total, lo, hi);
        abSpPut("ab_book", book);
        abSpPut("ab_ep", String.valueOf(firstEp));
        abSpPut("ab_title", firstTitle);
        StringBuilder sb = new StringBuilder("OK 已把《").append(book).append("》第")
                .append(firstEp).append("集起的 ").append(n).append(" 集排进播放列表，正在播第")
                .append(firstEp).append("集：").append(firstTitle);
        if (total > 0 && total != n) {
            sb.append("（全书").append(total).append("集，本次排到第").append(firstEp + n - 1).append("集）");
        }
        sb.append("。播完一集会自动连播下一集；想跳集就说「播放第N集」。");
        return sb.toString();
    }

    /**
     * v1.3.282：上一集专用 —— 整批入列（**不按 startEp 过滤**，批首的上一集
     * 能把之前的 ≤50 集整批载回来），起播定位到 playEp 那首（= 列表最底部）：
     * 推完批后转发 player 本地 play_at 跳到它。返回 null = 播放通道推不动。
     */
    private static String abQueueBookPrev(String book, String album, int total, JSONArray eps,
                                          int playEp, String what) {
        String b = nasBase();
        if (b == null) {
            return "ERR 音乐源没配（在线节目拿不到地址）";
        }
        List<Integer> pick = new ArrayList<Integer>();
        int cap = onlineMax();          // ★ v1.3.306：在线内容走「在线数」（不再走本地数）
        int pos0 = -1;          // playEp 在本批内的 0-based 位置
        String playTitle = "";
        synchronized (tracks) {
            List<String[]> keep = localTracksSnapshot();
            tracks.clear();
            tracks.addAll(keep);
            for (int i = 0; i < eps.length() && pick.size() < cap; i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                int ep = e.optInt("ep", 0);
                if (ep <= 0) {
                    continue;
                }
                String title = e.optString("title", "");
                String artist = e.optString("artist", "");
                String source = e.optString("source", "kw");
                String rid = e.optString("rid", "");
                String url = e.optString("url", "").trim();     // ★ v1.3.325：本地库直链
                boolean isLocal = url.length() > 0;             // ★ v1.3.328：下面 url 会被改写，先记下来
                if (ep == playEp && rid.length() > 0) {
                    // 起播那首正式取链（马上要出声的那首）
                    url = onlineUrl(title, artist, source, rid, 8000);
                }
                if (url.length() == 0 && rid.length() > 0) {
                    url = b + "/r/" + source + "/" + rid
                            + "?title=" + enc(title) + "&artist=" + enc(artist);
                }
                if (url.length() == 0 && album.length() > 0) {
                    /* ★ v1.3.304：未绑定集占位 URL，播到才解析 —— 不丢行。
                       之前直接 continue 会让队列断档 + 起播定位行（playEp）找不到
                       ⇒ pos0=-1 不 play_at ⇒ 引擎从批首乱播（点 671 上一集跳 677 的根因）。 */
                    url = "http://127.0.0.1:8080/r/prog?aid=" + enc(album) + "&ep=" + ep
                            + "&title=" + enc(title) + "&artist=" + enc(artist);
                }
                if (url.length() == 0) {
                    continue;
                }
                if (ep == playEp) {
                    pos0 = pick.size();
                    playTitle = title;
                }
                tracks.add(new String[]{abEpTitle(ep, title, isLocal), artist, "", url, book, "", "",
                        isLocal ? "abl" : "program"});
                pick.add(Integer.valueOf(tracks.size() - 1));
            }
        }
        if (pick.size() == 0) {
            return "ERR 这一本书一集地址都拿不到";
        }
        int n = pushSongs("ab_play", pick);
        if (n <= 0) {
            return null;
        }
        pushed = true;
        kickPlay("ab_play", startDelay(n), 2);
        if (pos0 < 0) {
            /* ★ v1.3.304：起播集没进列表（数据缺行）时兜底定位到列表最底部 ——
               批尾=最接近目标集的那集，绝不留在批首乱播。 */
            pos0 = n - 1;
            Log.w(TAG, "abQueueBookPrev playEp=" + playEp + " not in batch => tail pos=" + pos0);
        }
        if (pos0 > 0) {
            // 起播定位：转发 player 本地跳（列表顺序不动），落在列表最底部那集
            try {
                JSONObject pa = new JSONObject();
                pa.put("index", String.valueOf(pos0 + 1));
                String pr = dispatch("play_at", pa);
                Log.i(TAG, "abQueueBookPrev pos=" + (pos0 + 1) + "/" + n
                        + " ep=" + playEp + " => " + pr);
            } catch (Throwable t) {
                Log.i(TAG, "abQueueBookPrev play_at: " + t);
            }
        }
        abSpPut("ab_book", book);
        abSpPut("ab_ep", String.valueOf(playEp));
        abSpPut("ab_title", playTitle);
        StringBuilder sb = new StringBuilder("OK ").append(what == null ? "" : what)
                .append("已把《").append(book).append("》之前的 ").append(n)
                .append(" 集载回播放列表，正在播第").append(playEp).append("集：")
                .append(playTitle);
        if (total > 0) {
            sb.append("（全书").append(total).append("集）");
        }
        sb.append("。播完一集会自动连播下一集。");
        return sb.toString();
    }

    /** 单曲兜底（v1.3.263）：队列推不动时才走 —— ★ 必须把 title/artist 传给
     *  playUrl，否则 music_info.title 死写「网络音频」，页面也认不出节目。 */
    private static String abStart(String book, JSONObject e) {
        String url = e.optString("url", "").trim();
        if (url.length() == 0) {
            return "ERR 这一集没有可播地址";
        }
        JSONObject a = new JSONObject();
        try {
            a.put("title", e.optString("title", ""));
            a.put("artist", e.optString("artist", ""));
        } catch (Throwable t) { /* 带不上名字而已，不致命 */ }
        String r = playUrl(url, a);
        if (r == null) {
            return "ERR 播放通道不可用";
        }
        if (r.startsWith("ERR")) {
            return r;
        }
        abSpPut("ab_book", book);
        abSpPut("ab_ep", String.valueOf(e.optInt("ep", 0)));
        abSpPut("ab_title", e.optString("title", ""));
        int ep = e.optInt("ep", 0);
        return "OK 已开始播放《" + book + "》" + (ep > 0 ? "第" + ep + "集" : "")
                + "：" + e.optString("title", "") + "（单集兜底模式，未入列）";
    }

    private static String abSearch(JSONObject args) {
        String q = args == null ? "" : args.optString("keyword", args.optString("q", "")).trim();
        if (q.length() == 0) {
            return "ERR 缺少 keyword";
        }
        // v1.3.265：把用户点名的主播拼进查询，NAS 才能在同名多版本里挑对人（否则集数多的版本会被优先选中）
        String narArg = args == null ? "" : args.optString("narrator", "").trim();
        if (narArg.length() > 0 && q.indexOf(narArg) < 0) {
            q = narArg + " " + q;
        }
        try {
            String b = nasBase();
            if (b == null) {
                return "ERR 还没配置音乐服务器地址（indexUrl）";
            }
            String js = httpGet(b + "/api/ab/search?q=" + URLEncoder.encode(q, "UTF-8") + "&n=100", 90000, 16 * 1024 * 1024, 90000);
            if (js == null || js.length() == 0) {
                return "ERR 有声书搜索没应答";
            }
            JSONArray books = new JSONObject(js).optJSONArray("books");
            if (books == null || books.length() == 0) {
                return "没搜到「" + q + "」的有声书 —— 可试试直接报书名，或带上「有声/评书/相声」等词再搜";
            }
            StringBuilder sb = new StringBuilder("找到 ").append(books.length()).append(" 本：");
            int show = Math.min(books.length(), 8);
            for (int i = 0; i < show; i++) {
                JSONObject bk = books.optJSONObject(i);
                if (bk == null) {
                    continue;
                }
                sb.append(i == 0 ? " " : "；")
                  .append("《").append(bk.optString("book", "?")).append("》");
                String nar = bk.optString("narrator", "");
                if (nar.length() > 0) {
                    sb.append("播音").append(nar).append(" ");
                }
                sb.append("共").append(bk.optInt("count", 0)).append("集");
            }
            if (books.length() > show) {
                sb.append("…（还有").append(books.length() - show).append("本没列出）");
            }
            sb.append("。要播哪本就调 ab_play 传 book=书名，用户说了第几集就传 ep。");
            return sb.toString();
        } catch (Throwable t) {
            return "ERR 有声书搜索失败：" + t;
        }
    }

    /** ★ v1.3.275c：在指定专辑里定位目标集（含页游走兜底）。episodes 里必须拿得到
       target（target<=0 时只要有一页就算），拿不到返回 null。 */
    private static JSONObject abLocateEpisode(String q, String albumId, int size, int target) {
        JSONObject d = abEpisodes(q, albumId, 0, size, target);
        if (d == null) {
            return null;
        }
        JSONArray eps = d.optJSONArray("episodes");
        if (eps == null || eps.length() == 0) {
            return null;
        }
        if (target <= 0 || abPick(eps, target) != null) {
            return d;
        }
        /* 页游走：集号有空档（2101 集的表 ep 最大 2113）时 NAS 的 ep→页算位可能
           偏一页；沿页走把目标集找回来（每页一次本地 HTTP，NAS 5 分钟缓存 ⇒ 便宜）。 */
        int pg = d.optInt("page", 1);
        for (int i = 0; i < 8; i++) {
            int mn = Integer.MAX_VALUE, mx = 0;
            for (int j = 0; j < eps.length(); j++) {
                JSONObject e = eps.optJSONObject(j);
                if (e == null) {
                    continue;
                }
                int x = e.optInt("ep", 0);
                if (x > 0) {
                    if (x < mn) {
                        mn = x;
                    }
                    if (x > mx) {
                        mx = x;
                    }
                }
            }
            if (mx == 0) {
                break;
            }
            int npg = (target > mx) ? pg + 1 : (target < mn ? pg - 1 : 0);
            if (npg <= 0 || npg == pg) {
                break;
            }
            pg = npg;
            JSONObject d2 = abEpisodes(q, albumId, pg, size, 0);
            if (d2 == null) {
                break;
            }
            JSONArray e2 = d2.optJSONArray("episodes");
            if (e2 == null || e2.length() == 0) {
                break;
            }
            d = d2;
            eps = e2;
            if (abPick(eps, target) != null) {
                return d;
            }
        }
        return null;
    }

    /** ★ v1.3.275c：同名多版本候选专辑。NAS 搜书结果不稳定（同一查询两次返回的版本集
       都不同），所以先「主播 书名」搜、主播没命中再裸书名搜一遍；主播命中的排前面。
       返回 albumId 列表（保序去重）。 */
    private static List<String> abAlbumCandidates(String bq, String nar) {
        List<String> narHit = new ArrayList<String>();
        List<String> other = new ArrayList<String>();
        try {
            String b = nasBase();
            if (b == null) {
                return other;
            }
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
            String nq = Fav.key(bq, "");
            for (int t = 0; t < 2; t++) {
                String qs = (t == 0 && nar.length() > 0) ? (nar + " " + bq) : bq;
                String js = httpGet(b + "/api/ab/search?q=" + URLEncoder.encode(qs, "UTF-8") + "&n=20",
                        20000, 4 * 1024 * 1024, 20000);
                if (js != null && js.length() > 0) {
                    JSONArray books = new JSONObject(js).optJSONArray("books");
                    if (books != null) {
                        for (int i = 0; i < books.length(); i++) {
                            JSONObject bk = books.optJSONObject(i);
                            if (bk == null) {
                                continue;
                            }
                            String aid = bk.optString("albumId", "");
                            String nb = bk.optString("book", "");
                            if (aid.length() == 0 || nb.length() == 0) {
                                continue;
                            }
                            String nn = Fav.key(nb, "");
                            if (nq.indexOf(nn) < 0 && nn.indexOf(nq) < 0) {
                                continue;                       // 书名对不上，不要
                            }
                            if (!seen.add(aid)) {
                                continue;
                            }
                            String bnar = bk.optString("narrator", "");
                            boolean hit = nar.length() > 0 && bnar.length() > 0
                                    && (bnar.indexOf(nar) >= 0 || nar.indexOf(bnar) >= 0);
                            if (hit) {
                                narHit.add(aid);
                            } else {
                                other.add(aid);
                            }
                        }
                    }
                }
                if (narHit.size() > 0) {
                    break;                                      // 主播已命中，不用裸书名兜底
                }
            }
        } catch (Throwable t) {
            /* 尽力而为：拿多少算多少 */
        }
        narHit.addAll(other);
        return narHit;
    }

    private static String abPlay(JSONObject args) {
        String q = args == null ? "" : args.optString("book", args.optString("q", "")).trim();
        int ep = abInt(args, "ep");
        int target = ep > 0 ? ep : 0;
        String albumArg = args == null ? "" : args.optString("albumId", "").trim();
        if (q.length() == 0) {
            return "ERR 缺少 book（书名）";
        }
        /* ★ 1.3.360：没说集数 ⇒ 接着上次听，别从第一行/第一集重头。
           实测（11-20 20:11）：用户只说「凡人修仙传」（没带「继续」），模型按工具描述
           调 ab_play 不传 ep ⇒ 本地命中「第0集 预告」⇒ 播了预告，用户以为"续播记忆丢了"；
           更糟的是 ep=0 被上报侧整条丢弃（ProgProgress: ep<=0 ⇒ return false），
           服务端连一条记录都没有。口径：**没有集号就接上次听到的那一集**，
           从没听过的书才落到第一集（abPick 会跳过 ep<=0 的花絮/预告）。
           想从头听请说「从第1集播」（模型会带 ep=1，不受这里影响）。 */
        int resumePos = 0;
        if (target <= 0) {
            int[] lh = lastHeardOf(q);
            if (lh[0] > 0) {
                target = lh[0];
                resumePos = lh[1];
                Log.i(TAG, "abPlay 没给集号 → 接上次进度 ep=" + target + " pos=" + resumePos);
            }
        }
        String nar = args == null ? "" : args.optString("narrator", "").trim();
        /* ★ v1.3.275c：同名多版本盲挑根修。《凡人修仙传》有 7 个版本（光合积木/关彦之/
           微媒有道…），模型 ab_play 常不带 albumId ⇒ NAS 在同名版本里盲挑，挑错版本就
           「没有第N集」（小智嘴里的集数来自 ab_search 看到的版本，实际发配到别家）。
           而 NAS 搜书结果还不稳定（同一查询两次返回的版本集都不同），单次「搜书挑主播」
           也会漏。⇒ 候选专辑**逐个试到「真的包含目标集」为止**：
           显式 albumId → loader（同一本书，仅未点主播时，连听/跳集必中）→
           搜书候选（主播命中优先，主播 query 没命中再裸书名搜）。
           episodes 的 q 用带主播的原话（与 ab_search 同口径），候选书名匹配用裸书名。 */
        String bq = q;
        if (nar.length() > 0 && q.indexOf(nar) >= 0) {
            bq = q.replace(nar, " ").trim();
        }
        /* ★ v1.3.325：本地书库优先 —— 本地有这本就播本地文件，命中不了才走在线书源。
           为什么不看 albumArg：ab_search 给的是在线专辑号，模型常直接带下来；用户要的是
           「库里有就别上网」，所以本地命中压过一切在线专辑。target 集本地没有（书不全）
           就自然落到下面在线链路，行为与以前一致。 */
        try {
            String b0 = nasBase();
            if (b0 != null) {
                String lid = albumArg.startsWith("abl:") ? albumArg.substring(4) : "";
                /* ★ v1.3.357：本地书**书身份**（子书名）优先取 ablFind/collPick 算出来的
                   那一份 —— 它是 device 侧最权威的判断（合集已按 hitSub/lastSub 落到具体一部），
                   老服务端（≤1.7.47）集表回的还是父书名，不能被它盖掉。 */
                String lname = "";
                if (lid.length() == 0) {
                    JSONObject lbook = null;
                    /* ★ v1.3.355：先拿「用户原话」试一次。
                       模型调 ab_play 常把书名精简成「三体」，「死神永生」只留在原话里；
                       原话命中且**认出了分部**才采用，否则回落到 bq 的常规匹配
                       （纯「播放三体」时原话没有分部，行为与以前完全一致）。 */
                    String uq = recentUtterance();
                    if (uq.length() > 0) {
                        JSONObject h = ablFind(b0, uq);
                        if (h != null && h.optString("hitSub", "").length() > 0) {
                            lbook = h;
                            Log.i(TAG, "abPlay 原话补部名: " + uq + " → hitSub="
                                    + h.optString("hitSub", ""));
                        }
                    }
                    if (lbook == null) {
                        lbook = ablFind(b0, bq);
                    }
                    if (lbook != null) {
                        lid = lbook.optString("id", "").trim();
                        lname = lbook.optString("name", "").trim();
                    }
                }
                if (lid.length() > 0) {
                    JSONObject ldoc = ablEpsAsAb(b0, lid, 1,
                            Math.min(Math.max(abLoaderSize(), 200), 500), target);
                    JSONArray larr = ldoc == null ? null : ldoc.optJSONArray("episodes");
                    JSONObject lep0 = null;
                    if (larr != null && larr.length() > 0) {
                        /* ★ 1.3.360：无集号也走 abPick —— 它本来就跳过 ep<=0
                           （「第0集 预告」这类花絮不是正片）。老代码无集号时直接
                           larr[0]，绕过了这层保护：本地《凡人修仙传》第一行正是
                           「第0集 预告」，用户只说「凡人修仙传」就播了预告。
                           全表都没有正集号时才退回第一行（真有书只写集名不写集号）。 */
                        lep0 = abPick(larr, target);
                        if (lep0 == null && target <= 0) {
                            /* 只为「整张表都没有正集号」（有的书集名只写「引子」「玉佩」）兜底。
                               target>0 时本地取不到该集必须保持 null —— 下面会让位给在线链路，
                               本地残卷（3 集）续播第 14 集正是靠这条走在线。 */
                            lep0 = larr.optJSONObject(0);
                        }
                    }
                    if (lep0 != null) {
                        /* ★ v1.3.357：书名 = **子书名**（「黑暗森林Ⅱ」），不是合集名（「三体」）。
                           取值顺序：device collPick 认出的那部 → 服务端集表 book/name
                           （v1.7.48 起也是子书名）→ 用户原话 q。三体这种合集三部曲
                           从此各自一条书级记录，不再糊成「三体」。
                           老代码只读不存在的 `book` 字段 ⇒ 一律退回 q，
                           用户说「三体死神永生」就会把书级记录写成「三体死神永生」。 */
                        String lbk = lname;
                        if (lbk.length() == 0) {
                            lbk = ldoc.optString("book", "").trim();
                        }
                        if (lbk.length() == 0) {
                            lbk = ldoc.optString("name", "").trim();
                        }
                        if (lbk.length() == 0) {
                            lbk = q;
                        }
                        int ltot = ldoc.optInt("total", 0);
                        abLoaderSave(lbk, "abl:" + lid, "abl", 1,
                                ldoc.optInt("size", 200), ltot, 0, 0);
                        String lhint = resumePos > 0
                                ? ("（本地书库，接着上次第 " + lep0.optInt("ep", 0) + " 集）")
                                : "（本地书库）";
                        String lr = abQueueBook(lbk, ltot,
                                ldoc.optJSONArray("episodes"), lep0.optInt("ep", 0), lhint);
                        if (lr != null) {
                            if (resumePos > 0) {
                                askPlayerResumeSeek(resumePos, lep0.optInt("ep", 0));
                            }
                            Log.i(TAG, "abPlay local hit id=" + lid + " book=" + lbk
                                    + " ep=" + lep0.optInt("ep", 0)
                                    + (resumePos > 0 ? (" pos=" + resumePos) : ""));
                            return lr;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "abPlay local: " + t);
        }
        int size = abLoaderSize();
        List<String> alb = new ArrayList<String>();
        if (albumArg.length() > 0) {
            alb.add(albumArg);
        }
        String lb = abSpGet("ab_loader_book", "");
        String la = abSpGet("ab_loader_album", "");
        String bqN = abBookOnly(bq);
        boolean loaderSame = lb.length() > 0 && la.length() > 0
                && (bqN.indexOf(abBookOnly(lb)) >= 0 || abBookOnly(lb).indexOf(bqN) >= 0);
        /* ★ v1.3.302：安全网收紧 —— 只有「归一化精确同名」才回退上一本，避免子串重叠
           把别的书误拉回来（用户怀疑这道网导致跳书）。同名系列（番外/前传）若 NAS 搜得到
           自然走候选；搜不到就老实报网络异常，不再拿上一本凑数。 */
        boolean loaderExact = lb.length() > 0 && la.length() > 0
                && bqN.equals(abBookOnly(lb));
        /* ★ v1.3.276：只说集数不说主播时，单 loader 槽会被交叉听书冲掉（听《凡人修仙传》
           几集再回来说「播第50集」，loader 已是别的书 ⇒ 退回 NAS 盲挑 ⇒ 大概率换版本）；
           xiaozhi 会话过期后模型也不带 narrator（「马上说能跳对、过几分钟就不行」的根因）。
           ⇒ 这本书只要播过，就按 ab_ver_hist 历史接回上次的版本。 */
        String histAlbum = "";
        if (nar.length() == 0) {
            if (loaderSame) {
                if (!alb.contains(la)) {
                    alb.add(la);                                // 连听/跳集：上次的版本优先
                }
            } else {
                histAlbum = abHistFind(bq);
                if (histAlbum.length() > 0 && !alb.contains(histAlbum)) {
                    alb.add(histAlbum);                         // 这本书上次听的版本优先
                }
            }
        }
        Log.i(TAG, "abPlay q=" + q + " nar=" + nar + " loader=" + lb + "/" + la
                + " hist=" + histAlbum + " cand=" + alb);
        if (albumArg.length() == 0) {
            for (String aid : abAlbumCandidates(bq, nar)) {
                if (!alb.contains(aid)) {
                    alb.add(aid);
                }
            }
            if (alb.isEmpty() && loaderExact) {
                alb.add(la);                                    // 搜书全挂且确是同一本：至少别换版本
            }
        }
        JSONObject d = null;
        for (int ci = 0; ci < alb.size() && d == null; ci++) {
            d = abLocateEpisode(q, alb.get(ci), size, target);
        }
        /* ★ v1.3.301：砍掉「裸 q 让 NAS 盲挑」兜底 —— 它会在没精确匹配时让 NAS 自作主张
           挑一本同名书（多半不是用户要的那版/那本），表现为「集号对了但书播错了」。
           现在：候选专辑里只要有一本真含目标集就播那本；一本都含不上就老实报网络异常，
           绝不拿别的书凑数。 */
        if (d == null) {
            if (target <= 0) {
                return "网络好像出了点问题，请稍后重试";
            }
            return "网络好像出了点问题，请稍后重试（《" + q + "》第" + ep + "集没匹配上）";
        }
        Log.i(TAG, "abPlay hit album=" + jstr(d, "albumId") + " book=" + jstr(d, "book")
                + " ep=" + target);
        JSONArray eps = d.optJSONArray("episodes");
        JSONObject e0 = abPick(eps, target);
        String book = d.optString("book", q);
        String album = d.optString("albumId", alb.isEmpty() ? "" : alb.get(0));
        int[] rng = abPageRange(d);
        abLoaderSave(book, album, d.optString("source", "kw"),
                d.optInt("page", 1), size, d.optInt("total", 0), rng[0], rng[1]);
        // 从 target 集起把本批入列，首集正式取链
        String ohint = "";
        if (ep <= 0) {
            ohint = resumePos > 0
                    ? ("（接着上次第 " + e0.optInt("ep", 0) + " 集）")
                    : "（没说集数，从第1集开始）";
        }
        String r = abQueueBook(book, d.optInt("total", 0), eps, e0.optInt("ep", 0), ohint);
        if (r == null) {
            String rs = abStart(book, e0);
            if (resumePos > 0 && rs != null && rs.startsWith("OK")) {
                askPlayerResumeSeek(resumePos, e0.optInt("ep", 0));
            }
            return rs;
        }
        if (resumePos > 0 && r.startsWith("OK")) {
            askPlayerResumeSeek(resumePos, e0.optInt("ep", 0));
        }
        return r + batchHint(d);
    }

    /** v1.3.280：player 真队列正在播的节目行 → {书名(可能空), 集号}；非节目/解析不出返 null。
        书名来自 device tracks 里同 url 行的 slot[4]（device 推的批才有记）。 */
    /** ★ 1.3.348：队列项 → 书名。**只读队列项自带的身份，绝不猜。**
     *
     * <p>事故（实测）：R1 播本地节目《道诡异仙》第 2 集，播放记录被写成《遮天》。
     * 老实现只走下面第 ③ 步（拿 URL 去 device 自己的 tracks 表反查），本地节目是
     * player 入的列、tracks 里没有 ⇒ 书名空 ⇒ 上报侧退回全局 SP 的
     * {@code ab_loader_book}（上一本在线书「遮天」）⇒ 张冠李戴。</p>
     *
     * <p>现在的来源顺序，每一档都是**权威数据**，没有一档是「上一次的残留」：</p>
     * <ol>
     *   <li>album —— 入队方盖的章。player 入本地节目列时写书名
     *       （见 {@code PCtl.newSong(..., book)}），本工程里 album 一直是书名位；</li>
     *   <li>本地节目（itemId {@code abl_…} 或 URL 走 {@code /m/}）退用 artist ——
     *       老 player 入的队列没有 album，而 {@code /api/ablplay} 的 artist 就是
     *       「书名」或「书名 · 副标题」；</li>
     *   <li>tracks 表按 URL 反查 —— device 自己推的在线书队列（表是它写的）；</li>
     *   <li>都没有 ⇒ 返回空串，调用方**不上报**。</li>
     * </ol>
     */
    private static String queueItemBook(JSONObject it, String itemId, String url) {
        /* ★ 1.3.350：album 位放**所有**入队方盖的章，先看它 —— device 推书时
           tracks[4]=书名，player 入本地节目时 newSong(..., book) 也写这里。
           以前只在「本地节目」分支读 album，在线书（device 推的）被漏掉，
           退到 URL 反查又因 dev 参数比对失败 ⇒ 《仙逆》一条记录都没有。 */
        String al = it.optString("album", "").trim();
        if (al.length() > 0) {
            return al;
        }
        boolean local = (itemId != null && itemId.startsWith("abl_"))
                || (url != null && url.indexOf("/m/") >= 0);
        if (local) {
            String art = it.optString("artist", "").trim();
            /* ★ v1.3.357：player /api/ablplay 的 artist 是「合集名 · 子书名」
               （WebSrv: artist = sub ? book + " · " + sub : book）。书名在**后面** ——
               用户口径「后面的名字才是书名，前者只是系列」。以前取前段会把
               「三体 · 黑暗森林Ⅱ」记成「三体」，三部曲又糊成一本。 */
            int sp = art.indexOf(" · ");
            return (sp > 0) ? art.substring(sp + 3).trim() : art;
        }
        if (url != null && url.length() > 0) {
            String k = mediaKeyOf(url);
            if (k.length() > 0) {
                synchronized (tracks) {
                    for (String[] r : tracks) {
                        /* ★ 1.3.350：不能全等比 URL —— 队列 URL 被 withDevIdentity 追加了
                           dev/devName，player 拉流时又加 live=1，tracks[3] 是原始地址。
                           只比「媒体身份」：去掉协议/host/query 后的路径
                           （/r/<src>/<rid> 或 /m/…），这才是同一集。 */
                        if (r != null && r.length > 3 && k.equals(mediaKeyOf(r[3]))) {
                            return (r.length > 4 && r[4] != null) ? r[4].trim() : "";
                        }
                    }
                }
            }
        }
        return "";
    }

    /** ★ 1.3.350：URL 的「媒体身份」—— 去协议、去 host、去 query，只留路径。
     *  {@code http://192.168.2.14:8100/r/kw/228593095?x=1&dev=…&live=1}
     *  ⇒ {@code /r/kw/228593095}。用于「同一集」的比较，不用来判断播放。 */
    private static String mediaKeyOf(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        int sch = s.indexOf("://");
        if (sch >= 0) {
            int sl = s.indexOf('/', sch + 3);
            s = (sl >= 0) ? s.substring(sl) : "";
        }
        return s;
    }

    private static String[] liveNowRow() {
        JSONObject p = playerLiveQueue(0);
        if (p == null) {
            return null;
        }
        int cur = p.optInt("cur", -1);
        JSONArray l = p.optJSONArray("list");
        if (cur < 0 || l == null) {
            return null;
        }
        for (int i = 0; i < l.length(); i++) {
            JSONObject it = l.optJSONObject(i);
            if (it == null || it.optInt("n", -1) != cur + 1) {
                continue;
            }
            String t = it.optString("title", "");
            if (!abLikely(t)) {
                return null;
            }
            String itemId = it.optString("itemId", "");
            int ep = abEpNo(t);
            /* ★ 1.3.348：本地节目的集号在 itemId 尾段（abl_&lt;aid&gt;_&lt;ep&gt;，player 入队时拼的），
               比标题正则可靠 —— 标题可能叫「序章」压根没有集号。 */
            if (itemId.startsWith("abl_")) {
                int k = itemId.lastIndexOf('_');
                if (k >= 0 && k + 1 < itemId.length()) {
                    try {
                        int ie = Integer.parseInt(itemId.substring(k + 1));
                        if (ie > 0) {
                            ep = ie;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (ep <= 0) {
                return null;
            }
            String url = it.optString("url", "");
            String book = queueItemBook(it, itemId, url);
            Log.i(TAG, "abNext live cur=" + cur + " book=" + book + " ep=" + ep
                    + " title=" + t + " itemId=" + itemId);
            /* ★ v1.3.357：第三位带 URL —— 调用方据此判断「本地书」，
               本地书名是服务端本地库的子书名（干净），不该再过在线书那套 shortBook。 */
            return new String[]{book, String.valueOf(ep), url};
        }
        return null;
    }

    /** v1.3.280：从标题解析集号（「第50集」「第三回」…），没有返 0 */
    private static int abEpNo(String title) {
        if (title == null || title.length() == 0) {
            return 0;
        }
        java.util.regex.Matcher m = AB_EP_RE.matcher(title);
        if (!m.find()) {
            return 0;
        }
        String g = m.group();
        java.util.regex.Matcher d = java.util.regex.Pattern.compile("[0-9]+").matcher(g);
        if (d.find()) {
            try {
                return Integer.parseInt(d.group());
            } catch (Throwable t) {
                return 0;
            }
        }
        return abCnNum(g.replaceAll("[第集回话章\\s　]", ""));
    }

    /** v1.3.280：中文数字（一~九千九百九十九）转 int，认不出返 0 */
    private static int abCnNum(String s) {
        if (s == null || s.length() == 0) {
            return 0;
        }
        String D = "零一二三四五六七八九";
        int total = 0, num = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = D.indexOf(c);
            if (idx > 0) {
                num = num * 10 + idx;
            } else if (c == '十') {
                total += (num == 0 ? 1 : num) * 10;
                num = 0;
            } else if (c == '百') {
                total += (num == 0 ? 1 : num) * 100;
                num = 0;
            } else if (c == '千') {
                total += (num == 0 ? 1 : num) * 1000;
                num = 0;
            } else if (c == '两') {
                num = num * 10 + 2;
            } else if (idx != 0) {
                return 0;
            }
        }
        return total + num;
    }

    /**
     * ★ v1.3.305：上一/下一集的「我的节目缓存」直通路径。
     *
     * <p>老路径（NAS 搜索分页）有两个实测雷：① 分页验证一失败就跌回过期 SP
     * ab_ep —— 残留 676 就把「700 的下一集」接成 677；② 不带 albumId 的搜索
     * 会混入多版本结果（实测队列被顶成 579~747 缺 119 集的乱页）。
     * 缓存在本地、全集在册（骨架行占位、播到才解析）、rid 已绑 ——
     * 直接以「live 集（兜底 Prog lastEp，绝不用 ab_ep）±1」整数定位，
     * 窗口取缓存 [lo,hi] 整批入列（占位行保队列连续），复用
     * {@link #abQueueProgLazy}/{@link #abQueueBookPrev} 推歌与钉状态。</p>
     *
     * @param prev true=上一集 false=下一集
     * @return null = 缓存没这本书/拿不到行（调用方走老 NAS 路径）
     */
    private static String abStepProg(boolean prev, String book, String album, String[] lr) {
        try {
            JSONObject pe = Prog.findByName(book);
            if (pe == null) {
                return null;
            }
            String aid = pe.optString("albumId", "").trim();
            if (aid.length() == 0) {
                return null;
            }
            JSONArray eps = Prog.getEps(aid);
            if (eps == null || eps.length() == 0) {
                return null;
            }
            /* 跨书保险：live 行有书名且与 loader 书对不上 ⇒ 交回老路径处理 */
            if (lr != null && lr[0] != null && lr[0].length() > 0) {
                boolean ok = lr[0].indexOf(book) >= 0 || book.indexOf(lr[0]) >= 0
                        || abBookOnly(book).indexOf(abBookOnly(lr[0])) >= 0;
                if (!ok) {
                    return null;
                }
            }
            /* 基准集：live 优先 → Prog lastEp（每次点播都更新，可靠）；
               绝不用 ab_ep（跨会话残留）。 */
            int cur = 0;
            if (lr != null) {
                try {
                    cur = Integer.parseInt(lr[1]);
                } catch (Throwable t) {
                    cur = 0;
                }
            }
            if (cur <= 0) {
                cur = pe.optInt("lastEp", 0);
            }
            if (cur <= 0) {
                return null;
            }
            int total = pe.optInt("epsTotal", 0);
            if (total <= 0) {
                total = pe.optInt("count", 0);
            }
            int target = prev ? cur - 1 : cur + 1;
            if (target < 1) {
                return "《" + book + "》已经是第一集了";
            }
            if (total > 0 && target > total) {
                return "《" + book + "》已经播到最后一集了（共" + total + "集）";
            }
            /* ★ v1.3.306：窗口大小走「在线数」（不再固定 50）——
               与点播入列量同一个口径：在线内容一律 online_max。 */
            int w = onlineMax();
            int lo = prev ? Math.max(1, target - (w - 1)) : target;
            int hi = prev ? target : target + (w - 1);
            /* 缓存行归一：按 ep 去重（实测混入过 12 行别版本重复行），
               同集号带 rid 的行优先；窗口外忽略。 */
            java.util.TreeMap<Integer, JSONObject> win = new java.util.TreeMap<Integer, JSONObject>();
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                int ep = e.optInt("ep", 0);
                if (ep < lo || ep > hi) {
                    continue;
                }
                JSONObject old = win.get(Integer.valueOf(ep));
                if (old == null || (old.optString("rid", "").trim().length() == 0
                        && e.optString("rid", "").trim().length() > 0)) {
                    win.put(Integer.valueOf(ep), e);
                }
            }
            if (win.size() == 0 || !win.containsKey(Integer.valueOf(target))) {
                return null;
            }
            /* 幂等护栏：目标集已在实时队列 ⇒ 直接跳行，不整批重推（同老路径）。 */
            JSONObject lq2 = playerLiveQueue(0);
            JSONArray ll2 = lq2 == null ? null : lq2.optJSONArray("list");
            for (int i2 = 0; ll2 != null && i2 < ll2.length(); i2++) {
                JSONObject it2 = ll2.optJSONObject(i2);
                if (it2 == null) {
                    continue;
                }
                String tt2 = jstr(it2, "title");
                if (abLikely(tt2) && abEpNo(tt2) == target) {
                    try {
                        int nn2 = it2.optInt("n", i2 + 1);
                        JSONObject pa2 = new JSONObject();
                        pa2.put("index", String.valueOf(nn2));
                        String pr2 = dispatch("play_at", pa2);
                        abSpPut("ab_ep", String.valueOf(target));
                        Log.i(TAG, "abStepProg idem jump ep=" + target + " n=" + nn2 + " => " + pr2);
                        return "OK " + (prev ? "上一集" : "下一集") + " —— " + tt2;
                    } catch (Throwable t2) {
                        Log.i(TAG, "abStepProg idem jump: " + t2);
                    }
                }
            }
            JSONArray arr = new JSONArray();
            for (JSONObject e : win.values()) {
                arr.put(e);
            }
            String bk = pe.optString("book", book).trim();
            String alb = aid;
            String src = pe.optString("source", "").trim();
            String r = prev
                    ? abQueueBookPrev(bk, alb, total, arr, target, "上一集 —— ")
                    : abQueueProgLazy(bk, alb, src, total, arr, target);
            Log.i(TAG, "abStepProg " + (prev ? "prev" : "next") + " cur=" + cur
                    + " target=" + target + " win=" + lo + "~" + hi + " => " + r);
            return r == null ? null : r;
        } catch (Throwable t) {
            Log.w(TAG, "abStepProg: " + t);
            return null;
        }
    }

    private static String abNext() {
        String book = abSpGet("ab_loader_book", abSpGet("ab_book", ""));
        if (book.length() == 0) {
            return "ERR 还没播过有声书 —— 先用 ab_play 指定书名";
        }
        String album = abSpGet("ab_loader_album", "");
        int spCur = 0;
        try {
            spCur = Integer.parseInt(abSpGet("ab_ep", "0"));
        } catch (Throwable t) {
            spCur = 0;
        }
        int page = abIntSP("ab_loader_page", 1);
        int pend = abIntSP("ab_loader_pend", 0);
        int size = abLoaderSize();
        int cur = spCur;
        boolean intra = (cur > 0 && pend > 0 && cur < pend);
        int targetPage = intra ? page : (cur <= 0 ? page : page + 1);
        JSONObject d = null;
        /* ★ v1.3.280：「下一集」以 player 正在播的这集为基准 —— 手动点/网页跳集后
           SP ab_ep 还是旧的，只看它会把「历史的下一集」接上来。
           认账条件：player 当前曲是节目行、集号解析得出，且书对得上
           （tracks 记了书名就必须匹配；没记（player 侧起的播）就靠集表里
           真有这一集来兜底）。 */
        String[] lr = liveNowRow();
        if (lr == null) {
            /* ★ v1.3.285：实时队列一时拿不到（player 串行 accept 忙、瞬时超时）
               就再试一次 —— 别急着跌回 SP 旧集号去续拉，旧集号会播错集
               （实测：网页点下一集，350 的下一集被拉成 302）。 */
            try { Thread.sleep(500); } catch (Throwable t0) { }
            lr = liveNowRow();
        }
        if (lr != null) {
            int lep = 0;
            try {
                lep = Integer.parseInt(lr[1]);
            } catch (Throwable t) {
                lep = 0;
            }
            String lbook = lr[0];
            boolean bookOk = lbook.length() == 0
                    || lbook.indexOf(book) >= 0 || book.indexOf(lbook) >= 0
                    || abBookOnly(book).indexOf(abBookOnly(lbook)) >= 0;
            /* ★ v1.3.283：队列快路径 —— 播放列表当前行的下一行就是下一集
               （节目行、集号=lep+1）时直接总线切歌：不重拉集表、不整批重推。
               背景：以前批中间点「下一集」也走 abQueueBook ⇒ 整个播放列表被重载、
               下一集变成队首；连点竞态（点 250 马上点下一集）live 行还是旧的，
               还会接错集（250→203）。批尾（队列里没有下一行）才落到下面的整批续拉。 */
            if (bookOk && lep > 0) {
                JSONObject lq = playerLiveQueue(0);
                JSONArray ll = lq == null ? null : lq.optJSONArray("list");
                int lcur = lq == null ? -1 : lq.optInt("cur", -1);
                String nt = "";
                for (int i = 0; ll != null && i < ll.length(); i++) {
                    JSONObject it = ll.optJSONObject(i);
                    if (it != null && it.optInt("n", -1) == lcur + 2) {
                        nt = jstr(it, "title");
                        break;
                    }
                }
                if (abLikely(nt) && abEpNo(nt) == lep + 1 && msg(6, null)) {
                    kickPlay("ab_next", 1200, 2);
                    /* ★ v1.3.285：快路径也把 ab_ep 推到最新 —— 以后万一走 SP
                       回退路径，旧集号也不至于差出几十集。 */
                    abSpPut("ab_ep", String.valueOf(lep + 1));
                    Log.i(TAG, "abNext fast bus cur=" + lep + " next=" + nt);
                    return "OK 下一集 —— " + nt;
                }
            }
            /* ★ v1.3.305：缓存直通 —— 在册的书不走 NAS 搜索分页
               （分页验证一失败就跌回过期 SP 集号 ⇒ 700 的下一集被接成 677）。 */
            {
                String pr = abStepProg(false, book, album, lr);
                if (pr != null) {
                    return pr;
                }
            }
            if (bookOk && lep > 0 && lep != spCur) {
                JSONObject ld = abEpisodes(book, album, 0, size, lep);
                JSONArray le = ld == null ? null : ld.optJSONArray("episodes");
                boolean has = false;
                for (int i = 0; le != null && i < le.length(); i++) {
                    JSONObject e = le.optJSONObject(i);
                    if (e != null && e.optInt("ep", 0) == lep) {
                        has = true;
                        break;
                    }
                }
                if (has) {
                    cur = lep;
                    intra = true;
                    d = ld;
                    targetPage = ld.optInt("page", page);
                    Log.i(TAG, "abNext live ep=" + lep + " => cur=" + cur
                            + " page=" + targetPage + " (sp ep=" + spCur + ")");
                }
            }
        }
        /* ★ v1.3.305：live 拿不到时基准集用 Prog lastEp 兜底 —— ab_ep 跨会话
           残留会把目标集算到几百集外（实测 700 的下一集被接成 677）。 */
        if (lr == null && cur == spCur) {
            JSONObject pe0 = Prog.findByName(book);
            if (pe0 != null) {
                int lep0 = pe0.optInt("lastEp", 0);
                if (lep0 > 0) {
                    cur = lep0;
                    Log.i(TAG, "abNext lastEp fallback cur=" + cur + " (sp ep=" + spCur + ")");
                }
            }
        }
        if (d == null) {
            // v1.3.280：回退路径也先按 cur 定位页（比 loader.page+1 猜得准），失败再走老猜测
            if (cur > 0) {
                d = abEpisodes(book, album, 0, size, cur);
            }
            if (d == null) {
                d = abEpFetch(book, album, targetPage, 0);
            }
        }
        if (d == null) {
            return "ERR 有声书数据源没应答";
        }
        JSONArray eps = d.optJSONArray("episodes");
        if (eps == null || eps.length() == 0) {
            return "ERR《" + book + "》的剧集列表空了";
        }
        // 下一集：本页取 >cur 最小；没有再往后翻一页（v1.3.280：手动跳到页尾/批尾也能续上）
        int next = 0;
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) {
                continue;
            }
            int x = e.optInt("ep", 0);
            if (x > cur && (next == 0 || x < next)) {
                next = x;
            }
        }
        if (next == 0) {
            JSONObject d2 = abEpFetch(book, album, d.optInt("page", targetPage) + 1, 0);
            JSONArray e2 = d2 == null ? null : d2.optJSONArray("episodes");
            for (int i = 0; e2 != null && i < e2.length(); i++) {
                JSONObject e = e2.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                int x = e.optInt("ep", 0);
                if (x > cur && (next == 0 || x < next)) {
                    next = x;
                }
            }
            if (next > 0 && d2 != null) {
                d = d2;
                eps = e2;
            }
        }
        if (next == 0) {
            return "《" + book + "》已经播到最后一集了（共" + d.optInt("total", eps.length()) + "集）";
        }
        /* ★ v1.3.284：幂等护栏 —— 目标集已经在 player 队列里（上一轮刚续拉完、
           或连点竞态第二发）就不再整批重推：两轮 push 重叠会在 player 里叠出
           混合队列（实测连点队尾 next，队列串到 212）。直接 play_at 跳到那一行。 */
        {
            JSONObject lq2 = playerLiveQueue(0);
            JSONArray ll2 = lq2 == null ? null : lq2.optJSONArray("list");
            for (int i2 = 0; ll2 != null && i2 < ll2.length(); i2++) {
                JSONObject it2 = ll2.optJSONObject(i2);
                if (it2 == null) {
                    continue;
                }
                String tt2 = jstr(it2, "title");
                if (abLikely(tt2) && abEpNo(tt2) == next) {
                    try {
                        int nn2 = it2.optInt("n", i2 + 1);
                        JSONObject pa2 = new JSONObject();
                        pa2.put("index", String.valueOf(nn2));
                        String pr2 = dispatch("play_at", pa2);
                        Log.i(TAG, "abNext idem jump ep=" + next + " n=" + nn2 + " => " + pr2);
                        return "OK 下一集 —— " + tt2;
                    } catch (Throwable t2) {
                        Log.i(TAG, "abNext idem jump: " + t2);
                    }
                }
            }
        }
        int[] rng = abPageRange(d);
        abLoaderSave(book, album, d.optString("source", "kw"),
                d.optInt("page", targetPage), size, d.optInt("total", 0), rng[0], rng[1]);
        JSONObject e0 = abPick(eps, next);
        String r = abQueueBook(d.optString("book", book), d.optInt("total", 0), eps, next, "下一集 —— ");
        if (r == null) {
            if (e0 == null) {
                return "ERR 播放通道不可用";
            }
            return abStart(d.optString("book", book), e0);
        }
        return r + batchHint(d);
    }

    /** v1.3.282：上一集 —— 以 player 正在播的集为基准（回退 SP ab_ep）。
        批首的上一集会把之前的整批（≤50 集）载回列表并起播定位到最底部那集。 */
    private static String abPrev() {
        String book = abSpGet("ab_loader_book", abSpGet("ab_book", ""));
        if (book.length() == 0) {
            return "ERR 还没播过有声书 —— 先用 ab_play 指定书名";
        }
        String album = abSpGet("ab_loader_album", "");
        int spCur = 0;
        try {
            spCur = Integer.parseInt(abSpGet("ab_ep", "0"));
        } catch (Throwable t) {
            spCur = 0;
        }
        int page = abIntSP("ab_loader_page", 1);
        int size = abLoaderSize();
        int cur = spCur;
        JSONObject d = null;
        String[] lr = liveNowRow();
        if (lr == null) {
            /* ★ v1.3.285：同 abNext —— 拿不到实时队列先重试一次，再考虑 SP。 */
            try { Thread.sleep(500); } catch (Throwable t0) { }
            lr = liveNowRow();
        }
        if (lr != null) {
            int lep = 0;
            try {
                lep = Integer.parseInt(lr[1]);
            } catch (Throwable t) {
                lep = 0;
            }
            String lbook = lr[0];
            boolean bookOk = lbook.length() == 0
                    || lbook.indexOf(book) >= 0 || book.indexOf(lbook) >= 0
                    || abBookOnly(book).indexOf(abBookOnly(lbook)) >= 0;
            /* ★ v1.3.283：队列快路径 —— 当前行上一行就是上一集（节目行、
               集号=lep-1）时总线切上一首，不整批载回；
               批首（上一集不在列表里）才落到下面的整批载回+定位队尾。 */
            if (bookOk && lep > 1) {
                JSONObject lq = playerLiveQueue(0);
                JSONArray ll = lq == null ? null : lq.optJSONArray("list");
                int lcur = lq == null ? -1 : lq.optInt("cur", -1);
                String pt = "";
                for (int i = 0; ll != null && i < ll.length(); i++) {
                    JSONObject it = ll.optJSONObject(i);
                    if (it != null && it.optInt("n", -1) == lcur) {
                        pt = jstr(it, "title");
                        break;
                    }
                }
                if (abLikely(pt) && abEpNo(pt) == lep - 1 && msg(5, null)) {
                    kickPlay("ab_prev", 1200, 2);
                    /* ★ v1.3.285：快路径同步回写 ab_ep（同 abNext）。 */
                    abSpPut("ab_ep", String.valueOf(lep - 1));
                    Log.i(TAG, "abPrev fast bus cur=" + lep + " prev=" + pt);
                    return "OK 上一集 —— " + pt;
                }
            }
            /* ★ v1.3.305：缓存直通 —— 在册的书不走 NAS 搜索分页
               （分页一失败就跌回过期 SP 集号，实测 671 的上一集被顶成 690）。 */
            {
                String pr = abStepProg(true, book, album, lr);
                if (pr != null) {
                    return pr;
                }
            }
            if (bookOk && lep > 0 && lep != spCur) {
                JSONObject ld = abEpisodes(book, album, 0, size, lep);
                JSONArray le = ld == null ? null : ld.optJSONArray("episodes");
                boolean has = false;
                for (int i = 0; le != null && i < le.length(); i++) {
                    JSONObject e = le.optJSONObject(i);
                    if (e != null && e.optInt("ep", 0) == lep) {
                        has = true;
                        break;
                    }
                }
                if (has) {
                    cur = lep;
                    d = ld;
                    Log.i(TAG, "abPrev live ep=" + lep + " (sp ep=" + spCur + ")");
                }
            }
        }
        /* ★ v1.3.305：live 拿不到时基准集用 Prog lastEp 兜底（同 abNext）——
           ab_ep 跨会话残留会把「上一集」算到几百集外（实测 671 被顶成 690）。 */
        if (lr == null && cur == spCur) {
            JSONObject pe0 = Prog.findByName(book);
            if (pe0 != null) {
                int lep0 = pe0.optInt("lastEp", 0);
                if (lep0 > 0) {
                    cur = lep0;
                    Log.i(TAG, "abPrev lastEp fallback cur=" + cur + " (sp ep=" + spCur + ")");
                }
            }
        }
        if (d == null) {
            if (cur > 0) {
                d = abEpisodes(book, album, 0, size, cur);
            }
            if (d == null) {
                d = abEpFetch(book, album, page, 0);
            }
        }
        if (d == null) {
            return "ERR 有声书数据源没应答";
        }
        JSONArray eps = d.optJSONArray("episodes");
        if (eps == null || eps.length() == 0) {
            return "ERR《" + book + "》的剧集列表空了";
        }
        // 上一集：本页取 <cur 最大；没有翻上一页（批首的上一集在前面那批的末尾）
        int prev = 0;
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) {
                continue;
            }
            int x = e.optInt("ep", 0);
            if (x > 0 && x < cur && x > prev) {
                prev = x;
            }
        }
        if (prev == 0) {
            int pg = d.optInt("page", page);
            if (pg > 1) {
                JSONObject d2 = abEpFetch(book, album, pg - 1, 0);
                JSONArray e2 = d2 == null ? null : d2.optJSONArray("episodes");
                for (int i = 0; e2 != null && i < e2.length(); i++) {
                    JSONObject e = e2.optJSONObject(i);
                    if (e == null) {
                        continue;
                    }
                    int x = e.optInt("ep", 0);
                    if (x > 0 && x < cur && x > prev) {
                        prev = x;
                    }
                }
                if (prev > 0 && d2 != null) {
                    d = d2;
                    eps = e2;
                }
            }
        }
        if (prev == 0) {
            return "《" + book + "》已经是第一集了";
        }
        /* ★ v1.3.284：幂等护栏（同 abNext）—— 目标集已在队列里就不整批载回，
           直接 play_at 跳到那一行。 */
        {
            JSONObject lq2 = playerLiveQueue(0);
            JSONArray ll2 = lq2 == null ? null : lq2.optJSONArray("list");
            for (int i2 = 0; ll2 != null && i2 < ll2.length(); i2++) {
                JSONObject it2 = ll2.optJSONObject(i2);
                if (it2 == null) {
                    continue;
                }
                String tt2 = jstr(it2, "title");
                if (abLikely(tt2) && abEpNo(tt2) == prev) {
                    try {
                        int nn2 = it2.optInt("n", i2 + 1);
                        JSONObject pa2 = new JSONObject();
                        pa2.put("index", String.valueOf(nn2));
                        String pr2 = dispatch("play_at", pa2);
                        Log.i(TAG, "abPrev idem jump ep=" + prev + " n=" + nn2 + " => " + pr2);
                        return "OK 上一集 —— " + tt2;
                    } catch (Throwable t2) {
                        Log.i(TAG, "abPrev idem jump: " + t2);
                    }
                }
            }
        }
        int[] rng = abPageRange(d);
        abLoaderSave(book, album, d.optString("source", "kw"),
                d.optInt("page", page), size, d.optInt("total", 0), rng[0], rng[1]);
        JSONObject e0 = abPick(eps, prev);
        /* ★ v1.3.282：整批载回（页首起）+ 起播定位到 prev（列表最底部）——
           不能从 prev 起排（那样只剩 1 集，后面也没有可连播的了）。 */
        String r = abQueueBookPrev(d.optString("book", book), album, d.optInt("total", 0),
                eps, prev, "上一集 —— ");
        if (r == null) {
            if (e0 == null) {
                return "ERR 播放通道不可用";
            }
            return abStart(d.optString("book", book), e0);
        }
        return r;
    }

    /** ★ v1.3.336 P1 书级共享：问服务端书级共享进度（GET /v1/resume?q=<书名>，2.5s 超时）。
     *  命中（books 共享表里有这本、ep>0）返回服务端 JSON；没进度/网络失败一律 null ——
     *  调用方拿 null 就走原本地记忆链路，行为与 1.3.335 完全一致。 */
    private static JSONObject srvResume(String book) {
        try {
            String b = nasBase();
            if (b == null || b.length() == 0 || book == null || book.trim().length() == 0) {
                Log.i(TAG, "srvResume 无 base/book（nas=" + b + " book=" + book + "）");
                return null;
            }
            // ★ 1.3.347：带上设备标识（MAC12）—— 服务端 v1.7.38 设备分组按 ?dev= 严格隔离
            //   进度；不带的话匿名读退回「全组最新」，分组隔离对 R1 就不生效了。
            String m = mac12();
            String s = httpGet(b + "/v1/resume?q="
                    + java.net.URLEncoder.encode(book.trim(), "UTF-8")
                    + (m.length() > 0 ? ("&dev=" + m) : ""), 2500);
            if (s == null || s.indexOf("\"found\":true") < 0) {
                /* ★ 1.3.360：留一行诊断 —— 续播/无集号起播没接上服务端进度时，
                   「服务端到底回了什么」必须一眼可见（以前只有异常才打日志）。 */
                Log.i(TAG, "srvResume " + book + " miss -> "
                        + (s == null ? "null" : (s.length() > 160 ? s.substring(0, 160) : s)));
                return null;
            }
            JSONObject j = new JSONObject(s);
            return j.optInt("ep", 0) > 0 ? j : null;
        } catch (Throwable t) {
            Log.i(TAG, "srvResume: " + t);
            return null;
        }
    }

    /** ★ v1.3.341：/v1/history 拉取结果 20s TTL 缓存。
     *  r1web 页面每 8s load() 会打一次 /api/prog → prog_list → 这里同步 HTTP 拉服务端；
     *  以前不缓存，服务端一慢整条链拖 2.5s（配合 player 旧版串行 accept 就是
     *  「点暂停要过一会儿才起效」的元凶之一）。书级进度 20s 的陈旧度完全可接受。 */
    private static volatile String sHistCache = null;
    private static volatile long sHistCacheTs = 0L;

    /** ★ v1.3.339：prog_list 输出前，用服务端书级共享进度（media server /v1/history
     *  的 books 表）覆盖各条的 lastEp/lastPos —— 服务端是语音/网页/stem 全通道的
     *  权威进度本，r1web「我的节目/最近播放」显示与点击续播都以它为准。
     *  与服务端续播同口径：上一集 fin / 贴集尾 3s 内 ⇒ 显示成下一集开头。
     *  网络失败 / 服务端不在 ⇒ 原样返回（行为与 1.3.338 一致）。 */
    public static JSONArray srvSyncProg(JSONArray lst) {
        try {
            if (lst == null || lst.length() == 0) {
                return lst;
            }
            String b = nasBase();
            if (b == null || b.length() == 0) {
                return lst;
            }
            String s;
            long now = android.os.SystemClock.elapsedRealtime();
            if (sHistCache != null && now - sHistCacheTs < 20000L) {
                s = sHistCache;                      // ★ v1.3.341：20s 内直接用缓存
            } else {
                // ★ 1.3.347：同样带 MAC —— 服务端据此只回本组设备的书级进度
                String m = mac12();
                s = httpGet(b + "/v1/history" + (m.length() > 0 ? ("?dev=" + m) : ""), 2500);
                if (s != null && s.indexOf("\"books\"") >= 0) {
                    sHistCache = s;
                    sHistCacheTs = now;
                }
            }
            if (s == null || s.indexOf("\"books\"") < 0) {
                return lst;
            }
            JSONArray books = new JSONObject(s).optJSONArray("books");
            if (books == null || books.length() == 0) {
                return lst;
            }
            java.util.HashMap<String, JSONObject> map = new java.util.HashMap<String, JSONObject>();
            for (int i = 0; i < books.length(); i++) {
                JSONObject sb = books.optJSONObject(i);
                if (sb == null) {
                    continue;
                }
                String nm = normBookKey(sb.optString("book", ""));
                String qk = normBookKey(sb.optString("q", ""));
                if (nm.length() > 0) {
                    map.put(nm, sb);
                }
                if (qk.length() > 0 && !map.containsKey(qk)) {
                    map.put(qk, sb);
                }
            }
            for (int i = 0; i < lst.length(); i++) {
                JSONObject pe = lst.optJSONObject(i);
                if (pe == null) {
                    continue;
                }
                String pn = normBookKey(pe.optString("book", ""));
                if (pn.length() == 0) {
                    continue;
                }
                JSONObject sb = map.get(pn);
                if (sb == null) {
                    for (java.util.Map.Entry<String, JSONObject> e : map.entrySet()) {
                        String k = e.getKey();
                        if (k.length() >= 2 && pn.length() >= 2
                                && (k.indexOf(pn) >= 0 || pn.indexOf(k) >= 0)) {
                            sb = e.getValue();
                            break;
                        }
                    }
                }
                if (sb == null) {
                    continue;
                }
                int ep = sb.optInt("ep", 0);
                int pos = (int) sb.optDouble("pos", 0);
                int dur = (int) sb.optDouble("dur", 0);
                boolean fin = sb.optBoolean("fin", false);
                if (ep <= 0) {
                    continue;
                }
                if (fin || (dur > 0 && pos >= dur - 3)) {
                    ep = ep + 1;
                    pos = 0;
                }
                pe.put("lastEp", ep);
                pe.put("lastPos", Math.max(0, pos));
                String t = sb.optString("title", "");
                if (t.length() > 0) {
                    pe.put("lastEpTitle", t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "srvSyncProg: " + t);
        }
        return lst;
    }

    /** ★ v1.3.339：书名归一化（小写 + 去空白），与服务端 bookKeyOf 同口径 */
    private static String normBookKey(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase().replaceAll("\\s+", "");
    }

    private static String abResume(JSONObject args) {
        String book = args == null ? "" : args.optString("book", "").trim();
        if (book.length() == 0) {
            book = abSpGet("ab_loader_book", abSpGet("ab_book", ""));
        }
        if (book.length() == 0) {
            return "ERR 还没有听书进度 —— 先用 ab_play 播一本";
        }
        String album = abSpGet("ab_loader_album", "");
        /* ★ v1.3.276：续播点名了别的书时，别把 loader 专辑硬扣上 —— 交叉听书后
           「继续播《道诡异仙》」会拿《凡人修仙传》的专辑去续，集号全错位；
           这本书在历史里有版本就接历史的，没有才放手让 NAS 挑。 */
        String rlb = abSpGet("ab_loader_book", "");
        String rbq = abBookOnly(book);
        boolean rsame = rlb.length() > 0
                && (rbq.indexOf(abBookOnly(rlb)) >= 0 || abBookOnly(rlb).indexOf(rbq) >= 0);
        if (!rsame) {
            /* ★ v1.3.310：换书续播时，「上次听到第几集」不能再用全局 SP ab_ep ——
               它是「当前在播的那本」的集号。交叉听书后说「继续听凡人修仙传」，
               会拿《道诡异仙》的第3集去续凡人（实测：直接跳到《凡人修仙传》第3集、
               还挑了个落雪 800 集的版本）。这里先看我的节目里这本书记的 lastEp。 */
            JSONObject pb = Prog.findByName(book);
            String pa = (pb == null) ? "" : pb.optString("albumId", "");
            if (pa.length() > 0) {
                album = pa;                          // 收藏过的书优先用收藏里的版本
            } else {
                String ha = abHistFind(book);
                album = ha.length() > 0 ? ha : "";
            }
        }
        int last = 0;
        try {
            last = Integer.parseInt(abSpGet("ab_ep", "0"));
        } catch (Throwable t) {
            last = 0;
        }
        if (!rsame) {
            last = 0;                                // 换书 ⇒ 全局集号作废，下面按本书重取
            JSONObject pb2 = Prog.findByName(book);
            if (pb2 != null && pb2.optInt("lastEp", 0) > 0) {
                last = pb2.optInt("lastEp", 0);
            }
            if (last <= 0) {
                last = lastListenEp(book);           // 没收藏的书：看 SP 那份最近听书记忆
            }
        }
        /* ★ v1.3.280：正在播的就是这本书时，「继续」以正在播的这集为准 ——
           手动点跳集后 SP ab_ep 是旧的。书对不上（交叉听书）不动 SP 进度。 */
        String[] rr = liveNowRow();
        if (rr != null && rr[0].length() > 0) {
            String rb = rr[0];
            if (rb.indexOf(book) >= 0 || book.indexOf(rb) >= 0
                    || abBookOnly(book).indexOf(abBookOnly(rb)) >= 0) {
                try {
                    int lep = Integer.parseInt(rr[1]);
                    if (lep > 0 && lep != last) {
                        last = lep;
                        Log.i(TAG, "abResume live ep=" + lep
                                + " (sp ep=" + abSpGet("ab_ep", "0") + ")");
                    }
                } catch (Throwable t) {
                }
            }
        }
        /* ★ v1.3.311：收藏过的书优先走「我的节目」占位入列（ProgPlay，整批 /r/prog，
           播到才解析）。原因：NAS 的 /api/ab/episodes 按书名+页取，返回的那一页
           并不保证包含要续播的集（实测「凡人修仙传」要续第1630集，返回页
           1504~1668 / 1514~1892 都不含 1630 ⇒ 老路径直接 "ERR 找不到可播的那一集"）。
           Prog 里是按集号精确的缓存/现拉页，集号不会错。 */
        /* ★ v1.3.336 P1 书级共享：续播先问服务端书级进度（media server /v1/resume，
           与 AlphaPi /ap/play ep=0 同源同口径，含「听完⇒下一集」）。命中 ⇒ 集号/位置
           都采信服务端，并写回本地记忆（收藏书 Prog.setProgress、纯语音书
           noteListenPos），下面现有链路原样生效 —— 换台设备也能接着听。
           没命中/网络失败 ⇒ 完全走原本地逻辑，行为与 1.3.335 一致。 */
        try {
            JSONObject sr = srvResume(book);
            if (sr != null) {
                int sep = sr.optInt("ep", 0);
                int spos = Math.max(0, sr.optInt("pos", 0));
                if (sep > 0) {
                    last = sep;
                    JSONObject pbS = Prog.findByName(book);
                    String paS = (pbS == null) ? "" : pbS.optString("albumId", "").trim();
                    if (paS.length() > 0) {
                        Prog.setProgress(paS, sep, spos);
                    } else {
                        noteListenPos(book, sep, spos);
                    }
                    Log.i(TAG, "abResume srv shared ep=" + sep + " pos=" + spos
                            + (sr.optString("dev", "").length() > 0
                                ? " (dev " + sr.optString("dev", "") + ")" : ""));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "abResume srvResume: " + t);
        }
        JSONObject pbe = Prog.findByName(book);
        String paid = (pbe == null) ? "" : pbe.optString("albumId", "").trim();
        if (paid.length() > 0 && last > 0) {
            try {
                JSONObject a2 = new JSONObject();
                a2.put("book", pbe.optString("book", book));
                a2.put("narrator", pbe.optString("narrator", ""));
                a2.put("albumId", paid);
                a2.put("ep", last);
                String r2 = ProgPlay.play(a2);
                if (r2 != null) {
                    int ps = 0;
                    int lep2 = pbe.optInt("lastEp", 0);
                    int lp2 = pbe.optInt("lastPos", 0);
                    if (lp2 > 5 && (lep2 <= 0 || lep2 == last)) {
                        ps = lp2 - 10;   /* ★ v1.3.339：统一 10s（原 2s） */
                    }
                    if (ps <= 0) {
                        int l2 = lastListenPos(book, last);
                        if (l2 > 5) {
                            ps = l2 - 10;   /* ★ v1.3.339：统一 10s */
                        }
                    }
                    if (ps > 0) {
                        askPlayerResumeSeek(ps, last);
                    }
                    Log.i(TAG, "abResume via ProgPlay ep=" + last + " pos=" + ps);
                    return r2;
                }
            } catch (Throwable t) {
                Log.w(TAG, "abResume ProgPlay: " + t);
            }
        }
        int size = abLoaderSize();
        /* ★ v1.3.345：本地库优先 —— 本地库（NAS /audiobook）有这本书就播本地版
           （秒开、不走外网；与 v1.3.325 点播同口径，用户拍板「本地库有的话还是要
           本地优先，本地库没用才播在线库」）。四级链：
           ① 历史专辑就是 abl: → 直接取（活着的历史本地版本优先级天然最高）；
           ② abl: 失效（本地库重扫换 id / 书删过又放回）→ 按书名重新找本地库；
           ③ 历史是在线版但本地库现在有了 → 也切本地；
           ④ 本地没有 / 本地集表拉不到（本地没用）→ 按历史专辑取在线；
              仍失败 → 退专辑约束按书名搜在线（v1.3.344 兜底保留）。 */
        JSONObject d = null;
        String bNas = nasBase();
        boolean triedAlbum = false;
        if (album != null && album.startsWith("abl:") && bNas != null) {
            triedAlbum = true;
            d = abEpisodes(book, album, 0, size, last > 0 ? last : 0);
            if (d == null) {
                Log.i(TAG, "abResume abl id stale (" + album + "), re-find local by name");
            }
        }
        if (d == null && bNas != null) {
            JSONObject lbk = ablFind(bNas, book);
            if (lbk != null) {
                String lid = lbk.optString("id", "").trim();
                if (lid.length() > 0) {
                    d = ablEpsAsAb(bNas, lid, 1, size, last > 0 ? last : 0);
                    if (d != null) {
                        Log.i(TAG, "abResume local-first id=" + lid
                                + " name=" + lbk.optString("title", ""));
                    }
                }
            }
        }
        if (d == null && !triedAlbum && album != null && album.length() > 0) {
            // v1.3.264：用 ep 参数定位包含 last 的页（last<=0 ⇒ 第1页）
            d = abEpisodes(book, album, 0, size, last > 0 ? last : 0);
        }
        if (d == null && album != null && album.length() > 0) {
            /* ★ v1.3.344：历史版本兜底 —— ab_ver_hist 里最新的版本可能是已不存在的
               NAS 本地书（abl: 前缀；本地库清空后 /api/abl/eps 直接空回）或失效专辑，
               这里一票否决成 "ERR 有声书数据源没应答"，小智就会报「有声书连不上」。
               实测：凡人修仙传 hist 最新 = abl:（11-07 本地库播的），本地库已清空，
               在线版 91699665 就在历史里但 ts 更旧 ⇒ 退掉 album 约束按书名再问一次，
               服务端 abcache 按书名搜在线版几乎必中。 */
            Log.i(TAG, "abResume eps fail (album=" + album + ")，retry q-only");
            d = abEpisodes(book, "", 0, size, last > 0 ? last : 0);
        }
        if (d == null) {
            return "ERR 有声书数据源没应答";
        }
        JSONArray eps = d.optJSONArray("episodes");
        if (eps == null || eps.length() == 0) {
            return "ERR《" + book + "》的剧集列表空了";
        }
        int target = last > 0 ? last : 0;
        JSONObject e0 = abPick(eps, target);
        /* ★ 1.3.359：本地库版本「集数不够」→ 退在线库，别把 ERR 甩给小智。
           实测（11-19 22:20）：喊「继续播放凡人修仙传」回
           ERR《凡人修仙传》找不到可播的那一集，小智转述成「找不到上次播放的记录」，
           用户以为听书记忆丢了。真相：ab_loader_album 还指着本地库那本
           （/audiobook 只有 3 集），正在播的是在线版第 6 集 —— target=6 在 3 集的
           本地集表里当然 pick 不到。本地库拿不到目标集就换在线库问一次。 */
        String preAlb = d.optString("albumId", "").trim();
        boolean fromLocal = preAlb.startsWith("abl:")
                || "abl".equals(d.optString("source", ""));
        if (e0 == null && fromLocal) {
            try {
                /* 服务端 /api/ab/episodes 自身就是「files/ab_cache.json 永久缓存优先 →
                   miss 才回源搜上游」，所以这一跳等于「缓存 → 在线」两级，
                   响应里的 cache=disk|pinyin|search 就是实际命中的层级。 */
                JSONObject d2 = abEpisodes(book, "", 0, size, target);
                JSONArray e2 = (d2 == null) ? null : d2.optJSONArray("episodes");
                JSONObject p2 = abPick(e2, target);
                if (p2 != null) {
                    Log.i(TAG, "abResume 本地库缺第 " + target + " 集（本地版共 "
                            + eps.length() + " 集）→ 退缓存/在线（cache="
                            + (d2 == null ? "?" : d2.optString("cache", "?")) + "，共 "
                            + (e2 == null ? 0 : e2.length()) + " 集）");
                    d = d2;
                    eps = e2;
                    e0 = p2;
                }
            } catch (Throwable t) {
                Log.w(TAG, "abResume local->cache/online: " + t);
            }
        }
        /* ★ 1.3.359：还 pick 不到（这本书任何版本都没有这一集 —— 多半是集号比
           该版本总集数还大，比如换了短版本）→ 退到 ≤target 的最近一集，
           仍然没有就第一集。宁可播一集并说明，也不要回「找不到记录」。 */
        boolean epClamped = false;
        if (e0 == null) {
            int best = -1;
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                int xep = (e == null) ? 0 : e.optInt("ep", 0);
                if (xep > 0 && (target <= 0 || xep <= target) && xep > best) {
                    best = xep;
                    e0 = e;
                }
            }
            if (e0 == null) {
                e0 = abPick(eps, 0);
            }
            epClamped = (e0 != null);
            if (epClamped) {
                Log.i(TAG, "abResume target " + target + " 该版本没有 → 退到第 "
                        + e0.optInt("ep", 0) + " 集");
            }
        }
        if (e0 == null) {
            return "ERR《" + book + "》找不到可播的那一集";
        }
        int[] rng = abPageRange(d);
        /* ★ v1.3.344：loader 专辑记**响应里的真 albumId**（q-only 兜底时 album 变量
           可能还是过期的 abl:/失效值，照存会让 ab_next/ab_prev 接着踩同一个坑） */
        String dalb = d.optString("albumId", "").trim();
        if (dalb.length() == 0) {
            dalb = album;
        }
        /* ★ v1.3.357：loader 书名也存**响应里的 book**（本地合集子书时是子书名），
           ab_next/ab_prev 之后按这个名字续拉集表才对得上同一部。 */
        abLoaderSave(d.optString("book", book), dalb, d.optString("source", "kw"),
                d.optInt("page", 1), size, d.optInt("total", 0), rng[0], rng[1]);
        /* ★ v1.3.308：语音续播也要「从上次听到的秒数接着播」。
           以前 ab_resume 只负责把**集**对上（听第1630集），秒数一律从 0:00 起 ——
           用户：「喊小智继续播放某部有声书，没从标记点开始，而是从 0:00 开始」。
           位置必须**在起播之前读**：abQueueBook 之后 player 已经换集，
           进度会被写成新集的 0 秒。只有 same-ep 的 lastPos 才可信（换集时
           Prog.setLastEp 已把 lastPos 归零）。 */
        int posSec = 0;
        try {
            JSONObject pe = Prog.findByAlbum(d.optString("albumId", album));
            if (pe == null) {
                pe = Prog.findByName(d.optString("book", book));
            }
            if (pe != null) {
                int lep = pe.optInt("lastEp", 0);
                int lp = pe.optInt("lastPos", 0);
                if (lp > 5 && (lep <= 0 || lep == e0.optInt("ep", 0))) {
                    posSec = lp - 10;              /* ★ v1.3.339：统一 10s（原 2s）——
                                                      与 stem / r1web 点播同一口径 */
                }
            }
        } catch (Throwable t) {
            posSec = 0;
        }
        /* ★ v1.3.309：不在「我的节目」里的书（纯语音 ab_play 播的）Prog 里没有条目，
           上面必然拿到 0 ⇒ 又退回 0:00。这里补一份「最近听书记忆」（SP，与收藏无关）。 */
        if (posSec <= 0) {
            int lp = lastListenPos(d.optString("book", book), e0.optInt("ep", 0));
            if (lp > 5) {
                posSec = lp - 10;   /* ★ v1.3.339：统一 10s */
            }
        }
        /* ★ 1.3.359：集号被钳过就别再说「继续听第 6 集」（实际播的是第 3 集）——
           直接说清「这版没有第 N 集，从第 M 集继续」，小智转述才不误导。 */
        String hint = last > 0 ? "继续听 —— " : "继续听（从头开始）—— ";
        if (epClamped) {
            int tot = d.optInt("total", 0);
            hint = "这版没有第 " + target + " 集（共 " + (tot > 0 ? tot : eps.length())
                    + " 集）—— 从第 " + e0.optInt("ep", 0) + " 集继续 —— ";
        }
        String r = abQueueBook(d.optString("book", book), d.optInt("total", 0), eps,
                e0.optInt("ep", 0), hint);
        boolean queued = (r != null);
        if (!queued) {
            r = abStart(d.optString("book", book), e0);
        }
        if (posSec > 0 && r != null && r.startsWith("OK")) {
            askPlayerResumeSeek(posSec, e0.optInt("ep", 0));
        }
        return queued ? (r + batchHint(d)) : r;
    }

    /* ★ v1.3.308：让 player 在**新的一集真出声之后**再拖到 posSec 秒。
       走 player 的 /api/ab/resume（它内部有守卫：等切集 + 等 prepared，
       不会像以前那样在 prepare 之前 seek 触发 -38 把这一集判死）。 */
    private static void askPlayerResumeSeek(final int posSec, final int ep) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(300);            // 让 kickPlay 先把起播指令发出去
                    String r = httpGet("http://127.0.0.1:8080/api/ab/resume?pos="
                            + posSec + "&ep=" + ep, 2500, 65536, 4000);
                    Log.i(TAG, "askPlayerResumeSeek pos=" + posSec + "s ep=" + ep
                            + " => " + r);
                } catch (Throwable t) {
                    Log.w(TAG, "askPlayerResumeSeek: " + t);
                }
            }
        }, "ab-resume-ask").start();
    }

    /** ★ v1.3.309：当前在听的有声书（**不要求已收藏**）→ {book, ep}；不是有声书返 null。
     *  给「最近听书记忆」用 —— 纯语音 ab_play 播的书不在我的节目里，也得记住秒数。 */
    public static String[] liveListenNow() {
        try {
            String[] rr = liveNowRow();
            if (rr == null) {
                return null;
            }
            String book = rr[0] == null ? "" : rr[0].trim();
            int ep = 0;
            try {
                ep = Integer.parseInt(rr[1]);
            } catch (Throwable t) {
                ep = 0;
            }
            if (book.length() == 0 || ep <= 0) {
                /* ★ 1.3.348：不再拿 ab_loader_book 兜底 —— 那是「上一本在线书」的全局残留，
                   实测会把本地书《道诡异仙》记成《遮天》。认不出书名就不报，宁缺毋滥。 */
                return null;
            }
            /* ★ 1.3.350：上报统一用**短书名**（「丨」前第一段）。
               源里的真名是「仙逆丨多人剧丨曲中人有故事丨动漫热播原著丨耳根丨完结」，
               直接上报会和历史记录的「仙逆」变成两本书（书级区两条同名书）。
               Prog.shortBook 是入列时给队列标题用的同一套规则，不是新猜法。
               ★ 1.3.357：本地书例外 —— 书名是本地库的**子书名**（「黑暗森林Ⅱ」），
               它本身就是权威书名，不再过 shortBook（避免带「·」的子书名被前段截掉）。 */
            String url = rr.length > 2 && rr[2] != null ? rr[2] : "";
            if (url.indexOf("/m/") >= 0) {
                return new String[]{book, String.valueOf(ep)};
            }
            return new String[]{Prog.shortBook(book), String.valueOf(ep)};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ★ 1.3.350：当前在播的是电台 → 台名；不是电台返 null。
     *
     * <p>为什么要单独一条路：广播的 URL 是上游直链（服务端 /radio/ 代理默认关），
     * 媒体服务器**收不到任何拉流请求** ⇒ 播放记录里永远没有「在听广播」。书的
     * 那套 /v1/pos 上报只认「标题含第 N 集」，电台全被 abLikely 挡掉。</p>
     *
     * <p>★ v1.3.352：判据从「itemId 前缀 100_radio_」改成 **URL 排除法**。
     * 1.3.350 那个前缀是错的 —— 实测（2026-11-18 18:05，播「国际新闻」时抓
     * player /api/queue）队列项是：</p>
     * <pre>{"n":1,"title":"国际新闻","itemId":"local_1",
     *  "url":"http://lhttp.qtfm.cn/live/20500172/64k.mp3","album":""}</pre>
     * <p>{@code local_1} 是 {@link #playRadio} 走 {@code oneJson()} 入列的**通用 id**
     * （同一个 id 也用于 play_url / 闹钟铃声），{@code 100_radio_<n>} 只出现在
     * radioList 名册那条路径上。所以老判据对真实广播全落空，一条都没报上去。</p>
     *
     * <p>用 URL 认领才稳 —— 各类媒体的 URL 形态互斥：</p>
     * <ul>
     *   <li>本地文件/本地音乐：{@code file://} 或无 scheme 的路径 → 不是电台</li>
     *   <li>本地节目（听书）：带 {@code /m/} → 不是电台</li>
     *   <li>在线书/在线歌：带 {@code /r/} → 不是电台</li>
     *   <li>服务端电台代理：带 {@code /radio/} → 是电台</li>
     *   <li>剩下「外网 http(s) 直链」→ 直播流（蜻蜓/喜马拉雅/自定义台）</li>
     * </ul>
     */
    public static String liveRadioNow() {
        try {
            JSONObject p = playerLiveQueue(0);
            if (p == null) {
                return null;
            }
            int cur = p.optInt("cur", -1);
            JSONArray l = p.optJSONArray("list");
            if (cur < 0 || l == null) {
                return null;
            }
            for (int i = 0; i < l.length(); i++) {
                JSONObject it = l.optJSONObject(i);
                if (it == null || it.optInt("n", -1) != cur + 1) {
                    continue;
                }
                String itemId = it.optString("itemId", "");
                String url = it.optString("url", "").trim();
                String t = it.optString("title", "").trim();
                if (t.length() == 0 || url.length() == 0) {
                    return null;
                }
                if (itemId.startsWith("abl_")) {          // 有声书队列，另有权威路径
                    return null;
                }
                String u = url.toLowerCase();
                if (u.startsWith("file://") || u.indexOf("/m/") >= 0) {
                    return null;                          // 本地文件 / 本地节目
                }
                if (u.indexOf("/radio/") >= 0) {          // 服务端电台代理
                    Log.i(TAG, "abNext radio(proxy) cur=" + cur + " name=" + t);
                    return t;
                }
                if (u.indexOf("/r/") >= 0 || u.indexOf("://") < 0) {
                    return null;                          // 在线流（歌/书）/ 非法地址
                }
                Log.i(TAG, "abNext radio cur=" + cur + " name=" + t + " url=" + url);
                return t;
            }
        } catch (Throwable t) {
            Log.w(TAG, "liveRadioNow: " + t);
        }
        return null;
    }

    /** ★ 1.3.309：记「这本书听到第几秒」（SP：ab_live_pos，与收藏无关，12h 过期）。
     *  ★ v1.3.311：改成**按书一张小表**（最多 8 本）。以前只存一本，
     *  交叉听书（凡人→道诡异仙）后前一本的记忆被顶掉，
     *  再喊「继续听凡人」就变成 "继续听（从头开始）第1集"。 */
    public static void noteListenPos(String book, int ep, int posSec) {
        if (book == null || book.trim().length() == 0 || ep <= 0 || posSec < 0) {
            return;
        }
        try {
            String k = normBook(book);
            if (k.length() == 0) {
                return;
            }
            JSONObject m = livePosMap();
            JSONObject o = new JSONObject();
            o.put("book", book);
            o.put("ep", ep);
            o.put("pos", posSec);
            o.put("ts", System.currentTimeMillis());
            m.put(k, o);
            /* 每本书一条（不再过期）；只留最近 50 本，防 SP 无上限膨胀 */
            if (m.length() > 50) {
                java.util.List<String> ks = new java.util.ArrayList<String>();
                java.util.Iterator<?> it = m.keys();
                while (it.hasNext()) {
                    Object kk = it.next();
                    if (kk != null) {
                        ks.add(String.valueOf(kk));
                    }
                }
                while (ks.size() > 50) {
                    String old = null;
                    long oldTs = Long.MAX_VALUE;
                    for (int i = 0; i < ks.size(); i++) {
                        JSONObject e = m.optJSONObject(ks.get(i));
                        long ts = (e == null) ? 0 : e.optLong("ts", 0);
                        if (ts < oldTs) {
                            oldTs = ts;
                            old = ks.get(i);
                        }
                    }
                    if (old == null) {
                        break;
                    }
                    m.remove(old);
                    ks.remove(old);
                }
            }
            abSpPut("ab_live_pos", m.toString());
            Log.i(TAG, "listen pos " + book + " ep=" + ep + " pos=" + posSec + "s");
        } catch (Throwable t) { /* 记不住不致命 */ }
    }

    private static JSONObject livePosMap() {
        try {
            String js = abSpGet("ab_live_pos", "");
            if (js == null || js.length() == 0) {
                return new JSONObject();
            }
            JSONObject o = new JSONObject(js);
            /* ★ v1.3.314：老格式是**单条**（{key,book,ep,pos,ts}），
               直接当表读会让「道诡异仙」永远读到那条旧记录（实测：都听到第7集了，
               续播还是回第1集 3:34）。这里就地迁移成 «key: {book,ep,pos,ts}» 再写回。 */
            if (o.has("key") && o.optString("key", "").length() > 0) {
                JSONObject m = new JSONObject();
                String k = o.optString("key", "");
                JSONObject e = new JSONObject();
                e.put("book", o.optString("book", ""));
                e.put("ep", o.optInt("ep", 0));
                e.put("pos", o.optInt("pos", 0));
                e.put("ts", o.optLong("ts", 0));
                m.put(k, e);
                o.remove("key");
                o.remove("book");
                o.remove("ep");
                o.remove("pos");
                o.remove("ts");
                java.util.Iterator<?> it = o.keys();
                while (it.hasNext()) {
                    Object kk = it.next();
                    JSONObject v = o.optJSONObject(String.valueOf(kk));
                    if (v != null) {
                        m.put(String.valueOf(kk), v);
                    }
                }
                abSpPut("ab_live_pos", m.toString());
                Log.i(TAG, "listen pos 迁移旧格式 -> " + m.length() + " 本");
                return m;
            }
            return o;                                // «书名key: {book,ep,pos,ts}» 的一张表
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** ★ v1.3.311：取这本书的记忆条目（{book,ep,pos,ts}）；没记过/过期返 null。 */
    private static JSONObject livePosOf(String book) {
        try {
            String k = normBook(book);
            if (k.length() == 0) {
                return null;
            }
            JSONObject m = livePosMap();
            /* 老格式（单条 {key,book,ep,pos,ts}）兼容读一次 */
            if (m.has("key") && m.optString("key", "").length() > 0) {
                if (!m.optString("key", "").equals(k)) {
                    return null;
                }
                return m;
            }
            JSONObject o = m.optJSONObject(k);
            if (o == null) {
                return null;
            }
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** ★ v1.3.309：取「这本书/这一集」上次听到第几秒；对不上返 0。 */
    public static int lastListenPos(String book, int ep) {
        JSONObject o = livePosOf(book);
        if (o == null) {
            return 0;
        }
        if (o.optInt("ep", 0) != ep) {
            return 0;                                // 集变了 ⇒ 位置无意义
        }
        return o.optInt("pos", 0);
    }

    /** ★ v1.3.310：SP 记忆里这本书上次听到第几集；书对不上返 0。 */
    public static int lastListenEp(String book) {
        JSONObject o = livePosOf(book);
        return (o == null) ? 0 : o.optInt("ep", 0);
    }

    /** 书名归一（去空白/标点/《》，用于比对）。 */
    private static String normBook(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(Character.toLowerCase(c));
            }
        }
        return b.toString();
    }

    /** ★ v1.3.308：当前正在播的节目行 → {obook, ep, albumId}；不是节目返 null。
     *  给 ProgProgress（听书进度自记）用。 */
    public static String[] liveProgNow() {
        try {
            String[] rr = liveNowRow();
            if (rr == null) {
                return null;
            }
            String book = rr[0] == null ? "" : rr[0].trim();
            int ep = 0;
            try {
                ep = Integer.parseInt(rr[1]);
            } catch (Throwable t) {
                ep = 0;
            }
            if (book.length() == 0 || ep <= 0) {
                /* ★ 1.3.348：与 liveListenNow 同口径 —— 认不出书名就不写，
                   绝不拿 ab_loader_book（上一本在线书）顶上。 */
                return null;
            }
            String album = "";
            String lb = abSpGet("ab_loader_book", "");
            String la = abSpGet("ab_loader_album", "");
            if (lb.length() > 0 && la.length() > 0
                    && (abBookOnly(lb).indexOf(abBookOnly(book)) >= 0
                        || abBookOnly(book).indexOf(abBookOnly(lb)) >= 0)) {
                album = la;
            }
            if (album.length() == 0) {
                JSONObject pe = Prog.findByName(book);
                if (pe != null) {
                    album = pe.optString("albumId", "");
                }
            }
            if (album.length() == 0) {
                album = abHistFind(book);
            }
            if (album.length() == 0 || !Prog.has(album)) {
                return null;                        // 没收藏的书不用记进度
            }
            return new String[]{book, String.valueOf(ep), album};
        } catch (Throwable t) {
            return null;
        }
    }

    /** ★ v1.3.349：轻量播放状态指纹 —— 给 ProgProgress 每 2 秒「起播感知」用。
     *
     *  <p>为什么不能直接用 /api/queue：那是**整张队列**（3374 集的书要序列化几百 KB），
     *  2 秒一次会把 player 的 WebSrv 拖住。这里只读 /api/status 的 music 摘要
     *  （实测约 4.6KB，queueBrief 只有 cur/total，不含列表）。</p>
     *
     * <p>指纹必须「同一次播放内稳定」：只由 曲名/曲目 id/是否播放中/位置是否已过 3 秒
     *  组成。位置本身不进指纹，否则每 2 秒都算变化，等于白跑全量流程。</p>
     *
     * @return "" 表示 player 未就绪或没有当前曲（调用方应当不据此做判断）
     */
    public static String playerFingerprint() {
        try {
            String js = httpGet("http://127.0.0.1:8080/api/status", 1200, 262144, 2000);
            if (js == null || js.length() == 0) {
                return "";
            }
            JSONObject o = new JSONObject(js);
            JSONObject m = o.optJSONObject("music");
            if (m == null || m.optBoolean("stale", false)) {
                return "";
            }
            String t = m.optString("curTitle", "");
            if (t.length() == 0) {
                return "";
            }
            long ms = m.optLong("posMs", 0);
            return t + "|" + m.optString("curId", "") + "|"
                    + (m.optBoolean("playing", false) ? "1" : "0") + "|"
                    + (ms > 3000 ? "P" : "S");
        } catch (Throwable t) {
            return "";
        }
    }

    /** ★ v1.3.308：player 侧当前播放秒数（读它自己的 /api/status.music.posMs）。 */
    public static int playerPosSec() {
        try {
            String js = httpGet("http://127.0.0.1:8080/api/status", 1500, 262144, 2500);
            if (js == null || js.length() == 0) {
                return 0;
            }
            JSONObject o = new JSONObject(js);
            JSONObject m = o.optJSONObject("music");
            if (m == null) {
                return 0;
            }
            long ms = m.optLong("posMs", 0);
            return (int) (ms / 1000);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** ★ v1.3.358：player /api/status 里的 music 摘要 —— 查进度 / 时长 / 相位共用一次 HTTP。
     *
     *  <p>只取 music 对象：posMs / durMs / playing / phase / liveStream 都在里面。
     *  player 没起、或没在播（curTitle 空）时返回 null，调用方据此报「没在播」。</p>
     */
    public static JSONObject playerMusic() {
        try {
            String js = httpGet("http://127.0.0.1:8080/api/status", 1500, 262144, 2500);
            if (js == null || js.length() == 0) {
                return null;
            }
            JSONObject o = new JSONObject(js);
            return o.optJSONObject("music");
        } catch (Throwable t) {
            return null;
        }
    }

    /** ★ v1.3.358：秒 → m:ss（超过 1 小时给 h:mm:ss）—— 念出来不拗口，和 player 页面同口径。 */
    public static String fmtHms(long sec) {
        if (sec < 0) {
            sec = 0;
        }
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        String mm = (m < 10) ? ("0" + m) : String.valueOf(m);
        String ss = (s < 10) ? ("0" + s) : String.valueOf(s);
        return (h > 0) ? (h + ":" + mm + ":" + ss) : (m + ":" + ss);
    }

    /** ★ v1.3.358：进度一句话 —— 「已播到 12:30，共 25:10，还剩 12:40」。
     *
     *  <p>时长是 player 自己从播放器取的（durMs），不是我们估的：/m/ 与本地文件都有，
     *  电台直播流（liveStream）没有总时长，如实说「拖不了」而不是编一个数。</p>
     */
    private static String progLine(JSONObject m) {
        if (m == null) {
            return "";
        }
        if (m.optString("curTitle", "").length() == 0) {
            return "";
        }
        long pos = m.optLong("posMs", 0) / 1000L;
        long dur = m.optLong("durMs", 0) / 1000L;
        StringBuilder sb = new StringBuilder("已播到 ").append(fmtHms(pos));
        if (dur > 0) {
            sb.append("，共 ").append(fmtHms(dur))
              .append("，还剩 ").append(fmtHms(Math.max(0, dur - pos)));
        } else {
            sb.append("（这个音源没有总时长，拖不了进度）");
        }
        String ph = m.optString("phase", "");
        if ("loading".equals(ph)) {
            sb.append("（正在缓冲）");
        } else if ("stalled".equals(ph)) {
            sb.append("（缓冲中，可能没出声）");
        } else if (!m.optBoolean("playing", false)) {
            sb.append("（已暂停）");
        }
        return sb.toString();
    }

    /** ★ v1.3.358：「这集多长 / 放了多久 / 还剩多久」—— 只查不跳。 */
    public static String progressText() {
        JSONObject m = playerMusic();
        if (m == null || m.optString("curTitle", "").length() == 0) {
            return "ERR 现在没有在播的内容";
        }
        StringBuilder sb = new StringBuilder("OK ");
        /* 有声书把书名/集号带上，否则只说「已播到 3:20」模型不知道是哪一集 */
        String[] nr = liveNowRow();
        if (nr != null && nr.length >= 2) {
            sb.append("《").append(nr[0]).append("》第 ").append(nr[1]).append(" 集，");
        } else {
            String t = m.optString("curTitle", "");
            sb.append("《").append(t).append("》，");
        }
        sb.append(progLine(m));
        return sb.toString();
    }

    /** ★ v1.3.358：语音调进度 —— sec 绝对秒 / delta 相对秒（正快进 负后退）/ 都不传 = 只查。
     *
     *  <p>落到 player 的 {@code /api/act?op=seek&ms=}（pl-1.6.12 起那条 smali 补的
     *  事务 0x15，能真拖本地播放器）。可行性由 player 自己把关：非本地播放态、
     *  远程源、服务没绑上都会回 ok=false + 中文原因，这里原样转给模型。</p>
     */
    public static String seekPlay(JSONObject args) {
        int sec = -1;
        int delta = 0;
        if (args != null) {
            sec = args.optInt("sec", -1);
            if (sec < 0) {
                sec = args.optInt("to", -1);
            }
            delta = args.optInt("delta", 0);
            if (delta == 0) {
                delta = args.optInt("by", 0);
            }
        }
        JSONObject m = playerMusic();
        if (m == null || m.optString("curTitle", "").length() == 0) {
            return "ERR 现在没有在播的内容，没法跳进度";
        }
        if (sec < 0 && delta == 0) {
            return progressText();          // 只问进度，不跳
        }
        if (m.optBoolean("liveStream", false)) {
            return "ERR 这是电台直播流，没有终点，拖不了进度";
        }
        long pos = m.optLong("posMs", 0) / 1000L;
        long dur = m.optLong("durMs", 0) / 1000L;
        /* ★ 实测（1.3.358 首次装机）：网络电台的 liveStream/qRadio **都是 false**
           —— player 的 isRadio 只认它自己解析出「无长度流」的那一步，起播瞬间拿不到。
           但 durMs 恒为 0，所以「没总时长 = 拖不了」才是可靠判据（直播流、以及
           刚起播还没读出时长的本地文件都落在这一条）。 */
        if (dur <= 0) {
            return "ERR 这一条暂时没有总时长（直播流，或刚起播还没读出来），拖不了进度";
        }
        long target = (sec >= 0) ? sec : (pos + delta);
        if (dur > 0 && target > dur - 2) {
            target = Math.max(0, dur - 2);  // 别拖到片尾（拖到底会直接跳下一集）
        }
        if (target < 0) {
            target = 0;
        }
        String r;
        try {
            r = httpGet("http://127.0.0.1:8080/api/act?op=seek&ms=" + (target * 1000L), 3000);
        } catch (Throwable t) {
            return "ERR 跳转请求发不出去：" + t;
        }
        try {
            JSONObject o = new JSONObject(r == null ? "" : r);
            if (!o.optBoolean("ok", false)) {
                String msg = o.optString("msg", "");
                return "ERR " + (msg.length() > 0 ? msg : "播放器拒绝跳转（可能没在本地播放态）");
            }
        } catch (Throwable ignored) {
            /* player 回的不是 JSON 就当成功往下走，后面用 status 复核 */
        }
        String tail = (dur > 0)
                ? ("，共 " + fmtHms(dur) + "，还剩 " + fmtHms(Math.max(0, dur - target)))
                : "";
        SessionLog.i("seek_play: " + pos + "s -> " + target + "s (sec=" + sec
                + " delta=" + delta + ")");
        return "OK 已跳到 " + fmtHms(target) + tail;
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        return httpGet(url, timeoutMs, 8 * 1024 * 1024, 20000);
    }

    /** ★ v1.3.342：设备 MAC（wlan0，12 位大写无分隔；与服务端 devIdOf 归一化同口径）。
     *  读不到（飞行模式/接口名不同）返回 ""，调用方降级不带 devId（服务端退 ip-<地址>）。 */
    private static volatile String sMac12 = "";

    public static String mac12() {
        if (sMac12.length() > 0) {
            return sMac12;
        }
        try {
            java.io.FileInputStream f = new java.io.FileInputStream("/sys/class/net/wlan0/address");
            byte[] b = new byte[64];
            int n = f.read(b);
            f.close();
            String s = new String(b, 0, Math.max(0, n), "UTF-8").trim();
            StringBuilder o = new StringBuilder();
            for (int i = 0; i < s.length() && o.length() < 12; i++) {
                char ch = s.charAt(i);
                if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')) {
                    o.append(Character.toUpperCase(ch));
                }
            }
            if (o.length() >= 8) {
                sMac12 = o.toString();
            }
        } catch (Throwable ignored) {
        }
        return sMac12;
    }

    /** ★ v1.3.342：POST JSON（/v1/pos 专用，小超时，只关心发得出去、不解析响应体）。 */
    private static void httpPostJson(String url, String json, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] b = json.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            java.io.OutputStream os = c.getOutputStream();
            os.write(b);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new Exception("HTTP " + code + " for " + url);
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * ★ v1.3.342 P1 收尾：把 r1web 回写的进度上报媒体服务器 /v1/pos。
     *   直链播放（/r/）的「起播事实」记录由此补上真实位置；书级共享表同时更新，
     *   stem 等其它设备「继续听」从此能吃到 R1 的进度（此前 R1 只读不写，双向断一半）。
     *   book 名从 Prog 缓存按 albumId 反查（r1web 只传 albumId/ep/pos）。
     *   失败静默（打日志），绝不影响 prog_setpos 主流程；调用方已在后台线程。
     */
    public static void progPosReport(String albumId, int ep, int pos) {
        try {
            if (albumId == null || albumId.length() == 0 || ep <= 0 || pos <= 0) {
                return;
            }
            String b = nasBase();
            if (b == null || b.length() == 0) {
                return;
            }
            String book = "";
            JSONArray ls = Prog.list();
            for (int i = 0; ls != null && i < ls.length(); i++) {
                JSONObject it = ls.optJSONObject(i);
                if (it != null && albumId.equals(it.optString("albumId", "").trim())) {
                    book = it.optString("book", "").trim();
                    break;
                }
            }
            if (book.length() == 0) {
                return;
            }
            JSONObject o = new JSONObject();
            String mac = mac12();
            if (mac.length() > 0) {
                o.put("devId", mac);
                o.put("devName", "R1-" + mac.substring(Math.max(0, mac.length() - 4)));
            }
            o.put("kind", "book");
            o.put("q", book);
            o.put("book", book);
            o.put("ep", ep);
            o.put("pos", pos);
            o.put("fin", false);
            httpPostJson(b + "/v1/pos", o.toString(), 2500);
            Log.i(TAG, "progPosReport " + book + " ep=" + ep + " pos=" + pos);
        } catch (Throwable t) {
            Log.w(TAG, "progPosReport: " + t);
        }
    }

    /** v1.3.116：可指定字节上限与总耗时上限（拉 index.json 用更紧的档位）。 */
    private static String httpGet(String url, int timeoutMs, int maxBytes,
                                  long deadlineMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code + " for " + url);
            }
            String ct = c.getHeaderField("Content-Type");
            if (ct != null) {
                String l = ct.toLowerCase();
                // 明确的音频/视频流直接拒掉，给用户一句能看懂的话
                if (l.startsWith("audio/") || l.startsWith("video/")
                        || l.contains("mpegurl")) {
                    throw new Exception("这个地址返回的是 " + ct
                            + "（音视频流），不是 index.json。"
                            + "直播流请填进 NAS 的 radios.json，不要放在这里");
                }
            }
            return readAll(c.getInputStream(), maxBytes, deadlineMs);
        } finally {
            c.disconnect();
        }
    }

    /**
     * v1.3.116：带上限地读完一个流。
     *
     * 原来是无上限的 while(read>0) —— 遇到**永不结束的直播流**（用户误把
     * qingting 的 64k.mp3 当 index.json 填进音乐源）就会一直读下去：
     * setReadTimeout 只在「单次 read 阻塞超时」时触发，直播流一直在送数据所以
     * 永不超时 ⇒ loadIndex() 卡死并一直占着 PlayerBridge 类锁 ⇒
     * setIndexSpec()（保存音乐源走的路）跟着排队 ⇒ 控制台「保存」永远无响应，
     * 表现就是「加进去的地址删不掉」。
     *
     * 现在：字节超过 maxBytes、或总耗时超过 deadlineMs，直接抛异常收手。
     */
    private static String readAll(InputStream is, int maxBytes, long deadlineMs)
            throws Exception {
        long t0 = System.currentTimeMillis();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > maxBytes) {
                is.close();
                throw new Exception("响应超过 " + (maxBytes / 1024) + "KB，"
                        + "这不像一个 index.json（是不是填成了音频流地址？）");
            }
            if (System.currentTimeMillis() - t0 > deadlineMs) {
                is.close();
                throw new Exception("读取超过 " + deadlineMs + "ms 仍没结束，"
                        + "这不像一个 index.json（是不是填成了直播流地址？）");
            }
        }
        is.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String readAll(InputStream is) throws Exception {
        return readAll(is, 8 * 1024 * 1024, 20000);
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }
}
