package cn.workbuddy.r1web;

import android.content.Context;
import android.util.Log;

import com.phicomm.speaker.player.model.PlayRequestInfo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 注入层的总入口（player 进程）。
 *
 * <pre>
 *   Common.onCreate()                  → attach(Context)      // Application 起来了
 *   EasyPlayer.play()                  → onPlay(pri)          // 队列真相
 *   EasyPlayer.playIndex()             → onIndex(i)           // 只换位置
 *   EasyPlayer.shutdownAllExternalConnectPlayer()
 *                                      → onExternalShutdown() // 外部引擎被停
 * </pre>
 *
 * <p>所以 attach 只是「登记」，真正 bind + 起 web 放到 3.5s 后的后台线程。</p>
 *
 * <p>★ 2026-10-28：本文件曾被误删，按 classes_pv81 的 Boot.class 反编译还原，
 * 行为与丢失前逐条一致（后续改动：VER、restoreOnBoot 调用）。</p>
 */
public final class Boot {
    public static final String VER = "pl-1.6.163";    // ★ 播放页「转码」pill —— 服务端降档转码听得见也看得见
    public static final String TAG = "R1WEB";

    public static Context app;
    public static String proc = "?";
    public static long bootTs = System.currentTimeMillis();

    private static final int LOG_MAX = 300;
    private static final List<String> LOGS = new ArrayList<String>();

    /** 由 Common.onCreate() 调用。全部 try 包住：注入层出任何问题都不能拖垮 player。 */
    public static void attach(Context c) {
        try {
            app = c.getApplicationContext();
            proc = procName();
            log("attach proc=" + proc + " ver=" + VER);
            if (proc.indexOf(':') >= 0) {
                log("skip web: 非主进程");
                return;
            }
            /* pl-1.6.30：先把 SP 里存着的七彩开关接回来（含自驱泵）。
               以前只在 setOn(true) 那一刻起泵 ⇒ 进程一重启「页面显示开、灯却不动」。 */
            try {
                Amb.boot();
            } catch (Throwable t) {
                log("amb boot 失败: " + t);
            }
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(3500);
                    } catch (Throwable t) {
                        // ignore
                    }
                    try {
                        PCtl.bind(app);
                    } catch (Throwable t) {
                        log("bind EasyPlayer 失败: " + t);
                    }
                    try {
                        WebSrv.start(app);
                    } catch (Throwable t) {
                        log("起 web 失败: " + t);
                    }
                    try {
                        // 蓝牙广播 + A2DP sink 代理（蓝牙开着才会拿到代理，
                        // 之后 STATE_ON 广播里还会再试一次）
                        Bt.init(app);
                    } catch (Throwable t) {
                        log("bt init 失败: " + t);
                    }
                    try {
                        /* pl-1.6.150：蓝牙氛围灯兜底（原厂 lightId 255 = CYAN 淡青蓝）。
                           原厂 EchoService.enterBusy 会发 255，但 14:47 实测出现过
                           「A2DP 在放歌却没 enterBusy」⇒ 一次灯效都不发；
                           这里只按 A2DP 实际连接态补 show/close，灯效仍由原厂跑。 */
                        Bt.initBtLight();
                    } catch (Throwable t) {
                        log("bt light init 失败: " + t);
                    }
                    try {
                        // 手机 DLNA 推流的曲目广播（:DlnaPlayer 进程插桩发过来）
                        Ext.init(app);
                    } catch (Throwable t) {
                        log("ext init 失败: " + t);
                    }
                    try {
                        /* pl-1.6.93：按账本把用户手动开过的 DLNA/AirPlay/蓝牙
                           恢复回来 —— 重启这条路不走 shutdownAllExternalConnectPlayer，
                           以前装完包这两个功能就一直关着，直到下次播放才可能补开。 */
                        Svc.restoreOnBoot();
                    } catch (Throwable t) {
                        log("svc restore 失败: " + t);
                    }
                }
            }, "r1web-boot").start();
        } catch (Throwable t) {
            try {
                Log.e(TAG, "attach: " + t);
            } catch (Throwable t2) {
                // ignore
            }
        }
    }

    /** 由 EasyPlayer.play() 开头调用：把整张列表记下来（= 队列的真相）。 */
    public static void onPlay(PlayRequestInfo pri) {
        try {
            PCtl.noteQueue(pri);
        } catch (Throwable t) {
            log("onPlay: " + t);
        }
    }

    /** 由 EasyPlayer.playIndex() 开头调用：只换位置，不动列表。 */
    public static void onIndex(int i) {
        try {
            PCtl.noteIndex(i);
        } catch (Throwable t) {
            // ignore
        }
    }

    /**
     * 由 smali 钩子（EasyPlayer.shutdownAllExternalConnectPlayer → 本方法）调用。
     * 接着交给 {@link Svc#onExternalShutdown()} 在 5 秒窗口里按账本补开。
     */
    public static void onExternalShutdown() {
        try {
            Svc.onExternalShutdown();
        } catch (Throwable t) {
            log("onExternalShutdown: " + t);
        }
    }

    /** 写内存环日志（/api/log?local=1 读的就是它），同时打一条 logcat。 */
    public static void log(String s) {
        try {
            String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + s;
            synchronized (LOGS) {
                LOGS.add(line);
                while (LOGS.size() > LOG_MAX) {
                    LOGS.remove(0);
                }
            }
            Log.i(TAG, s);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** 最后 n 行内存日志。 */
    public static String logs(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (LOGS) {
            int from = LOGS.size() - n;
            if (from < 0) {
                from = 0;
            }
            for (int i = from; i < LOGS.size(); i++) {
                sb.append(LOGS.get(i)).append("\n");
            }
        }
        return sb.toString();
    }

    /** /proc/self/cmdline 第一段 —— 用来区分主进程与 :DlnaPlayer 之类的子进程。 */
    private static String procName() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/self/cmdline"), 256);
            String line = br.readLine();
            if (line == null) {
                line = "";
            }
            int z = line.indexOf(0);
            String name = (z > 0) ? line.substring(0, z) : line;
            if (br != null) {
                br.close();
            }
            return name;
        } catch (Throwable t) {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Throwable t2) {
                // ignore
            }
            return "?";
        }
    }

    private Boot() {
    }
}
