package cn.workbuddy.r1client;

import android.util.Log;

import org.json.JSONObject;

/**
 * v1.3.254：顶部实体按键的「动作」实现。
 *
 * <p>按键<b>检测</b>（单击 / 双击 / 长按 / 三击的判定）仍由原厂
 * {@code PhicommKeyEventProcessor} 负责；它在 {@code uni_smali} 里 new 出来的
 * {@code PhicommKeyEventController} 经 smali 钩子把 {@code onClickEvent / onDoubleClickEvent}
 * 转调本类的 {@link #onClick()} / {@link #onDoubleClick()} —— 这样我们<b>接管动作</b>而不用
 * 碰检测逻辑，也绕开了「dex 合并同名类」的坑。</p>
 *
 * <p>原厂这套链本是为「小讯」服务的：双击停唤醒 + 亮闭麦灯、单击灭闭麦灯。小讯被替换后
 * 那段失效（灯还亮、但没真 mute）。这里重新定义成：</p>
 * <ul>
 *   <li><b>双击</b> → 切换「关闭语音助手（省电模式）」，并同步闭麦/休眠灯（亮 = 语音关）。</li>
 *   <li><b>单击</b> → 闹钟正在响 ⇒ 停止闹钟；否则音乐在播 ⇒ 暂停，音乐暂停/空闲 ⇒ 继续播放。</li>
 * </ul>
 */
public final class TopKey {

    private static final String TAG = "R1TopKey";

    private TopKey() {
    }

    /** 双击：切换语音助手（省电模式），同步闭麦灯。 */
    public static void onDoubleClick() {
        try {
            boolean turnOff = !VoicePower.isOff();   // 当前 ON → 关；当前 OFF → 开
            VoicePower.set(turnOff);
            R1Lights.setDormant(turnOff);            // 灯：关语音 = 亮，开语音 = 灭
            Log.i(TAG, "双击：语音助手 " + (turnOff ? "关闭(省电)" : "开启")
                    + "，闭麦灯 " + (turnOff ? "亮" : "灭"));
            notifyPlayer();                          // 让网页实时刷新（不必等轮询）
        } catch (Throwable t) {
            Log.w(TAG, "onDoubleClick failed: " + t);
            notifyPlayer();
        }
    }

    /** 单击：闹钟响→停闹钟；否则切换音乐播放/暂停。 */
    public static void onClick() {
        try {
            // 闹钟优先：响铃中单击 → 停止闹钟
            String ring = Alarm.ringId();
            if (ring != null && !ring.isEmpty()) {
                Alarm.mcp("dismiss", new JSONObject());
                Log.i(TAG, "单击：闹钟响铃中 → 停止闹钟");
                notifyPlayer();
                return;
            }
            // 否则音乐播放态切换
            if (PlayerBridge.isSoftPlaying()) {
                PlayerBridge.pauseMusic();
                MusicResume.noteUserPause();   // ★ v1.3.341：实体键暂停也是显式意图 ⇒ 小智说完话不自动恢复
                Log.i(TAG, "单击：音乐在播 → 暂停");
                notifyPlayer("toppause");   // pl-1.6.92：player 记「用户暂停」+ 推 SSE
            } else {
                PlayerBridge.resumeMusic();
                MusicResume.noteUserPlay();    // ★ v1.3.341：实体键恢复 ⇒ 清暂停标记，下一轮让路照常
                Log.i(TAG, "单击：音乐暂停/空闲 → 继续播放");
                notifyPlayer("topresume");  // pl-1.6.92：清暂停记账 + 推 SSE
            }
        } catch (Throwable t) {
            Log.w(TAG, "onClick failed: " + t);
            notifyPlayer();
        }
    }

    /** 改完状态后通知 player 推一次 SSE，让网页实时刷新（不必等 8s 轮询）。
     *  走 127.0.0.1:8080（player 控制台），与 R1Lights 的拾音灯直写同一条路。
     *
     *  ★ pl-1.6.92 / v1.3.256：op 可带意图 ——
     *  <ul>
     *    <li>{@code topkey}   —— 纯刷新（双击语音开关、停闹钟用）；</li>
     *    <li>{@code toppause} —— player 先把 PCtl 记成「用户主动暂停」再推 SSE。
     *        实体键的暂停走原厂总线（msg(2)），不经过 PCtl.pause()，sUserPaused
     *        一直没人置位 ⇒ phase() 靠「进度 4 秒内变过 ⇒ 当在播」的宽限撑着，
     *        图标/假进度要 ~4 秒才停。这个 op 补上记账，网页立刻回 ▶；</li>
     *    <li>{@code topresume} —— 反向：清掉暂停记账（恢复后别一直显示暂停）。</li>
     *  </ul> */
    private static void notifyPlayer(final String op) {
        final String url = "http://127.0.0.1:8080/api/act?op=" + op;
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    try {
                        c.setConnectTimeout(1500);
                        c.setReadTimeout(2500);
                        c.setRequestMethod("GET");
                        c.getResponseCode();
                    } finally {
                        c.disconnect();
                    }
                } catch (Throwable t) {
                    // player 没起 / 8080 还没绑 —— 网页靠 8s 轮询兜底，不影响按键本身
                    Log.i(TAG, "notifyPlayer(" + op + ") 失败: " + t);
                }
            }
        }, "r1-topkey-notify").start();
    }

    private static void notifyPlayer() {
        notifyPlayer("topkey");
    }
}
