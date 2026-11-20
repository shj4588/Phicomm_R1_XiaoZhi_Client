package cn.workbuddy.r1client;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * 「我的节目」仿「我的收藏」的播放入口（v1.3.298）。
 *
 * <p>模型照抄收藏的 fav 模型（用户定调：收藏也是<b>播的时候临时生成</b>，不要求预绑定）：
 * 点击 = 整批占位 URL 入列（{@code /r/prog?aid=&ep=}）+ 目标集起播；
 * 播到占位行才解析 rid → 真链 → 302。目标集缓存里还没有 rid 时，
 * <b>就地现拉那一页</b>（一次 NAS 调用，≈1~2s）拿到精确 rid 再播 ——
 * 绝不回退「按书名全书搜索」（同名多版本会播错书/播错集）。</p>
 */
public final class ProgPlay {

    private static final String TAG = "R1ProgPlay";
    /** 与 PlayerBridge.BINDER_BUDGET 同值：一次 Binder 事务的 UTF-8 字节天花板。 */
    private static final int BINDER_BUDGET = 700000;
    /** NAS /api/ab/episodes 的页大小（与 abEpisodes 常规调用一致）。 */
    static final int PAGE_SIZE = 200;
    /** 整本最多多少页（8000 集封顶，防异常 total 拉爆）。 */
    static final int MAX_PAGES = 40;
    /** v1.3.299：当前正在播放的有声书（供网页「收藏当前书」用，含真实 albumId）。 */
    private static JSONObject sLastProg;
    static JSONObject lastProg() { return sLastProg; }

    private ProgPlay() {
    }

    /**
     * 点播整批占位入列 + 起播目标集。
     *
     * @return 成功 = OK 文本；缓存不可用/现拉也没拿到 rid = null（调用方兜底）
     */
    public static String play(JSONObject args) {
        try {
            String aid = args == null ? "" : args.optString("albumId", "").trim();
            String book = args == null ? "" : args.optString("book", "").trim();
            String nar = args == null ? "" : args.optString("narrator", "").trim();
            int ep = args == null ? 0 : args.optInt("ep", 0);
            if (ep <= 0) {
                return null;
            }
            if (aid.length() == 0 && book.length() > 0) {
                JSONObject it = Prog.findByName(book);
                if (it != null) {
                    aid = it.optString("albumId", "").trim();
                }
            }
            if (aid.length() == 0) {
                return null;
            }
            JSONObject pe = progEntry(aid);
            if (pe != null) {
                if (book.length() == 0) {
                    book = pe.optString("book", "").trim();
                }
                if (nar.length() == 0) {
                    nar = pe.optString("narrator", "").trim();
                }
            }
            // v1.3.299：记录当前正在播放的有声书（供网页「收藏当前书」用，含真实 albumId）
            try {
                JSONObject lp = new JSONObject();
                lp.put("albumId", aid);
                lp.put("book", book);
                lp.put("narrator", nar);
                lp.put("source", pe == null ? "" : pe.optString("source", ""));
                lp.put("count", pe == null ? 0 : pe.optInt("count", 0));
                sLastProg = lp;
            } catch (Throwable ignored) {}
            JSONArray eps = Prog.getEps(aid);
            int ti = locateEp(eps, ep);
            if (ti < 0) {
                return null;
            }
            JSONObject te = eps.optJSONObject(ti);
            String rid = te == null ? "" : te.optString("rid", "").trim();
            /* ★ v1.3.333：真机回归 —— v1.6.x 遗留的「错误缓存」行只有
               「（缺集 · 上游没有第 N 集）」/「第 N 集」文案，却可能带着假 rid。
               旧版只在 rid 为空时才现拉页，这批行直接拿假 rid 去播（语音报
               「网络好像出了点问题」），服务端也永远收不到 ep=N ⇒ 救不回来。
               改成「脏占位行」一律强制问服务端一次（ep=N 单点，零批量回源）。 */
            if (isDirtyPlace(te)) {
                /* ★ v1.3.298：播时临时解析（=收藏的「播到才搜」）——现拉目标集所在页，
                   拿到精确 rid 就直连；拿不到才交给调用方兜底。 */
                fillPage(book, aid, ep, pe);
                eps = Prog.getEps(aid);
                ti = locateEp(eps, ep);
                if (ti < 0) {
                    return null;
                }
                te = eps.optJSONObject(ti);
                rid = te == null ? "" : te.optString("rid", "").trim();
                if (rid.length() == 0) {
                    return null;
                }
            }
            // v1.3.302：改用 abQueueProgLazy —— 用已绑定 rid 整批入列（懒加载、不卡界面），
            // 并写死 loader/book 状态，使下一集/上一集/续播锁定本书（修复「跳回之前播的书下一集」
            // + 「大部分播不了」）。Prog 缓存命中时 rid 已在，无需再搜 NAS、不走易崩的 msgPush。
            try {
                Method m = PlayerBridge.class.getDeclaredMethod("abQueueProgLazy",
                        String.class, String.class, String.class, int.class,
                        JSONArray.class, int.class);
                m.setAccessible(true);
                String album = pe == null ? "" : pe.optString("albumId", "");
                String psrc = pe == null ? "" : pe.optString("source", "");
                int ptotal = pe == null ? 0 : pe.optInt("epsTotal", 0);
                Object r = m.invoke(null, book, album, psrc,
                        Integer.valueOf(ptotal), eps, Integer.valueOf(ep));
                if (r instanceof String) {
                    String bt = te == null ? "" : te.optString("title", "");
                    Prog.setLastEp(aid, ep, bt.length() > 0 ? bt : ("第" + ep + "集"));
                    Log.i(TAG, "play ep=" + ep + " aid=" + aid + " via abQueueProgLazy");
                    return (String) r;
                }
            } catch (Throwable t) {
                Log.w(TAG, "abQueueProgLazy: " + t);
            }
            return null;   // WebConsole 回退 ab_play 现搜兜底（带 albumId ⇒ 不会误用 loader 旧书）
        } catch (Throwable t) {
            Log.w(TAG, "play: " + t);
            return null;
        }
    }

    /**
     * 播到占位行才解析（仿 fav_resolve）。
     *
     * @return 成功 = 纯 URL（player 302）；失败 = "ERR ..."（player 404，引擎跳下一首）
     */
    public static String resolve(String aid, String epStr) {
        try {
            int ep = Integer.parseInt(epStr == null ? "0" : epStr.trim());
            aid = aid == null ? "" : aid.trim();
            if (aid.length() == 0 || ep <= 0) {
                return "ERR 缺少 aid/ep";
            }
            JSONObject e = findEp(aid, ep);
            if (e == null) {
                /* ★ v1.3.307：整本书都没缓存（首次点播、后台预填还没跑完）⇒ 先现拉那一页
                   再判，不再直接 404。冷缓存 NAS 枚举整本书约 15s，player 转发超时已放宽到
                   40s 等得起；后台预填（progPrefetchKick）命中后这条路基本不触发。 */
                JSONObject pe = progEntry(aid);
                fillPage(pe == null ? "" : pe.optString("book", ""), aid, ep, pe);
                e = findEp(aid, ep);
                if (e == null) {
                    return "ERR 缓存里没有第" + ep + "集";
                }
            }
            String rid = e.optString("rid", "").trim();
            if (isDirtyPlace(e)) {
                /* ★ v1.3.298：骨架行播到 ⇒ 现拉那一页再解析（收藏同款「播到才搜」，
                   只是慢一拍，不再直接 404 烧一片）。
                   ★ v1.3.333：脏占位行即使带假 rid 也要重问服务端（见 play 注释）。 */
                JSONObject pe = progEntry(aid);
                fillPage(pe == null ? "" : pe.optString("book", ""), aid, ep, pe);
                e = findEp(aid, ep);
                if (e == null) {
                    return "ERR 缓存里没有第" + ep + "集";
                }
                rid = e.optString("rid", "").trim();
                if (rid.length() == 0) {
                    return "ERR 第" + ep + "集还没缓存到播放源";
                }
            }
            String url = onlineUrl(e.optString("title", ""), e.optString("artist", ""),
                    e.optString("source", "").trim(), rid);
            if (url == null || url.length() == 0) {
                return "ERR 第" + ep + "集拿不到播放地址";
            }
            return url;
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    /**
     * 拉一页集表并入 Prog 缓存（Keeper 整本填全与播时现拉共用）。
     * Keeper 的整本逐页预填走 ep=0 —— 绝不批量回源救援（上游风控红线）。
     *
     * @return true = 拿到了行（是否新增由 mergeEpsPage 决定）
     */
    static boolean fetchAndMerge(String book, String albumId, int page, int size, String defSrc) {
        return fetchAndMerge(book, albumId, page, size, defSrc, 0);
    }

    /**
     * ★ v1.3.332：ep&gt;0 时把集号透传给 NAS /api/ab/episodes。
     * 服务端 v1.7.5 起「ep=N 且该集是占位」会自动做一次精确词救援，救回后
     * 这一页里该集就是真名 + 真 rid（v1.7.9 缺集行输出「第 N 集」，真集输出真名）。
     * 只在**真播到这一集 / 这一集没源**（play、resolve 的 fillPage）时带 ep，
     * 属按需回源；整本填页仍走 ep=0，禁止对几百个缺集批量搜索。
     */
    static boolean fetchAndMerge(String book, String albumId, int page, int size,
                                 String defSrc, int epTarget) {
        try {
            Method m = PlayerBridge.class.getDeclaredMethod("abEpisodes",
                    String.class, String.class, int.class, int.class, int.class);
            m.setAccessible(true);
            Object r = m.invoke(null, book, albumId, Integer.valueOf(page),
                    Integer.valueOf(size), Integer.valueOf(epTarget > 0 ? epTarget : 0));
            if (!(r instanceof JSONObject)) {
                return false;
            }
            JSONObject d = (JSONObject) r;
            JSONArray pageArr = d.optJSONArray("list");
            if (pageArr == null) {
                pageArr = d.optJSONArray("episodes");
            }
            if (pageArr == null) {
                pageArr = d.optJSONArray("items");
            }
            if (pageArr == null) {
                pageArr = d.optJSONArray("data");
            }
            if (pageArr == null || pageArr.length() == 0) {
                return false;
            }
            int total = d.optInt("total", d.optInt("count", 0));
            if (total > 0) {
                Prog.setEpsSkeleton(albumId, total, defSrc == null ? "" : defSrc);
            }
            JSONArray norm = new JSONArray();
            for (int j = 0; j < pageArr.length(); j++) {
                JSONObject e = pageArr.optJSONObject(j);
                if (e == null) {
                    continue;
                }
                int ep = e.optInt("ep", e.optInt("index", 0));
                if (ep <= 0) {
                    continue;
                }
                JSONObject o = new JSONObject();
                String erid = firstNonEmpty(e, "rid", "id", "audioId", "trackId");
                o.put("ep", ep);
                o.put("rid", erid);
                /* ★ v1.3.333：只在按需单点（ep=N）请求里带上「服务端明确说这集没源」
                   的标记，Prog.mergeEpsPage 据此清掉本地脏假 rid。整本预填 ep=0 不带，
                   避免分页/字段口径差异误清本地真 rid。 */
                if (epTarget > 0 && erid.length() == 0) o.put("srvMiss", true);
                o.put("title", firstNonEmpty(e, "title", "name", "epTitle"));
                o.put("artist", firstNonEmpty(e, "artist", "narrator", "author"));
                String s = firstNonEmpty(e, "source", "src");
                o.put("source", s.length() > 0 ? s : (defSrc == null ? "" : defSrc));
                norm.put(o);
            }
            if (norm.length() == 0) {
                return false;
            }
            Prog.mergeEpsPage(albumId, norm);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "fetchAndMerge: " + t);
            return false;
        }
    }

    /* ===================== 内部 ===================== */

    private static int locateEp(JSONArray eps, int ep) {
        for (int i = 0; eps != null && i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e != null && e.optInt("ep", 0) == ep) {
                return i;
            }
        }
        return -1;
    }

    /**
     * ★ v1.3.333：这一行要不要「强制重问服务端一次」。
     * 服务端 v1.7.10 已统一「没有 rid 就是缺集」，且会在 ep=N 时按需精确词救援；
     * 设备本地的历史错误缓存行（旧缺集文案 / 只有集号 / 带假 rid）必须重问，
     * 才能拿到真名 + 真 rid 覆盖本地，或变成纯占位行走 /r/prog 解析。
     */
    private static boolean isDirtyPlace(JSONObject e) {
        if (e == null) return true;
        if (e.optString("rid", "").trim().length() == 0) return true;
        if (e.optBoolean("missing", false)) return true;
        return Prog.isDirtyEpTitle(e.optString("title", ""));
    }

    private static JSONObject findEp(String aid, int ep) {
        JSONArray eps = Prog.getEps(aid);
        int i = locateEp(eps, ep);
        return i < 0 ? null : eps.optJSONObject(i);
    }

    private static JSONObject progEntry(String aid) {
        JSONArray ls = Prog.list();
        for (int i = 0; ls != null && i < ls.length(); i++) {
            JSONObject it = ls.optJSONObject(i);
            if (it != null && aid.equals(it.optString("albumId", "").trim())) {
                return it;
            }
        }
        return null;
    }

    /** 只拉目标集所在的那一页（一次 NAS 调用），拿到就并进缓存。
     *  ★ v1.3.332：带 ep 让服务端对这一集做按需救援 —— 缺集占位播到时才有机会
     *  换成真 rid + 真名（旧版 ep=0 只会拿回占位行，于是 resolve 永远 ERR）。 */
    private static void fillPage(String book, String aid, int ep, JSONObject pe) {
        if (aid.length() == 0) {
            return;
        }
        int page = (ep - 1) / PAGE_SIZE + 1;
        String src = pe == null ? "" : pe.optString("source", "").trim();
        boolean got = fetchAndMerge(book, aid, page, PAGE_SIZE, src, ep);
        Log.i(TAG, "fillPage aid=" + aid + " ep=" + ep + " page=" + page
                + " rescueEp=" + ep + " got=" + got);
    }

    /** 占位行标题：带书名（仿收藏/abQueueBook 风格）—— tail-kick、网页判据都靠它认书。 */
    private static String decoTitle(JSONObject e, int epNo, String book, String nar) {
        String t = e.optString("title", "").trim();
        if (t.length() == 0) {
            t = "第" + epNo + "集";
        }
        if (t.indexOf('《') < 0 && book != null && book.length() > 0) {
            t = t + " - 《" + (nar != null && nar.length() > 0 ? nar + "·" : "") + book + "》";
        }
        return t;
    }

    /** 反射 PlayerBridge.msg(15, json)：subType=15 保留 index，队列顺序不动。 */
    private static Boolean msgPush(String json) {
        try {
            Method m = PlayerBridge.class.getDeclaredMethod("msg", int.class, String.class);
            m.setAccessible(true);
            Object r = m.invoke(null, Integer.valueOf(15), json);
            return (r instanceof Boolean) ? (Boolean) r : Boolean.FALSE;
        } catch (Throwable t) {
            Log.w(TAG, "msgPush: " + t);
            return null;
        }
    }

    /** 反射 PlayerBridge.onlineUrl(title, artist, source, rid, ms)：NAS /api/url 拿直链。 */
    private static String onlineUrl(String title, String artist, String source, String rid) {
        try {
            Method m = PlayerBridge.class.getDeclaredMethod("onlineUrl",
                    String.class, String.class, String.class, String.class, int.class);
            m.setAccessible(true);
            Object r = m.invoke(null, title, artist, source, rid, Integer.valueOf(12000));
            return (r instanceof String) ? (String) r : "";
        } catch (Throwable t) {
            Log.w(TAG, "onlineUrl: " + t);
            return "";
        }
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            String v = o.optString(keys[i], "").trim();
            if (v.length() > 0 && !"null".equals(v)) {
                return v;
            }
        }
        return "";
    }
}
