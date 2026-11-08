#!/bin/bash
# pl-1.6.3：
#   ① 「只有可被搜到时才能连」—— 原厂 BluetoothPlayer 在手机连上（CONNECTION_STATE
#      CONNECTED）和 disconnectDevice 里把 scan mode 强设 SCAN_MODE_NONE(20)，
#      isSystemInNormalMode 常态恒真 ⇒ 我们设的 21/23 活不过一次连接。
#      patch_bt_scan.py 两处全拔（startServer 的 23 保留）。
#   ② 可发现状态偏好账本（Bt.java：files/r1_bt_disc.txt，手动点过才写）——
#      attach / 适配器 STATE_ON / reopenEngine 后恢复；无记录默认 23
#      （和设置向导出厂态一致），保证「蓝牙开着就能被已配对手机连上」。
#   ③ 常数口径写死 20/21/23（本 ROM 编译值互证；教材 21/23/39 是别的平台的），
#      /api/bt 加 scanMode 原值，页面 hint 直显，排连不上一眼定位。
#
# pl-1.6.2：
#   ① 手动意图账本（files/r1_svc_manual.txt）—— 手动开过的功能（dlna/airplay/bt）
#      被 ensureBusy 停掉后 2.5s 自动补开；手动关过/没记录的一律不补。
#
# pv12（pl-1.4.0）：
#   ① 赞赏码修好 —— 页面引用 /donate.png，player 侧压根没这个路由 ⇒ 图片 404；
#   ② PWA「安装到桌面」修好 —— 之前只给了一个没有图标的假 manifest，
#      而 Chrome 要求 manifest(带 192/512 图标) + 注册过 SW 才肯给安装资格；
#      现在 /manifest.webmanifest / /sw.js / /icon-*.png 三件套整套补齐；
#   ③ 设置页「设备」栏 → 「设备状态」，新增 CPU 占用 / 内存占用 / 温度 / 存储空间
#      （数据源 /api/device：player 直接读 procfs、sysfs；小飞是靠 8080 WS 发
#       {type:"shell"} 让设备跑 cat 再回传，我们不用绕这一圈）；
#   ④ 一键清理无用进程 —— 只杀认得的原厂残留（launcher / systemtool / netctl）。
#
#   ① 循环模式用**原厂真值**（1=随机 2=顺序 3=单曲循环）—— 以前发 0，
#      而 AbsPlayer.setPlayMode 只认 1/2/3，0 直接被打回 ⇒「顺序播放」点了没反应；
#   ② 「播放控制」里的「随机播放」更名「随便听」；
#   ③ 按歌手连播：数量留空 = 该歌手全部歌曲（服务端 count<=0 不限量）；
#   ④ player 重启后顶栏残留曲名 ⇒ 队列空时点 ▶ 走 op=replay（device 用落盘曲名
#      回库反查重推列表开播，查不到退化 random），status 新增 mode / stale。
#
# pv7（pl-1.1.5）：① 队列为空时 ▶ 直接随机播放；② 上下曲 1.2s 主动补状态+队列；
#   ③ 切歌时队列自动把正在播的那条滚进可视区（cur 没变则不动）。
#
# pv6（pl-1.1.4）：设置页重排 + 队列上限 + 空搜索列全部
#   ① 氛围灯 + 调试 两栏合并为「灯光控制」（氛围灯 / 唤醒灯 / 播报灯）；
#   ② 新增「播放列表」上限输入（localStorage，0 = 不限制）；
#   ③ 搜索框留空点搜索 = 列全部曲库（/api/search?k=&limit=N，player 侧原样转发）。
#
# =============================================================================
#  jp2-player 注入版构建：把控制台塞进原厂 player（com.phicomm.speaker.player）
#
#  产物 = [前置 dex（原厂 934 类 + 我们的 cn.workbuddy.r1web）] ++ [EchoService_system.apk]
#  （Janus CVE-2017-13156 逐字节拼接；janus_poc.py 会修 dex 头与 EOCD 偏移）
#
#  用法：  bash build_pv2.sh
# =============================================================================
# =============================================================================
#  pl-1.6.33（pv40 复用）：赞赏/讨论群双码弹窗 + 页脚 GitHub 项目地址
#
#  ① 「赞赏码」按钮更名「赞赏」；弹窗改两栏：左赞赏（附「本项目完全免费，
#     是否捐赠不影响功能使用」声明），右 QQ 玩机讨论群（群号 885906281）；
#  ② 群二维码 = 剪贴板 jpg → GROUP_QR_B64（device WebConsole.java 落常量，
#     _gen_assets.py 抽到 player Assets.java），WebSrv 新增 /group.png 路由
#     （image/jpeg + 7 天缓存）；device 侧同步加路由，两边口径一致；
#  ③ 页脚加 GitHub 项目地址（shj4588/Phicomm_R1_XiaoZhi_Client）。
#
# =============================================================================
#  pv40（pl-1.6.30）
#
#  ① ★★ 颜色必须 & 0xFFFFFF —— 之前「开了七彩但灯不亮」的头号嫌疑。
#       原厂：LedLight.setColor(0x7FFF, 0xffffff & Color.HSBtoColor(...))；
#       小飞魔改版：const p3, 0xffffff ... and-int/2addr 之后再 set_color。
#       ⇒ 传下去的一直是 **0x00RRGGBB**；而 Color.HSVToColor() 返回 0xFFRRGGBB
#         （alpha=255），多出来的最高字节被 native 当什么用没人保证。按原厂口径砍掉。
#  ② ★★ 不能依赖原厂的「氛围灯总开关」(SP music_light_enable)。
#       原厂每帧是 if (mUserEnable) 写彩色 else setColor(ALL, 0)
#       —— 总开关关着时它每 ~50ms 送一次**黑**；老版本碰到黑就跟着写黑
#       ⇒ 用户观感就是「开了七彩，灯不亮」。
#       现在七彩开着就整帧接管：原厂送黑我们用自驱亮度顶上。
#  ③ 自驱泵 pump()：原厂完全不回调时（没播歌 / PlayerVisualizer 没起来）自己按
#       100ms 一帧闪，色相缓慢推进。判据是「上次回调多久了」而不是「在不在播歌」，
#       免得两边抢着写同一批灯。
#  ④ 诊断口 op=amb7&v=probe（status 里也有 amb7d）：钩子调用/接管/自驱帧/异常计数
#       + 上次回调年龄 + 基色 + 原厂总开关值 —— 以后「灯不亮」不用再靠肉眼猜。
#  ⑤ 顺手在开七彩时把原厂总开关也拨开（尽力而为，失败有自驱兜底），
#       这样播歌时才有「跟着音乐走」的色相/亮度可以沿用。
#  ⑥ queue_all 给足 25s 超时（整库上千首要逐首推总线，8s 会假失败）。
#
# =============================================================================
# =============================================================================
#  pv39（pl-1.6.28 / pl-1.6.29）
#
#  ---------------------------------------------------------------- 1.6.28 ----
#  ① 搜索曲库那一栏加「全部载入播放列表」按钮（配套 device 侧新 op=queue_all）：
#       以前想连播只能一首首点 —— 点一条只播一条，列表根本没动。
#       现在把当前搜索结果整批排进播放列表（ids = 曲库序号，1 起，逗号分隔）；
#       还没搜过 ⇒ 空 ids ⇒ device 理解为「整个曲库」。两种都受播放列表上限约束。
#  ② 去掉「按歌手连播」卡片（语音/MCP 路径的 play_artist 保留不动）。
#  ③ 七彩氛围灯：原厂 PlayerVisualizer 每帧 LedLight.setColor(0x7FFF, 基色)，
#       15 颗**同色**；底层其实是「掩码+颜色」API ⇒ 钩子接管后逐颗写，
#       每次闪动把 15 个色相洗一次牌（最小间隔 100ms，不然会糊成噪点）。
#       ★★ op 名是 amb7 —— 页面的「氛围灯总开关」走 act("amb") 反代给 device，
#          这里叫 amb 会把那条路抢走。
#
#  ---------------------------------------------------------------- 1.6.29 ----
#  ④ 播放页加「码率 + 格式」显示（含电台）。三路退化：
#       ① Exo 引擎 ⇒ 反射拿 Exo 的 Format（bitrate/sampleMimeType/sampleRate/channelCount）
#       ② URL 里把码率写进路径的源（蜻蜓 ".../live/15318317/64k.mp3" ⇒ 64k）
#       ③ HTTP HEAD 的 Content-Length ÷ 总时长（异步 + 按 URL 缓存）
#     格式与码率互不阻塞：电台拿不到时长也照样显示「MP3 · 64kbps」。
#     ★ mp 引擎没有 Format ⇒ 走 ②③，所以换了引擎也看得到（只是码率晚一轮轮询）。
#
# =============================================================================
# =============================================================================
#  pv38（pl-1.6.27）：暂停即时反馈 / 电台不再外挂歌词封面 / 电台列表去高亮
#
#  ① 点「暂停」后 UI 迟迟不变（声音早停了，按钮图标还停在「暂停」、进度条还在走，
#     长的要十几秒才变）—— 两条原因叠加：
#       · 页面状态靠 8 秒一次的 /api/status 刷新，点那一刻没人重新渲染；
#       · 就算轮询到了，PCtl.phase() 里那条「进度 4 秒内变过 ⇒ 当在播」的宽限
#         （为 mp 引擎直播流 isPlaying() 假阴性加的）会让暂停后仍报 playing；
#         某些引擎上 isPlaying() 更是暂停了还返 true。
#     修法两层：
#       · 服务端：加「用户主动暂停」记账 sUserPaused —— pause() 置位，phase() 见到
#         它直接 idle（再不去问引擎），任何起播动作（play/next/prev/playAt/
#         小智推列表 noteQueue/换 index noteIndex）都调 noteResume() 清掉；
#         stallMs() 同步返 0（暂停不是「卡住」）；新增 playingUi() 给页面用。
#       · 页面：togglePlay() 乐观更新 —— 就地置 PSTATE=0 / PH="idle" 并 renderNp()
#         （图标回 ▶、唱片停转、POS_BASE_SEC 作废 ⇒ tickPos 当场停摆），
#         800ms 后再拉一次真值校准（op 万一失败，那一次 load 会纠正回来）。
#
#  ② 电台列表切台时高亮「跳回旧台再跳回来」——
#     load() 里用 device 回推的 curTitle 反查台名来标红；切台时上一个台还在缓冲，
#     curTitle 迟迟不变 ⇒ 红条先跳回旧台、新台起播再跳回来。而且电台的 curTitle
#     是 ICY 流标题，跟列表台名常常对不上 ⇒ 这个高亮根本没有可信来源。
#     ⇒ 按用户要求：去掉高亮（统一「网络直播」），改成点一下闪一下 + toast；
#       load() 那段反推只留记账、不再重建列表（重建会把滚动位置弹回顶部）；
#       renderRadios 的签名里去掉 curRadio。
#
#  ③ 电台也会去匹配歌词和封面，实际上都是不对的 ——
#     台名（ICY 流标题）拿去按「歌名+歌手」搜，回来的必是别的歌/别的图。
#     ⇒ 服务端：radio 时把 curPic 置空（device 那份直接不给页面）；
#       页面：电台隐藏「歌词」按钮（已展开则收起）、paintCover 回落成转碟、
#             fetchLrc 连请求都不发。
#
#  pv37（pl-1.6.26）：电台判定即时化（Exo 起播不再先显示「本地曲库」）
#
#  pv36（pl-1.6.24）：进度条匀速 / 电台不再是「本地曲库」/ 网页切引擎
#
#  ① 进度条「一下快一下慢」根治（客户端）：
#     syncPos 以前拿 p - POS_SEC*1000 当误差 ⇒ 把 HTTP 往返那 80~400ms 的抖动
#     当成「我走快了/走慢了」，每格在 0.7~1.3s 之间随机游走。
#     现在只锁**整秒**：秒数对得上就完全不碰 POS_NEXT。
#  ② 电台被当成本地曲库（player）：
#     player 直供的 music 段覆盖了 device 的 qRadio；且 Exo 对直播流报 TIME_UNSET，
#     long-to-int 后是 1 ⇒ 页面用 1ms 当总时长 ⇒ 条恒 100%、时间恒 0:00。
#     现在 PCtl.isRadio() 自己认（时长无效 + 已在走），durMs 归 0，seekOk 置 false。
#  ③ 网页加「播放引擎」切换（ExoPlayer 2.18 ↔ 原厂 MediaPlayer）：
#     写 SP r1_engine_mp + 重启 :NetPlayer 进程（onCreate 才读开关）。
#
#  pv35（pl-1.6.22）：网络流引擎换成 ExoPlayer 2.18.0
#
#  背景：同一台设备实测
#    v126 device + 小飞 player（Exo 2.18）→ 1.49s 出声，后 4 条补踢不重开声卡，0 停顿
#    v126 device + 我们 player（MediaPlayer）→ 4.29s 出声，每条补踢都重建引擎，卡 3s
#  根因不在 device（两边补踢节奏一样），在 player 的引擎：
#    · Exo 对「已 prepared 的 start」幂等，只翻 PlayWhenReady
#    · MultiMediaPlayer 每次 start 都 stopParparingPlayer + reset + 重开声卡
#
#  做法（源头 = smali_exo，由 _prep_exo_tree.py 从 smali_echo 派生）：
#    · 删掉原厂 exomedia(88 类) + exoplayer2(2.6.0, 828 类)
#    · 搬小飞的 exoplayer2 2.18.0 + com.google.common + fastjson + MultiExoPlayer/new_NetPlayer
#    · com/xiaofei/server 用桩（只有 MultiExoPlayer 用到的两个静态方法）
#    · PlaySong 换小飞版（多 albumUrl 字段）
#    · EchoService 补 ver:I（MultiExoPlayer$PlayerInstance 上报用）
#  方法数实测：26918 / 65536（余量 38618）
# =============================================================================
set -u
cd "$(dirname "$0")"
HERE="$(pwd)"
R1="C:/Users/shaoh/WorkBuddy/R1"

AJAR="$R1/sdkdl/stage/android.jar"
R8="$R1/sdkdl/stage/r8.jar"
JAVAC="$R1/janus/dl/jdk-17.0.20.1+1/bin/javac.exe"
JAVA="$R1/janus/dl/jdk-17.0.20.1+1/bin/java.exe"
PY="C:/Users/shaoh/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
SM="$R1/janus/smali"
BCP="$SM/baksmali-2.5.2.jar;$SM/dexlib2-2.5.2.jar;$SM/guava-25.1-android.jar;$SM/jcommander-1.78.jar;$SM/commons-cli-1.4.jar;$SM/antlr-runtime-3.5.2.jar;$SM/util-2.5.2.jar"
SCP="$SM/smali-2.5.2.jar;$SM/dexlib2-2.5.2.jar;$SM/guava-25.1-android.jar;$SM/jcommander-1.78.jar;$SM/commons-cli-1.4.jar;$SM/antlr-runtime-3.5.2.jar;$SM/util-2.5.2.jar"

V=pv40
OUT="xiaozhi_EchoService_$V.apk"

chk() {   # chk <文件> <说明> <特征串>
  if grep -qF -- "$3" "$1"; then echo "  OK   $2"; else echo "  FAIL $2（没找到：$3）"; FAIL=1; fi
}
chkNo() {
  if grep -qF -- "$3" "$1"; then echo "  FAIL $2（不该出现：$3）"; FAIL=1; else echo "  OK   $2"; fi
}
FAIL=0

echo "=========== [0/8] 生成 Ui.java（页面 -> Java 常量） ==========="
# [0.1] 静态资源（赞赏码 base64 / PWA 三件套）从 device 侧抽取，别手抄
"$PY" _gen_assets.py || exit 1
"$PY" ui/gen_java.py || exit 1

echo "=========== [1/8] 静态自检 ==========="
UI="plsrc/cn/workbuddy/r1web/Ui.java"
SRCS="plsrc/cn/workbuddy/r1web"
chk "$SRCS/WebSrv.java" 'op=play_at 路由'  '"play_at".equals(op)'
chk "$SRCS/WebSrv.java" 'op=devctl 路由'   '"devctl".equals(op)'
chk "$SRCS/WebSrv.java" 'CORS'             'Access-Control-Allow-Origin: *'
chk "$SRCS/WebSrv.java" '状态带进度'        'posMs'
chk "$SRCS/DevCtl.java" '停用走 op=halt'    'op=halt'
chk "$SRCS/DevCtl.java" '恢复 startservice' 'am startservice -n '
chk "$SRCS/PCtl.java"   '跳播用 playIndex'  'playIndex(i, true)'
chk "$SRCS/PCtl.java"   '队列来自钩子'       'noteQueue'
chk "$SRCS/Boot.java"   '非主进程跳过'       'skip web: 非主进程'
chk "$UI"               '页面已生成'         'class Ui'
chk "ui/player.html"    '页面有小智开关'      '停用小智'
chk "ui/player.html"    '页面有队列'         'play_at'
chk "$SRCS/Proxy.java"  '反代 8088'         '127.0.0.1:8088'
chk "$SRCS/WebSrv.java" 'status 同构标记'    '"pl", true'
chk "$SRCS/WebSrv.java" '设置反代'          'path.equals("/api/set")'
chk "$SRCS/WebSrv.java" '搜索反代'          'path.equals("/api/search")'
chk "$SRCS/WebSrv.java" '未知 op 转 device'  'Proxy.post("/api/act"'
chk "$SRCS/WebSrv.java" 'act 返回 JSON'     'jsonOk(true'
chk "ui/player.html"    '页面跳过 8080 WS'   'S.pl'
chk "$SRCS/WebSrv.java" 'form body 合并'     'query.putAll(parse(body))'
chk "$SRCS/WebSrv.java" '音量百分比→档位'     'pct * mx / 100.0f'
chk "$SRCS/WebSrv.java" 'volstep 直设档位'   '"volstep".equals(op)'
chk "$SRCS/WebSrv.java" '停用时不干等 8s'    '小智已停用（8088 不可达）'
chk "ui/player.html"    '页面读 volMax'      'm.volMax'
chk "ui/player.html"    '队列签名防重绘'      'qLastSig'
chk "ui/player.html"    '重建保 scrollTop'   'box.scrollTop = st'
chk "ui/player.html"    '定位当前'           'jumpCur'
chk "ui/player.html"    'lnk 样式'           '.ttl .lnk'
chkNo "ui/player.html"  '不再自动 scrollIntoView' 'c.scrollIntoView('
chk "ui/player.html"    '页面有日志/设置/电台' '/api/log'
chkNo "ui/player.html"  '氛围灯已瘦身'        'ambMode('
chkNo "ui/player.html"  '不再有模式段'        'id="segAmb"'
chkNo "ui/player.html"  '不再有颜色选择'      'ambColorApply'
chk "ui/player.html"    '保留氛围灯开关'      'ambToggle'
chk "ui/player.html"    '灯光控制栏'          '>灯光控制 <'
chk "ui/player.html"    '唤醒灯开关'          'swWkL'
chk "ui/player.html"    '播报灯开关'          'swBsL'
chk "ui/player.html"    '队列上限输入'        'saveQlim'
chk "ui/player.html"    '队列用 QLIM'         'limit=" + QLIM'
chk "ui/player.html"    '空队列随机播放'      'playOrRandom'
chk "ui/player.html"    '切歌自动定位'        'queueScrollTo(box, cur)'
chk "ui/player.html"    '上下曲主动补状态'    'function skip('
chk "$SRCS/PCtl.java"    'curIdx 按实际在放校正' 'public static synchronized void syncCur('
chk "$SRCS/PCtl.java"    'queueJson 先 syncCur'  'syncCur();'
chk "$SRCS/PCtl.java"    '窗口 start'          'o.put("start", start)'
chk "ui/player.html"    '队列窗口 start'      'Q.start'
chk "ui/player.html"    'playAt 收真实下标'  'function playAt(i, real)'
chk "ui/player.html"    '切歌按绝对下标判定'  'qLastCurAbs'
chk "$SRCS/PCtl.java"    '模式只收 1/2/3'      'm != 1 && m != 2 && m != 3'
chk "$SRCS/WebSrv.java"  '状态带 mode'         'm.put("mode", PCtl.mode())'
chk "$SRCS/WebSrv.java"  '状态带 stale'        'm.put("stale"'
chk "ui/player.html"    '随机->随便听'        '>随便听</button>'
chk "ui/player.html"    '模式 2/1/3'          'setMode(3)'
chk "ui/player.html"    '空队列走 replay'     'act("replay")'
chk "ui/player.html"    '留空=列全部'         '留空点搜索 = 列全部'
chk "ui/player.html"    '模式回显'            'setModeUI(m.mode)'
chk "ui/player.html"    '空搜索列全部'        '&limit='
chkNo "ui/player.html"  '调试栏已移除'        '>调试</div>'
chk "$SRCS/WebSrv.java" '搜索转发 limit'      '&limit='
# ---- pv12 新增：赞赏码 + PWA 三件套 + 设备状态 ----
chk "$SRCS/Assets.java"  '赞赏码 base64'       'DONATE_QR_B64'
chk "$SRCS/Assets.java"  'manifest 常量'       'MANIFEST_JSON'
chk "$SRCS/Assets.java"  'service worker'      'SW_JS'
chk "$SRCS/Assets.java"  '运行时画图标'         'iconPng(int size'
chk "$SRCS/WebSrv.java"  '/donate.png 路由'    'path.equals("/donate.png")'
chk "$SRCS/WebSrv.java"  '/sw.js 路由'         'path.equals("/sw.js")'
chk "$SRCS/WebSrv.java"  '/icon-* 路由'        'path.startsWith("/icon-")'
chk "$SRCS/WebSrv.java"  'SW 作用域头'         'Service-Worker-Allowed: /'
chk "$SRCS/WebSrv.java"  '二进制写出'          'writeBytes(os, 200'
chk "$SRCS/WebSrv.java"  '/api/device 路由'    'path.equals("/api/device")'
chk "$SRCS/WebSrv.java"  'op=cleanup'          '"cleanup".equals(op)'
chk "$SRCS/WebSrv.java"  'op=restore'          '"restore".equals(op)'
chk "$SRCS/Sys.java"     'CPU 取两次求差'       'Thread.sleep(250)'
chk "$SRCS/Sys.java"     '温度节点 tsadc'       'hwmon0/device/temp0_input'
chk "$SRCS/Sys.java"     '存储 StatFs'         'new android.os.StatFs(path)'
chk "$SRCS/Sys.java"     '进程白名单'           '"system_server"'
chk "$SRCS/Sys.java"     'launcher 受保护'      '"com.phicomm.speaker.launcher"'
chk "$SRCS/Sys.java"     'systemtool 受保护'    '"com.phicomm.speaker.systemtool"'
chk "$SRCS/Sys.java"     'netctl 受保护'        '"com.phicomm.speaker.netctl"'
chk "$SRCS/Sys.java"     '无用服务清单照小飞'    'String[] USELESS'
chk "$SRCS/Sys.java"     '清单 16 个不许多禁'    '"com.phicomm.speaker.exceptionreporter"'
chk "$SRCS/Sys.java"     'defcontainer 受保护'   '"com.android.defcontainer"'
chk "$SRCS/Sys.java"     '只禁已安装的'          'inst.indexOf(pkg) < 0'
chkNo "$SRCS/Sys.java"   '不再按 uid 通杀'       'isAppUid'
chk "$SRCS/Sys.java"     '清缓存 trim-caches'    'pm trim-caches'
chk "$SRCS/Sys.java"     '进程表读 /proc'        '"/proc/" + pid + "/cmdline"'
chk "$SRCS/Sys.java"     'RSS 取 VmRSS'         'VmRSS:'
chk "$SRCS/Sys.java"     '停用后复查'           'left.indexOf(q.name) < 0'
chk "ui/player.html"    '恢复已禁用服务'      '恢复已禁用服务'
chk "$SRCS/Sys.java"     '恢复走 pm unhide'     '/system/bin/pm unhide '
chk "$SRCS/Boot.java"    '版本 pl-1.6.34'      'pl-1.6.34'
# ---- pl-1.6.34：赞赏 / 讨论群 各自独立弹窗 + 项目地址明文另起一行 ----
# pl-1.6.33 是两个码并排塞同一个弹窗（一个按钮），现在左「赞赏」右「讨论群」各弹各的；
# 关闭手势改成「点图片外任意处」（遮罩 / 标题 / 说明都吃点击，只有图片 stopPropagation）
chk   "ui/player.html"    '赞赏按钮'            "showQR('donate')"
chk   "ui/player.html"    '讨论群按钮'          "showQR('group')"
chk   "ui/player.html"    '独立弹窗 #group'     'id="group"'
chk   "ui/player.html"    '遮罩可关（赞赏）'    'id="donate"'
chk   "ui/player.html"    '图片吞掉点击'        'alt="赞赏" onclick="event.stopPropagation()"'
chk   "ui/player.html"    '群图吞掉点击'        'alt="讨论群二维码" onclick="event.stopPropagation()"'
chk   "ui/player.html"    '统一关闭函数'        'function hideQR()'
chk   "ui/player.html"    '项目地址明文'        '>项目地址：<'
chk   "ui/player.html"    'URL 明文可见'        '>https://github.com/shj4588/Phicomm_R1_XiaoZhi_Client<'
chk   "ui/player.html"    '赞赏免责声明'         '本项目完全免费，是否捐赠不影响功能使用'
chk   "ui/player.html"    '群号'                 '885906281'
chk   "$SRCS/WebSrv.java" '/group.png 路由'      'path.equals("/group.png")'
chk   "$SRCS/WebSrv.java" '群码常量引用'         'Assets.GROUP_QR_B64'
chk   "$SRCS/Assets.java" '群码 B64 常量'        'GROUP_QR_B64'
chkNo "ui/player.html"    '赞赏码已更名赞赏'     '赞赏码'
chkNo "ui/player.html"    '不再有旧开关函数'     'showDonate()'
chkNo "ui/player.html"    '不再是双栏弹窗'       'flex-wrap:wrap;justify-content:center;align-items:flex-start'
chk "$SRCS/PCtl.java"    '暂停幂等'            'if (playerStatus(p) != 2)'
# ---- pl-1.6.24：进度条匀速 / 电台识别 / 引擎切换 ----
chk   "$SRCS/PCtl.java"   '电台识别'            'public static boolean isRadio()'
# ---- pl-1.6.26：电台判定即时化 ----
chk   "$SRCS/PCtl.java"   '电台粘性记账'        'private static boolean sRadio = false;'
chk   "$SRCS/PCtl.java"   '停播清记账'          'forgetRadio();'
chk   "$SRCS/PCtl.java"   '不再等 2 秒'          'if (p.getTotal_time() > 1000) {'
chkNo "$SRCS/PCtl.java"   '去掉 pos>2000 门槛'   'getTotal_time() <= 1000 && p.getPlay_time() > 2000'
chk   "$SRCS/WebSrv.java" '未判明不贴标签'      'm.put("liveUnknown", !radio && durMs <= 1000)'
chk   "ui/player.html"    '未判明标签留空'      'else if (QUNK) { tag.style.display = "none"; }'
chk   "$SRCS/PCtl.java"   '引擎切换'            'public static synchronized String setEngine'
chk   "$SRCS/WebSrv.java" '状态吐当前引擎'      'o.put("engine", PCtl.engine())'
chk   "$SRCS/WebSrv.java" 'op=engine'           '"engine".equals(op)'
chk   "$SRCS/WebSrv.java" '电台 durMs 归零'     'if (durMs <= 1000)'
chk   "ui/player.html"    '进度条增量锁相'      'var derr = (sec - POS_BASE_SEC) - (POS_SEC - POS_BASE_DISP);'
chk   "ui/player.html"    '电台显示直播'        '"直播中"'
chk   "ui/player.html"    '引擎按钮 exo'        "setEngine('exo')"
chk   "ui/player.html"    '引擎按钮 mp'         "setEngine('mp')"
# ---- pl-1.6.28：搜索曲库整批入队 + 去掉「按歌手连播」----
chk   "ui/player.html"    '全部载入按钮'        'onclick="queueAll()"'
chk   "ui/player.html"    'queueAll 函数'       'function queueAll()'
chk   "ui/player.html"    'queue_all op'        'act("queue_all"'
chkNo "ui/player.html"    '按歌手连播已删'      '按歌手连播'
chkNo "ui/player.html"    'doArtist 已删'       'function doArtist'
# ---- pl-1.6.28：七彩氛围灯 ----
chk   "$SRCS/Amb.java"    '七彩类存在'          'public final class Amb'
chk   "$SRCS/Amb.java"    '灯珠数 15'           'int N = 15'
chk   "$SRCS/Amb.java"    '每次闪动洗牌'        'Collections.shuffle'
chk   "$SRCS/WebSrv.java" 'amb7 op'             '"amb7".equals(op)'
chkNo "$SRCS/WebSrv.java" 'amb 不被抢'          'if ("amb".equals(op))'
chk   "$SRCS/WebSrv.java" '状态回显 amb7'       'o.put("amb7", Amb.isOn())'
chk   "ui/player.html"    '灯效是氛围灯子项'   'class="row sub"'
chk   "ui/player.html"    '灯效段控件'          'id="segAmbMode"'
chk   "ui/player.html"    '原厂档'              "setAmbMode('factory')"
chk   "ui/player.html"    '多彩档'              "setAmbMode('multi')"
chk   "ui/player.html"    '灯效回显函数'        'function ambModeUI()'
# ★ 选「多彩」时总开关还关着 ⇒ 顺手打开，否则钩子等到天亮也没帧进来
chk   "ui/player.html"    '多彩顺手开总开关'   'AMB.on !== 1'
chk   "patch_amb.py"      'LedLight 钩子'       'Amb;->takeover(I)Z'
# ★ 开关不能是纯静态：player 进程一重启（换引擎 / 崩溃自愈 / 上电）就掉回关，
#   页面按服务端回显上色 ⇒ 看着像「开关自己跳了」。跟引擎开关一样走 SP。
chk   "$SRCS/Amb.java"    '七彩开关落 SP'       'SPHelper.setIntValue(Boot.app, K_ON'
chk   "$SRCS/Amb.java"    '不走 files flag'     'K_ON = "r1_amb7"'
# ---- pl-1.6.29：码率 / 格式（含电台）----
chk   "$SRCS/AudioInfo.java" '取数类存在'        'public final class AudioInfo'
chk   "$SRCS/AudioInfo.java" 'Exo Format 路'     'MultiExoPlayer'
chk   "$SRCS/AudioInfo.java" '读 Format 字段'    '"sampleMimeType"'
chk   "$SRCS/AudioInfo.java" 'URL 码率正则'      '/(\\d{2,3})[kK]\\.'
chk   "$SRCS/AudioInfo.java" 'HEAD 探测'         'setRequestMethod("HEAD")'
chk   "$SRCS/AudioInfo.java" 'Content-Type'      'getHeaderField("Content-Type")'
# ★ 在线歌的代理地址 /R/KW/657547062 没有扩展名，只找最后一个 '.' 会命中 IP 里的点
#   ⇒ 生成了一整串 "189:8100/R/KW/657547062" 当格式名（用户实测反馈）
chk   "$SRCS/AudioInfo.java" '点须在斜杠后'      'if (d < 0 || d < slash)'
chk   "$SRCS/AudioInfo.java" '扩展名长度闸门'    'ext.length() > 5'
chk   "$SRCS/WebSrv.java" 'status 带码率'        'm.put("br", ai.br)'
chk   "$SRCS/WebSrv.java" 'status 带格式'        'm.put("fmt", ai.fmt)'
chk   "ui/player.html"    '码率行元素'           'id="npQual"'
chk   "ui/player.html"    'readQual 函数'        'function readQual(m)'
chk   "ui/player.html"    'paintQual 调用'       'readQual(m); paintQual();'
# ---- pl-1.6.30：七彩三处修正（灯不亮的真因）----
# ★① 颜色砍掉 alpha：原厂与小飞传的都 0x00RRGGBB，HSVToColor 给的是 0xFFRRGGBB
chk   "$SRCS/Amb.java"    '逐颗写灯走 vivid'    'LedLight.set_color(1L << i, vivid(sHue[i], s, v));'
# ★② 原厂的灭灯帧（静音 / 总开关关着）不能再跟着写黑，用自驱亮度顶上
chk   "$SRCS/Amb.java"    '黑帧兜底自驱'         'write(sAutoHue, DEF_S, DEF_V, now);'
chk   "$SRCS/Amb.java"    '原厂总开关 key'       'K_FACTORY = "music_light_enable"'
# ★③ 自驱泵 + 诊断
chk   "$SRCS/Amb.java"    '不放歌不闪'           'return false;'
chk   "$SRCS/Amb.java"    '只在播歌时兜底'       'if (playingUi(now) && (now - sLastLiveTs) > BLIND_MS)'
chk   "$SRCS/Amb.java"    '诊断串'               'public static synchronized String probe()'
chk   "$SRCS/WebSrv.java" 'probe 分支'           '"probe".equals(v)'
chk   "$SRCS/WebSrv.java" 'status 带诊断'        'o.put("amb7d", Amb.probe())'
chk   "$SRCS/WebSrv.java" 'queue_all 长超时'     '"queue_all".equals(op)'
# ---- pl-1.6.31：在线搜索结果整批入队 ----
chk   "$SRCS/WebSrv.java" 'queue_online 长超时'  '"queue_online".equals(op)'
chk   "ui/player.html"    'queueAll 走三条路'    'act("queue_online"'
chk   "ui/player.html"    '随机批走 rand_at'     'act("play_rand_at", "i=0")'
chk   "ui/player.html"    '留空搜索洗牌'         '"&shuffle=1"'
# ★ 反代漏一个参数 = 功能静悄悄退化（8088 直连正常、8080 没变化）⇒ 逐条点名
chk   "$SRCS/WebSrv.java" '搜索转发 shuffle'    'q.get("shuffle")'
# ★ 进程重启后开关从 SP 读回来，泵也要跟着续上（否则「页面显示开、灯一动不动」）
chk   "$SRCS/Amb.java"    '重启后拉总开关'       'if (sOn && !sFactoryTried)'
chk   "$SRCS/Amb.java"    '总开关只拉一次'       'private static boolean sFactoryTried = false;'
chk   "$SRCS/Boot.java"   'attach 接七彩'        'Amb.boot();'
# ---- pl-1.6.32：多彩 = 氛围灯的子项（原厂 / 多彩 二选一），且多彩配色不出白 ----
# ★ 页面上不再是并列的「七彩模式」开关，而是氛围灯下面的一行段控件；
#   总开关关着时整段压暗（选了也不亮）。
chk   "$SRCS/Amb.java"    '最低饱和度'          'float MIN_S = 0.90f;'
chk   "$SRCS/Amb.java"    '最高亮度'            'float MAX_V = 0.88f;'
# ★ 彩度复检必须按**比例**判：用绝对值会把「音乐轻 ⇒ V 小」的段硬拉成永远满亮，
#   跟着音乐明暗走这件事就没了
chk   "$SRCS/Amb.java"    '彩度复检按比例'      'MIN_SAT_PCT * mx'
chk   "$SRCS/Amb.java"    'vivid 三道闸'        'private static int vivid(float hue, float s, float v)'
# ★ 钩子里只能调 native set_color，回调 setColor 会无限递归把播放进程打挂
chkNo "$SRCS/Amb.java"    '不回调 setColor'      'LedLight.setColor(1L << i'
chkNo "ui/player.html"    '不再拿毫秒相位对齐'  'var err = p - POS_SEC * 1000;'
# ---- pl-1.6.27：暂停即时反馈 / 电台不挂歌词封面 / 电台列表去高亮 ----
chk   "$SRCS/PCtl.java"   '主动暂停记账字段'    'private static volatile boolean sUserPaused = false;'
chk   "$SRCS/PCtl.java"   '起播清记账入口'      'static void noteResume()'
chk   "$SRCS/PCtl.java"   'pause 置记账'        'sUserPaused = true;'
chk   "$SRCS/PCtl.java"   'phase 认主动暂停'    'if (sUserPaused) return "idle";'
chk   "$SRCS/PCtl.java"   'stallMs 认主动暂停'  'if (sUserPaused) return 0;'
chk   "$SRCS/PCtl.java"   'playingUi 给页面用'  'public static boolean playingUi()'
chk   "$SRCS/PCtl.java"   'play 清记账'         '// pl-1.6.27：起播 ⇒ 主动暂停记账作废'
chk   "$SRCS/PCtl.java"   'stop 清记账'         '// pl-1.6.27：主动暂停记账一并作废'
chk   "$SRCS/PCtl.java"   'next 清记账'         '// pl-1.6.27：下一曲 ⇒ 清暂停记账'
chk   "$SRCS/PCtl.java"   'prev 清记账'         '// pl-1.6.27：上一曲 ⇒ 清暂停记账'
chk   "$SRCS/PCtl.java"   'playAt 清记账'       '// pl-1.6.27：指定曲目 ⇒ 清暂停记账'
chk   "$SRCS/PCtl.java"   '小智推列表清记账'    'noteResume();                  // pl-1.6.27：小智推列表开播'
chk   "$SRCS/PCtl.java"   '换 index 清记账'     'noteResume();                  // pl-1.6.27：换 index'
chk   "$SRCS/WebSrv.java" 'playing 走 playingUi' 'm.put("playing", PCtl.playingUi());'
chk   "$SRCS/WebSrv.java" '电台不给封面'        'm.put("curPic", "");'
chk   "ui/player.html"    '暂停乐观更新'        'PSTATE = 0; PH = "idle"; STALL = false;'
chk   "ui/player.html"    '暂停后拉真值校准'    'setTimeout(load, 800);'
chk   "ui/player.html"    '暂停作废锁相基准'    'POS_BASE_SEC = -1; POS_NEXT = 0;   // 增量锁相基准作废'
chkNo "ui/player.html"    '电台不再标红'        'on ? "正在收听" : "网络直播"'
chk   "ui/player.html"    '电台点击闪一下'      '"li rad hit"'
chk   "ui/player.html"    '电台切台给 toast'    'toast("正在切到「" + n + "」…");'
chk   "ui/player.html"    '签名不再含 curRadio' '"\u0003" + radioFilt;'
chk   "ui/player.html"    '电台不贴封面'        'im.removeAttribute("src"); im.className = ""; QPICKEY = ""; return;'
chk   "ui/player.html"    '电台隐藏歌词按钮'    'lrcBtn.style.display = QRADIO ? "none" : "";'
chk   "ui/player.html"    '电台不取歌词'        'if ($("lrcHd")) $("lrcHd").textContent = "电台没有歌词";'

# ---- pl-1.6.19：DLNA / AirPlay 状态检测补 IPv6 表 ----
# 本机实测（2026-10-19 读 /proc/net/*）：UDP 1900 只在 udp、UDP 5353 只在 udp6、
# TCP 8080 只在 tcp6 ⇒ 只查 IPv4 表必然漏 ⇒ AirPlay 会恒显示「关」。
chk "$SRCS/Svc.java"     'DLNA 查 udp6'       '"/proc/net/udp6"'
chk "$SRCS/Svc.java"     'AirPlay 查 tcp6'    '"/proc/net/tcp6"'
# ---- pl-1.6.20：外部引擎被停 → 5 秒有界补开（事件驱动，撤掉 45s 常驻巡检）----
# 触发点 = 原厂 EasyPlayer.shutdownAllExternalConnectPlayer()（停这三个引擎的唯一出口）
chk   "$SRCS/Boot.java"   '停机事件入口'        'onExternalShutdown'
chk   "$SRCS/Svc.java"    '5 秒观察窗口'        'REOPEN_WINDOW_MS'
chk   "$SRCS/Svc.java"    '窗口内 500ms 采样'   'REOPEN_GAP_MS'
chkNo "$SRCS/Svc.java"    '不再常驻轮询(45s)'   '45000'
# ---- pl-1.6.21：蓝牙三处隐患 ----
# ① reged 守卫挡住 getProfileProxy：首次 attach 时蓝牙多半没开（白问一趟），
#    之后 STATE_ON 再调 init 又被第一行 return 弹回 ⇒ 自己这条代理路径永远没被调用过。
#    修法 = 把取代理拆成 ensureProxy()，不受 reged 守卫。
# ② connect/disconnect 只读静态 sink 字段 ⇒ 页面还没刷过一轮时是 null
#    ⇒ 「A2DP 服务还没就绪」假阴性。修法 = 统一走 ensureSink() 兜底 + 10s TTL 重借。
# ③ found 栏按 v.bond 快照过滤已配对 ⇒ 「扫到→当场配对→没重扫」时同一台出两张卡。
#    修法 = 用此刻 bonded 集合过滤。
chk   "$SRCS/Bt.java"     'proxy 不受 reged 挡'  'private static void ensureProxy(Context c)'
chk   "$SRCS/Bt.java"     '连接前先兜 sink'      'BluetoothA2dpSink sk = ensureSink()'
chk   "$SRCS/Bt.java"     'sink 缓存 TTL'        'SINK_TTL_MS'
chk   "$SRCS/Bt.java"     '此刻 bonded 集合过滤' 'bondedAddrs.contains'
chk   "$SRCS/Bt.java"     '不收 AvrcpController' 'proxy instanceof BluetoothA2dpSink'
chkNo "$SRCS/Bt.java"     '不再用快照 bond 过滤' 'v.bond == BluetoothDevice.BOND_BONDED'
# ---- pl-1.6.18：歌词框收成 5 排（150px），留白同步 58px 保证居中 ----
chk "ui/player.html"     '歌词框 5 排高'       'max-height:150px'
chk "ui/player.html"     '留白同步'            '#lrcLines{padding:58px 0}'
chk "$SRCS/Ui.java"      '页面已随 HTML 重建'   'max-height:150px'
# ---- pl-1.6.17：封面时序（换歌立刻摘旧图 / 下载完才显示）+ 歌词框内滚动 ----
chk "ui/player.html"     '封面钥匙'            'QPICKEY'
chk "ui/player.html"     '换歌摘封面'          'im.removeAttribute("src")'
chk "ui/player.html"     '下载完才显示'        'im.onload = function ()'
chk "ui/player.html"     '歌词框内滚动'        'function lrcScroll('
chk "ui/player.html"     '不用 scrollIntoView' 'box.scrollTop = t'
chk "ui/player.html"     '框 relative 基准'    'position:relative'
chk "ui/player.html"     '用户翻词不抢滚'      'LRCUSR'
chk "ui/player.html"     '首尾可居中留白'      '#lrcLines{padding:58px 0}'
# ---- pl-1.6.16：封面 + 可折叠歌词（反代 /api/lrc） ----
chk "$SRCS/WebSrv.java"  '/api/lrc 反代'       'path.equals("/api/lrc")'
chk "$SRCS/WebSrv.java"  'lrc 透传 t/a'        '/api/lrc?t=" + enc(t) + "&a=" + enc(a)'
chk "ui/player.html"     '封面 img'            'id="npImg"'
chk "ui/player.html"     '歌词按钮'            'toggleLrc()'
chk "ui/player.html"     '歌词面板'            'id="lrcBox"'
chk "ui/player.html"     '折叠类 open'         '.lrcbox.open'
chk "ui/player.html"     '当前行高亮'          'function lrcTick('
chk "ui/player.html"     '取词走 /api/lrc'      'fetch("/api/lrc?t="'
chk "$SRCS/Ui.java"      '页面已随 HTML 重建'   'lrcBox'
# ---- pl-1.6.6：在线搜歌（8080 侧补齐） ----
chk "$SRCS/WebSrv.java"  'search 透传 online'  '&online="'
chk "$SRCS/WebSrv.java"  '在线类 op 长超时'    'fwdMs = 25000'
chk "ui/player.html"     '在线搜歌按钮'        'doSearchOnline()'
chk "ui/player.html"     '在线结果区'          'SRES_ON'
chk "ui/player.html"     '在线点播 play_online' 'act("play_online", p)'
# ---- pl-1.6.8：在线随机 100 首（反代透传 random/olimit + 新 op 长超时） ----
chk "$SRCS/WebSrv.java"  'search 透传 random'  '&random="'
chk "$SRCS/WebSrv.java"  'search 透传 olimit'  '&olimit="'
chk "$SRCS/WebSrv.java"  'play_artist 长超时'  '"play_artist".equals(op)'
chk "$SRCS/WebSrv.java"  'random_online 长超时' '"random_online".equals(op)'
chk "$SRCS/WebSrv.java"  'play_rand_at 长超时' '"play_rand_at".equals(op)'
chk "ui/player.html"     '留空走随机批'        'rnd ? "&random=1'
chk "ui/player.html"     '随机批点播'          'function playRandAt(j)'
chk "ui/player.html"     '随机批文案'          '在线随机 '
# ---- pl-1.6.4：可发现 = 60 秒临时窗口（连上/到点自动收回） ----
chk "$SRCS/Bt.java"      '可发现窗口时长'      'DISC_WINDOW_MS'
chk "$SRCS/Bt.java"      '连上即收回'          '设备已连上'
chk "$SRCS/Bt.java"      '到点自动收回'        '60 秒无设备连上，到点收回'
chk "$SRCS/Bt.java"      '手动立即收回'        '手动关闭'
chk "$SRCS/Bt.java"      '默认落 21'           'int mode = win ? SM_CD : SM_CONN;'
chk "$SRCS/Bt.java"      '倒计时上报'          'discLeft'
chk "$SRCS/Bt.java"      '账本已废弃'          '已废弃'
chk "ui/player.html"     '窗口倒计时显示'      '（" + o.discLeft + "s）'
chk "ui/player.html"     '窗口规则说明'        '60 秒临时窗口'
# ---- pl-1.6.3：蓝牙 scan mode kick 全拔 + 可发现偏好账本 ----
chk "$SRCS/Bt.java"      'scan mode 常量口径'  'SM_CD'
chk "$SRCS/Bt.java"      '恢复可发现偏好'      'applyDiscPref'
chk "$SRCS/Bt.java"      'STATE_ON 也恢复'     'applyDiscPref();'
chk "$SRCS/Bt.java"      'scanMode 原值上报'   'o.put("scanMode"'
chk "ui/player.html"     'scan 档位提示'        '· scan '
chk "ui/player.html"     '20 谁也不收提示'      '20 = 谁也不收'
chk "ui/player.html"     'airplay 记账说明'     '没有苹果设备也能验'
# ---- pl-1.4.0：蓝牙 / DLNA / AirPlay 开关 ----
chk "$SRCS/Bt.java"      '蓝牙开关'            'public static String setOn(boolean on)'
chk "$SRCS/Bt.java"      '扫描'                'startDiscovery()'
chk "$SRCS/Bt.java"      '配对 createBond'     'createBond()'
chk "$SRCS/Bt.java"      '取消配对反射'         'removeBond'
chk "$SRCS/Bt.java"      'A2DP 连接'           'sk.connect(d)'
chk "$SRCS/Bt.java"      '可发现反射 setScanMode' 'setScanMode'
chk "$SRCS/Bt.java"      '广播接收'            'ACTION_DISCOVERY_FINISHED'
chk "$SRCS/Svc.java"     'DLNA 字段'           'mDlnaPlayer'
chk "$SRCS/Svc.java"     'AirPlay 字段'        'mAirPortPlayer'
chk "$SRCS/Svc.java"     '开功能走 AIDL'        'startServer'
chk "$SRCS/Svc.java"     '关功能走 AIDL'        'stopServer'
chk "$SRCS/Svc.java"     '反射取 mIPlayer'      'mIPlayer'
chk "$SRCS/Svc.java"     '不杀进程'             '进程是功能的载体'
chk "$SRCS/Svc.java"     'DLNA 实际状态读内核'  'P_DLNA'
chk "$SRCS/Svc.java"     'DLNA 端口 1900'      '0x76C'
chk "$SRCS/Svc.java"     'AirPlay 端口 5002'   '0x138A'
chk "$SRCS/Svc.java"     '状态读 socket 表'    '/proc/net/udp'
chk "$SRCS/Svc.java"     '进程状态也报'         'hasProc(":DlnaPlayer")'
chk "$SRCS/Sys.java"     'hasProc 可见'        'static boolean hasProc('
chk "$SRCS/WebSrv.java"  '/api/bt 路由'        'path.equals("/api/bt")'
chk "$SRCS/WebSrv.java"  '/api/svc 路由'       'path.equals("/api/svc")'
chk "$SRCS/WebSrv.java"  'op=bt_scan'          '"bt_scan".equals(op)'
chk "$SRCS/WebSrv.java"  'op=dlna/airplay'     '"dlna".equals(op)'
chk "$SRCS/Boot.java"    '蓝牙初始化'           'Bt.init(app)'
chk "ui/player.html"     '蓝牙栏'              '>蓝牙 <'
chk "ui/player.html"     '外部播放源栏'         '>外部播放源 <'
chk "ui/player.html"     '蓝牙列表渲染'         'function btShow('
chk "ui/player.html"     'DLNA 开关按钮(单键翻转)'  "svcToggle('dlna')"
chk "ui/player.html"     '设备栏更名'           '>设备状态 <'
chk "ui/player.html"     'CPU 字段'            'id="kCpu"'
chk "ui/player.html"     '内存字段'            'id="kMem"'
chk "ui/player.html"     '温度字段'            'id="kTemp"'
chk "ui/player.html"     '存储字段'            'id="kDisk"'
chk "ui/player.html"     '拉 /api/device'      '"/api/device"'
chk "ui/player.html"     '清理按钮'            'doClean()'
chk "ui/player.html"     '设置页定时采集'       'if (curTab === "set") loadDev()'
chk "ui/player.html"     '手动刷新按钮'        'loadDev(1)'
# ---- pl-1.6.0：音量应用按钮 + 播放列表推流后恢复 ----
chk "$SRCS/PCtl.java"    '状态反射读取'         'getDeclaredField("mPlayerStatus")'
chk "$SRCS/PCtl.java"    '非 BUSY 重推恢复'     'private static boolean ensureBusy('
chk "$SRCS/PCtl.java"    '重推走 play(info)'    'p.play(info, true)'
chk "$SRCS/PCtl.java"    '推流后重推恢复'      '（重推恢复）'
chk "$SRCS/PCtl.java"    '远程源判据'          'onlineIsRemote'
chk "$SRCS/PCtl.java"    '停推流引擎交接'      'exitWaitingExternalConnect'
chk "$SRCS/PCtl.java"    '触发自动补开'        'Svc.autoReopenLater'
chk "$SRCS/Svc.java"     '手动意图账本'        'r1_svc_manual.txt'
chk "$SRCS/Svc.java"     '补开只看手动账本'    'manualOn(w)'
chk "$SRCS/Svc.java"     '开功能独立方法'      'static String openOnly'
chk "$SRCS/Bt.java"      '蓝牙手动记账'        'Svc.saveManual("bt"'
chk "$SRCS/Bt.java"      '蓝牙引擎补开'        'reopenEngine'
chk "ui/player.html"     '自动补开规则'        '手动关过的一律不自动补开'
chk "ui/player.html"     '音量拖动不生效'        'oninput="onVolDrag('
chk "ui/player.html"     '音量应用按钮'          'volApply()'
chk "ui/player.html"     '3 秒自动还原'          'setTimeout(volRevert, 3000)'
if [ "$FAIL" != "0" ]; then echo "静态自检未过，中止"; exit 1; fi

echo "=========== [2/8] javac：stub -> stubout（stub 不进 dex） ==========="
rm -rf stubout classes_$V out_$V smali_$V smali_build_$V
mkdir -p stubout classes_$V out_$V
MSYS2_ARG_CONV_EXCL="*" "$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$AJAR" -d stubout $(find stub -name "*.java") 2>&1 | head -20
echo "  stub class 数：$(find stubout -name '*.class' | wc -l)"

echo "=========== [3/8] javac：注入类 -> classes_$V ==========="
# ★ 坑：以前是 `javac ... | head -30` —— 管道的退出码是 head 的，javac 挂了脚本也看不出来，
#   白装一次机（pl-1.6.12 踩过）。改成落盘再取 RC。
MSYS2_ARG_CONV_EXCL="*" "$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$AJAR" -cp stubout -d classes_$V $(find plsrc -name "*.java") > javac_$V.log 2>&1
JRC=$?
head -40 javac_$V.log
if [ "$JRC" != "0" ]; then echo "javac 失败（rc=$JRC），中止"; exit 1; fi
N1=$(find classes_$V -name "*.class" | wc -l)
N2=$(find classes_$V -path "*com/phicomm*" -name "*.class" | wc -l)
echo "  产出 class：$N1（其中 com/phicomm 污染：$N2，必须为 0）"
if [ "$N2" != "0" ] || [ "$N1" -lt 5 ]; then echo "class 产物不对，中止"; exit 1; fi
# ★ javac 失败时 -d 目录里还留着上一次的 class，N1 照样好看 ⇒ 必须点名校验新类。
#   （pl-1.4.0 踩过：BluetoothProfile.A2DP_SINK 是隐藏常量，编译直接挂掉，
#    而脚本看着 14 个旧 class 就放行了，白装一次机。）
for c in Boot WebSrv PCtl Bt Svc Sys AudioInfo Amb; do
  if [ ! -f "classes_$V/cn/workbuddy/r1web/$c.class" ]; then
    echo "缺 $c.class（javac 没编出来），中止"; exit 1
  fi
done

echo "=========== [4/8] d8 -> dex ==========="
MSYS2_ARG_CONV_EXCL="*" "$JAVA" -cp "$R8" com.android.tools.r8.D8 --min-api 22 \
  --lib "$AJAR" --output out_$V $(find classes_$V -name "*.class" | tr '\n' ' ') 2>&1 | head -20
ls -l out_$V/classes.dex

echo "=========== [5/8] baksmali：dex -> smali_$V ==========="
MSYS2_ARG_CONV_EXCL="*" "$JAVA" -cp "$BCP" org.jf.baksmali.Main d out_$V/classes.dex -o smali_$V
echo "  smali 文件：$(find smali_$V -name '*.smali' | wc -l)"
chk "smali_$V/cn/workbuddy/r1web/Boot.smali" 'Boot 进了 smali' 'attach'
chk "smali_$V/cn/workbuddy/r1web/WebSrv.smali" 'WebSrv 进了 smali' 'devctl'

echo "=========== [6/8] 合并 smali 树 + 打钩子 ==========="
if [ ! -d smali_exo ]; then echo "缺 smali_exo（先跑 _prep_exo_tree.py）"; exit 1; fi
# ⚠ 坑：`cp -r smali_echo smali_build_$V` 在目标**已存在**时会拷成
#   smali_build_$V/smali_echo/ 子目录 ⇒ smali 编译时「类已 interned」报错。
#   所以先删干净、再 mkdir、最后用 `smali_echo/.` 的写法拷内容。
# 用 mv 挪走而不是 rm -rf：批量删除会被安全机制拦（构建中间产物没必要真删）
TS="$(date +%s)"
if [ -d smali_build_$V ]; then
  mv smali_build_$V smali_build_old_$TS || { echo "挪不动旧目录，中止"; exit 1; }
fi
if [ -d smali_build_$V ]; then echo "旧目录仍在，中止"; exit 1; fi
mkdir -p smali_build_$V
cp -r smali_exo/. smali_build_$V/
mkdir -p smali_build_$V/cn/workbuddy/r1web
cp -f smali_$V/cn/workbuddy/r1web/*.smali smali_build_$V/cn/workbuddy/r1web/
"$PY" patch_smali.py smali_build_$V || exit 1
# pl-1.6.22：把网络流引擎换成 ExoPlayer 2.18 ——
#   ① NetPlayerService 里默认 new_NetPlayer（Exo），SP r1_engine_mp=1 切回原厂 NetPlayer4
#      （pl-1.6.24 起网页「播放引擎」按钮切的就是这个开关）
#   ② new_NetPlayer 补 seek 覆写（落 MultiExoPlayer.seek）
"$PY" patch_exo_engine.py smali_build_$V || exit 1
"$PY" patch_seek_exo.py smali_build_$V || exit 1
# pl-1.6.23：pause 幂等 —— 停止态/出错态调 pause，引擎给 false 会被界面读成「ERR 暂停」，
#   其实「暂停一个没在播的东西」本就该算成功。只动 pause() 的失败尾巴，start() 不动。
"$PY" patch_pause_idem.py smali_build_$V/com/phicomm/speaker/player/model/new_NetPlayer.smali || exit 1
# 临时诊断开关（R1DBG=1）：给 PlayerBinderWrapper$2 里所有静默 catch(Exception)
# 插入 printStackTrace + Logger.d —— 用来抓 pause/start 返回 false 的真凶。
"$PY" patch_amb.py smali_build_$V/com/phicomm/speaker/player/light/LedLight.smali || exit 1
if [ -n "${R1DBG:-}" ]; then "$PY" patch_dbg_swallow.py smali_build_$V || exit 1; fi
# pl-1.4.0：蓝牙「连上就断」的原厂 bug —— 两处 kick 全拔（幂等，带标记）：
#   ① BluetoothPlayer.bootInit()  A2DP proxy 连上时把已连接设备统统 disconnect
#   ② BluetoothPlayer$2.run()      收 CONNECTION_STATE 广播后按 mConnectState
#      case 0/3 直接 disconnect 新连接 ← 主凶
"$PY" patch_bt_hold.py smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali smali_build_$V/com/phicomm/speaker/player/model/'BluetoothPlayer$2.smali' || exit 1
FAIL=0
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali" '蓝牙不再踢连接(bootInit)' 'bootInit 已空掉'
chk "smali_build_$V/com/phicomm/speaker/player/model/EasyPlayer.smali" '外部引擎停机钩子' 'Boot;->onExternalShutdown()V'
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer\$2.smali" '蓝牙不再踢连接(receiver)' 'r1web: 去掉 disconnect'
# pl-1.6.3：原厂把 scan mode 强设 SCAN_MODE_NONE(20) 的两处也拔掉
#   （手机连上就不可连 = 「只有可被搜到时才能连」的根因；startServer 的 23 保留）
"$PY" patch_bt_scan.py smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali smali_build_$V/com/phicomm/speaker/player/model/'BluetoothPlayer$2.smali' || exit 1
FAIL=0
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali" '不再强设 SCAN_MODE_NONE(disconnectDevice)' 'r1web: 不再强设 SCAN_MODE_NONE'
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer\$2.smali" '不再强设 SCAN_MODE_NONE(receiver)' 'r1web: 不再强设 SCAN_MODE_NONE'
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali" 'startServer 仍设 23' 'setScanMode(I)Z'
# pl-1.5.0：DLNA 推流同步 —— DlnaPlayer2.onPlayCommand 插桩，把手机推来的曲目
#   广播给主进程控制台（Ext 收 → music.ext；播不播另由 AIDL isPlaying 问真实引擎）
"$PY" patch_dlna_sync.py smali_build_$V/com/phicomm/speaker/player/model/DlnaPlayer2.smali || exit 1
chk "smali_build_$V/com/phicomm/speaker/player/model/DlnaPlayer2.smali" 'DLNA 推流同步钩子' 'DlnaHook;->onPush'
# pl-1.6.12：拖动进度条 —— 原厂 AIDL 里没有 seek，这里新增事务 0x15
#   （IPlayer$Stub 分派 → PlayerBinderWrapper$2 → AbsPlayer → NetPlayer4
#    → MultiMediaPlayer.seek → MediaPlayer.seekTo）；客户端那半在 PCtl.seekTo()
"$PY" patch_seek.py smali_build_$V || exit 1
chk "smali_build_$V/com/phicomm/speaker/player/IPlayer\$Stub.smali" 'Stub 分派 seek' ':sswitch_seek'
chk "smali_build_$V/com/phicomm/speaker/player/PlayerBinderWrapper\$2.smali" 'Wrapper 实现 seek' 'r1web: seek'
chk "smali_build_$V/com/phicomm/speaker/player/model/AbsPlayer.smali" 'AbsPlayer 默认 seek' 'r1web: seek'
chk "smali_build_$V/com/phicomm/speaker/player/model/NetPlayer4.smali" 'NetPlayer4 seek 落点' 'MultiMediaPlayer;->seek(I)I'
chk "$SRCS/PCtl.java" 'seek 客户端' 'TX_SEEK'
chk "$SRCS/WebSrv.java" 'op=seek 路由' '"seek".equals(op)'
# pl-1.6.13：拖动「把手跟手、条慢一拍又弹回去」—— paintPos 拖动期间强制画 PR_VAL
chk "$SRCS/Ui.java"       '拖动期间独占绘图' 'if (PR_DRAG) pos = PR_VAL'
# pl-1.6.14：松手后「跳回原处再跳回落点」—— seek 生效窗口，窗口内不采纳旧真值
chk "$SRCS/Ui.java"       'seek 生效窗口状态' 'function prWait()'
chk "$SRCS/Ui.java"       '窗口内锁死落点' 'else if (prWait()) pos = PR_SEEK_MS'
chk "$SRCS/Ui.java"       '窗口内不碰时钟' 'if (PR_SEEK_WAIT) {'
chk "$SRCS/Ui.java"       '窗口内冻住插值' 'if (PR_DRAG || prWait()) { POS_NEXT = 0; return; }'
chk "$SRCS/Ui.java"       '换曲作废窗口' 'PR_SEEK_WAIT = false; PR_SEEK_TILL = 0'
chk "$SRCS/Ui.java"       '把手同步过渡' 'transition:left .5s linear'
# pl-1.6.15：卡片信息去重 —— 专辑=歌名不显示；来源文本只留 npTag 一颗 pill
# （★ Ui.java 是 Java 字符串常量，引号已转义成 \"，chk 串也要用 \"）
chk "$SRCS/Ui.java"       '专辑=歌名不显示' 'm.curAlbum !== \"Radio\" && m.curAlbum !== m.curTitle'
chk "$SRCS/Ui.java"       'via 不再写第二遍' 'npMeta\").textContent = pm.join(\" · \");'
chk "$SRCS/Ext.java"     '外部源状态读真实引擎' 'Svc.playing'
chk "smali_build_$V/com/phicomm/speaker/player/util/Common.smali"     'attach 钩子'  'Boot;->attach'
chk "smali_build_$V/com/phicomm/speaker/player/model/EasyPlayer.smali" 'onPlay 钩子' 'Boot;->onPlay'
chk "smali_build_$V/com/phicomm/speaker/player/model/EasyPlayer.smali" 'onIndex 钩子' 'Boot;->onIndex'
if [ "$FAIL" != "0" ]; then echo "钩子自检未过，中止"; exit 1; fi
FAIL=0
chk "smali_build_$V/com/phicomm/speaker/player/NetPlayerService.smali" 'exo 引擎开关' 'r1web: exo 引擎'
chk "smali_build_$V/com/phicomm/speaker/player/NetPlayerService.smali" '切回 MediaPlayer 开关' 'r1_engine_mp'
chk "smali_build_$V/com/phicomm/speaker/player/NetPlayerService.smali" '开关走原厂 SP' 'helpers/SPHelper;->getIntValue'
chk "smali_build_$V/com/phicomm/speaker/player/NetPlayerService.smali" '走 new_NetPlayer' 'new_NetPlayer;-><init>(Landroid/content/Context;)V'
chk "smali_build_$V/com/phicomm/speaker/player/model/new_NetPlayer.smali" 'exo seek 覆写' 'r1web: seek (exo)'
chk "smali_build_$V/com/phicomm/speaker/player/model/new_NetPlayer.smali" 'exo seek 落点' 'MultiExoPlayer;->seek(I)I'
chk "smali_build_$V/com/phicomm/speaker/player/model/new_NetPlayer.smali" 'pause 幂等' 'r1web: pause 幂等'
chk "smali_build_$V/com/google/android/exoplayer2/ExoPlayerLibraryInfo.smali" 'Exo 2.18.0' '2.18.0'
chk "smali_build_$V/com/google/common/collect/ImmutableList.smali" 'guava 在' 'ImmutableList'
chk "smali_build_$V/com/phicomm/speaker/player/model/MultiExoPlayer.smali" 'MultiExoPlayer 在' 'MultiExoPlayer'
chkNo "smali_build_$V/com/phicomm/speaker/player/exomedia/AudioPlayer.smali" '旧 exomedia 已删' 'exomedia'
if [ "$FAIL" != "0" ]; then echo "exo 自检未过，中止"; exit 1; fi
echo "  合并后 smali：$(find smali_build_$V -name '*.smali' | wc -l)"

echo "=========== [7/8] smali -> bridge_$V.dex ==========="
rm -f bridge_$V.dex
MSYS2_ARG_CONV_EXCL="*" "$JAVA" -Xmx2g -cp "$SCP" org.jf.smali.Main a smali_build_$V -o bridge_$V.dex
ls -l bridge_$V.dex

echo "=========== [8/8] Janus 拼接 + 校验 ==========="
"$PY" "$R1/diag/pandora/janus_poc.py" --dex "$HERE/bridge_$V.dex" \
  --apk "$R1/EchoService_system.apk" --out "$HERE/$OUT" || exit 1
"$PY" - "$HERE/$OUT" <<'EOF'
import struct, zlib, hashlib, zipfile, sys
p = sys.argv[1]
d = open(p, 'rb').read()
print('  size', len(d), 'magic', d[:4])
ok = True
ok &= d[:4] == b'dex\n'
print('  dex magic     ', ok)
fs = struct.unpack('<L', d[32:36])[0] == len(d)
print('  file_size==总长', fs)
ad = struct.unpack('<L', d[8:12])[0] == (zlib.adler32(d[12:]) & 0xffffffff)
print('  adler32       ', ad)
sh = d[12:32] == hashlib.sha1(d[32:]).digest()
print('  sha1          ', sh)
z = zipfile.ZipFile(p)
print('  zip 条目       ', len(z.namelist()), 'testzip', z.testzip())
if not (ok and fs and ad and sh and z.testzip() is None):
    print('校验未全过！'); sys.exit(1)
print('  ★ 全部校验通过')
EOF
echo "=========== 完成：$OUT ==========="
ls -l "$OUT"
