package cn.workbuddy.r1client;

import android.util.Log;

/**
 * 听书进度自记（v1.3.308）。
 *
 * <p>问题：以前「播到第几秒」只有**网页开着**的时候才回写（player.html 每 8 秒
 * POST /api/prog/pos）。语音听书是最常见的用法，网页根本没开 ⇒ 我的节目里
 * 只有集数、lastPos 永远是上次开网页那一刻的旧值（或者 0）。于是用户喊
 * 「继续播放《凡人修仙传》」时，集数对得上，秒数只能从 0:00 起。</p>
 *
 * <p>现在 device 自己每 15 秒记一次：正在播的是节目行 ⇒ 问 player 要当前
 * 秒数（它自己的 /api/status.music.posMs）⇒ 写回 Prog.setProgress。
 * 网页那条路照旧，两边写的是同一份记忆。</p>
 *
 * <p>节流：位置比上次写入前进不足 15 秒就不写（也不回退写 —— 回退说明用户
 * 刚拖过进度条，那就立刻记）。文件写入量 ≈ 听 1 小时 240 次 → 15 秒粒度
 * 实际约 120 次，可忽略。</p>
 */
public final class ProgProgress {

    private static final String TAG = "R1ProgPos";
    private static final int PERIOD_MS = 15000;
    /** ★ v1.3.349：轻探间隔。每 2 秒看一眼 player 的播放指纹（约 4.6KB 的
     *  /api/status 摘要），发现「换项 / 刚开始播」就立刻走一次完整 tick
     *  —— 起播即上报，服务端播放记录不用再等 15 秒周期。 */
    private static final int PROBE_MS = 2000;

    private static volatile boolean started = false;
    private static String lastKey = "";
    private static int lastPos = -1;
    /** ★ v1.3.309：SP 那份「最近听书记忆」的节流状态（与收藏无关的那条路）。 */
    private static String liveKey = "";
    private static int liveLastPos = -1;
    /* ★ v1.3.336 P1 书级共享：服务端同步节流状态（books 共享表，换台设备接着听）。 */
    private static String srvKey = "";
    private static int srvPos = -1;
    private static String srvPrevBook = "";
    private static int srvPrevEp = 0;
    /* ★ v1.3.350：电台（广播）的上报节流状态。 */
    private static String srvRadioKey = "";
    private static int srvRadioPos = -1;

    private ProgProgress() {
    }

    /** 幂等启动（XzBootstrap 开机挂一次）。 */
    public static void start() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(10000);            // 等 player 的 web 起来
                } catch (Throwable ignored) {
                }
                long lastFull = 0;
                String lastFp = "";
                while (true) {
                    try {
                        /* ★ v1.3.349：2 秒一次轻探。
                           指纹变了（换书/换集/起停）⇒ 立刻完整跑一遍，起播那一拍就上报；
                           指纹没变 ⇒ 只按 15 秒周期跑，避免持续拉整张队列。 */
                        String fp = PlayerBridge.playerFingerprint();
                        long now = System.currentTimeMillis();
                        boolean moved = fp.length() > 0 && !fp.equals(lastFp);
                        boolean ok = true;
                        if (moved || now - lastFull >= PERIOD_MS) {
                            ok = tick();
                            lastFull = now;
                        }
                        /* 只有「真的处理了这份指纹」才记住它：起播瞬间 player 可能还没
                           把队列项/书名解析出来（tick 返回 false），若此时也记下指纹，
                           后面指纹不再变化，就白白退回到 15 秒周期了。 */
                        if (fp.length() > 0 && (ok || !moved)) {
                            lastFp = fp;
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "tick: " + e);
                    }
                    try {
                        Thread.sleep(PROBE_MS);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "prog-progress");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "started");
    }

    /** 跑一拍：把「在播什么 + 播到哪」写进服务端 / 本地记忆。
     *
     *  @return true 表示这一拍确实处理了一份有效的播放状态（调用方据此决定要不要
     *          记住本次指纹）；false 表示还没准备好（队列项/书名没解析出来），
     *          应当很快重试。
     */
    private static boolean tick() {
        /* ★ v1.3.309：先记一份「最近听书记忆」（SP，跟收藏无关）——
           纯语音 ab_play 播的书不在我的节目里，Prog 没条目，但「继续听」也得接得上。 */
        String[] live = PlayerBridge.liveListenNow();
        if (live == null) {
            liveKey = "";
            liveLastPos = -1;
            lastKey = "";
            lastPos = -1;
            srvKey = "";
            srvPos = -1;
            srvPrevBook = "";
            srvPrevEp = 0;
            /* ★ v1.3.350：不是书 —— 是不是在听广播？
               电台是上游直链，服务端 /r/ /radio/ 都不在链路上（代理默认关），
               播放记录里从来不会有「在听广播」。这里补一条：台名 + 已播秒数，
               由服务端 /v1/pos（kind=radio）落设备维度记录。 */
            String rd = PlayerBridge.liveRadioNow();
            if (rd != null) {
                srvRadioTick(rd, PlayerBridge.playerPosSec());
                return true;        // 这一拍有效（电台也算「处理了当前播放状态」）
            }
            srvRadioKey = "";
            srvRadioPos = -1;
            return false;
        }
        srvRadioKey = "";
        srvRadioPos = -1;
        int ep;
        try {
            ep = Integer.parseInt(live[1]);
        } catch (Throwable t) {
            return false;
        }
        int pos = PlayerBridge.playerPosSec();
        /* ★ v1.3.349：位置不再设下限（原来要求 ≥8 秒才算数）。起播那一刻 pos 就是 0，
           卡 8 秒等于起播后还得再等一个周期才有记录。安全性由「谁在播」保证：这里的
           位置永远来自 player 队列的**当前项** —— 取链预载的下一首 cur 还没指过去，
           不会把预取误记成播放。 */
        if (ep <= 0) {
            return false;
        }
        /* ★ v1.3.336 P1 书级共享：位置同步给服务端 books 共享表（POST /v1/pos，
           media server v1.7.14+）。节流与本地记忆同款：换书/换集立即报；同一集内
           位置前进 ≥15s 才报。自然连播（同书 ep+1）先给上一集补 fin=1（服务端据此
           「下一台设备从下一集开头」），随后再报当前集 —— 顺序保证最终态正确。
           网络差/服务端不在只静默，绝不影响本地播放。 */
        try {
            String bk = live[0];
            String k = bk + ":" + ep;
            if (!k.equals(srvKey)) {
                if (srvPrevEp > 0 && srvPrevBook.equals(bk) && ep == srvPrevEp + 1) {
                    srvReport(bk, srvPrevEp, 0, true);
                }
                /* ★ v1.3.349：刚换集却读到 60 秒以上的位置 ⇒ 引擎切源瞬间 status
                   还没重置，那是上一集的残留 posMs。按 0 报，别把新一集记成旧位置
                   （真实位置 15 秒内就会补上）。 */
                int usePos = pos > 60 ? 0 : pos;
                srvReport(bk, ep, usePos, false);
                srvKey = k;
                srvPos = usePos;
                srvPrevBook = bk;
                srvPrevEp = ep;
            } else if (srvPos < 0 || pos < srvPos || pos - srvPos >= 15) {
                srvReport(bk, ep, pos, false);
                srvPos = pos;
            }
        } catch (Throwable t) {
            Log.w(TAG, "srv sync: " + t);
        }
        String lk = live[0] + ":" + ep;
        /* 前进不足 15 秒不写；回退（拖了进度条）立刻写 */
        if (!lk.equals(liveKey) || liveLastPos < 0 || pos < liveLastPos
                || pos - liveLastPos >= 15) {
            PlayerBridge.noteListenPos(live[0], ep, pos);
            liveKey = lk;
            liveLastPos = pos;
        }
        /* 已收藏的书再写一份到我的节目（网页 / 卡片读的就是这份） */
        String[] now = PlayerBridge.liveProgNow();
        if (now == null) {
            lastKey = "";
            lastPos = -1;
            return true;            // 服务端那一路已经报过，这份「在播什么」有效
        }
        String key = now[2] + ":" + ep;
        if (!key.equals(lastKey)) {                 // 换集 ⇒ 重新开始记
            lastKey = key;
            lastPos = -1;
        }
        if (lastPos > 0 && pos > lastPos && pos - lastPos < 15) {
            return true;
        }
        if (Prog.setProgress(now[2], ep, pos)) {
            lastPos = pos;
            Log.i(TAG, "prog pos " + now[0] + " ep=" + ep + " pos=" + pos + "s");
        }
        return true;
    }

    /* ★ v1.3.343：设备身份统一为 12 位大写 MAC（wlan0，复用 PlayerBridge.mac12，
       与服务端 devIdOf 的 X-Dev-Mac 归一化同口径）。1.3.342 起 WebConsole prog_setpos
       转发已用 MAC 上报，本类旧口径 R1-xxxx 会在服务端设备列表多出同机第二条，
       故收敛为同一把钥匙。MAC 读不到退化为型号本身（服务端退 ip-<地址>）。 */

    /** 设备型号 —— 本客户端只跑在斐讯 R1 音箱上。实测这台的 Build.MODEL 是板级名
     *  「rk322x-box」（rk3229 方案的通用板名，不是产品名），上报出去看不懂，故固定 R1。 */
    private static String devModel() {
        return "R1";
    }

    /** 设备名（服务端设备列表展示，如「R1-CD6B」；与 progPosReport 转发同口径） */
    private static String devName() {
        String mac = PlayerBridge.mac12();
        return devModel() + (mac.length() >= 4 ? "-" + mac.substring(mac.length() - 4) : "音箱");
    }

    /** 上报用的完整设备号（12 位大写 MAC，如 98BB9936CD6B；无 MAC 时退化为型号本身） */
    private static String devId() {
        String mac = PlayerBridge.mac12();
        return mac.length() >= 8 ? mac : devModel();
    }

    /** ★ v1.3.350：电台（广播）上报的节流 —— 换台立刻报，同台每 30 秒报一次位置。
     *  服务端把同台的上报合并成一条设备记录（不刷屏），这里只控制请求量。 */
    private static void srvRadioTick(String name, int pos) {
        if (name == null || name.trim().length() == 0) {
            return;
        }
        String n = name.trim();
        if (!n.equals(srvRadioKey) || srvRadioPos < 0 || pos < srvRadioPos
                || pos - srvRadioPos >= 30) {
            srvPost("radio", n, "", 0, Math.max(0, pos), false);
            srvRadioKey = n;
            srvRadioPos = pos;
            Log.i(TAG, "radio pos " + n + " " + pos + "s");
        }
    }

    /** ★ v1.3.336：单次 POST /v1/pos（2.5s 超时，失败静默）。 */
    private static void srvReport(String book, int ep, int pos, boolean fin) {
        srvPost("book", book, book, ep, pos, fin);
    }

    /** ★ v1.3.350：kind 通用化 —— book / radio 共用一条上报通道（服务端 /v1/pos 支持两者）。 */
    private static void srvPost(String kind, String q, String book, int ep, int pos, boolean fin) {
        try {
            String b = PlayerBridge.nasBase();
            if (b == null || b.length() == 0 || q == null || q.trim().length() == 0) {
                return;
            }
            if ("book".equals(kind) && ep <= 0) {
                return;
            }
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("kind", kind);
            j.put("q", q);
            if (book != null && book.length() > 0) {
                j.put("book", book);
            }
            if (ep > 0) {
                j.put("ep", ep);
            }
            j.put("pos", Math.max(0, pos));
            j.put("fin", fin);
            // ★ v1.3.337：设备型号上报 —— 服务端播放记录/书级进度能认出「这是谁播的」
            j.put("devId", devId());
            j.put("devName", devName());
            byte[] out = j.toString().getBytes("UTF-8");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(b + "/v1/pos").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            java.io.OutputStream os = c.getOutputStream();
            os.write(out);
            os.flush();
            try { os.close(); } catch (Throwable ignored) { }
            int code = c.getResponseCode();
            try { c.getInputStream().close(); } catch (Throwable ignored) { }
            if (code != 200) {
                Log.w(TAG, "srv pos http " + code);
            }
        } catch (Throwable t) {
            Log.w(TAG, "srv pos: " + t);
        }
    }

    /** ★ v1.3.340：主动暂停 / 停止 / 关机 / 网页暂停 的**结算上报** —— 立即把当前
     *  live 位置报给服务端 books 共享表，不等下一个 15s 周期（与 stem /ap/pos
     *  的暂停/打断结算同口径）。防误报：不在播 / 集号非法 / pos<8s 一律静默，
     *  不会报出「0 开始」的记录。reason 只进日志，方便 logcat 认来源。 */
    public static void srvFinal(String reason) {
        try {
            String[] live = PlayerBridge.liveListenNow();
            if (live == null) {
                /* ★ v1.3.350：正听广播时暂停/停止 —— 也结算一次，别让记录停在
                   上一次 30 秒节流的位置。 */
                String rd = PlayerBridge.liveRadioNow();
                if (rd != null) {
                    int p2 = PlayerBridge.playerPosSec();
                    if (p2 >= 1) {
                        srvPost("radio", rd, "", 0, p2, false);
                        srvRadioKey = rd;
                        srvRadioPos = p2;
                        Log.i(TAG, "srv final radio" + (reason == null ? "" : "(" + reason + ")")
                                + " " + rd + " pos=" + p2 + "s");
                    }
                }
                return;
            }
            int ep;
            try {
                ep = Integer.parseInt(live[1]);
            } catch (Throwable t) {
                return;
            }
            if (ep <= 0) {
                return;
            }
            int pos = PlayerBridge.playerPosSec();
            /* ★ v1.3.349：原来卡 8 秒，起播后马上暂停（3 秒）就不结算，
               服务端一直停在起播那拍的 0。放到 1 秒即可，0 秒仍不报（没有信息量）。 */
            if (pos < 1) {
                return;
            }
            srvReport(live[0], ep, pos, false);
            srvKey = live[0] + ":" + ep;
            srvPos = pos;
            srvPrevBook = live[0];
            srvPrevEp = ep;
            Log.i(TAG, "srv final" + (reason == null ? "" : "(" + reason + ")")
                    + " ep=" + ep + " pos=" + pos + "s");
        } catch (Throwable t) {
            Log.w(TAG, "srvFinal: " + t);
        }
    }
}
