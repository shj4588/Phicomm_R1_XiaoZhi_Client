package cn.workbuddy.r1web;

import android.content.Context;

import com.phicomm.speaker.player.model.PlayPositionInfo;
import com.phicomm.speaker.player.model.PlaySong;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跑在 player 主进程里的控制台（:8080）—— **8088 那套页面的完整宿主**。
 *
 * <p>★ 与 device 侧 8088 的分工：</p>
 * <pre>
 *   本地直供（不依赖小智，停用了照样有）：
 *      播放控制 / 队列 / 播放位置 / 总时长 / 播放模式 / 音量 / 小智开关
 *   反代到 8088（物理上只在 device 侧）：
 *      设置项 / 曲库搜索 / 电台 / 氛围灯 / 唤醒 / 重启 / 日志
 * </pre>
 *
 * <p>★ 返回格式必须与 8088 一致：页面 act()/setKV() 都按 {@code {"ok":..,"msg":..}}
 * 解析，所以这里不再吐裸文本。</p>
 *
 * <p>零依赖：手写 HTTP/1.0，单 accept 线程串行处理（控制台量级够用，
 * 也避免在原厂进程里起一堆线程）。所有异常都吞掉，绝不让 player 挂。</p>
 *
 * <p>加新 op / 新接口时记得三处同改：
 *   ① {@link #actOp} ② ui/player.html 的 JS ③ build 脚本的自检。</p>
 */
public final class WebSrv {

    public static final int DEF_PORT = 8080;

    static Context app;
    static int port = DEF_PORT;
    static volatile long started = 0;

    /* ★ pl-1.6.91：实时推送（Server-Sent Events）。device 物理按键改状态后打
       op=topkey ⇒ ssePush() 唤醒所有挂起的 SSE 线程各推一条 {type:status}，页面据此
       立刻 load() 一次，不必等 8s 轮询。 */
    private static final Set<Socket> SSE_SOCKS =
            Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private static final Object SSE_MON = new Object();
    private static volatile long SSE_GEN = 0;

    /* ★ pl-1.6.104：歌单导入进度（异步）。导入丢到后台线程跑，单 accept 线程
       立刻返回，页面永不冻结、退出重进也连得上。 */
    private static volatile boolean sFavImporting = false;
    private static volatile int sFavImportDone = 0;
    private static volatile int sFavImportTotal = 0;
    private static volatile String sFavImportMsg = "";

    /* ★ v1.3.286：电子书（有声书）搜索 / 集表 / 我的节目。player 直接打 NAS 8100 取 JSON
       （浏览器有 CORS，且 WebSrv 单 accept 线程不能同步等慢请求），结果存进下面的 holder，
       页面用 /result 轮询。播放与收藏走 device（:8088 主本）。 */
    private static volatile String sAbState = "idle";   // idle|pending|done|err
    private static volatile String sAbMsg = "";
    private static volatile JSONArray sAbBooks = null;
    private static volatile long sAbTs = 0;
    private static final Map<String, JSONObject> sEps = new ConcurrentHashMap<String, JSONObject>();
    /* ★ v1.2.0：NAS 本地有声书库（/api/abl/*）的异步结果 —— 与 sAbState 那套同构。
       WebSrv 是串行 accept，同步等 NAS（首次扫目录可能几秒）会拖住整页，所以也走「异步+轮询」。 */
    private static volatile String sAblState = "idle";   // idle|pending|done|err
    private static volatile String sAblMsg = "";
    private static volatile JSONArray sAblBooks = null;
    private static volatile boolean sAblOff = false;
    private static volatile String sAblDir = "";
    /* ★ pl-1.6.134：NAS 侧扫盘时间（ablocal 的 MEM.at）。网页拿它跟本地缓存比对 ——
       「重新载入曲库/重扫磁盘」后这个值会变，变了前端才重绘，没变就一声不响，
       省得每次进页面列表都跳一下。 */
    private static volatile long sAblAt = 0;

    static void ssePush() {
        SSE_GEN++;
        synchronized (SSE_MON) { SSE_MON.notifyAll(); }
    }

    public static synchronized void start(Context c) {
        if (started > 0) {
            return;
        }
        app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "r1web-http").start();
    }

    private static void loop() {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            started = System.currentTimeMillis();
            Boot.log("控制台起来了：http://" + localIp() + ":" + port + "/");
        } catch (Throwable t) {
            Boot.log("bind " + port + " 失败: " + t);
            return;
        }
        /* ★ pl-1.6.157：改**每连接一线程**（镜像 device WebConsole 的 acceptLoop）。
           为什么必须改：旧实现 accept 后在同一个线程里 handle(s)，任何一个慢请求
           都会把整个控制台堵死 —— 页面每 8s load() 会拉一次 /api/prog（反代 device
           prog_list，v1.3.339 起还要同步拉服务端 /v1/history，链路超时 2.5~5s），
           这条链一慢，期间点的暂停/播放全排在 accept 线程队尾 ⇒ 「点暂停/播放
           要过一会儿才起效」，而且时好时坏（跟着服务端/网络快慢走），修了多少次
           都「过段时间又复发」。多线程后慢请求只堵它自己，控制键永远即时。
           并发安全性：SSE_SOCKS 本就是 ConcurrentHashMap 支撑的集合；慢接口
           （ab/abl/fav 导入）早已是「异步 + 轮询」模型；PCtl 的播放控制是短
           binder 调用且关键记账均有 synchronized —— device 侧同构跑了一年无恙。 */
        while (true) {
            Socket s = null;
            try {
                s = ss.accept();
                final Socket sock = s;
                Thread w = new Thread(new Runnable() {
                    public void run() {
                        try {
                            handle(sock);
                        } catch (Throwable t) {
                            // ignore
                        } finally {
                            try {
                                // ★ pl-1.6.91：交給 SSE 线程常驻的连接（在 SSE_SOCKS 里）不能关，
                                //   它由 serveSse 的线程在用完时自己关。
                                if (!SSE_SOCKS.contains(sock)) {
                                    sock.close();
                                }
                            } catch (Throwable t) {
                                // ignore
                            }
                        }
                    }
                }, "r1web-conn");
                w.setDaemon(true);
                w.start();
            } catch (Throwable t) {
                // accept 失败：稍歇再收，避免错误风暴
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void handle(Socket s) throws Exception {
        s.setSoTimeout(10000);
        InputStream in = s.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line = br.readLine();
        if (line == null) {
            return;
        }
        String method = line.split(" ")[0];
        // 读完请求头，顺带记下 Content-Length / If-None-Match
        int bodyLen = 0;
        String inm = null;
        while (true) {
            String h = br.readLine();
            if (h == null || h.length() == 0) {
                break;
            }
            String hl = h.toLowerCase();
            if (hl.startsWith("content-length:")) {
                try {
                    bodyLen = Integer.parseInt(h.substring(15).trim());
                } catch (Throwable t) {
                    bodyLen = 0;
                }
            } else if (hl.startsWith("if-none-match:")) {
                inm = h.substring(14).trim();
            }
        }
        String body = "";
        if (bodyLen > 0 && "POST".equals(method)) {
            /* ★ pl-1.6.62：按【字节】读 body。Content-Length 是字节数，而 BufferedReader
               读的是字符数；中文等多字节 UTF-8 下字符数 < 字节数，若按 bodyLen 个字符去读
               会一直阻塞等更多字符（永远不来）⇒ 空响应 / 导入挂起。这里逐字符读、用 UTF-8
               字节长度计数，读到够 bodyLen 字节即停。仍走 br，不混用原始 InputStream，
               避免 BufferedReader 的预读缓冲吞掉 body 字节。 */
            StringBuilder bb = new StringBuilder();
            int byteCount = 0;
            while (true) {
                int c = br.read();
                if (c < 0) {
                    break;
                }
                bb.append((char) c);
                /* ★ pl-1.6.104：增量累计 UTF-8 字节数。原写法每读一个字符就把整段 bb
                   重新 getBytes 量长度 —— 大 body 下是 O(N²)，105KB 的歌单会卡几分钟，
                   且发生在唯一 accept 线程上、handler 还没起来，整页冻结。
                   这里按 UTF-16 code unit 增量推算原 UTF-8 字节数（高代理项记 4 字节、
                   低代理项 0；其余按码点范围），O(N)。 */
                if (c >= 0xD800 && c <= 0xDBFF) {
                    byteCount += 4;          // 高代理项（整对 4 字节）
                } else if (c >= 0xDC00 && c <= 0xDFFF) {
                    // 低代理项：4 字节已在高代理项记过
                } else if (c <= 0x7F) {
                    byteCount += 1;
                } else if (c <= 0x7FF) {
                    byteCount += 2;
                } else {
                    byteCount += 3;
                }
                if (byteCount >= bodyLen) {
                    break;
                }
            }
            body = bb.toString();
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            return;
        }
        String raw = parts[1];
        int q = raw.indexOf('?');
        String path = q >= 0 ? raw.substring(0, q) : raw;
        Map<String, String> query = parse(q >= 0 ? raw.substring(q + 1) : "");
        /* ★★ v1.1.1：页面 act()/setKV() 一律用 POST + form body 发参数（op=xxx&v=1）。
             只读 query 会全部拿到 null ⇒ 每个按钮都回「缺 op」（pv1/pv2 的 bug）。
             form body 必须合并进参数表（与 device 侧 8088 同策略：body 优先）。 */
        if (body.length() > 0) {
            query.putAll(parse(body));
        }
        OutputStream os = s.getOutputStream();
        try {
            route(os, s, path, query, body, inm);
        } catch (Throwable t) {
            try {
                txt(os, 500, jsonOk(false, "ERR " + t));
            } catch (Throwable t2) {
                // ignore
            }
        }
        os.flush();
    }

    private static void route(OutputStream os, Socket sock, String path, Map<String, String> q,
            String body, String inm) throws Exception {
        if (path.equals("/") || path.equals("/index.html")) {
            String html = Ui.html();
            if ("1".equals(q.get("embed"))) {
                html = html.replace("<body>", "<body class=\"embed\">");
            }
            /* ★ pl-1.6.147：这里是「改完 UI 得刷页面才生效」的真正原因。
               原来给的是 `public, max-age=600` —— 浏览器整页缓存 10 分钟，
               Service Worker 的 fetch 也照样命中这份 HTTP 缓存，所以换了几版页面
               用户在浏览器里看到的还是旧的（这个坑害过好几次：限高、搜索清空、
               本地节目都「改了没反应」）。
               现在改成 no-cache + ETag：每次打开都向设备验证一次，
               内容没变只回 304（几百字节，局域网 1ms 级，比 334KB 还快），
               内容一变（Boot.VER 或页面文本变了）立刻 200 给新页，无需强刷。 */
            byte[] hb = html.getBytes("UTF-8");
            String etag = "\"" + Boot.VER + "-" + Integer.toHexString(html.hashCode()) + "\"";
            String cc = "Cache-Control: no-cache\r\nETag: " + etag + "\r\n";
            if (inm != null && inm.indexOf(etag) >= 0) {
                writeEmpty(os, 304, "Not Modified", cc);
                return;
            }
            writeBytes(os, 200, "text/html; charset=utf-8", hb, cc);
            return;
        }
        if (path.equals("/api/status")) {
            json(os, statusJson().toString());
            return;
        }
        /* ★ pl-1.6.66：收藏里「本地库没有、导入时也没搜过源」的那批歌，排进播放
           列表时拿的是占位地址 http://127.0.0.1:8080/r/fav?k=<key>。播到它时引擎
           来取 ⇒ 这时才回头问 device（op=fav_resolve）现搜一次，搜到就 302 跳到
           真的惰性地址（/r/<source>/<rid>）；搜不到给 404，引擎跳下一首。
           ⇒ 导入 357 首的歌单不再为上百首歌各搜一遍（原来 60~80s，页面假失败）。 */
        if (path.equals("/r/fav")) {
            favResolveRedirect(os, q);
            return;
        }
        /* ★ pl-1.6.121：我的节目仿收藏 —— 整批占位行（url=/r/prog?aid=&ep=）播到才解析。
           device op=prog_resolve：查 Prog 缓存该集 rid → NAS /api/url 拿真链 → 302。 */
        if (path.equals("/r/prog")) {
            progResolveRedirect(os, q);
            return;
        }
        /* ★★ 设备状态（CPU/内存/温度/存储/进程）—— 本地采集，不依赖小智。
           不塞进 /api/status：CPU 要睡 250ms 取两次 /proc/stat，
           status 是 8s 一轮的主心跳，别让它跟着变慢。页面单独 5s 拉这个。 */
        if (path.equals("/api/device")) {
            json(os, Sys.sample("1".equals(q.get("cpu"))).toString());
            return;
        }
        /* 蓝牙状态（开关 / 已配对 / 扫到的 / A2DP 连接）—— 标准 BluetoothAdapter API，
           与小飞的 WS 命令（BT_enable / BT_Device / BT_createBond …）无关，
           我们注入的是原厂 player，没有那套服务端。 */
        if (path.equals("/api/bt")) {
            json(os, Bt.state().toString());
            return;
        }
        /* 外部播放源（DLNA / AirPlay）在不在 —— 看 :DlnaPlayer / :AirPlayer 进程。 */
        if (path.equals("/api/svc")) {
            json(os, Svc.state().toString());
            return;
        }
        // ★ player 自己的队列 = 钩子抓到的那张（就是 player 手里正在放的那张），
        //   比 device 侧「最后一次 push 的记录」更真。
        if (path.equals("/api/queue")) {
            int lim = 0;
            try {
                lim = Integer.parseInt(q.get("limit"));
            } catch (Throwable t) {
                lim = 0;
            }
            json(os, PCtl.queueJson(lim).toString());
            return;
        }
        // 日志：优先 device 的（对话/唤醒都在那边），停用了才回落到 player 自己的
        // ?local=1 强制看 player 自己的（排障注入层时很有用）
        if (path.equals("/api/log")) {
            String qs = "/api/log?n=" + def(q.get("n"), "80")
                    + ("1".equals(q.get("chat")) ? "&chat=1" : "");
            if ("1".equals(q.get("local"))) {
                int ln = 80;
                try {
                    ln = Integer.parseInt(q.get("n"));
                } catch (Throwable t) {
                    ln = 80;
                }
                txt(os, 200, "(player 侧日志)\n" + Boot.logs(ln));
                return;
            }
            String s = Proxy.get(qs, 2500);
            if (s == null || s.length() == 0) {
                s = "(小智未运行，以下是 player 侧日志)\n" + Boot.logs(80);
            }
            txt(os, 200, s);
            return;
        }
        // 设置：只能转给 device（files/ 在它那儿）
        if (path.equals("/api/set")) {
            String r = Proxy.post("/api/set", body.length() > 0 ? body
                    : buildQuery(q), 4000);
            Proxy.invalidate();
            json(os, r != null ? r
                    : jsonOk(false, "小智未运行（8088 不可达），设置项改不了"));
            return;
        }
        // 曲库搜索：曲库在 device 侧（从 NAS mini-lx 拉的）
        if (path.equals("/api/search")) {
            // v1.3.168：k 留空 = 列全部曲库；limit 原样转给 device（<=0 = 不截断）
            // v1.3.175：online=1 透传给 device —— 池内没搜到时它会附带 NAS 全源在线结果。
            //           在线要跑六级取链兜底，8s 不够（跨源 + lxserver 中继），给 30s。
            // v1.3.178：random=1（在线随机一批）和 olimit（条数）也要逐个透传 ——
            //   反代漏一个参数，8080 页面上「在线搜歌」就静悄悄退化成池内搜索，
            //   用户看到的是「点了没变化」，而 8088 直连一切正常（最难查的那类 bug）。
            String ol = def(q.get("online"), "");
            String rd = def(q.get("random"), "");
            String lim2 = def(q.get("olimit"), "");
            /* pl-1.6.31：shuffle=1（留空列全部时洗牌）也必须透传 —— 漏了就是
               「留空搜索每次都是那几首」，而 8088 直连一切正常（反代漏参的老坑，
               这次轮到自己踩）。★ 以后加新搜索参数，记得同步这里。 */
            String sh = def(q.get("shuffle"), "");
            String r = Proxy.get("/api/search?k=" + enc(def(q.get("k"), ""))
                    + "&limit=" + def(q.get("limit"), "60")
                    + (ol.length() > 0 ? "&online=" + enc(ol) : "")
                    + (rd.length() > 0 ? "&random=" + enc(rd) : "")
                    + (lim2.length() > 0 ? "&olimit=" + enc(lim2) : "")
                    + (sh.length() > 0 ? "&shuffle=" + enc(sh) : ""),
                    (ol.length() > 0 || rd.length() > 0) ? 25000 : 8000);
            if (r != null) {
                json(os, r);
                return;
            }
            json(os, "{\"list\":[],\"note\":\"小智未运行（8088 不可达），搜不了曲库\"}");
            return;
        }
        // v1.3.188：歌词（+封面）反代 —— 词在 device 侧（经 NAS mini-lx 从落雪拿）。
        //   ?t=曲名&a=歌手 逐个透传；不给就取「当前曲目」。
        //   首次要现场取词（跨源匹配 + 落雪），给 12s，别让页面先判失败。
        if (path.equals("/api/lrc")) {
            String t = def(q.get("t"), "");
            String a = def(q.get("a"), "");
            String r = Proxy.get("/api/lrc?t=" + enc(t) + "&a=" + enc(a), 12000);
            if (r != null) {
                json(os, r);
                return;
            }
            json(os, "{\"ok\":false,\"msg\":\"小智未运行（8088 不可达），取不到歌词\"}");
            return;
        }
        /* ★ pl-1.6.42：收藏列表。
             ① op=cur —— device 的语音侧问「现在播的是哪首」（它要拿完整 url 去收藏），
                player 自己是播放态的真相源，本地直接答，一次 HTTP 都不用往外出；
             ② 其余 —— 反代 device 的 /api/fav（主本在那儿，页面拉列表用）。 */
        /* ★ pl-1.6.79：闹钟。主本在 device（files/r1_alarms.json），这里只反代。
           页面在闹钟 tab 里 5s 拉一次 —— ring（正在响）只有 device 知道，别缓存。
           device 不可达时给一个空壳 + 说明，页面照旧能渲染（列表显示为空）。 */
        if (path.equals("/api/alarm")) {
            String av = Proxy.get("/api/alarm", 4000);
            if (av != null && av.length() > 0) {
                json(os, av);
                return;
            }
            json(os, "{\"ok\":false,\"total\":0,\"ring\":\"\",\"ringTitle\":\"\","
                    + "\"list\":[],\"msg\":\"小智未运行（8088 不可达），闹钟状态取不到\"}");
            return;
        }
        if (path.equals("/api/fav")) {
            if ("cur".equals(def(q.get("op"), ""))) {
                JSONObject fo = new JSONObject();
                try {
                    fo.put("ok", true);
                    JSONObject c = new JSONObject();
                    PlaySong fs = PCtl.song();
                    if (fs != null) {
                        c.put("title", nz(fs.getTitle()));
                        c.put("artist", nz(fs.getArtist()));
                        c.put("url", nz(fs.getUrl()));
                        c.put("itemId", nz(fs.getItemId()));
                    }
                    c.put("radio", PCtl.isRadio());
                    fo.put("cur", c);
                } catch (Throwable t) {
                    Boot.log("fav cur: " + t);
                }
                json(os, fo.toString());
                return;
            }
            String fv = Proxy.get("/api/fav", 3000);
            if (fv != null && fv.length() > 0) {
                /* ★ v1.3.231：给每条收藏补一个 miss 标记 —— 仅当「已尝试解析且确实没配上」
                   （PCtl.favFailed 命中、且还没解析成功）。这样收藏列表里待解析的占位歌
                   默认**不红**，只有真匹配不上的才红（「尝试匹配以后没匹配上才红」）。 */
                String out = fv;
                try {
                    JSONObject o = new JSONObject(fv);
                    if (o.optBoolean("ok", false)) {
                        JSONArray a = o.optJSONArray("list");
                        if (a != null) {
                            for (int i = 0; i < a.length(); i++) {
                                JSONObject it = a.optJSONObject(i);
                                if (it == null) {
                                    continue;
                                }
                                String k = it.optString("k", "");
                                if (k.length() == 0) {
                                    k = Fav.key(it.optString("title", ""),
                                            it.optString("artist", ""));
                                }
                                if (PCtl.favFailed(k) && PCtl.favReal(k) == null) {
                                    it.put("miss", true);
                                }
                                /* ★ pl-1.6.128：失败次数给页面 —— 页面的「✕ 忽略红标」
                                   只在次数没变时有效；又失败一次 ⇒ 重新标红。 */
                                it.put("missN", PCtl.favFailN(k));
                            }
                        }
                    }
                    out = o.toString();
                } catch (Throwable t) {
                    Boot.log("fav augment: " + t);
                }
                json(os, out);
                return;
            }
            json(os, "{\"ok\":false,\"total\":0,\"list\":[],\"note\":"
                    + "\"小智未运行（8088 不可达），收藏列表读不到\"}");
            return;
        }
        /* pl-1.6.62：收藏导入 / 导出（txt 格式，记事本可直接编辑）。
           导入支持的每行样式：
             · 歌名 - 歌手            （最常用，控制台导出的就是这种）
             · 歌名                   （只有歌名也行）
             · 歌名 | 歌手 | 地址 | 专辑 | 时长   （富格式，精确备份用）
             · 歌名 - 歌手 | 地址     （导出时带播放地址的写法）
           ★ pl-1.6.78：上面每种写法后面都可以再跟「绑定信息」列（导出会自带）：
               | src=wy | rid=548950024 | rman=1 | lt=本地曲库名 | la=本地歌手
             导入时原样交给 device 的 fav_add ⇒ 重传后不用再手动绑一次。
           以 # 开头的行是注释，空行忽略。 */
        if (path.equals("/api/fav/export")) {
            String fv = Proxy.get("/api/fav", 3000);
            if (fv == null || fv.length() == 0) {
                txt(os, 502, "小智未运行（8088 不可达），收藏列表读不到");
                return;
            }
            String txtOut = favToText(fv);
            String fn = "r1_fav_" + ymd() + ".txt";
            try {
                writeBytes(os, 200, "text/plain; charset=utf-8",
                        txtOut.getBytes("UTF-8"),
                        "Content-Disposition: attachment; filename=\"" + fn + "\"\r\n");
            } catch (Throwable t) {
                txt(os, 500, "export: " + t);
            }
            return;
        }
        if (path.equals("/api/fav/import")) {
            String raw = (body == null) ? "" : body;
            if (raw.trim().length() == 0) {
                json(os, jsonOk(false, "没有收到文件内容"));
                return;
            }
            JSONArray arr;
            try {
                arr = favFromText(raw);
            } catch (Throwable t) {
                json(os, jsonOk(false, "解析失败：" + t));
                return;
            }
            int n = (arr == null) ? 0 : arr.length();
            if (n == 0) {
                json(os, jsonOk(false, "文件里没有有效歌曲行（全是注释或空行？）"));
                return;
            }
            /* ★ pl-1.6.104：异步导入。fav_clear + 批量写盘丢到后台线程，单 accept 线程
               立刻返回，页面永不冻结、退出重进也连得上。进度查 /api/fav/import/status。 */
            sFavImporting = true;
            sFavImportDone = 0;
            sFavImportTotal = n;
            sFavImportMsg = "解析完成，开始导入 " + n + " 首";
            final JSONArray fArr = arr;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        String clr = Proxy.post("/api/act", "op=fav_clear", 8000);
                        if (clr == null) {
                            sFavImportMsg = "小智未运行（8088 不可达），导入失败";
                            return;
                        }
                        int ok = 0;
                        final int CHUNK = 150;
                        for (int start = 0; start < n; start += CHUNK) {
                            JSONArray part = new JSONArray();
                            for (int i = start; i < n && i < start + CHUNK; i++) {
                                JSONObject it = fArr.optJSONObject(i);
                                if (it != null
                                        && it.optString("title", "").trim().length() > 0) {
                                    part.put(it);
                                }
                            }
                            if (part.length() == 0) {
                                continue;
                            }
                            String r = Proxy.post("/api/act",
                                    "op=fav_import_batch&data=" + enc(part.toString()), 20000);
                            /* ★ pl-1.6.105：device 把 /api/act 响应包成 JSON 信封
                               {"ok":true,"msg":"OK 批量导入完成：..."}，整段以 '{' 开头，
                               不再是裸 "OK" 开头。这里按信封判成功（兼容老格式：裸 OK 开头）。 */
                            boolean okBatch = false;
                            if (r != null) {
                                try {
                                    JSONObject o = new JSONObject(r);
                                    okBatch = o.optBoolean("ok", false)
                                            || o.optString("msg", "").startsWith("OK");
                                } catch (Throwable t) {
                                    okBatch = r.startsWith("OK");
                                }
                            }
                            if (okBatch) {
                                ok += part.length();
                            } else {
                                /* 未知 op / 上限 / 解析失败 ⇒ 这块退回逐条模式 */
                                for (int i = 0; i < part.length(); i++) {
                                    JSONObject it = part.optJSONObject(i);
                                    if (it == null) {
                                        continue;
                                    }
                                    Proxy.post("/api/act", favAddBody(it), 8000);
                                }
                            }
                            sFavImportDone += part.length();
                        }
                        Fav.invalidate();
                        sFavImportMsg = "OK 已导入 " + ok + " 首收藏（后台完成）";
                    } catch (Throwable t) {
                        sFavImportMsg = "导入异常：" + t;
                    } finally {
                        sFavImporting = false;
                    }
                }
            }, "fav-import").start();
            json(os, jsonOk(true, "导入任务已启动（" + n + " 首），后台处理中，完成后自动刷新"));
            return;
        }
        if (path.equals("/api/fav/import/status")) {
            try {
                JSONObject st = new JSONObject();
                st.put("importing", sFavImporting);
                st.put("done", sFavImportDone);
                st.put("total", sFavImportTotal);
                st.put("msg", sFavImportMsg == null ? "" : sFavImportMsg);
                json(os, st.toString());
            } catch (Throwable t) {
                txt(os, 500, "status: " + t);
            }
            return;
        }
        /* ★ pl-1.6.159：播放来源标签直连服务端 v1.7.31 的权威判定。
           浏览器直接跨Origin问 8100 会被 CORS 拦，这里走 player 自己反代；
           只做一次短超时 GET，页面侧按「曲目|标题」缓存，不会增加轮询压力。 */
        if (path.equals("/api/src")) {
            String su = q.get("url");
            if (su == null) {
                su = "";
            }
            String sq = q.get("q");
            if (sq == null) {
                sq = "";
            }
            String st = q.get("title");
            if (st == null) {
                st = "";
            }
            try {
                String b = nasBase();
                if (b == null) {
                    json(os, jsonOk(false, "取不到音乐服务器地址"));
                    return;
                }
                String js = httpGet(b + "/api/src?url=" + enc(su) + "&q=" + enc(sq)
                        + "&title=" + enc(st), 4000);
                if (js == null) {
                    json(os, jsonOk(false, "来源查询没应答"));
                    return;
                }
                json(os, js);
            } catch (Throwable t) {
                json(os, jsonOk(false, "来源查询失败：" + t));
            }
            return;
        }
        /* ============ v1.3.286：电子书（有声书） ============ */
        if (path.equals("/api/absearch")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            final String fq = rq.optString("q", "").trim();
            if (fq.length() == 0) { json(os, jsonOk(false, "请输入书名")); return; }
            sAbState = "pending"; sAbMsg = "搜索中…"; sAbBooks = null;
            new Thread(new Runnable() { public void run() {
                try {
                    String b = nasBase();
                    if (b == null) { sAbState="err"; sAbMsg="取不到音乐服务器地址（小智状态读不到）"; return; }
                    String js = httpGet(b + "/api/ab/search?q=" + enc(fq) + "&n=20", 20000);
                    if (js == null) { sAbState="err"; sAbMsg="有声书搜索没应答"; return; }
                    JSONObject o = new JSONObject(js);
                    JSONArray books = o.optJSONArray("books");
                    sAbBooks = (books == null) ? new JSONArray() : books;
                    sAbState = "done"; sAbMsg = "找到 " + sAbBooks.length() + " 本";
                } catch (Throwable t) { sAbState="err"; sAbMsg="搜索异常：" + t; }
                finally { sAbTs = System.currentTimeMillis(); }
            }}, "eb-search").start();
            json(os, jsonOk(true, "搜索中…"));
            return;
        }
        if (path.equals("/api/absearch/result")) {
            try {
                JSONObject r = new JSONObject();
                r.put("state", sAbState);
                r.put("msg", sAbMsg);
                r.put("books", sAbBooks == null ? new JSONArray() : sAbBooks);
                json(os, r.toString());
            } catch (Throwable t) { txt(os, 500, "result: " + t); }
            return;
        }
        /* ★ pl-1.6.151：点缺集时先带 ep=N 问服务端（v1.7.5 自动救援）。
           一次**按需**精确词救援拿真名 + rid，再交给 /api/abplay 直接播：
           播放栏一上来就是真名，且救援成功时服务端已预热取链 ⇒ 点击秒开。
           救不回 ⇒ missing:true，页面提示「这集暂时没有源」，不硬播非音频。 */
        if (path.equals("/api/abep")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            String aid = rq.optString("albumId", "").trim();
            String bk = rq.optString("book", "").trim();
            int ep = rq.optInt("ep", 0);
            if (aid.length() == 0 || ep <= 0) { json(os, jsonOk(false, "缺少 albumId/ep")); return; }
            try {
                String b = nasBase();
                if (b == null) { json(os, jsonOk(false, "取不到音乐服务器地址")); return; }
                String js = httpGet(b + "/api/ab/episodes?albumId=" + enc(aid)
                        + (bk.length() > 0 ? "&q=" + enc(bk) : "")
                        + "&ep=" + ep + "&size=20", 40000);
                if (js == null) { json(os, jsonOk(false, "集表没应答")); return; }
                JSONObject o = new JSONObject(js);
                JSONArray eps = o.optJSONArray("episodes");
                JSONObject hit = null;
                for (int i = 0; eps != null && i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e != null && e.optInt("ep", 0) == ep) { hit = e; break; }
                }
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("rev", o.optInt("rev", 0));
                out.put("ep", ep);
                out.put("missing", hit == null || hit.optBoolean("missing", false)
                        || hit.optString("rid", "").trim().length() == 0);
                if (hit != null) {
                    out.put("rid", hit.optString("rid", ""));
                    out.put("title", hit.optString("title", ""));
                    out.put("source", hit.optString("source", ""));
                    out.put("artist", hit.optString("artist", ""));
                }
                json(os, out.toString());
            } catch (Throwable t) { json(os, jsonOk(false, "救援失败：" + t)); }
            return;
        }
        /* ★ pl-1.6.151：集表轻量复查 —— 只问 /api/ab/cache?key=..&full=1
           （服务端**纯缓存直读、绝不回源**），拿 rev + 精简集表。
           别的设备救援 / 补真名会让 rev++，页面据此把当前列表原地刷成真名。
           15s 一次、零上游请求，不碰风控红线。 */
        if (path.equals("/api/abrev")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            String aid = rq.optString("albumId", "").trim();
            String src = rq.optString("source", "kw").trim();
            if (src.length() == 0) { src = "kw"; }
            if (aid.length() == 0) { json(os, jsonOk(false, "缺少 albumId")); return; }
            try {
                String b = nasBase();
                if (b == null) { json(os, jsonOk(false, "取不到音乐服务器地址")); return; }
                String js = httpGet(b + "/api/ab/cache?key=" + enc(src + "|" + aid)
                        + "&full=1", 15000);
                if (js == null) { json(os, jsonOk(false, "rev 没应答")); return; }
                JSONObject o = new JSONObject(js);
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("rev", o.optInt("rev", 0));
                out.put("total", o.optInt("total", 0));
                out.put("missing", o.optInt("missing", 0));
                JSONArray list = o.optJSONArray("list");
                JSONArray outEps = new JSONArray();
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject e = list.optJSONObject(i);
                    if (e == null) { continue; }
                    JSONObject x = new JSONObject();
                    x.put("ep", e.optInt("ep", 0));
                    x.put("title", e.optString("title", ""));
                    x.put("rid", e.optString("rid", ""));
                    x.put("source", e.optString("source", o.optString("source", src)));
                    x.put("artist", o.optString("narrator", ""));
                    /* ★ pl-1.6.152：与服务端 v1.7.10 同口径 —— 没有 rid 就是缺集。
                       旧版缓存行只有 rid:'' 没打 missing，页面 ebRevApply 会当成
                       「既无 rid 又无 missing」而拒绝覆盖本地假 rid 脏行。 */
                    x.put("missing", e.optBoolean("missing", false)
                            || e.optString("rid", "").trim().length() == 0);
                    x.put("rescued", e.optBoolean("rescued", false));
                    outEps.put(x);
                }
                out.put("episodes", outEps);
                json(os, out.toString());
            } catch (Throwable t) { json(os, jsonOk(false, "rev 异常：" + t)); }
            return;
        }
        if (path.equals("/api/abepisodes")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            final String aid = rq.optString("albumId", "").trim();
            final String bk = rq.optString("book", "").trim();   // ★ pl-1.6.117：带上书名→NAS 强词查询
            final int pg = Math.max(1, rq.optInt("page", 1));
            if (aid.length() == 0) { json(os, jsonOk(false, "缺少 albumId")); return; }
            final String key = aid + ":" + pg;
            try { JSONObject h = new JSONObject(); h.put("state","pending"); h.put("msg","加载中…"); sEps.put(key, h); } catch (Throwable ignore) { }
            new Thread(new Runnable() { public void run() {
                try {
                    String b = nasBase();
                    if (b == null) { sEps.put(key, errHolder("取不到音乐服务器地址")); return; }
                    String js = httpGet(b + "/api/ab/episodes?albumId=" + enc(aid)
                            + (bk.length() > 0 ? "&q=" + enc(bk) : "")   // ★ 只带 albumId 时 NAS 拿数字当搜索词，上游不稳定→时有时无
                            + "&page=" + pg + "&size=20", 20000);
                    if (js == null) { sEps.put(key, errHolder("集表没应答")); return; }
                    JSONObject o = new JSONObject(js);
                    JSONObject h = new JSONObject();
                    h.put("state", "done");
                    h.put("book", o.optString("book", ""));
                    h.put("narrator", o.optString("narrator", ""));
                    h.put("albumId", o.optString("albumId", ""));
                    h.put("total", o.optInt("total", 0));
                    h.put("page", o.optInt("page", pg));
                    h.put("size", o.optInt("size", 20));
                    h.put("hasMore", o.optBoolean("hasMore", false));
                    h.put("rev", o.optInt("rev", 0));                  // ★ pl-1.6.151 集表修订号
                    h.put("source", o.optString("source", "kw"));      // ★ 拼 /api/ab/cache 的 key 用
                    JSONArray eps = o.optJSONArray("episodes");
                    h.put("episodes", eps == null ? new JSONArray() : eps);
                    h.put("msg", "");
                    sEps.put(key, h);
                } catch (Throwable t) { sEps.put(key, errHolder("集表异常：" + t)); }
            }}, "eb-eps").start();
            json(os, jsonOk(true, "加载中…"));
            return;
        }
        if (path.equals("/api/abepisodes/result")) {
            String key = def(q.get("albumId"), "") + ":" + atoi(q.get("page"), 1);
            JSONObject h = sEps.get(key);
            json(os, h == null ? "{\"state\":\"pending\",\"msg\":\"\",\"episodes\":[]}" : h.toString());
            return;
        }
        /* ============ v1.2.0：NAS 本地有声书库（/api/abl/*） ============
           NAS 侧已把「书名 → 合集/子书 → 集」和播放记忆算好，这里只透传。
           与在线节目（absearch/abepisodes 走落雪）并列，「我的节目」分两栏显示。 */
        if (path.equals("/api/abl/books")) {
            final String fq = def(q.get("q"), "");
            sAblState = "pending"; sAblMsg = "读取中…"; sAblBooks = null; sAblOff = false;
            new Thread(new Runnable() { public void run() {
                try {
                    String b = nasBase();
                    if (b == null) { sAblState="err"; sAblMsg="取不到音乐服务器地址（小智状态读不到）"; return; }
                    String js = httpGet(b + "/api/abl/books" + (fq.length() > 0 ? "?q=" + enc(fq) : ""), 20000);
                    if (js == null) { sAblState="err"; sAblMsg="本地书库没应答"; return; }
                    JSONObject o = new JSONObject(js);
                    sAblOff = o.optBoolean("off", false);
                    sAblDir = o.optString("dir", "");
                    sAblAt = o.optLong("at", 0);
                    JSONArray bs = o.optJSONArray("books");
                    sAblBooks = (bs == null) ? new JSONArray() : bs;
                    sAblState = "done";
                    sAblMsg = sAblOff ? "本地有声书库已在 NAS 设置页关闭"
                            : ("本地书库 " + sAblBooks.length() + " 本");
                } catch (Throwable t) { sAblState="err"; sAblMsg="读取异常：" + t; }
            }}, "abl-books").start();
            json(os, jsonOk(true, "读取中…"));
            return;
        }
        if (path.equals("/api/abl/books/result")) {
            try {
                JSONObject r = new JSONObject();
                r.put("state", sAblState);
                r.put("msg", sAblMsg);
                r.put("off", sAblOff);
                r.put("dir", sAblDir);
                r.put("at", sAblAt);
                r.put("books", sAblBooks == null ? new JSONArray() : sAblBooks);
                json(os, r.toString());
            } catch (Throwable t) { txt(os, 500, "abl books result: " + t); }
            return;
        }
        if (path.equals("/api/abl/eps")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            final String aid = rq.optString("id", "").trim();
            final int pg = Math.max(1, rq.optInt("page", 1));
            final String sq = rq.optString("q", "").trim();
            final int epq = rq.optInt("ep", 0);
            /* ★ pl-1.6.138：size 可调（NAS 端上限 500）。本地书点播一次要 300 集入列
               —— 50 集太短，听到队尾就断了；集表本来就一次能给一大段。
               不加这个参数时默认 100，和以前完全一致（弹窗拉集不受影响）。 */
            final int szq = Math.min(Math.max(rq.optInt("size", 100), 10), 500);
            if (aid.length() == 0) { json(os, jsonOk(false, "缺少 id")); return; }
            /* 缓存键与 /api/abl/eps/result 必须同口径（id|page|ep|size|q） */
            final String key = "abl:" + aid + ":" + pg + ":" + epq + ":" + szq + ":" + sq;
            try { JSONObject h = new JSONObject(); h.put("state","pending"); h.put("msg","加载中…"); sEps.put(key, h); } catch (Throwable ignore) { }
            new Thread(new Runnable() { public void run() {
                try {
                    String b = nasBase();
                    if (b == null) { sEps.put(key, errHolder("取不到音乐服务器地址")); return; }
                    String u = b + "/api/abl/eps?id=" + enc(aid) + "&page=" + pg + "&size=" + szq
                            + (epq > 0 ? "&ep=" + epq : "")
                            + (sq.length() > 0 ? "&q=" + enc(sq) : "");
                    String js = httpGet(u, 20000);
                    if (js == null) { sEps.put(key, errHolder("集表没应答")); return; }
                    JSONObject o = new JSONObject(js);
                    JSONObject h = new JSONObject();
                    h.put("state", "done");
                    h.put("id", o.optString("id", aid));
                    h.put("name", o.optString("name", ""));
                    h.put("sub", o.optString("sub", ""));
                    h.put("kind", o.optString("kind", "book"));
                    h.put("total", o.optInt("total", 0));
                    h.put("page", o.optInt("page", pg));
                    h.put("hasMore", o.optBoolean("hasMore", false));
                    h.put("lastEp", o.optInt("lastEp", 0));
                    h.put("lastPos", o.optInt("lastPos", 0));
                    h.put("lastTitle", o.optString("lastTitle", ""));
                    /* ★ v1.2.1：书目录 sidecar（封面/作者/演播/年份/简介）也透传 ——
                       这里是一层层重新拼 JSON 的，不显式 put 就会被吞掉（集表里的 dur 走
                       JSONArray 原样带过去，不用管）。 */
                    h.put("title", o.optString("title", ""));
                    h.put("author", o.optString("author", ""));
                    h.put("narrator", o.optString("narrator", ""));
                    h.put("year", o.optString("year", ""));
                    h.put("cover", o.optString("cover", ""));
                    h.put("desc", o.optString("desc", ""));
                    JSONArray a1 = o.optJSONArray("episodes");
                    JSONArray a2 = o.optJSONArray("subs");
                    h.put("episodes", a1 == null ? new JSONArray() : a1);
                    h.put("subs", a2 == null ? new JSONArray() : a2);
                    h.put("msg", o.optString("error", ""));
                    sEps.put(key, h);
                } catch (Throwable t) { sEps.put(key, errHolder("集表异常：" + t)); }
            }}, "abl-eps").start();
            json(os, jsonOk(true, "加载中…"));
            return;
        }
        if (path.equals("/api/abl/eps/result")) {
            String key = "abl:" + def(q.get("id"), "") + ":" + atoi(q.get("page"), 1) + ":"
                    + atoi(q.get("ep"), 0) + ":"
                    + Math.min(Math.max(atoi(q.get("size"), 100), 10), 500) + ":"
                    + def(q.get("q"), "");
            JSONObject h = sEps.get(key);
            json(os, h == null ? "{\"state\":\"pending\",\"msg\":\"\",\"episodes\":[],\"subs\":[]}" : h.toString());
            return;
        }
        if (path.equals("/api/abl/pos")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            final String pid = rq.optString("id", "").trim();
            final int pep = rq.optInt("ep", 0);
            final int ppos = rq.optInt("pos", 0);
            final String pttl = rq.optString("title", "").trim();
            if (pid.length() == 0 || pep <= 0) { json(os, jsonOk(false, "缺少 id/ep")); return; }
            /* 记忆写在 NAS（files/ab_local_pos.json，按书/子书各一份）——
               网页与语音将来共用同一份，不散在浏览器 localStorage 里。 */
            new Thread(new Runnable() { public void run() {
                try {
                    String b = nasBase();
                    if (b == null) return;
                    httpGet(b + "/api/abl/pos?id=" + enc(pid) + "&ep=" + pep + "&pos=" + ppos
                            + (pttl.length() > 0 ? "&title=" + enc(pttl) : ""), 6000);
                } catch (Throwable ignore) { }
            }}, "abl-pos").start();
            json(os, jsonOk(true, "已记录"));
            return;
        }
        if (path.equals("/api/ab/resume")) {
            /* ★ v1.3.287：续播定位。device（语音/网页点播）算好「lastPos-2」后通知到这里，
               player 侧等播放就绪再 PCtl.seekTo。pos 单位为秒（已减 2）。
               ★ pl-1.6.130：改走 abResumeGuardedSeek（等切到目标集 + 等 prepared），
               原来那版 abResumeSeek 只判 seekReady（BUSY 就算），会在新集还没 prepare 时
               抢着拖 ⇒ -38 把整条播放判死。ep 用来确认「真的是这一集在播」。 */
            int p = atoi(q.get("pos"), 0);
            int epn = atoi(q.get("ep"), 0);
            abResumeCancel();
            if (p > 0) {
                PlaySong preS = PCtl.song();
                final String preSig = (preS == null) ? "" : (nz(preS.getUrl()) + "|" + nz(preS.getTitle()));
                abResumeGuardedSeek(p, preSig, epn);
            }
            json(os, jsonOk(true, "已请求续播定位到第 " + Math.max(0, p) + " 秒"));
            return;
        }
        if (path.equals("/api/abplay")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            final String book = rq.optString("book", "").trim();
            final String nar = rq.optString("narrator", "").trim();
            final String aid = rq.optString("albumId", "").trim();
            final int ep = rq.optInt("ep", 0);
            final int pos = rq.optInt("pos", 0);   // 续播秒数（已含往前推2秒），透传给 device → 由其触发 player seek
            final String rid = rq.optString("rid", "").trim();   // ★ pl-1.6.119：已缓存集直接播（免现搜）
            final String src = rq.optString("source", "").trim();
            final String ttl = rq.optString("title", "").trim();
            if (book.length() == 0 || aid.length() == 0) { json(os, jsonOk(false, "缺少书名或版本")); return; }
            /* ★ pl-1.6.121：改发 prog_play（我的节目仿收藏：device 用 Prog 缓存整批
               占位入列 + 起播目标集，播到才解析 —— 秒出声、不搜书、不错集；
               缓存不可用（骨架集）device 内部自动回退 ab_play 现搜，行为不变。 */
            final String fq = "op=prog_play&book=" + enc(book) + "&narrator=" + enc(nar)
                    + "&albumId=" + enc(aid) + "&ep=" + ep + "&pos=" + pos
                    + (rid.length() > 0 ? ("&rid=" + enc(rid) + "&source=" + enc(src) + "&title=" + enc(ttl)) : "");
            if (!DevCtl.alive()) { json(os, jsonOk(false, "小智未运行（8088 不可达）")); return; }
            /* ★ pl-1.6.120：秒数续播自足。device 侧的 /api/ab/resume 通知方随 v1.3.29x
               源码丢失（smali 注入版无此调用），player 记住点播前的曲目签名，
               自己等「切到新集」再 seekTo(pos) —— 不依赖 device 回调。 */
            /* ★ pl-1.6.129：先作废上一单续播守卫 —— 之前只有带 pos 的请求才作废，
               于是「先点续播失败 → 再手点另一集（pos=0）」时，老守卫还在 45s 里
               反复 seek，把新起的这一集也打出 -38（用户：手动点大概率播不起来）。 */
            abResumeCancel();
            if (pos > 0) {
                PlaySong preS = PCtl.song();
                final String preSig = (preS == null) ? "" : (nz(preS.getUrl()) + "|" + nz(preS.getTitle()));
                abResumeGuardedSeek(pos, preSig);
            }
            new Thread(new Runnable() { public void run() {
                try { String rr = Proxy.post("/api/act", fq, 120000); Proxy.invalidate(); Boot.log("async abplay => " + rr); }
                catch (Throwable tt) { Boot.log("async abplay: " + tt); }
            }}, "eb-play").start();
            json(os, jsonOk(true, "正在播放《" + book + "》第" + (ep > 0 ? ep : 1) + "集…"));
            return;
        }
        /* ★ pl-1.6.137：本地有声书点播 —— 整批入列（本集 + 后面 49 集）后起播。
           为什么不用 /api/abplay：那条是 device 的 ab 加载器（NAS /api/ab/* 的在线书），
           本地书在 NAS 的 /api/abl/* 里，device 不认识；而 player 自己就是播放器，
           直接把 PlaySong 列表塞进引擎最省事，下一曲/上一曲也就落在同一本书里。 */
        if (path.equals("/api/ablplay")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            final String aid = rq.optString("id", "").trim();
            final String book = rq.optString("name", "").trim();
            final String sub = rq.optString("sub", "").trim();
            final int pos = rq.optInt("pos", 0);
            JSONArray its = rq.optJSONArray("items");
            if (its == null || its.length() == 0) {
                json(os, jsonOk(false, "缺少播放列表（items）"));
                return;
            }
            String artist = sub.length() > 0 ? (book + " · " + sub) : book;
            List<PlaySong> items = new ArrayList<PlaySong>();
            for (int i = 0; i < its.length(); i++) {
                JSONObject o = its.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "").trim();
                if (u.length() == 0) continue;
                int ep = o.optInt("ep", i + 1);
                String et = o.optString("title", "").trim();
                /* ★ pl-1.6.162：book 一起带上（落到 PlaySong.album）—— 队列项自带书名，
                   device 侧听书进度上报就不用再猜（以前本地节目被记成上一本在线书）。 */
                PlaySong sg = PCtl.newSong("abl_" + aid + "_" + ep,
                        ablEpLabel(ep, et), u, artist, book);
                if (sg != null) items.add(sg);
            }
            if (items.isEmpty()) {
                json(os, jsonOk(false, "播放列表为空"));
                return;
            }
            /* 续播秒数：与 /api/abplay 同一套「等切到新集再 seek」的守卫 */
            abResumeCancel();
            if (pos > 0) {
                PlaySong preS = PCtl.song();
                final String preSig = (preS == null) ? "" : (nz(preS.getUrl()) + "|" + nz(preS.getTitle()));
                abResumeGuardedSeek(pos, preSig);
            }
            String r = PCtl.playList(items, 0);
            Boot.log("ablplay " + book + (sub.length() > 0 ? " · " + sub : "")
                    + " 入列 " + items.size() + " 集 => " + r);
            json(os, jsonOk(r.startsWith("OK"), r));
            return;
        }
        if (path.equals("/api/prog")) {
            if (!DevCtl.alive()) { json(os, "{\"ok\":false,\"list\":[],\"msg\":\"小智未运行（8088 不可达）\"}"); return; }
            String r = Proxy.get("/api/act?op=prog_list", 5000);
            if (r != null) { json(os, actUnwrap(r)); } else { json(os, "{\"ok\":false,\"list\":[],\"msg\":\"取不到我的节目\"}"); }
            return;
        }
        if (path.equals("/api/prog/add")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            String b = rq.optString("book", "").trim();
            String nar = rq.optString("narrator", "").trim();
            String aid = rq.optString("albumId", "").trim();
            String src = rq.optString("source", "").trim();
            int cnt = rq.optInt("count", 0);
            if (!DevCtl.alive()) { json(os, jsonOk(false, "小智未运行（8088 不可达）")); return; }
            String r = Proxy.post("/api/act", "op=prog_add&book=" + enc(b) + "&narrator=" + enc(nar)
                    + "&albumId=" + enc(aid) + "&source=" + enc(src) + "&count=" + cnt, 8000);
            if (r != null) { json(os, actUnwrap(r)); } else { json(os, jsonOk(false, "收藏失败（8088 没回应）")); }
            return;
        }
        if (path.equals("/api/prog/del")) {
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            String aid = rq.optString("albumId", "").trim();
            if (!DevCtl.alive()) { json(os, jsonOk(false, "小智未运行（8088 不可达）")); return; }
            String r = Proxy.post("/api/act", "op=prog_del&albumId=" + enc(aid), 8000);
            if (r != null) { json(os, actUnwrap(r)); } else { json(os, jsonOk(false, "取消失败（8088 没回应）")); }
            return;
        }
        if (path.equals("/api/prog/pos")) {
            /* ★ v1.3.287：网页发起的听书，周期性回写「播到第几秒」到我的节目（device 主本）。 */
            JSONObject rq;
            try { rq = new JSONObject(body == null ? "{}" : body); }
            catch (Throwable t) { rq = new JSONObject(); }
            String aid = rq.optString("albumId", "").trim();
            int ep = rq.optInt("ep", 0);
            int ps = rq.optInt("pos", 0);
            if (!DevCtl.alive()) { json(os, jsonOk(false, "小智未运行（8088 不可达）")); return; }
            String r = Proxy.post("/api/act", "op=prog_setpos&albumId=" + enc(aid)
                    + "&ep=" + ep + "&pos=" + ps, 8000);
            json(os, r != null ? actUnwrap(r) : jsonOk(false, "记忆进度失败（8088 没回应）"));
            return;
        }
        if (path.equals("/api/prog/addcur")) {
            /* ★ v1.3.288：在播节目时网页 ☆ = 收藏这本书到我的节目（toggle，device 判在不在）。
               不用参数 —— 书名/版本/当前集数全在 device loader 状态里。 */
            if (!DevCtl.alive()) { json(os, jsonOk(false, "小智未运行（8088 不可达）")); return; }
            String r = Proxy.post("/api/act", "op=prog_addcur", 8000);
            json(os, r != null ? actUnwrap(r) : jsonOk(false, "操作失败（8088 没回应）"));
            return;
        }
        if (path.equals("/api/act")) {
            json(os, actOp(q));
            return;
        }
        /* ---------- 静态资源：赞赏码 + PWA 三件套 ----------
           pv1..pv11 这边只给了一个没有图标的假 manifest，而页面是从 8088 那套
           复制来的（引用 /donate.png、/sw.js、/icon-*.png）⇒ 赞赏码 404、
           Chrome 拿不到「安装应用」的资格。这里整套补齐（Assets 由 _gen_assets.py
           从 device 侧抽取生成，两边内容一模一样）。 */
        if (path.equals("/donate.png")) {
            try {
                byte[] img = android.util.Base64.decode(Assets.DONATE_QR_B64,
                        android.util.Base64.DEFAULT);
                writeBytes(os, 200, "image/png", img,
                        "Cache-Control: public, max-age=604800\r\n");
            } catch (Throwable t) {
                txt(os, 500, "donate decode: " + t);
            }
            return;
        }
        if (path.equals("/group.png")) {
            try {
                byte[] gimg = android.util.Base64.decode(Assets.GROUP_QR_B64,
                        android.util.Base64.DEFAULT);
                writeBytes(os, 200, "image/jpeg", gimg,
                        "Cache-Control: public, max-age=604800\r\n");
            } catch (Throwable t) {
                txt(os, 500, "group decode: " + t);
            }
            return;
        }
        if (path.equals("/favicon.ico")) {
            writeBytes(os, 204, "text/plain", new byte[0], null);
            return;
        }
        if (path.equals("/manifest.webmanifest") || path.equals("/manifest.json")) {
            json(os, Assets.MANIFEST_JSON);
            return;
        }
        if (path.equals("/sw.js")) {
            // Service-Worker-Allowed 默认就是 /，写出来是为了提醒：sw.js 必须在根路径下发。
            // ★ 缓存名里的 __VER__ 由 Boot.VER 替换 —— 每次装机换版本 => 换新壳；
            //   activate 里按前缀 r1-shell- 删旧壳，老缓存自动清。no-store 让浏览器
            //   永远拉最新 sw.js（否则旧脚本被缓存 => 永不换新壳 => 点横幅也只更新一半）。
            String sw = Assets.SW_JS.replace("__VER__", Boot.VER.replace('.', '_'));
            writeBytes(os, 200, "application/javascript; charset=utf-8",
                    sw.getBytes("UTF-8"),
                    "Service-Worker-Allowed: /\r\nCache-Control: no-store\r\n");
            return;
        }
        if (path.startsWith("/icon-")) {
            int size = 192;
            try {
                size = Integer.parseInt(path.replaceAll("[^0-9]", ""));
            } catch (Throwable t) {
                size = 192;
            }
            if (size < 48) {
                size = 48;
            }
            if (size > 512) {
                size = 512;
            }
            byte[] img = Assets.iconPng(size, path.indexOf("maskable") > 0);
            if (img != null) {
                writeBytes(os, 200, "image/png", img,
                        "Cache-Control: public, max-age=604800\r\n");
                return;
            }
        }
        if (path.equals("/api/events")) {
            /* ★ pl-1.6.91：把这条连接挂成 SSE 流（在独立线程里常驻），
               按键后 device 打 op=topkey ⇒ ssePush() 推一条，页面收到立刻 load()。
               单 accept 线程模型下这是唯一常驻线程（本地控制台通常 1~2 标签页），可接受。 */
            serveSse(sock, os);
            return;                // socket 交给 SSE 线程（已进 SSE_SOCKS，外层 finally 不会关）
        }
        txt(os, 404, jsonOk(false, "404 " + path));
        return;
    }

        /** v1.3.244 起的逐条 fav_add 请求体（pl-1.6.96 抽出来，批量失败时逐条回退复用）。 */
        private static String favAddBody(JSONObject it) {
            StringBuilder b = new StringBuilder();
            b.append("op=fav_add&title=").append(enc(it.optString("title", "").trim()));
            String artist = it.optString("artist", "").trim();
            if (artist.length() > 0) {
                b.append("&artist=").append(enc(artist));
            }
            String url = it.optString("url", "").trim();
            if (url.length() > 0) {
                b.append("&url=").append(enc(url));
            }
            String album = it.optString("album", "").trim();
            if (album.length() > 0) {
                b.append("&album=").append(enc(album));
            }
            String duration = it.optString("duration", "").trim();
            if (duration.length() > 0) {
                b.append("&duration=").append(enc(duration));
            }
            /* 绑定信息（导出 txt 里的扩展列）原样带给 device 的 fav_add；
               老 device 会忽略，功能退化成重新匹配。 */
            String[] kv = {"source", "rid", "rman", "ltitle", "lartist", "rtitle", "rartist"};
            for (int j = 0; j < kv.length; j++) {
                String v = it.optString(kv[j], "").trim();
                if (v.length() > 0) {
                    b.append('&').append(kv[j]).append('=').append(enc(v));
                }
            }
            return b.toString();
        }

    /* ★ pl-1.6.91：把一条 HTTP 连接变成 SSE 流，挂起在独立线程里。 */
    private static void serveSse(final Socket s, final OutputStream os) {
        // 先进集合：这样 handle 返回后外层 finally 不会把这条连接关掉（SSE 线程还握着它）
        SSE_SOCKS.add(s);
        new Thread(new Runnable() {
            public void run() {
                try {
                    s.setSoTimeout(0);
                    StringBuilder h = new StringBuilder();
                    h.append("HTTP/1.1 200 OK\r\n");
                    h.append("Content-Type: text/event-stream\r\n");
                    h.append("Cache-Control: no-cache\r\n");
                    h.append("Connection: keep-alive\r\n");
                    h.append("\r\n");
                    os.write(h.toString().getBytes("UTF-8"));
                    os.write("retry: 3000\n\n".getBytes("UTF-8"));
                    os.flush();
                    SSE_SOCKS.add(s);
                    long last = SSE_GEN;
                    while (true) {
                        boolean fired = false;
                        synchronized (SSE_MON) {
                            long t0 = System.currentTimeMillis();
                            while (SSE_GEN == last) {
                                long remain = 25000 - (System.currentTimeMillis() - t0);
                                if (remain <= 0) {
                                    break;
                                }
                                SSE_MON.wait(remain);
                            }
                            if (SSE_GEN != last) {
                                fired = true;
                            }
                        }
                        if (fired) {
                            last = SSE_GEN;
                            os.write("data: {\"type\":\"status\"}\n\n".getBytes("UTF-8"));
                            os.flush();
                        } else {
                            // 25s 心跳，防止中间代理 / 系统把空闲连接掐了
                            os.write(": ping\n\n".getBytes("UTF-8"));
                            os.flush();
                        }
                    }
                } catch (Throwable t) {
                    // 客户端断开（关标签页 / 刷新）⇒ 写失败，这里收尾
                } finally {
                    SSE_SOCKS.remove(s);
                    try { s.close(); } catch (Throwable t) {}
                }
            }
        }, "r1web-sse").start();
    }

    /* ---------------- 动作 ---------------- */

    /**
     * 本地能干的自己干，认不出的 op **一律转给 device**。
     *
     * <p>这样 8088 那 26 个 op 不用在 player 侧重写一遍：搜索/电台/氛围灯/重启/
     * 唤醒这些本来就在 device 里，转发过去它自己执行（推列表回来时钩子会更新队列）。</p>
     */
    private static String actOp(Map<String, String> q) {
        String op = q.get("op");
        if (op == null) {
            return jsonOk(false, "缺 op");
        }
        try {
            /* ★ pl-1.6.91：device 物理按键改完状态后打这个 op，触发一次 SSE 推送，
               让网页实时刷新（不必等 8s 轮询）。device 侧 TopKey.notifyPlayer() 负责打。 */
            if ("topkey".equals(op)) {
                ssePush();
                return jsonOk(true, "pushed");
            }
            /* ★ pl-1.6.92：实体键暂停/恢复 —— 音频动作由 device 走原厂总线驱动，
               这里只补 PCtl 的「用户主动暂停」记账 + 推 SSE。**必须先记账再推送**：
               ssePush 唤醒的页面会立刻拉 /api/status，若记账在后，那一拉读到的还是
               旧态（phase() 的 4 秒进度宽限会让暂停后仍报 playing）。 */
            if ("toppause".equals(op)) {
                PCtl.markPaused();
                ssePush();
                return jsonOk(true, "paused+pushed");
            }
            if ("topresume".equals(op)) {
                PCtl.markResumed();
                ssePush();
                return jsonOk(true, "resumed+pushed");
            }
            if ("play".equals(op) || "resume".equals(op)) {
                String r = PCtl.play();
                ssePush();                 // ★ pl-1.6.121：网页起播/恢复 ⇒ 立刻推状态，不等 8s 轮询
                return jsonOk(true, r);
            }
            if ("pause".equals(op)) {
                String r = PCtl.pause();
                ssePush();                 // ★ 同上：暂停后立刻让网页反映，消除「暂停延时」
                return jsonOk(true, r);
            }
            if ("stop".equals(op)) {
                String r = PCtl.stop();
                ssePush();
                return jsonOk(true, r);
            }
            if ("next".equals(op)) {
                String r = PCtl.next();
                ssePush();
                return jsonOk(true, r);
            }
            if ("prev".equals(op)) {
                String r = PCtl.prev();
                ssePush();
                return jsonOk(true, r);
            }
            /* ★ pl-1.6.24：播放引擎切换（ExoPlayer 2.18 ↔ 原厂 MediaPlayer）。
               不带 v 就是**查询**；带 v=exo / mp 就是切换（会重启 :NetPlayer，约 5 秒）。 */
            if ("engine".equals(op)) {
                String v = q.get("v");
                if (v == null || v.length() == 0) {
                    return jsonOk(true, "当前引擎：" + ("mp".equals(PCtl.engine())
                            ? "MediaPlayer（原厂）" : "ExoPlayer 2.18")
                            + "　（" + PCtl.engineInfo() + "）");
                }
                return jsonOk(true, PCtl.setEngine(v));
            }
            /* ★ pl-1.6.28：七彩氛围灯 —— 灯就长在 player 里（LedLight），
               所以这个开关**必须由 player 自己收**，不能反代到 device
               （device 那套走消息总线 what=4，只管「氛围灯总开关」；
                68/70 这类效果命令原厂 player 压根不认）。
               v = on|off|ms:N|state
               ★★ op 名必须是 amb7 而不是 amb：页面的「氛围灯总开关」走的是
                 act("amb",...)，它在 player 这里是**未知 op ⇒ 反代给 device**
                 （device 的消息总线 what=4 管总开关）。以前叫 amb 会把那条路抢走，
                 点「氛围灯」开关实际改的是七彩。 */
            if ("amb7".equals(op)) {
                String v = q.get("v");
                if (v == null || v.length() == 0) {
                    v = "state";
                }
                /* pl-1.6.32：多彩 = 氛围灯的子项，所以 op 值同时认
                   on|multi（切多彩）与 off|factory（切回原厂）。 */
                /* ★ pl-1.6.59 单色档：15 颗同一个颜色（亮度仍跟音乐）。
                   solo:<RRGGBB> = 顺便指定颜色；不带参数 = 沿用上次 / 跟原厂基色。 */
                if (v.startsWith("solo:")) {
                    try {
                        Amb.setSoloColor(
                                (int) Long.parseLong(v.substring(5).trim(), 16));
                    } catch (Throwable t) {
                        return jsonOk(false, "solo 颜色格式不对（要 RRGGBB）");
                    }
                    Amb.setMode(Amb.MODE_SOLO);
                    return jsonOk(true, "氛围灯已切到单色：#" + String.format("%06X", Amb.soloColor())
                            + "，15 颗同色、亮度跟着音乐");
                }
                if ("solo".equals(v)) {
                    Amb.setMode(Amb.MODE_SOLO);
                    return jsonOk(true, "氛围灯已切到单色：#" + String.format("%06X", Amb.soloColor())
                            + "，15 颗同色、亮度跟着音乐");
                }
                if ("on".equals(v) || "multi".equals(v)) {
                    Amb.setOn(true);
                    return jsonOk(true, "氛围灯已切到多彩：15 颗灯各一色，每次闪动乱序重排（不含白色）");
                }
                if ("off".equals(v) || "factory".equals(v)) {
                    Amb.setOn(false);
                    return jsonOk(true, "氛围灯已切回原厂单色");
                }
                if (v.startsWith("ms:")) {
                    try {
                        Amb.setMinMs(Integer.parseInt(v.substring(3).trim()));
                    } catch (Throwable t) {
                        return jsonOk(false, "ms 不是数字");
                    }
                    return jsonOk(true, "重排间隔=" + Amb.minMs() + "ms");
                }
                /* pl-1.6.30：诊断口 —— 「灯怎么不亮」的第一现场 */
                if ("probe".equals(v) || "diag".equals(v)) {
                    return jsonOk(true, Amb.probe());
                }
                return jsonOk(true, "氛围灯灯效：" + (Amb.isOn() ? "多彩" : "原厂")
                        + "（on|multi / off|factory / ms:N / probe）"
                        + "，重排间隔 " + Amb.minMs() + "ms");
            }
            /* pl-1.6.150：蓝牙氛围灯兜底链的诊断 / 手动重判。
               真正的触发在 Bt 里自驱（连上补 show 255 / 断开补 close 255），
               这里不提供任何「自定义灯效」设置，灯效仍由原厂灯效服务跑。 */
            if ("btlight".equals(op)) {
                if ("refresh".equals(q.get("v"))) {
                    Bt.syncBtLight();
                    return jsonOk(true, "已重判蓝牙连接：" + Bt.btLightState());
                }
                return jsonOk(true, Bt.btLightState());
            }
            /* ★ pl-1.6.49：拾音灯「单颗直写」—— 给 device 的聆听灯用。
               灯长在 player 里（LedLight native），而 device 是 u0_a8、SELinux 挡着
               写不了 sysfs，所以它只能 HTTP 到 :8080 让我们代写。
               ★★ op 名必须避开 device 的那些（light / amb …），否则会把反代路抢走。
               v = on:K（点亮第 K 颗，1..24）| off:K | off（灭当前）| state */
            if ("led1".equals(op)) {
                String v = q.get("v");
                if (v == null || v.length() == 0) {
                    v = "state";
                }
                try {
                    if (v.startsWith("on:")) {
                        return jsonOk(true, Amb.pick(Integer.parseInt(v.substring(3).trim()), true));
                    }
                    if (v.startsWith("off:")) {
                        return jsonOk(true, Amb.pick(Integer.parseInt(v.substring(4).trim()), false));
                    }
                    if ("off".equals(v)) {
                        return jsonOk(true, Amb.pickOff());
                    }
                    /* ★ pl-1.6.51：整组 A 组（15 颗氛围环）写同一个颜色 —— 夜灯用。
                       device 要开夜灯的氛围环时调的就是这条（它自己写不了 sysfs）。 */
                    if (v.startsWith("all:")) {
                        return jsonOk(true, Amb.all(
                                (int) Long.parseLong(v.substring(4).trim(), 16)));
                    }
                    if ("alloff".equals(v)) {
                        return jsonOk(true, Amb.all(0));
                    }
                    /* ★ pl-1.6.61 排障：519（音乐律动）链路运行时状态。 */
                    if ("dbg519".equals(v)) {
                        return jsonOk(true, Amb.dbg519());
                    }
                    /* ★ pl-1.6.59：device 开夜灯底部时走这条 —— 不能只写颜色！
                       只写色的话 player 进不了「夜灯态」（不停七彩、不守灯、钩子不拦
                       原厂）⇒ 从 MCP 喊「开夜灯」时灯会被原厂抢回去（「说开了实际没开」）。 */
                    if (v.startsWith("ambon:")) {
                        /* device 开夜灯底部：必须走 Amb.nightAmb(true)（进夜灯态），
                           不能只写颜色。顺手把 device 的颜色 / 亮度口径对齐过来，
                           格式 ambon:RRGGBB 或 ambon:RRGGBB:luma。 */
                        String tail = v.substring(6).trim();
                        int k = tail.indexOf(':');
                        int c;
                        int l = -1;
                        if (k > 0) {
                            c = (int) Long.parseLong(tail.substring(0, k).trim(), 16);
                            l = Integer.parseInt(tail.substring(k + 1).trim());
                        } else {
                            c = (int) Long.parseLong(tail, 16);
                        }
                        Amb.nightColor(c & 0xFFFFFF);
                        if (l >= 0) {
                            Amb.nightLuma(l);
                        }
                        return jsonOk(true, Amb.nightAmb(true));
                    }
                    /* ★ pl-1.6.58：device 关夜灯时走这条 —— 「把 A 组交回氛围灯」。
                       不是写黑：写黑会卡在「夜灯灭」和「氛围灯接手」之间，
                       用户看到的就是底部先灭一下再亮。由 Amb 决定要不要兜底写黑。 */
                    if ("ambback".equals(v)) {
                        return jsonOk(true, Amb.nightAmb(false));
                    }
                    /* ★ pl-1.6.52：顶部 24 颗拾音灯整组写色（夜灯的「顶」）。
                       走小飞那条路：sysfs 直写掩码 0x7FFFFF8000，不碰灯效服务。 */
                    if (v.startsWith("top:")) {
                        return jsonOk(true, Amb.nightTop(true));
                    }
                    if ("topoff".equals(v)) {
                        return jsonOk(true, Amb.nightTop(false));
                    }
                } catch (Throwable t) {
                    return jsonOk(false, "led1 v=" + v + " : " + t);
                }
                return jsonOk(true, "拾音灯单颗：" + Amb.pickState() + "（on:K / off:K / off / state）");
            }
            /* ★ pl-1.6.51：夜灯 —— 顶(拾音灯 24 颗) + 底(氛围环 15 颗)，可分别开关。
               ★ 分工：底部这 15 颗长在 player（A 组，只有 player 能写 sysfs）；
                 顶部 24 颗归 device（灯效服务 503 FORCE 常亮）+「夜灯期间所有灯效停用」
                 的那道闸门 ⇒ 这里两边都做：自己写 A 组，再把同一条指令原样转给 device。
               ★★ op 名必须叫 night：不能叫 light / amb（会把反代给 device 的路抢走）。
               v = on | off | part:both|top|amb | top:1|0 | amb:1|0
                   | color:RRGGBB | luma:N(0..100) | state
               ★★ pl-1.6.54 口径：**on / off 才是总开关**；part: 只是「开的时候亮哪一组」，
                  夜灯关着时改 part 只记配置、不点灯（以前它会顺手开灯 = 第二个开关）。 */
            if ("night".equals(op)) {
                String v = q.get("v");
                if (v == null || v.length() == 0) {
                    v = "state";
                }
                String mine;
                try {
                    if ("on".equals(v)) {
                        mine = Amb.nightPower(true);
                    } else if ("off".equals(v)) {
                        mine = Amb.nightPower(false);
                    } else if (v.startsWith("part:")) {
                        mine = Amb.nightPart(v.substring(5).trim());
                    } else if ("amb:1".equals(v)) {
                        mine = Amb.nightAmb(true);
                    } else if ("amb:0".equals(v)) {
                        mine = Amb.nightAmb(false);
                    } else if (v.startsWith("color:")) {
                        mine = Amb.nightColor(
                                (int) Long.parseLong(v.substring(6).trim(), 16));
                    } else if (v.startsWith("luma:")) {
                        mine = Amb.nightLuma(Integer.parseInt(v.substring(5).trim()));
                    } else if (v.startsWith("top:")) {
                        /* ★ pl-1.6.52：顶部改成 player 直写 sysfs（小飞那套），
                           device 那边只管闸门 + 持久化，不再 show 503。 */
                        mine = Amb.nightTop(parse01(v.substring(4)));
                    } else {
                        mine = Amb.nightState();
                    }
                } catch (Throwable t) {
                    return jsonOk(false, "night v=" + v + " : " + t);
                }
                /* 顶部拾音灯 + 闸门 + 持久化都在 device 那边 ⇒ 原样转过去 */
                String dev;
                if (DevCtl.alive()) {
                    dev = Proxy.post("/api/act", buildQuery(q), 8000);
                    if (dev == null) {
                        dev = "（device 无回应）";
                    }
                } else {
                    dev = "（小智未运行 ⇒ 顶部拾音灯没管上）";
                }
                return jsonOk(true, (mine.length() == 0 ? "" : (mine + " ｜ ")) + "device: " + dev);
            }
            // ★ 跳播本地做：直接 playIndex(i,true)，列表顺序一点不变
            if ("play_at".equals(op)) {
                int i = 1;
                try {
                    i = Integer.parseInt(q.get("index"));
                } catch (Throwable t) {
                    return jsonOk(false, "index 不是数字");
                }
                return jsonOk(true, PCtl.playAt(i - 1));
            }
            /* ★★ 音量量纲：页面滑块是**档位**（0..max，实测 15），但它发给后端的一直
                 是**百分比**（与 device 8088 同构：`pct = val*100/max`）。
                 而原厂 player 的音量就是系统 STREAM_MUSIC 档位 ⇒ 这里必须把百分比
                 换算回档位再 setStreamVolume。pv1/pv2 直接把百分比当档位传
                 （20% ⇒ setStreamVolume(20) ⇒ 被 clamp 成满档 15；更糟的是小于
                 7% 的那些档位全部 clamp 成 0/1，回读后页面把滑块拽回 0）
                 ⇒ 这就是「音量点了跳回 0」的根因。 */
            if ("vol".equals(op)) {
                int pct = -1;
                try {
                    pct = Integer.parseInt(q.get("v"));
                } catch (Throwable t) {
                    return jsonOk(false, "v 不是数字");
                }
                int mx = PCtl.volMax();
                if (mx <= 0) {
                    mx = 15;
                }
                return jsonOk(true, PCtl.setVol(Math.round(pct * mx / 100.0f)));
            }
            /* 直设档位（0..max），curl 调试 / 日后精确控制用 */
            if ("volstep".equals(op)) {
                int v = -1;
                try {
                    v = Integer.parseInt(q.get("v"));
                } catch (Throwable t) {
                    return jsonOk(false, "v 不是数字");
                }
                return jsonOk(true, PCtl.setVol(v));
            }
            /* ★ pl-1.6.12：拖动进度条 —— 跳到 ms 毫秒处。
               原厂 AIDL 里本来没有 seek，是我们在 smali 上新增的事务 0x15
               （见 patch_seek.py）。只在本地播放态放行，见 PCtl.seekTo()。 */
            if ("seek".equals(op)) {
                int ms = -1;
                try {
                    ms = Integer.parseInt(q.get("ms"));
                } catch (Throwable t) {
                    return jsonOk(false, "ms 不是数字");
                }
                return jsonOk(true, PCtl.seekTo(ms));
            }
            if ("mode".equals(op)) {
                int m = -1;
                try {
                    m = Integer.parseInt(q.get("v"));
                } catch (Throwable t) {
                    return jsonOk(false, "v 不是数字");
                }
                return jsonOk(true, PCtl.setMode(m));
            }
            if ("devctl".equals(op)) {
                String v = q.get("v");
                String r;
                if ("off".equals(v)) {
                    r = DevCtl.off();
                } else if ("on".equals(v)) {
                    r = DevCtl.on();
                } else if ("fs".equals(v)) {
                    r = DevCtl.forceStopOnly();
                } else {
                    r = "OK 小智" + (DevCtl.alive() ? "在跑" : "已停用");
                }
                Proxy.invalidate();
                return jsonOk(true, r);
            }
            /* 一键清理无用进程：只杀 Sys.JUNK 里认得的原厂残留。
               ?v=dry = 只列不杀（页面刷新待清理清单用）。 */
            if ("cleanup".equals(op)) {
                if ("dry".equals(q.get("v"))) {
                    return jsonOk(true, "已刷新待清理清单");
                }
                return jsonOk(true, Sys.cleanup());
            }
            /* 清理的反向操作：pm unhide，把停掉的原厂残留放回来。 */
            if ("restore".equals(op)) {
                return jsonOk(true, Sys.restore());
            }
            /* ---- 蓝牙（标准 BluetoothAdapter API，不经过小智） ---- */
            if ("bt_on".equals(op)) {
                return jsonOk(true, Bt.setOn(true));
            }
            if ("bt_off".equals(op)) {
                return jsonOk(true, Bt.setOn(false));
            }
            if ("bt_scan".equals(op)) {
                return jsonOk(true, Bt.scan());
            }
            if ("bt_discover".equals(op)) {
                return jsonOk(true, Bt.discoverable(!"0".equals(q.get("v"))));
            }
            if ("bt_pair".equals(op)) {
                return jsonOk(true, Bt.pair(q.get("addr")));
            }
            if ("bt_unpair".equals(op)) {
                return jsonOk(true, Bt.unpair(q.get("addr")));
            }
            if ("bt_connect".equals(op)) {
                return jsonOk(true, Bt.connect(q.get("addr")));
            }
            if ("bt_disconnect".equals(op)) {
                return jsonOk(true, Bt.disconnect(q.get("addr")));
            }
            /* ---- 外部播放源 DLNA / AirPlay：v=1 开，v=0 关 ---- */
            if ("dlna".equals(op) || "airplay".equals(op)) {
                boolean on = !"0".equals(q.get("v"));
                return jsonOk(true, Svc.set(op, on));
            }
            if ("ping".equals(op)) {
                return jsonOk(true, "pong ver=" + Boot.VER + " proc=" + Boot.proc
                        + " dev=" + (DevCtl.alive() ? "on" : "off"));
            }
            /* ★ pl-1.6.12：seek 通路自检 —— 排「拖了没反应」时先看这个 */
            if ("diag".equals(op)) {
                return jsonOk(PCtl.seekReady(), PCtl.diag());
            }
        } catch (Throwable t) {
            Boot.log("act " + op + ": " + t);
            return jsonOk(false, "ERR " + t);
        }
        // ★ pl-1.6.75：手动绑定（网页重链接 🔗 / 旧的手动配对）—— 转发 device 成功后，
        //   必须把 player 这边「已尝试且没配上」的失败标记撤掉。收藏列表和队列行的红底
        //   都是这个标记算出来的，只改 device 的收藏主本 ⇒ 绑好了仍然红、✕ 还退不掉
        //   （用户实测第 105 首就是这样）。
        if ("fav_link".equals(op) || "fav_bind".equals(op)) {
            if (!DevCtl.alive()) {
                return jsonOk(false, "小智已停用（8088 不可达）—— " + op + " 只在小智进程里有");
            }
            String fk = q.get("k");
            if (fk == null || fk.length() == 0) {
                fk = Fav.key(nz(q.get("title")), nz(q.get("artist")));
            }
            String r = Proxy.post("/api/act", buildQuery(q), 20000);
            if (r == null) {
                return jsonOk(false, "已转发但 8088 没回应（" + op + "）");
            }
            if (r.indexOf("OK") >= 0) {
                PCtl.noteFavBound(fk);
                Fav.invalidate();
            }
            return r;
        }
        // ★ 兜底：转发给 device（search / radio / random / play_index / play_artist /
        //   play_url / amb / light / wakeup / reboot / restart_app / reload / ws_restart …）
        //   小智都停用了就别干等 8s 超时（串行 accept 会拖住整页），直接说清楚。
        if (!DevCtl.alive()) {
            return jsonOk(false, "小智已停用（8088 不可达）—— " + op + " 只在小智进程里有");
        }
        // v1.3.175：在线点歌类 op（取链要跑六级兜底 + lxserver 中继）给足时间，
        //          否则 8s 一过页面就判失败，而 device 那边其实还在忙、随后会播出来。
        int fwdMs = 8000;
        // v1.3.178：play_artist 现在会在线搜 + 并行取链（最多 ~6s），
        //   random_online / play_rand_at 要现拉一批在线曲库 —— 都给 25s，
        //   否则页面先判失败而 device 那边还在忙，随后照样播出来（假失败）。
        if ("play_online".equals(op) || "play_song".equals(op)
                || "search_online".equals(op) || "play_url".equals(op)
                || "play_artist".equals(op) || "random_online".equals(op)
                || "play_rand_at".equals(op)
                /* pl-1.6.30：「全部载入播放列表」—— 整库上千首要逐首推总线，
                   8s 一过页面就判失败，而 device 那边其实还在推、随后照样播出来 */
                || "queue_all".equals(op)
                /* pl-1.6.31：在线结果整批入队 —— 第一首要正式取链（预算 8s），
                   整批推完再起播，给 25s 与 play_online 同档 */
                || "queue_online".equals(op)
                /* pl-1.6.42：播收藏 —— 第一首可能要在线取链（预算 8s），
                   整批再逐首推总线；8s 一过页面就判失败而 device 还在忙（假失败）。
                   ★ pl-1.6.43：光杆条目（只有歌名）还要在线补源（总预算 15s）
                   ⇒ 25s 不够，给 45s，否则这一次补到的源全白搜。
                   ★ pl-1.6.65：刚上传完的歌单是**全新条目**（缓存字段被 fav_clear
                   清掉了）⇒ 首轮解析 357 首 fuzzy + 在线补源实测 60~80s，
                   45s 照样假失败（页面弹「8088 没回应」而 device 随后照播）。
                   device WebConsole 是串行 accept，这一刀的代价是解析期间别的
                   act 要排队 —— 但换歌单本来就是低频重操作，值得。 */
                || "fav_play".equals(op) || "play_fav".equals(op)) {
            fwdMs = 120000;
        }
        /* ★ pl-1.6.110：网页上一集/下一集按钮改「后台转发、立即返回」。
           根因（实测：正在播 350，网页点下一集拉出 302）：WebSrv 单 accept
           串行 —— 同步转发（30s 预算）把请求线程占死，而 device 侧 abNext
           起播前要 GET 本机 /api/queue（liveNowRow + 幂等护栏两处），本机
           accept 正被这个转发占着 ⇒ 两处必超时返 null ⇒ abNext 跌回 SP 旧
           集号去续拉。语音入口不经 WebSrv 所以一直没事，网页入口必坏。
           页面 skip() 本来就是乐观更新 + 800ms 后 load，不用等转发返回。 */
        if ("ab_next".equals(op) || "ab_prev".equals(op)) {
            final Map<String, String> fq = q;
            final String fop = op;
            Thread tf = new Thread(new Runnable() { public void run() {
                try {
                    String rr = Proxy.post("/api/act", buildQuery(fq), 60000);
                    if (rr != null) {
                        Proxy.invalidate();
                    }
                    Boot.log("async fwd " + fop + " => " + rr);
                } catch (Throwable tt) {
                    Boot.log("async fwd " + fop + ": " + tt);
                }
            }}, "abfwd");
            tf.setDaemon(true);
            tf.start();
            return jsonOk(true, "OK 正在" + ("ab_next".equals(op) ? "下一集" : "上一集") + "…");
        }
        /* pl-1.6.108（110 起上面已异步接管，此分支仅留作语义记录）：
           device 侧要拉集表 + 整批推总线 + 定位起播，8s 不够（假失败）；
           120s 又太长（页面干等），给 30s。 */
        if ("ab_next".equals(op) || "ab_prev".equals(op)) {
            fwdMs = 30000;
        }
        String r = Proxy.post("/api/act", buildQuery(q), fwdMs);
        if (r != null) {
            Proxy.invalidate();
            /* ★ pl-1.6.42：收藏类 op 改的是 device 那份主本 ⇒ 立刻作废 player 的
               只读缓存，下一轮 status 就带真值回来（不然星标要等 8s TTL 才跟上）。 */
            if (op != null && op.startsWith("fav")) {
                Fav.invalidate();
            }
            return r;
        }
        return jsonOk(false, DevCtl.alive()
                ? "已转发但 8088 没回应（" + op + "）"
                : "小智未运行（8088 不可达）—— " + op + " 只在小智进程里有");
    }

    /* ---------------- 状态 ---------------- */

    /**
     * 组装成 **device 8088 同构**的 status —— 页面搬过来不用改就能渲染。
     *
     * <p>规则：device 段（activation/audio/server/ambient/ws/session）整体拷贝，
     * 再用 player 本地的真实播放数据覆盖 music 段（曲名/进度/总时长/队列位置）。
     * device 停用时这些段给降级值，页面对应面板变灰但不白屏。</p>
     */
    static JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            JSONObject d = null;
            String ds = Proxy.deviceStatus();
            if (ds != null) {
                try {
                    d = new JSONObject(ds);
                } catch (Throwable t) {
                    d = null;
                }
            }
            boolean devOn = DevCtl.alive();
            if (d != null) {
                Iterator<String> it = d.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    o.put(k, d.opt(k));
                }
            }

            /* ---- player 自己的身份 ---- */
            o.put("pl", true);            // 页面据此跳过 8080 WS、启用小智开关
            o.put("proc", Boot.proc);
            o.put("ver", Boot.VER);       // 页面 checkVer 防呆用这个
            o.put("devVer", d != null ? d.optString("ver", "") : "");
            o.put("port", port);
            o.put("ip", localIp());
            o.put("uptimeSec", (int) ((System.currentTimeMillis() - Boot.bootTs) / 1000));
            o.put("ready", PCtl.ready());
            /* pl-1.6.24：当前播放引擎（exo / mp）—— 页面「播放引擎」按钮回显用 */
            o.put("engine", PCtl.engine());
            /* pl-1.6.28：七彩氛围灯（player 自己的开关，与 device 的「氛围灯总开关」是两回事） */
            o.put("amb7", Amb.isOn());
            /* pl-1.6.56：多彩换色间隔（ms）—— 页面「多彩速度」滑块回显用 */
            o.put("amb7ms", Amb.minMs());
            /* pl-1.6.59：灯效档位（0=原厂 / 1=多彩 / 2=单色）+ 单色当前颜色 */
            o.put("amb7mode", Amb.mode());
            o.put("amb7solo", String.format("%06X", Amb.soloColor()));
            /* pl-1.6.30：七彩诊断串（钩子跑没跑 / 原厂喂不喂 / 自驱顶没顶）——
               以前「点了没反应」只能靠肉眼看灯猜，现在一句话说清 */
            o.put("amb7d", Amb.probe());
            /* pl-1.6.150：蓝牙氛围灯兜底链运行状态（排障用） */
            o.put("btLight", Bt.btLightState());
            /* pl-1.6.42：收藏条数（-1 = 读不到）—— 页面收藏卡的标题上显示 */
            o.put("favTotal", Fav.total());

            /* ---- music：player 直供（device 拿不到总时长）---- */
            /* ★ pl-1.6.44：顺手把**电台名册**喂给 PCtl（零额外请求 ——
               device 的 status 里本来就带着 music.radioList）。
               电台是固定条目 ⇒ 认它该查名单，不该靠「没有时长」去猜。 */
            PCtl.noteRadioUrls(d);
            JSONObject m = (d != null && d.optJSONObject("music") != null)
                    ? d.optJSONObject("music") : new JSONObject();
            PlaySong s = PCtl.song();
            /* ★ pl-1.6.67：当前曲是收藏占位地址（本地库没有、导入时没搜到源的那批，
               device v1.3.228 起给的就是 http://127.0.0.1:8080/r/fav?k=<key>）——
               播到过一次、解析成功过 ⇒ favReal 非空：播放栏要显示**真实歌曲信息**，
               码率探测也改用真地址（占位地址探测出来的是我们自己的 302/404）。 */
            String favK = PCtl.favPendingKey(s == null ? "" : nz(s.getUrl()));
            String[] favReal = (favK != null) ? PCtl.favReal(favK) : null;
            /* ★ pl-1.6.151：节目占位行（/r/prog?aid=&ep=）解析成功后，播放栏显示服务端真名，
               而不是入列时的「第 N 集」缺集占位名。 */
            String[] progPair = PCtl.progPendingKey(s == null ? "" : nz(s.getUrl()));
            String[] progReal = (progPair != null)
                    ? PCtl.progReal(progPair[0] + "|" + progPair[1]) : null;
            boolean viaPend = false;      // pl-1.6.45：曲目刚换 ⇒ 来源结论先不给
            if (s != null) {
                m.put("curTitle", nz(s.getTitle()));
                m.put("curArtist", nz(s.getArtist()));
                m.put("curId", nz(s.getItemId()));
                /* ★ pl-1.6.42：当前曲的**地址** —— 收藏时要存下来，
                   不然收藏条目只有歌名，播的时候还得回库反查（在线歌常常查不到）。 */
                m.put("curUrl", nz(s.getUrl()));
            }
            PlayPositionInfo p = PCtl.pos();
            /* ★ pl-1.6.24：直播流/电台的时长归一。
               Exo 对没有终点的流报 TIME_UNSET，long-to-int 后是 **1**（实测恒为 1），
               原厂 MediaPlayer 报 -1/0 ⇒ 「<= 1 秒」一律当「没有时长」给 0。
               以前把 1 直接吐给页面：页面拿它算百分比 ⇒ 条永远 100%；
               拿它夹进度 ⇒ Math.floor(1/1000)=0 ⇒ 时间恒 0:00。 */
            boolean radio = PCtl.isRadio();
            int durMs = (p == null) ? 0 : p.getTotal_time();
            if (durMs <= 1000) {
                durMs = 0;
            }
            if (p != null) {
                m.put("posMs", p.getPlay_time());
                m.put("state", p.getStatus());
            }
            m.put("durMs", durMs);
            /* ★ pl-1.6.29：码率 + 格式（含电台）。
               三路退化：① Exo 的 Format（最准，直播流也有）→ ② URL 里的 /64k.mp3
               → ③ HTTP HEAD 的 Content-Length ÷ 时长（异步探测，下一次轮询才看得到）。
               格式与码率互不阻塞：电台拿不到时长也照样显示「MP3 · 64k」。 */
            {
                AudioInfo ai = AudioInfo.of(
                        (progReal != null && progReal[2].length() > 0) ? progReal[2]
                                : (favReal != null && favReal[2].length() > 0) ? favReal[2]
                                : (s == null ? null : s.getUrl()), durMs);
                m.put("br", ai.br);
                m.put("fmt", ai.fmt);
                m.put("sr", ai.sr);
                m.put("ch", ai.ch);
                m.put("brSrc", ai.src);
            }
            /* ★ pl-1.6.24：电台由 player 自己认（见 PCtl.isRadio 的长注释）。
               device 侧那份 qRadio 只有「它自己推过电台列表」时才为 true，
               页面用 play_url 起的直播流永远是 false ⇒ 会被标成「本地曲库」。 */
            /* ★★ pl-1.6.45：换曲观察期 —— 曲目 key（曲名|歌手|地址）一变，
               先不给来源结论（viaPend=true：curVia 置空、qRadio 置 false），
               页面据此把 npTag **藏起来**，等 1.2s 稳定后再贴标签。
               背景：切到电台时服务端手里的还是上一首的 url ⇒ 先标「在线搜到的」，
               等电台名单命中的那一轮才改成「电台」⇒ 用户看到标签先错后改。
               用户要求：换了就先消失，识别出来了再显示。 */
            {
                String ck = nz(s == null ? "" : s.getTitle()) + "|"
                        + nz(s == null ? "" : s.getArtist()) + "|"
                        + nz(s == null ? "" : s.getUrl());
                long nowMs = System.currentTimeMillis();
                if (!ck.equals(sViaKey)) {
                    sViaKey = ck;
                    sViaPendTs = nowMs;
                }
                viaPend = (nowMs - sViaPendTs) < VIA_PEND_MS;
            }
            m.put("viaPend", viaPend);
            m.put("qRadio", radio && !viaPend);
            m.put("liveStream", radio);
            /* ★ pl-1.6.44：曲目来源（页面 npTag 的「在线搜到的/在线随机」靠它）。
               以前 player 侧**从来没给过 curVia** ⇒ 页面 QVIA 恒空，于是判来源时
               直接掉到 PTYPE —— 本地引擎播的在线歌、收藏里的在线歌，全被标成
               「本地曲库」。player 手里没有 device 那份 e[7] 来源标记，只能从
               当前曲的 url 反推（够用了）。 */
            m.put("curVia", viaPend ? "" : viaOf((s == null) ? "" : nz(s.getUrl()), radio));
            /* ★ pl-1.6.27：页面认的是 playingUi()（含「用户主动暂停」记账），
               不是裸 isPlaying() —— 后者在暂停后仍可能返 true，页面图标/进度条就得多等一轮
               8 秒轮询才停。 */
            m.put("playing", PCtl.playingUi());
            /* ★ pl-1.6.27：电台不配封面。device 是按「歌名+歌手」去落的，
               电台的曲名是 ICY 流标题（"国际新闻" 之类），搜出来的封面/歌手全是错的，
               贴上去只会误导 ⇒ 一律置空，页面回落成转碟。 */
            if (radio) {
                m.put("curPic", "");
            }
            /* ★ pl-1.6.12：这首拖得动吗（本地播放态 + 已拿到播放服务的 Binder）。
               页面据此显示/隐藏拖动把手 —— 推流中或没在播时不给假的拖动条。
               pl-1.6.24：直播流不能拖（没有终点，seek 到哪都是「从现在起」）。 */
            m.put("seekOk", !radio && PCtl.seekReady());
            /* pl-1.6.9：假播检测（显示「播放中」但其实没出声 —— 典型是在线歌取链失败）。
               页面据此把「播放中」改成「缓冲中（可能没出声）」并停掉进度条，
               不再用一个假装在走的时间骗人。 */
            PCtl.samplePos();
            m.put("stalled", PCtl.stalled());
            m.put("stallMs", PCtl.stallMs());
            /* pl-1.6.9（二）：三态 —— loading（起播缓冲中）/ playing（真出声）/ stalled（没出声）。
               页面据此：没出声前显示「缓冲中」、出声后才走时间，总时长则一直显示。 */
            m.put("phase", PCtl.phase());
            /* ★ pl-1.6.25：还没判出来（时长还没出来、也没认出是直播流）时
               一律**不要**说「本地曲库」—— 页面据此把标签留空，等结论。
               典型场景：Exo 起播头 0.6 秒 total_time 还是 0，说它是本地曲库就是错的。 */
            m.put("liveUnknown", !radio && durMs <= 1000);
            /* pl-1.5.0：外部推送源真实状态（手机 DLNA/AirPlay 推流时 playing=true，
               「正在播放」页据此同步显示推流曲目 —— 推流走 :DlnaPlayer 独立引擎，
               本地队列是空的，不读这里页面就显示「未播放」） */
            m.put("ext", Ext.snap());
            /* 音量：与 device 同构对外给**百分比**（页面按 volume*max/100 还原档位），
               另外把真实档位 volStep / 满档 volMax 也带上，方便直接核对。 */
            int vs = PCtl.vol();
            int vm = PCtl.volMax();
            /* ★ pl-1.6.89：响铃期间 device 会把媒体流<b>设成闹钟音量</b>（那是铃声的音量）。
               播放界面是给音乐用的 —— 只该显示<b>用户自己的音乐音量</b>，否则闹钟一响
               音量条就自己跳上去。device 在 status 里报「设之前的原值」（musicVolUser），
               这里用它覆盖；不是响铃态就一动不动。 */
            int userPct = o.optInt("musicVolUser", -1);
            if (o.optBoolean("ringVolOn", false) && userPct >= 0 && vm > 0) {
                vs = Math.round(userPct * vm / 100.0f);
            }
            m.put("volStep", vs);
            m.put("volMax", vm);
            m.put("volume", (vm > 0 && vs >= 0) ? Math.round(vs * 100.0f / vm) : vs);
            JSONObject qb = PCtl.queueBrief();
            m.put("curIdx", qb.optInt("cur", -1));
            m.put("qTotal", qb.optInt("total", 0));
            m.put("tracks", qb.optInt("total", 0));
            /* pl-1.2.0：播放模式回显（1=随机 2=顺序 3=单曲循环）。 */
            m.put("mode", PCtl.mode());
            /* pl-1.2.0：queue 是本进程的**内存态** —— player 一重启它就是空的，
               而 curTitle 是 device 落盘的（跨重启存活）⇒ 顶栏会挂着上次的曲名却点不动。
               这里给页面一个 stale 标记：提示「上次播过」，并让 ▶ 走 op=replay。 */
            /* ★ pl-1.6.41：player 进程重启后，把「上一次的曲目信息」从播放页清掉。
               背景：queue 是本进程的内存态，player 一重启它就是空的；而 curTitle /
               curPic 来自 device，是**落盘的**（跨重启存活）⇒ 播放页会一直挂着上一首
               的曲名 + 封面 + 歌词 + 码率，看着像还在播，实际没声音、也点不动。
               以前只在 npNote 写一句「上次播过的曲名」——太隐晦，用户当成真状态。
               判据（三条全中才算「刚重启的残留」）：
                 ① 本进程从来没拿到过一首歌（PCtl.everSong()==false）
                 ② 现在没在播、也不是外部推流在播
                 ③ 不是正在起播缓冲（loading / stalled）—— 那是真在起，别误清
               命中 ⇒ 展示用的曲目字段全部置空，页面回落成「未播放」+ 转碟。
               ★ 连 lastTitle 都不留 —— 以前把它单独带出去给 ▶ 的 op=replay，
               结果「看着清了、点 ▶ 又把上一首播回来」，等于记录还在。
               现在重启就是重来：空队列时 ▶ 走 op=random 随机一批。 */
            {
                boolean extOn = false;
                JSONObject exNow = m.optJSONObject("ext");
                if (exNow != null) extOn = exNow.optBoolean("playing", false);
                String phNow = nz(PCtl.phase());
                boolean staleNow = !PCtl.everSong() && !extOn
                        && !m.optBoolean("playing", false)
                        && !"loading".equals(phNow) && !"stalled".equals(phNow);
                if (staleNow) {
                    m.put("curTitle", "");
                    m.put("curArtist", "");
                    m.put("curId", "");
                    m.put("curAlbum", "");
                    m.put("curVia", "");
                    m.put("curPic", "");
                    m.put("curDuration", "");
                    m.put("posMs", 0);
                    m.put("durMs", 0);
                    m.put("br", 0);
                    m.put("fmt", "");
                    m.put("sr", 0);
                    m.put("ch", 0);
                    m.put("brSrc", "");
                    m.put("qRadio", false);
                    m.put("liveStream", false);
                    m.put("liveUnknown", false);
                    m.put("viaPend", false);   // pl-1.6.45：整段清空 ⇒ 没有「未定」可言
                    m.put("seekOk", false);
                    m.put("curIdx", -1);
                    m.put("curUrl", "");
                }
            }
            m.put("stale", qb.optInt("total", 0) == 0 && !m.optBoolean("playing", false));
            /* ★ pl-1.6.42：当前这首收没收藏（本地缓存判，零额外请求）。
               favKnown=false 表示「收藏列表现在读不到（小智没在跑）」—— 页面据此
               把星标置灰，而不是显示一个假的「未收藏」让用户以为真取消了。 */
            m.put("favCur", Fav.has(nz(m.optString("curTitle", "")),
                    nz(m.optString("curArtist", ""))));
            m.put("favKnown", Fav.known());
            /* ★ pl-1.6.67：播放栏显示**真实歌曲信息** —— device 推进队列的曲名是收藏
               里的自定义原名（导入 txt 里写的），解析出来的真名在这里换上。
               ★ 必须放在 favCur **之后**：星标按 device 推进来的原名算（收藏主本的
               key 就是原名算的），换成真名会害星标误灭。
               curUrl 也换成真地址 —— 从播放页点 ☆ 收藏时存进去的才是能直接播的地址
               （占位地址虽然也能播，但下一次还得再解析一回）。 */
            if (favReal != null && favReal[0].length() > 0) {
                m.put("curTitle", favReal[0]);
                if (favReal[1].length() > 0) {
                    m.put("curArtist", favReal[1]);
                }
                if (favReal[2].length() > 0) {
                    m.put("curUrl", favReal[2]);
                }
            }
            if (progReal != null && progReal[0].length() > 0) {
                m.put("curTitle", progReal[0]);
                if (progReal[1].length() > 0) {
                    m.put("curArtist", progReal[1]);
                }
                if (progReal[2].length() > 0) {
                    m.put("curUrl", progReal[2]);
                }
            }
            o.put("music", m);
            o.put("queue", qb);

            /* ---- 小智进程 ---- */
            JSONObject dv = DevCtl.json();
            dv.put("ver", d != null ? d.optString("ver", "") : "");
            o.put("dev", dv);

            /* ---- device 停用时把它的动态段降级，免得页面显示过期数据 ---- */
            if (!devOn) {
                JSONObject ss = new JSONObject();
                ss.put("state", "未运行");
                ss.put("raw", 0);
                ss.put("rawPeak", 0);
                o.put("session", ss);
                JSONObject w = new JSONObject();
                w.put("open", false);
                w.put("url", "");
                o.put("ws", w);
            }
        } catch (Throwable t) {
            Boot.log("statusJson: " + t);
        }
        return o;
    }

    /* ---------------- HTTP 写出 ---------------- */

    private static void write(OutputStream os, int code, String type, String body)
            throws Exception {
        byte[] b = body.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.0 ").append(code).append(" OK\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes("UTF-8"));
        os.write(b);
    }

    /** 二进制响应（PNG / 204）。extra=null 时给 no-store。 */
    /**
     * pl-1.6.147：无 body 的响应（目前只有 304 Not Modified）。
     * 304 不带 Content-Length / Content-Type，浏览器直接拿本地那份用。
     */
    private static void writeEmpty(OutputStream os, int code, String reason, String extra)
            throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.0 ").append(code).append(" ").append(reason).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        if (extra != null && extra.length() > 0) {
            h.append(extra);
        }
        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes("UTF-8"));
    }

    private static void writeBytes(OutputStream os, int code, String type,
            byte[] b, String extra) throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.0 ").append(code).append(" OK\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        if (extra != null && extra.length() > 0) {
            h.append(extra);
        } else {
            h.append("Cache-Control: no-store\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes("UTF-8"));
        os.write(b);
    }

    /**
     * pl-1.6.66：占位地址 {@code /r/fav?k=<收藏 key>} 的落点 —— 播到才解析。
     *
     * <p>问 device 要真地址（{@code op=fav_resolve}），拿到就 302 跳过去。
     * device 已停用 ⇒ 直接 404，引擎跳下一首（别干等超时拖住起播）。</p>
     *
     * <p>★ pl-1.6.67：解析成功时把「key → 真名 / 真地址」记进 {@link PCtl#noteFavResolved}：
     * ① 播放栏的 curTitle/curArtist 据此换成**真名**（device 推进队列的是收藏里的
     * 自定义原名 —— 用户要求「播放栏显示真实歌曲信息」）；② 播放列表里这一行的
     * 「没配上」红底据此撤掉。device 回的真地址形如
     * {@code .../r/<source>/<rid>?title=<真名>&artist=<真歌手>}，真名从 query 里取。</p>
     */
    /* ★ pl-1.6.121：我的节目占位行解析（仿 favResolveRedirect）。
       device op=prog_resolve 回纯 URL ⇒ 302；ERR ⇒ 404（引擎跳下一首，
       与收藏「搜不到给 404」同款）。骨架行（rid 空）由 Keeper 陆续填上。 */
    private static void progResolveRedirect(OutputStream os, Map<String, String> q)
            throws Exception {
        StringBuilder qs = new StringBuilder("op=prog_resolve");
        String aid = q.get("aid");
        String ep = q.get("ep");
        if (aid != null && aid.length() > 0) {
            qs.append("&aid=").append(enc(aid));
        }
        if (ep != null && ep.length() > 0) {
            qs.append("&ep=").append(enc(ep));
        }
        String u = null;
        if (DevCtl.alive()) {
            /* ★ pl-1.6.125：15s → 40s —— 冷缓存时 device 要现拉 NAS 一整本书的集表
               （凡人修仙传实测 15.0s），15s 预算必超 ⇒ 302 发不回去、引擎 404、
               pl-1.6.124 节目行原地停 ⇒「点了一直播不了」。宁可口多等，也别判死。
               热缓存（device v1.3.307 起入列即预填）毫秒级返回，不受影响。 */
            String r = Proxy.post("/api/act", qs.toString(), 40000);
            if (r != null) {
                try {
                    JSONObject o = new JSONObject(r);
                    if (o.optBoolean("ok", false)) {
                        String m = o.optString("msg", "");
                        if (m.startsWith("http://") || m.startsWith("https://")) {
                            u = m;
                        }
                    }
                } catch (Throwable ignore) {
                    // ignore
                }
                Proxy.invalidate();
            }
        }
        if (u == null) {
            /* ★★ pl-1.6.127：解析不到 ⇒ **绝不把"非音频"交给 MediaPlayer**。
               旧版 `txt(os, 404, "no source")` 写出 `HTTP/1.0 404 OK` + 9 字节纯文本，
               mediaserver 的 NuCachedSource2 视作可重试错误（每 5s 一次、共 10 次）；
               用户在这 ~50s 里点别的集 ⇒ player reset 打断重试中的 source
               ⇒ mediaserver 卡死，之后每次 setDataSource 都 `error(-38)`，最后 SIGSEGV
               （2026-11-03「点 1631 再点 1630 就播不了」的完整因果）。
               设计原则（用户定调，与收藏预编辑歌单一致）：占位行只是「播到才解析」，
               **解析不出就什么都不播**，并且这一行标红让用户去重链接挑版本。
               实测两种"失败"响应：
                 · 直接断连 ⇒ NuHTTPDataSource 读失败(-1) ⇒ 仍重试 10 次（~50s 挂"正在播"）
                 · 200 + 0 字节 + audio/mpeg ⇒ source 立刻 eos ⇒ 引擎当场报错、不重试
               所以选后者：干净失败 ⇒ abNoSkipOnErr 原地停 ⇒ 状态如实显示"没在播"。 */
            Boot.log("prog_resolve 无源 aid=" + aid + " ep=" + ep + " ⇒ 空流，不给非音频");
            PCtl.noteProgFailed(aid, ep);      // ⇒ 队列那一行标红（miss），点它走重链接
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.0 200 OK\r\n");
            h.append("Content-Type: audio/mpeg\r\n");
            h.append("Content-Length: 0\r\n");
            h.append("Accept-Ranges: none\r\n");
            h.append("Connection: close\r\n\r\n");
            os.write(h.toString().getBytes("UTF-8"));
            os.flush();
            return;
        }
        PCtl.noteProgResolved(aid, ep);        // 解析成功 ⇒ 撤掉之前的红标
        /* ★ pl-1.6.151：device 回的真地址带 title=<服务端真名>&artist=...
           （device v1.3.332 起 prog_resolve 带 ep 触发缺集按需救援，救回的就是真名 + rid）。
           302 不会改写原 PlaySong 的 title（入列时是「第 N 集」占位名）⇒ 记一份真名，
           /api/status 据此把播放栏换成真名。 */
        if (u != null) {
            String rt = "", ra = "";
            int qi = u.indexOf('?');
            if (qi > 0) {
                for (String kv : u.substring(qi + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) { continue; }
                    try {
                        if ("title".equals(kv.substring(0, eq))) {
                            rt = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                        } else if ("artist".equals(kv.substring(0, eq))) {
                            ra = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                        }
                    } catch (Throwable ignore) { }
                }
            }
            if (rt.length() > 0) { PCtl.noteProgReal(aid, ep, rt, ra, u); }
        }
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.0 302 Found\r\n");
        h.append("Location: ").append(u).append("\r\n");
        h.append("Content-Length: 0\r\n");
        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes("UTF-8"));
        os.flush();
    }

    private static void favResolveRedirect(OutputStream os, Map<String, String> q)
            throws Exception {
        String k = q.get("k");
        String t = q.get("title");
        String a = q.get("artist");
        StringBuilder qs = new StringBuilder("op=fav_resolve");
        if (k != null && k.length() > 0) {
            qs.append("&k=").append(enc(k));
        }
        if (t != null && t.length() > 0) {
            qs.append("&title=").append(enc(t));
        }
        if (a != null && a.length() > 0) {
            qs.append("&artist=").append(enc(a));
        }
        String u = null;
        boolean tried = false;
        if (DevCtl.alive()) {
            String r = Proxy.post("/api/act", qs.toString(), 15000);
            tried = true;
            if (r != null) {
                try {
                    JSONObject o = new JSONObject(r);
                    if (o.optBoolean("ok", false)) {
                        String m = o.optString("msg", "");
                        if (m.startsWith("http://") || m.startsWith("https://")) {
                            u = m;
                        }
                    }
                } catch (Throwable ignore) {
                    // ignore
                }
                Proxy.invalidate();
                Fav.invalidate();          // 解析顺手写了真名 ⇒ 收藏缓存作废重拉
            }
        }
        if (u == null) {
            /* ★ v1.3.231：device fav_resolve 回 ERR ⇒ 这一条就是「没配上」。device 真的尝试过
               （tried）才记失败：device 没起来的 404 是瞬时故障，不该把歌判死。
               ★ pl-1.6.127：不再回 404 + 纯文本 —— 那是「非音频响应」，
               mediaserver 的 NuCachedSource2 会每 5s 重试 10 次，中途被打断就把
               mediaserver 拖死（-38/SIGSEGV，见 progResolveRedirect 注释）。
               改成不写响应直接断连：引擎立刻报错 ⇒ 歌单照旧跳下一首，但不留后遗症。 */
            if (k != null && k.length() > 0 && tried) {
                PCtl.noteFavFailed(k);
            }
            /* ★ pl-1.6.127：同样不给非音频 —— 200 + 0 字节 ⇒ 引擎当场报错跳下一首，
               不会像 404+文本那样让 NuCachedSource2 重试 10 次、打断后拖死 mediaserver。 */
            Boot.log("fav_resolve 无源 k=" + k + " ⇒ 空流，不给非音频");
            StringBuilder fh = new StringBuilder();
            fh.append("HTTP/1.0 200 OK\r\n");
            fh.append("Content-Type: audio/mpeg\r\n");
            fh.append("Content-Length: 0\r\n");
            fh.append("Accept-Ranges: none\r\n");
            fh.append("Connection: close\r\n\r\n");
            os.write(fh.toString().getBytes("UTF-8"));
            os.flush();
            return;
        }
        /* ★ pl-1.6.67：记下真名（见方法注释）。title/artist 取不到就留空 ——
           调用方（status）只在非空时才覆盖，空 = 保持 device 推来的原名。 */
        if (k != null && k.length() > 0) {
            String rt = "";
            String ra = "";
            int qi = u.indexOf('?');
            if (qi > 0) {
                for (String kv : u.substring(qi + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e <= 0) {
                        continue;
                    }
                    try {
                        if ("title".equals(kv.substring(0, e))) {
                            rt = URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                        } else if ("artist".equals(kv.substring(0, e))) {
                            ra = URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                        }
                    } catch (Throwable ignore) {
                        // ignore
                    }
                }
            }
            PCtl.noteFavResolved(k, rt, ra, u);
        }
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.0 302 Found\r\n");
        h.append("Location: ").append(u).append("\r\n");
        h.append("Content-Length: 0\r\n");
        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes("UTF-8"));
        os.flush();
    }

    /** 从 device 状态里抽 NAS MediaServer 基址（剥掉末段 /index.json）。缓存。 */
    private static volatile String sNasBase = null;
    /** ★ pl-1.6.115：device /api/act 把返回统一包成 {"ok":true,"msg":"<原文>"} 信封。
     *  msg 若本身就是 JSON 对象（prog_list 的列表 / prog_addcur 的结果），
     *  拆开还原 —— 不然页面拿到的是二次编码的字符串，d.list 取不到。 */
    private static String actUnwrap(String r) {
        if (r == null) { return null; }
        try {
            JSONObject e = new JSONObject(r);
            if (e.has("msg") && !e.has("list")) {
                String msg = e.optString("msg", "");
                if (msg.startsWith("{")) {
                    return new JSONObject(msg).toString();
                }
            }
        } catch (Throwable ignore) { }
        return r;
    }

    private static String nasBase() {
        if (sNasBase != null) {
            return sNasBase;
        }
        try {
            String st = Proxy.get("/api/status", 3000);
            if (st != null && st.length() > 0) {
                JSONObject o = new JSONObject(st);
                /* ★ pl-1.6.115 根修：device status 的 indexUrl/indexUrls 在 **music 段里**，
                   顶层根本没有 —— 原来只在顶层找 ⇒ nasBase 永远 null ⇒
                   「取不到音乐服务器地址（小智状态读不到）」，电子书搜索必失败。 */
                JSONObject mu = o.optJSONObject("music");
                JSONArray iu = (mu != null) ? mu.optJSONArray("indexUrls") : null;
                if (iu == null || iu.length() == 0) {
                    String one = (mu != null) ? mu.optString("indexUrl", "") : "";
                    if (one.length() == 0) {
                        one = o.optString("indexUrl", "");   // 顶层旧位兜底
                    }
                    if (one.length() > 0) {
                        iu = new JSONArray().put(one);
                    }
                }
                if (iu != null) {
                    for (int i = 0; i < iu.length(); i++) {
                        String u = iu.optString(i, "");
                        if (u != null && u.length() > 0 && u.startsWith("http")) {
                            int x = u.indexOf("/index.json");
                            if (x > 0) {
                                u = u.substring(0, x);
                            }
                            if (u.endsWith("/")) {
                                u = u.substring(0, u.length() - 1);
                            }
                            sNasBase = u;
                            return sNasBase;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    /** 简单 GET，带超时；失败返回 null。 */
    private static String httpGet(String url, int ms) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(ms);
            c.setReadTimeout(ms);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignore) { }
            }
        }
    }

    private static JSONObject errHolder(String msg) {
        try {
            JSONObject h = new JSONObject();
            h.put("state", "err");
            h.put("msg", msg);
            h.put("episodes", new JSONArray());
            return h;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int atoi(String s, int d) {
        try {
            return Integer.parseInt(def(s, "" + d));
        } catch (Throwable t) {
            return d;
        }
    }

    private static void json(OutputStream os, String s) throws Exception {
        write(os, 200, "application/json; charset=utf-8", s);
    }

    private static void txt(OutputStream os, int code, String s) throws Exception {
        write(os, code, "text/plain; charset=utf-8", s);
    }

    /** "1" / "on" / "true" 都算开（页面和 device 两边写法不一致，统一在这里兜住）。 */
    private static boolean parse01(String s) {
        String t = (s == null ? "" : s.trim().toLowerCase());
        return "1".equals(t) || "on".equals(t) || "true".equals(t) || "yes".equals(t);
    }

    /** 与 device 侧一致的返回体：页面 act()/setKV() 都读 {ok,msg}。 */
    private static String jsonOk(boolean ok, String msg) {
        try {
            JSONObject r = new JSONObject();
            r.put("ok", ok);
            r.put("msg", msg);
            return r.toString();
        } catch (Throwable t) {
            return ok ? "{\"ok\":true,\"msg\":\"\"}" : "{\"ok\":false,\"msg\":\"\"}";
        }
    }

    private static Map<String, String> parse(String qs) {
        Map<String, String> m = new HashMap<String, String>();
        if (qs == null || qs.length() == 0) {
            return m;
        }
        String[] kv = qs.split("&");
        for (int i = 0; i < kv.length; i++) {
            int e = kv[i].indexOf('=');
            if (e <= 0) {
                continue;
            }
            try {
                m.put(URLDecoder.decode(kv[i].substring(0, e), "UTF-8"),
                        URLDecoder.decode(kv[i].substring(e + 1), "UTF-8"));
            } catch (Throwable t) {
                // ignore
            }
        }
        return m;
    }

    /** 把 query map 还原成 form-urlencoded（转发给 device 用）。 */
    private static String buildQuery(Map<String, String> q) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        Iterator<Map.Entry<String, String>> it = q.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        if (s == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String def(String s, String dflt) {
        return (s == null || s.length() == 0) ? dflt : s;
    }

    /* ---------------- 收藏 txt 导入 / 导出（pl-1.6.62） ---------------- */

    /** 把 device 的 /api/fav JSON 渲染成可手编的 txt（每行「歌名 - 歌手」，带地址/绑定则追加）。 */
    private static String favToText(String favJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("# R1 音乐收藏\n");
        sb.append("# 每行一首：  歌名 - 歌手\n");
        sb.append("# 歌手可留空（只写「歌名」也行）；以 # 开头的行是注释，会被忽略；空行也忽略\n");
        sb.append("# 需要保留播放地址时，在行尾加「 | 地址」（没有就别写，导入时会自动重新查找）\n");
        sb.append("# 本文件由「导出我的收藏」生成时，行尾还会带配对信息（src= / rid= / rman=1 / rt= / ra= / lt= / la=）：\n");
        sb.append("#   src + rid = 这首歌绑的是哪一版在线源；rman=1 = 手动 🔗 绑过；rt/ra = 真名；lt/la = 本地曲库名\n");
        sb.append("#   kind=program = 有声书/节目（重新上传照旧按有声书解析与标记）\n");
        sb.append("#   ⇒ 原样重新上传就不用再手动绑一次；手编歌单时把这些列删掉即可（删了就按歌名重新匹配）\n");
        sb.append("# 注意：导入会整表替换，旧收藏不会自动保留。导入前请先点「导出我的收藏」留一份备份！\n");
        try {
            JSONObject o = new JSONObject(favJson);
            JSONArray a = o.optJSONArray("list");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject it = a.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    String title = it.optString("title", "").trim();
                    if (title.length() == 0) {
                        continue;
                    }
                    String artist = it.optString("artist", "").trim();
                    /* ★ pl-1.6.78 改口径：歌名行**保持收藏里的原文**（title/artist），真名放到
                       rt=/ra= 列。为什么不沿用 pl-1.6.63 的「标题直接写真名」：
                       不同原文的条目（「Believer」原文 vs 另一条写法）解析到同一个真名后，
                       导出的行会撞成同一个 key ⇒ 重新上传时 Fav.add 当成同一首合并、收藏少歌。
                       实测当前 357 首里就有 2 组（Believer / Blame）会因此被吞掉。
                       展示名不丢：rt → rtitle，导入后列表照旧显示真名。 */
                    String rt = it.optString("rtitle", "").trim();
                    String ra = it.optString("rartist", "").trim();
                    String url = it.optString("url", "").trim();
                    /* ★ pl-1.6.78：占位地址（/r/fav?k=…）只认本机 8080，导出去换台机器必然 404
                       ⇒ 有 source+rid 时宁可不写地址，让重传后按 src/rid 重新取链。 */
                    if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                        url = "";
                    }
                    String album2 = it.optString("album", "").trim();
                    String dur2 = it.optString("duration", "").trim();
                    /* ★ pl-1.6.78：歌名或歌手自身含「 - 」时改走**富格式**（| 分隔）。
                       否则拼回「歌名 - 歌手」后，导入端按第一个「 - 」拆会拆错
                       （"Song - Live" + "歌手" 会变成 Song / Live - 歌手，key 全变）。 */
                    boolean rich = title.indexOf(" - ") >= 0 || artist.indexOf(" - ") >= 0;
                    if (rich) {
                        sb.append(clr(title)).append(" | ").append(clr(artist));
                        if (url.length() > 0 || album2.length() > 0 || dur2.length() > 0) {
                            sb.append(" | ").append(clr(url));
                        }
                        if (album2.length() > 0 || dur2.length() > 0) {
                            sb.append(" | ").append(clr(album2));
                        }
                        if (dur2.length() > 0) {
                            sb.append(" | ").append(clr(dur2));
                        }
                    } else {
                        sb.append(clr(title));
                        if (artist.length() > 0) {
                            sb.append(" - ").append(clr(artist));
                        }
                        if (url.length() > 0) {
                            sb.append(" | ").append(clr(url));
                        }
                    }
                    /* ★ pl-1.6.78：绑定信息（本轮需求：「备份歌单时把已绑定的歌曲的绑定信息
                       一块带上，重新上传不用再绑一次」）。key=value 自描述列，手编可删：
                         src/rid = 绑的是哪一版在线源；rman=1 = 手动 🔗 绑过
                         rt/ra   = 真名（列表显示用；与原文不同才写，省列宽）
                         lt/la   = 本地曲库命中名（有它导入后能直接播本地文件，省一次在线取链） */
                    String src = it.optString("source", "").trim();
                    String rid = it.optString("rid", "").trim();
                    if (src.length() > 0) {
                        sb.append(" | src=").append(clr(src));
                    }
                    if (rid.length() > 0) {
                        sb.append(" | rid=").append(clr(rid));
                    }
                    if (it.optInt("rman", 0) > 0) {
                        sb.append(" | rman=1");
                    }
                    if (rt.length() > 0 && !rt.equals(title)) {
                        sb.append(" | rt=").append(clr(rt));
                    }
                    if (ra.length() > 0 && !ra.equals(artist)) {
                        sb.append(" | ra=").append(clr(ra));
                    }
                    String lt2 = it.optString("ltitle", "").trim();
                    String la2 = it.optString("lartist", "").trim();
                    if (lt2.length() > 0) {
                        sb.append(" | lt=").append(clr(lt2));
                    }
                    if (la2.length() > 0) {
                        sb.append(" | la=").append(clr(la2));
                    }
                    /* ★ pl-1.6.101：类型列 —— 有声书条目导出 kind=program，
                       重新上传后照旧按有声书解析/展示（device buildEntry 也认）。 */
                    if ("program".equals(it.optString("kind", "").trim())) {
                        sb.append(" | kind=program");
                    }
                    sb.append("\n");
                }
            }
        } catch (Throwable t) {
            Boot.log("favToText: " + t);
        }
        return sb.toString();
    }

    /** 导出单字段清洗：去换行、把竖线换掉（竖线是列分隔符）。 */
    private static String clr(String s) {
        return s.replace("\n", " ").replace("\r", " ").replace("|", "/");
    }

    /** 导出扩展列的白名单前缀（导入时认，别的一律当位置列）。 */
    private static boolean isKvSeg(String p) {
        if (p == null || p.length() < 3) {
            return false;
        }
        int eq = p.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        String k = p.substring(0, eq).trim().toLowerCase(java.util.Locale.US);
        return k.equals("src") || k.equals("rid") || k.equals("rman")
                || k.equals("lt") || k.equals("la") || k.equals("rt") || k.equals("ra")
                || k.equals("kind");
    }

    /**
     * 解析手编 txt → 收藏 JSON 数组。支持四种行样式（详见 export 注释）。
     * 歌手里的「 / 」多歌手分隔原样保留，不去拆。
     */
    private static JSONArray favFromText(String txt) throws Exception {
        JSONArray arr = new JSONArray();
        String[] lines = txt.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim().replace("\r", "");
            if (ln.length() == 0 || ln.startsWith("#")) {
                continue;
            }
            String title = "", artist = "", url = "", album = "", duration = "";
            String src = "", rid = "", rman = "", ltitle = "", lartist = "", rtitle = "", rartist = "";
            String kind = "";
            /* ★ pl-1.6.78：先按 | 切段，把 key=value 形式的「绑定信息」列挑出来，
               剩下的按老位置规则（地址 / 专辑 / 时长）填 —— 老的 3 列、5 列写法完全不受影响。 */
            String[] segs = ln.split("\\|");
            java.util.ArrayList<String> pos = new java.util.ArrayList<String>();
            for (int s = 0; s < segs.length; s++) {
                String p = segs[s].trim();
                if (!isKvSeg(p)) {
                    pos.add(p);
                    continue;
                }
                int eq = p.indexOf('=');
                String kk = p.substring(0, eq).trim().toLowerCase(java.util.Locale.US);
                String vv = p.substring(eq + 1).trim();
                if (kk.equals("src")) {
                    src = vv;
                } else if (kk.equals("rid")) {
                    rid = vv;
                } else if (kk.equals("rman")) {
                    rman = vv;
                } else if (kk.equals("lt")) {
                    ltitle = vv;
                } else if (kk.equals("la")) {
                    lartist = vv;
                } else if (kk.equals("rt")) {
                    rtitle = vv;
                } else if (kk.equals("ra")) {
                    rartist = vv;
                } else if (kk.equals("kind")) {
                    kind = vv;
                }
            }
            if (pos.size() == 0) {
                continue;
            }
            String head = pos.get(0);
            int dash = head.indexOf(" - ");
            int base;                                   // 位置列（地址/专辑/时长）从第几段开始
            if (dash >= 0) {
                /* 歌名 - 歌手 | 地址[ | 专辑 | 时长] */
                title = head.substring(0, dash).trim();
                artist = head.substring(dash + 3).trim();
                base = 1;
            } else {
                /* 富格式：歌名 | 歌手 | 地址 | 专辑 | 时长 */
                title = head;
                artist = pos.size() > 1 ? pos.get(1) : "";
                base = 2;
            }
            url = pos.size() > base ? pos.get(base) : "";
            album = pos.size() > base + 1 ? pos.get(base + 1) : "";
            duration = pos.size() > base + 2 ? pos.get(base + 2) : "";
            if (title.length() == 0) {
                continue;
            }
            JSONObject e = new JSONObject();
            e.put("title", title);
            e.put("artist", artist);
            e.put("url", url);
            e.put("album", album);
            e.put("duration", duration);
            if (src.length() > 0) {
                e.put("source", src);
            }
            if (rid.length() > 0) {
                e.put("rid", rid);
            }
            if (rman.length() > 0) {
                e.put("rman", rman);
            }
            if (ltitle.length() > 0) {
                e.put("ltitle", ltitle);
            }
            if (lartist.length() > 0) {
                e.put("lartist", lartist);
            }
            if (rtitle.length() > 0) {
                e.put("rtitle", rtitle);
            }
            if (rartist.length() > 0) {
                e.put("rartist", rartist);
            }
            if (kind.length() > 0) {
                e.put("kind", kind);
            }
            arr.put(e);
        }
        return arr;
    }

    private static String ymd() {
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        return sdf.format(new java.util.Date());
    }

    private static String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                java.util.Enumeration<java.net.InetAddress> ia = ni.getInetAddresses();
                while (ia.hasMoreElements()) {
                    java.net.InetAddress a = ia.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return "127.0.0.1";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /* ★ pl-1.6.146：本地书队列标题 —— 与 device 侧 `abEpTitle` 完全同一套规则：
       集名是纯集名（「玉佩」「墨大夫」）就补「第N集 」；标题任意位置已经有
       「第N集/回/章/话/节」就原样用，绝不拼成两个集号。
       两条链路（网页手动 /api/ablplay、小智语音 ab_play）必须一致，别只改一边。 */
    private static String ablEpLabel(int ep, String t) {
        if (t == null || t.length() == 0) {
            return "第" + ep + "集";
        }
        if (t.matches(".*第\\s*\\d+\\s*[集回章话节].*")) {
            return t;
        }
        return "第" + ep + "集 " + t;
    }

    /* ★ pl-1.6.45：换曲观察期。
       曲目 key（曲名|歌手|地址）只要在变，就一直算「还没认出来」，
       稳定满 VIA_PEND_MS 之后才把 curVia / qRadio 的真结论吐出去 ⇒
       页面标签「换了就先消失，认出来再显示」，不会先错后改。 */
    private static volatile String sViaKey = "\u0001none";
    private static volatile long sViaPendTs = 0;
    private static final long VIA_PEND_MS = 1200;

    /**
     * pl-1.6.44：从当前曲的 url 反推「这是本地曲库的歌还是在线的歌」。
     *
     * <p>页面标签（npTag）优先级是 电台 &gt; curVia &gt; PTYPE；player 这边以前
     * 根本不给 curVia，于是凡是本地引擎播的一律掉到 PTYPE=1 ⇒ 标成「本地曲库」，
     * 在线搜到的、收藏里从在线补来的歌全被冤枉。</p>
     *
     * <p>判据（player 只有 url 可用）：</p>
     * <pre>
     *   ① url 含 "/r/"         ⇒ 惰性在线地址（/r/&lt;source&gt;/&lt;rid&gt;）⇒ online
     *   ② 其它 http(s) 地址     ⇒ 内网直链（192.168./10./127./localhost）算曲库，外网算 online
     *   ③ 电台 / 空地址         ⇒ 交回 QRADIO、QUNK 处理，这里不给结论
     * </pre>
     */
    private static String viaOf(String u, boolean radio) {
        if (radio || u == null || u.length() == 0) {
            return "";
        }
        if (u.indexOf("/r/") > 0) {
            return "online";
        }
        /* ★ pl-1.6.141：本地有声书库（NAS /m/<书目录>/<书名>-第N集-<标题>.m4a）。
           语音点播本地书时队列里全是这种内网直链，光按「内网 = 曲库」判就会落到
           PTYPE=1，页面把标题「玉佩」「引子」当普通音频贴成「本地音乐」。
           文件名带「第N集/回/章/话/节」的 /m/ 直链 ⇒ abl（页面映射成「本地节目」）。 */
        if (ablUrl(u)) {
            return "abl";
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return "";                      // 本地文件路径 ⇒ 曲库
        }
        String h = u.substring(u.indexOf("://") + 3);
        int sl = h.indexOf('/');
        if (sl > 0) {
            h = h.substring(0, sl);
        }
        int c = h.indexOf(':');
        if (c > 0) {
            h = h.substring(0, c);
        }
        boolean lan = h.startsWith("192.168.") || h.startsWith("10.")
                || h.startsWith("127.") || "localhost".equals(h);
        return lan ? "" : "online";
    }

    /** pl-1.6.141：URL 解码后文件名/目录里带「第N集/回/章/话/节」⇒ 本地有声书。 */
    private static boolean ablUrl(String u) {
        if (u == null || u.indexOf("/m/") < 0) {
            return false;
        }
        String s = u;
        try { s = java.net.URLDecoder.decode(u, "UTF-8"); } catch (Throwable ignore) { }
        return s.matches("(?s).*第\\s*[0-9０-９零〇一二三四五六七八九十百千]+\\s*[集回话章节].*");
    }

    /* ★ v1.3.287 的 abResumeSeek 已在 pl-1.6.130 删除：
       它只判 seekReady（BUSY + 有 binder 就算「就绪」），新集还没 prepare 就抢着拖
       ⇒ -38 把这一集整条判死。语音续播现在统一走 abResumeGuardedSeek。 */

    /** ★ pl-1.6.130：标题里是不是这一集（「第1630集　大战再起」/「1630.xxx」）。
     *  只用于「要不要开始拖进度」的判据，判不出来就退回签名/位置判断。
     *  ★ pl-1.6.131：补「数字串」判据 —— 落雪/酷我 的有声书标题常写成
     *  「《道诡异仙》第003章 玉佩」（补零 + 量词是「章」），老的
     *  「第3 / 3集 / 3回 / 3话」一条都匹配不上，于是语音续播在同集重播时
     *  永远判不出「已经在目标集上」，10 秒后撤单、一次都不拖（实测道诡异仙第3集）。 */
    static boolean titleHasEp(String title, int ep) {
        if (title == null || title.length() == 0 || ep <= 0) {
            return false;
        }
        String t = title.replace(" ", "").replace("　", "");
        String n = String.valueOf(ep);
        if (t.indexOf("第" + n) >= 0
                || t.indexOf(n + "集") >= 0
                || t.indexOf(n + "回") >= 0
                || t.indexOf(n + "话") >= 0
                || t.indexOf(n + "章") >= 0
                || t.indexOf(n + "节") >= 0) {
            return true;
        }
        /* 兜底：把标题里每一段数字都拎出来按整数比（「第003章」⇒ 3） */
        int acc = 0;
        boolean has = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                acc = acc * 10 + (c - '0');
                has = true;
                if (acc > 999999) {                 // 明显是年份/码率之类，丢掉
                    acc = 0;
                    has = false;
                }
            } else {
                if (has && acc == ep) {
                    return true;
                }
                acc = 0;
                has = false;
            }
        }
        return has && acc == ep;
    }

    /** ★ pl-1.6.120：网页点播的秒数续播（自足版）。
       preSig = 点播前的「URL|标题」签名；device 切到新集后签名会变，
       变了且 seekReady ⇒ seekTo(pos)。45s 窗口，签名不变（切集失败/没切）则放弃。
       只在本地播放态 seek（seekReady 内部已判），电台/远端不碰。

       ★★★ pl-1.6.129（2026-11-03 实锤 logcat）：以前只判 seekReady，而 seekReady
       = BUSY + 拿得到 binder —— **prepareAsync 还没回来时也是 true**。于是续播的
       500ms 轮询常常抢先在新集还没 prepare 时拖一把：

       <pre>
       20:08:25.707 E/MediaPlayer: Attempt to call getDuration without a valid mediaplayer
       20:08:25.707 E/MediaPlayer: Attempt to perform seekTo in wrong state: mCurrentState=0
       20:08:25.708 E/MediaPlayer: Error (-38,0)
       20:08:25.717 NetPlayer4 set new PlayState: 4  (MEDIA_PLAY_ERROR)
       </pre>

       ⇒ 刚排进队列的那一集被**整条判死**，用户看到的就是「点续播只载入队列不出声」，
       而且这一单守卫还会在 45s 里反复打，把后面手点的每一集也一起打死
       （= 「手动点概率能播起来，大概率不能」）。
       ⇒ 现在改判 {@link PCtl#seekReadyNow()}（真在出声 + 有总时长），
       并且一单只试 3 次、8 秒不切集就作废，绝不长期挂着手贱拖。 */
    private static final java.util.concurrent.atomic.AtomicInteger AB_RESUME_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** ★ pl-1.6.129：任何一次点播都作废上一单续播守卫（原来只有带 pos 的才作废）。 */
    static void abResumeCancel() {
        AB_RESUME_SEQ.incrementAndGet();
    }

    private static void abResumeGuardedSeek(final int posSec, final String preSig) {
        abResumeGuardedSeek(posSec, preSig, 0);
    }

    /** ★ pl-1.6.130：expectEp &gt; 0（语音续播）时，「是不是新的一集」还要看标题里有没有
     *  这个集号 —— 语音续播很可能和上一轮是**同一集**、位置都在 0 秒附近，
     *  光靠「签名变了 / 位置回退」判不出来，会等到 10 秒超时一次都不拖。 */
    private static void abResumeGuardedSeek(final int posSec, final String preSig,
                                            final int expectEp) {
        final int mySeq = AB_RESUME_SEQ.incrementAndGet();
        final long t0 = System.currentTimeMillis();
        /* ★ pl-1.6.129：判断「新的一集真的起播了」不能只看曲目签名 ——
           同一集续播（最常见：听了一半再点续播）签名一模一样，
           老逻辑会以为「还没切集」，干等到超时、一次都不拖。这里补一条：
           起播总是从 0 秒开始 ⇒ 位置明显回退 = 新一轮播放已开始。 */
        long prePos = 0;
        try {
            PlayPositionInfo pp = PCtl.pos();
            prePos = (pp == null) ? 0 : pp.getPlay_time();
        } catch (Throwable t) {
            prePos = 0;
        }
        final long prePosMs = prePos;
        new Thread(new Runnable() { public void run() {
            int tries = 0;
            String last = "";
            try {
                for (int i = 0; i < 60; i++) {          // 最多 30s（原来 45s，且只在真出声后才拖）
                    Thread.sleep(500);
                    if (AB_RESUME_SEQ.get() != mySeq) return;   // 有点了新的一单 ⇒ 本单作废
                    PlaySong s = PCtl.song();
                    String sig = (s == null) ? "" : (nz(s.getUrl()) + "|" + nz(s.getTitle()));
                    boolean switched = (preSig.length() == 0) || !sig.equals(preSig);
                    long cur = 0;
                    try {
                        PlayPositionInfo cp = PCtl.pos();
                        cur = (cp == null) ? 0 : cp.getPlay_time();
                    } catch (Throwable t) {
                        cur = 0;
                    }
                    boolean restarted = prePosMs > 5000 && cur >= 0 && cur < 3000;
                    boolean onEp = (expectEp > 0)
                            && titleHasEp(s == null ? "" : nz(s.getTitle()), expectEp);
                    if (!switched && !restarted && !onEp) {
                        /* 20 秒还没换集/没重起 ⇒ 这一单的起播已经失败（无源/报错），别干耗。
                           ★ pl-1.6.131：原来 10s 太紧 —— 语音续播要现拉页解析，慢的时候
                           10 秒还在 prepare，守卫先撤单了（实测道诡异仙第3集）。 */
                        if (System.currentTimeMillis() - t0 > 20000) {
                            Boot.log("abResumeGuardedSeek: 20s 没换集也没重起 ⇒ 撤单 (pos="
                                    + posSec + "s, sig unchanged)");
                            return;
                        }
                        continue;
                    }
                    if (!PCtl.seekReadyNow()) continue;   // ★ 等「真在出声 + 有总时长」
                    String r = PCtl.seekTo(Math.max(0, posSec) * 1000);
                    last = r == null ? "null" : r;
                    if (last.startsWith("OK")) {
                        Boot.log("abResumeGuardedSeek => " + last + " (pos=" + posSec + "s)");
                        return;
                    }
                    if (++tries >= 3) break;              // ★ 拖不动就撤，不反复打
                }
                Boot.log("abResumeGuardedSeek: give up (pos=" + posSec + "s, tries=" + tries
                        + ", last=" + last + ")");
            } catch (Throwable t) {
                Boot.log("abResumeGuardedSeek: " + t);
            }
        }}, "ab-resume-guard").start();
    }

    private WebSrv() {
    }
}
