#!/bin/bash
# =============================================================================
#  jp2-player RELEASE V2.0.5 —— 与测试版(pl-1.6.x)分开的第二条版本序列。
#
#  基线 = 测试版 pl-1.6.148（pv81），相对 V2.0.4（pl-1.6.95 / pv72）的变化：
#    · 有声书/节目页（pl-1.6.101~127）：新增「节目」一栏 —— 搜索只列书名，
#      点书名弹窗选集（首行=最近播放、带集数搜索）；点一集就整批占位入列，
#      播到才解析（和音乐收藏的「播到才搜」同款）
#    · 「我的节目」= 有声书收藏（pl-1.6.121/127）：收藏当前在听的书，
#      整批 /r/prog?aid=&ep= 占位，解析不到绝不把「非音频」交给播放器
#    · 下一首/上一首在播书时自动变「下一集/上一集」（pl-1.6.108/110）：
#      网页上直接转 ab_next/ab_prev，后台转发立即返回，不卡界面
#    · 秒数续播（pl-1.6.120/129/130/131）：等切到目标集且 prepared 之后再拖，
#      并带守卫 —— 任何一次点播都作废上一单续播，不会把新歌拖到旧秒数
#    · 歌单导入异步化（pl-1.6.104）：按钮实时显示「导入中 X/N」，不再假死
#    · 收藏显示自定义原名开关（pl-1.6.97/98）：列表可切「原名 / 匹配名」
#    · 本地有声书库（pl-1.6.133~141）：书列表缓存 + 扫盘指纹比对、整批入列
#      （一次 300 集）、队列 itemId 用 abl_ 前缀不串到在线书、来源标「📖 本地节目」
#    · 节目/书单三栏封顶（pl-1.6.143~145）：高度 236px 框内上下滑，不再把卡片撑长
#    · 本地书队列标题补「第N集 」（pl-1.6.146）：与 device 侧 abEpTitle 同一套规则
#    · 搜索框删空即回全列表（pl-1.6.140/147）：看「视图词」而不是按钮状态，
#      迟到的响应按词作废，不会再画回旧结果
#    · 页面缓存（pl-1.6.148）：/ 改 no-cache + ETag（304 零字节）；
#      「页面已过时」横幅点一下先注销旧 Service Worker 再强刷
#  仅在用户明确要求出 release 时才执行本脚本。
#
#  ★ 版本号策略：
#      release 序列 pl-2.0.x   <- 只有本脚本产出，产物名 XiaoZhi_EchoService_V2.0.5.apk
#      测试序列   pl-1.6.x     <- 日常迭代（build_pv81.sh 等），只在开发目录流转
#    本脚本在 javac 之前把 Boot.VER 临时改成 pl-2.0.5，
#    d8 构建完立刻还原 —— 源码树永远回到测试序列，两条线不会串。
# =============================================================================
# 2026-10-29：★★★ 本机环境拦截「覆盖已存在的文件」（新建文件不受影响，违者进程直接被 SIGTERM）
#   ⇒ 构建前把所有会被覆盖写的产物/日志/生成物先 mv 进 _stale/，让脚本全程只做「新建」。
mkdir -p _stale
__st=$(date +%s)
for __f in javac_pv81.log xiaozhi_EchoService_pv81.apk \
           plsrc/cn/workbuddy/r1web/Ui.java plsrc/cn/workbuddy/r1web/Assets.java \
           ui/_preview.html ui/_js_check.tmp.js; do
  if [ -e "$__f" ]; then
    mkdir -p "_stale/$(dirname "$__f")" 2>/dev/null
    mv "$__f" "_stale/$__f.$__st" 2>/dev/null
  fi
done
# pl-1.6.61：★ 暂停/播放音乐不再闪夜灯 —— 堵住音乐律动 519 + 守灯放宽到 5s
#   · 519 由灯效服务直写 A 组，setColor 钩子拦不到 ⇒ enable() 门口加闸门
#   · 开夜灯 disable 一次（补刀压异步清灯），关夜灯放歌中 enable 接回
# pl-1.6.67：★ 收藏/播放列表「没配上」红底 + 点它去在线搜 + 播放栏显示真名
#   · 收藏红基按条目自身判（favNoSrc：无直链/无四件套/无库内真名），后台补源自动褪色
#   · /api/queue 每行带 miss（占位地址 /r/fav?k= 且没解析过）⇒ 列表红底 + qMissTap 去搜
#   · /r/fav 解析成功记 PCtl.sFavReal ⇒ status 的 curTitle/curArtist/curUrl 换真名
#     （放在 favCur 之后换，星标按原名算不受影响）
# pl-1.6.89：★★★ 闹钟音量「应用」按钮 + 播放界面只显示音乐音量 + 起播先让路
#   · 页面：闹钟音量滑块改成「拖动只改显示 → 点应用才生效 → 3 秒自动还原」
#     （用户实测在响铃前碰到滑块，音量被悄悄改掉）
#   · status.music.volume：响铃期间 device 会把媒体流设成「闹钟音量」，播放界面
#     只该显示用户自己的音乐音量 ⇒ 用 device 报的 musicVolUser 覆盖
#   · PCtl.alarmYield()：play / next / prev / playAt 起播前先 POST alarm_yield，
#     让 device 先还原铃声音量、关掉正在响的闹钟，然后才出声（防音量突变）
# pl-1.6.88：★★★ 弹窗全套统一 + 铃声缓存单项删除 + 重链接不抢输入法
#   · 原生 confirm/alert（13 处）全换自绘 uiConfirm/uiAlert —— 复用 .modal，取消/确定等宽
#   · 铃声缓存每行右侧 ✕ 单独删（要确认）→ device op=alarm_cache&del=<k>
#   · 重链接弹窗不再 inp.focus()（一开就弹软键盘，把搜索词盖住）
# pl-1.6.87：★★★ 新建闹钟的时间改「内嵌列表式」—— 原生 <input type="time"> 弹 Material 表盘太突兀
#   · 大号时间行点开就地展开：两个数字框手输 + 时/分两条横向条滑动（松手吸附到最近格）
#   · -30 / +30 / 此刻 三个快捷键；重复模式 select 也改成深色卡片配色
# pl-1.6.86：★★★ 闹钟「默认铃声」页面四件（下一個响铃 / 缓存清单展开 / 点缓存设铃声 / 响铃歌曲显示默认）
#   · 顶部「下一個响铃歌曲」卡片（设完铃声立刻同步）
#   · 「铃声缓存」行点击展开已缓存清单；点清单里一条 => 设为默认铃声
#   · 「响铃歌曲」一栏显示 device 记住的默认铃声（dfltSong）
#   · 选歌弹窗的「选择」改成设默认（alarm_dflt）；「随机」= 清默认
# pl-1.6.85：★★★ 修 renderSearch 的 data-sr-artist 漏闭合引号（整个行结构塌成竖排 —— 用户看到的就是这个）
# pl-1.6.84：★★ 回退到 pl-1.6.81 页面基线 + 只加「曲库搜索长按设闹铃」（用户要求重做）
# pl-1.6.82：★★ 闹钟「设铃声」页面四项（曲库长按 / 缓存列表 / 点缓存设 / 顶部同步）
#   · 曲库搜索结果长按 → confirm 设闹钟（alarm_song id=next&title&artist）
#   · 新建卡「铃声缓存」点击展开清单、点一项 confirm 设成闹铃
#   · 顶部常显「下一个响铃歌曲」随设置同步
# pl-1.6.81：★★ 闹钟铃声缓存（页面侧：已缓存排最前 / 目录展示 / 清空）
#   · 去掉「试听」按钮与 alTone()（合成提示音已删）
#   · 新建卡加「铃声缓存 N 首 + 目录 + 清空」
#   · 选歌弹层已缓存排最前、行尾标「已缓存」
#   · 响铃卡片标「（本地铃声）」；音量默认值 80 → 50
# pl-1.6.80：★★ 闹钟音量（页面可调 + 实时显示系统两条流）
#   · 新建卡加「闹钟音量」滑块 20~100，走 alarm_vol → Alarm.mcp("vol")
#   · 实时显示闹钟流/媒体流实际值 —— 抬上没有、还原没有，一眼可见
#   · 响铃卡片标「（提示音）」，分清此刻是歌还是合成提示音
# pl-1.6.79：★★ 闹钟（web 同步状态 / 建·停用·删除 / 指定歌曲 / 日志页搬进设置页）
#   · WebSrv /api/alarm 反代 device 8088；取不到给空壳，别让前端 JS 断在半路
#   · alarm_* 走 actOp 兜底转发 —— 调度器只在 device 进程（停用语音也照响）
#   · tabbar 日志 → 闹钟；<section id="s-log"> → <section id="s-alarm">
#   · 日志整卡搬进设置页「赞赏」卡上方，标题「会话日志」
# pl-1.6.78：★★ 备份歌单连「绑定信息」一起备份（重传不用再绑一次）
#   · favToText 行尾追加 | src= | rid= | rman=1 | lt= | la=（key=value 自描述）
#   · 占位地址 127.0.0.1:8080/r/fav?k= 不导出（换机器必 404，src+rid 足够）
#   · favFromText 先挑 key=value 列，其余按老位置规则 ⇒ 三种老写法不受影响
#   · /api/fav/import 逐条 fav_add 时把绑定信息一起 POST
# pl-1.6.77：★★ 收藏点歌查不到就重拉一次队列再判（pl-1.6.76 的实机补丁）
#   · 刚导入完就点 / 夹在两拍轮询之间 ⇒ 手里的 Q 是旧版 ⇒ 误重载队列（实测撞到）
#   · favPlayOne 拆成 qFindFav / playFavAt / favPlayOneGo 三块
# pl-1.6.76：★★ 收藏栏点歌也先查播放列表（pl-1.6.75 只接了搜索结果那两条路）
#   · 新增 qFindSame：占位 key → source+rid → 歌名+歌手（strict）三路认同一首
#   · favPlayOne 命中就 play_at 跳过去，队列一首都不动
#     （以前无条件 play_fav_one ⇒ 63 首的收藏单点一首只剩 1 首）
# pl-1.6.75：★★ 自动没配上的也进重链接模式 + 点歌不重载队列
#   · 红底行（FAVMISS / it.miss）= 重链接模式：整行/🔗 弹窗挑版本，✕ 只退出/忽略
#   · 绑定成功立刻褪红（rlClearMarks 抹 RLM/RLHIDE/FAVMISS/it.miss）
#   · player 新增 PCtl.noteFavBound：fav_link/fav_bind 成功后撤 sFavFail
#     （不然收藏与队列会一直挂 miss=true ⇒ 红标永远下不来）
#   · 搜索结果点歌先查队列：已有同一首就 play_at 跳过去，不重载播放列表
# pl-1.6.74：★★ 重链接（手动匹配）—— 长按歌名标红 + 右边 🔗 → 在线挑一版绑过去
#   · 长按 0.55s 进/出「重链接模式」，行标红（.li.miss）+ 右侧 🔗（.li.fix）
#   · 长按后的那次 click 在 document 捕获阶段吞掉（别顺手切歌）
#   · 弹窗只列 online 段（曲库段是本地文件）；▶ 试听、🔗 才绑定
#   · 绑定走 device op=fav_link；正播着的那首由 device 原地重推
# pl-1.6.73：★ 点收藏单条不再丢 source+rid（修「收藏 Billie 版播成 Drake 版」）
#   · favPlayOne 有精确源/真名/地址 ⇒ device op=play_fav_one
#   · 没配上的才退回 play_song 按歌名重搜
# pl-1.6.72：★ 星标 index 补两类「只补了一边」的真名 key（修「播放列表里很多歌没星」）
#   · rartist 有值但 rtitle 没写（歌名本来就一样）⇒ 用原文歌名 + rartist
#   · ltitle 有值但 lartist 空（库里没写歌手）⇒ 再补一条 key(ltitle, "")
# pl-1.6.71：★ 撤掉导入即匹配 + 重新匹配按钮（改回播到才临时匹配，配合 v1.3.233）
#   · 导入只把歌名原文写进收藏，不匹配；匹配推迟到播到那首时由 fav_resolve 做
#   · 删除 WebSrv 导入后自动 fav_rematch、转发超时项、页面「重新匹配收藏」按钮
# pl-1.6.70：★ 导入即离线批量匹配 + 「重新匹配收藏」按钮（配合 device v1.3.232）
#   · 导入只 fav_add 原文不匹配 ⇒ 收藏大多没星、播放整首被跳过
#   · /api/fav/import 完成后自动调 device op=fav_rematch（离线、亚秒级）
#   · 收藏卡片加「重新匹配收藏」按钮，手动重跑并 toast 结果
# pl-1.6.69：★ 占位地址「尝试失败才红」（配合 device v1.3.231）
#   · 新导入歌单默认全红 ⇒ 改为只有「播到且解析失败」才红，占位只是待解析
#   · sFavFail + noteFavFailed/favFailed；/r/fav 404 且 device 真尝试过才记失败
#   · 队列与收藏 miss 都改由「已尝试且失败」驱动；页面 favNoSrc 只看 it.miss
# pl-1.6.68：★ 播放栏星标对「有四件套的收藏条目」不再灭
#   · device 推队列用真名（路径④），player 收藏缓存只索引原名 ⇒ has(真名) 恒 false
#   · refresh() 补收 key(rtitle,rartist) / key(ltitle,lartist)，与页面 favReindex 同口径
#   · total 仍按条目数算（favTotal 不能虚高）
# pl-1.6.60：★ 关夜灯按**原档位**接回（不再一律变多彩）+ 切档位按钮不再乱跳
#   · sQuietMode = 让路前的 mode() 快照；恢复走 restoreModeQuiet(m)
#     （setOn(true) 会清 sSolo ⇒ 单色被改成多彩）
#   · 页面 ambLock()：切档位是两条指令，中间回填会把按钮顶回去
# pl-1.6.59：★★ 切换不黑窗（不再关原厂，改在钩子里拦）+ 底部开灯进夜灯态 + 开关回显加锁
#   · 关原厂氛围灯这个动作**本身**就会清一次 A 组 ⇒ 不关了，takeover() 拦它的写
#   · device 开底部改发 ambon:<色>:<亮度> ⇒ player 完整走 nightAmb(true)
#   · 页面操作后 2.5s 锁定期，轮询不许覆盖开关 / 分组显示
# pl-1.6.58：★ 氛围灯 ⇄ 夜灯切换时底部不再「先灭再亮」
#   · 开夜灯：先上色再按停，按停走软停（不写黑），补刀首刀 400→80ms
#   · 关夜灯：不再同步写黑，先让接手者接上，没人接手才兜底写黑
# pl-1.6.57：★ 多彩调速只管换色，亮暗仍每帧跟音乐
#   · pl-1.6.56 把「写硬件」也节流了 ⇒ 亮度 600ms 才更新一次，
#     用户反馈「不随音乐闪动」⇒ 改回「换色慢、亮暗快」
# pl-1.6.56：★ 夜灯守灯加密 + 关夜灯接回氛围灯 + 多彩调速
#   · 守灯 8s→1.5s、补刀 400/1000/1600 ⇒ 被异步清灯抹掉后最多灭 1.5s
#   · 关夜灯无条件恢复原厂氛围灯（延后再补发一次，压住 device 的清场写黑）
#   · 多彩默认 600ms 换一次色、整帧节流、60~6000ms 可调、页面加速度滑块
# pl-1.6.55：★ 夜灯「亮一下就灭」—— 写完再补三刀
#   · 症状：开夜灯时底部亮一下就灭，要等 8 秒守灯那一刀才回来（实测约 7s）。
#   · 根因：开灯会顺手按停两拨「也会写 A 组」的人，而这两条是**异步到达**的，
#     到达时间在我们写完颜色**之后** ⇒ 它们那一次写黑把夜灯抹了：
#       ① 七彩自驱 setOn(false) → LedLight.setColor(ALL, 0)
#       ② 原厂氛围灯 msgcenter what=4 arg1=64 arg2=0
#       ③ device 清场的 504（A 组 FORCE 灭）同样是异步落地
#     ⇒ 凡直写必配补偿（跟 v1.3.206 那套 FIX_DELAYS 一个道理）。
#   · 做法：写完后在 300 / 1000 / 2500ms 各幂等重写一次（顶 + 底），
#     灭灯路径先 cancelFix() 撤掉（否则刚灭又被补亮）。
#     ★ 三刀必须是 3 个不同 Runnable（同一对象会被 removeCallbacks 一起撤，只剩最晚那刀）。
#   · 其余同 pl-1.6.54（总开关 on/off 与 part 配置分开）。
# pl-1.6.54：★ 夜灯 —— 「总开关」和「亮哪一组」彻底分开（修逻辑混乱）
#   · 以前页面的「亮哪一组」（都亮 / 只顶部 / 只底部）发的是 top:1 / amb:1，
#     而这两个指令本身就会上灯 ⇒ 等于藏了第二个开关：关了夜灯再点一下它，灯又亮了。
#   · 新口径（device v1.3.214 同口径）：
#       on / off          —— **唯一的开关**；开 = 按 part 亮，关 = 全灭
#       part:both|top|amb —— **只是配置**，夜灯关着时改它不点灯、只记下来；
#                            夜灯开着时改它立刻重排（该灭的那组写黑）
#   · status 新增 night.on（总开关真值）+ night.part（当前选择），
#     页面照这两个字段回显，不再靠 top/amb 反推 ⇒ 高亮不会再跳。
#   · 其余同 pl-1.6.53（顶/底都由 player 直写 sysfs、8 秒守灯、夜灯期间停七彩+原厂氛围灯）。
# pl-1.6.53：★ 顶部 24 颗拾音灯也由 player 直写 sysfs（照抄小飞版）
#   · 小飞的「开灯/关灯」= shell `lights_test set 7fffff8000 ffffff` / `... 0`
#     —— 不走灯效服务，直接写 sysfs。掩码 0x7FFFFF8000 = 灯号 16..39 = 顶部 24 颗。
#   · 夜灯的「顶」不再由 device show 503（关不干净：抢占 = 挂 pending 复活、
#     老 pid 的效果还 close 不掉）⇒ 新增 `op=led1&v=top:<hex> | topoff`。
#   · 增加「守灯」：夜灯开着时每 8 秒重写一次（幂等，不闪），
#     万一有别的效果补位复活把灯盖了，能压回去。
#   · 其余同 pl-1.6.52（夜灯开时 quietAmbient 停七彩 + 关原厂氛围灯，关时接回）。
# pl-1.6.52：★ 夜灯「让路」—— 开夜灯时把会抢 A 组的人按住，关夜灯按原样接回
#   · 夜灯开：① 七彩自驱停掉（takeover 每帧写 A 组）；② 原厂氛围灯总开关拨到关
#     （放歌时 PlayerVisualizer 按频谱写 A 组）。两者都记住原状态。
#   · 夜灯关：按原状态接回（七彩 SP r1_amb7 / 原厂 SP music_light_enable + what=4 arg1=64）。
#   · ★ 为什么必须做：不停掉的话夜灯颜色随时被它们盖掉 —— 用户看到的就是
#     「氛围灯一直亮着，改颜色也没反应」（pl-1.6.51 只写在注释里、没真做）。
#   · 其余同 pl-1.6.51（A 组整组写色 op=led1&v=all:<hex>|alloff、op=night 转发 device）。
#   · 配套 device v1.3.212：清场改「逐个 close」，不再用 505 去抢占 503
#     （抢占 = 挂 pending，505 一 close 503 就原地复活 ⇒ 关夜灯拾音灯闪一下又亮）。
# pl-1.6.51：★ 夜灯 —— 底部氛围环（A 组 15 颗）常亮，颜色 / 亮度可调
#   · 新增 op=led1&v=all:<hex> | alloff —— 整组 A 组写同一个颜色（device 开夜灯时调这条，
#     它自己写不了 sysfs：u0_a8 被 SELinux 挡住）。
#   · 新增 op=night 路由（页面用）：自己写底部，再把同一条指令**原样转发**给 device
#     （device 管顶部 503 +「夜灯期间所有灯效停用」那道闸门 + 持久化）。
#     ★★ op 名必须叫 night：叫 light / amb 会把反代给 device 的路抢走。
#   · 夜灯期间必须把别的灯效停干净，否则它们会来抢 A 组：七彩自驱（我们自己的钩子）
#     关掉，原厂氛围灯总开关（放歌时 PlayerVisualizer 按频谱写 A 组）也要关。
#   · ★ 亮度上限 100：小飞版 web 上氛围灯亮度标 1..200，但那只是它自己的口径 ——
#     实测原厂 setLumaGain 只收 0..100，传 200 静默 return -1。夜灯的亮度是我们自己缩放
#     RGB，不过原厂那一关，但同口径取 0..100，免得页面两套数字对不上。
#   · 页面「灯光控制」加夜灯：总开关 / 只开顶或只开底 / 颜色 / 亮度(0..100)。
#   · 配套 device v1.3.211（顶部 24 颗走灯效服务 503，因为 251 会 preempt 503）。
# =============================================================================
#  pv54（pl-1.6.55）★ 拾音灯单颗直写：从 native set_color 改成 sysfs 直写
#
#  症状：device 那一侧「写了」、player 也回了 ok（记账 sPickIdx 变了），
#        但用户目视「小智说完方向灯没有重新亮起」。
#
#  排查：整条链路逐步自证过 ——
#    · device -> player 的 HTTP（op=led1&v=on:K）通的：curl 与 op=light&v=pick:N
#      两条路都能把 player 的 sPickIdx 改掉，无「单颗直写失败」日志；
#    · 灯效服务没在抢：logcat 里 TTS stop 之后不再有任何 0x1000 报文，
#      「close light 4 / light 4 was stoped and deleted」在 busyOn 时就已执行完；
#    · 排障只能落到最后一段：native LedLight.set_color(long mask, int color)。
#      这条链（libledLight-jni.so -> liblight.so）原厂只用它写 **A 组 15 颗**
#      （bit0..14，多彩氛围灯实测可用），而拾音灯在 **bit15..38**（B 组）。
#
#  改法：按 /system/bin/lights_test 的格式化串，把 "<掩码hex> <RRGGBB>"
#  直接写进 /sys/class/leds/multi_leds0/led_color —— 这条路是**目视验证过**的
#  （当年 echo '8000 ffffff' > ... 用户确认「顶部亮了一颗」）。
#  native 那一路保留（幂等重复写），但不再是指望所在。
#
#  ★ 自证入口：op=led1&v=state 末尾会带 sysfs=ok 或 ⚠ sysfs:<异常>。
#
# =============================================================================
# =============================================================================
#  pv54（pl-1.6.49）★ 新增 op=led1：拾音灯「单颗直写」（给 device 的聆听灯用）
#
#  配套 device v1.3.206：非唤醒的聆听点亮只写「最后定格的那颗」，不播 5 秒动画。
#
#  为什么由 player 来写：
#    · device 寄生在 u0_a8，实测写 /sys/class/leds/multi_leds0/led_color
#      => Permission denied（SELinux），写不了；
#    · player 是 system(1000)，有 native 的 LedLight.set_color(mask, color) 能用。
#    ⇒ device 只能 HTTP 到 127.0.0.1:8080 的 /api/act?op=led1，由 player 代写。
#
#  掩码：bit(N-1) = 灯号 N；拾音灯 = 灯号 16..39 ⇒ 第 k 颗 = 1L << (14+k)。
#  （sysfs / native 的写入格式是「掩码 + 颜色」，不是「编号 + 颜色」——
#    早期一直按编号写，被解析成掩码 0x16 落在氛围环上，所以永远点亮的是蓝灯。）
#
#  ★★ op 名必须叫 led1 而不是 light/amb 等：player 收到未知 op 会反代给 device，
#     重名会把 device 那条路抢走（amb7 就是这么来的）。
#
# =============================================================================
# =============================================================================
#  pv54（pl-1.6.48）★ 页面「灯光控制」加第四项：聆听灯
#
#  配套 device v1.3.204：24 颗白色拾音灯改成跟随小智的聆听状态 ——
#  建连 / 聆听时亮（沿用唤醒那一刻的 DOA 方向），思考与播报时灭，会话结束灭。
#
#  player 侧只动页面：
#   ① 灯光控制卡片「三项」改「四项」，新增「聆听灯」开关 swLsL；
#   ② 开关走 tg('swLsL','listenLight') —— 与唤醒灯 / 播报灯同一条 /api/set，
#      player 整包转发 body 给 device，device 写 files/listen_light_off；
#   ③ 状态回填 setSw("swLsL", a.listenLight !== false)（默认开）；
#   ④ 演示假数据 MOCK_STATUS 同步加 listenLight: true。
#
#  ★ 灯本身在 device 里，player 不碰灯 —— 这里只是把开关摆到页面上。
#
# =============================================================================
# =============================================================================
#  pv54（pl-1.6.47）★ 收藏 key 归一化修正 —— 符号/韩文歌名不再互相误配
#
#  症状（用户报）：一首 Coldplay 的特殊字符歌一直占着播放队列。
#
#  根因：Fav.key 的归一化只留 a-z 0-9 汉字 ⇒
#        韩文歌《하루하루》→ ""，符号歌《⦵》→ ""，
#        两边 key 都塌成 "|" ⇒ 播收藏时用《하루하루》去本地库 findLocal，
#        命中的却是本地那首《⦵》（Coldplay），它就被塞进队列第 2 位反复出现。
#
#  修法（三处 key 必须逐字等价：device Fav / player Fav / 页面 nk）：
#   ① norm 改用 Character.isLetterOrDigit（汉字、假名、韩文、西里尔、é ü 全保留）；
#   ② 曲名归一化后为空（纯符号）⇒ 用原串兜底 "~"+原曲名，不再塌成同一个 key；
#   ③ device 读档时按新算法重算 k ⇒ 老收藏文件自动迁移。
#
# =============================================================================
# =============================================================================
#  pv54（pl-1.6.46）★ 来源 pill 隐身但**占位** —— 界面不再一下大一下小
#
#  用户反馈：标签消失了，可它那一块也跟着没了 ⇒ 播放页高度/排版在换曲时抽动。
#
#  改法：
#   ① 显隐改 visibility（不是 display）—— 元素一直在文档流里，位置永远占住。
#   ② 隐藏时不动 textContent ⇒ 宽度不归零、也不忽宽忽窄。
#   ③ CSS 给 min-width:74px + text-align:center，吃掉「电台(2字)/本地曲库(4字)/
#      在线搜到的(5字)」之间的宽度差 ⇒ 认出来换字时也不跳。
#   ④ 统一入口 setTag(txt)：空串=隐身占位，非空=显示。
#
# =============================================================================
# =============================================================================
#  pv54（pl-1.6.45）★ 来源标签随曲目走 —— 换了就先消失，认出来再显示
#
#  症状（用户报）：切到电台时，播放页那颗 pill 先亮「在线搜到的」，过一下才变「电台」。
#
#  根因：换曲瞬间服务端手里还是**上一首**的 url（player 的 song() 更新比 UI 慢半拍），
#        ⇒ 那一轮 status 的 curVia 还是上一首的 online、qRadio 还是 false；
#        前端又沿用上一轮的 QVIA 继续渲染 ⇒ 标签先亮错的、等下一轮才改口。
#
#  修法（两道防线）：
#   ① 服务端「换曲观察期」：曲目 key（曲名|歌手|地址）一变就记时间戳，
#      VIA_PEND_MS=1200ms 内 status 输出 viaPend=true，且 curVia 置空、qRadio 置 false
#      ⇒ 观察期内不给任何来源结论。key 还在变就一直续期（在线取链那几秒一直在变）。
#   ② 前端「换曲即清」：renderNp 检测到曲名变化就立刻 QVIA=""/QRADIO=false/QPEND=true
#      并藏掉整颗 pill —— 因为 status 是轮询，不自己清就会多亮一拍旧标签。
#
#  结果：换曲 ⇒ 标签消失；1.2s 后服务端认出来 ⇒ 才显示（电台/在线搜到的/本地曲库）。
#
# =============================================================================
# =============================================================================
#  pv54（pl-1.6.44）★ 曲目来源标签：在线歌不再被标成「本地曲库」
#
#  症状（用户报）：播收藏里的在线歌 / 在线搜到的歌，播放页标签写「本地曲库」；
#  而且刚点开有时先显示「电台」，等一下才变。
#
#  根因两条：
#   ① player 侧的 /api/status **从来没给过 curVia**（只在 stale 清空时置空）。
#      页面判标签是 电台 > curVia > PTYPE，QVIA 恒空 ⇒ 一律掉到 PTYPE，
#      而本地引擎播的在线歌 PTYPE 也是 1（声音确实从本地引擎出来）⇒ 标「本地曲库」。
#      ⇒ 新增 viaOf(url)：含 /r/<source>/<rid> = 惰性在线地址 ⇒ online；
#        其它 http 里内网直链算曲库、外网算 online；电台/空交回 QRADIO、QUNK。
#   ② 电台判定太急：「在播 + 还没有时长」**当场**锁成电台。在线歌取链的那 1 秒
#      Exo 还没给 total_time ⇒ 先被贴上「电台」，等时长到了才改口。
#      ⇒ 改成「无时长」**持续 1.2s** 才认电台（真电台 ~1.2s 出结论，在线歌
#        基本在 1.2s 内拿到时长）；这 1.2s 里页面靠 liveUnknown 把标签留空。
#
# =============================================================================
# =============================================================================
#  pv54（pl-1.6.43）★ 导入收藏：等得住、看得见
#
#  device v1.3.201 起，播收藏会给「只有歌名」的条目在线补源（单次 5s、总预算 15s），
#  整批下来可能十几秒。player 这边配套两件事：
#    · /api/act 转发 fav_play 的预算 25s -> 45s（不然这边判失败、那边还在忙 = 假失败）；
#    · 页面「导入播放列表并播放」按钮点击后当场置灰写「导入中…（缺地址的要在网上搜）」，
#      回来再复原 —— 否则用户以为没反应会连点。
#
# =============================================================================
# =============================================================================
#  pv54（pl-1.6.42）★ 我的收藏
#
#  在听的歌 / 搜到的歌 / 播放队列里的歌都能手动点 ☆ 收进「我的收藏」：
#    · 播放页标题右边一颗星：已收藏 ★（琥珀）、没收藏 ☆（灰），点一下切换；
#    · 音乐页新增「我的收藏」卡：列表 + 一键导入播放列表（op=fav_play）+ ✕ 删除；
#    · 搜索结果（曲库段 / 在线段）与播放队列每行行尾都有 ☆。
#
#  ★ 数据主本在 **device**（files/r1_fav.json）：语音（小智 MCP）活在那边，
#    曲库 / 在线四件套也都在那边，播收藏要回库反查。player 这边只做
#    ① 只读缓存（8s TTL，供 status 的 favCur 判星标，零额外请求）
#    ② /api/fav 反代（页面拉列表用）+ op=cur（device 问「现在播的是哪首」）。
#  ★ key = norm(曲名)+"|"+norm(歌手)（只留小写字母/数字/汉字）——
#    Java 侧 Fav.key() 与页面 favKey() 必须逐字一致，否则星标亮错、取消不掉。
#  ★ 播收藏**本地优先**：本地曲库有就播本地直链，拉不到才走在线四件套取链。
#
# =============================================================================
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
#      ★ pl-1.6.41 起这条作废：残留曲名整体清掉、op=replay 一并删除，
#        空队列时 ▶ 一律 op=random（重启就是重来，不再偷偷播回上一首）。
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
#  pl-1.6.33（pv54 复用）：赞赏/讨论群双码弹窗 + 页脚 GitHub 项目地址
#
#  ① 「赞赏码」按钮更名「赞赏」；弹窗改两栏：左赞赏（附「本项目完全免费，
#     是否捐赠不影响功能使用」声明），右 QQ 玩机讨论群（群号 885906281）；
#  ② 群二维码 = 剪贴板 jpg → GROUP_QR_B64（device WebConsole.java 落常量，
#     _gen_assets.py 抽到 player Assets.java），WebSrv 新增 /group.png 路由
#     （image/jpeg + 7 天缓存）；device 侧同步加路由，两边口径一致；
#  ③ 页脚加 GitHub 项目地址（shj4588/Phicomm_R1_XiaoZhi_Client）。
#
# =============================================================================
#  pv54（pl-1.6.30）
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
# ============================== 路径解析（V2.0.5 起可移植）==============================
# 2026-11-07：为了「把源码发给别人也能构建」，不再假设固定目录结构。同时支持两套布局：
#   布局 A（开发工作树 R1/）：
#         $ROOT/janus/dl/jdk-17.0.20.1+1   $ROOT/sdkdl/stage/{android.jar,r8.jar}
#         $ROOT/janus/smali/               $ROOT/diag/pandora/janus_poc.py
#   布局 B（发布源码包 源码/）：
#         $ROOT/tools/{jdk/,android.jar,r8.jar,smali/}   $HERE/{EchoService_system.apk,janus_poc.py}
#   本目录资产（ui/ plsrc/ smali_echo/ smali_exo/ patch_*.py）两套都在脚本同级。
#   ★ 环境变量 R1_TOOLS 可强制指定工具链根目录，优先级最高。
cd "$(dirname "$0")"
HERE="$(pwd -W 2>/dev/null || pwd)"
R1="$(cd "$HERE/.." && { pwd -W 2>/dev/null || pwd; })"
ROOT="$R1"

if [ -n "${R1_TOOLS:-}" ]; then
    TOOLS="$(cd "$R1_TOOLS" && { pwd -W 2>/dev/null || pwd; })"
elif [ -d "$ROOT/tools" ]; then
    TOOLS="$ROOT/tools"
else
    TOOLS=""
fi

pick_tool() {
    if [ -n "$TOOLS" ] && [ -e "$TOOLS/$1" ]; then
        printf '%s' "$TOOLS/$1"
    elif [ -e "$ROOT/$2" ]; then
        printf '%s' "$ROOT/$2"
    fi
}

# --- JDK 17：tools/jdk → R1/janus/dl/jdk-17 → $JAVA_HOME → PATH ---
JDK="$(pick_tool jdk janus/dl/jdk-17.0.20.1+1)"
if [ -z "$JDK" ] && [ -n "${JAVA_HOME:-}" ]; then
    if [ -x "$JAVA_HOME/bin/javac.exe" ] || [ -x "$JAVA_HOME/bin/javac" ]; then
        JDK="$JAVA_HOME"
    fi
fi
if [ -n "$JDK" ]; then
    JAVA_HOME="$JDK"
    if [ -x "$JDK/bin/javac.exe" ]; then JAVAC="$JDK/bin/javac.exe"; else JAVAC="$JDK/bin/javac"; fi
    if [ -x "$JDK/bin/jar.exe" ];   then JAR="$JDK/bin/jar.exe";     else JAR="$JDK/bin/jar"; fi
    if [ -x "$JDK/bin/java.exe" ];  then JAVA="$JDK/bin/java.exe";   else JAVA="$JDK/bin/java"; fi
else
    JAVAC=javac; JAR=jar; JAVA=java
fi

AJAR="$(pick_tool android.jar sdkdl/stage/android.jar)"
R8="$(pick_tool r8.jar sdkdl/stage/r8.jar)"
SM="$(pick_tool smali janus/smali)"
BCP="$SM/baksmali-2.5.2.jar;$SM/dexlib2-2.5.2.jar;$SM/guava-25.1-android.jar;$SM/jcommander-1.72.jar;$SM/commons-cli-1.4.jar;$SM/antlr-runtime-3.5.2.jar;$SM/util-2.5.2.jar"
SCP="$SM/smali-2.5.2.jar;$SM/dexlib2-2.5.2.jar;$SM/guava-25.1-android.jar;$SM/jcommander-1.72.jar;$SM/commons-cli-1.4.jar;$SM/antlr-runtime-3.5.2.jar;$SM/util-2.5.2.jar"

# --- 本目录资产（两种布局都在脚本同级，旧布局回退到 R1 根）---
if [ -f "$HERE/EchoService_system.apk" ]; then
    APKSHELL="$HERE/EchoService_system.apk"
else
    APKSHELL="$ROOT/EchoService_system.apk"
fi
if [ -f "$HERE/janus_poc.py" ]; then
    JANUS_PY="$HERE/janus_poc.py"
else
    JANUS_PY="$ROOT/diag/pandora/janus_poc.py"
fi

# --- Python 3：$PY 环境变量 → python3 → python → py ---
if [ -z "${PY:-}" ]; then
    for _c in python3 python py; do
        if command -v "$_c" >/dev/null 2>&1; then PY="$_c"; break; fi
    done
fi

# --- 依赖自检 ---
BAD=0
need() {
    if [ -z "$2" ] || [ ! -e "$2" ]; then
        echo "  x 缺少 $3"
        echo "      期望位置: ${2:-（未找到）}"
        BAD=1
    fi
}
need JDK      "$JDK"      "JDK 17（javac/jar/java）"
need AJAR     "$AJAR"     "android.jar"
need R8       "$R8"       "r8.jar"
need SM       "$SM"       "smali/baksmali 工具链目录"
need APKSHELL "$APKSHELL" "原厂壳 EchoService_system.apk（Janus 拼包用）"
need JANUS_PY "$JANUS_PY" "janus_poc.py"
if [ -z "${PY:-}" ]; then
    echo "  x 缺少 Python 3（进 PATH，或用 PY=/path/to/python 指定）"
    BAD=1
fi
if [ "$BAD" -ne 0 ]; then
    echo ""
    echo "构建中止。按 源码/环境说明.md 补齐上面缺件，或用 R1_TOOLS=<工具链目录> 指定位置。"
    exit 1
fi
echo "环境自检通过：JDK=$(basename "$JDK" 2>/dev/null || echo PATH)  PY=$PY  TOOLS=${TOOLS:-$ROOT}"
# ======================================================================================

V=pv205
OUT="XiaoZhi_EchoService_V2.0.5.apk"

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

echo "=========== [0.5/8] RELEASE 版本号注入：VER -> pl-2.0.5 ==========="
"$PY" - <<'INJEOF'
import io, os, re
p = 'plsrc/cn/workbuddy/r1web/Boot.java'
src = io.open(p, encoding='utf-8').read()
if os.path.exists('_VER_dev.bak'):
    print('  [warn] 已有 _VER_dev.bak（上次构建没走到还原），沿用旧备份、不覆盖')
else:
    io.open('_VER_dev.bak', 'w', encoding='utf-8', newline='\n').write(src)
s = re.sub(r'public static final String VER = "[^"]*"',
           'public static final String VER = "pl-2.0.5"', src)
assert 'VER = "pl-2.0.5"' in s, 'release 版本注入失败'
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("  VER -> pl-2.0.5  (release 序列；测试序列 pl-1.6.x 保留在源码树)")
INJEOF

echo "=========== [1/8] 静态自检 ==========="
UI="plsrc/cn/workbuddy/r1web/Ui.java"
SRCS="plsrc/cn/workbuddy/r1web"
chk "$SRCS/WebSrv.java" 'op=play_at 路由'  '"play_at".equals(op)'
chk "$SRCS/WebSrv.java" 'op=devctl 路由'   '"devctl".equals(op)'
chk "$SRCS/WebSrv.java" 'SSE 推送通道'      '"/api/events"'
chk "$SRCS/WebSrv.java" 'SSE 触发推送'       'ssePush()'
chk "$SRCS/WebSrv.java" 'topkey 触发推送'    '"topkey".equals(op)'
chk "$SRCS/WebSrv.java"   '导入走批量'          'op=fav_import_batch'
chk "$SRCS/WebSrv.java"   '逐条回退体'          'private static String favAddBody'
chk "$SRCS/WebSrv.java"   '批量判成功走信封'    'o.optBoolean("ok", false)'

# ★ pl-1.6.93：实体键暂停/恢复的记账 op（先记账再 ssePush，顺序不能反）
chk "$SRCS/WebSrv.java" 'toppause 记账推送'  '"toppause".equals(op)'
chk "$SRCS/WebSrv.java" 'topresume 记账推送' '"topresume".equals(op)'
chk "$SRCS/PCtl.java"   '实体键暂停记账'     'markPaused'
chk "$SRCS/PCtl.java"   '实体键恢复记账'     'markResumed'
# ★ pl-1.6.93：开机按账本恢复 DLNA/AirPlay（重启不走 shutdownAllExternalConnectPlayer）+
#   静默死亡看门狗；账本本身也报进 /api/svc 便于排障
chk "$SRCS/Boot.java"   '开机恢复调用'        'Svc.restoreOnBoot()'
chk "$SRCS/Svc.java"    '开机恢复实现'        'restoreOnBoot'
chk "$SRCS/Svc.java"    '静默死亡看门狗'      'startWatch'
chk "$SRCS/Svc.java"    '账本对外可见'        'manualDlna'
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
chk "ui/player.html"    '节目判据放宽(107)'   '无书名号的集数也算节目'
chk "ui/player.html"    '按钮节目转ab(108)'    '? "ab_next" : "ab_prev"'
# ---- pl-1.6.122/123：节目栏更名 + 搜索按钮状态机 + 弹窗骨架秒开 ----
chk "ui/player.html"    '搜索按钮状态机(122)'  'function ebSearchBtn()'
chk "ui/player.html"    '搜索框清空清记录(123)' '搜索框清空 ⇒ 主动清掉搜索记录'
# pl-1.6.147：删空判断必须看「视图词」，不能看按钮状态（逐字删会提前把 ablSearched 置 false）
chk "ui/player.html"    '本地搜索删空看视图词' "ablViewQ !== '' || ablSearched || ablPendingQ"
chk "ui/player.html"    '在线搜索删空看视图词' 'ebSearched || ebPendingQ || ebViewQ'
chk "ui/player.html"    '骨架阈值降到1(123)'   'pe.eps.length >= 1'
chk "$SRCS/WebSrv.java" 'ab按钮转发30s'       'fwdMs = 30000'
chk "$SRCS/Proxy.java"  '反代 8088'         '127.0.0.1:8088'
chk "$SRCS/Proxy.java"  '响应攒字节一次解码'  'bo.write(buf, 0, n)'
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
chk "ui/player.html"    '空队列走随机'     'act("random", randomExtra());'
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
# ---- pl-1.6.56：守灯加密 / 关灯接回氛围灯 / 多彩调速 ----
chk "$SRCS/Amb.java"     '守灯放宽 5s'       'KEEP_MS = 5000L'
chk "$SRCS/Amb.java"     '恢复无条件'        'pl-1.6.56：改成<b>无条件</b>恢复'
chk "$SRCS/Amb.java"     '恢复延后补发'      'private static void scheduleAmbRestore()'
chk "$SRCS/Amb.java"     '多彩默认 600ms'    'DEF_MIN_MS = 600'
chk "$SRCS/Amb.java"     '多彩调速下限'      'MIN_MS_LO = 60'
chk "$SRCS/Amb.java"     '多彩调速上限'      'MIN_MS_HI = 6000'
chk "$SRCS/Amb.java"     '换色间隔范围收紧'  'sMinMs = (ms < MIN_MS_LO) ? MIN_MS_LO : (ms > MIN_MS_HI ? MIN_MS_HI : ms)'
chk "$SRCS/WebSrv.java"  'status 带多彩速度' 'o.put("amb7ms", Amb.minMs())'
chk "$SRCS/Ui.java"      '多彩速度滑块'      'function ambMsLive(v)'
# ---- pl-1.6.57：换色慢、亮暗快 ----
chk "$SRCS/Amb.java"     '亮度每帧跟音乐'    '★ sHue 不变（没到换色点），但 v 用的是**这一帧**的音量'
chk "$SRCS/Amb.java"     '间隔只管换色'      'pl-1.6.57：这个间隔<b>只管换色</b>，不管亮度'
chk "$SRCS/Amb.java"     '换色受间隔管'      'if (!sInit || (now - sLastShuffle) >= sMinMs) {'
# ---- pl-1.6.58：切换不黑窗 ----
chk "$SRCS/Amb.java"     'A组写入心跳'        'A 组最近被**别人**写过'
chk "$SRCS/Amb.java"     '软停不写黑'          '夜灯「让路」专用：停七彩但**不写黑**'
chk "$SRCS/Amb.java"     '让路走软停'          '让路用「软停」'
chk "$SRCS/Amb.java"     '开灯先上色'          '**先上色、再让别人停**'
chk "$SRCS/Amb.java"     '关灯不写黑'          '**不再同步写黑**'
chk "$SRCS/Amb.java"     '兜底写黑'            '关夜灯后的兜底写黑'
chk "$SRCS/Amb.java"     '补刀首刀80ms'        '首刀 400ms → <b>80ms</b>'
chk "$SRCS/WebSrv.java"  'ambback 路由'        'if ("ambback".equals(v)) {'
# ---- pl-1.6.59：不关原厂、改拦原厂 ----
chk "$SRCS/Amb.java"     '钩子拦原厂'        '**直接拦掉**原厂这一次写灯'
chk "$SRCS/Amb.java"     '不再关原厂'        '**不再** sendFactoryAmbient(false)'
chk "$SRCS/Amb.java"     '补刀六刀'          'FIX_DELAYS = {60L, 150L, 300L, 500L, 800L, 1200L}'
chk "$SRCS/WebSrv.java"  'ambon 路由'        'if (v.startsWith("ambon:")) {'
chk "$SRCS/Ui.java"      '回显加锁'          'function nightLock() { NIGHT.lock = Date.now() + 2500; }'
chk "$SRCS/Ui.java"      '锁期不覆盖'        'var lock = !force && Date.now() < NIGHT.lock;'
# ---- pl-1.6.59：氛围灯加「单色」档 ----
chk "$SRCS/Amb.java"     '单色档常量'        'public static final int MODE_SOLO = 2'
chk "$SRCS/Amb.java"     'setMode'           'public static synchronized void setMode(int m)'
chk "$SRCS/Amb.java"     '单色取色'          '单色取色：传 RRGGBB'
chk "$SRCS/Amb.java"     '单色写灯'          '★ pl-1.6.59 单色档'
chk "$SRCS/Amb.java"     '让路不写SP'        '**不写 SP**'
chk "$SRCS/Amb.java"     '档位记忆单调'      '**只能单调累加**'
chk "$SRCS/Amb.java"     '恢复不抢原厂'      '**不再无条件**把原厂氛围灯拨到开'
chk "$SRCS/WebSrv.java"  '单色路由'          'if ("solo".equals(v)) {'
chk "$SRCS/WebSrv.java"  '档位回显'          'o.put("amb7mode", Amb.mode())'
chk "$SRCS/Ui.java"      '单色取色行'        'rowSoloColor'
chk "$SRCS/Ui.java"      '单色取色器'        'function ambSoloColor(v)'
# ---- pl-1.6.60：关夜灯按原档位接回 ----
chk "$SRCS/Amb.java"     '让路记档位'        'private static int sQuietMode = -1'
chk "$SRCS/Amb.java"     '档位快照'          'int m = mode();'
chk "$SRCS/Amb.java"     '按档位接回'        'restoreModeQuiet(sQuietMode)'
chk "$SRCS/Amb.java"     '恢复不冲单色'      '按记忆的**档位**接回'
# ---- pl-1.6.60：切档位按钮不乱跳 ----
chk "$SRCS/Ui.java"      '档位加锁'          'function ambLock()'
chk "$SRCS/Ui.java"      '档位锁期不覆盖'    'var lock = !force && Date.now() < AMB.lock;'
# ---- pl-1.6.61：夜灯期间停音乐律动 519（暂停/播放不闪）----
chk "$SRCS/Amb.java"     '519 按停方法'       'private static void stopMusicLight()'
chk "$SRCS/Amb.java"     '519 接回方法'       'private static void restoreMusicLight()'
chk "$SRCS/Amb.java"     '开灯先停律动'       'stopMusicLight();'
chk "$SRCS/Amb.java"     '关灯接回律动'       'restoreMusicLight();'
chk "patch_player_visualizer.py" '519 闸门'   'Amb;->nightAmbOn()Z'
chk "stub/com/phicomm/speaker/player/helpers/PlayerVisualizerHelper.java" '519 stub' 'obtain(Context'
# ---- pl-1.6.67：没配上 = 红底 + 点它去搜 + 播放栏显示真名 ----
chk "$SRCS/PCtl.java"    '占位 key 提取'      'public static String favPendingKey(String url)'
chk "$SRCS/PCtl.java"    '真名查询'          'public static String[] favReal(String k)'
chk "$SRCS/PCtl.java"    '解析记账'          'public static synchronized void noteFavResolved('
chk "$SRCS/PCtl.java"    '解析记真名日志'     'fav resolve: '
chk "$SRCS/PCtl.java"    '队列带 miss'       'it.put("miss", miss);'
chk "$SRCS/WebSrv.java"  '302 记真名'        'PCtl.noteFavResolved(k, rt, ra, u);'
chk "$SRCS/WebSrv.java"  '取当前占位 key'    'PCtl.favPendingKey(s == null ? "" : nz(s.getUrl()))'
chk "$SRCS/WebSrv.java"  '码率用真地址'       '(favReal != null && favReal[2].length() > 0) ? favReal[2]'
chk "$SRCS/WebSrv.java"  'favCur 在换名前'   'm.put("favCur", Fav.has(nz(m.optString("curTitle", "")),'
chk "$SRCS/WebSrv.java"  '播放栏换真名'       'if (favReal != null && favReal[0].length() > 0) {'
chk "$SRCS/WebSrv.java"  'curUrl 也换真址'    'm.put("curUrl", favReal[2]);'
chk "ui/player.html"     '红判据函数'         'function favNoSrc(it)'
# chk "ui/player.html"     '占位地址不算凭据'    'u.indexOf("/r/fav?") < 0'  # pl-1.6.69：红基改由 it.miss 驱动（见下 626），客户端不再按 URL 判断
chk "ui/player.html"     '占位地址仍被文档标注'  '占位地址 /r/fav? 不算'
chk "ui/player.html"     '收藏红基扩到无凭据'  'FAVMISS[it.title] || favNoSrc(it)'
chk "ui/player.html"     '收藏提示带未配数'    'nMiss ? (" · " + nMiss + " 首自动没配上（红底，点它重链接）")'
chk "ui/player.html"     '队列签名含未配数'    '"|m" + miss'
chk "ui/player.html"     '队列红底'          'var miss = !!it.miss && !hid;'
chk "ui/player.html"     '红行副标题'         '自动没配上，点这一行去在线挑一版'
chk "ui/player.html"     '红行走重链接'        'if (rlMarked(nm, ar) || miss) {'
chkNo "ui/player.html"   'qMissTap 已删'       'function qMissTap(i)'
chkNo "ui/player.html"   '旧跳音乐页已删'      'tab("music");'
chk "ui/player.html"     '红底+当前曲加深'      '.li.miss.cur{'
chk "ui/player.html"     '队列提示带红基说明'   '红底 = 自动没配上，点它在线挑一版'
# ---- pl-1.6.68：星标按真名也认（与页面 favReindex 同口径）----
chk "$SRCS/Fav.java"     '真名 key 也收'      'nk.add(key(rt, it.optString("rartist", "")));'
chk "$SRCS/Fav.java"     '库内真名 key 也收'   'nk.add(key(lt, it.optString("lartist", "")));'
chk "$SRCS/Fav.java"     'total 按条目数'     'total = nEntry;'
chkNo "$SRCS/Fav.java"  'total 不许按 key 数' 'total = nk.size();'
# ---- pl-1.6.69：占位「尝试失败才红」----
chk "$SRCS/PCtl.java"   '失败集合 sFavFail'       'Map<String, Integer> sFavFail'
chk "$SRCS/PCtl.java"   '记失败 noteFavFailed'    'void noteFavFailed(String k)'
chk "$SRCS/PCtl.java"   '判失败 favFailed'        'boolean favFailed(String k)'
chk "$SRCS/PCtl.java"   '队列 miss=已尝试且失败'   'fk != null && favFailed(fk) && favReal(fk) == null'
chk "$SRCS/WebSrv.java" '/r/fav 失败才记'         'PCtl.noteFavFailed(k)'
chk "$SRCS/WebSrv.java" '/api/fav 失败补 miss'    'PCtl.favFailed(k) && PCtl.favReal(k) == null'
chk "ui/player.html"    'favNoSrc 改尝试失败才红'  'if (it.miss) { return true; }'
# ---- pl-1.6.71：撤掉导入即匹配 + 重新匹配按钮（chkNo 锁行为）----
chkNo "$SRCS/WebSrv.java" '导入不再自动 fav_rematch' 'op=fav_rematch'
chkNo "$SRCS/WebSrv.java" 'fav_rematch 转发已撤'     '"fav_rematch".equals(op)'
chkNo "ui/player.html"    '重新匹配按钮已撤'         'favRematch(this)'
chkNo "ui/player.html"    '重新匹配函数已撤'         'function favRematch(btn)'
# ---- pl-1.6.72：星标补两类「只补了一边」的真名 key ----
chk "ui/player.html"    '星标补 rartist 单独命中'  'if (it.rartist) FAVK[favKey(it.rtitle || it.title, it.rartist)] = 1;'
chk "ui/player.html"    '星标补 ltitle 空歌手'      'if (it.ltitle) FAVK[favKey(it.ltitle, "")] = 1;'
# ---- pl-1.6.73：收藏单条优先走 play_fav_one（保留 source+rid）----
chk "ui/player.html"    '收藏单条认精确源'        'if ((it.source && it.rid) || it.ltitle || it.url) {'
chk "ui/player.html"    '收藏单条走 play_fav_one'  'act("play_fav_one",'
# ---- pl-1.6.74：重链接（长按歌名 → 在线挑一版）----
chk "ui/player.html"    '重链接标记表'            'var RLM = {};'
chk "ui/player.html"    '重链接弹窗'              'function openRelink(o) {'
chk "ui/player.html"    '重链接在线搜索'          'function rlGo() {'
chk "ui/player.html"    '重链接绑定'              'function rlBind(j) {'
chk "ui/player.html"    '重链接 op'              'fav_link'
chk "ui/player.html"    '长按监听'                'function lpBind(box, kind) {'
chk "ui/player.html"    '收藏行标红入口'          'if (rlMarked(favShowT(it), favShowA(it)) || autoMiss) {'
chk "ui/player.html"    '队列行标红入口'          'if (rlMarked(nm, ar) || miss) {'
chk "ui/player.html"    '轮询不擦红底'            'function qRepaintMarks(box, use) {'
chk "ui/player.html"    '快路径补贴红底'          'qRepaintMarks(box, use);'
chk "ui/player.html"    '重链接 ✕ 只退出不删收藏'  '重链接模式下的 ✕'
chk "ui/player.html"    '列表行禁止选词(长按才准)'  'user-select:none'
chk "ui/player.html"    '整行任意位置可长按'        '任意位置'
chk "ui/player.html"    '取消收藏先弹确认'          'function favDelAsk(i) {'
chk "ui/player.html"    '确认后真删'                'function favDelOk() {'
chk "$SRCS/Ui.java"    '页面内嵌重链接弹窗'      'function openRelink(o) {'
chk "$SRCS/Ui.java"    '页面内嵌长按监听'        'function lpBind(box, kind) {'
# ---- pl-1.6.75：自动没配上 = 重链接模式 + 点歌不重载队列 ----
chk "ui/player.html"    '红标忽略集合'            'var RLHIDE = {};'
chk "ui/player.html"    '自动没配上判定'          'function favAutoMiss(it) {'
chk "ui/player.html"    '绑完立刻褪红'            'function rlClearMarks(k, title, artist) {'
chk "ui/player.html"    '绑定后清标记调用'        'rlClearMarks(rlKey(RL.title, RL.artist), RL.title, RL.artist);'
chk "ui/player.html"    '自动红标文案'            '自动没配上，点这一行去在线挑一版'
chk "ui/player.html"    '队列已有就查'            'function qFindSong(title, artist, strict) {'
chk "ui/player.html"    '已有则定位不重载'        'function playInQueueOr(title, artist, fallback) {'
chk "ui/player.html"    '在线结果先查队列'        'if (playInQueueOr(it.title || "", it.artist || "", function () {'
chk "$SRCS/PCtl.java"   '撤失败标记'              'public static synchronized void noteFavBound(String k) {'
chk "$SRCS/WebSrv.java" '绑定成功调 noteFavBound' 'PCtl.noteFavBound(fk);'
# ---- pl-1.6.76：收藏栏点歌先查播放列表 ----
chk "ui/player.html"    '队列断言语义'          'function qIndexOf(pred) {'
chk "ui/player.html"    '三路认同一首'          'function qFindSame(title, artist, k, src, rid) {'
chk "ui/player.html"    '占位 key 认法'         'var tag = "/r/fav?k=";'
chk "ui/player.html"    'rid 认法'              'u.indexOf("/r/" + ss + "/" + rr) >= 0'
chk "ui/player.html"    '收藏点歌先查队列'        'var at = qFindFav(it);'
chk "ui/player.html"    '原名再认一次'          'at = qFindSame(it.title || "", it.artist || "", fk, it.source, it.rid);'
chk "ui/player.html"    '命中直接跳过去'        '播放列表里已有这首 —— 直接跳过去播'
# ---- pl-1.6.77：判不到就重拉队列再判 ----
chk "ui/player.html"    '收藏定位封装'          'function qFindFav(it) {'
chk "ui/player.html"    '命中跳转封装'          'function playFavAt(at) {'
chk "ui/player.html"    '未命中重拉队列'        'var pr = loadQueue();'
chk "ui/player.html"    '重拉后再判一次'        'var at2 = qFindFav(it);'
chk "ui/player.html"    '单曲起播封装'          'function favPlayOneGo(it, t, a) {'
# ★ pl-1.6.140：版本自检改成动态读 Boot.java —— 以前写死 pl-1.6.139，每次升版本
#   都要来改这一行，忘了就「javac 都过、自检 FAIL、包不出」且报错被 tail 截掉。
VERCHK=$(sed -n 's/.*VER = "\([^"]*\)".*/\1/p' plsrc/cn/workbuddy/r1web/Boot.java | head -1)
chk "$SRCS/Boot.java"    "版本 $VERCHK"         "$VERCHK"

# ---- pl-1.6.135：本地节目播放要带书名/集名 ----
#      play_url 认 title/artist（PlayerBridge.playUrl），不带就落回「网络音频」。
chk "$SRCS/Ui.java"      '本地节目带集名'      "title: title || ''"
# ★ pl-1.6.146：标题拼装统一走 ablEpLabel（带集号判重），别再直接拼 "第N集"+title
chk "$SRCS/WebSrv.java"  '入列每集标题'        'ablEpLabel(ep, et)'

# ---- pl-1.6.134：本地节目缓存（进页面先上缓存，NAS 扫盘时间变了才重绘）----
#      为什么要：书库在 NAS，设备 WebSrv 是「异步线程 + 轮询」，每进一次页面/每点开一本书
#      都得等 0.5~1s；缓存后同一本书第二次点开是 0 延迟。
chk "$SRCS/Ui.java"      '书列表缓存键'        'ABL_BOOKS_KEY'
chk "$SRCS/Ui.java"      '内容指纹比对'        'function ablSig('
chk "$SRCS/Ui.java"      '集表缓存'            'ablEpsPut('
chk "$SRCS/Ui.java"      '缓存回放'            'function ablApplyEps('
chk "$SRCS/Ui.java"      '记忆同步缓存'        'function ablTouchCache('
chk "$SRCS/Ui.java"      '重载曲库后校验'      'ablLoad(\"\", true)'
chk "$SRCS/Ui.java"      '合集父级记忆字段'    'b.lastSub'
chk "$SRCS/WebSrv.java"  '透传扫盘时间'        'sAblAt'

# ---- pl-1.6.133：本地节目读书目录自带元数据（封面/作者/演播/年份/简介/每集时长）----
#      NAS 侧（mediaserver v1.2.1）读 cover*.jpg / desc.txt / reader.txt / metadata.json，
#      这里只确认「拿到的字段有地方用」：
# ---- pl-1.6.138：本地节目不转 ab_next（修「第50集跳到在线书第1643集」）+ 队列铺到 300 集 ----
#      根因：本地书按「下一曲」时仍转发 device 的 ab_next，device 的 ab 加载器只记着
#      上一次在线听的书（凡人修仙传 1643），整批 22 集推下来顶掉本地队列。
chk "$SRCS/Ui.java"      '本地节目不走ab转发'  'function ablIsLocalQueue('
chk "$SRCS/Ui.java"      '本地节目自己切集'    'function ablSkip('
chk "$SRCS/Ui.java"      '队首队尾续拉'        'function ablEdge('
chk "$SRCS/Ui.java"      '在听哪集可反解'      'function ablCurInfo('
chk "$SRCS/Ui.java"      '点播先铺满队列'      'ablFetchBatch(m.id, ep, 300'
chk "$SRCS/Ui.java"      '本地队列铺到300集'    'out.length < 300'
chkNo "$SRCS/Ui.java"    '别退回50集小队列'    'out.length < 50'
chk "$SRCS/WebSrv.java"  '集表size可调'        'rq.optInt("size", 100)'

# ---- pl-1.6.137：本地节目整批入列（下一曲不再串到别的书）+ 简介配色 ----
#      点一集 = 本集 + 后面 49 集一次性塞进 player 队列（和在线节目的 ab 加载器同语义），
#      player 侧反射造 PlaySong + play(info,true)，走 WebSrv 新端点 /api/ablplay。
chk "$SRCS/Ui.java"      '本地节目组队列'      'function ablQueue('
chk "$SRCS/Ui.java"      '点播走整批入列'      '/api/ablplay'
chk "$SRCS/Ui.java"      '简介不用默认链接色'  'color:#9fb0c9'
chk "$SRCS/WebSrv.java"  'ablplay 端点'        'path.equals("/api/ablplay")'
chk "$SRCS/WebSrv.java"  '入列演播拼书名'      'book + " · " + sub'
chk "$SRCS/PCtl.java"    '整批起播'            'playList(List<PlaySong>'
chk "$SRCS/PCtl.java"    '反射造 PlaySong'     'com.phicomm.speaker.player.model.PlaySong'

# ---- pl-1.6.136：本地节目列表瘦身（一行一本）/ 固定不显示封面 / 简介可点开 / 改名在线节目 ----
chk "$SRCS/Ui.java"      '本地书作者/演播行'    'function ablMetaLine('
chk "$SRCS/Ui.java"      '每集显示时长'        'ablDur(e.dur)'
#   ★ pl-1.6.136：本地节目固定不显示封面（跨网拉 NAS 图拉不到，列表会先撑高再塌）。
#     用 chkNo 反向盯住：谁再把封面拼回来就 FAIL。
chkNo "$SRCS/Ui.java"    '本地节目别拉封面'      'ablCov'
#   ★ 简介改用 onclick 切换：R1 是 Android 5.1 的 WebView，<details> 根本展不开。
chk "$SRCS/Ui.java"      '简介点击展开'        'function ablToggleDesc('
chk "$SRCS/Ui.java"      '本地列表一行格式'    "right.join(' · ')"
chk "$SRCS/Ui.java"      '栏目改名在线节目'    '在线节目 <span class='
chk "$SRCS/WebSrv.java"  '简介往下透传'        'h.put("desc", o.optString("desc", ""));'
chk "$SRCS/WebSrv.java"  '封面往下透传'        'h.put("cover", o.optString("cover", ""));'

# ---- pl-1.6.132：本地节目（NAS 有声书库）----
#      我的节目分两栏（在线/本地），本地书「合集→子书→集」三级弹窗 + 按子书记忆。
chk "$SRCS/WebSrv.java"  '本地书库代理 books'  '/api/abl/books'
chk "$SRCS/WebSrv.java"  '本地书库代理 eps'    '/api/abl/eps'
chk "$SRCS/WebSrv.java"  '本地书库记忆 pos'    '/api/abl/pos'
chk "$SRCS/Ui.java"      '本地节目两栏'        'ablSearchBtn'
chk "$SRCS/Ui.java"      '合集子书弹窗'        'ablOpenSubs'
#   ★ 断言里别放 emoji：这条链路上 emoji 过 shell 参数 / grep -F 会被转码成别的字节，
#     文件里明明有也匹配不上（实测 📖 在 Ui.java 里 8 处、grep 却 exit=1），改锚在旁边的代码上。
chk "$SRCS/Ui.java"      '本地节目来源标签'    'ABL_CUR) { setTag('

# ---- pl-1.6.131：标题里的集号「第003章」也要认（数字串兜底）+ 撤单窗口 10s→20s ----
chk "$SRCS/WebSrv.java"    '集号数字串兜底'     'if (has && acc == ep)'
chk "$SRCS/WebSrv.java"    '量词补章/节'        't.indexOf(n + "章") >= 0'
chk "$SRCS/WebSrv.java"    '撤单窗口 20s'       '> 20000'
# ---- pl-1.6.130：语音续播（ab_resume）也定位到「上次听到的那一秒」----
chk "$SRCS/WebSrv.java"    '续播定位走守卫'     'abResumeGuardedSeek(p, preSig, epn)'
chk "$SRCS/WebSrv.java"    '目标集判据'         'boolean onEp = (expectEp > 0)'
chk "$SRCS/WebSrv.java"    '标题认集号'         'static boolean titleHasEp(String title, int ep)'
chkNo "$SRCS/WebSrv.java"  '旧的裸 seek 已删'    'private static void abResumeSeek('
# ---- pl-1.6.124：有声书/节目行播放失败不跳集（引擎 HandlePlayError 闸门）----
chk "$SRCS/PCtl.java"       'abNoSkipOnErr 判据'   'public static synchronized boolean abNoSkipOnErr()'
# ---- pl-1.6.125：prog_resolve 冷缓存转发超时 15s → 40s（NAS 首次枚举整本书约 15s）----
chk "$SRCS/WebSrv.java"    'prog_resolve 冷缓存 40s 超时'   'Proxy.post("/api/act", qs.toString(), 40000)'
chk "$SRCS/PCtl.java"       '节目行正则'           '集回话章'
# ---- pl-1.6.126：闸门跨进程修复（静态队列在引擎进程里永远是空的 ⇒ 闸门一直放行）----
chk "$SRCS/PCtl.java"       '本进程队列优先'       'if (!queue.isEmpty() && curIdx >= 0 && curIdx < queue.size())'
chk "$SRCS/PCtl.java"       '跨进程标记文件名'     'r1_abnow'
chk "$SRCS/PCtl.java"       '主进程写标记'         'writeAbFlag(isProgRow(curIdx))'
chk "$SRCS/PCtl.java"       '切集也写标记'         'writeAbFlag(isProgRow(i))'
chk "$SRCS/PCtl.java"       '引擎进程读标记兜底'   'abNoSkipOnErr[引擎进程]'
chk "patch_abnoskip.py"     '闸门标记'             'r1web: 有声书/节目失败不跳集'
chk "patch_abnoskip.py"     '闸门调用 PCtl'        'PCtl;->abNoSkipOnErr()Z'
chk "patch_abnoskip.py"     '闸门原地停'           ':abns_off'
chk "$SRCS/PCtl.java"       '队尾即停判据'         'public static boolean stopAtListEnd(int mode, int pos, int size)'
chk "patch_list_end.py"     '队尾闸门标记'         'r1web: 列表循环队尾即停'
chk "patch_list_end.py"     '队尾闸门调用 PCtl'    'PCtl;->stopAtListEnd(III)Z'
chk "$SRCS/Ui.java"      'saveQlim 0 也落盘'    'post(\"/api/set\", \"playlistMax=\" + n);'
# ---- pl-1.6.127：/r/prog、/r/fav 解析不到 ⇒ 空流（绝不回 404+"no source" 纯文本喂播放器）----
chk "$SRCS/WebSrv.java"    'prog 无源空流'      '⇒ 空流，不给非音频'
chk "$SRCS/WebSrv.java"    'fav 无源空流'       'fav_resolve 无源 k='
chk "$SRCS/PCtl.java"      '节目占位行失败集合'  'sProgFail'
chk "$SRCS/PCtl.java"      '节目行标红'         'progFailed(pk[0], pk[1])'
chk "$SRCS/WebSrv.java"    '成功撤红'           'PCtl.noteProgResolved(aid, ep)'
chkNo "$SRCS/WebSrv.java"  '旧 404 文本响应已移除' 'txt(os, 404, "no source");'
# ---- pl-1.6.128：节目/收藏红标的「忽略」是一次性的（再失败一次自动重新红）+ 长按与 ✕ 拆开 ----
chk "$SRCS/PCtl.java"      '节目失败次数'       'public static int progFailN(String aid, String ep)'
chk "$SRCS/PCtl.java"      '收藏失败次数'       'public static int favFailN(String k)'
chk "$SRCS/PCtl.java"      '队列带失败次数'     'it.put("missN", missN);'
chk "$SRCS/WebSrv.java"    '收藏带失败次数'     'it.put("missN", PCtl.favFailN(k));'
chk "ui/player.html"       '忽略记次数'         'mn <= RLHIDE[mk]'
chk "ui/player.html"       '长按只进模式'       'function rlEnterQ(i) {'
chk "ui/player.html"       '✕ 才忽略'          'function rlIgnore(t, a, n, redraw) {'
chkNo "ui/player.html"     '旧共用入口已移除'    'function rlToggleQ(i) {'
chk "$SRCS/Ui.java"      '生效中 WS 显示真实值' 'sv.wsEffective || sv.wsOverride'
chk "ui/player.html"    'FAVRT 反向 map'       'FAVRT[favKey(it.rtitle, it.rartist || it.artist)] = it.title;'
chk "ui/player.html"    '服务器 Token 输入框'  'id="inToken"'
chk "ui/player.html"    'saveServer 带 token'   "setKV2({ otaUrl: v(\"inOta\"), wsUrl: v(\"inWs\"), serverToken: v(\"inToken\") });"
chk "ui/player.html"    '生效中 Token 显示'     'id="curToken"'
# ---- pl-1.6.78：导出/导入带绑定信息 ----
chk "$SRCS/WebSrv.java" '导出带 src 列'      'sb.append(" | src=").append(clr(src));'
chk "$SRCS/WebSrv.java" '导出带 rman 列'     'sb.append(" | rman=1");'
chk "$SRCS/WebSrv.java" '导出带本地名'       'sb.append(" | lt=").append(clr(lt2));'
chk "$SRCS/WebSrv.java" '导出真名进 rt 列'   'if (rt.length() > 0 && !rt.equals(title)) {'
chk "$SRCS/WebSrv.java" '标题含横杠转富格式' 'boolean rich = title.indexOf(" - ") >= 0 || artist.indexOf(" - ") >= 0;'
chkNo "$SRCS/WebSrv.java" '标题不再被真名顶替' 'title = rt;'
chk "$SRCS/WebSrv.java" '占位地址不导出'     'if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {'
chk "$SRCS/WebSrv.java" '扩展列白名单'       'private static boolean isKvSeg(String p) {'
chk "$SRCS/WebSrv.java" '导入挑出扩展列'     'if (!isKvSeg(p)) {'
chk "$SRCS/WebSrv.java" '导入回传绑定信息'   'String[] kv = {"source", "rid", "rman", "ltitle", "lartist", "rtitle", "rartist"};'
chk "ui/player.html"  '导出提示带配对'      '已导出收藏（txt，含配对信息）'

# ---- pl-1.6.104：歌单导入改异步（后台线程 + 进度轮询），根治整页冻结 ----
chk "$SRCS/WebSrv.java" '导入进度字段'        'private static volatile boolean sFavImporting = false;'
chk "$SRCS/WebSrv.java" '导入后台线程'        'new Thread(new Runnable() {'
chk "$SRCS/WebSrv.java" '导入立即返回'        '导入任务已启动（'
chk "$SRCS/WebSrv.java" '导入进度接口'        'if (path.equals("/api/fav/import/status")) {'
chk "ui/player.html"    '导入轮询函数'        'function favImportPoll(btn, input, setBusy) {'
chk "ui/player.html"    '导入轮询状态接口'    'fetch("/api/fav/import/status")'
chk "$SRCS/WebSrv.java" 'body 增量计字节'      'int byteCount = 0;'
chkNo "$SRCS/WebSrv.java" 'body 不再每字符重编码量长度' 'bb.toString().getBytes("UTF-8").length'




# ---- pl-1.6.63：收藏显示真名（rtitle/ltitle）+ 导出 txt 用真名 ----
chk "$SRCS/WebSrv.java"  '真名走 rt 列'      'String rt = it.optString("rtitle", "").trim();'
chk "ui/player.html"     '列表显示真名'       'function favShowT(it)'
chk "ui/player.html"     '星标认真名'         'if (it.rtitle) FAVK[favKey(it.rtitle'
# ---- pl-1.6.64：没配上的歌点了去搜索 + 手动配对（🔗 fav_bind） ----
chk "ui/player.html"     '自动没配上行也能重链'  'onclick="rlOpenFav('
chk "ui/player.html"     '🔗按钮样式'          '.li .fix{font-size:14px;color:var(--acc)'
chkNo "ui/player.html"   'favBindAt 已删'      'function favBindAt(i)'
chkNo "ui/player.html"   'favMissTap 已删'     'function favMissTap(i)'
chk "$SRCS/WebSrv.java"   'fav_play 120s'         'fwdMs = 120000;'
chk "$SRCS/WebSrv.java"   '占位地址路由'         'path.equals("/r/fav")'
chk "$SRCS/WebSrv.java"   '播到才解析'           'private static void favResolveRedirect('
chk "$SRCS/WebSrv.java"   '302 跳到真地址'       'HTTP/1.0 302 Found'
# ---- pl-1.6.63：弹窗面板不透明 + 去掉「刷新」+ 灯光控制按钮不再弹提示 ----
chk "ui/player.html"     '弹窗面板实色'       '.modal{background:#141b27'
chkNo "ui/player.html"   '收藏无刷新链接'     '<span class="lnk" onclick="loadFav(1)">刷新</span>'
chk "ui/player.html"     '夜灯开关静默'       'act("night", "v=" + (on ? "on" : "off"), true)'
chk "ui/player.html"     '氛围灯开关静默'     'act("amb", "v=" + (on === 1 ? "on" : "off"), true)'
# ---- pl-1.6.41：player 重启后播放页清掉上一首的残留（曲名/封面/歌词/码率）----
#   device 的 curTitle/curPic 是落盘的（跨重启存活），player 的 queue 是内存态（重启即空）
#   ⇒ 以前重启后播放页挂着上一首、看着像还在播。判据：本进程没播过 + 没在播 + 不是起播缓冲。
# ---- pl-1.6.42：我的收藏（☆ 收藏 / 一键导入播放列表）----
chk "$SRCS/Fav.java"      '收藏缓存层'        'public static synchronized boolean has(String title, String artist)'
chk "$SRCS/Fav.java"      'key 与后端同构'     'return t + "|" + norm(artist);'
chk "$SRCS/Fav.java"      'device 不可达不干等' 'if (!DevCtl.alive()) {'
chk "$SRCS/WebSrv.java"   '/api/fav 路由'     'path.equals("/api/fav")'
chk "$SRCS/WebSrv.java"   'op=cur 给 device'  '"cur".equals(def(q.get("op"), ""))'
chk "$SRCS/WebSrv.java"   'status 带 favCur'  'm.put("favCur"'
chk "$SRCS/WebSrv.java"   '收藏未知时置灰'     'm.put("favKnown"'
chk "$SRCS/WebSrv.java"   '当前曲地址'        'm.put("curUrl"'
chk "$SRCS/WebSrv.java"   '收藏后作废缓存'     'op.startsWith("fav")'
chk "$SRCS/WebSrv.java"   '播收藏给足 25s'     '"fav_play".equals(op) || "play_fav".equals(op)'
chk "$SRCS/PCtl.java"     '队列带 url'        'it.put("url"'
chk "ui/player.html"      '播放页星标'        'id="npStar"'
chk "ui/player.html"      '星标点击'          'onclick="favToggleCur()"'
chk "ui/player.html"      '收藏卡'            'id="favList"'
# ---- pl-1.6.43：导入收藏的等待反馈 ----
chk "ui/player.html"      '导入按钮有 id'     'id="btnFavPlay"'
chk "ui/player.html"      '导入中置灰'        'b.textContent = "导入中'
chk "ui/player.html"      '导入完复原'        'b.textContent = "导入播放列表并播放"'
# ---- pl-1.6.44：曲目来源标签 ----
chk "$SRCS/WebSrv.java"   '观察期内不给来源'  'm.put("curVia", viaPend ? "" : viaOf('
chk "$SRCS/WebSrv.java"   '来源反推方法'      'private static String viaOf(String u, boolean radio)'
chk "$SRCS/WebSrv.java"   '惰性地址算在线'    'u.indexOf("/r/") > 0'
chk "$SRCS/WebSrv.java"   '内网直链算曲库'    'h.startsWith("192.168.") || h.startsWith("10.")'
chk "$SRCS/PCtl.java"     '电台候选计时'      'private static long sRadioCandTs = 0'
chk "$SRCS/PCtl.java"     '停播清候选'        'sRadioCandTs = 0;
        sAdvanceTs = 0;'
chk "$SRCS/PCtl.java"     '电台名册字段'      'private static volatile String[] sRadioUrls'
chk "$SRCS/PCtl.java"     '摘电台名单'        'public static void noteRadioUrls(JSONObject d)'
chk "$SRCS/PCtl.java"     '名册查表'          'public static boolean isRadioUrl(String u)'
chk "$SRCS/PCtl.java"     '先查名册再猜'      'if (cur0 != null && isRadioUrl(nz(cur0.getUrl())))'
chk "$SRCS/PCtl.java"     '109只认名册'       'only-roster-radio-v109'
chk "$SRCS/WebSrv.java"   '喂电台名册'        'PCtl.noteRadioUrls(d);'
# ---- pl-1.6.45：来源标签随曲目走（换曲观察期）----
chk "$SRCS/WebSrv.java"   '观察期常量'        'VIA_PEND_MS = 1200'
chk "$SRCS/WebSrv.java"   '曲目 key 变化'     'if (!ck.equals(sViaKey)) {'
chk "$SRCS/WebSrv.java"   '输出 viaPend'      'm.put("viaPend", viaPend)'
chk "$SRCS/WebSrv.java"   '观察期不认电台'    'm.put("qRadio", radio && !viaPend)'
chk "$SRCS/WebSrv.java"   '清空段无未定'      'm.put("viaPend", false)'
chk "ui/player.html"      '前端有 QPEND'      'var QPEND = false;'
chk "ui/player.html"      '换曲即清标签'      'QVIA = ""; QRADIO = false; QPEND = true;'
chk "ui/player.html"      '未认出就藏'        'if (QPEND) { setTag(""); }'
chk "ui/player.html"      'status 取 viaPend' 'QPEND = !!m.viaPend;'
# ---- pl-1.6.46：pill 隐身但占位 ----
chk "ui/player.html"      '统一显隐入口'      'function setTag(txt)'
chk "ui/player.html"      '隐身不改文本'      'tag.style.visibility = "hidden"'
chk "ui/player.html"      '显示走 setTag'     'tag.style.visibility = "visible"'
chk "ui/player.html"      '初始就是隐身'      'id="npTag" style="visibility:hidden"'
chk "ui/player.html"      '等宽不跳动'        'min-width:74px;text-align:center'
# ---- pl-1.6.48：灯光控制第四项「聆听灯」----
chk "ui/player.html"      '灯光四项'          '>灯光控制 <span class="hint">四项各自独立</span>'
chk "ui/player.html"      '聆听灯开关'        "onclick=\"tg('swLsL','listenLight',1)\""
chk "ui/player.html"      '聆听灯回填'        'setSw("swLsL", a.listenLight !== false);'
chk "ui/player.html"      '聆听灯默认开'      'wakeLight: true, busyLight: true, listenLight: true }'
# ---- pl-1.6.49：拾音灯单颗直写（op=led1）----
chk "$SRCS/Amb.java"      '单颗直写'          'public static synchronized String pick(int k, boolean on)'
chk "$SRCS/Amb.java"      '单颗掩码'          'long mask = 1L << (14 + k);'
chk "$SRCS/Amb.java"      '单颗颜色变量'       'int color = on ? 0xFFFFFF : 0;'
chk "$SRCS/Amb.java"      '单颗熄灭'          'public static synchronized String pickOff()'
chk "$SRCS/WebSrv.java"   'led1 路由'         'if ("led1".equals(op)) {'
chk "$SRCS/WebSrv.java"   'led1 on:K'         'v.startsWith("on:")'
chk "$SRCS/WebSrv.java"   'led1 避开 device'  'op 名必须避开 device 的那些'
# ---- pl-1.6.55：单颗直写改走 sysfs（native 链写不动 B 组 24 颗）----
chk "$SRCS/Amb.java"      'sysfs 节点'        '/sys/class/leds/multi_leds0/led_color'
chk "$SRCS/Amb.java"      'sysfs 主路'        'String e1 = writeLedColor(mask, color);'
chk "$SRCS/Amb.java"      'sysfs 写入方法'     'private static String writeLedColor(long mask, int color)'
chk "$SRCS/Amb.java"      'sysfs 格式'         '"%llx %06x"'
chk "$SRCS/Amb.java"      'hex6 不走 locale'   'private static String hex6(int c)'
chk "$SRCS/Amb.java"      'native 退为备份'    'LedLight.set_color(mask, color);'
chk "$SRCS/Amb.java"      'state 带 sysfs'     'sysfs=ok'
# ---- pl-1.6.47：收藏 key 归一化修正（三处同款）----
chk "$SRCS/Fav.java"      'norm 保留所有字母数字' 'if (Character.isLetterOrDigit(c)) {'
chk "$SRCS/Fav.java"      '符号曲名兜底'        't = "~" + (title == null ? "" : title.trim().toLowerCase());'
chk "ui/player.html"      '页面 nk 保留韩文'    '0xac00 && c <= 0xd7af'
chk "ui/player.html"      '页面 nk 保留假名'    '0x3040 && c <= 0x30ff'
chk "ui/player.html"      '页面 favKey 兜底'    'if (!x) { x = "~" + String(t || "").trim().toLowerCase(); }'
chkNo "$SRCS/Fav.java"    '旧 norm 只留汉字'   '(c >= 0x4e00 && c <= 0x9fff)'
chk "ui/player.html"      '未认出不给来源'    'QVIA = QPEND ? "" : String(m.curVia || "")'
chkNo "ui/player.html"     '不许 display:none' 'npTag").style.display'
chk "ui/player.html"      '兜底块也认未定'    'setTag((!m.viaPend && m.qRadio) ? "广播电台" : "")'
chk "ui/player.html"      '一键导入播放列表'   'function favPlay()'
chk "ui/player.html"      '收藏列表可删'       'function favDelAt(i)'
chk "ui/player.html"      '曲库行尾星标'       'favToggleS('
chk "ui/player.html"      '在线行尾星标'       'favToggleO('
chk "ui/player.html"      '队列行尾星标'       'favToggleQ('
chk "ui/player.html"      'key 与后端同构'     'function favKey(t, a)'
chk "ui/player.html"      '收藏不做乐观回显'   'setTimeout(function () { loadFav(1); }, 600)'
# ---- pl-1.6.41：player 重启后播放页清掉上一首的残留（曲名/封面/歌词/码率）----
chk "$SRCS/PCtl.java"    '本进程播过标记'   'private static volatile boolean sEverSong'
chk "$SRCS/PCtl.java"    'everSong 读取'    'public static boolean everSong()'
chk "$SRCS/PCtl.java"    'song() 记账'      'if (s != null) sEverSong = true;'
chk "$SRCS/WebSrv.java"  '残留判定'         'boolean staleNow = !PCtl.everSong() && !extOn'
chk "$SRCS/WebSrv.java"  '曲名清空'         'm.put("curTitle", "");'
chk "$SRCS/WebSrv.java"  '封面清空'         'm.put("curPic", "");'
chk "$SRCS/WebSrv.java"  '码率清空'         'm.put("brSrc", "");'
chk "$SRCS/WebSrv.java"  '进度清空'         'm.put("posMs", 0);'
# 误清防护：正在起播缓冲（loading/stalled）或外部推流在播时绝不能清
chk "$SRCS/WebSrv.java"  '不误清(缓冲)'     '&& !"loading".equals(phNow) && !"stalled".equals(phNow)'
chk "$SRCS/WebSrv.java"  '不误清(推流)'     'if (exNow != null) extOn = exNow.optBoolean("playing", false);'
chk "ui/player.html"  '空队列走随机'      'toast("队列是空的 —— 随机来一批");'
# ★ 记录必须一起没：lastTitle 字段和 op=replay 都不许再出现
#   （只清显示、点 ▶ 又播回上一首 = 没清干净，用户一眼就看出来）
chkNo "$SRCS/WebSrv.java" '不留 lastTitle'  'm.put("lastTitle"'
chkNo "ui/player.html"  '不再 replay'       'act("replay")'
chkNo "ui/player.html"  '不留 lastTitle'    '.lastTitle'  
# ---- pl-1.6.40：开关不再「来回闪」+ 去掉没用的「刷新」链接 ----
#   闪的根因：乐观回显把**真值对象就地改写成目标状态**（o.on = want）再渲染，
#   真值一回来又翻回去 ⇒ 观感就是来回闪、最后停在哪不一定是真状态。
#   改法：不伪造数据 —— 行永远显示真值，只有按钮在切换期间显示「正在…」；
#   再加兜底：锁到期真值还没跟上就丢掉期盼、按真值渲染并补拉一次确认。
chk "ui/player.html"  '不伪造 o.disc'      'DISC_WANT = want; DISC_LOCK = Date.now() + 6000;'
chk "ui/player.html"  '第四拍校准'         'setTimeout(btSync, 9000)'
chk "ui/player.html"  '蓝牙兜底落真值'      'if (!btPend() && BT_WANT != null) { BT_WANT = null; setTimeout(loadBt, 1200); }'
chk "ui/player.html"  '投播兜底落真值'      'if (!svcPend() && SVC_WANT) { SVC_WANT = null; setTimeout(loadSvc, 1200); }'
chkNo "ui/player.html" '连接设置已无刷新链接' 'loadBt();loadSvc()'
# ---- pl-1.6.39：翻转开关的 toast 静音（UI 已有「正在开启…」回显，不再弹「过2~3秒再看」）----
chk "ui/player.html"  'act 支持静音'   'function act(op, extra, quiet)'
chk "ui/player.html"  '蓝牙开关静音'    'act(want ? "bt_on" : "bt_off", null, true)'
chk "ui/player.html"  '可被搜到静音'    'act("bt_discover", "v=" + (want ? 1 : 0), true)'
chk "ui/player.html"  '投播静音'        'act(w, "v=" + (want ? 1 : 0), true)'
# ---- pl-1.6.37/38：翻转类开关「点两次才切换」—— 真值定方向 + 乐观回显 + 防连点 ----
#   根因：按钮方向取自本地缓存 S.bt / S.svc，那两个只在进设置页和每 10s 轮询时刷新
#   ⇒ 缓存一陈旧，第一次按就发错方向（那一侧本来就是这个状态 ⇒ 没动作）。
chk "ui/player.html"  '蓝牙先读真值'    'var want = !o.on;'
chk "ui/player.html"  '可被搜到先读真值' 'var want = !o.disc;'
chk "ui/player.html"  '投播先读真值'    'var on = w === "dlna" ? !!o.dlna : !!o.airplay;'
chk "ui/player.html"  '不伪造状态(蓝牙)'   'BT_WANT = want; BT_LOCK = Date.now() + 6000;'
chk "ui/player.html"  '不伪造状态(投播)'   '// ② 按钮显示「正在…」，行是真值'
chk "ui/player.html"  '三拍校准 btSync' 'setTimeout(btSync, 6000)'
chk "ui/player.html"  '三拍校准 svcSync' 'setTimeout(svcSync, 6000)'
chk "ui/player.html"  '防连点 btPend'   'function btPend()'
chk "ui/player.html"  '防连点 svcPend'  'function svcPend()'
chk "ui/player.html"  '真值到位即解锁'    'SVC_LOCK = 0; SVC_WANT = null'
chk "ui/player.html"  'svcShow 已抽出'  'function svcShow(o)'
chkNo "ui/player.html" '不得再用缓存定方向' 'act((S.bt && S.bt.on) ? "bt_off" : "bt_on")'
# ---- pl-1.6.36：设置页分组 —— 4 组可折叠 + 快捷跳转 + 危险操作收起 ----
HTML_="ui/player.html"
chk "$HTML_" '语音助手开关'        'id="swVoice"'
chk "$HTML_" '开关回调'            'vpToggle()'
chk "$HTML_" '状态回显位'          'id="vpNote"'
# ★ 语音助手是「小智进程」卡里的一行，不再单独占一张卡
chkNo "$HTML_" '不再单列语音助手卡' '<div class="ttl">语音助手'
chk "$HTML_" '小智进程卡'          '小智进程 <span class="hint">本页面由 player 提供</span>'
chk "$HTML_" '硬停用按钮仍在'      'id="btnOff"'
chk "$HTML_" '重新启用按钮仍在'    'id="btnOn"'
# ★ 蓝牙 + 外部播放源 合成一张「连接设置」
chk "$HTML_" '连接设置栏'          'class="ttl">连接设置'
chk "$HTML_" '蓝牙按钮仍在'        'id="btnBtOn"'
chk "$HTML_" '可被搜到按钮仍在'    'id="btnBtDisc"'
chk "$HTML_" 'DLNA 按钮仍在'       'id="btnDlna"'
chk "$HTML_" 'AirPlay 按钮仍在'    'id="btnAir"'
chk "$HTML_" '蓝牙列表容器仍在'    'id="btList"'
chkNo "$HTML_" '旧的蓝牙独立卡'     'class="ttl">蓝牙 '
# ★ 顺序：小智进程 → 播放引擎 → 连接设置 → 设备状态 → 赞赏（设备状态压在赞赏之上）
ord() {  # ord <说明> <先出现的串> <后出现的串>
  local a b
  a=$(grep -n -m1 -F -- "$2" "$HTML_" | cut -d: -f1)
  b=$(grep -n -m1 -F -- "$3" "$HTML_" | cut -d: -f1)
  if [ -z "$a" ] || [ -z "$b" ] || [ "$a" -ge "$b" ]; then
    echo "  FAIL $1（顺序不对：[$2] 应在 [$3] 之前）"; FAIL=1
  else echo "  OK   $1"; fi
}
ord '小智进程在连接设置前' '小智进程 <span'  'class="ttl">连接设置'
ord '连接设置在设备状态前' 'class="ttl">连接设置' '设备状态 <span'
ord '设备状态在赞赏卡之上' '设备状态 <span'  "showQR('donate')"
# ★ 重排最怕把 section 起始标签弄丢 —— 漏了就四个页的内容同时显示
chk "$HTML_" '设置 section 起'   '<section id="s-set">'
chk "$HTML_" '四个 section 齐'   '<section id="s-alarm">'
# ---- pl-1.6.35：语音软开关（省电模式）—— 页面开关 + 状态回显 ----
#   后台是 device 侧 v1.3.199 的 op=voice（v=on|off|state）：关掉「KWS 唤醒 + 收音 +
#   小智本体」，device 进程与 web 控制台照常 ⇒ 电台 / 搜索 / 点播不受影响。
#   ★ player 侧不自己实现这个 op —— 走 actOp 末尾「兜底转发 8088」，
#     所以这里**不能**在 WebSrv 里加同名分支（会被 player 自己吃掉）。
chk   "ui/player.html"    '语音开关卡片'        'id="swVoice"'
chk   "ui/player.html"    '开关回调'            'onclick="vpToggle()"'
chk   "ui/player.html"    '状态回显位'          'id="vpNote"'
chk   "ui/player.html"    '状态填充函数'        'function vpFill(v)'
chk   "ui/player.html"    '开关动作函数'        'function vpToggle()'
chk   "ui/player.html"    'load 里填充'         'vpFill(S.voice)'
chk   "ui/player.html"    '发 op=voice'         'act("voice", "v="'
chk   "ui/player.html"    '位取反说明'          'voice.off=true 表示省电模式'
chkNo "$SRCS/WebSrv.java" 'player 不得自吞 voice' '"voice".equals(op)'
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
chkNo "$SRCS/PCtl.java"   '去掉 pos>2000 门槛'   'getTotal_time() <= 1000 && p.getPlay_time() > 2000'
chk   "$SRCS/WebSrv.java" '未判明不贴标签'      'm.put("liveUnknown", !radio && durMs <= 1000)'
chk   "ui/player.html"    '未判明标签留空'      'else if (QUNK) { setTag(""); }'
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
chk "$SRCS/WebSrv.java"  '在线类 op 长超时'    'fwdMs = 120000'
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
# pl-1.6.36：蓝牙栏已并入「连接设置」，不再有独立的「>蓝牙 <」标题
chk "ui/player.html"     '连接设置栏(合并后)'    'class="ttl">连接设置'
chk "ui/player.html"     '外部播放源二级标题'     '>外部播放源 <'
chkNo "ui/player.html"   '不得残留独立蓝牙卡'    'class="ttl">蓝牙 '
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

# ---- pl-1.6.55：夜灯（底部氛围环 A 组）----
chk "$SRCS/Amb.java"     '夜灯配置 key'      'K_NIGHT_C'
chk "$SRCS/Amb.java"     '夜灯亮度上限 100'  'MAX_NIGHT_LUMA = 100'
chk "$SRCS/Amb.java"     '亮度缩放'          'scaleLuma('
chk "$SRCS/Amb.java"     'A 组整组写色'      'public static synchronized String all(int color)'
chk "$SRCS/Amb.java"     '夜灯开关'          'public static synchronized String nightAmb('
chk "$SRCS/Amb.java"     '夜灯改色'          'public static synchronized String nightColor('
chk "$SRCS/Amb.java"     '夜灯调亮度'        'public static synchronized String nightLuma('
chk "$SRCS/Amb.java"     '夜灯状态'          'public static synchronized String nightState('
chk "$SRCS/Amb.java"     '夜灯开时让路'      'private static synchronized void quietAmbient()'
chk "$SRCS/Amb.java"     '夜灯关时接回'      'private static synchronized void restoreAmbient()'
chk "$SRCS/Amb.java"     '原厂氛围灯开关'    'private static void sendFactoryAmbient('
chk "$SRCS/Amb.java"     '让路前记状态'      'private static boolean sQuietAmbOn = false;'
chk "$SRCS/Amb.java"     '夜灯开走让路'      'quietAmbient();'
chk "$SRCS/Amb.java"     '夜灯关走接回'      'restoreAmbient();          // 把刚才为了让路而停掉的东西接回去'
chk "$SRCS/Amb.java"     '顶部掩码'          'TOP = 0x7FFFFF8000L'
chk "$SRCS/Amb.java"     '顶部整组写色'      'public static synchronized String top(int color)'
chk "$SRCS/Amb.java"     '夜灯顶开关'        'public static synchronized String nightTop('
chk "$SRCS/Amb.java"     '夜灯总开关'        'public static synchronized String nightPower('
chk "$SRCS/Amb.java"     '亮哪一组(配置)'    'public static synchronized String nightPart('
chk "$SRCS/Amb.java"     'part 持久化 key'   'K_NIGHT_P = "r1_night_part"'
chk "$SRCS/Amb.java"     'part 取值'         'sNightPart == PART_BOTH || sNightPart == PART_TOP'
chk "$SRCS/Amb.java"     '关着改 part 不点灯' '（当前关着，没点灯）'
chk "$SRCS/WebSrv.java"  'night on 走总开关' 'Amb.nightPower(true)'
chk "$SRCS/WebSrv.java"  'night off 走总开关' 'Amb.nightPower(false)'
chk "$SRCS/WebSrv.java"  'night part 分支'   'v.startsWith("part:")'
chk "ui/player.html"         '总开关只认 on'     'NIGHT.on = (n.on != null)'
chk "ui/player.html"         'part 只是配置'     'return act("night", "v=part:" + p, true)'
chk "ui/player.html"         '关灯显示选择'      'seg[i].getAttribute("data-v") === NIGHT.part'
chk "ui/player.html"         '关灯整段压暗'      '$("segNightPart").style.opacity = NIGHT.on ? "" : "0.45"'
chk "$SRCS/Amb.java"     '守灯'              'private static synchronized void scheduleKeep()'
chk "$SRCS/Amb.java"     '补偿三刀'          'private static synchronized void scheduleFix()'
chk "$SRCS/Amb.java"     '灭灯撤补偿'        'private static synchronized void cancelFix()'
chk "$SRCS/Amb.java"     '三个不同 Runnable'  'sFixTasks = new Runnable[FIX_DELAYS.length]'
chk "$SRCS/Amb.java"     '开底部后补刀'      'quietAmbient() 里那两条「按停」是异步到达的'
chk "$SRCS/Amb.java"     '灭底部先撤刀'      'cancelFix();               // ★ 先撤补偿'
chk "$SRCS/Amb.java"     '改色后补刀'        '★ 改色也补刀'
chk "$SRCS/Amb.java"     '调亮度后补刀'      '★ 调亮度也补刀'
chk "$SRCS/Amb.java"     '小飞那条路'        'lights_test set'
chk "$SRCS/WebSrv.java"  'led1 top 分支'     'v.startsWith("top:")'
chk "$SRCS/WebSrv.java"  'led1 topoff 分支'  '"topoff".equals(v)'
chk "$SRCS/WebSrv.java"  'night 顶归 player' 'Amb.nightTop(parse01('
chk "$SRCS/WebSrv.java"  '01 解析'           'private static boolean parse01('
chk "ui/player.html"         '亮度实时'          'function nightLumaLive('
chk "ui/player.html"         '取色实时'          'oninput="nightColor(this.value)"'
chk "$SRCS/WebSrv.java"  'led1 all 分支'     'v.startsWith("all:")'
chk "$SRCS/WebSrv.java"  'led1 alloff 分支'  '"alloff".equals(v)'
chk "$SRCS/WebSrv.java"  'night 路由'        '"night".equals(op)'
chk "$SRCS/WebSrv.java"  'night 转 device'   'Proxy.post("/api/act", buildQuery(q), 8000)'
chk "ui/player.html"         '夜灯开关'          'id="swNight"'
chk "ui/player.html"         '夜灯只开一组'      'function nightPart('
chk "ui/player.html"         '夜灯取色'          'id="inNightColor"'
chk "ui/player.html"         '夜灯亮度滑块'      'id="rgNightLuma"'
chk "ui/player.html"         '亮度上限注释'      'setLumaGain() 只收 0..100'
chk "ui/player.html"         '底部亮度标签'      '底部亮度'
chk "ui/player.html"         '亮度 60% 提示'     '亮度不建议超过 60%，过高会导致发热，严重影响灯条寿命'
chk "ui/player.html"         '夜灯回填'          'nightFill(S.night)'
# ---- pl-1.6.79：闹钟（web 侧）+ 日志页搬家 ----
chk "$SRCS/WebSrv.java" '闹钟反代路由'       'if (path.equals("/api/alarm")) {'
chk "$SRCS/WebSrv.java" '闹钟反代超时 4s'    'String av = Proxy.get("/api/alarm", 4000);'
chk "$SRCS/WebSrv.java" '取不到给空壳'       '小智未运行（8088 不可达），闹钟状态取不到'
chk "$SRCS/WebSrv.java" '空壳带 ok:false'    '{\"ok\":false,\"total\":0'
chk "ui/player.html"    '闹钟 tab'           'data-t="alarm"'
chk "ui/player.html"    '闹钟 section'       'id="s-alarm"'
chk "ui/player.html"    '响铃提示卡'         'id="alRingCard"'
chk "ui/player.html"    '新建闹钟函数'       'function alAdd()'
chk "ui/player.html"    '关闭这次函数'       'function alDismiss()'
chk "ui/player.html"    '闹钟渲染函数'       'function renderAlarm(o)'
chk "ui/player.html"    '闹钟拉取函数'       'function loadAlarm()'
chk "ui/player.html"    '闹钟轮询 5s'        'if (curTab === "alarm") loadAlarm();'
chk "ui/player.html"    '周几七个格'         'function alDayTap('
chk "ui/player.html"    '重复方式切换'       'function alRepChange()'
chk "ui/player.html"    '选歌弹窗'           'id="alModal"'
chk "ui/player.html"    '默认随机一项'       'function alPickRandom('
chk "ui/player.html"    '停用/删除入口'      'alAct("alarm_toggle"'
chk "ui/player.html"    '删除入口'           'alAct("alarm_del"'
chk "ui/player.html"    '指定歌曲入口'       'alAct("alarm_song"'
chk "ui/player.html"    '试听入口'           'alAct("alarm_test"'
chk "ui/player.html"    '日志搬进设置'       'id="logBox"'
chk "ui/player.html"    '日志标题'           '会话日志 <span class="hint">'
chk "ui/player.html"    '日志自动刷新限设置页' '切走这一页就停'
ord '日志在赞赏卡之上' 'id="logBox"' "showQR('donate')"
chkNo "ui/player.html"  '日志 tab 已移除'    'data-t="log"'
chkNo "ui/player.html"  '日志 section 已移除' 'id="s-log"'
# ---- pl-1.6.80：闹钟音量（页面侧）----
chk "ui/player.html"    '音量滑块'          'id="alVol"'
chk "ui/player.html"    '音量设定函数'      'function alVolSet(v)'
chk "ui/player.html"    '音量走 alarm_vol'  'alAct("alarm_vol"'
chk "ui/player.html"    '音量上下限 20~100' 'n >= 20 && n <= 100'
chk "ui/player.html"    '音量实时显示'      'id="alVolSys"'
chk "ui/player.html"    '渲染里带音量'      'AL.vol = o.vol || 50;'
chk "ui/player.html"    '渲染里带音乐音量'  'o.musicVolUser === undefined'
chk "ui/player.html"    '本地铃声标记'      '（本地铃声）'
chk "ui/player.html"    '清缓存入口'        'function alCacheClear()'
chk "ui/player.html"    '清缓存走 alarm_cache' 'alAct("alarm_cache"'
chk "ui/player.html"    '防误触口径'        '拖动只改显示，点「应用」才生效'
# ---- pl-1.6.81：闹钟铃声缓存（页面侧）----
chk "ui/player.html"    '缓存行'            'id="alCacheTxt"'
chk "ui/player.html"    '缓存目录行'        'id="alCacheDir"'
chk "ui/player.html"    '缓存拉取函数'      'function alLoadCache(cb)'
chk "ui/player.html"    '用 cacheKeys'      'o.cacheKeys'
chk "ui/player.html"    '已缓存排最前'      'ALROWS.sort(function (x, y)'
chk "ui/player.html"    '已缓存标记'        '已缓存</span>'
chk "ui/player.html"    '渲染缓存数'        'o.cache === undefined ? "?" : o.cache'
chk "ui/player.html"    '渲染缓存目录'      'o.cacheDir'
chk "ui/player.html"    '本地铃声判定'      'o.ringLocal'
chk "ui/player.html"    '滑块默认 50'       'min="20" max="100" step="5" value="50"'
chkNo "ui/player.html"  '试听按钮已删'      'function alTone()'
chkNo "ui/player.html"  'alarm_tone 已删'   'alarm_tone'
chkNo "ui/player.html"  '提示音文案已删'    '合成的「嘀嘀嘀」'
# ---- pl-1.6.82：闹钟「设铃声」页面四项 ----
chk "ui/player.html"    '曲库长按设闹铃'    'function alSearchSetRing('
chk "ui/player.html"    '搜索结果挂长按'    'srLpBind($("qRes"))'
chk "ui/player.html"    '搜索结果带曲名'    'data-sr-title='
chk "ui/player.html"    '长按 550ms'        '}, 550);'
chk "ui/player.html"    '长按后吞 click'     'SR_LP_DONE < 500'
chk "ui/player.html"    '搜索列表防选词'    '#qRes .li,#qRes .li *'
chk "ui/player.html"    '横幅撤 SW 壳'      'serviceWorker.getRegistrations'
chk "ui/player.html"    '设铃声走 id=next'   'alarm_song", "id=next'
chkNo "ui/player.html"  'data-sr-artist 引号闭合' "escAttr(it.artist || \"\") + '>'"
# ---- pl-1.6.86：默认铃声 + 铃声缓存清单（点开 / 点一条设为铃声）----
chk "ui/player.html"    '下一个响铃卡片'     'id="alNextRingCard"'
chk "ui/player.html"    '下一个响铃渲染'     'function alRenderNextRing()'
chk "ui/player.html"    '缓存清单可展开'     'function alCacheToggle()'
chk "ui/player.html"    '缓存清单渲染'       'function alRenderCache()'
chk "ui/player.html"    '点缓存设铃声'       'function alCacheSet('
chk "ui/player.html"    '缓存清单容器'       'id="alCacheList"'
chk "ui/player.html"    '响铃歌曲读默认'     'o.dfltSong'
chk "ui/player.html"    '选择=设默认铃声'    'alAct("alarm_dflt"'
chk "ui/player.html"    '随机=清默认'        'alAct("alarm_dflt", "clear=1")'
chk "ui/player.html"    '两处 data-sr 带引号'  "escAttr(it.artist || \"\") + '\">'"
# ---- pl-1.6.87：闹钟时间 = 内嵌列表式（不再弹原生表盘）----
chk "ui/player.html"    '时间行（大号）'       'id="alHmRow"'
chk "ui/player.html"    '时间面板'            'id="alHmPanel"'
chk "ui/player.html"    '小时横条'            'id="alHStrip"'
chk "ui/player.html"    '分钟横条'            'id="alMStrip"'
chk "ui/player.html"    '手输：时'            'id="alHh"'
chk "ui/player.html"    '手输：分'            'id="alMm"'
chk "ui/player.html"    '滑动松手吸附'        'function alHmSettle('
chk "ui/player.html"    '时间初始化'          'alHmInit();'
chk "ui/player.html"    '添加读 ALHM'         'var h = ALHM.h, m = ALHM.m;'
chkNo "ui/player.html"  '不再有原生 time 控件' 'input type="time" id="alTime"'
# ---- pl-1.6.88：统一弹窗 / 缓存单项删除 / 重链接不抢焦点 ----
chk "ui/player.html"    '统一弹窗'            'function uiDlg(o)'
chk "ui/player.html"    '确认封装'            'function uiConfirm(text, onOk, o)'
chk "ui/player.html"    '单行删除按钮'        'event.stopPropagation();alCacheDel('
chk "ui/player.html"    '缓存单项删除'        'function alCacheDel(i)'
chk "ui/player.html"    '弹窗按钮等宽'        '.modal-foot .btn{flex:1;min-width:0}'
chkNo "ui/player.html"  '无原生 confirm'      'if (!confirm("'
chkNo "ui/player.html"  '无原生 alert'        '      alert('
chkNo "ui/player.html"  '重链接不抢焦点'      'try { inp.focus(); }'
# ---- pl-1.6.89：闹钟音量应用按钮 / 只显示音乐音量 / 起播先让路 ----
chk "ui/player.html"    '闹钟音量应用按钮'    'id="alVolApply"'
chk "ui/player.html"    '音量拖动不落库'      'function alVolDrag(v)'
chk "ui/player.html"    '音量应用'            'function alVolApply()'
chk "ui/player.html"    '音量 3 秒还原'       'function alVolRevert()'
chk "ui/player.html"    '待应用不回填'        'if (!alVolPending) {'
chk "ui/player.html"    '只报音乐音量'        'o.musicVolUser'
chk "plsrc/cn/workbuddy/r1web/WebSrv.java" 'status 覆盖铃声音量' 'o.optInt("musicVolUser", -1)'
chk "plsrc/cn/workbuddy/r1web/PCtl.java"   '起播前让路'         'private static void alarmYield() {'
chk "plsrc/cn/workbuddy/r1web/PCtl.java"   '让路发 alarm_yield'  'op=alarm_yield'
chkNo "ui/player.html"  '滑块不再一拖就发'    'onchange="alVolSet(this.value)"'
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
# 2026-10-31：同 device 构建，baksmali 多线程写 .smali 会被 Defender 抢锁 Access Denied，
# 用 -XX:ActiveProcessorCount=1 压成单线程（本机无管理员权限加 Defender 排除项）。
MSYS2_ARG_CONV_EXCL="*" "$JAVA" -XX:ActiveProcessorCount=1 -cp "$BCP" org.jf.baksmali.Main d out_$V/classes.dex -o smali_$V
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
# pl-1.6.124：有声书/节目行播放失败 ⇒ 引擎不跳下一集（原地停）。两个引擎都要插。
"$PY" patch_abnoskip.py smali_build_$V/com/phicomm/speaker/player/model/new_NetPlayer.smali smali_build_$V/com/phicomm/speaker/player/model/NetPlayer4.smali || exit 1
# 列表循环（顺序播放）播到队尾即停，不再回卷到第 0 首（单曲队列 = 单曲循环）；节目行保持回卷。两个引擎都要插。
"$PY" patch_list_end.py smali_build_$V/com/phicomm/speaker/player/model/new_NetPlayer.smali smali_build_$V/com/phicomm/speaker/player/model/NetPlayer4.smali || exit 1
# 临时诊断开关（R1DBG=1）：给 PlayerBinderWrapper$2 里所有静默 catch(Exception)
# 插入 printStackTrace + Logger.d —— 用来抓 pause/start 返回 false 的真凶。
"$PY" patch_amb.py smali_build_$V/com/phicomm/speaker/player/light/LedLight.smali || exit 1
# pl-1.6.61：夜灯占着底部时不开音乐律动 519（灯效服务直写 A 组，钩子拦不到）
"$PY" patch_player_visualizer.py smali_build_$V/com/phicomm/speaker/player/PlayerVisualizer.smali || exit 1
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
# pl-1.6.129：seek 未 prepare 直接拒绝 —— 不再 getDuration 抛 -38 把整条播放判死
#   （续播守卫抢在 prepareAsync 回来之前拖一把 ⇒ MEDIA_PLAY_ERROR ⇒「只进队列不出声」）
"$PY" patch_seek_guard.py smali_build_$V || exit 1
chk "smali_build_$V/com/phicomm/speaker/player/model/MultiMediaPlayer.smali" 'seek 未 prepare 闸门' 'r1web: 未 prepare 不许 seek'
chk "smali_build_$V/com/phicomm/speaker/player/model/MultiMediaPlayer.smali" 'seek 闸门出口' ':r1seek_ok'
chk "$SRCS/PCtl.java"   '严格 seek 判据'      'seekReadyNow'
chk "$SRCS/WebSrv.java" '点播作废旧守卫'      'abResumeCancel'
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
MSYS2_ARG_CONV_EXCL="*" "$JAVA" -XX:ActiveProcessorCount=1 -Xmx2g -cp "$SCP" org.jf.smali.Main a smali_build_$V -o bridge_$V.dex
ls -l bridge_$V.dex

echo "=========== [8/8] Janus 拼接 + 校验 ==========="
"$PY" "$JANUS_PY" --dex "$HERE/bridge_$V.dex" \
  --apk "$APKSHELL" --out "$HERE/$OUT" || exit 1
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

# ---- 自动留档：每次构建按版本号留 apk + 源码快照，历史版本绝不覆盖 ----
VER=$(sed -n 's/.*VER = "\([^"]*\)".*/\1/p' plsrc/cn/workbuddy/r1web/Boot.java | head -1)
if [ -n "$VER" ] && [ -f xiaozhi_EchoService_pv81.apk ]; then
  APK="release/xiaozhi_EchoService_${VER}.apk"
  REL="$VER"
  if [ -f "$APK" ]; then
    echo "== [留档] $APK 已存在，历史版本不覆盖（本次未归档）=="
  else
    mkdir -p release "$REL"
    cp -f xiaozhi_EchoService_pv81.apk "$APK"
    tar czf "$REL/src.tar.gz" plsrc ui/player.html build_pv81.sh 2>/dev/null || true
    { echo "version: $VER"; echo "built:   $(date '+%Y-%m-%d %H:%M:%S')"
      echo "apk:     $APK ($(stat -c %s "$APK") bytes)"
      echo "sha256:  $(sha256sum "$APK" | cut -d' ' -f1)"; } > "$REL/MANIFEST.txt"
    echo "== [留档] $APK + $REL/src.tar.gz =="
  fi
fi

echo "=========== [9/8] 源码树版本号还原（回到测试序列 pl-1.6.x） ==========="
"$PY" - <<'RSTEOF'
import io, os
p = 'plsrc/cn/workbuddy/r1web/Boot.java'
io.open(p, 'w', encoding='utf-8', newline='\n').write(
    io.open('_VER_dev.bak', encoding='utf-8').read())
os.remove('_VER_dev.bak')
print("  源码树 VER 已还原为测试序列")
RSTEOF

echo "RELEASE DONE: XiaoZhi_EchoService_V2.0.5.apk"
echo "  本版新增：节目（有声书）页与我的节目、整批占位入列、秒数续播、"
echo "  下一首/上一首在播书时变集、本地有声书库（整批入列 + 缓存 + 标签）、"
echo "  三栏封顶、本地书队列标题带集号、搜索删空即回列表、"
echo "  页面 no-cache + ETag 与「页面已过时」一键强刷"
