package cn.workbuddy.r1client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 小智官方服务器 WebSocket 客户端（按需连接模式）+ MCP 工具服务端。
 *
 * <p>⚠️ 本文件 2026-10-26 曾因一次改写脚本失误被删除（rename 备份被同一条命令的
 * {@code os.remove} 清掉），后用 jadx 从 {@code v22classes/XiaoZhiWs.class}
 * 反编译重建，并补回注释与可读命名。<b>逻辑与原文件等价</b>，已通过完整 javac 与
 * 构建自检；如果以后有人对比历史版本发现注释措辞不同，那是这次重建造成的。</p>
 *
 * <h3>关键决策（沿用 C3 主线的教训）</h3>
 * <ol>
 *   <li><b>按需连接</b>：空闲时零连接，避免官方服务器 70s/80s 空闲踢出。</li>
 *   <li><b>上行裸 opus</b>，绝不加 {@code [0x01,0x01]} 之类帧头（C3 的 ASR 单字 bug 根因）。</li>
 *   <li>TTS stop 后延迟一段时间主动 close（与 S2 official 固件行为一致）。</li>
 * </ol>
 *
 * <h3>MCP：三类工具按前缀分发</h3>
 * <pre>
 *   self.r1player.*  → PlayerBridge.dispatch()   音乐 / 收藏 / 音量 / 电台
 *   self.r1light.*   → R1Lights.mcp()            灯效 / 夜灯
 *   self.r1alarm.*   → Alarm.mcp()               闹钟（v1.3.245 起）
 * </pre>
 * <p>工具描述是这个项目里最贵的资产之一 —— 模型会不会「关闭闹钟」当「停用闹钟」，
 * 全靠描述里把边界写死。改描述前先想清楚：模型会怎么误读。</p>
 */
public final class XiaoZhiWs {

    private static final String TAG = "R1CLIENT";

    private static final String PLAYER_PREFIX = "self.r1player.";
    private static final String LIGHT_PREFIX = "self.r1light.";
    /** v1.3.245：闹钟（调度在 device 进程，所以工具也挂在 device 的 WS 上） */
    private static final String ALARM_PREFIX = "self.r1alarm.";
    /** v1.3.330：会话控制（「退下 / 安静」⇒ 真正退出 LISTENING） */
    private static final String SESSION_PREFIX = "self.r1session.";

    /** 官方服务器（同 C3 固件）。可被 OTA 下发的 websocket.url 覆盖 */
    private static final String WS_URL = "wss://api.tenclass.net/xiaozhi/v1/";

    /** TTS 结束后多久主动断开（按需连接模式的关键参数） */
    private static final long IDLE_CLOSE_DELAY_MS = 120000L;
    /** 重连退避上限 */
    private static final long BACKOFF_MAX_MS = 30000L;

    public interface Listener {
        void onConnected();

        void onHandshakeDone();

        void onTtsStart();

        void onTtsAudio(byte[] opusFrame);

        void onTtsStop();

        void onStt(String text);

        void onClosed(String reason);

        void onError(String msg);
    }

    private final Listener listener;

    private OkHttpClient client;
    private volatile WebSocket ws;
    /** 上行计数（诊断用：只有前几次和每 50 次落日志，免得刷屏） */
    private volatile int uplinkSent;
    private volatile int uplinkFail;

    private String token;
    private volatile String deviceId;
    private volatile String clientId;

    private volatile boolean wantConnected;
    private volatile String wsUrl = WS_URL;
    private volatile long idleCloseDelayMs = IDLE_CLOSE_DELAY_MS;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean open = new AtomicBoolean(false);
    /** 二进制帧日志只打前 2 条（TTS 音频每 60ms 一帧，全打会把日志冲爆） */
    private int binLogged;
    private volatile boolean helloDone;
    /** v1.3.330：「退下」是否已排队（MCP 工具与 STT 兜底可能同时命中，只收一次） */
    private volatile boolean quitPending = false;
    private long reconnectBackoff = 1000L;
    private int idleCloseSeq;
    /**
     * ★ 连接代号：每次 connect 递增。所有回调都先比对代号，
     * 过期 socket 的迟到事件一律丢掉 —— 没有这个，快速重连后旧 socket 的
     * onClosed 会把新连接的状态清掉。
     */
    private volatile int connGen;

    private final Runnable idleCloseTask = new Runnable() {
        public void run() {
            Log.i(TAG, "idle timeout reached, closing ws (on-demand mode)");
            close("idle-timeout");
        }
    };

    public XiaoZhiWs(Listener listener) {
        this.listener = listener;
    }

    public void setToken(String t) {
        token = t;
    }

    public void setDeviceId(String id) {
        deviceId = id;
    }

    public void setClientId(String id) {
        clientId = id;
    }

    public void setUrl(String u) {
        if (u != null && u.length() > 0) {
            wsUrl = u;
            Log.i(TAG, "ws url set from OTA: " + u);
        }
    }

    public boolean isOpen() {
        return open.get();
    }

    public String getUrl() {
        return wsUrl;
    }

    /** v1.3.259：当前生效的 token（OTA 返回或手动设置），供状态栏显示 */
    public String getToken() {
        return token;
    }

    public void setIdleCloseDelayMs(long ms) {
        if (ms > 0) {
            idleCloseDelayMs = ms;
            Log.i(TAG, "idle close delay = " + ms + "ms");
        }
    }

    public void connect() {
        connectInternal(true);
    }

    /** 只探活不常驻：连上握个手就够，用于「语音开关」检测服务端可达性 */
    public void probe() {
        connectInternal(false);
    }

    private void connectInternal(boolean want) {
        wantConnected = want;
        if (open.get() || ws != null) {
            if (!helloDone) {
                // 上一条没握手完成的 socket：补发 hello，别重开
                Log.w(TAG, "connect() on already-open socket without handshake -> re-send hello");
                SessionLog.i("connect() re-sent hello on pre-opened socket");
                sendHello();
                return;
            }
            return;
        }
        helloDone = false;
        main.removeCallbacks(idleCloseTask);

        Request.Builder rb = new Request.Builder()
                .url(wsUrl)
                .addHeader("User-Agent", "R1Client/1.0")
                .addHeader("Protocol-Version", "1");
        if (token != null && token.length() > 0) {
            rb.addHeader("Authorization", "Bearer " + token);
        }
        if (deviceId != null && deviceId.length() > 0) {
            rb.addHeader("Device-Id", deviceId);
        }
        if (clientId != null && clientId.length() > 0) {
            rb.addHeader("Client-Id", clientId);
        }

        final int gen = ++connGen;
        Log.i(TAG, "connecting " + wsUrl);
        SessionLog.i("connecting " + wsUrl);

        getClient().newWebSocket(rb.build(), new WebSocketListener() {
            public void onOpen(WebSocket w, Response resp) {
                if (gen != connGen) {
                    try {
                        w.close(1000, "stale");
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                ws = w;
                open.set(true);
                reconnectBackoff = 1000L;
                binLogged = 0;
                uplinkSent = 0;
                uplinkFail = 0;
                Log.i(TAG, "ws open");
                SessionLog.i("ws open");
                sendHello();
                main.post(new Runnable() {
                    public void run() {
                        listener.onConnected();
                    }
                });
            }

            public void onMessage(WebSocket w, String text) {
                if (gen == connGen) {
                    handleText(text);
                }
            }

            public void onMessage(WebSocket w, ByteString bytes) {
                if (gen != connGen) {
                    return;
                }
                if (binLogged < 2) {
                    binLogged++;
                    Log.i(TAG, "<- bin#" + binLogged + " " + bytes.size() + "B");
                    SessionLog.i("<- bin#" + binLogged + " " + bytes.size() + "B");
                }
                final byte[] frame = bytes.toByteArray();
                main.post(new Runnable() {
                    public void run() {
                        listener.onTtsAudio(frame);
                    }
                });
            }

            public void onFailure(WebSocket w, final Throwable t, Response resp) {
                if (gen != connGen) {
                    return;
                }
                open.set(false);
                ws = null;
                SessionLog.i("uplink summary: sent=" + uplinkSent + " fail=" + uplinkFail);
                final String code = resp != null ? "http=" + resp.code() : "";
                Log.e(TAG, "ws failure " + code + " : " + t);
                SessionLog.i("ws failure " + code + " : " + t);
                main.post(new Runnable() {
                    public void run() {
                        listener.onError("ws failure " + code + " " + t.getMessage());
                        if (wantConnected) {
                            scheduleReconnect();
                        } else {
                            listener.onClosed("failure");
                        }
                    }
                });
            }

            public void onClosed(WebSocket w, int code, String reason) {
                if (gen != connGen) {
                    return;
                }
                open.set(false);
                ws = null;
                SessionLog.i("uplink summary: sent=" + uplinkSent + " fail=" + uplinkFail);
                Log.i(TAG, "ws closed code=" + code + " reason=" + reason);
                SessionLog.i("ws closed code=" + code + " reason=" + reason);
                final String why = "code=" + code + " " + reason;
                main.post(new Runnable() {
                    public void run() {
                        if (wantConnected) {
                            listener.onClosed(why + " (will reconnect)");
                            scheduleReconnect();
                        } else {
                            listener.onClosed(why);
                        }
                    }
                });
            }
        });
    }

    private void scheduleReconnect() {
        long delay = reconnectBackoff;
        reconnectBackoff = Math.min(BACKOFF_MAX_MS, reconnectBackoff * 2);
        Log.i(TAG, "reconnect in " + delay + "ms");
        main.postDelayed(new Runnable() {
            public void run() {
                if (wantConnected && !open.get()) {
                    connect();
                }
            }
        }, delay);
    }

    public void close(String reason) {
        wantConnected = false;
        connGen++;
        helloDone = false;
        main.removeCallbacks(idleCloseTask);
        WebSocket old = ws;
        ws = null;
        open.set(false);
        final String why = old == null ? reason + " (socket never opened)" : reason;
        if (old != null) {
            try {
                old.close(1000, reason);
            } catch (Throwable ignored) {
            }
        }
        main.post(new Runnable() {
            public void run() {
                listener.onClosed(why);
            }
        });
    }

    /** 一轮会话结束后调用：idleCloseDelayMs 后自动断开（按需连接） */
    public void markIdle() {
        main.removeCallbacks(idleCloseTask);
        main.postDelayed(idleCloseTask, idleCloseDelayMs);
    }

    private void sendHello() {
        try {
            JSONObject audio = new JSONObject();
            audio.put("format", "opus");
            audio.put("sample_rate", AudioCapture.SAMPLE_RATE);
            audio.put("channels", 1);
            audio.put("frame_duration", 60);

            JSONObject features = new JSONObject();
            features.put("mcp", true);

            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("version", 1);
            hello.put("features", features);
            hello.put("transport", "websocket");
            hello.put("audio_params", audio);

            if (sendText(hello.toString())) {
                Log.i(TAG, "hello sent: " + hello);
                SessionLog.i("hello sent: " + hello);
            } else {
                Log.e(TAG, "hello NOT sent (socket missing): " + hello);
                SessionLog.i("hello NOT sent (socket missing)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "sendHello threw", t);
        }
    }

    public boolean sendAudio(byte[] opus) {
        WebSocket w = ws;
        if (w == null || !open.get()) {
            uplinkFail++;
            if (uplinkFail <= 3 || uplinkFail % 50 == 0) {
                SessionLog.i("uplink FAIL x" + uplinkFail + " (socket missing/closed)");
            }
            return false;
        }
        boolean ok = w.send(ByteString.of(opus));
        if (ok) {
            uplinkSent++;
            if (uplinkSent <= 3 || uplinkSent % 50 == 0) {
                SessionLog.i("uplink #" + uplinkSent + " (" + opus.length + "B opus)");
            }
        } else {
            uplinkFail++;
            if (uplinkFail <= 3 || uplinkFail % 50 == 0) {
                SessionLog.i("uplink FAIL x" + uplinkFail + " (send() rejected)");
            }
        }
        return ok;
    }

    public void sendStartListening() {
        sendText("{\"type\":\"listen\",\"state\":\"start\",\"mode\":\"auto\"}");
    }

    public void sendStopListening() {
        sendText("{\"type\":\"listen\",\"state\":\"stop\"}");
    }

    public void sendAbort(String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "abort");
            if (reason != null && reason.length() > 0) {
                o.put("reason", reason);
            }
            sendText(o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "sendAbort threw", t);
        }
    }

    public void sendWakeWordDetected(String word) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "listen");
            o.put("state", "detect");
            o.put("text", word);
            sendText(o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "sendWakeWordDetected threw", t);
        }
    }

    // ---------------- MCP ----------------

    private void sendMcpInitializeResult(int id) {
        try {
            JSONObject serverInfo = new JSONObject();
            serverInfo.put("name", "r1-client");
            serverInfo.put("version", "1.3.6");

            JSONObject result = new JSONObject();
            result.put("protocolVersion", "2024-11-05");
            result.put("capabilities", new JSONObject());
            result.put("serverInfo", serverInfo);
            sendMcpResult(id, result);
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpInitializeResult threw", t);
        }
    }

    /**
     * 上报工具表。
     *
     * <p>★ v1.3.246 修复：以前 {@code !isMyMusic()} 那条早退分支<b>只注册灯控</b>，
     * 于是用户把「自定义音乐库」一关，语音就连闹钟也设不了了 —— 闹钟跟音乐库开关
     * 本来没关系（没歌可播时还有提示音兜底）。现在两个分支都注册闹钟。</p>
     */
    private void sendMcpToolsResult(int id) {
        if (!PlayerBridge.isMyMusic()) {
            try {
                JSONArray tools = new JSONArray();
                addLightTools(tools);
                addAlarmTools(tools);
                addSessionTools(tools);
                JSONObject result = new JSONObject();
                result.put("tools", tools);
                sendMcpResult(id, result);
                SessionLog.i("-> mcp tools/list: " + tools.length()
                        + " tools (自定义音乐库已关闭 ⇒ 灯控 + 闹钟 + 会话)");
            } catch (Throwable t) {
                Log.e(TAG, "sendMcpToolsResult(light only) threw", t);
            }
            return;
        }
        try {
            JSONArray tools = new JSONArray();
            tools.put(tool("play_song", "播放**指定的某一首**歌。用户说「播放晴天」「我想听七里香」时用这个，必须传 title（完整歌名）。它会把这一首排在第一位、后面接上整个曲库，播完自动续播；如果你想连播某个歌手的**多首**，改用 play_artist。不知道该播什么时先用 list 或 search 查库里有什么。⚠️ 电台（先锋898、FM91.8 这类）不是歌，要用 play_radio。这是让音箱出声的入口之一，不要只调 play。★ 用户把**歌手和歌名一起说了**（「播放周杰伦的晴天」「我想听陈奕迅的浮夸」），请把整句**原样**传进 title，不要自己拆成只有歌名 —— 后台会按歌手+歌名一起匹配，你只传歌名的话很可能挑到翻唱版。★ 用户**点名要听某首歌**时，直接调这个（哪怕你觉得库里可能没有）—— 后台会先查本地曲库，没有就**自动去在线搜索**并播放；**不要**先调 search 试探（search 只覆盖本地曲库池，搜不到不代表没有这首歌）。★ 返回结果里如果写了「还有 N 首同名的」，说明库里有多个版本（原唱/翻唱/Live），**必须把这件事告诉用户**并问要不要换（如「还找到周杰伦版的，要听吗」），不要闷声播完就当完事。",
                    prop("title", "string", "歌名，如「晴天」；歌手+歌名一起说时整句原样传入，如「周杰伦的晴天」")));

            tools.put(tool("play_artist", "随机连播某个歌手（或某个关键词）的**多首**歌。用户说「放周杰伦的歌」「来几首邓丽君」「随便放点周杰伦」「我想听王菲的歌」时用这个 —— 凡是**指定了歌手但没指定具体歌名**，一律用它，不要用 play_song（那个只播一首）、也不要用 random（那是全库随机）。不知道库里有哪些歌手时，先用 list 看歌手列表。★ 后台会**自动补上在线搜到的同歌手歌曲**（本地曲库 + 在线曲库一起放），所以哪怕本地池里这个歌手只有两三首，也不用担心不够放；本地池里**完全没有**这个歌手时，它也会直接去在线找来播，**不要**因为你觉得库里可能没有就回复用户「找不到」。结果话术里会写明「本地 N 首 + 在线 M 首」，照原样告诉用户即可。注意：如果**连歌手/关键词都没有**（如「随机放 5 首」），请用 random 的 count",
                    props(prop("artist", "string", "歌手名，如「周杰伦」；也可以是风格/专辑等任意关键词"),
                            prop("count", "integer", "要播几首；**不填 = 这个歌手的全部歌曲**。只有用户明确说了数量（「放 5 首周杰伦」「来 3 首」）才填，没说数量就不要填"))));

            tools.put(tool("play_index", "播放音乐库里**第 N 首**。序号从 1 开始，必须先通过 search 或 list 拿到带序号的列表才知道第几首是什么。⚠️ 这个工具会把**整张曲库列表推成新的播放队列**，顶掉当前正在播的一切内容 —— **有声书/评书跳集绝对不要用**（会把正在听的书顶成曲库里的歌）！跳集用 ab_play 传 ep；想在**当前播放列表**内跳到第 N 首（不换列表、不打断顺序）用 play_at。",
                    prop("index", "integer", "第几首，从 1 开始的正整数")));
            tools.put(tool("play_at", "跳到**当前播放列表**的第 N 首（序号从 1 开始），**不改变列表内容和顺序**。用户说「跳到列表第 3 首」「播列表里的第 8 个」「回到第一首」这类**在当前队列内跳转**时用。⚠️ 想放**曲库**里第 N 首才用 play_index；有声书跳集用 ab_play 传 ep。",
                    prop("index", "integer", "当前播放列表的第几首，从 1 开始")));

            tools.put(tool("search", "只查询不播放：按歌名或歌手搜出匹配的曲目，**返回带序号的列表**。用户问「有没有周杰伦的歌」「库里有什么邓丽君」时用；或者当你需要序号来调 play_index 时先用它。⚠️ 它**只覆盖本地曲库池**（约 100 首），搜不到≠没有这首歌；用户明确要听某首歌时请直接调 play_song（后台会自动在线搜），不要用这里的空结果回复用户「没找到」。",
                    prop("keyword", "string", "歌名或歌手名关键词")));

            tools.put(tool("random", "开始随机播放。默认**整个曲库**随机排进播放列表（播完自动续不会停）。用户说「来点音乐」「随便放」「播放音乐」「放点歌」「随机播放」——只要**没有指定歌手也没有指定歌名**，一律用这个，不要用 next。⚠️ 用户说了**数量**（「随机放 5 首」「来 3 首」「放十首」）时必须传 count，**绝对不要用 next 去一首首凑数** —— next 只是切下一曲，既不会限制播放列表长度，还会把正在播的歌切掉。如果指定了歌手（如「放点周杰伦」），请用 play_artist（它也有 count）。⚠️ 用户说了「**在线**随机」「网络的歌」「换一批」「来点没听过的」这类意思，请用 random_online（每次重新拉一批新的），不要用这个 —— 这个播的是本地曲库快照，内容每次都一样。这是「从无到有开始播」的入口。",
                    prop("count", "integer", "要随机几首；**不填 = 整个曲库**。用户说「随机放 5 首」就填 5")));

            tools.put(tool("random_online", "从**网络在线曲库**随机拉一批歌来放，**每次都是新的一批**。用户说「随机播放在线音乐」「放点在线的歌」「在线随机播放」「换一批歌」「放点没听过的」「来点新歌」时用这个 —— 凡是**带「在线 / 网络 / 新的一批 / 换一批」这类意思**、又没有指定歌手和歌名，一律用它，不要用 random。⚠️ 与 random 的区别：random 播的是**本地曲库快照**（启动时加载的那批，内容固定不变）；这个每次都去网络曲库重新摇一批，内容不一样。如果指定了歌手（「在线放点周杰伦」），请用 play_artist —— 它现在也会同时搜在线的歌。",
                    prop("count", "integer", "要几首；**不填 = 100 首**（默认一批就是 100）。用户说「在线随机放 20 首」就填 20")));

            tools.put(tool("list", "列出**本地曲库**概况：有哪些歌手（各多少首）、带序号的样例、电台列表。用户问「本地歌曲里有什么」「本地有多少首」「能放什么」「有哪些歌手」时用；想知道某个歌手具体有哪些歌请用 search。★ 返回里「本地共 N 首」才是本地曲库的数量，「另收录了 M 首在线听过的歌」不属于本地 —— 转述时别把两者混在一起。",
                    null));

            tools.put(tool("pause", "暂停当前正在播放的音乐", null));
            tools.put(tool("play", "继续播放（接暂停处）。只有用户说「继续/接着放」、且之前确实在播时才用；这不会选歌，要播新歌请用 play_song", null));
            tools.put(tool("stop", "停止播放", null));
            tools.put(tool("next", "下一曲。**正在播有声书/节目时也用它**：队列里有下一首就直接切，到播放列表末尾会自动续拉下一集——用户说「下一集」「下一回」「播下一集」用它或 ab_next 都行；如果当前没在播，用户说「播放音乐」请用 random，用这个不会开始播放。⚠️ 它**不是**选歌/凑数量工具：用户要「放 5 首」请用 random 或 play_artist 的 count，连续调 next 只会不停切歌", null));
            tools.put(tool("prev", "上一曲。**只有在音乐正在播放时**才用", null));
            tools.put(tool("volume", "设置音量 0~100",
                    prop("level", "integer", "音量 0~100")));

            tools.put(tool("play_radio", "播放**网络电台**。用户说「播放先锋898」「听 FM91.8」「放个电台」「换个电台」时用这个，传 name = 电台名（用 list 能查到有哪些电台）。⚠️ 电台名不是网址，**绝对不要**自己拼造 URL（例如把「FM91.8」写成 http://fm91.8），也不要用 play_url 播电台。要说**完整台名**（如「上海经典音乐广播」「Groove Salad」），只说「音乐台」这种泛词匹配不上。网络流要缓冲，可能几秒后才出声。⚠️ 一次只播一个台 —— 用户要把**所有**电台排进播放列表（「所有电台都放一遍」「电台全加进去」）请用 play_radio_all。★ 用户说「第 3 个电台」「第三个」「3 号台」这种**序号**时，**必须传 index = 那个数字**（3），不要去猜台名、也不要改用别的工具；序号就是 list 里电台名字前的那个编号。",
                    props(prop("name", "string", "电台名，如「先锋898」「FM91.8」；也可以直接写「第3个」这类序号"),
                            prop("index", "integer", "可选：第几个电台（1 开始）。用户说「第 N 个电台」时填这个"))));

            tools.put(tool("play_radio_all", "把**所有网络电台**排进播放列表并开始播第一个。用户说「把所有电台加进播放列表」「所有电台都放一遍」「电台全部来一遍」时用这个（不用传 name）。想从某个台开始就传 name = 那个台名，它会被排到第一个。排好后说「下一首」「上一首」可以换台；⚠️ 直播流没有结尾，**不会自动跳下一个台**，换台只能手动。⚠️ 用户说的是「第 N 个电台」这种**某一个**台，请改用 play_radio 并传 index，不要用这个把整张列表推过去。",
                    props(prop("name", "string", "可选：从哪个台开始播（完整台名）"),
                            prop("index", "integer", "可选：从第几个电台开始（1 开始）"))));

            tools.put(tool("play_url", "播放一个**用户明确给出的**完整音频网址（http/https）。⚠️ 只有用户真的念出了/给出了完整网址时才用；**不要自己猜测或拼造网址**。要播电台请用 play_radio（传电台名），要播库里的歌用 play_song。",
                    prop("url", "string", "完整的 http/https 音频地址")));

            tools.put(tool("status", "查询音箱**当前是不是在放歌、放的是哪一首**。用户问「现在放的什么歌」「在放吗」「暂停一下」「声音关小点」这类问题**先查这个**，不要凭空回答。返回里有两项：**本地记录**（音箱自己的播放记录，重启后仍保留）和 DLNA状态（外部控制通道看到的状态），并且**带进度**（已播到几分几秒 / 这一集共多长 / 还剩多久）。★ 只要「本地记录=在播」就说明**确实在放**，即使 DLNA 状态是 NO_MEDIA_PRESENT / STOPPED，也**绝对不要**说「没在播」「播放列表为空」——DLNA 状态经常不同步（音箱自己起播时尤为如此）。两项都说没在播，才是真的没在播。★ 只想知道「这集多长 / 还剩多久 / 放了几分钟」用 seek_play（不传参数）更快，它也答进度。",
                    null));

            /* ★ v1.3.358：进度 / 跳转 —— 与 stem 固件的 self.stem.seek_play 同一套语义
               （sec 绝对秒 / delta 相对秒 / 都不传只查），用户「快进 30 秒」「跳到第 12 分钟」
               「这集还剩多久」都落在这一个工具上，模型不用在 status 里自己算时间。 */
            tools.put(tool("seek_play", "查询 / 调节**当前播放进度** —— 前进、后退、直接跳到第几秒。用户说「快进 30 秒」「后退 5 分钟」「跳到第 12 分钟」「从 3 分 20 秒开始」「倒回去一点」时用；问「这集多长」「还剩多久」「放了多久」也用它（不传参数）。★ 三种用法：① **sec** = 绝对秒数（「跳到第 12 分钟」⇒ sec=720，「跳到 3 分 20 秒」⇒ sec=200）；② **delta** = 相对秒数（「快进 30 秒」⇒ delta=30，「后退 2 分钟」⇒ delta=-120）；③ **两个都不传 = 只查进度**，回「已播到 X，共 Y，还剩 Z」。★ 进度是按**当前这一集 / 这一首**算的：要换集请用 ab_play（传 ep），要换歌用 next / play_song。★ 网络电台是直播流，没有终点，拖不了 —— 会返回错误，这时如实告诉用户电台不能拖进度。",
                    props(prop("sec", "integer", "绝对秒数：跳到第 N 秒（「跳到第 12 分钟」= 720，「跳到 3 分 20 秒」= 200）"),
                            prop("delta", "integer", "相对秒数：正数快进、负数后退（「快进 30 秒」= 30，「后退 5 分钟」= -300）"))));

            tools.put(tool("fav_add", "把歌**加入收藏**。用户说「收藏这首歌」「我喜欢这首歌」「这首歌不错」「加入收藏」「加进我喜欢」「这首歌好听，存下来」「收藏一下」——只要是**对当前在放的这首歌表示喜欢**，就用这个。★ **不传参数 = 收藏当前正在播放的那一首**（绝大多数情况就是它）；只有用户明确点名了**另一首**歌（「把《晴天》加进收藏」）才传 title。⚠️ 这收的是**歌**：用户说的是「收藏这本书」「收藏这个节目」这类（在播有声书/节目）就改用 prog_addcur。",
                    props(prop("title", "string", "可选：要收藏的歌名；**不填 = 收藏当前正在播放的那首**"),
                            prop("artist", "string", "可选：歌手名，匹配更准"))));

            tools.put(tool("fav_del", "**取消收藏**。用户说「取消收藏」「不喜欢这首歌」「把这首歌从收藏里删掉」「这首歌不好听」「移出收藏」「别收这首」时用这个。★ **不传参数 = 取消当前正在播放的那首**的收藏；用户点名了别的歌才传 title。⚠️ 这取消的是**歌**收藏：用户说的是「取消收藏这本书/这个节目」就改用 prog_addcur（再调一次即取消）。",
                    props(prop("title", "string", "可选：要取消收藏的歌名；**不填 = 取消当前正在播放的那首**"),
                            prop("artist", "string", "可选：歌手名"))));

            tools.put(tool("play_fav", "播放**收藏列表**（用户收藏过的那些歌）。用户说「播放我喜欢的歌」「播放收藏列表」「放我收藏的歌」「听我喜欢的音乐」「播放收藏」时用这个 —— 它把收藏里的歌整批排进播放列表并开始播第一首。★ 收藏为空时会明确报错，那时请**如实告诉用户收藏列表还是空的**，不要自作主张改去随机播放糊弄。",
                    null));

            /* v1.3.262：有声书四件套（数据=NAS MediaServer /api/ab/*，起播/进度全在本地）。 */
            tools.put(tool("ab_search", "搜**有声书/评书/相声**（按「本」汇总）。用户问「有没有道诡异仙的有声书」「找本评书」「有什么相声」「听听书」但还没确定要播哪一集时用。返回带书名/播音者/集数的列表；要播请接着调 ab_play（把书名传给 book）。⚠️ 这搜的是**节目**不是歌 —— 搜歌用 search 或 play_song。",
                    props(prop("keyword", "string", "书名或关键词，如「道诡异仙」「单田芳」「德云社」"),
                            prop("narrator", "string", "可选：演播者/版本。用户点名了演播者（如「光合积木版凡人修仙传」「关彦之版」）就填演播者名（「光合积木」「关彦之」）。同名书有多版本时用它挑对人。"))));

            tools.put(tool("ab_play", "播放**有声书/评书/相声**。用户说「播放道诡异仙第4集」「听白眉大侠第10回」「放德云社的相声」「播凡人修仙传」时用这个。book=书名（用户说的或 ab_search 查到的），ep=集数（用户说第几集/第几回就传几；**没说集数就不传**，从第1集开始）。★ 播放是**整部入列**：从那一集起整本书的后续集数会自动排进播放列表连播，不用一集集叫。★ 有声书**不是歌**：不要用 play_song 播有声书 —— 那会按歌名去曲库/在线搜，多半搜错版本或搜到单集之外的东西。返回会写实际起播的书名和集数，照实转告用户；想跳集就说「播放第N集」（再调本工具），想接上次就说「继续听」（ab_resume）。",
                    props(prop("book", "string", "书名，如「道诡异仙」"),
                            prop("ep", "integer", "第几集/第几回；不填 = 从第一集开始"),
                            prop("narrator", "string", "可选：演播者/版本。用户点名了演播者（如「光合积木版凡人修仙传」「关彦之版」）就填演播者名（「光合积木」「关彦之」）。同名书有多版本时用它挑对人，否则可能播成另一个版本。"))));

            tools.put(tool("ab_next", "播放**当前有声书的下一集**。用户听完一集说「下一集」「接着下一回」「下一话」时用。整部书已在播放列表里时其实会自动连播，这个工具用于自动连播被手动打断/用户不想等的情况（会从下一集起重新入列）。从没播过有声书时会返回错误，那时请引导用户先用 ab_play 指定书名。",
                    null));

            tools.put(tool("ab_prev", "播放**当前有声书的上一集**。用户说「上一集」「上一回」时用。正在播本批第一集时，上一集会把之前的整批（最多50集）载回播放列表并直接跳到最底部那集起播。从没播过有声书时会返回错误。",
                    null));

            tools.put(tool("ab_resume", "**继续听**上次的有声书（从上次停的那一集、**那一秒**接着放）。用户说「继续听书」「接着上次的有声书听」「继续道诡异仙」「继续播放凡人修仙传」时用；传 book 可指定书名，不传 = 最近在听的那本。★ 用户说「继续 / 接着」并且点名了**某本已经听过的书** ⇒ 优先用这个，不要用 ab_play（ab_play 不传集数是从第 1 集起）。",
                    prop("book", "string", "可选：书名；不填 = 最近在听的那本")));

            /* v1.3.288：收藏正在播的这本书到「我的节目」（与歌收藏分开）。toggle 语义。 */
            tools.put(tool("prog_addcur", "**收藏这本正在播的有声书/节目**到「我的节目」。用户说「收藏这本书」「收藏这个节目」「把这个节目加到我的节目」「收藏这本书」时用这个；**再调一次 = 取消收藏**（用户说「取消收藏这本书」「把这本书从我的节目里删掉」也走这个）。★ 只对有声书/节目有效，收**歌**用 fav_add；播着歌时调它会报「没在播有声书」。**不用传任何参数** —— 书名/版本/当前集数自动带上，收藏完用户说「继续播放」就会从最近听的集数接着播。",
                    null));

            addLightTools(tools);
            addAlarmTools(tools);
            addSessionTools(tools);

            JSONObject result = new JSONObject();
            result.put("tools", tools);
            sendMcpResult(id, result);
            SessionLog.i("-> mcp tools/list: " + tools.length()
                    + " tools (r1player dlna + r1light + r1alarm + r1session)");
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpToolsResult threw", t);
        }
    }

    /** 音乐类的工具都挂在 self.r1player. 前缀下 */
    private static JSONObject tool(String name, String desc, JSONObject props) throws Exception {
        return tool(PLAYER_PREFIX, name, desc, props);
    }

    private static JSONObject tool(String prefix, String name, String desc,
                                   JSONObject props) throws Exception {
        JSONObject t = new JSONObject();
        t.put("name", prefix + name);
        t.put("description", desc);
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", props == null ? new JSONObject() : props);
        t.put("inputSchema", schema);
        return t;
    }

    private void addLightTools(JSONArray tools) throws Exception {
        tools.put(tool(LIGHT_PREFIX, "ambient",
                "控制音箱的**氛围灯**（彩色灯效，不是唤醒时那圈白灯）。用户说「开氛围灯」「彩灯调成七彩」「灯亮一点」「灯暗一点」「渐变快点」「把灯变成蓝色」「关掉氛围灯」时用这个。所有参数都是可选的，**只传要改的那几项**；要一次改多项（「开灯并调成蓝色」）就同时传多个参数。",
                props(prop("on", "string", "开或关：\"on\" / \"off\"。不传 = 不改这一项"),
                        prop("mode", "integer", "灯效：0 官方 / 1 七彩旋转 / 2 七彩 / 3 七彩旋转1 / 4 单色。不传 = 不改"),
                        prop("luma", "integer", "亮度 1~200（默认 100）。不传 = 不改"),
                        prop("chroma", "integer", "颜色渐变速度 0~100（默认 4）。不传 = 不改"),
                        prop("color", "string", "单色模式的颜色，如 \"#66CCFF\" 或 \"66CCFF\"；只有 mode=4 单色时才看得出来"))));

        tools.put(tool(LIGHT_PREFIX, "night",
                "把音箱当**小夜灯**用：顶部拾音灯 24 颗全亮、底部氛围灯全亮，也可以只开一样。用户说「开夜灯」「夜灯只开下面的灯」「夜灯调成暖橙色」「夜灯暗一点」「关掉夜灯」时用这个。★ 夜灯期间唤醒灯、播报灯、聆听灯会**全部停用**（免得互相打架），只有说「关掉夜灯」才会恢复 —— 回复用户时要顺带说明这一点。所有参数都是可选的，只传要改的那几项。",
                props(prop("on", "string", "\"on\" 开 / \"off\" 关。不传 = 不改这一项"),
                        prop("top", "string", "只控制顶部拾音灯：\"on\" / \"off\"。不传 = 不改"),
                        prop("amb", "string", "只控制底部氛围灯：\"on\" / \"off\"。不传 = 不改"),
                        prop("color", "string", "夜灯颜色，如 \"#FF8C1A\"（暖橙）或 \"FF8C1A\"。不传 = 不改"),
                        prop("luma", "integer", "夜灯亮度 0~100（默认 30，夜里当小夜灯用，别太亮）"))));
    }

    /**
     * v1.3.245：闹钟工具。
     *
     * <p>★ 这里唯一容易出事的地方是「关闭 / 停用 / 删除」三件事的边界 ——
     * 用户喊「关闭闹钟」时模型很容易去把闹钟停用了（明天就不响了），
     * 所以 dismiss 的描述里反复强调「不动开关」，toggle 的描述里也点明「明天就不响了」。</p>
     *
     * <p>响铃歌曲：不给就是**随机播一首** —— v1.3.246 起是「收藏 / 在线曲库 / 本地」
     * 三池等概率洗牌（以前只有收藏→本地，NAS 上那几万首根本轮不到）。</p>
     */
    private void addAlarmTools(JSONArray tools) throws Exception {
        tools.put(tool(ALARM_PREFIX, "add",
                "添加一个**闹钟**（到点会播一首歌当闹铃）。用户说「每天早上七点叫我」「明天七点叫我起床」「周一到周五七点半叫我」「七点叫我」时用这个。★ 时间用 24 小时制：hour=7 minute=0 就是早上七点；下午三点是 hour=15。说不清上午下午时，按用户字面意思填。★ repeat 三选一：once = 只响一次（「明天」「就今天」）；daily = 每天；weekly = 每周某几天（必须在 days 里写清是哪几天）。★ 没说重复方式时：说了「明天 / 今天」用 once，否则用 daily。★ 闹钟默认响铃是**随机播一首歌**（收藏、在线曲库、本地随机），不用传歌名。",
                props(prop("hour", "integer", "小时，24 小时制 0~23。例如早上七点 = 7"),
                        prop("minute", "integer", "分钟 0~59，不传 = 0"),
                        prop("repeat", "string", "重复方式：\"once\" 仅一次 / \"daily\" 每天 / \"weekly\" 每周（配合 days）"),
                        prop("days", "string", "weekly 时哪几天：\"1\" 表示周一 … \"7\" 表示周日，可以写 \"1,2,3,4,5\" 或 \"1-5\" 或 \"周一到周五\"（工作日 = 1-5，周末 = 6,7）"),
                        prop("date", "string", "可选：once 的日期，格式 2026-10-27。用户说了具体日期才传；只说「明天 / 七点」不用传"),
                        prop("label", "string", "可选：给闹钟起个名，如「起床」「吃药」"))));

        tools.put(tool(ALARM_PREFIX, "list",
                "查询**现在有哪些闹钟、都开着没有**。用户问「我有哪些闹钟」「闹钟开着吗」「明天几点叫我」「我设了闹钟吗」时用这个。返回里带序号、时间、重复方式、响铃歌曲、下次几点响、是否停用。★ 接下来的停用 / 删除要用这里的**序号**。",
                null));

        tools.put(tool(ALARM_PREFIX, "toggle",
                "**停用 / 启用**某个闹钟（停用之后**以后都不响了**，明天也不会响）。用户说「把七点的闹钟关掉」「停用闹钟」「闹钟关掉别响了」「重新打开那个闹钟」时用这个。★⚠️ 如果用户是**在闹钟正响的时候**说「关闭闹钟」「别响了」「关掉闹钟」，那是**关掉这一次响铃**，必须用 dismiss，**不要用这个** —— 用这个会让闹钟明天也不响，不是用户要的。★ index 是 alarm_list 里的序号（从 1 开始）；也可以直接传 hour/minute 指明是几点那个闹钟。enabled 不传 = 翻转当前状态。",
                props(prop("index", "integer", "闹钟序号（alarm_list 里的编号，从 1 开始）"),
                        prop("hour", "integer", "可选：按时间定位，几点（0~23）"),
                        prop("minute", "integer", "可选：按时间定位，几分"),
                        prop("enabled", "string", "\"off\" 停用 / \"on\" 启用；不传 = 翻转"))));

        tools.put(tool(ALARM_PREFIX, "del",
                "**彻底删除**一个闹钟（删了就没了，以后都不响）。用户说「把七点的闹钟删了」「删掉这个闹钟」「不要这个闹钟了」时用这个。★ 只是想让某个闹钟暂时不响请用 toggle（停用），不要删。★ index 是 alarm_list 里的序号；也可以传 hour/minute 定位。",
                props(prop("index", "integer", "闹钟序号（alarm_list 里的编号，从 1 开始）"),
                        prop("hour", "integer", "可选：按时间定位，几点（0~23）"),
                        prop("minute", "integer", "可选：按时间定位，几分"))));

        tools.put(tool(ALARM_PREFIX, "dismiss",
                "**关闭正在响的闹钟** —— 只让这一次别响了，**闹钟本身照旧开着**，明天/下一次到点还会响。用户说「关闭闹钟」「别响了」「关掉闹钟」「闹钟关一下」（尤其是**闹钟正在响**的时候）就用这个。★ 它同时会把正在放的歌和提示音都停掉，并把临时抬高的音量还原。★⚠️ 不要拿它代替「停用」：用户说「以后这个闹钟别响了」要用 toggle（enabled=off）。",
                null));

        tools.put(tool(ALARM_PREFIX, "vol",
                "查询 / 设置**闹钟音量**。用户说「闹钟声音太小」「闹钟大声点」「闹钟音量调到最大」「闹钟音量是多少」时用这个。★ 实现方式：响铃时把**媒体音量**临时抬到这个值（关掉后还原），所以就算媒体被静音了，闹钟也听得见。★ v 不传 = 只查询，返回当前设定值和媒体流实际音量。",
                props(prop("v", "integer", "音量百分比 20~100，推荐 50；不传 = 只查询"))));
    }

    /**
     * v1.3.330：会话控制工具 —— 「退下 / 安静」必须真退出，不能只是回一句「好的」。
     *
     * <p>以前云端听到「退下」只回一句客气话，客户端仍停在 LISTENING 继续上行，
     * 服务器 VAD 一检测到人声又开口搭话（用户原话：「过一会儿又开始和我说话了」）。
     * 这里把「结束对话」显式做成工具，让模型有办法把这个动作落到设备上。</p>
     *
     * <p>v1.3.331：用户实测「你先退下吧 / 你可以下线了 / 你下线吧 / 再见小智 /
     * 你下班吧 / 不用你了」这些口语化说法不容易触发 —— 模型常把它们当成普通寒暄回了句话，
     * 不调工具。这里把整组告别/遣散说法**逐条写进工具描述**，并明确「不用你了」与
     * 回答式「不用了」的区别；本地 STT 兜底词表同步扩充（见 isQuitCommand）。</p>
     */
    private void addSessionTools(JSONArray tools) throws Exception {
        tools.put(tool(SESSION_PREFIX, "quit",
                "**结束本轮对话、让音箱真正退下**。用户说以下任意一种话时，**必须调用这个工具**，不能只回一句「好的」「那我不打扰了」就算完 —— 只回话不调工具，设备会一直保持聆听、继续收音，过一会儿又会开口搭话。① 直接遣散：「退下」「你先退下吧」「安静」「别说了」「不要说话」「闭嘴」「别吵」「休息吧」「你先休息」「别打扰我」「停止对话」「结束对话」「别理我」「先这样」「就这样」；② 下线/下班：「你可以下线了」「你下线吧」「下线」「你下班吧」「下班」「你先下去吧」；③ 告别：「再见小智」「再见」「拜拜」「回见」；④ 不用你了：「不用你了」「不用你啦」「不用你小智」。⚠️ ②③④ 虽然不是标准「退下」，但语义都是让用户结束对话，**一律按退下处理**。调用后设备立即收掉本轮会话：停止麦克风上行、退出聆听状态、关闭连接、熄灭拾音灯；直到用户**再次喊唤醒词**才会重新开始对话。⚠️ 这不是静音、不是暂停音乐、也不是关闹钟，不要用 self.r1player.pause / stop 代替。⚠️ 不要调用的情况：用户只是在问问题、点歌、聊天中途停顿，或说**孤立的回答式**短语「不用了」「没事」「不用了谢谢」（注意与「不用你了」区分：后者明确是让助手退下，必须调用）。不需要传参数。",
                null));
    }

    private static JSONObject prop(String name, String type, String desc) throws Exception {
        JSONObject p = new JSONObject();
        p.put("type", type);
        p.put("description", desc);
        JSONObject holder = new JSONObject();
        holder.put(name, p);
        return holder;
    }

    /** 多参数工具用 —— 合并若干个 prop() 返回的 properties 容器 */
    private static JSONObject props(JSONObject... parts) throws Exception {
        JSONObject out = new JSONObject();
        if (parts != null) {
            for (int i = 0; i < parts.length; i++) {
                JSONObject p = parts[i];
                if (p == null) {
                    continue;
                }
                Iterator<String> it = p.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    out.put(k, p.get(k));
                }
            }
        }
        return out;
    }

    private void handleMcpToolCall(JSONObject call) {
        int id = call.optInt("id", -1);
        String name = "";
        JSONObject args = null;
        try {
            JSONObject params = call.optJSONObject("params");
            if (params != null) {
                name = params.optString("name", "");
                args = params.optJSONObject("arguments");
            }
        } catch (Throwable t) {
            Log.w(TAG, "tools/call params parse: " + t);
        }

        String ret;
        boolean isError;
        if (name.startsWith(PLAYER_PREFIX)) {
            ret = PlayerBridge.dispatch(name.substring(PLAYER_PREFIX.length()), args);
            isError = ret.startsWith("ERR");
        } else if (name.startsWith(LIGHT_PREFIX)) {
            ret = R1Lights.mcp(name.substring(LIGHT_PREFIX.length()), args);
            isError = ret.startsWith("ERR");
        } else if (name.startsWith(ALARM_PREFIX)) {
            // v1.3.245：闹钟与 web 的 /api/act?op=alarm_* 走同一个 Alarm.mcp 入口
            ret = Alarm.mcp(name.substring(ALARM_PREFIX.length()), args);
            isError = ret.startsWith("ERR");
        } else if (name.startsWith(SESSION_PREFIX)) {
            // v1.3.330：「退下」—— 复用 60s 自动断开那条 ws.close -> enterIdle 收尾路径
            if ("quit".equals(name.substring(SESSION_PREFIX.length()))) {
                ret = requestQuit("mcp:" + (args == null ? "" : args.optString("reason", "")));
                isError = false;
            } else {
                isError = true;
                ret = "unknown session tool: " + name;
            }
        } else {
            isError = true;
            ret = "unknown tool: " + name;
        }

        try {
            JSONArray content = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", ret);
            content.put(text);

            JSONObject result = new JSONObject();
            result.put("content", content);
            result.put("isError", isError);
            sendMcpResult(id, result);
            SessionLog.i("<- mcp call " + name + " -> " + ret);
        } catch (Throwable t) {
            Log.e(TAG, "handleMcpToolCall threw", t);
        }
    }

    private void sendMcpResult(int id, JSONObject result) {
        try {
            JSONObject rpc = new JSONObject();
            rpc.put("jsonrpc", "2.0");
            rpc.put("id", id);
            rpc.put("result", result);

            JSONObject frame = new JSONObject();
            frame.put("type", "mcp");
            frame.put("payload", rpc);
            if (sendText(frame.toString())) {
                SessionLog.i("-> mcp result id=" + id);
            }
        } catch (Throwable t) {
            Log.e(TAG, "sendMcpResult threw", t);
        }
    }

    private boolean sendText(String s) {
        WebSocket w = ws;
        if (w == null) {
            Log.w(TAG, "sendText but ws null: " + s);
            return false;
        }
        return w.send(s);
    }

    private void handleText(String text) {
        Log.i(TAG, "<- " + text);
        SessionLog.i("<- " + text);
        try {
            JSONObject o = new JSONObject(text);
            String type = o.optString("type");

            if ("tts".equals(type)) {
                String state = o.optString("state");
                if ("start".equals(state)) {
                    main.post(new Runnable() {
                        public void run() {
                            main.removeCallbacks(idleCloseTask);
                            listener.onTtsStart();
                        }
                    });
                } else if ("stop".equals(state) || "abort".equals(state)) {
                    main.post(new Runnable() {
                        public void run() {
                            listener.onTtsStop();
                            markIdle();
                        }
                    });
                }
            } else if ("stt".equals(type)) {
                final String t = o.optString("text");
                main.post(new Runnable() {
                    public void run() {
                        listener.onStt(t);
                        // v1.3.330：云端不一定会把「退下」映射到 MCP 工具，
                        // 本地对高置信度短语兜一道，保证真的退出 LISTENING。
                        if (isQuitCommand(t)) {
                            requestQuit("stt:" + t);
                        }
                    }
                });
            } else if ("hello".equals(type)) {
                String sid = o.optString("session_id");
                helloDone = true;
                Log.i(TAG, "handshake done, session_id=" + sid);
                main.post(new Runnable() {
                    public void run() {
                        listener.onHandshakeDone();
                    }
                });
            } else if ("mcp".equals(type)) {
                JSONObject payload = o.optJSONObject("payload");
                if (payload != null && payload.optInt("id", -1) >= 0) {
                    int id = payload.optInt("id");
                    String method = payload.optString("method");
                    if ("initialize".equals(method)) {
                        sendMcpInitializeResult(id);
                    } else if ("tools/list".equals(method)) {
                        sendMcpToolsResult(id);
                    } else if ("tools/call".equals(method)) {
                        handleMcpToolCall(payload);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "handleText parse error", t);
        }
    }

    /**
     * v1.3.330：「退下 / 安静」⇒ 复用「60s 自动断开」那条收尾路径。
     *
     * <p>60s 到点做的是 {@code ws.close("no-tts-timeout")} → onClosed →
     * {@code SessionController.enterIdle()}（撤掉所有计时器、清 pending 上行、
     * 灭拾音灯、setState(IDLE)）。这里只是把「等 60 秒」换成「立刻」，
     * 不另造一套退出逻辑，收尾行为与自动断开逐字一致。</p>
     *
     * <p>延迟 600ms 是为了让 MCP result / 在途 STT 正常发完再关连接；
     * {@code quitPending} 保证 MCP 工具与 STT 兜底同时命中时只收一次。</p>
     */
    private String requestQuit(String why) {
        if (quitPending) {
            return "OK 已在退下（重复触发已忽略）";
        }
        quitPending = true;
        // 不额外屏蔽 KWS：设备本来就是「只有命中唤醒词才激活」，
        // 回到 IDLE 后麦克风只喂 KWS、不再上行；强行静默反而会让用户
        // 退下后马上喊「小智小智」叫不醒。已有的 WAKEUP_DEBOUNCE_MS 挡重复命中即可。
        String reason = why == null ? "" : why;
        if (reason.length() > 24) {
            reason = reason.substring(0, 24);   // WebSocket close reason 上限 123 字节
        }
        final String safeReason = reason;
        Log.i(TAG, "quit requested (" + safeReason + ") -> closing session in 600ms");
        SessionLog.i("quit requested (" + safeReason + ") -> reuse 60s auto-disconnect path");
        main.postDelayed(new Runnable() {
            public void run() {
                quitPending = false;
                close("user-quit:" + safeReason);
            }
        }, 600L);
        return "OK 已退出聆听状态，等待下次唤醒";
    }

    /**
     * 本地 STT 兜底：只认高置信度的「退下 / 下线 / 告别」类短句。
     * 云端未必会把这类命令映射到 MCP 工具，所以本地再兜一道；
     * 但必须挡住「安静一点的歌」「把音量关小」「网络怎么下线了」这类
     * 其实在点歌/调音量/问问题的句子。
     *
     * <p>v1.3.331 口令扩充（用户原话：「现在这些还是难触发」）：
     * 「你先退下吧」「你可以下线了」「你下线吧」「再见小智」「你下班吧」「不用你了」。
     * 原词表只有「退下 / 安静 / 休息」这一族，「下线 / 下班 / 再见 / 不用你」全都不认；
     * 且 12 字上限会把 ASR 常带出来的「小智小智，……」前缀挤掉。这里补齐词表、
     * 上限放宽到 16 字，同时给疑问句/内容句加一层 blockers，避免放宽后长句误伤。</p>
     */
    private static boolean isQuitCommand(String raw) {
        if (raw == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        String t = sb.toString().toLowerCase();
        if (t.length() == 0 || t.length() > 16) {
            return false;
        }
        String[] blockers = {"音量", "声音大", "声音小", "歌", "音乐", "播放", "灯",
                "闹钟", "天气", "新闻", "收藏", "定时", "几点", "下一首", "打开", "关闭",
                // v1.3.331：长度放宽后，疑问句 / 内容句先挡掉，别让长句蹭到关键词
                "怎么", "什么", "为什么", "多少", "几首", "帮我", "请问", "是不是",
                "能不能", "可以吗", "讲个", "说个", "查一下", "设置", "翻译", "网络",
                // 「不用你提醒」「你下班了吗」这类回答/疑问也要挡掉
                "提醒", "不用你说", "吗", "什么呢"};
        for (int i = 0; i < blockers.length; i++) {
            if (t.contains(blockers[i])) {
                return false;
            }
        }
        String[] words = {"退下", "安静", "闭嘴", "别说了", "不要说了", "别说话", "不要说话",
                "住口", "别吵", "休息吧", "你休息", "先休息", "休息一下", "别打扰",
                "不要打扰", "停止对话", "结束对话", "结束会话", "别理我", "不用理我",
                // v1.3.331 新增：下线 / 下班 / 告别 / 不用你了，以及口语尾巴
                "下线", "下班", "再见", "拜拜", "回见", "不用你", "下去吧",
                "退下吧", "下线吧", "下班吧", "先这样", "就这样"};
        for (int i = 0; i < words.length; i++) {
            if (t.contains(words[i])) {
                return true;
            }
        }
        return false;
    }

    private OkHttpClient getClient() {
        if (client == null) {
            OkHttpClient.Builder b = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)   // 0 = 不设读超时（长连）
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true);
            try {
                // 信任所有证书：音箱没校时，证书校验必挂（时间不对 ⇒ 证书「未生效」）
                X509TrustManager tm = new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
                b.sslSocketFactory(ctx.getSocketFactory(), tm);
                b.hostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String host, SSLSession session) {
                        return true;
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "trust-all ssl setup failed", t);
            }
            client = b.build();
        }
        return client;
    }
}
