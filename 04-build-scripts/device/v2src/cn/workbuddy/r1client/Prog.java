package cn.workbuddy.r1client;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 我的节目（v1.3.286）—— 电子书收藏，**唯一主本在 device 侧** {@code files/r1_prog.json}。
 *
 * <p>与 Fav（我的收藏 / 歌曲）并列，但完全独立：歌曲收藏走 Fav，有声书收藏走这里。
 * 用户原话「这个收藏和前面的我的收藏分开，命名为我的节目」。</p>
 *
 * <p>★ 条目口径：只收藏「书名 + 版本」（以 albumId 唯一标识某一版本的某一本有声书），
 * 不收藏具体集数 —— 集数在播的时候由 device 的有声书引擎临时拉。展示用 book（书名），
 * 回放用 albumId（锁定版本，避免同名多版本挑错）。</p>
 *
 * <p>落盘口径与 Fav 一致：原子写（先写 .tmp 再 rename）。</p>
 */
public final class Prog {

    private static final String TAG = "R1Prog";
    private static final String FILE = "r1_prog.json";
    /** 物理安全阀：超出直接拒绝新增（不会删旧）。 */
    private static final int MAX = 5000;

    private static Context app;
    private static final Object LOCK = new Object();
    private static JSONArray cache = null;

    public static void init(Context c) {
        app = c.getApplicationContext();
    }

    private static File file() {
        return new File(app.getFilesDir(), FILE);
    }

    private static JSONArray read() {
        JSONArray a = new JSONArray();
        try {
            File f = file();
            if (!f.exists()) {
                return a;
            }
            FileInputStream in = new FileInputStream(f);
            StringBuilder sb = new StringBuilder();
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    sb.append(new String(b, 0, n, "UTF-8"));
                }
            } finally {
                in.close();
            }
            JSONObject o = new JSONObject(sb.toString());
            JSONArray l = o.optJSONArray("list");
            if (l != null) {
                a = l;
            }
        } catch (Throwable t) {
            Log.w(TAG, "read: " + t);
        }
        return a;
    }

    /** 原子写：先写 .tmp 再 rename —— 写到一半断电也不会留个半截 JSON。 */
    private static void write(JSONArray a) {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("list", a);
            File f = file();
            File tmp = new File(app.getFilesDir(), FILE + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(o.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
            tmp.renameTo(f);
        } catch (Throwable t) {
            Log.w(TAG, "write: " + t);
        }
    }

    public static JSONArray list() {
        synchronized (LOCK) {
            if (cache == null) {
                cache = read();
            }
            return cache;
        }
    }

    /** 收藏一本（按 albumId 去重；已存在直接算成功）。只收藏书名+版本，不收具体集。 */
    public static boolean add(String book, String narrator, String albumId,
                             String source, int count) {
        if (book == null || book.trim().length() == 0) {
            return false;
        }
        if (albumId == null || albumId.trim().length() == 0) {
            return false;
        }
        boolean added = false;
        synchronized (LOCK) {
            JSONArray a = list();
            if (a.length() >= MAX) {
                return false;
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && albumId.equals(it.optString("albumId", ""))) {
                    return true;            // 已存在 ⇒ 直接成功（骨架之前已建过）
                }
            }
            try {
                JSONObject it = new JSONObject();
                it.put("book", book.trim());
                it.put("narrator", narrator == null ? "" : narrator.trim());
                it.put("albumId", albumId.trim());
                it.put("source", source == null ? "" : source.trim());
                it.put("count", count);
                it.put("lastEp", 0);          // ★ v1.3.287：最近播放集数（0=未播过）
                it.put("lastEpTitle", "");    // ★ 最近播放那集的标题（弹窗置顶展示用）
                it.put("lastPos", 0);         // ★ 最近播放到第几秒（续播定位用）
                it.put("ts", System.currentTimeMillis());
                JSONArray eps0 = new JSONArray();
                it.put("eps", eps0);
                it.put("epsTotal", 0);
                it.put("epsState", 0);
                it.put("epsSrc", "");
                it.put("epsTs", 0);
                a.put(it);
            } catch (Throwable t) {
                return false;
            }
            write(a);
            added = true;
        }
        /* ★ v1.3.302：加完立即生成骨架（第1..count 集，rid 空）⇒ 网页弹窗秒开「空列表」，
           后台 Keeper 再逐页填真源。之前骨架只能等 Keeper 90s 一轮才建，导致弹窗被迫走
           慢速 NAS 现搜、等很久才出列表（且常等到已快绑完才显示）。必须在锁外调，
           否则 setEpsSkeleton 再抢 LOCK 会自锁。 */
        if (added && count > 0) {
            setEpsSkeleton(albumId, count, source);
        }
        return true;
    }

    /** 按 albumId 取消收藏。 */
    public static boolean del(String albumId) {
        if (albumId == null || albumId.trim().length() == 0) {
            return false;
        }
        synchronized (LOCK) {
            JSONArray a = list();
            JSONArray out = new JSONArray();
            boolean found = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && albumId.equals(it.optString("albumId", ""))) {
                    found = true;
                    continue;
                }
                out.put(it);
            }
            if (!found) {
                return false;
            }
            cache = out;
            write(out);
            return true;
        }
    }

    public static boolean has(String albumId) {
        if (albumId == null || albumId.trim().length() == 0) {
            return false;
        }
        synchronized (LOCK) {
            JSONArray a = list();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && albumId.equals(it.optString("albumId", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** ★ v1.3.287：按书名找收藏条目（归一化匹配，忽略空格/书名号/标点/大小写）。
     *  找不到返 null。用于语音 ab_play 优先用收藏版本 + 续播记忆。 */
    public static JSONObject findByName(String book) {
        if (book == null || book.trim().length() == 0) {
            return null;
        }
        String k = norm(book);
        synchronized (LOCK) {
            JSONArray a = list();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                if (norm(it.optString("book", "")).equals(k)
                        || k.indexOf(norm(it.optString("book", ""))) >= 0
                        || norm(it.optString("book", "")).indexOf(k) >= 0) {
                    return it;
                }
            }
        }
        return null;
    }

    /** ★ v1.3.308：按 albumId 找收藏条目（精确）。续播定位要用**这个版本**的进度，
     *  按书名找可能撞上同名别的版本。找不到返 null。 */
    public static JSONObject findByAlbum(String albumId) {
        if (albumId == null || albumId.trim().length() == 0) {
            return null;
        }
        synchronized (LOCK) {
            JSONArray a = list();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && albumId.equals(it.optString("albumId", ""))) {
                    return it;
                }
            }
        }
        return null;
    }

    /** ★ v1.3.287：记录「最近播放集数 + 标题」（开始播这一集时调用）。
     *
     *  <p>★ v1.3.308：**换集就把 lastPos 归零**。以前只动 lastEp、不动 lastPos，
     *  于是「第1630集听到 1:54 → 跳去第1640集 → 说继续听」会拿 1640 集配上 1630 集
     *  的 114 秒去定位，直接跳进半集里。位置永远只对「同一集」有意义。</p> */
    public static boolean setLastEp(String albumId, int ep, String title) {
        if (albumId == null || albumId.trim().length() == 0 || ep <= 0) {
            return false;
        }
        synchronized (LOCK) {
            JSONArray a = list();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && albumId.equals(it.optString("albumId", ""))) {
                    try {
                        int old = it.optInt("lastEp", 0);
                        it.put("lastEp", ep);
                        if (title != null) {
                            it.put("lastEpTitle", title);
                        }
                        if (old != ep) {
                            it.put("lastPos", 0);      // 换集 ⇒ 从头开始
                        }
                    } catch (Throwable t) {
                        return false;
                    }
                    write(a);
                    return true;
                }
            }
        }
        return false;
    }

    /** ★ v1.3.287：记录播放进度（集号 + 第几秒）。供网页/播放器周期性回写，
     *  用于「续播往前推 2 秒」。仅对已收藏条目生效。 */
    public static boolean setProgress(String albumId, int ep, int posSec) {
        if (albumId == null || albumId.trim().length() == 0 || ep <= 0) {
            return false;
        }
        synchronized (LOCK) {
            JSONArray a = list();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && albumId.equals(it.optString("albumId", ""))) {
                    try {
                        it.put("lastEp", ep);
                        it.put("lastPos", posSec > 0 ? posSec : 0);
                    } catch (Throwable t) {
                        return false;
                    }
                    write(a);
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean setEpsSkeleton(String albumId, int total, String source) {
        if (albumId == null || albumId.trim().length() == 0 || total <= 0) return false;
        synchronized (LOCK) {
            JSONArray a = list();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null || !albumId.equals(it.optString("albumId", ""))) continue;
                try {
                    int old = it.optInt("epsTotal", 0);
                    JSONArray eps = it.optJSONArray("eps");
                    if (eps == null) { eps = new JSONArray(); it.put("eps", eps); }
                    int maxEp = 0;
                    for (int k = 0; k < eps.length(); k++) {
                        JSONObject x = eps.optJSONObject(k);
                        if (x == null) continue;
                        int e = x.optInt("ep", 0);
                        if (e > maxEp) maxEp = e;
                    }
                    int newTotal = total > old ? total : old;
                    for (int e = maxEp + 1; e <= newTotal; e++) {
                        JSONObject o = new JSONObject();
                        o.put("ep", e);
                        o.put("title", "第" + e + "集");
                        o.put("artist", "");
                        o.put("source", source == null ? "" : source.trim());
                        o.put("rid", "");
                        o.put("filled", false);
                        eps.put(o);
                    }
                    it.put("epsTotal", newTotal);
                    it.put("epsSrc", source == null ? "" : source.trim());
                    if (old == 0) it.put("epsState", 1);
                    it.put("epsTs", System.currentTimeMillis());
                    write(a);
                    return true;
                } catch (Throwable t) { return false; }
            }
        }
        return false;
    }

    /**
     * ★ 1.3.333：历史「错误缓存」里缺集占位行的标题特征。
     * v1.6.x 遗留的是「（缺集 · 上游没有第 N 集）」，服务端 v1.7.9 起缺集行
     * 只输出「第 N 集」；真集是「书名 第N集 真名」，不会命中。
     * 带这种标题的行即使残留了假 rid，也要重新问服务端拿真名/真 rid。
     */
    public static boolean isDirtyEpTitle(String t) {
        String s = t == null ? "" : t.trim();
        if (s.length() == 0) return true;
        if (s.indexOf("缺集") >= 0 || s.indexOf("上游没有第") >= 0) return true;
        return s.matches("第\\s*\\d+\\s*集");
    }

    /**
     * ★ 1.3.334：短书名 —— 补《书名》前缀时不能用缓存里的**全名**。
     * 「仙逆丨多人剧丨曲中人有故事丨动漫热播原著丨耳根丨完结」拼上集号后，
     * 队列/播放页只见书名不见集数（用户实测反馈）。取常见副标题分隔符
     * （丨 | · ／ / 等）前的第一段当短名；第一段不足 2 字（歧义大）或没有
     * 分隔符就退回全名。
     */
    public static String shortBook(String b) {
        if (b == null) return "";
        String s = b.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.{1,24}?)\\s*[丨|·・／/].+$").matcher(s);
        if (m.matches()) {
            String g = m.group(1).trim();
            if (g.length() >= 2) return g;
        }
        return s;
    }

    public static boolean mergeEpsPage(String albumId, JSONArray page) {
        if (albumId == null || page == null || page.length() == 0) return false;
        synchronized (LOCK) {
            JSONArray a = list();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null || !albumId.equals(it.optString("albumId", ""))) continue;
                try {
                    JSONArray eps = it.optJSONArray("eps");
                    if (eps == null) { eps = new JSONArray(); it.put("eps", eps); }
                    for (int pp = 0; pp < page.length(); pp++) {
                        JSONObject e = page.optJSONObject(pp);
                        if (e == null) continue;
                        int ep = e.optInt("ep", 0);
                        if (ep <= 0) continue;
                        JSONObject hit = null;
                        for (int k = 0; k < eps.length(); k++) {
                            JSONObject x = eps.optJSONObject(k);
                            if (x == null) continue;
                            if (x.optInt("ep", 0) == ep) { hit = x; break; }
                        }
                        if (hit == null) { hit = new JSONObject(); hit.put("ep", ep); eps.put(hit); }
                        String rid = e.optString("rid", "");
                        String tt = e.optString("title", "");
                        /* ★ 1.3.333：只在「带 ep=N 的按需单点拉取」里带 srvMiss 标记。
                           Keeper 整本逐页预填（ep=0）不带，绝不因分页口径误清本地真 rid。
                           服务端 v1.7.10 统一了「没有 rid 就是缺集」，这里照它的结论把
                           v1.6.x 遗留的「假 rid + 旧占位文案」清成纯占位行 —— 不清的话
                           play/resolve 会拿假 rid 直接播（语音报「网络好像出了点问题」），
                           也永远不会触发 /api/ab/episodes?ep=N 的按需救援。 */
                        if (e.optBoolean("srvMiss", false) && rid.length() == 0
                                && isDirtyEpTitle(hit.optString("title", ""))) {
                            hit.put("rid", "");
                            hit.put("title", tt.length() > 0 ? tt : ("第" + ep + "集"));
                            hit.put("filled", false);
                            continue;
                        }
                        if (rid.length() > 0) hit.put("rid", rid);
                        if (tt.length() > 0) hit.put("title", tt);
                        String ar = e.optString("artist", "");
                        if (ar.length() > 0) hit.put("artist", ar);
                        String so = e.optString("source", "");
                        if (so.length() > 0) hit.put("source", so);
                        hit.put("filled", true);
                    }
                    int total = it.optInt("epsTotal", 0);
                    int filled = 0;
                    for (int k = 0; k < eps.length(); k++) {
                        JSONObject x = eps.optJSONObject(k);
                        if (x != null && x.optBoolean("filled", false)) filled++;
                    }
                    if (total > 0 && filled >= total) it.put("epsState", 2);
                    it.put("epsTs", System.currentTimeMillis());
                    write(a);
                    return true;
                } catch (Throwable t) { return false; }
            }
        }
        return false;
    }

    public static JSONArray getEps(String albumId) {
        if (albumId == null || albumId.trim().length() == 0) return null;
        synchronized (LOCK) {
            JSONArray a = list();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && albumId.equals(it.optString("albumId", ""))) {
                    return it.optJSONArray("eps");
                }
            }
        }
        return null;
    }

    public static JSONObject epsMeta(String albumId) {
        JSONObject m = new JSONObject();
        if (albumId == null || albumId.trim().length() == 0) return m;
        synchronized (LOCK) {
            JSONArray a = list();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && albumId.equals(it.optString("albumId", ""))) {
                    try {
                        m.put("total", it.optInt("epsTotal", 0));
                        m.put("state", it.optInt("epsState", 0));
                        m.put("ts", it.optLong("epsTs", 0));
                        m.put("src", it.optString("epsSrc", ""));
                    } catch (Throwable t) {}
                    break;
                }
            }
        }
        return m;
    }

    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s　《》〈〉()（）\\[\\]【】“”\"'‘’，。、：:！!？?~～|/\\\\.#\\-_]", "")
                .toLowerCase();
    }
}
