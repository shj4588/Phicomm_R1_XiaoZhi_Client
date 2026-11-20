package cn.workbuddy.r1web;

import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 蓝牙管理（设置页「蓝牙」一栏的数据源 + 动作）。
 *
 * <p>★ 为什么必须自己写：小飞那套是给魔改 player 的 8080 WS 发
 * {@code {type:"BT_enable"}} / {@code BT_disable} / {@code BT_StartScan} /
 * {@code BT_Device} / {@code BT_createBond} / {@code BT_removeBond} /
 * {@code BT_setScanMode}，服务端是它自己魔改出来的；我们注入的是<b>原厂</b>
 * EchoService，没有这套 WS 命令 ⇒ 用 Android 标准 API 直接做。</p>
 *
 * <p>player 原厂有 BLUETOOTH / BLUETOOTH_ADMIN / BLUETOOTH_PRIVILEGED 三个权限，
 * 所以 enable/disable/startDiscovery 都能直接用；只有「配对后主动连 A2DP」需要
 * 隐藏类 {@link BluetoothA2dpSink}（原厂 BluetoothPlayer 也用它 —— 音箱是 A2DP
 * sink 角色，连接通常由手机端发起，我们能做的是 connect/disconnect）。</p>
 *
 * <p>★★ 本 ROM 的 scan mode 常量口径（pl-1.6.3，与原厂编译字面量互证 20/21/23，
 * 别按教材里的 21/23/39 记 —— 那套在这台机器上会让「取消可发现」变成
 * SCAN_MODE_NONE，已配对手机直接失联）：</p>
 * <pre>
 *   20 = SCAN_MODE_NONE                    不可发现、也不可连
 *   21 = SCAN_MODE_CONNECTABLE             可连、不可发现（已配对手机连这个档）
 *   23 = SCAN_MODE_CONNECTABLE_DISCOVERABLE  可连 + 可被搜到
 * </pre></p>
 *
 * <p>★★ pl-1.6.4：「可被搜到」改成<b>临时开放窗口</b>，不再长开、也不再记偏好
 * （pl-1.6.3 的账本 files/r1_bt_disc.txt 已废弃）——点开只给 60 秒：</p>
 * <ul>
 *   <li>窗口内<b>有设备连上（ACL_CONNECTED）就立刻收回</b>（21）——用户原话
 *       「连上就自动关闭」，开放只为了让新设备连上这一次；</li>
 *   <li>60 秒没人连 → 到点自动收回（21）——「没有连上就等一分钟」；</li>
 *   <li>收回后停在 21（SCAN_MODE_CONNECTABLE）：已配对手机随时能连，
 *       陌生人搜不到，防误连。</li>
 * </ul>
 * <p>蓝牙（重）起来后默认落 21（pl-1.6.3 之前会恢复 23，现在不给了）；
 * 若此刻窗口还没到期（进程存活期间）才给 23 继续等倒计时。</p>
 */
public final class Bt {

    /** BluetoothProfile.A2DP_SINK 的值（隐藏常量，android.jar 里没有 ⇒ 写在本地）。 */
    private static final int A2DP_SINK = 11;

    /** 本 ROM 的 scan mode 常量（见类注释：20/21/23，不是教材里的 21/23/39）。 */
    private static final int SM_NONE = 20;
    private static final int SM_CONN = 21;
    private static final int SM_CD = 23;

    /** 延迟补认一次的时间（BT 栈刚起来时 setScanMode 会静默失败）。 */
    private static final long DISC_RETRY_MS = 2500L;
    private static boolean discRetryDone = false;

    /**
     * ★ pl-1.6.4：可发现开放窗口时长（毫秒）。点开「可被搜到」只给这么久，
     * 期间没设备连上就自动收回。
     */
    private static final long DISC_WINDOW_MS = 60_000L;

    /** 窗口轮询步长（到点收回 + 页面 discLeft 倒计时靠它推进）。 */
    private static final long DISC_TICK_MS = 500L;

    /**
     * 开放窗口截止时刻（{@link System#currentTimeMillis()} 口径）；0 = 无窗口。
     *
     * <p>★ 为什么用 currentTimeMillis 而不是 elapsedRealtime：窗口内要能跨
     * {@code reopenEngine} 等路径判断「窗口还开着吗」，墙钟足够，重启进程后
     * 自然归 0（音箱重启后不再临时开放，符合防误连）。</p>
     */
    private static volatile long discEnd = 0;

    /** 窗口倒计时线程（单例，窗口关了自然退出）。 */
    private static Thread discTimer;

    /* 扫描到的（未配对）设备，addr → 设备；扫到哪存到哪，不清空直到下次扫描 */
    private static final Map<String, Dev> found = new LinkedHashMap<String, Dev>();

    /** 当前生效的 A2DP sink（由 {@link #ensureSink()} 定，别直接读它）。 */
    private static BluetoothA2dpSink sink;
    /** 自己向系统要来的那份（本 ROM 实测拿不到，见 {@link #ensureProxy}）。 */
    private static volatile BluetoothA2dpSink ownSink;
    /**
     * ★ pl-1.6.21：上次成功拿到 sink 的时刻。
     *
     * <p>借来的是<b>别人的对象引用</b>，原厂 {@code BluetoothPlayer} 一旦被重建
     * 就成了僵尸引用 —— {@code connect()} 不报错但也不生效，最难查的那种。
     * 所以带一个 TTL，过期重借。</p>
     */
    private static volatile long sinkAt;
    private static volatile boolean scanning;
    private static boolean reged;
    /** ★ pl-1.6.21：有没有向系统要过 proxy（掉了会置回 false，允许再要）。 */
    private static volatile boolean proxyAsked;

    /* ---- pl-1.6.150：蓝牙氛围灯兜底链（原厂 lightId 255）----
       原厂 EchoService 的 EasyPlayer 在 enterBusy 时发 255、离开时关 255；
       conf 里 255 = SYNC open color CYAN（淡青蓝），251 是 BLUE 1414ff（深蓝）。
       11-08 14:47 实测：A2DP 已 Connected 且在放歌，但那次没 enterBusy，
       原厂全程没发 requestLightEffect ⇒ 底部环全黑。故按 A2DP 连接态兜底。
       灯效本身仍由原厂灯效服务跑，不新做灯效、不接管灯、页面无设置项。 */
    /** 原厂蓝牙氛围灯的效果 id（255 = CYAN 淡青蓝常亮）。 */
    private static final int BT_LIGHT_ID = 255;
    /** 轮询间隔：广播只做即时触发，最终以轮询为准（ACL 先连、profile 后连）。 */
    private static final long BT_LIGHT_TICK_MS = 1200L;
    private static volatile boolean sBtLightInit = false;
    /** 上次判定的 A2DP 连接态。 */
    private static volatile boolean sBtLightLast = false;
    /** 最近一次动作的结果，排障用。 */
    private static volatile String sBtLightMsg = "未初始化";
    /** 已发出的报文条数（用来区分「首次同步」与「状态翻转」）。 */
    private static volatile long sBtLightSends = 0;

    /** {@link #sinkAt} 的缓存有效期；再长就有引用失效风险，再短则每次都反射。 */
    private static final long SINK_TTL_MS = 10_000L;

    static final class Dev {
        String name;
        String addr;
        int rssi;
        int bond;               // 10=未配对 11=配对中 12=已配对
        boolean connected;      // A2DP 已连

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name == null ? addr : name);
                o.put("addr", addr);
                o.put("rssi", rssi);
                o.put("bond", bond);
                o.put("connected", connected);
            } catch (Throwable t) {
                // ignore
            }
            return o;
        }
    }

    /* ---------------- 初始化 ---------------- */

    /** 注册广播 + 取 A2DP sink 代理。attach 后调一次即可，重复调用无副作用。 */
    public static synchronized void init(Context c) {
        if (c == null) {
            return;
        }
        try {
            if (!reged) {
                IntentFilter f = new IntentFilter();
                f.addAction(BluetoothDevice.ACTION_FOUND);
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
                c.getApplicationContext().registerReceiver(new Receiver(), f);
                reged = true;
                Boot.log("bt: receiver 已注册");
            }
        } catch (Throwable t) {
            Boot.log("bt init: " + t);
        }
        /* ★★ pl-1.6.21：拿 proxy 这件事单独走 ensureProxy()，**不再被 reged 守卫挡住**。
           以前「已注册过 → 直接 return」一杆子打出去，而首次 attach 时蓝牙通常还没开
           （那一趟 getProfileProxy 是白跑的），等 STATE_ON 再进来又被第一行弹回
           ⇒ 自己这条代理路径<b>永远没被真正问过</b>，只能靠借原厂的。 */
        ensureProxy(c);
        /* ★ pl-1.6.4：attach 时把 scan mode 落到「可连不可发现」（21）——
           蓝牙刚起来时系统给的是 20=NONE，那一档已配对手机都连不上，
           必须压成 21；窗口还开着（进程存活期间）的才给 23 继续等倒计时。
           BT 栈偶尔比 service 起得晚，补一个延迟再认一次。 */
        applyDiscPref();
        if (!discRetryDone) {
            discRetryDone = true;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(DISC_RETRY_MS);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    applyDiscPref();
                }
            }, "r1web-bt-disc").start();
        }
    }

    /**
     * ★ pl-1.6.21：向系统要 A2DP sink 代理（幂等，可从 init / STATE_ON 反复调用）。
     *
     * <p>单独拆出来的唯一理由：<b>不受 {@code reged} 守卫</b>。见 {@link #init(Context)}
     * 里的说明 —— 蓝牙往往是后来才开的，那时候必须还能再问一次。</p>
     *
     * <p>11 = {@code BluetoothProfile.A2DP_SINK}，隐藏常量（android.jar 里没有，
     * javac 编不过，只能写字面量；原厂 BluetoothPlayer 里也是同一个值）。
     * 音箱是 A2DP <b>sink</b> 端。</p>
     */
    private static void ensureProxy(Context c) {
        if (proxyAsked || ownSink != null) {
            return;                             // 问过了 / 已经拿到了，不重复要
        }
        try {
            final Context app = c.getApplicationContext();
            BluetoothAdapter a = ad();
            if (a == null || !a.isEnabled()) {
                return;                         // 蓝牙没开，问了也白问；STATE_ON 会再来
            }
            boolean r = a.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        /* ★ 实测：这台机器 profile=11 回来的是 BluetoothAvrcpController。
                           ★★ **千万别放宽类型去收它** —— AvrcpController 同样有
                           connect(BluetoothDevice) / disconnect()，收下之后「连接」按钮
                           连的是 AVRCP 而不是 A2DP：手机显示连上了，声音不过来。
                           宁可收不到，走 origSink() 借原厂那支真身。 */
                        if (proxy instanceof BluetoothA2dpSink) {
                            ownSink = (BluetoothA2dpSink) proxy;
                            sink = ownSink;
                            sinkAt = System.currentTimeMillis();
                            Boot.log("bt: a2dp sink 就绪 profile=" + profile + "（自己的代理）");
                        } else {
                            Boot.log("bt: profile " + profile + " 回来的是 "
                                    + (proxy == null ? "null" : proxy.getClass().getSimpleName())
                                    + "，不是 A2DP sink ⇒ 不收（继续借原厂）");
                        }
                    } catch (Throwable t) {
                        // 回调查不到 / cast 失败都要留下痕迹，否则就只能看到 sink=false
                        Boot.log("bt: sink 回调异常 " + t);
                    }
                }

                public void onServiceDisconnected(int profile) {
                    if (profile == A2DP_SINK) {
                        ownSink = null;
                        if (sink != ownSink) {
                            sink = null;        // 丢的是自己的那支；借来的下次 ensureSink 重判
                        }
                        proxyAsked = false;     // ★ 掉了要允许再要一次
                    }
                }
            }, A2DP_SINK);
            proxyAsked = true;
            Boot.log("bt: getProfileProxy(A2DP_SINK=11)=" + r);
        } catch (Throwable t) {
            Boot.log("bt proxy: " + t);
        }
    }

    /**
     * ★ pl-1.6.21：统一决定「这一刻用哪个 sink」，所有用 sink 的地方都必须先调它。
     *
     * <p>以前只有 {@link #state()} 会去借 ⇒ 用户<b>刚打开蓝牙就去点「连接」</b>、
     * 页面还没刷过一轮时，静态字段还是 null ⇒ 直接回「A2DP 服务还没就绪」的
     * <b>假阴性</b>（其实借一下就有了）。现在 connect/disconnect/state 三条路全先兜。</p>
     *
     * <p>优先级：自己的代理 &gt; 借原厂 {@code BluetoothPlayer.mA2dpSink}。
     * 借来的是别人的引用，带 {@link #SINK_TTL_MS} 缓存；长时间借不到就丢掉旧引用
     * （原厂对象多半已被重建，继续用只会得到一个「不报错也不生效」的僵尸）。</p>
     */
    private static synchronized BluetoothA2dpSink ensureSink() {
        if (ownSink != null) {
            sink = ownSink;
            return sink;
        }
        long now = System.currentTimeMillis();
        if (sink != null && now - sinkAt < SINK_TTL_MS) {
            return sink;                        // 缓存期内直接用，别每次都反射
        }
        boolean had = sink != null;
        BluetoothA2dpSink s = origSink();
        if (s != null) {
            sink = s;
            sinkAt = now;
            if (!had) {
                Boot.log("bt: sink 就位（借原厂 mA2dpSink）");
            }
        } else if (had && now - sinkAt > 60_000L) {
            Boot.log("bt: 原厂 sink 借不到了，丢弃可能失效的旧引用");
            sink = null;
        }
        return sink;
    }

    static final class Receiver extends BroadcastReceiver {
        public void onReceive(Context c, Intent i) {
            try {
                String act = i.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(act)) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) {
                        return;
                    }
                    Dev v = new Dev();
                    v.name = nameOf(d);
                    v.addr = d.getAddress();
                    v.bond = d.getBondState();
                    short r = 0;
                    try {
                        r = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 0);
                    } catch (Throwable t) {
                        // ignore
                    }
                    v.rssi = r;
                    synchronized (Bt.class) {
                        found.put(v.addr, v);
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(act)) {
                    scanning = true;
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(act)) {
                    scanning = false;
                    Boot.log("bt: 扫描结束，发现 " + found.size() + " 台");
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(act)) {
                    int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    Boot.log("bt: 适配器状态 " + st);
                    if (st == BluetoothAdapter.STATE_ON) {
                        /* ★ pl-1.6.21：适配器整个重启过，旧 sink 引用（自己的 + 借来的）
                           都可能失效 ⇒ 一律作废，由 ensureProxy / ensureSink 重新弄一个。 */
                        ownSink = null;
                        sink = null;
                        sinkAt = 0;
                        proxyAsked = false;
                        init(Boot.app);          // 蓝牙开了才拿得到 profile proxy
                        // ★ pl-1.6.4：开适配器后落到 21（可连不可发现）；
                        // 若此刻开放窗口还没到期才给 23 继续等
                        applyDiscPref();
                    } else if (st == BluetoothAdapter.STATE_OFF) {
                        /* pl-1.6.150：整个蓝牙关掉 ⇒ A2DP 必然断开，把 255 收掉。 */
                        syncBtLight();
                    }
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(act)) {
                    /* ★ pl-1.6.4：「连上就自动关闭」—— 窗口开放期间只要有设备
                       连上（不论已配对重连还是新设备首次连），立刻收回 23。
                       已配对手机平时在 21 档连不影响（窗口不在开）。 */
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    String ad = d == null ? "?" : d.getAddress();
                    Boot.log("bt: 有设备连上 " + ad);
                    if (discEnd != 0) {
                        closeDisc("设备已连上（" + ad + "）");
                    }
                    /* pl-1.6.149：立刻重判一次；ACL 先连、profile 后连也没关系，
                       1.2s 的轮询会补上那一次 show 255。 */
                    syncBtLight();
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(act)) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    Boot.log("bt: 设备断开 " + (d == null ? "?" : d.getAddress()));
                    syncBtLight();
                }
            } catch (Throwable t) {
                Boot.log("bt recv: " + t);
            }
        }
    }

    /* ---------------- pl-1.6.150：蓝牙氛围灯兜底（原厂 255） ---------------- */

    /**
     * A2DP sink 此刻有没有设备连着。
     *
     * <p>两条判据并用：① 适配器的 {@code getProfileConnectionState(11)}（隐藏 API，
     * 反射拿）；② 借来的 {@link BluetoothA2dpSink} 代理的已连设备表。任一条说连着
     * 就算连着 —— 原厂代理偶尔借不到，单靠一条会漏判成「断开」。</p>
     */
    public static synchronized boolean a2dpConnected() {
        try {
            BluetoothAdapter a = ad();
            if (a != null && a.isEnabled()) {
                Method m = BluetoothAdapter.class.getMethod(
                        "getProfileConnectionState", int.class);
                Object r = m.invoke(a, Integer.valueOf(A2DP_SINK));
                // BluetoothProfile.STATE_CONNECTED = 2
                if (r instanceof Integer && ((Integer) r).intValue() == 2) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // 隐藏 API 拿不到就走下面那条
        }
        try {
            BluetoothA2dpSink sk = ensureSink();
            if (sk != null) {
                List<BluetoothDevice> cs = sk.getConnectedDevices();
                if (cs != null && !cs.isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return false;
    }

    /** 由 {@link Boot#attach} 调一次，启动轮询（幂等）。广播只做即时触发。 */
    public static synchronized void initBtLight() {
        if (sBtLightInit) {
            return;
        }
        sBtLightInit = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(BT_LIGHT_TICK_MS);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    try {
                        syncBtLight();
                    } catch (Throwable t2) {
                        // 拿不到蓝牙状态也别把线程带死
                    }
                }
            }
        }, "r1web-btlight");
        t.setDaemon(true);
        t.start();
        Boot.log("bt: 蓝牙氛围灯兜底链已启动（原厂 255）");
    }

    /** 立即重判一次；真正的 HTTP 在后台线程发，不占 onReceive 的主线程。 */
    public static void syncBtLight() {
        boolean conn;
        try {
            conn = a2dpConnected();
        } catch (Throwable t) {
            return;
        }
        applyBtLight(conn);
    }

    private static synchronized void applyBtLight(final boolean conn) {
        if (sBtLightSends > 0 && conn == sBtLightLast) {
            return;                         // 状态没变，别反复打扰灯效服务
        }
        if (sBtLightSends == 0 && !conn) {
            /* 开机时蓝牙没连：不发 close，免得无谓清一次别人的灯。 */
            return;
        }
        sBtLightLast = conn;
        sBtLightSends++;
        new Thread(new Runnable() {
            public void run() {
                /* 复用 device 现成的灯效调试口：原厂 R1Lights 里 ON=0 / OFF=1，
                   send(255,0) = show 255（A 组淡青蓝常亮），send(255,1) = close 255。 */
                String body = "op=light&v=raw:" + BT_LIGHT_ID + ":"
                        + (conn ? "0" : "1");
                String r = Proxy.post("/api/act", body, 4000);
                sBtLightMsg = (conn ? "show 255（淡青蓝常亮）" : "close 255")
                        + " → " + (r == null ? "8088 无响应" : "ok");
                Boot.log("bt: " + sBtLightMsg);
            }
        }, "r1web-btlight-send").start();
    }

    /** 诊断串（{@code /api/status} 的 btLight / {@code op=btlight&v=probe}）。 */
    public static String btLightState() {
        return "connected=" + sBtLightLast
                + " sends=" + sBtLightSends
                + " last=" + sBtLightMsg
                + " | 连上=show255 断开=close255";
    }

    /* ---------------- 状态 ---------------- */

    /** 没有硬件适配器（或取不到）返回 null，页面据此显示「本机不支持蓝牙」。 */
    private static BluetoothAdapter ad() {
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 蓝牙状态快照。
     *
     * <p>{@code bonded}=已配对列表（含 A2DP 是否已连），{@code found}=上次扫描
     * 发现的（已配对的会被过滤掉，免得重复出现两遍）。</p>
     */
    public static synchronized JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            BluetoothAdapter a = ad();
            if (a == null) {
                o.put("has", false);
                return o;
            }
            o.put("has", true);
            o.put("on", a.isEnabled());
            o.put("name", a.getName());
            o.put("addr", a.getAddress());
            o.put("discovering", a.isDiscovering() || scanning);
            /* 可被搜到：读适配器真实 scan mode（23=SCAN_MODE_CONNECTABLE_DISCOVERABLE）。
               页面「可被搜到」按钮据此翻转，同样是实际状态不是记录。 */
            try {
                int sm = a.getScanMode();
                o.put("disc", sm == SM_CD);
                // 原值也上报（20/21/23），排「连不上」时一眼看出是不是被打回 20 了
                o.put("scanMode", sm);
            } catch (Throwable t) {
                o.put("disc", false);
            }
            /* ★ pl-1.6.4：开放窗口剩余秒数（0 = 没开窗口）。
               页面按钮开着时显示倒计时，到点/连上会自动翻回「关」。 */
            long left = discEnd - System.currentTimeMillis();
            o.put("discLeft", left > 0 ? (left + 999) / 1000 : 0);
            BluetoothA2dpSink sk = ensureSink();
            o.put("sink", sk != null);

            List<String> connected = new ArrayList<String>();
            if (sk != null) {
                List<BluetoothDevice> cs = sk.getConnectedDevices();
                if (cs != null) {
                    for (BluetoothDevice d : cs) {
                        connected.add(d.getAddress());
                    }
                }
            }

            JSONArray bond = new JSONArray();
            /* ★ pl-1.6.21：这一步顺便攒「此刻真的已配对」的地址集合，
               下面过滤 found 要用它 —— 见那边注释。 */
            java.util.Set<String> bondedAddrs = new java.util.HashSet<String>();
            Set<BluetoothDevice> bs = a.getBondedDevices();
            if (bs != null) {
                for (BluetoothDevice d : bs) {
                    if (d == null) {
                        continue;
                    }
                    bondedAddrs.add(d.getAddress());
                    Dev v = new Dev();
                    v.name = nameOf(d);
                    v.addr = d.getAddress();
                    v.bond = d.getBondState();
                    v.connected = connected.indexOf(v.addr) >= 0;
                    bond.put(v.json());
                }
            }
            o.put("bonded", bond);

            JSONArray fd = new JSONArray();
            for (Dev v : found.values()) {
                /* ★★ pl-1.6.21：判「是不是已配对」必须用<b>此刻的 bonded 集合</b>，
                   不能用 {@code v.bond} —— 那是**扫描那一刻拍下的快照**。
                   踩雷路径：扫到一台还没配对的设备 → 当场配对 → 没再重扫 →
                   它在 found 里的快照 bond 还是 10 ⇒ 过滤不掉 ⇒
                   「扫描到的」和「已配对」两栏<b>各出现一次同一台设备</b>。
                   顺手把 v.bond 刷成此刻真值，页面上的配对状态也跟着自愈。 */
                if (bondedAddrs.contains(v.addr)) {
                    continue;                     // 已配对的在上面那栏
                }
                BluetoothDevice cur = dev(v.addr);
                if (cur != null) {
                    v.bond = cur.getBondState();
                }
                fd.put(v.json());
            }
            o.put("found", fd);
            o.put("foundCount", found.size());
        } catch (Throwable t) {
            Boot.log("bt state: " + t);
        }
        return o;
    }

    /**
     * 借原厂 BluetoothPlayer 手上的 A2DP sink 代理（反射两个私有字段）。
     *
     * <p>我们自己的 {@code getProfileProxy(11)} 实测回调不来（BluetoothAdapter 对
     * sink profile 的支持看版本），而原厂 BluetoothPlayer 在构造时就调过一次，
     * 它那个 {@code mA2dpSink} 字段大概率已经有值 —— 同一个进程里，直接用它的。</p>
     *
     * <p>★ pl-1.6.21：<b>静默</b> —— 以前每次调用成功/失败都 Boot.log，而 state() 是
     * 被页面轮询的，日志会被同一句话刷满。改由 {@link #ensureSink()} 只在
     * <b>状态发生变化</b>时打一条。</p>
     */
    private static BluetoothA2dpSink origSink() {
        try {
            Object bp = PCtl.field(PCtl.ep(), "mBluetoothPlayer");
            if (bp == null) {
                return null;
            }
            Object s = PCtl.field(bp, "mA2dpSink");
            if (s == null) {
                return null;
            }
            return (BluetoothA2dpSink) s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String nameOf(BluetoothDevice d) {
        try {
            String n = d.getName();
            return (n == null || n.length() == 0) ? d.getAddress() : n;
        } catch (Throwable t) {
            return null;
        }
    }

    private static BluetoothDevice dev(String addr) {
        BluetoothAdapter a = ad();
        if (a == null || addr == null) {
            return null;
        }
        try {
            return a.getRemoteDevice(addr);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 动作 ---------------- */

    /**
     * 开 / 关蓝牙。{@code BluetoothAdapter.enable()} 是<b>异步</b>的（返回后
     * 还要几秒才真的 STATE_ON），所以这里只报告「命令已下发」，状态由页面轮询。
     */
    public static String setOn(boolean on) {
        BluetoothAdapter a = ad();
        if (a == null) {
            return "这台机器没有蓝牙适配器";
        }
        try {
            boolean r = on ? a.enable() : a.disable();
            if (!r) {
                return (on ? "打开" : "关闭") + "失败（adapter 返回 false）";
            }
            // ★ pl-1.6.2：手动开/关都记入手动意图账本（和 DLNA/AirPlay 同一本），
            // 之后被系统停掉时 autoReopen 按它决定补不补
            Svc.saveManual("bt", on);
            return (on ? "蓝牙正在打开" : "蓝牙正在关闭") + "…（异步，过 2~3 秒再看状态）";
        } catch (Throwable t) {
            return (on ? "打开" : "关闭") + "蓝牙失败: " + t;
        }
    }

    /** 蓝牙适配器当前是否开着。 */
    public static boolean enabled() {
        try {
            BluetoothAdapter a = ad();
            return a != null && a.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ pl-1.6.2：原厂蓝牙引擎被停掉后重新拉起。
     *
     * <p>{@code ensureBusy → exitWaitingExternalConnect →
     * shutdownAllExternalConnectPlayer} 里三样一起停，其中蓝牙是
     * {@code BluetoothPlayer.stopServer() → stop()}（断当前播放）。
     * 补开 = 反射 {@code EasyPlayer.mBluetoothPlayer.startServer()}
     * （原厂方法：按 mConnectState 做恢复，含可被搜到 setScanMode(23)）。</p>
     */
    public static void reopenEngine() {
        try {
            BluetoothAdapter a = ad();
            if (a == null) {
                return;
            }
            if (!a.isEnabled()) {
                Boot.log("bt: adapter 被停，重新 enable");
                a.enable();
                return;                 // enable 是异步的，下次补开再拉引擎
            }
            Object ep = PCtl.ep();
            if (ep == null) {
                return;
            }
            java.lang.reflect.Field f = ep.getClass().getDeclaredField("mBluetoothPlayer");
            f.setAccessible(true);
            Object bp = f.get(ep);
            if (bp == null) {
                return;
            }
            bp.getClass().getMethod("startServer").invoke(bp);
            Boot.log("bt: 原厂蓝牙引擎已重新拉起（startServer）");
            /* ★ pl-1.6.21：引擎重启 = mA2dpSink 有可能是<b>新建的对象</b>，
               手上的旧引用会变成僵尸（connect 不报错也不生效）⇒ 作废缓存，
               下次 ensureSink() 重新去借。 */
            sinkAt = 0;
            sink = null;
            /* ★ pl-1.6.4：startServer 在 mConnectState 0/3 时会把 scan mode 设成 23
               （长开可发现）——新模型下绝不长开，这里按 applyDiscPref 压回 21；
               除非用户此刻正好开着 60 秒窗口（discEnd 未到期），那才保留 23。 */
            applyDiscPref();
        } catch (Throwable t) {
            Boot.log("bt reopenEngine: " + t);
        }
    }

    /** 开始扫描（12 秒后系统自动结束）。开蓝牙是前提。 */
    public static String scan() {
        BluetoothAdapter a = ad();
        if (a == null) {
            return "没有蓝牙适配器";
        }
        if (!a.isEnabled()) {
            return "蓝牙没开，先打开再扫描";
        }
        try {
            synchronized (Bt.class) {
                found.clear();
            }
            if (a.isDiscovering()) {
                a.cancelDiscovery();
            }
            boolean r = a.startDiscovery();
            Boot.log("bt: startDiscovery=" + r);
            return r ? "开始扫描（约 12 秒）…" : "扫描启动失败（startDiscovery 返回 false）";
        } catch (Throwable t) {
            return "扫描失败: " + t;
        }
    }

    /** 配对（createBond 自 API19 起是公开方法）。 */
    public static String pair(String addr) {
        BluetoothDevice d = dev(addr);
        if (d == null) {
            return "地址不合法: " + addr;
        }
        try {
            if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
                return "已经配对了";
            }
            boolean r = d.createBond();
            return r ? "已发起配对（手机端可能要确认）…" : "配对失败（createBond 返回 false）";
        } catch (Throwable t) {
            return "配对失败: " + t;
        }
    }

    /**
     * 取消配对 —— {@code removeBond} 是<b>隐藏方法</b>，只能反射。
     * 反射不到就把话说清楚，不用别的野路子。
     */
    public static String unpair(String addr) {
        BluetoothDevice d = dev(addr);
        if (d == null) {
            return "地址不合法: " + addr;
        }
        try {
            Method m = d.getClass().getMethod("removeBond");
            Object r = m.invoke(d);
            return "取消配对：" + r + "（Boolean.TRUE 才算成功）";
        } catch (Throwable t) {
            return "取消配对失败: " + t;
        }
    }

    /** 主动连 A2DP（音箱是 sink 端；一般由手机发起，这里只是补一个入口）。 */
    /** 连接值：{@link #ensureSink()} 先兜一次，避免「还没刷过页面」时的假阴性。 */
    public static String connect(String addr) {
        BluetoothDevice d = dev(addr);
        if (d == null) {
            return "地址不合法: " + addr;
        }
        BluetoothA2dpSink sk = ensureSink();
        if (sk == null) {
            return "A2DP 服务还没就绪（蓝牙刚开？等几秒再来）";
        }
        try {
            return sk.connect(d) ? "正在连接…" : "连接失败（sink.connect 返回 false）";
        } catch (Throwable t) {
            return "连接失败: " + t;
        }
    }

    /** 断开 A2DP。 */
    public static String disconnect(String addr) {
        BluetoothDevice d = dev(addr);
        if (d == null) {
            return "地址不合法: " + addr;
        }
        BluetoothA2dpSink sk = ensureSink();
        if (sk == null) {
            return "A2DP 服务还没就绪";
        }
        try {
            return sk.disconnect(d) ? "已断开" : "断开失败（sink.disconnect 返回 false）";
        } catch (Throwable t) {
            return "断开失败: " + t;
        }
    }

    /**
     * ★ pl-1.6.4：可发现开关 —— {@code setScanMode} 是<b>隐藏方法</b>，反射调用。
     *
     * <p>★ 口径（本 ROM）—— 23 = SCAN_MODE_CONNECTABLE_DISCOVERABLE
     * （可连 + 可被搜到）；21 = SCAN_MODE_CONNECTABLE（可连、不可发现，
     * 已配对手机连这个档）。<b>别设 20</b>（SCAN_MODE_NONE）——
     * 原厂自己在手机连上时会强设 20（已被 patch_bt_scan.py 拔掉），
     * 那一档不可发现也不可连。</p>
     *
     * <p>★★ pl-1.6.4 起「开」不再是长开，而是<b>60 秒临时窗口</b>：
     * <ul>
     *   <li>开 → 设 23，起 60 秒倒计时；期间<b>有设备连上立即收回</b>；</li>
     *   <li>60 秒没人连 → 到点自动收回到 21；</li>
     *   <li>关（手动）→ 立即收回，取消倒计时。</li>
     * </ul>
     * 收回后停在 21：已配对手机随时能连，陌生人搜不到——防误连。</p>
     */
    public static String discoverable(boolean on) {
        BluetoothAdapter a = ad();
        if (a == null) {
            return "没有蓝牙适配器";
        }
        if (!a.isEnabled()) {
            return "蓝牙没开";
        }
        if (on) {
            try {
                Method m = a.getClass().getMethod("setScanMode", Integer.TYPE, Integer.TYPE);
                Object r = m.invoke(a, Integer.valueOf(SM_CD), Integer.valueOf(0));
                if (Boolean.FALSE.equals(r)) {
                    return "设置失败（setScanMode 返回 false，蓝牙可能没完全起来）";
                }
                discEnd = System.currentTimeMillis() + DISC_WINDOW_MS;
                startDiscTimer();
                return "已开放 60 秒：有设备连上会自动收回，没人连 1 分钟后自动收回";
            } catch (Throwable t) {
                return "设置失败: " + t;
            }
        }
        closeDisc("手动关闭");
        return "已收回：只有已配对的手机能连（陌生人搜不到）";
    }

    /**
     * ★ pl-1.6.4：收回可发现窗口 —— 设回 21（可连、不可发现），停倒计时。
     *
     * @param why 收回原因（进日志，排「怎么自己关了」用）
     */
    private static synchronized void closeDisc(String why) {
        discEnd = 0;
        try {
            BluetoothAdapter a = ad();
            if (a != null && a.isEnabled()) {
                Method m = a.getClass().getMethod("setScanMode", Integer.TYPE, Integer.TYPE);
                Object r = m.invoke(a, Integer.valueOf(SM_CONN), Integer.valueOf(0));
                Boot.log("bt: 可发现窗口已收回（" + why + "）→ scanMode=21 → " + r);
            } else {
                Boot.log("bt: 可发现窗口已收回（" + why + "），蓝牙没开不设 scan mode");
            }
        } catch (Throwable t) {
            Boot.log("bt: closeDisc " + t);
        }
    }

    /** 窗口倒计时线程（单例；discEnd 到点或 ACL_CONNECTED 提前置 0 即退出）。 */
    private static synchronized void startDiscTimer() {
        if (discTimer != null && discTimer.isAlive()) {
            return;                     // 已有线程在跑，discEnd 是共享的，继续倒计时
        }
        discTimer = new Thread(new Runnable() {
            public void run() {
                try {
                    while (true) {
                        Thread.sleep(DISC_TICK_MS);
                        long end = discEnd;
                        if (end == 0) {
                            return;     // 已收回（手动/连上）
                        }
                        if (System.currentTimeMillis() >= end) {
                            closeDisc("60 秒无设备连上，到点收回");
                            return;
                        }
                    }
                } catch (InterruptedException ie) {
                    // 进程要死了，直接退
                }
            }
        }, "r1web-bt-discwin");
        discTimer.start();
    }

    /**
     * ★ pl-1.6.4：把 scan mode 落到正确档位（原 pl-1.6.3 的「按账本恢复偏好」
     * 已废弃——可发现不再长开也就不需要记住）：
     *
     * <ul>
     *   <li>默认 {@link #SM_CONN}（21 = 可连、不可发现）：蓝牙（重）起来后
     *       系统初始是 {@link #SM_NONE}（20，已配对手机都连不上），必须压成 21；</li>
     *   <li>进程存活期间开放窗口还没到期（{@link #discEnd} 在未来）才给 23 ——
     *       比如 reopenEngine 把原厂引擎拉回来时不能把窗口冲掉。</li>
     * </ul>
     *
     * <p>触发点：attach、适配器 STATE_ON、{@link #reopenEngine()} 之后。
     * 蓝牙没开 / 反射失败都静默跳过（STATE_ON 还会再兜一次）。</p>
     */
    public static void applyDiscPref() {
        try {
            BluetoothAdapter a = ad();
            if (a == null || !a.isEnabled()) {
                return;
            }
            boolean win = discEnd - System.currentTimeMillis() > 0;
            int mode = win ? SM_CD : SM_CONN;
            Method m = a.getClass().getMethod("setScanMode", Integer.TYPE, Integer.TYPE);
            Object r = m.invoke(a, Integer.valueOf(mode), Integer.valueOf(0));
            Boot.log("bt: scan mode 落档=" + mode + (win ? "（开放窗口进行中）" : "（默认可连不可发现）")
                    + " → " + r);
        } catch (Throwable t) {
            Boot.log("bt: applyDiscPref " + t);
        }
    }

    private Bt() {
    }
}
