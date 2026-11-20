#!/bin/bash
# =============================================================================
#  jp2 RELEASE V2.0.6 —— 与测试版(1.3.x)分开的第二条版本序列。
#
#  基线 = 测试版 v1.3.359（jp2 v206），相对 V2.0.5（v1.3.329）的变化：
#
#   一、播放记录 / 进度上报（v1.3.339~352）—— 本版主线
#    · 暂停/停止/关机立即结算（v1.3.340）：网页暂停、实体键暂停、语音 stop、
#      设备关机四条路都走 srvFinal，不再等下一拍
#    · 位置同步上报 /v1/pos（v1.3.342），拉流 URL 带 dev 设备身份（v1.3.346）——
#      群晖 docker 下 R1 的播放记录不再误落「网页」桶
#    · 书级共享进度（v1.3.336/347）：续播先问服务端（含「听完⇒下一集」），
#      换台设备也能接着听；读取带 dev，服务端设备分组隔离才生效
#    · 起播即上报（v1.3.349）：ProgProgress 每 2 秒轻探 player 指纹
#      （曲名|curId|playing|pos>3s），变化或到期才上报；不再拉整张队列
#    · 入队盖章 + 广播上报（v1.3.350/352）：谁入队谁把书名写进队列项 album，
#      电台判据改 URL 形态（电台 liveStream/qRadio 起播瞬间都是 false）
#
#   二、听书身份唯一化（v1.3.348/351/357）
#    · 合集子书的书身份 = 子书名（「黑暗森林Ⅱ」），合集名只作 series 展示位；
#      记录里不带前缀，避免三部曲糊成一条
#    · 上报名统一短名；禁止用全局「上一本书」兜底 —— 认不出书名就不上报
#
#   三、合集（coll）语音点播（v1.3.353~357）
#    · ablFind 不再一票跳过合集；说哪部就播哪部（指名播）
#    · 合集子书按子书名找本地文件与续播记忆
#
#   四、语音调进度（v1.3.358）
#    · 新 MCP 工具 self.r1player.seek_play：sec 绝对秒 / delta 相对秒 /
#      都不传只查进度；语义与 stem 固件 self.stem.seek_play 一致
#    · status 输出末尾带「已播到 X，共 Y，还剩 Z」
#    · 判据：durMs<=0 一律拒绝拖动（直播流恒 0，刚起播没读出时长也落这条）
#
#   五、续播查找顺序（v1.3.359）
#    · 本地库 → 缓存 → 在线：本地同名书可能只是残卷（只剩 3 集），
#      集号超范围时自动退 /api/ab/episodes（服务端内部缓存优先、miss 才回源），
#      再退 ≤目标集的最近一集，不再回「找不到可播的那一集」
#
#   六、缺集救援与脏缓存（v1.3.332~335）
#    · 历史错误缓存的脏占位行强制重问服务端；带 ep 让服务端按需救援
#    · 补书名改用短书名（全名太长，列表里看不到集号）
#    · 歌曲解析服务端优先（吃 /api/song/resolve 的 rid 缓存）
#
#   七、其他
#    · 「退下」本地兜底（v1.3.330）：云端不一定映射到 MCP 工具，
#      本地对高置信度短语直接退出 LISTENING
#
#  仅在用户明确要求出 release 时才执行本脚本。
#
#  ★ 版本号策略：
#      release 序列 2.0.x   <- 只有本脚本产出，产物名 XiaoZhi_Unisound_V2.0.6.apk
#      测试序列   1.3.x     <- 日常迭代（build_v205.sh 等），只在开发目录流转
#    本脚本在 javac 之前把 XzBootstrap.VERSION 临时改成 2.0.6，
#    janus 构建完立刻还原 —— 源码树永远回到测试序列，两条线不会串。
# =============================================================================
# 2026-10-29：★★★ 本机环境拦截「覆盖已存在的文件」（新建文件不受影响，违者进程直接被 SIGTERM）
#   ⇒ 构建前把所有会被覆盖写的产物/日志/生成物先 mv 进 _stale/，让脚本全程只做「新建」。
mkdir -p _stale
__st=$(date +%s)
for __f in v22sources.txt javac_v166.log r8_v166.log dex_log.txt XiaoZhi_Unisound_V2.0.6.apk \
           v22client.jar v22_merged.dex v22out/classes.dex classes.dex \
           v2src/cn/workbuddy/r1client/UiModern.java ui/_js_check.tmp.js ui/_preview.html; do
  if [ -e "$__f" ]; then
    mkdir -p "_stale/$(dirname "$__f")" 2>/dev/null
    mv "$__f" "_stale/$__f.$__st" 2>/dev/null
  fi
done
# jp2-v1.3.253：★★★ 音乐 ⇄ 响铃互斥，且一律「先设音量，再出声」
#   · 用户诉求：①闹钟音量滑块加「应用」防误触；②音乐播放时响铃 ⇒ 暂停音乐、音量设到铃声音量；
#     响铃时播放音乐 ⇒ 关掉响铃、音量恢复到音乐音量；③播放界面只显示音乐音量，不显示响铃音量；
#     ④播放音乐和播放响铃都要先设音量再播放（防止声音突然变大变小）
#   · AlarmVol.guard 语义改成「设成」（不再是「至少抬到」）—— 音乐比闹钟响也要降下来；
#     新增 userMusic()：报「用户自己的音乐音量」（设之前的原值）
#   · Alarm.fire/test：顺序死规矩 —— 先暂停音乐 → 再设音量 → 才起播（以前是设音量后 playByRing 里才 pause）
#   · 新 op=yield（web 走 alarm_yield）：player 起播音乐**之前**调一次 ⇒ 先还原音量再出声
#   · 新 watchMusic() 看护线程（700ms）：兜底语音点歌/DLNA 这类不经过 player 控制台的路径；
#     ★ 必须先确认「确实停过」才武装，且 sRingOnMain 为真（主播放器在放我们的铃声）时绝不自关
#   · dismiss 支持 quiet=1（只关闹钟不动音乐）；closeRing() 抽出收尾四件事
#   · status/json 新增 ringVolOn + musicVolUser ⇒ player 用它覆盖音量显示
#   · ★★★ 顺带修真 bug：到点后、tick 之前任何 touch()（改音量就会）都会让 nextTs()
#     把这一档判成「过期」⇒ 一次性闹钟永不触发、每天的直接跳到明天。用户现象正是
#     「响铃前摸了一下音量滑块，闹钟没响」。现在按 last 判「这一档响过没有」+ 60s 宽限
# jp2-v1.3.252：★★★ 闹钟铃声改走「独立播放通道」—— 不再借主播放器放闹钟
#   · 新增 RingPlayer.java：自己 new MediaPlayer 播 /sdcard/R1Alarm 的本地 mp3（循环）
#   · 根因：以前闹铃走 PlayerBridge.play_local ⇒ 等于「用音乐播放器放闹钟」，
#     关掉闹钟后主播放器的标题/封面/进度条还停在铃声那首歌上（用户：
#     「关闭闹钟以后铃声歌曲还占着播放界面挺突兀的」）
#   · ★ STREAM_ALARM 仍然是死通道（AudioTrack 一写就 BUFFER TIMEOUT），换的不是音频流，
#     是「谁在播」：RingPlayer 与主播放器完全无关 ⇒ 关闭闹钟后界面保持原样
#   · 播放优先级：指定歌+有缓存 → 独立通道；随机 → 先吃铃声缓存（也走独立通道）；
#     缓存空 → 才三池随机（这条仍会占主播放器）；独立通道起不来 → 退回 play_local
#   · dismiss / 响铃收尾 / 试响窗口到点 都会 RingPlayer.stop()；maintain 出声判据含它
#   · 新 op=ring（play/stop/status，web 走 alarm_ring）—— 装机后一条命令验证能否出声
#   · json 新增 ringCh=local（这次响铃走的是独立通道）
# jp2-v1.3.251：★★★ 闹钟「默认铃声」—— 设一次就记住，之后 web / 语音建的闹钟都默认用它
#   · Alarm：新增全局默认铃声 sDflt（存在 r1_alarms.json 的 dflt 字段）
#   · setSong：设铃声同时写默认；★ 一个闹钟都没有也能设（以前直接 ERR，导致长按设铃声全失败）
#   · add：没指定歌 ⇒ 自动套默认铃声（语音「每天早上七点叫我」也会用设过的那首）
#   · json：输出 dfltSong / dfltArtist / dfltK 给页面「响铃歌曲」一栏
#   · mcp 新增 op=dflt（查 / 设 / 清默认铃声），web 走 alarm_dflt（前缀自动转发）
# jp2-v1.3.250：★★ 闹钟「设铃声」页面四项（曲库长按 / 缓存列表 / 点缓存设 / 顶部同步）
#   · 曲库搜索结果长按 → confirm 设闹钟（alarm_song id=next&title&artist）
#   · 新建卡「铃声缓存」点击展开清单、点一项 confirm 设成闹铃
#   · 顶部常显「下一个响铃歌曲」随设置同步
# jp2-v1.3.249：★★ 修「清空铃声缓存」不生效（clear 拿文件名当 key 删账）
#   · clear() 改按 key 删账（原来 items.remove(文件名) 一条都删不掉）
#   · loadIndex() 剪掉「文件已不在」的幽灵账并写回
# jp2-v1.3.248：★★ 闹钟铃声改成真文件缓存（/sdcard/R1Alarm），去掉合成提示音
#   · 新增 AlarmCache.java：/sdcard/R1Alarm + index.json，下载/查/删/清
#   · Alarm：本地缓存优先、歌播完重放本地铃声、指定即缓存、随机且目录空则补一首
#   · PlayerBridge + WebConsole：新 op play_local（推 file://，MP/Exo 都认）
#   · 删除 AlarmTone.java（用户：生成的提示音不需要了）
# jp2-v1.3.247：★★ 修「响铃结束后音量还原不回来」
#   · AlarmVol.isTouched()：收尾判断不能再挂在「提示音还在响」上
#   · Alarm：试响 5 分钟窗口 TEST_HOLD_MS；maintain 收尾无条件 restore
#   · AlarmTone：write 负值先分辨「被 stop 收走」/ 真失败，别报假故障
#   · Alarm：vol op 文案从「抬系统闹钟流」改成「抬媒体音量」
# jp2-v1.3.246：★★ 闹钟三补强（随机含在线 / 提示音兜底 / 音量兜底）
#   · 新增 AlarmTone.java：AudioTrack 合成「嘀嘀嘀+长音」（系统无铃音文件）
#   · 新增 AlarmVol.java：STREAM_ALARM 保底抬量 + 关闭还原
#   · Alarm：fire 三段式（抬量→出声→切歌）、三池随机、maintain 续响、vol op
#   · XiaoZhiWs：self.r1alarm.vol
# jp2-v1.3.245：★★ 闹钟（语音 / web 双向，响铃播歌，停用语音助手照响）
#   · 新增 Alarm.java：files/r1_alarms.json + 5s 调度线程 + mcp() 统一入口
#   · XzBootstrap 装配 Alarm.init（必须早于 WebConsole）
#   · WebConsole：/api/alarm + doAct 的 alarm_* 六件事
#   · XiaoZhiWs：self.r1alarm.{add,list,toggle,del,dismiss}
# jp2-v1.3.244：★★ 备份歌单连「绑定信息」一起备份（重传不用再绑一次）
#   · fav_add 新增入参 rtitle/rartist/ltitle/lartist/rman（WebConsole 收、Fav.add 落）
#   · Fav.fill（已在收藏里补空）扩到这些字段 + rman
#   · restoreFromPrev：本次带了的以本次为准，并把 rman 也捡回来
# jp2-v1.3.243：★★ 手动重链接（长按歌名 → 在线挑一版 → 永久绑过去）
#   · 新 op=fav_link（dispatch + WebConsole 双登记）：参数带 source/rid 直接绑
#   · 只改收藏主本；清 url/ltitle/lartist（它们排在 source 前面，不清等于白绑）
#   · 不在收藏里的顺手收进收藏；打 rman=1（手动意图，purge 不清）
#   · playFav / playFavOne：rman=1+有源 ⇒ 跳过本地库匹配（①/①b/②/③）
#   · retargetQueue：改 tracks 快照里的地址，正播着才原地重推（subType=15）
#   · Fav.hit（真名 key 也认）+ Fav.force（覆盖写，patch 只补空）
# jp2-v1.3.242：★★ 收藏解析不再被「翻唱版」顶掉（第 11 首配出翻唱）
#   · 真因：《XX（翻自 YY）》被 fold 后把原曲名整条包含 ⇒ 92 分；
#     原唱标题写歪一个字母（bitch/bitches）只能走词元覆盖 ⇒ 90 分 ⇒ 翻唱胜出
#   · searchPick 加歌手闸门：歌手零重合的候选不参加打分（也堵住「同名即 return」）
#   · osScore：92 档不再豁免（sc<92 → sc<100），同名压到 60 ⇒ 必然标 unsure
#   · Fav.purgeUnrelated：rtitle/rartist 也查歌手，清历史脏缓存（+ url）
#   · favBind 写 rman=1，purge 跳过手动绑定的条目（明确意图不清）
# jp2-v1.3.241：★ 从收藏导入播放列表**不设数量限制**
#   · playFav 链路四处 cap 全撤（主循环/占位/got/need）⇒ 收藏多少排多少
#   · Fav.MAX 500 -> 5000（只做物理安全阀：patch 要整份重写盘）
#   · 真天花板挪到 pushPlaylist：Binder 1MB ⇒ 700KB 字节预算（UTF-8）
# jp2-v1.3.240：★ fav_resolve 的本地模糊命中也过歌手闸门
#   · 《SKINNY》词元被《Skinny Love》100% 覆盖 ⇒ 又配成 Birdy 并写回 ltitle
#   · 命中后 Fav.artistSame 校验，歌手不是一个人 ⇒ 弃用，改走在线搜
# jp2-v1.3.239：★ 收藏里的直链地址也要验「是不是这首」（修「显示 SKINNY 播 Birdy」）
#   · url .../m/Birdy - Skinny Love.mp3 被挂在「SKINNY / Billie Eilish」名下
#   · urlMatchesFav()：NAS 本地文件名 歌手 - 歌名 ⇒ 歌手冲突/歌名不相关就退成占位
#   · playFav ③⑤ + playFavOne ③⑤ 全部过闸
# jp2-v1.3.238：★ 播收藏单条不再换成别人的版本（保留 source+rid）
#   · 真因：页面只看 it.url ⇒ 空就 play_song 按歌名重搜，搜索结果按热度排
#     （收藏 Billie Eilish《hotline bling》= kw/57047050 ⇒ 播出 Drake kw/6539505）
#   · 新增 op=play_fav_one（dispatch + WebConsole 双登记），解析一首时优先 source+rid
#   · Fav.artistSame() + purgeUnrelated：清「歌名一样、歌手全不同」的 ltitle 脏缓存
# jp2-v1.3.237：★ 导入播放列表后队列顺序 = 收藏顺序（修「顺序发生变化」）
#   · 真因：本地命中/直链/在线源是边走边入列的，光杆歌(place)循环后才补进尾部
#   · 修法：pick 配一条并行 pickFav（收藏序号），push 前稳定插入排序重排
# jp2-v1.3.236：★ 收藏 k 字段自愈（k 写坏一条 ⇒ 永远配不上、永远没星）
#   · purgeUnrelated 全表重算 k = key(title, artist)，不一致就落盘
#   · 实测《风继续吹》k 被写成 "风继续\uFFFD\uFFFD\uFFFD|张国荣"，title 完好
# jp2-v1.3.235：★★ ① 同名不同歌手不再乱认（播错歌）+ 命中即记库内真名（修「很多歌没星」）
#   · findLocal title-only 回退加歌手闸门（词元零重合 ⇒ 不认，退成占位/在线搜）
#   · 库里同名多首 ⇒ 挑歌手对得上的那一首；库内歌手==歌名（ID3 脏）⇒ 仍认
#   · playFav ① 命中时把库内真名写回 ltitle/lartist（display 仍走 rtitle>ltitle>title）
# jp2-v1.3.234：★★ 负缓存不再把歌从播放列表里剔掉（修「少了歌 / 被跳过」第三条路径）
#   · playFav 主循环 + tail 的 `if (Fav.skipSearch(it)) continue;` 整段撤掉
#   · 导入期零搜索（v1.3.229 起）⇒ 负缓存已无意义，只会让搜败过的歌整首消失
# jp2-v1.3.233：★★ 撤导入/排队列离线匹配（改回播到才临时匹配）+ 修 pushPlaylist 丢歌
#   · 用户要求：匹配只在播到那首时由 fav_resolve 临时做；导入/排队列不匹配
#   · 撤 playFav 主循环 ⑥ + tail ⑥ + WebSrv 导入后自动 fav_rematch + UI 按钮
#   · pushPlaylist：msg 失败先原样重试 3 次再砍半（原来一失败就砍半 ⇒ 播放列表少歌）
# jp2-v1.3.232：★★ 导入即离线批量匹配（fav_rematch）+ tail 循环补匹配
#   · 根因：导入只 fav_add 原文不匹配；⑥ 只在 play_fav 且 pick>=cap 就 break
#     ⇒ 357 首里 154 首在本地库却没 ltitle（没星），播放时整首被跳过
#   · 抽出 favLocalMatchIdx()：主循环 / tail / fav_rematch 共用同一份离线匹配
#   · tail 循环（超 cap）也跑 ⑥；新增 op=fav_rematch 全量离线重匹配
# jp2-v1.3.231：★★ 恢复离线本地模糊匹配 + 占位「尝试失败才红」
#   · v1.3.230 误删 playFav ⑥ 离线模糊匹配 ⇒ 法语歌《Il aurait suffi》类变占位+全红
#   · 用词元倒排索引 favLocalCands 只比共享词元候选（库再大也亚秒级），绝不碰在线搜索
#   · 红基改由 player 侧「已尝试且失败」驱动（/r/fav 404 且 device 真尝试过）
# jp2-v1.3.230：★★ 导入期零匹配 —— 精确匹配 + 占位入列，模糊/在线全留到播到才解析
#   · playFav 三处 findLocalFuzzy 全删（357×65 分词打分是 37 秒卡顿真凶）
#   · 模糊匹配移到 fav_resolve 播到时单次做，本地有的歌优先播本地文件
# jp2-v1.3.229：★★ 导入零在线搜索 —— 占位地址入列，播到才由 fav_resolve 按需解析
#   · 删掉 playFav 里 warmFavSource(整份 need) 的全量后台预搜
#   · 搜索只在真正播放那首时按需发生，缓存随播放自然积累
# jp2-v1.3.228：★★ 导入不再批量在线搜 —— 占位地址 + 播到才解析（fav_resolve）
#   · 本地没命中的歌先拿 http://127.0.0.1:8080/r/fav?k=<key> 排进列表 ⇒ 导入 1~2s
#   · 播到它时 player /r/fav 回头问 device，现搜一次再 302 到真地址
#   · 后台 warmFavSource 顺序补源 = 「提前搜接下来几首」
# jp2-v1.3.227：★★ 收藏缓存跨导入存活（fav_clear 留快照，fav_add 捡回）
#   · 重传同歌单不再冷启动（上一轮 357 首 fuzzy+补源要 60~80s）
# jp2-v1.3.226：★★ 歌手从加分项升级为判据 + 覆盖率按请求侧算
#   · Fav.coverQuery：短歌名不再能「覆盖」长歌名 0.69
#   · 歌手完全不对且歌名没包住对方 ⇒ 不认（干掉 Don't Wanna Know / the 1 / in the end）
#   · 歌手对牢 ⇒ 放宽歌名（容忍译名 / (Live) / 版本差异）
# jp2-v1.3.225：★★ 收藏解析全程改用「按词匹配」，不再用子串
#   · findLocalFuzzy + Fav.tokScore：il 不再被配进 chill、of 不再被配进 Opera
#   · 库侧词元预算 KT/KAT（每首歌只 fold 一次）⇒ 导入不再把同一件事做 357 遍
#   · 播放列表满了也继续解析剩下的收藏（以前直接不看了 ⇒ 缓存练不出来）
# jp2-v1.3.224：★★ 修「正常歌反而识别不出来」+ 法语歌配成英语歌 + 缓存不复效
#   · Fav.purgeUnrelated()：全表清脏缓存（不再受播放列表 cap 限制）
#   · Fav.fold / Fav.cover：去注音 + 词元覆盖，Côme 认得回 Come
#   · favSearchOne 只拼主歌手；带歌手拿不准时用纯歌名再搜一次
#   · 本地宽松匹配砍掉「搜索词里含库名」那两档（纯猜，是错配元凶）
#   · unsure 也记负缓存 ⇒ 每轮不再重复搜同一批歌
# jp2-v1.3.223：★★ 修「随便写的名字也能匹配到」+ 收藏列表显示真名
#   · favSearchOne 不再拿第一条凑数：打分低于 FAV_HIT_MIN(80) 标 unsure
#   · 在线补源用搜回来的真名推队列，并回写 rtitle/rartist
#   · Fav.realHit()：原名 / 真名算出来的 key 都认同一首（星标、取消收藏）
# jp2-v1.3.222：★★ 修宽松匹配「空串恒真」误配（整批收藏被指到同一首歌）
#   · lenientScore() 打分排序；反向包含要求被包含方 >= LENIENT_MIN(3)
#   · ①b 脏缓存自愈；⑥ 只固化 strict 或 score>=80，猜的不落盘
# jp2-v1.3.221：★★ 让导入播放列表第二次起只要 0.几秒
#   · 模糊匹配结果落盘（新字段 ltitle/lartist = 库里那首的真名）
#   · 新增路径 ①b：用真名走 findLocal() HashMap O(1) ⇒ 模糊匹配一次都不跑
#   · Fav.patchBatch()：整批只写一次盘（值相同不动、值不同才覆盖）
# jp2-v1.3.220：★★ 修「每次导入播放列表都重新搜一遍」
#   · 本地库模糊匹配提到「进 need/在线搜」之前 ⇒ 本地有的歌永不在线搜
#   · 15s 预算没轮到的光杆丢后台 warmFavSource() 补完并落盘 ⇒ 补完零在线搜索
# jp2-v1.3.219：★★ 播放列表兜底宽松匹配 + 回报未匹配歌名
#   · 新增 matchTracksLenient()：曲名包含 / 被包含即算匹配（降低匹配度要求）
#   · playFav() 收集 missedTitles，回传末尾附 \n###MISSED### 供网页标红 + 弹窗
# jp2-v1.3.218：★★ 夜灯「只亮底部」时放行顶部拾音灯（聆听灯正常工作）
#   · 闸门从「夜灯一开全拦」改成「只拦被夜灯占了的那组」
#   · 顶部被占（都亮 / 只顶部）才拦；只亮底部 ⇒ 1..24 方向灯 + 203 播报灯照常
#   · 切到占用顶部时先 stopPickup()（守灯 1.5s 一次写白，会跟动画互抹）
# jp2-v1.3.217：★★ 开夜灯底部改发 ambon（让 player 完整进夜灯态）
#   · 以前发 all:<hex> 只写色 ⇒ player 不停七彩、不拦原厂、不守灯
#     ⇒ 「小智说开了实际没开」+ web 状态不同步
# jp2-v1.3.216：★★ 夜灯切换不黑窗 —— 底部交给 player 收尾
#   · 关灯不再 show 504 写黑 A 组，改发 op=led1&v=ambback
#     （player 先让氛围灯接手，没人接手才兜底写黑）
#   · forceBlack() 收窄成 forceBlackTop()：只写黑 B 组顶部
# jp2-v1.3.215：★★ 夜灯 —— 清场不再自抹 + 关夜灯把原厂氛围灯接回来
#   · 开灯清场只 close、不 show 504/505 ⇒ 不再「亮一下就灭，七八秒后才亮」
#   · 写黑抽成 forceBlack() 只在关灯路径调
#   · 关灯最后 restoreAmbientLater()：等写黑落地后再把原厂氛围灯开回来
# jp2-v1.3.214：★★ 夜灯 —— 「总开关」和「亮哪一组」彻底分开（修逻辑混乱）
#   · 症状：夜灯关掉之后，在 web 上点一下「只顶部 / 只底部」灯又亮了；
#     开关状态和高亮还互相打架、来回跳。
#   · 根因：「亮哪一组」发的是 `top:1 / amb:1`，而这两个指令**本身就会上灯**
#     ⇒ 它事实上是第二个开关。
#   · 新口径（player pl-1.6.54 同口径）：
#       on / off          —— **唯一的开关**：开 = 按 part 选的组亮，关 = 全灭 + 灯效恢复
#       part:both|top|amb —— **只是配置**：夜灯关着时改它只记下来，不点灯；
#                            开着时改它立刻重排（该灭的那组写黑）
#       top: / amb: 保留作调试，动完 syncSel() 把 part 同步成实际亮着的组合
#                  （两个都关时不动 part —— 那是关灯，不是「改成不亮任何一组」）
#   · status 的 nightJson 新增 on（总开关真值）+ part（当前选择），页面照这两个回显。
#   · 持久化 files/night.txt 增加 part=，重启后按同一口径接回去。
# jp2-v1.3.213：★★ 夜灯改成「顶 / 底都由 player 直写 sysfs」（照抄小飞版）
#   · 小飞版的开灯 / 关灯是跑 shell：`lights_test set 7fffff8000 ffffff` / `... 0`
#     —— **不走灯效服务**，直接写 sysfs。掩码 0x7FFFFF8000 = 灯号 16..39 = 顶部 24 颗。
#   · 我们照抄：夜灯的「顶」不再 `show 503`，改 `op=led1&v=top:<hex>|topoff`
#     （player 直写，掩码 0x7FFFFF8000）。灯效服务那条路天生关不干净 ——
#     效果被抢占只是挂 pending、等会儿复活，老 pid 发的效果还 close 不掉
#     （`can not close other process's lights`）⇒ 表现就是「顶部关不掉」。
#   · player 侧加了 8 秒一次的「守灯」重写：万一有别的效果补位复活把它盖了，能压回去。
#   · device 侧保留：清场（逐个 close）+「夜灯期间所有灯效停用」的闸门 + 持久化。
# jp2-v1.3.212：★★★ 夜灯「关不掉」真修 —— 清场改成**逐个 close**
#   · 症状：关夜灯时拾音灯只是闪一下又亮起来；氛围灯一直亮着，改颜色也没反应。
#   · ★★★ 真因：灯效服务有 **pending 复活** 机制 —— 效果被抢占时并不删除，只是挂到
#     pending，等抢占它的那个一结束就 `pending light X turned to active` 原地复活。
#       开夜灯 show 503 → 抢占原厂氛围灯 251 → 251 挂 pending；
#       关夜灯 show 505 → 抢占 503        → 503 挂 pending；505 一 close，503 复活。
#     日志铁证：close light 503 → deleted → show light 251 → pending 251 turned to active
#   · 修法：清场不再「抢占」，改成把 CLEAR_IDS 里每一个 id **逐个 close**（多轮，因为
#     close 一个会把 pending 栈顶的下一个弹回来），最后再靠 505 / 504 两个 FORCE 灭
#     把 B / A 两组写黑。改色 / 调亮度时只清 A 组（ambClear，不动顶部的 503）。
#   · 其余同 v1.3.211：夜灯期间所有灯效停用（send 闸门）、顶 503 + 底 player 直写 A 组。
# jp2-v1.3.211：★ 夜灯 —— 拾音灯全亮 / 氛围灯全亮（可只开一样），颜色亮度可调
#   · 用法：喊「开启夜灯」/「关掉夜灯」（MCP 工具 self.r1light.night），
#     也可以只开一样（top / amb 分别控制）；web 页面「灯光控制」里能调颜色和亮度。
#   · ★★ 这次能成的关键：夜灯期间**所有灯效全部停用** —— send() 那道闸门把
#     唤醒灯 / 播报灯 / 聆听灯全挡住，player 侧也把原厂氛围灯总开关关掉。
#     灯效服务一活动，它收尾那次「异步清灯」就会把我们的灯抹掉
#     （v1.3.206~209 单颗直写两轮实测都不亮、当年「夜灯模式」做不成的同一个原因）。
#   · ★ 顶 / 底必须分走两条路：灯效服务**同时只能跑一个效果**
#     （实测 `light 251 preempt light 503`：同 channel 0、同 priority 1，后到的抢占先前的）
#       ⇒ 顶部 24 颗 = 灯效服务 503（FORCE 全白常亮，本来就关不掉，正好当夜灯）；
#         底部 15 颗 = 借 player 的手直写 A 组（device 是 u0_a8，写不了 sysfs）。
#   · 亮度上限 **100**：小飞版 web 上氛围灯亮度标 1..200，但那只是它自己的口径 ——
#     实测原厂 setLumaGain 只收 0..100，传 200 静默 return -1。夜灯的亮度是我们自己
#     缩放 RGB，不过原厂那一关，但同口径取 0..100，免得页面两套数字对不上。
#   · 配置落 files/night.txt，进程重启 3 秒后自动接回去。
#   · 配套 player pl-1.6.51：新增 op=led1&v=all:<hex>|alloff（A 组整组写色）
#     与 op=night 路由（自己写底部 + 原样转发 device 管顶部）。
# jp2-v1.3.210：★ 聆听灯回退 —— 直接调<b>完整动画</b>（send(idx, ON)）
#   · 206~209 那套「单颗直写」整条拆掉：唤醒播动画 → 说话灭灯 → 说完
#     setListening(true) 重新 send(idx, ON) 播一遍完整动画（5s 流动后定格在方向灯）。
#   · 为什么要回退：单颗直写两轮实测都不亮 —— 灯效服务（system_server）收掉
#     最后一个效果后的清灯是**异步**的，我们写的那一颗会被它盖掉；
#     209 加的「写完补三刀（300/1000/2500ms）」同样没救回来。
#     ⇒ 认了：这 24 颗拾音灯只能交给灯效服务自己播。
#   · 保留：灭灯防抖 250ms（v1.3.205）、唤醒同灯位不重播（v1.3.205）、
#     唤醒灯/播报灯/聆听灯三个独立开关（web 页面「灯光控制」四项）。
#   · 保留单颗直写的**调试入口** op=light&v=pick:N / pickoff（player pl-1.6.50
#     那条 sysfs 通路还在），以及灭灯时对它的兜底写黑（防老版本残留）。
# jp2-v1.3.209：★ 拾音灯「抢灯补偿」—— 写完隔 300ms / 1s / 2.5s 再补三刀
#   · 症状：device 记账写了、player 也回了 ok、直写本身目视确认能点亮（写 1 颗就亮
#     1 颗，几分钟都不掉），可一轮真实会话里「小智说完方向灯就是不亮」。
#   · 真因（★ 用户点破：唤醒灯和我们亮起的灯是两套路子，会打架）：
#       21:01:15.607 light 播报灯 off          ← busyOff() 发 close 203
#       21:01:15.611 light 拾音灯 on（单颗直写）  ← 隔 4ms 我们就写白
#     灯效服务（system_server）收掉最后一个效果是**异步**收尾的，它那次清灯落到
#     硬件的时刻可能晚于我们这 4ms ⇒ 刚亮就被抹掉。
#     「夜灯模式」当年做不成是同一个原因：只要旁边还有灯效服务在活动，
#     直写的那颗随时会被它收尾时的清灯带走。
#   · 修法：写完不指望一次成功 —— scheduleFix() 排 300/1000/2500ms 三次 reassert()，
#     每次绕过去重强制写一遍同一颗（幂等、不播动画，不会「多播一次」）。
#     灭灯路径（busyOn / allOff / offNow / 调试 idle）一律 cancelFix()。
#   · 配套 player pl-1.6.50：单颗直写改成 sysfs 直写（与 lights_test 同格式），
#     native 那一路退为备份。op=led1&v=state 末尾会带 sysfs=ok / ⚠ sysfs:<异常>。
# jp2-v1.3.208：★ 灯效时序改回「唤醒播动画 → 小智说话灭灯 → 说完方向灯再亮起」
#   · v1.3.207 在唤醒那一刻就抢先单颗直写了一次，观感变成「方向灯先亮、然后才播动画」，
#     与需求相反。现在唤醒**只播动画**（原厂反馈），定格那颗就是「在听」的灯。
#   · 动画播完（ANIM_MS=5.4s）由 ensurePick 补一次单颗直写并兜住定格那一帧。
#   · 小智说话 = onStt -> busyOn()：拾音灯灭 + 蓝色播报环（并把 sAnimTs 清零，
#     ⇒ 动画算「已被打断」，之后「再亮起」立刻生效，不用再等 5.4s）。
#   · 说完 = onTtsStop -> busyOff() + setState(LISTENING) -> setListening(true)
#     -> 此时 sAnimTs=0 ⇒ rest<=0 ⇒ **立刻**单颗直写那一颗，不播动画。
#   · 去重口径仍是 sPickIdx == idx（v1.3.207 的修法保留）：不会被 sListen 一刀切挡住。
# jp2-v1.3.207：★ 聆听灯的「亮」不再把希望全押在唤醒动画的最后一帧
#   · 症状：唤醒之后整段会话的聆听灯全靠动画定格那一帧撑着 ——
#     setListening(true) 被 sListen 记账一刀切去重挡住，device 侧一条写灯都发不出去，
#     动画没兜住（灯效服务被别的效果挤掉 / close 清掉最后一帧）就全程不亮。
#   · 修法三条：
#     ① 唤醒时**立刻**再单颗直写一次同一颗（走 player native，不播动画），
#        「在听就亮」从唤醒那一刻就成立，不用等 5 秒动画定格；
#     ② 动画播完（ANIM_MS=5.4s）再补写一次（ensurePick），把定格那一下兜住；
#     ③ setListening(true) 的去重口径从「sListen」改成「sPickIdx == idx」——
#        单颗直写是幂等的（不播动画、重复写同一颗看不出差别），放行它只会更稳，
#        不会造成「多播一次」（动画只在 onWakeup 发，且灯位不变时跳过）。
#   · 动画在播期间（sAnimTs 起 ANIM_MS 内）不立刻写，改成排 ensurePick，
#     免得「直写」和「动画每一帧」互相盖。
# jp2-v1.3.206：★ 拾音灯「非唤醒的点亮只亮最后那一颗」，不再重播 5 秒动画
#   · 背景：一次 0x1000/idx/0（灯效服务）就是 25 段 ×200ms 的流动动画，最后才定格。
#     唤醒时用它没问题（原厂反馈就是这么设计的），但「播报结束回聆听」「硬重启续接」
#     这类非唤醒点亮再走它 ⇒ 每切换一次状态就重播一遍动画。
#   · 新路：只写「最后定格的那颗」—— 经 player 的 LedLight native 直写 LED，
#     完全绕开灯效服务，不播动画。
#   · 为什么必须借 player 的手：device 是 u0_a8，实测写
#       /sys/class/leds/multi_leds0/led_color => Permission denied（SELinux），
#     而 player 是 system(1000)、有 native 的 LedLight.set_color(mask, color)。
#     所以 device HTTP 到 127.0.0.1:8080 的 /api/act?op=led1（player 侧新 op）代写。
#   · 掩码 bit(N-1) = 灯号 N；拾音灯 = 灯号 16..39 ⇒ 第 k 颗 = 1L << (14+k)。
#     （写入格式是「掩码+颜色」不是「编号+颜色」，详见 R1/LIGHTS-灯组与灯效.md §6）
#   · 唤醒那一闪仍然走 send(idx, ON) 播动画；此后 sPickIdx 交回 0（灯由服务接管）。
#   · 灭灯时**总是**显式写黑一次（按 lastIndex）：灯效服务的 close 只停调度，
#     不保证把 LED 写黑（503 那种 FORCE 效果就是关不掉的）。
# jp2-v1.3.205：★ 修「灯效多播一次」（灭 → 亮 的快速翻转把动画重播了一遍）
#   · 现象：一次唤醒，拾音灯的动画播了两遍 / 亮一下灭一下又亮。
#   · 根因（设备日志实证，每一轮都有，间隔 12~20ms）：
#       light 拾音灯 off（not listening）   <- enterIdle: setState(IDLE)
#       light all off                       <- enterIdle: R1Lights.allOff()
#       light 拾音灯 on（listening index=N）<- 硬重启 startSession(): setState(CONNECTING)
#     原厂灯效服务每收到一条 show 就把动画从头播一遍，于是「灭了又亮」= 多播一次。
#     触发条件是「唤醒打在僵尸会话上」⇒ hardRestart() ⇒ enterIdle 收尾后马上开新会话。
#   · 第二处：会话内又喊一次唤醒时，onWakeup 无条件再发一条 ON（灯本来就亮着）
#     ⇒ 同样重播一遍。现在灯已亮且灯位没变就跳过（方向真变了才更新）。
#   · 修法：
#       ① 灭灯防抖 —— setListening(false) 推迟 OFF_DEBOUNCE_MS(250ms) 执行，
#          窗口内若又要求「亮」则撤销灭灯，且因为 sListen 记账还是 true，
#          「亮」那条报文也被去重挡掉 ⇒ 整段「灭→亮」塌缩成「什么都不做」。
#       ② 硬重启路径显式 keepListening() 撤销灭灯，不再调 allOff()。
#       ③ 同步灭灯路径（busyOn / allOff / wipe / 调试 idle）先 cancelOff()，
#          保证任何时候都只发一轮灭灯报文。
# jp2-v1.3.204：★ 拾音灯跟随「小智在听」（聆听灯）
#   · 用户要的效果：唤醒后最后定格在音源方向的那颗拾音灯，改成小智的聆听指示灯 ——
#     在听就亮，没在听就灭。
#   · 驱动点只有一处：SessionController.setState（状态写入的唯一入口）⇒
#       CONNECTING / LISTENING       -> 亮（沿用唤醒那一刻读到的 DOA 方向）
#       TTS_PLAYING（思考 / 播报）   -> 灭（蓝色播报灯接手）
#       IDLE（会话结束）             -> 灭
#     ★ 服务器告别语那次转 LISTENING 不算「在听」（farewellActive）⇒ 不亮。
#   · ★ CONNECTING 也算在听：唤醒那一刻 UscTap.onWakeup 刚按 DOA 点亮，紧接着
#     startSession() 就切 CONNECTING —— 只认 LISTENING 的话这一闪会被自己秒灭，
#     唤醒反馈反而没了。
#   · 方向不重新猜：DOA 只有唤醒那一刻读得准（原厂引擎只在那时给角度），
#     之后沿用 lastIndex，跟「最后指向音源的那颗灯」是同一颗。
#   · 独立开关 files/listen_light_off（页面「灯光控制 · 聆听灯」）：
#     关掉只影响「全程亮着」，唤醒那一闪照旧；与 wake_light_off 是「与」关系
#     （同一组灯，两个开关都满足才亮）。
#   · 去重：R1Lights.sListen 记着灯此刻亮没亮 ⇒ 状态抖动不会刷 24 条灭灯报文。
#   · 调试：?op=light&v=listen / v=idle（不看当前状态，强制亮 / 灭）。
# jp2-v1.3.203：★ 播收藏不再慢（负缓存 + 不提前取链）
#   · 用户报：收藏导入要好几秒，而搜出来几百首的曲库导入是秒回。
#   · 慢在两个地方：
#       ① 每条「只有歌名」的收藏每次导入都要在线搜一次 source+rid（1s 左右）；
#          而且搜不到也不记 ⇒ 下一次导入照样再搜一遍，白卡一秒。
#          ⇒ 负缓存：搜到就回写 source+rid；搜不到就记 nsTs，
#            6 小时内不再为它发搜索（Fav.skipSearch / Fav.markNoSrc）。
#       ② 第一首还额外 onlineUrl() 正式取一次链（0.5~1.5s）。
#          ⇒ 用户早就订正过「歌曲源是播放时临时拉的，不用提前拉源」，
#             两处正式取链全删，一律推惰性地址 /r/<source>/<rid>。
#   · 结果：同一批收藏第二次起就是「零网络请求」，跟导入几百首一样快；
#     第一次遇到新光杆条目才会慢一拍（页面按钮已提示）。
# jp2-v1.3.202：★ 只保留「导入时」那一次补源，收藏时不再提前补
#   · 用户订正：歌曲源是**播放时临时拉的**（惰性地址 /r/<source>/<rid>，
#     播到那一首才去取链）⇒ 收藏那一刻没必要发网络请求去补源。
#   · 所以 favFillAsync（收藏后后台线程补源）整段删除。
#   · 保留 playFav 里的补源：只针对「source/rid/url/index 全空」的历史条目
#     （这类条目不补就整首跳过 = 「收藏 9 首、导入只有 4 首」）。
#     ★ 它补的只是 source+rid（源标识），**不是取链** —— 取链仍在播放时做，
#       补到的源会回写收藏，同一批第二次导入就是 0 搜索。
# jp2-v1.3.201：★ 收藏里「只有歌名」的条目在线补源
#   · 症状：收藏 9 首、导入只有 4 首 —— 收藏登记时常拿不到地址（语音收藏时
#     player 给的 curUrl 可能为空、队列里的在线歌也可能还没取链），条目只剩
#     title+artist；本地库里又查不到 ⇒ 播收藏时整首跳过。
#   · 修法两条：
#       ① 播收藏时（playFav）对这些光杆条目在线搜一次拿 source+rid
#          （NAS /api/search，单次 5s、总预算 15s），拿到就拼惰性地址入列，
#          并**回写收藏**，下次不用再搜；
#       ② 收藏时就地补源（favFillAsync 后台线程），趁早把 source+rid 存下来。
#   · player 侧 fav_play 转发预算 25s -> 45s（补源要时间，否则假失败）。
# jp2-v1.3.200：★ 我的收藏（web 手动 ☆ + 语音收藏/取消/播放收藏）
#   · 主本在 device：files/r1_fav.json（Fav.java），player 只读缓存 + 转发。
#     为什么不放 player：语音（MCP）活在这边，曲库/在线四件套也在这边，
#     播收藏要回库反查；player 重启频繁，当不了主本。
#   · key = norm(曲名)+"|"+norm(歌手)（只留小写字母/数字/汉字）——
#     与 player 侧 Fav.key()、页面 favKey() 必须逐字一致，否则星标亮错。
#   · web：op=fav_add / fav_del / fav_toggle / fav_add_cur / fav_del_cur /
#          fav_clear / fav_play，外加 GET /api/fav（player 反代它做列表）。
#   · 语音：self.r1player.fav_add / fav_del / play_fav —— 不带参数 = 当前在播的那首。
#   · ★ 播收藏**本地优先**：本地曲库按「曲名+歌手」命中就播本地直链，
#     拉不到才用在线四件套取链（用户明确要求）。
# jp2-v1.3.199：★ 语音软开关（省电模式）—— 停用「KWS 唤醒 + 收音 + 小智本体」，
#   web 端的电台 / 音乐搜索 / 点播全部保留，用来在不说话的时候降功耗与发热。
#   ★ 为什么不能只停寄生层：USC_KWS_ONLY=true 时寄生层 IDLE 期根本不占麦；
#     真正的大头是原厂 USC 自己那三个 native 线程（usc_record_thre /
#     usc_fix_thread / usc_vad_thread，2026-10-19 实测常驻 ~107% CPU）。
#     ⇒ 开关必须能真的停掉原厂 USC 的「唤醒 + 录音」，否则一点电也省不下来。
#   三处协作：
#     ① smali SpeechManager.<init> —— 把 ANTEngine 实例交给 VoicePower 保管；
#     ② smali NativeANTEngine.doStopWakeup() —— v1.3.74 的 no-op 改成受控：
#        正常态仍 no-op（一字不差），省电态才走原厂 j.b()（= SpeechUnderstander
#        .cancel()，native 停唤醒 + 停录音）；
#     ③ smali NativeANTEngine.doEnterWakeup(Z) —— 省电态拦掉原厂任何一次「进唤醒」，
#        否则刚 cancel 完又被按键 / 网络恢复拉起来。
#   新增 VoicePower.java（保管实例 + files/voice_off 持久化 + 反射动引擎 + 关 WS），
#   WebConsole 加 op=voice（v=on|off|state），/api/status 增加 voice 段。
#   ★ 补丁脚本 patch_voice_power.py（幂等）必须先跑 —— build 里有断言盯着。
#
# jp2-v1.3.197：补上 op=queue_all 的 WebConsole 路由（v1.3.196 漏了）
#   ★ 症状：控制台「搜索曲库 → 全部载入播放列表」点一下弹 "unknown op queue_all"。
#   ★ 根因：PlayerBridge.dispatch() 里 queue_all 早就实现了（v1.3.196），
#     但 **WebConsole 的 /api/act 路由表没登记它** ⇒ 一路掉到方法末尾的
#     `return "unknown op " + op`。这正是当年 play_index 栽过的同款坑
#     （build 脚本里那两行注释就是当年的记录）：dispatch 支持 ≠ WebConsole 认。
#   ★ 修：在 play_url 之前插一条 queue_all 分支，把 ids / limit 原样传给 dispatch。
#
# jp2-v1.3.196：专辑占位名过滤 + 电台切台清残留 + 「全部载入播放列表」
# jp2-v1.3.196：专辑占位名过滤 + 电台切台清残留 + 「全部载入播放列表」
#   ① 专辑占位名（用户原话：「没有专辑信息的好像都会显示 billboard hot 100」）
#      —— 源头是本地 mp3 的 ID3 tag：从榜单合集下来的文件 album 填的是榜单名。
#      实测 1288 首里 65 首命中（Billboard Hot 100 14 / Hot Dance & Electronic 50 /
#      带日期版 1）。新增 PlayerBridge.sanitizeAlbum()，命中「榜单/合集/占位」的
#      一律按「没有专辑」处理；notePlayingFull / notePlayingBus 两个赋值点都走它。
#   ② 电台切台当场清掉上一首歌的专辑/时长/来源
#      —— 直播流要等 player 回推「当前曲目」（1~3s），空窗里卡片还挂着上首歌的信息，
#      于是「播的是电台，专辑却写着 Billboard Hot 100」。
#   ③ 新增 op=queue_all（网页「搜索曲库」那一栏的「全部载入播放列表」按钮）：
#      ids = 曲库序号（1 起，逗号分隔），留空 = 整个曲库；两种都受 maxPlaylist 约束。
#      页面同步去掉「按歌手连播」卡片（语音路径 play_artist 保留）。
# jp2-v1.3.195：★ 去掉电台起播「蓄水 5 秒」——player 换 ExoPlayer 后前提条件消失
#   背景：v1.3.194 的 5 秒蓄水是为**原厂 NuPlayer** 打的补丁。NuPlayer 收到重复 play(kick)
#   会 stop+reset 重开声卡，直播流没有 Content-Length ⇒ 每次 reset 把缓冲清零 ⇒
#   起播 ~6s 处必卡 3~4s。给它 5 秒蓄水只是**躲开**这个 reset 窗口，代价是出声慢 6 秒。
#   player 侧（pl-1.6.22+ / pv37 的 ExoPlayer）换引擎后，重复 kick 变成幂等的
#   PlayWhenReady 翻转，不再 reset、不重开声卡 ⇒ 蓄水没意义了。
#   ⇒ NET_STREAM_KICK_MS 5000L → 800L（回到 v1.3.194 之前的原值），
#     电台/网络流起播恢复「~1.3s 出声」。若哪天回退到原厂 player，改回 5000L。
#   本版结构性改动为零，纯参数回退 + 一次性同步升级（设备从 1.3.168 直上 1.3.195）。
#   铁律补强：javac / R8 的返回码现在会被硬检查，编译挂了直接 exit 1（以前会装旧 dex）。
# jp2-v1.3.194：★ 彻底修掉「电台起播后停 3~4 秒」——网络流起播前蓄水 5 秒
#   实测拐点（同一 64k 电台，每档跑两遍）：
#     首脚延迟 0.8s → 停 3.0~4.0s ｜ 3s → 2.0s ｜ 4s → 2.0s ｜ 5s → 0 ｜ 6s → 0
#   ⇒ NuPlayer 对无 Content-Length 的直播流「prepare 完就开闸」，靠边播边补必然见底；
#     给它 5 秒把 resume 水位攒够，buffer low 一次都不出（且总出声时间反而更早：
#     0.8s 方案 1.3s 出声→5.3s 卡 4s→9.3s 连续；5s 方案 6s 出声后一路连续）。
#   落点：NET_STREAM_KICK_MS=5000，只给「裸网址 play_url / playRadio / playAllRadios」；
#         本地曲目仍走 startDelay(n)（几百毫秒，不受影响）；times 保留 2 次兜底，
#         但 kickPlay 会轮询 isMusicActive，已在出声就立即收手（v1.3.192）。
#   现场可调：files/kick_delay_ms 写数字即覆盖首脚延迟（v1.3.193）。
# jp2-v1.3.192：★ 修「电台/网络流起播卡 4.001 秒」——真凶=重复补踢 play
#   ① 2026-10-19 先用「降 usc_* 线程 nice」试过：nice -19/-8 → 10（prio 1/12→30）全程生效，
#      但 4.001 s 一秒没少 ⇒ **不是 CPU/调度问题**，昨天那套"device 抢 CPU"结论作废。
#   ② 同一次抓的 logcat 才是实锤：起播后 device 连发 5 条 subType=3(play)，
#      每条都让 player 走一遍 stopping→startInternal + pcm_close 重开声卡 ⇒
#      audio queue 反复见底 ⇒ NuPlayer buffer low ⇒ 停 4.001 s 才 resume。
#   ③ 改法：kickPlay 每发一条 play 后轮询 AudioManager.isMusicActive()（≤1.2s），
#      **已经在出声就立刻收手** ⇒ 正常只发 1 条；没起来才补（保留兜底）。
#      比"直接把 times 改成 1"更稳：慢 china 网络下真需要补时仍能补。
#   ④ 诊断工具（v1.3.191 引入，保留）：CpuGovernor + op=cool —— 线程 CPU 剖析/降级，
#      现在主要用途是**排除** CPU 类嫌疑。
# jp2-v1.3.191：给 USC 唤醒引擎的 native 线程让路（修「电台起播后卡 4 秒」的试验版）
#   起因（2026-10-19 A-B-A 实证）：device 进程活着时播电台必在起播 ~6s 处停顿 4.001s，
#   force-stop device 后一次 buffer low 都没有。线程剖面显示 usc_record_thre /
#   usc_fix_thread / usc_vad_thread 三个 native 线程常驻吃满 ~107% CPU，
#   而它们 nice 是 -19/-8（≈最高 CFS 优先级），把 mediaserver / 收包 / AudioTrack 挤死。
#   关键：这些线程**在我们自己的进程里** ⇒ 进程内 Process.setThreadPriority(tid, nice)
#   可以降（adb shell renice 不行：Operation not permitted，缺 CAP_SYS_NICE）。
#   落点：新增 CpuGovernor.java（scan / cool / restore / probe），WebConsole 加
#   op=cool（v=probe|on|off，n=<nice>，ms=<窗口>）。先做**手动触发**的试验版，
#   A-B-A 验证有效后再改成「播歌期间自动降、播完恢复」。
#   注意：还有一个 usc_record_thre 是 SCHED_FIFO / rt_priority 99，nice 对它无效
#   （RT 线程不参与 CFS 加权）；它 CPU 占用很低，本期不动。
# jp2-v1.3.175：在线搜歌全套（HTTP 入口 + 在线搜索接口 + 实际歌曲信息）
#   ① 用户抱怨串：「搜到的还是只有池子里的歌」「语音点歌也只播池子里的」。
#      根因两层：web /api/search 只搜内存 tracks（100 首池）；语音链上 MCP 模型
#      习惯先调 search 试探，池内空就直接回复「没找到」，根本走不到 v1.3.174 的
#      playOnlineSong 兜底。
#   ② 落点：
#      - PlayerBridge：nasBase()/onlineSearchJson()/playOnlineItem(六参)；
#        tracks 扩 String[7]（[4]album [5]duration [6]pic）；
#        播放元数据经 notePlayingFull 落盘（curAlbum/curDuration/curVia，
#        K_ALBUM/K_DURATION/K_VIA），status/queueJson 全带出；
#        池内歌也上报（index.json 条目本就带 album）。
#      - WebConsole：op=play_song（HTTP 触发完整语音链）/ op=play_online /
#        op=search_online；/api/search?online=1 池内 0 中时附带在线结果。
#      - XiaoZhiWs：MCP 描述纠偏 —— play_song「点名直接调，别先 search 试探」、
#        search「只覆盖本地曲库池，搜不到≠没有」。
#      - ui/ui.html：「在线搜歌」按钮 + 在线结果区（点 ▶ 即播）+ npMeta 展示
#        专辑·时长·来源（翻唱/DJ 版显示真实信息）。
#   ③ 配套 player 侧 pl-1.6.5：/api/status 已整表透传 music 段，player.html
#      加 npMeta 同行展示。
#   根因：music index.json 是启动时拉的快照 —— 池子外的歌（新歌/没导入的），
#         play_song 走到 matchRadio 之后只剩一个 ERR。
#   改动：miss 链末尾插 playOnlineSong(q)：从 indexUrl 推导 NAS mini-lx base，
#         GET /api/search?q=&source=all 全源搜 → 取第一条 →
#         GET /api/url?rid=&source=&q=128k 拿直链 → append 进 tracks →
#         走与库内歌完全相同的 pushSongs/kickPlay（队列可见、能上下曲）。
#         全程 try/catch，任何一步失败返回 null ⇒ 维持原 ERR，零副作用。
#   依赖：files/music_index 指向的 mini-lx（NAS 8100）提供 /api/search 与 /api/url。
#
# jp2-v1.3.173：device 的 :8088 收口 ——
#   ① **只监听 127.0.0.1**：整页已搬到 player 的 :8080，8088 现在只剩一个用途
#      （给同机的 player 控制台做反向代理，Proxy.BASE 本来就是 127.0.0.1:8088）。
#      以前绑 0.0.0.0 ⇒ 局域网里谁都能开一个「第二控制台」（能改设置、看对话日志）。
#   ② **主端口不再供页面**：直接访问 8088 只回一句指引 + 3s 跳转到 :8080；
#      /api/* 全部照旧。镜像端口（files/mirror_port，默认关）仍绑 0.0.0.0 且给完整页面。
#   ③ 配套：NAS 侧 控制页本地化-NAS部署/www/js/xz_panel.js 的 iframe 已改指 8080。
#
# jp2-v1.3.173：
#   ① 循环模式真值修正 —— 原厂 AbsPlayer.setPlayMode **只认 1/2/3**
#      （1=列表随机 2=列表循环/顺序 3=单曲循环），以前页面发 0 会被打回 ⇒「顺序播放」无效；
#   ② 新增 op=replay：player 进程重启后列表（内存态）空了、但 curTitle 是落盘的，
#      点 ▶ 用落盘曲名回库反查并重推列表开播（查不到退化 random）；
#   ③ 「播放控制」的「随机播放」更名「随便听」；按歌手连播数量留空 = 该歌手全部；
#   ④ status 新增 mode / stale（player 侧）供页面回显与提示。
#
# jp2-v1.3.169：① 播放列表为空时点 ▶ 直接随机播放（player 只有「列表+index」模型，
#      空列表发 play 没有可播对象）；② 上一首/下一首发完 1.2s 主动补状态+队列；
#      ③ 切歌时队列自动把正在播的那条滚进可视区（cur 没变时一动不动）。
#
# jp2-v1.3.168：设置页重排 —— 氛围灯/调试两栏合并成「灯光控制」+ 新增「播放列表」上限
#   ① 灯光控制：氛围灯（player 侧 op=amb）+ 唤醒灯 / 播报灯（device 侧 files flag，
#      files/wake_light_off / files/busy_light_off，只挡「开」不挡「灭」）；
#   ② 播放列表上限：纯前端（localStorage r1_qlim），0 = 不限制，影响 /api/queue?limit=
#      与空搜索的 limit；
#   ③ /api/search?k= 留空 = 列全部曲库（PlayerBridge.searchJson 加 limit 参数）。
#   页头/调试卡片（灯位、角度、模拟唤醒、重连 WS）已按用户要求删除。
#
# jp2-v1.3.173：play_at 改走 subType=15（原厂 case 1 会 setIndex(0) 写死清零，15 不清零）
#    ⇒ 跳到队列第 N 首时**列表顺序完全不变**（复刻小飞版行为），失败才退回 rotate
#   起因：把 player 刷回原厂（/system/app/EchoService/EchoService.apk）后，8080 上
#         再也没有服务 ⇒ 页面一直「正在等音箱播放服务（8080）… 已等 234s」，队列空白。
#
#   实测（2026-10-16，原厂 player）：
#     · 我们的 type=4 总线指令照单全收 —— push `100_local_MUSIC_4` 后
#       EchoService 的 MultiMediaPlayer 真的 buffered → STATE_PLAYING；
#     · player 会把「当前曲目」回推到 what=33554432（USER_DATA）总线 arg1=1：
#       [{"singer":"Echosmith","songName":"Cool Kids","music_id":"100_local_MUSIC_4",…}]
#       ⇒ 曲名 / 歌手 / 库内下标全都有，而且切歌即推，比 8080 的 get_info 轮询还实时；
#       （以前 WakeupBridge 只认 key=wakeup，这条信息白白丢掉）
#     · itemId 是我们 push 时自己拼的（100_local_MUSIC_<库内下标>）⇒ 队列能自己重建。
#
#   改动：
#     ① PlayerBridge 记下最后一次 push 的列表（lastIdx）⇒ /api/queue 出队列；
#     ② WakeupBridge 把 arg1=1 的曲目事件转给 PlayerBridge.onBusSong() ⇒ 曲名/歌手/高亮；
#     ③ op=play_at&index=N（1-based）：★原厂 player 不认 JSON 的 index（实测连推三次
#        都播 itemList[0]）⇒ 改成把目标顶到列表第一再整表重推（与 play_index 同路子） ⇒
#        队列内跳转；player 不认 index 时 1.6s 后自动把目标顶到第一重推一次（两种都收敛）；
#     ④ UI：连续 3 次连不上 8080 ⇒ wsAbsent，全面改用自有链路，不再显示「正在等」；
#     ⑤ 音量：8080 缺席时改系统 STREAM_MUSIC（原厂 player 播的就是这条流，实测有效）。
#   ★ 唯一拿不到的还是总时长/进度（那个只上报原厂云 MQTT）⇒ 显示本地已播计时。
#
# jp2-v1.3.155：音量链路量纲错配修复 + 重启后「还没就绪」的显示
#   ★ 音量：player 的音量只有 WS {"type":"set_vol","vol":N} 能改（0..15 档位）。
#     以前走 /api/set vol= —— 那是系统 STREAM_MUSIC 的 0~100 百分比：
#     拖到 1~3 档 ⇒ setSystemVolume 换算成 index 0 ⇒ 静音 + 回读 0 ⇒ 滑块弹回 0，
#     而且 player 播的不是那条流 ⇒ 怎么拖都不响。现在 WS 在线一律走 set_vol，
#     离线才退回系统流（并把档位换算成百分比）。
#   ★ 重启后：player（8080）比我们起得晚，那段时间的队列/进度本来就拿不到。
#     现在切到播放/音乐页立刻重试 WS，未连时队列显示「正在等…已等 Ns」而不是「空」。
#
# jp2-v1.3.154：控制台支持「安装到桌面」（PWA）
#   · 新增 GET /manifest.webmanifest、/sw.js、/icon-<n>.png（图标运行时用 Canvas 画，
#     不往 dex 里塞 PNG 常量）
#   · 页面 head 引 manifest + iOS apple-* meta，设置 Tab 加「安装到桌面」卡片
#   ★ 硬门槛：Android Chrome 要求安全上下文，局域网 http 不算 ⇒ 用户需在
#     chrome://flags/#unsafely-treat-insecure-origin-as-secure 登记本地址；
#     UI 上会检测 window.isSecureContext 并把做法写清楚（iOS Safari 不受此限）。
#
# jp2-v1.3.153：控制台页面「过时防呆」—— 装机后用户手上那份 HTML 仍是旧的
#               （最典型症状：新按钮点了没反应）。加 no-cache meta + status ver
#               与 localStorage 比对，变了就顶部弹条提示刷新。
#
# jp2-v1.3.150：氛围灯设置（原厂总线 what=4，命令表从小飞版 r1_control.min.js 扒出并自证）
#               + 重启音箱（借道 adbd：adb reboot，我们是 u0_a8 没有 REBOOT 权限）
# jp2-v1.3.152：播放队列 / 搜索结果「点一下即播」
#               · 队列：点第 i 行 → 音箱 WS {"type":"play","index":i}
#                 （小飞版是双击，我们单击；实测回包 {"code":200,"type":"play"} 并真的切歌）
#               · 搜索：新增结构化接口 GET /api/search?k=… → {total,list:[{index,title,artist,url}]}
#                 （原 op=search 返回一整段中文文本，是给 MCP/模型看的，前端没法渲染）
#                 点结果 → op=play_url 播那条 url（没有 url 时退化为按歌名播）
# jp2-v1.3.151：设置 Tab「音频」与「行为」合并为一张卡 —— 移除「AEC 回声消除」
#               （= Android 软件 AEC，v1.3.9 实测连人声一起消掉）与「搭车原厂拾音」
#               （关掉就退化 AudioRecord 直采）两个有害开关，改只读回显「采集链路」。
#               后端 /api/set 仍接受 aec / uscTap，留给手工 A/B。
#
# ── v1.3.134 装机实测暴露的 bug（2026-10-13 20:58 r1client.log）───────────
#     20:58:40.237 music deferPause (唤醒让路挂起：音乐继续播，小智开口即暂停；trusted=true)
#     20:58:40.238 music markPaused(wakeup) → pause + 待恢复        ← 只隔 1ms！
#   根因：两个 handler 的 pause 分支里，v1.3.130 遗留的 markPaused() 钩子紧跟在
#   deferPause() 之后（DefaultMusicHandler:5619 / PhicommExternalMusicHandler:1533），
#   v1.3.134 只替换了原厂 pause，没摘掉这个旧钩子 ⇒
#     · 账记上了，但音乐**根本没被暂停**（原厂 pause 已被 deferPause 顶掉）
#       ⇒ 小智说完话的「恢复」是凭空的，会把上一曲拽回来；
#     · deferPending / pending 两套语义打架，首帧开播时反而走成 tts 分支。
#
# ── v1.3.135 改动 ──────────────────────────────────────────────────────────
#   ① 两个 handler 的挂起分支：**deferPause 之后直接 return**（goto :goto_36 /
#      :goto_49），彻底不再调 markPaused()。记账统一由
#      MusicResume.onTtsAudioStart() 在第一帧音频开播那一刻完成。
#   ② PhicommExternalMusicHandler 去掉 isSoftPlaying 门禁：external 模式原厂
#      本来就是**无条件** pause（说明原厂认为有东西在播），而软状态进程重启即丢，
#      拿它当门禁只会漏掉让路；trusted=false ⇒ 开播时 DLNA 查真状态，阴性撤账。
#
# ── 判读（files/r1client.log，tag 前缀 "music "）──────────────────────────
#   放歌中喊小智（正确顺序，两条之间应隔好几秒 = 唤醒到开口的时间）：
#     music deferPause (唤醒让路挂起：音乐继续播，小智开口即暂停；trusted=…)
#     …（音乐继续播）…
#     music markPaused(wakeup) → pause + 待恢复      ← 紧挨 tts-first-frame->play-start
#     music onTurnEnded(tts-stop) -> resume in 2500ms
#     music auto-resume … : sent
#   ★ 若 deferPause 与 markPaused 相隔 <100ms ⇒ 挂起没生效，别急着判恢复链路。
#   唤醒后啥也没说：music onTurnEnded(session-end) skip: 唤醒后音乐从未暂停…
#   软状态丢了：music yield-probe(…) -> DLNA 说没在播/在播 …
# jp2-v1.3.190：歌词多候选 rid（撞上 Live/伴奏版没词时继续试下一个）
#   真机翻车（2026-10-18 14:01~14:05）：语音「我要听 Taylor Swift 的歌」、web 按歌手
#   连播「周杰伦」全部 ERR「本地和在线都没找到」。device 日志真凶：
#     online append skipped: 已 append 401 条（上限 400），等下次 reloadIndex 归零
#   即在线 append 额度耗尽后 appendOnlineTracks 一律返回空 —— 搜索明明成功
#   （NAS /api/search 周杰伦 247 条 / Taylor Swift 263 条都秒回），只是装不进库。
#   而「等下次 reloadIndex」在正常使用里永远不会来 ⇒ 额度一满，在线点歌全哑。
#   修复：额度满时自动回收最早收录的一半在线 append（FIFO，只删 via=online/rand
#   的尾部条目；本地段/pool 段下标不动 ⇒ 调用方手里的本地命中下标不受影响；
#   player 队列持条目值拷贝 ⇒ 不影响正在播）。改动仅 PlayerBridge.appendOnlineTracks。
#
set -e
# ============================== 路径解析（V2.0.5 起可移植）==============================
# 2026-11-07：为了「把源码发给别人也能构建」，这里不再假设某种固定目录结构。
#   同时支持两套布局，同一份脚本两边都能跑：
#     布局 A（开发工作树 R1/）：
#         $ROOT/janus/dl/jdk-17.0.20.1+1   $ROOT/sdkdl/stage/{android.jar,r8.jar}
#         $ROOT/janus/smali/               $ROOT/diag/pandora/Unisound.apk
#     布局 B（发布源码包 源码/）：
#         $ROOT/tools/{jdk/,android.jar,r8.jar,smali/}   $HERE/Unisound.apk
#   本目录资产（ui/ v2src/ uni_smali/ javacp/ libr1opus.so keep_v23.pro）两套都在脚本同级。
#   ★ 环境变量 R1_TOOLS 可强制指定工具链根目录，优先级最高。
cd "$(dirname "$0")"
HERE="$(pwd -W 2>/dev/null || pwd)"
R1="$(cd .. && { pwd -W 2>/dev/null || pwd; })"
ROOT="$R1"

if [ -n "${R1_TOOLS:-}" ]; then
    TOOLS="$(cd "$R1_TOOLS" && { pwd -W 2>/dev/null || pwd; })"
elif [ -d "$ROOT/tools" ]; then
    TOOLS="$ROOT/tools"
else
    TOOLS=""
fi

# 在「新布局 tools/」与「旧布局 R1/」之间取第一个存在的
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
SMALI_DIR="$(pick_tool smali janus/smali)"
SMALI_CP="$SMALI_DIR/*"

# --- 本目录资产（两种布局都在脚本同级）---
ACKP="$HERE/javacp"
SO="$HERE/libr1opus.so"
if [ -f "$HERE/Unisound.apk" ]; then
    APKSHELL="$HERE/Unisound.apk"
else
    APKSHELL="$ROOT/diag/pandora/Unisound.apk"
fi
# janus_poc.py 所在目录：新布局在脚本同级，旧布局在 R1/diag/pandora/
if [ -f "$HERE/janus_poc.py" ]; then
    JANUS_DIR="$HERE"
else
    JANUS_DIR="$ROOT/diag/pandora"
fi

# --- Python 3：$PY 环境变量 → python3 → python → py ---
if [ -z "${PY:-}" ]; then
    for _c in python3 python py; do
        if command -v "$_c" >/dev/null 2>&1; then PY="$_c"; break; fi
    done
fi

# --- 依赖自检：缺什么一次说清，不要跑到一半才炸 ---
BAD=0
need() {
    if [ -z "$2" ] || [ ! -e "$2" ]; then
        echo "  x 缺少 $3"
        echo "      期望位置: ${2:-（未找到）}"
        BAD=1
    fi
}
need JDK        "$JDK"        "JDK 17（javac/jar/java）"
need AJAR       "$AJAR"       "android.jar"
need R8         "$R8"         "r8.jar"
need SMALI_DIR  "$SMALI_DIR"  "smali/baksmali 工具链目录"
need ACKP       "$ACKP"       "javacp（编译期依赖 jar）"
need SO         "$SO"         "libr1opus.so"
need APKSHELL   "$APKSHELL"   "原厂壳 Unisound.apk（Janus 拼包用）"
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

# v1.3.173：页面常量 UiModern.java 是 ui/ui.html 生成的 —— 以前要手动跑
# janus_pandora2/ui/gen_java.py，忘了跑就会出现「源码改了、8088 页面还是旧的」。
# 这里在 javac 之前强制重生成一次（幂等，几十毫秒）。
# ★ 2026-10-29：必须在 find 生成 v22sources.txt 之前跑 —— 预清理会把旧的 UiModern.java
#   mv 走（覆盖写被环境拦截），find 若在 gen 之前跑，列表里就没有它 ⇒ javac MISSING class。
echo "== [0.4/6] ui.html -> UiModern.java（须在 find 之前）=="
"$PY" ui/gen_java.py || exit 1

# safe-delete 阈值：旧产物先 mv 走（2026-10-29：已存在文件的覆盖写会被杀软/句柄锁拒 ⇒ 先 mv 走再让脚本重建）
if [ -f v22sources.txt ]; then mv v22sources.txt _old_v22src_$(date +%s) 2>/dev/null || true; fi
if [ -d v22classes ]; then mv v22classes _old_v24_$(date +%s) 2>/dev/null || true; fi
if [ -d v22out ]; then mv v22out _old_v24_out_$(date +%s) 2>/dev/null || true; fi
mkdir -p v22classes v22out
# 2026-11-03：源文件列表改用**相对路径**（原来 sed 前置绝对路径）。
#   本机路径含中文（F:/玩机/...），而 javac 读 @argfile 用的是系统 ANSI(GBK) 编码，
#   UTF-8 写进去的中文路径会被解成乱码 ⇒ error: file not found。相对路径全 ASCII，绕开这个坑。
find v2src -name "*.java" > v22sources.txt
echo "sources: $(wc -l < v22sources.txt)"

echo "== [0.9/6] RELEASE 版本号注入：VERSION -> 2.0.6 =="
"$PY" - <<'INJEOF'
import io, os, re
p = 'v2src/cn/workbuddy/r1client/XzBootstrap.java'
src = io.open(p, encoding='utf-8').read()
if os.path.exists('_VERSION_dev.bak'):
    print('  [warn] 已有 _VERSION_dev.bak（上次构建没走到还原），沿用旧备份、不覆盖')
else:
    io.open('_VERSION_dev.bak', 'w', encoding='utf-8', newline='\n').write(src)
s = re.sub(r'public static final String VERSION = "[^"]*"',
           'public static final String VERSION = "2.0.6"', src)
assert 'VERSION = "2.0.6"' in s, 'release 版本注入失败'
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('  VERSION -> 2.0.6  (release 序列；测试序列 1.3.x 保留在源码树)')
INJEOF

echo "== [1/6] javac =="
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options \
    -bootclasspath "$AJAR" -cp "$ACKP/*" -d v22classes @v22sources.txt > javac_v166.log 2>&1
JRC=$?
echo "   javac rc=$JRC (log: javac_v166.log, $(grep -c 'error:' javac_v166.log) error)"
if [ $JRC -ne 0 ]; then tail -40 javac_v166.log; echo "javac FAILED rc=$JRC"; exit 1; fi
echo "   classes: $(find v22classes -name '*.class' | wc -l)"
# 铁律：点名校验关键 class 真的产出了（javac 静默失败会一路装旧 dex）
for c in XzBootstrap PlayerBridge WebConsole KwsEngine MusicResume UiModern CpuGovernor DeviceCtl SessionController TtsPlayer R1Lights AudioCapture OpusCodec OtaClient WakeupBridge XiaoZhiWs ActivationHud VoicePower Alarm AlarmCache AlarmVol TopKey; do
    if [ ! -f "v22classes/cn/workbuddy/r1client/$c.class" ]; then
        echo "   MISSING class: $c"; exit 1
    fi
done
echo "   关键 class 齐备: OK"

echo "== [2/6] R8（shrink sherpa + kotlin 运行时）=="
"$JAR" cf v22client.jar -C v22classes .
"$JAVA" -cp "$R8" com.android.tools.r8.R8 --release --min-api 22 \
    --lib "$AJAR" --lib "$ACKP/okhttp.jar" --lib "$ACKP/okio.jar" --lib "$ACKP/orgjson.jar" \
    --pg-conf keep_v23.pro --output v22out \
    v22client.jar "$ACKP/sherpa-onnx-classes.jar" "$ACKP/kotlin-stdlib.jar" > r8_v166.log 2>&1
RRC=$?
tail -8 r8_v166.log
echo "   R8 rc=$RRC"
if [ $RRC -ne 0 ]; then echo "R8 FAILED rc=$RRC (see r8_v166.log)"; exit 1; fi
if [ ! -s v22out/classes.dex ]; then echo "R8 没产出 classes.dex"; exit 1; fi
ls -la v22out/classes.dex

echo "== [3/6] 方法数预算 =="
"$PY" - <<'PYEOF'
import struct
d = open('v22out/classes.dex','rb').read()
print(f'  stand-alone dex method_ids = {struct.unpack("<L", d[88:92])[0]}')
PYEOF

echo "== [4/6] baksmali =="
if [ -d smali_xz27 ]; then mv smali_xz27 _old_v24_smali_$(date +%s) 2>/dev/null || true; fi
# 2026-10-31：Windows 上 baksmali 写 .smali 会被 Defender 实时扫描抢锁 ⇒ Access Denied（跳过整类 ⇒ 成品缺类）。
# 本机无管理员权限加 Defender 排除项，且 --jobs 因 jcommander 版本冲突会崩，故用
# -XX:ActiveProcessorCount=1 把可用核数压成 1，强制 baksmali 单线程反编译。
# 2026-11-01：单线程仍偶发 Access Denied（Defender 锁刚写的文件），故加重试环 —— 直到零「skipping class」才过关。
BK_TRIES=0; BK_OK=0
while [ $BK_TRIES -lt 8 ]; do
  BK_TRIES=$((BK_TRIES+1))
  "$JAVA" -XX:ActiveProcessorCount=1 -cp "$SMALI_CP" org.jf.baksmali.Main d v22out/classes.dex -o smali_xz27 > _baksmali_try.log 2>&1 || true
  if ! grep -q "skipping class" _baksmali_try.log; then BK_OK=1; break; fi
  echo "   baksmali 第 $BK_TRIES 次仍有类被 Defender 跳过，重试…"
  rm -rf smali_xz27
done
if [ $BK_OK -ne 1 ]; then echo "   ✗ baksmali 多次重试仍跳过类（Defender 抢锁）"; exit 1; fi
echo "   smali files: $(find smali_xz27 -name '*.smali' | wc -l)"
rm -rf smali_xz27/com/unisound/jni

echo "== [5/6] smali 合并装配 =="
rm -f v22_merged.dex
"$JAVA" -XX:ActiveProcessorCount=1 -cp "$SMALI_CP" org.jf.smali.Main a uni_smali smali_xz27 -o v22_merged.dex
ls -la v22_merged.dex
"$PY" - <<'PYEOF'
import struct
d = open('v22_merged.dex','rb').read()
m = struct.unpack('<L', d[88:92])[0]
print(f'  merged method_ids = {m} / 65536')
assert m < 65536, '64K OVERFLOW'
for s in [b'XzBootstrap', b'KwsEngine', b'KeywordSpotter', b'sherpa-onnx-jni', b'MusicResume']:
    assert s in d, s
print('  PARASITE + SHERPA + MusicResume PRESENT: OK')
PYEOF

echo "== [5b/6] 钩子落点自检（两个 music handler） =="
"$PY" - <<'PYEOF'
import re
# --- DefaultMusicHandler：唤醒分支 isPlaying() 真 ⇒ deferPause(true)，然后**直接返回** ---
p = 'uni_smali/com/unisound/vui/handler/session/music/DefaultMusicHandler.smali'
s = open(p, encoding='utf-8', errors='replace').read()
# ★ v1.3.135：markPaused 遗留钩子必须为 0（挂起后 1ms 记账 = 无中生有的恢复账）
for name, want in [('markPaused', 0), ('markStopped', 1), ('markResumed', 1)]:
    n = len(re.findall(r'MusicResume;->' + name + r'\(\)V', s))
    print(f'  DefaultMusicHandler.{name} hooks = {n}')
    assert n == want, f'{name} hook count {n} != {want} (v1.3.135 要求 {want})'
n = len(re.findall(r'MusicResume;->deferPause\(Z\)V', s))
print(f'  DefaultMusicHandler.deferPause(Z) hooks = {n}')
assert n == 1, 'deferPause(Z) hook count != 1'
# 挂起后必须立刻返回：deferPause 到 .end method 之间只能有 goto，不能有别的 MusicResume 调用
k = s.find('MusicResume;->deferPause(Z)V')
end = s.find('.end method', k)
seg = s[k:end]
assert 'goto ' in seg, 'deferPause 之后没有直接返回（缺 goto）'
n = len(re.findall(r'MusicResume;->(?!deferPause)', seg))
print(f'  deferPause 之后的 MusicResume 调用残留: {n}')
assert n == 0, 'deferPause 之后仍残留其它 MusicResume 钩子（会污染挂起语义）'
print('  OK: 挂起后直接返回，无记账残留')
# 唤醒分支内不能有原厂 ANTPlayController;->pause
seg2 = s[max(0, k-800):k]
n = seg2.count('ANTPlayController;->pause()V')
print(f'  唤醒分支内原厂 pause 残留: {n}')
assert n == 0, '原厂 pause 仍在唤醒分支里'
print('  OK: 唤醒 pause 已替换为挂起版')
PYEOF
"$PY" - <<'PYEOF'
import re
# --- PhicommExternalMusicHandler：external 模式（用户的音乐走这条）---
p = 'uni_smali/com/phicomm/speaker/device/custom/handler/PhicommExternalMusicHandler.smali'
s = open(p, encoding='utf-8', errors='replace').read()
for name, want in [('markPaused', 0), ('markStopped', 1), ('markResumed', 1)]:
    n = len(re.findall(r'MusicResume;->' + name + r'\(\)V', s))
    print(f'  PhicommExternalMusicHandler.{name} hooks = {n}')
    assert n == want, f'{name} hook count {n} != {want} (v1.3.135 要求 {want})'
n = len(re.findall(r'MusicResume;->deferPause\(\)V', s))
print(f'  PhicommExternalMusicHandler.deferPause() hooks = {n}')
assert n == 1, 'deferPause() hook count != 1'
# v1.3.135：isSoftPlaying 门禁已去掉（软状态重启即丢，会漏让路）
n = len(re.findall(r'PlayerBridge;->isSoftPlaying\(\)Z', s))
print(f'  PhicommExternalMusicHandler.isSoftPlaying 门禁 = {n} (v1.3.135 应为 0)')
assert n == 0, 'isSoftPlaying 门禁仍在（会漏掉让路）'
k = s.find('MusicResume;->deferPause()V')
end = s.find('.end method', k)
seg = s[k:end]
assert 'goto ' in seg, 'deferPause 之后没有直接返回（缺 goto）'
n = len(re.findall(r'MusicResume;->(?!deferPause)', seg))
print(f'  deferPause 之后的 MusicResume 调用残留: {n}')
assert n == 0, 'deferPause 之后仍残留其它 MusicResume 钩子'
n = s.count('mCommonPlayer;->pause()V')
print(f'  PhicommExternalMusicHandler 原厂 pause 残留: {n}')
assert n == 0, '原厂 mCommonPlayer.pause() 仍在（应已替换）'
print('  OK: external 模式唤醒 pause 已替换为无条件挂起 + 直接返回')
PYEOF

echo "== [5c/6] v1.3.135 首帧开播让路自检 =="
"$PY" - <<'PYEOF'
import re
# TtsPlayer 播放循环起点应调用 MusicResume.onTtsAudioStart()
p = 'smali_xz27/cn/workbuddy/r1client/TtsPlayer.smali'
s = open(p, encoding='utf-8', errors='replace').read()
n = len(re.findall(r'MusicResume;->onTtsAudioStart\(\)V', s))
print(f'  TtsPlayer.onTtsAudioStart calls = {n}')
assert n == 1, 'TtsPlayer 首帧开播钩子缺失'
i = s.find('MusicResume;->onTtsAudioStart()V')
j = s.find('tts-first-frame->play-start')
assert 0 < i and 0 < j and abs(i - j) < 2000, 'onTtsAudioStart 未紧挨首帧打点'
print('  onTtsAudioStart 紧挨 tts-first-frame->play-start 打点: OK')
PYEOF
"$PY" - <<'PYEOF'
d = open('v22_merged.dex','rb').read()
for s in [b'onTtsAudioStart', b'queryPlayingStateAsync', b'StateCallback',
          b'deferPause', b'cancelDefer', b'music_pause_tts',
          # v1.3.136：跨重启播放状态
          b'restorePlayState', b'didlTitle', b'r1player', b'cur_title',
          # v1.3.137：真状态判据
          b'isMusicActive']:
    n = d.count(s)
    print(f'  {s.decode():22s} in dex: {n}')
    assert n >= 1, s
print('  first-frame yield symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.138：开关语义统一自检 —— ttsPause 必须是「让路的唯一且最先的门禁」
p = 'v2src/cn/workbuddy/r1client/MusicResume.java'
s = open(p, encoding='utf-8', errors='replace').read()
i1 = s.find('public static void deferPause(boolean trusted)')
i2 = s.find('public static void onTtsAudioStart()')
assert 0 < i1 < i2, '方法定位失败'
segd = s[i1:i2]
assert 'if (!ttsPause)' in segd, 'deferPause 缺少 ttsPause 门禁（开关关着时唤醒仍会让路）'
print('  deferPause ttsPause 门禁: OK')
seg = s[i2:i2 + 4000]
k1 = seg.find('if (!ttsPause)')
k2 = seg.find('if (wakeupYield)')
assert 0 < k1 < k2, 'onTtsAudioStart 里 ttsPause 门禁必须排在 wakeupYield 分支之前'
print(f'  onTtsAudioStart 门禁顺序 OK (ttsPause@{k1} < wakeupYield@{k2})')
# 文案同步
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
assert '音乐一路播到底' in w, 'Web 文案未同步 v1.3.138 语义'
print('  Web 开关文案已同步: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.139：自定义音乐库开关 + 日志「仅显示对话」
d = open('v22_merged.dex','rb').read()
for s in [b'my_music_off', '自定义音乐库'.encode(), '仅显示对话'.encode(),
          '我：'.encode(), '小智：'.encode(), b'sentence_start']:
    n = d.count(s)
    print(f'  {s.decode("utf-8","replace"):14s} in dex: {n}')
    assert n >= 1, s
print('  v1.3.139 symbols PRESENT: OK')
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
assert 'saveMyMusic' in w and 'tgChat' in w and "q.get(\"chat\")" in w
print('  Web: myMusic 开关 + chat 视图 wiring OK')
m = open('v2src/cn/workbuddy/r1client/XiaoZhiWs.java', encoding='utf-8', errors='replace').read()
i = m.find('private void sendMcpToolsResult(int id)')
assert 'isMyMusic()' in m[i:i+900], 'MCP 工具表缺少自定义音乐库门禁'
print('  MCP tools/list 门禁 OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.141：切音乐库开关 ⇒ 断当前连接（不必重启进程）
b = open('v2src/cn/workbuddy/r1client/XzBootstrap.java', encoding='utf-8', errors='replace').read()
assert 'public static void dropWs(' in b, 'XzBootstrap.dropWs 缺失'
assert 'probe()' not in b[b.find('public static void dropWs('):b.find('public static void dropWs(')+900], \
    'dropWs 里不能 probe（那是 restartWs 的活儿）'
print('  XzBootstrap.dropWs(只断不连): OK')
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find('String mm = q.get(\"myMusic\")')
assert 'dropWs' in w[i:i+700], 'myMusic 切换未调用 dropWs'
print('  myMusic 切换 -> dropWs wiring OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.142：日志视图选择持久化到 localStorage（刷新页面不丢）
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find('var chatOnly=false;')
assert i > 0 and 'localStorage.getItem' in w[i:i+220], 'chatOnly 未从 localStorage 读回'
j = w.find('function tgChat()')
assert j > 0 and 'localStorage.setItem' in w[j:j+260], 'tgChat 未写回 localStorage'
print('  chatOnly localStorage 持久化: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.143：唤醒即挂起让路 + 软状态新鲜度 + 页脚版本号动态化
c = open('v2src/cn/workbuddy/r1client/SessionController.java', encoding='utf-8', errors='replace').read()
i = c.find('private void handleWakeup(String src, boolean usePreroll)')
assert i > 0, 'handleWakeup 未找到'
seg = c[i:i+3000]
k1 = seg.find('MusicResume.onWakeup()')
k2 = seg.find('playWakeAck();', k1)   # 从调用点之后找，避开注释里的同名文字
assert k1 > 0, 'handleWakeup 未调用 MusicResume.onWakeup()'
assert k2 > 0 and k1 < k2, 'onWakeup() 必须在 playWakeAck() 之前（采样点要干净）'
print(f'  handleWakeup: onWakeup@{k1} < playWakeAck@{k2}: OK')

m = open('v2src/cn/workbuddy/r1client/MusicResume.java', encoding='utf-8', errors='replace').read()
assert 'public static void onWakeup()' in m, 'MusicResume.onWakeup 缺失'
assert 'isSoftPlayingFresh' in m, '让路判据未改用 fresh'
print('  MusicResume.onWakeup + fresh 判据: OK')

p = open('v2src/cn/workbuddy/r1client/PlayerBridge.java', encoding='utf-8', errors='replace').read()
assert 'public static boolean isSoftPlayingFresh()' in p, 'isSoftPlayingFresh 缺失'
assert 'softFresh = false;' in p[p.find('private static void restorePlayState()'):p.find('private static void restorePlayState()')+2200], \
    'restorePlayState 恢复值必须标为不新鲜'
print('  PlayerBridge.softFresh: OK')

w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find("class='foot'")
j = w.find("</div>", i)    # 截到页脚 div 结束，别把后面的注释也算进来
line = w[i:j]
assert i > 0 and 'XzBootstrap.VERSION' in line, '页脚版本号未动态化'
assert 'v1.3.' not in line, '页脚仍写死版本号: ' + line
print('  页脚版本号动态化: OK')

d = open('v22_merged.dex','rb').read()
for s in [b'onWakeup', b'isSoftPlayingFresh', b'wakeup-yield armed', b'markPaused']:
    assert d.count(s) >= 1, s
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.149：指示灯联动（原厂 PhicommLightController）
r = open('v2src/cn/workbuddy/r1client/R1Lights.java', encoding='utf-8', errors='replace').read()
for s in ['public static void onWakeup(Object ctx)', 'public static void busyOn()',
          'public static void busyOff()', 'public static void allOff()',
          'ASR_FOURMIC_DOA_RESULT', 'messageCenter', 'LIGHT_LOADING = 0xcb']:
    assert s in r, 'R1Lights 缺 ' + s
print('  R1Lights 灯控方法齐备: OK')
c = open('v2src/cn/workbuddy/r1client/SessionController.java', encoding='utf-8', errors='replace').read()
i = c.find('public void onStt(String text)')
assert 'R1Lights.busyOn()' in c[i:i+900], 'onStt 未开播报灯'
j = c.find('public void onTtsStop()')
assert 'R1Lights.busyOff()' in c[j:j+400], 'onTtsStop 未关播报灯'
k = c.find('private void enterIdle()')
assert 'R1Lights.allOff()' in c[k:k+4000], 'enterIdle 未兜底关灯'
print('  SessionController: onStt/busyOn + onTtsStop/busyOff + enterIdle/allOff wiring OK')
u = open('v2src/cn/workbuddy/r1client/UscTap.java', encoding='utf-8', errors='replace').read()
i = u.find('public static void onWakeup(final Object ctx)')
assert 'R1Lights.onWakeup(ctx)' in u[i:i+900], 'UscTap.onWakeup 未点拾音灯'
print('  UscTap.onWakeup -> R1Lights.onWakeup wiring OK')
b = open('v2src/cn/workbuddy/r1client/XzBootstrap.java', encoding='utf-8', errors='replace').read()
assert 'R1Lights.init(app)' in b, 'XzBootstrap 未 init 灯控'
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find('"light".equals(op)')
assert i > 0 and 'R1Lights.debug' in w[i:i+300], 'Web ?op=light 未接线'
print('  R1Lights.init + ?op=light wiring OK')
d = open('v22_merged.dex','rb').read()
for s in [b'R1Lights', b'busyOn', b'allOff', b'ASR_FOURMIC_DOA_RESULT']:
    assert d.count(s) >= 1, s
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.143b：调试唤醒入口 ?op=wakeup
b = open('v2src/cn/workbuddy/r1client/XzBootstrap.java', encoding='utf-8', errors='replace').read()
assert 'public static boolean debugWakeup()' in b, 'XzBootstrap.debugWakeup 缺失'
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find('"wakeup".equals(op)')
assert i > 0 and 'XzBootstrap.debugWakeup' in w[i:i+300], 'op=wakeup 未接到 debugWakeup'
print('  ?op=wakeup 调试唤醒 wiring: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.144：应答音（叮）不得触发让路
mm = open('v2src/cn/workbuddy/r1client/MusicResume.java', encoding='utf-8', errors='replace').read()
i = mm.find('public static void onTtsAudioStart()')
seg = mm[i:i+2500]
assert 'inWakeCue()' in seg, 'onTtsAudioStart 未排除应答音'
k1 = seg.find('inWakeCue()'); k2 = seg.find('cancelDefer()', k1)
assert k1 < k2, '应答音判断必须排在 cancelDefer() 之前（不能把挂起撤掉）'
print('  onTtsAudioStart 应答音豁免 (inWakeCue@%d < cancelDefer@%d): OK' % (k1, k2))
j = mm.find('public static void onTurnEnded(String why)')
segt = mm[j:j+1600]
assert 'deferPending && !serverTtsSeen' in segt, 'onTurnEnded 未保留应答音收尾时的挂起'
print('  onTurnEnded 应答音收尾保留挂起: OK')

c = open('v2src/cn/workbuddy/r1client/SessionController.java', encoding='utf-8', errors='replace').read()
k = c.find('public void onTtsStart()')
assert 'MusicResume.noteServerTts()' in c[k:k+900], 'onTtsStart 未标记服务器 TTS'
k2 = c.find('private void playWakeAck()')
assert 'MusicResume.noteWakeCue()' in c[k2:k2+2600], 'playWakeAck 未标记应答音'
print('  SessionController: noteWakeCue + noteServerTts wiring OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.145：唤醒一律重采 + session-end 撤账
mm = open('v2src/cn/workbuddy/r1client/MusicResume.java', encoding='utf-8', errors='replace').read()
i = mm.find('public static void onWakeup()')
seg = mm[i:i+2200]
assert 'if (deferPending) {' not in seg, 'onWakeup 不能因为已挂起就跳过采样'
assert 'PlayerBridge.isMusicActive()' in seg, 'onWakeup 必须采样'
print('  onWakeup 一律重采: OK')
j = mm.find('public static void onTurnEnded(String why)')
segt = mm[j:j+2000]
assert 'session-end".equals(why)' in segt, 'session-end 未撤账'
print('  onTurnEnded session-end 撤账: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.146：会话结束后（断连提示音）不得再让路
mm = open('v2src/cn/workbuddy/r1client/MusicResume.java', encoding='utf-8', errors='replace').read()
assert 'public static void setSessionActive(boolean a)' in mm, 'MusicResume.setSessionActive 缺失'
i = mm.find('public static void onTtsAudioStart()')
seg = mm[i:i+1800]
k0 = seg.find('if (!sessionActive)')
kw = seg.find('inWakeCue()')
k1 = seg.find('cancelDefer()', max(k0, kw))
assert k0 > 0, 'onTtsAudioStart 缺少 sessionActive 门禁'
assert kw > 0 and kw < k0, 'inWakeCue 必须排在 sessionActive 门禁之前（否则「叮」被误记为断连 cue）'
assert k1 > 0 and k0 < k1, 'sessionActive 门禁必须排在 cancelDefer() 之前'
print('  onTtsAudioStart 判据顺序 (inWakeCue@%d < sessionActive@%d < cancelDefer@%d): OK' % (kw, k0, k1))
c = open('v2src/cn/workbuddy/r1client/SessionController.java', encoding='utf-8', errors='replace').read()
k = c.find('private void setState(State s)')
seg2 = c[k:k+700]
assert 'MusicResume.setSessionActive(active)' in seg2, 'setState 未同步 sessionActive'
print('  setState -> MusicResume.setSessionActive wiring OK')
d = open('v22_merged.dex','rb').read()
assert d.count(b'setSessionActive') >= 1, 'dex 缺 setSessionActive'
assert '会话已结束'.encode() in d or b'sessionActive' in d
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.148：拾音灯定格 —— onRecognizeStart 不得再灭拾音灯
p = 'uni_smali/com/phicomm/speaker/device/custom/lights/PhicommLightListener.smali'
s = open(p, encoding='utf-8', errors='replace').read()
i = s.find('.method public onRecognizeStart()V')
assert i > 0, '找不到 onRecognizeStart'
seg = s[i:s.find('.end method', i)]
assert 'turnOffALLWakeupLight' not in seg,     'onRecognizeStart 仍在灭拾音灯（唤醒后 ~440ms 会把定格亮点砍掉）'
assert 'turnOnLoadingLight' in seg, 'onRecognizeStart 结构变了'
print('  PhicommLightListener.onRecognizeStart: 灭灯已摘除，仅剩被短路的 loading  OK')
# 全灭路径必须还在：onInterrupt 保留（异常/打断时仍能收尾）
j = s.find('.method public onInterrupt()V')
assert 'turnOffALLWakeupLight' in s[j:s.find('.end method', j)],     'onInterrupt 的灭灯被误删了'
print('  onInterrupt 灭灯保留: OK')
# 我们自己的收尾：busyOn 灭拾音灯 + enterIdle 全灭
r = open('v2src/cn/workbuddy/r1client/R1Lights.java', encoding='utf-8', errors='replace').read()
k = r.find('public static void busyOn()')
assert 'wakeupAllOff()' in r[k:k+1200], 'busyOn 未先灭拾音灯'
print('  R1Lights.busyOn -> wakeupAllOff + blue: OK')
d = open('v22_merged.dex','rb').read()
for t in [b'R1Lights', b'busyOn', b'allOff']:
    assert d.count(t) >= 1, t
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.149：唤醒触发的「会话硬重启」不得播断连提示音
p = 'v2src/cn/workbuddy/r1client/SessionController.java'
s = open(p, encoding='utf-8', errors='replace').read()
i = s.find('if (cueOnDisconnect && wasSession)')
assert i > 0, '找不到 cueOnDisconnect 判定'
seg = s[i:i + 1100]
assert 'restartPending' in seg, \
    '断连提示音未加 restartPending 门禁（唤醒硬重启会误播）'
assert 'disconnect cue skipped' in seg, '缺 skip 日志（取证用）'
print('  enterIdle 断连提示音: restartPending 门禁已加  OK')
j = s.find('ws.close("no-tts-timeout")')
assert j > 0, 'no-tts-timeout 真掉线路径不见了'
print('  no-tts-timeout 真掉线路径保留（照旧出声）: OK')
d = open('v22_merged.dex', 'rb').read()
for t in [b'disconnect cue skipped', b'disconnect cue played']:
    assert d.count(t) >= 1, t
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.151：设置页合并 —— 两个有害开关必须真的从 UI 里消失
d = open('v22_merged.dex', 'rb').read()
for t in [b'chainNote', b'fillChain', b'\xe9\x87\x87\xe9\x9b\x86\xe9\x93\xbe\xe8\xb7\xaf']:
    assert d.count(t) >= 1, t            # 「采集链路」
print('  UI 采集链路只读回显: PRESENT  OK')
for t in [b'swAec', b'swTap', b'AEC \xe5\x9b\x9e\xe5\xa3\xb0\xe6\xb6\x88\xe9\x99\xa4',
          b'\xe6\x90\xad\xe8\xbd\xa6\xe5\x8e\x9f\xe5\x8e\x82\xe6\x8b\xbe\xe9\x9f\xb3']:
    assert d.count(t) == 0, 'UI 里还有被移除的开关: ' + str(t)
print('  swAec / swTap 两个有害开关已从 UI 移除  OK')
assert d.count(b'\xe9\x9f\xb3\xe9\xa2\x91\xe4\xb8\x8e\xe8\xa1\x8c\xe4\xb8\xba') >= 1, '音频与行为'
print('  「音频与行为」合并卡片: PRESENT  OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.152：点一下即播（队列 index + 搜索结果 url）
p = 'v2src/cn/workbuddy/r1client/WebConsole.java'
s = open(p, encoding='utf-8', errors='replace').read()
assert '/api/search' in s, '缺 /api/search 路由'
assert 'PlayerBridge.searchJson' in s, '/api/search 没接到 PlayerBridge.searchJson'
print('  /api/search 路由 -> PlayerBridge.searchJson: OK')

p = 'v2src/cn/workbuddy/r1client/PlayerBridge.java'
s = open(p, encoding='utf-8', errors='replace').read()
i = s.find('public static JSONObject searchJson')
assert i > 0, 'PlayerBridge.searchJson 不见了'
# ★★ 别再用固定字符窗口截 —— 每次给 searchJson 加注释/重载都会踩一次
#   （v1.3.181 在线过滤 1600→3200，v1.3.198 shuffle 重载又超了，窗口拉到 6000
#    又反过来把下一个方法的 play_index 圈进来 ⇒ 假故障「实现被改坏」）。
#   改成**精确定位方法体**：从三参签名起，到下一个同级方法声明为止。
i = s.find('public static JSONObject searchJson(String q, int lim, boolean shuffle)')
assert i > 0, 'PlayerBridge.searchJson 三参版不见了'
_ends = [x for x in (s.find('\n    public ', i + 10), s.find('\n    private ', i + 10)) if x > 0]
seg = s[i:min(_ends)] if _ends else s[i:]
assert 'matchTracks' in seg and 'play_index' not in seg, 'searchJson 实现被改坏'
assert 'it.put("url"' in seg, 'searchJson 没带 url（前端要靠它点播）'
print('  searchJson 返回 index/title/artist/url: OK')

d = open('v22_merged.dex', 'rb').read()
for t in [b'playAt(', b'playRes(', b'renderSearch', b'\xe7\x82\xb9\xe4\xb8\x80\xe4\xb8\x8b\xe5\x8d\xb3\xe6\x92\xad']:
    assert d.count(t) >= 1, t            # 「点一下即播」
print('  UI 点播入口 playAt / playRes: PRESENT  OK')
for t in [b'/api/search', b'searchJson']:
    assert d.count(t) >= 1, t
print('  /api/search + searchJson 已进 dex: OK')
PYEOF

echo "== [5.5/6] UI 自检（v1.3.153 页面过时防呆）=="
H="v2src/cn/workbuddy/r1client/UiModern.java"
for kw in 'checkVer' 'updBar' 'Cache-Control'; do
  if grep -q "$kw" "$H"; then echo "  PRESENT  $kw"; else echo "  ✗ MISSING $kw"; exit 1; fi
done
if grep -q 'u2192' "$H"; then echo "  ✗ 残留 \\u2192（Java 词法层会吃掉，引号会被破坏）"; exit 1; else echo "  CLEAN    no \\u escape"; fi

echo "== [5.6/6] 自检：play_url 必须带走真实歌名（v1.3.153 修复）=="
python - <<'PCHECK'
p = 'v2src/cn/workbuddy/r1client/PlayerBridge.java'
s = open(p, encoding='utf-8', errors='replace').read()
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
ok = True
# 后端：不能再死写「网络音频」当唯一标题
if 'oneJson("网络音频", url)' in s:
    print('  ✗ playUrl 仍在死写「网络音频」'); ok = False
else:
    print('  OK  playUrl 不再死写标题')
# 后端：要读 args 里的 title
if 'optString("title"' not in s or 'optString("artist"' not in s:
    print('  ✗ playUrl 没读 args.title / args.artist'); ok = False
else:
    print('  OK  playUrl 读 args.title / artist')
# 前端：playRes 要带 title/artist
u = open('ui/ui.html', encoding='utf-8', errors='replace').read()
if 'title=" + encodeURIComponent(it.title)' not in u:
    print('  ✗ UI playRes 没带 title'); ok = False
else:
    print('  OK  UI playRes 带 title/artist')
if 'play_index", "index=" + it.index' not in u:
    print('  ✗ UI playRes 没优先走 play_index（曲库序号）'); ok = False
else:
    print('  OK  UI playRes 优先走 play_index')
# WebConsole 要转发 play_index（dispatch 支持但路由没转 = unknown op）
if '"play_index".equals(op)' not in w:
    print('  ✗ WebConsole 没转发 op=play_index'); ok = False
else:
    print('  OK  WebConsole 转发 op=play_index')
# WebConsole 要转发 play_index（dispatch 支持但路由没转 = unknown op）
if '"play_index".equals(op)' not in w:
    print('  ✗ WebConsole 没转发 op=play_index'); ok = False
else:
    print('  OK  WebConsole 转发 op=play_index')
# WebConsole 要把 title/artist 透传
if '"title", t' not in w or '"artist", ar' not in w:
    print('  ✗ WebConsole 没透传 title/artist'); ok = False
else:
    print('  OK  WebConsole op=play_url 透传 title/artist')
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.7/6] 自检：PWA（v1.3.154 安装到桌面）=="
python - <<'PCHECK'
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
u = open('ui/ui.html', encoding='utf-8', errors='replace').read()
ok = True
pairs = [
    ('manifest 路由',      '/manifest.webmanifest', w),
    ('sw.js 路由',         '"/sw.js"',              w),
    ('icon 路由',          'path.startsWith("/icon-")', w),
    ('Service-Worker-Allowed', 'Service-Worker-Allowed: /', w),
    ('manifest 含 display',    '\\"display\\"',         w),
    ('manifest 含 standalone', '\\"standalone\\"',      w),
    ('UI 引 manifest',     'rel="manifest"',        u),
    ('UI 安装逻辑',        'instInit()',            u),
    ('UI 给出 flag 指引',  'chrome://flags',        u),
]
for name, kw, src in pairs:
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ')'); ok = False
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.8/6] 自检：音量走 WS set_vol（v1.3.155 量纲修复）=="
python - <<'PCHECK'
u = open('ui/ui.html', encoding='utf-8', errors='replace').read()
ok = True
must = [
    ('音量走 WS set_vol',   'type: "set_vol"'),
    ('防回弹 applyVol',     'function applyVol'),
    ('wsSend 可发对象',     'function wsSend('),
    ('等待文案 wsWaitTxt',  'function wsWaitTxt'),
]
for name, kw in must:
    if kw in u: print('  OK       ' + name)
    else: print('  MISSING  ' + name + '  (' + kw + ')'); ok = False
# 绝对不能再回退到「拖滑块直接写自有 0~100」
if 'setKV("vol"' in u:
    print('  ✗ 音量又退回 /api/set vol=（0~100 百分比，量纲错配）'); ok = False
else:
    print('  OK       滑块不再直接写 /api/set vol=')
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.9/6] 自检：灯光控制 + 夜灯 + 灯控 MCP（v1.3.173：自有队列 / 总线曲名 / play_at）=="
python - <<'PCHECK'
import io
def rd(p):
    return io.open(p, encoding='utf-8', errors='replace').read()
u      = rd('ui/ui.html')
lights = rd('v2src/cn/workbuddy/r1client/R1Lights.java')
wc     = rd('v2src/cn/workbuddy/r1client/WebConsole.java')
xz     = rd('v2src/cn/workbuddy/r1client/XiaoZhiWs.java')
pb2    = rd('v2src/cn/workbuddy/r1client/PlayerBridge.java')
sl     = rd('v2src/cn/workbuddy/r1client/SessionLog.java')
fx     = rd('v2src/cn/workbuddy/r1client/Fav.java')
ok = True
def chk(where, src, name, kw):
    global ok
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
def chkNo(where, src, name, kw):
    """负面断言：这段关键字必须已经不存在（功能已移除）。"""
    global ok
    if kw in src:
        print('  STILL    ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
    else:
        print('  OK(去掉) ' + name)
chk('ui',        u,      '栏目名灯光控制',   '>灯光控制 <')
chkNo('ui',      u,      '夜灯开关已移除',   'id="swNight"')
chkNo('ui',      u,      '夜灯函数已移除',   'function nightToggle')
chk('ui',        u,      '氛围灯状态回填',   'ambFill(S.ambient)')
chk('WebConsole', wc,    'CORS 放开',        'Access-Control-Allow-Origin')
chk('WebConsole', wc, 'fav_link 路由已登记', 'return PlayerBridge.favLink(a2);')
chk('WebConsole', wc, 'fav_add 收绑定信息', 'putS(it, "rtitle", q.get("rtitle"));')
chk('WebConsole', wc, 'fav_add 收 rman', 'it.put("rman", Integer.parseInt(rm.trim()));')
# v1.3.181：device 页面已从 `/` 下线（只回指引页，API 全保留）——
# 设备上跑的 v134 dex 里同样没有 `<body class=` ⇒ 原「embed 嵌入模式」断言已过时，
# 换成验证指引页本身（internalNotice）还在。
chk('WebConsole', wc,    '指引页 internalNotice', 'private static String internalNotice()')
chk('WebConsole', wc,    '页面只回指引',      '8088 已改为内部接口')
chk('ui',        u,      'embed 隐藏页头',   'body.embed .top{display:none}')
chk('R1Lights',  lights, '强制清灯 wipe',      'public static String wipe(')
chk('R1Lights',  lights, 'wipe 路由',          '"wipe".equals(v)')
chkNo('ui',      u,      '调试卡片已移除',     '>调试</div>')
chk('R1Lights',  lights, '灯控 MCP 入口',    'public static String mcp(')
chk('XiaoZhiWs', xz,     '灯控前缀',         'LIGHT_PREFIX = "self.r1light."')
chk('XiaoZhiWs', xz,     '灯工具表',         'addLightTools')
chk('XiaoZhiWs', xz,     'v1.3.182 list 描述强调本地', '才是本地曲库的数量')
chk('XiaoZhiWs', xz,     'tools/call 分流',  'name.startsWith(LIGHT_PREFIX)')
chk('SessionLog', sl,    '对话日志带日期',   '保留日期')
chk('ui',        u,      '唤醒灯开关',       "swWkL", )
chk('ui',        u,      '播报灯开关',       "swBsL", )
chk('ui',        u,      '氛围灯开关',       'ambToggle')
chk('ui',        u,      '队列上限输入',     'saveQlim')
chk('ui',        u,      '空队列随机播放',   'playOrRandom')
chk('ui',        u,      '切歌自动定位',     'queueScrollTo(box, cur)')
chk('ui',        u,      '队列窗口 start',   'Q.start')
chk('ui',        u,      'playAt 收真实下标', 'function playAt(i, real)')
chk('ui',        u,      '切歌按绝对下标判定', 'qLastCurAbs')
chk('ui',        u,      '随机->随便听',     '>随便听</button>')
chk('ui',        u,      '模式 2/1/3',       'setMode(3)')
chk('ui',        u,      '空队列走 replay',  'act("replay")')
chk('ui',        u,      '数量留空=全部',    '数量留空')
chk('ui',        u,      '模式回显',         'setModeUI(m.mode)')
chk('PlayerBridge', pb2, 'replay op',        '"replay".equals(op)')
chk('PlayerBridge', pb2, 'lastMode 字段',    'private static volatile int lastMode')
chk('PlayerBridge', pb2, 'status 带 mode',   'o.put("mode", lastMode)')
chk('PlayerBridge', pb2, '模式真值注释',     'PLAY_MODE_LIST_CYCLE   = 2')
chk('WebConsole', wc,    'replay 路由',      'dispatch("replay"')
chk('WebConsole', wc,    '主端口只监听回环',  'new ServerSocket(p, 50, lo)')
chk('WebConsole', wc,    '主端口不供页面',    'internalNotice()')
chk('WebConsole', wc,    '镜像仍供页面',      'acceptLoop(ss, true)')
chk('ui',        u,      '上下曲主动补状态', 'function skip(')
chk('ui',        u,      '空搜索列全部',     '&limit=')
chk('R1Lights',  lights, '唤醒灯 flag',      'wake_light_off')
chk('R1Lights',  lights, '播报灯 flag',      'busy_light_off')
chk('WebConsole', wc,    'set 收 wakeLight', '"wakeLight"')
chk('WebConsole', wc,    '状态带 wakeLight', 'a.put("wakeLight"')
chk('PlayerBridge', pb2, '空搜索列全部',     'key.length() == 0')
chk('PlayerBridge', pb2, '搜索带 limit',     'public static JSONObject searchJson(String q, int lim)')
# ---- v1.3.204：拾音灯跟随「小智在听」 ----
sc = rd('v2src/cn/workbuddy/r1client/SessionController.java')
chk('R1Lights',       lights, '聆听灯入口',       'public static void setListening(boolean on)')
chk('R1Lights',       lights, '聆听灯 flag',      'listen_light_off')
chk('R1Lights',       lights, '聆听灯去重',       'private static volatile boolean sListen')
chk('R1Lights',       lights, '聆听灯调试 listen', '"listen".equals(v)')
chk('R1Lights',       lights, '聆听灯调试 idle',  '"idle".equals(v)')
# ---- v1.3.205：修「灯效多播一次」----
chk('R1Lights',       lights, '灭灯防抖窗口',   'OFF_DEBOUNCE_MS = 250')
chk('R1Lights',       lights, '灭灯防抖撤销',   'private static void cancelOff()')
chk('R1Lights',       lights, '立即灭灯',       'private static void offNow()')
chk('R1Lights',       lights, '硬重启保持灯',   'public static void keepListening()')
chk('R1Lights',       lights, '唤醒同灯位不重播', 'boolean same = sListen && idx == lastIndex;')
chk('R1Lights',       lights, '延迟灭灯排队',   'sHandler.postDelayed(offTask, OFF_DEBOUNCE_MS);')
chk('SessionController', sc,  '硬重启不灭灯',   'R1Lights.keepListening();')
chk('SessionController', sc,  '真收会话才全灭', 'R1Lights.allOff();')
# ---- v1.3.206：拾音灯单颗直写（不播动画）----
# ---- v1.3.218：只亮底部时放行拾音灯 ----
chk('R1Lights', lights, '拾音灯判定',   'private static boolean isPickup(int arg1)')
chk('R1Lights', lights, '闸门放行',     'if (sNightTop || !isPickup(arg1)) {')
chk('R1Lights', lights, '收掉聆听灯',   'private static void stopPickup()')
# ---- v1.3.219：播放列表兜底宽松匹配 + 回报未匹配歌名 ----
chk('PlayerBridge', pb2, '宽松匹配方法',   'private static List<Integer> matchTracksLenient(String k)')
chk('PlayerBridge', pb2, '未匹配收集',     'List<String> missedTitles = new ArrayList<String>();')
chkNo('PlayerBridge', pb2, '兜底也按词已删',     'int h = findLocalFuzzy(need.get(i)[0], need.get(i)[1],')
chk('PlayerBridge', pb2, '未匹配标记',     '###MISSED###')
# ---- v1.3.220：本地模糊匹配提前 + 后台补源缓存 ----
chkNo('PlayerBridge', pb2, '模糊匹配提前已删',   '永不进 need、永不在线搜')
chk('PlayerBridge', pb2, '后台补源方法',   'private static void warmFavSource(List<String[]> rest)')
chkNo('PlayerBridge', pb2, '导入不调warmFavSource(旧断点)', 'warmFavSource(need.subList(ni, need.size()))')
# ---- v1.3.221：模糊匹配结果落盘 + O(1) 命中 ----
chk('PlayerBridge', pb2, 'O(1)命中真名',   'String lt = it.optString("ltitle", "")')
chkNo('PlayerBridge', pb2, '真名落盘收集已删',   'locFix.add(new String[]{fk, "ltitle", le[0]})')
chk('PlayerBridge', pb2, '整批落盘一次',   'int fixed = Fav.patchBatch(locFix)')
# ---- v1.3.222：宽松匹配误配修复 ----
chk('PlayerBridge', pb2, '宽松打分方法',   'private static int lenientScore(String key, String[] t)')
chk('PlayerBridge', pb2, '反向包含最短长', 'private static final int LENIENT_MIN = 3;')
chk('PlayerBridge', pb2, '①b用干净缓存', 'Fav.purgeUnrelated 统一做')
chkNo('PlayerBridge', pb2, '⑥先按词找本地已删', 'int lz = findLocalFuzzy(title, artist, keep, KT, KAT);')
# ---- v1.3.223：搜到但名字对不上 ⇒ 不敢认；收藏显示真名 ----
chk('PlayerBridge', pb2, '补源最低分',   'private static final int FAV_HIT_MIN = 70;')
chk('PlayerBridge', pb2, '不敢认标记',   'best.put("unsure", true);')
chk('PlayerBridge', pb2, '不敢认不落盘', 'if (unsure || src.length() == 0')
chkNo('PlayerBridge', pb2, '导入不再全量后台预搜', 'warmFavSource(need.subList(ni, need.size()));')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '真名也算同一首', 'private static boolean realHit(JSONObject it, String k)')
chk('Fav', fx, '导入落绑定字段', 'String[] bf = {"rtitle", "rartist", "ltitle", "lartist"};')
chk('Fav', fx, '补空含绑定字段', '"rtitle", "rartist", "ltitle", "lartist"};')
chk('Fav', fx, '快照捡回 rman', 'if (n.optInt("rman", 0) <= 0 && old.optInt("rman", 0) > 0) {')
chk('Fav', fx, '本次优先不覆盖', 'if (n.optString(fs[i], "").length() > 0) {')
chk('Fav', fx, '导入保留 rman', 'if (it.optInt("rman", 0) > 0) {')
# ★ v1.3.257：批量导入（上传歌单 O(N^2) 优化）+ 收藏匹配歌手加权
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '批量导入方法',   'public static String addBatch(JSONArray arr)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '整批只落一次盘', '整批只落这一次盘')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '条目组装助手',   'private static JSONObject buildEntry(JSONObject it, String k)')
chk('WebConsole', rd('v2src/cn/workbuddy/r1client/WebConsole.java'), '批量导入 op', '"fav_import_batch".equals(op)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '匹配歌手加权',   'sc += 20;')
chk('PlayerBridge', pb2, '④在线四件套',  'if (src.length() > 0 && rid.length() > 0 && b != null) {')
chk('PlayerBridge', pb2, '全表清脏缓存', 'int purged = Fav.purgeUnrelated();')
chk('PlayerBridge', pb2, '歌手冲突过滤方法', 'private static boolean artistConflict(String want, String got)')
chk('PlayerBridge', pb2, '候选歌手冲突即跳过', 'if (artistConflict(artist, s.optString("artist", ""))) {')
chkNo('PlayerBridge', pb2, '92 分不再豁免歌手', 'if (sc < 92) {')
chk('PlayerBridge', pb2, '同名换人压到阈值下', 'if (sc < 100) {')
chk('PlayerBridge', pb2, '手动绑定打记号', 'Fav.patch(k, "rman", "1");')
chk('PlayerBridge', pb2, '重链接 op 实现', 'public static String favLink(JSONObject args) {')
chk('PlayerBridge', pb2, '重链接 op 已分发', 'if ("fav_link".equals(op)) {')
chk('PlayerBridge', pb2, '队列换址方法', 'private static int retargetQueue(String title, String artist, String url) {')
chk('PlayerBridge', pb2, '正播着才原地重推', 'int n = pushPlaylist("fav_link", snap, false, at, 15);')
chk('PlayerBridge', pb2, '手动绑定压过本地库', 'boolean manual = "1".equals(it.optString("rman", ""))')
chkNo('PlayerBridge', pb2, '本地优先不再一刀切', 'int loc = findLocal(title, artist);')
chk('PlayerBridge', pb2, '③④⑤用真名推队列', 'String rt = it.optString("rtitle", "");')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '清缓存方法', 'public static void clearCache(String k)')
# ---- v1.3.224：正常歌要认得回来，脏缓存要洗得掉 ----
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '全表清脏缓存方法', 'public static int purgeUnrelated()')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '去注音折叠',     'public static String fold(String s)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '词元覆盖',       'public static double cover(String fa, String fb)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '同首相判据',     'public static boolean relatedName(String name, String artist,')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '清干净含真名',   '"ltitle", "lartist", "rtitle", "rartist",')
chk('PlayerBridge', pb2, '主歌手入搜索串', 'private static String mainArtist(String artist)')
chk('PlayerBridge', pb2, '按相似度挑搜索结果', 'private static JSONObject searchPick(String b, String q,')
chk('PlayerBridge', pb2, '在线相似度打分', 'private static int osScore(String title, String artist,')
chk('PlayerBridge', pb2, '解析失败记负缓存', 'Fav.markNoSrc(k);')
# ---- v1.3.225：按词匹配（子串匹配的替代品） ----
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '词元切分',       'public static String[] tokens(String s)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '按词打分',       'public static int tokScore(String[] A, String[] B)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '歌手当判据',     'public static int nameScore(String[] A, String[] aA,')
chk('PlayerBridge', pb2, '本地门槛',     'private static final int LOCAL_HIT_MIN = 70;')
chk('PlayerBridge', pb2, '按词本地查找', 'private static int findLocalFuzzy(String title, String artist,')
chk('PlayerBridge', pb2, '库侧词元预算', 'final String[][] KT = new String[kn][];')
chkNo('PlayerBridge', pb2, '不再按 cap 截断入场', 'if (pick.size() >= cap) {')
chk('PlayerBridge', pb2, 'Binder 字节预算', 'private static final int BINDER_BUDGET = 700000;')
chk('PlayerBridge', pb2, '按 UTF-8 字节算', 'int blen = utf8Len(json);')
chk('PlayerBridge', pb2, '播放列表上限 5000', 'private static final int MAX_PLAYLIST_CAP = 5000;')
chkNo('PlayerBridge', pb2, '占位不再受 cap', '(pick.size() + got.size()) < cap')
chk('PlayerBridge', pb2, 'push 不再被 maxPlaylist 压回', 'int lim = idxs.size();')
chk('PlayerBridge', pb2, '尾巴补进源队列', 'fav tail:'),
chkNo('PlayerBridge', pb2, '⑥改为按词已删',     'findLocalFuzzy(title, artist, keep, KT, KAT);')
# ---- v1.3.226：歌手当判据 + 请求侧覆盖率 ----
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '负缓存作废',     'public static void clearNoSrc(String k)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '请求侧覆盖率',   'public static double coverQuery(String fa, String fb)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '歌手无关不认',   'if (base < 92) {')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '库名单token不认', 'if (common == B.length && B.length >= 2)')
chk('PlayerBridge', pb2, '在线歌手判据', 'double af = Fav.coverQuery(fa, sa);')
# ---- 手动配对：未匹配的歌点了去搜索，播对了点 🔗 绑回去 ----
chk('PlayerBridge', pb2, '手动绑定方法', 'public static String favBind(JSONObject args)')
chk('PlayerBridge', pb2, '语音链的登记', 'return favBind(args);')
chk('WebConsole', wc, '网页路由登记',   '"fav_bind".equals(op)')
# ---- v1.3.227：缓存跨导入存活（clear 快照 + add 捡回）----
# ---- v1.3.267：改为「清空 = 彻底清」，快照随清空删除（错缓存不再跨重传存活）----
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '清空删快照',   'pf.delete();')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '快照装载',     'private static void restoreFromPrev(JSONObject n, String k)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '新条目捡缓存', 'restoreFromPrev(n, k);')
# ---- v1.3.228：播到才解析（占位地址 + fav_resolve）----
chk('PlayerBridge', pb2, '占位地址常量',   'private static final String PENDING_URL = "http://127.0.0.1:8080/r/fav?k=";')
chk('PlayerBridge', pb2, '播到才解析方法', 'public static String favResolve(JSONObject args)')
chk('PlayerBridge', pb2, '解析语音链登记', 'return favResolve(args);')
chk('WebConsole', wc, '解析网页路由登记', '"fav_resolve".equals(op)')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '按key取条目',   'public static JSONObject entry(String k)')
# ---- v1.3.229：导入零搜索（搜索只在播放时按需发生）----
chkNo('PlayerBridge', pb2, '导入不调warmFavSource', 'warmFavSource(need.subList(ni, need.size()))')
chk('PlayerBridge', pb2, '占位入列保留(带收藏序号)', 'got.add(new String[]{e[0], e[1], PENDING_URL + enc(pk), e[2], e[3], e[0],')
chk('PlayerBridge', pb2, '导入零搜索注释',        '★★ 导入期**绝不**再批量/后台搜索')
# ---- v1.3.231：恢复**离线**本地模糊匹配（v1.3.230 误删 ⇒ 法语歌变占位+全红）----
chk('PlayerBridge', pb2, 'playFav ⑥ 离线词元倒排候选', 'favLocalCands(title, artist, keep, KT, KAT)')
chk('PlayerBridge', pb2, 'playFav ⑥ 只对候选做模糊匹配', 'int lh = findLocalFuzzy(title, artist, ckeep, cKT, cKAT);')
chk('PlayerBridge', pb2, 'playFav ⑥ 模糊命中落 ltitle', 'locFix.add(new String[]{pk, "ltitle", (e[0] == null) ? "" : e[0]})')
# ---- v1.3.232：导入即匹配 + tail 补匹配 + fav_rematch ----
chk('PlayerBridge', pb2, 'favLocalMatchIdx 抽 ⑥ 离线匹配', 'private static int favLocalMatchIdx(')
chkNo('PlayerBridge', pb2, 'playFav tail 不再离线匹配', 'int tloc = favLocalMatchIdx(title, artist, keep, KT, KAT);')
chk('PlayerBridge', pb2, 'pushPlaylist 原样重试再砍半', 'sameRetry < 3 && lim > 1')
chkNo('PlayerBridge', pb2, 'playFav 排队列不做离线匹配', 'int loc3 = favLocalMatchIdx(title, artist, keep, KT, KAT);')
chkNo('PlayerBridge', pb2, 'playFav 不再按负缓存剔歌', 'if (Fav.skipSearch(it)) {')
chk('PlayerBridge', pb2, '负缓存不剔歌(v1.3.234)', '负缓存**不再把歌从播放列表里剔掉**')
chk('PlayerBridge', pb2, '同名过歌手闸门', 'return titleOnlyIdx(title, artist);')
chk('PlayerBridge', pb2, '歌手闸门实现', 'private static boolean artistOkForLocal(')
chk('PlayerBridge', pb2, '同名多首挑歌手', 'private static int titleOnlyIdx(')
chk('PlayerBridge', pb2, '① 命中记库内真名', 'addLocFix(locFix, title, artist, keep, loc);')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), 'k 字段自愈', 'k 自愈 —— 磁盘上有个别条目的 k 字段被错误编码写坏过')
chk('PlayerBridge', pb2, '队列按收藏顺序重排', 'sortPickByFav(pick, pickFav);')
chk('PlayerBridge', pb2, '入列同记收藏序号', 'private static void pickAdd(List<Integer> pick, List<Integer> favId, int idx, int fi)')
chk('PlayerBridge', pb2, 'need 带收藏序号(v1.3.237)', '[4] = 收藏第几条（v1.3.237 排顺序用）')
chk('PlayerBridge', pb2, '占位歌也记收藏序号', 'pickAdd(pick, pickFav, tracks.size() - 1, gf);')
chk('PlayerBridge', pb2, 'play_fav_one 用精确源', 'pu = b + "/r/" + src + "/" + rid')
chk('PlayerBridge', pb2, 'play_fav_one 注册 dispatch', 'if ("play_fav_one".equals(op)) {')
chk('WebConsole', rd('v2src/cn/workbuddy/r1client/WebConsole.java'), 'play_fav_one 双登记 WebConsole', 'return PlayerBridge.dispatch("play_fav_one", a3);')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), '歌手同源判据', 'public static boolean artistSame(String a, String b) {')
chk('Fav', rd('v2src/cn/workbuddy/r1client/Fav.java'), 'ltitle 歌手冲突判脏', '!artistSame(artist, it.optString("lartist", ""))')
chk('PlayerBridge', pb2, '直链地址验同曲', 'private static boolean urlMatchesFav(String url, String title, String artist) {')
chk('PlayerBridge', pb2, 'playFav ③ 过地址闸门', '&& urlMatchesFav(url, title, artist)) {')
chk('PlayerBridge', pb2, '本地模糊过歌手闸门', '!Fav.artistSame(a, ca)')
chk('PlayerBridge', pb2, 'favRematch 方法', 'String favRematch()')
chk('PlayerBridge', pb2, 'fav_rematch 接后台在线补源', 'warmFavSource(rest)')
chk('PlayerBridge', pb2, 'fav_rematch 已登记 dispatch', '"fav_rematch".equals(op)')
chk('WebConsole', wc, 'fav_rematch 已登记 WebConsole 路由', 'dispatch("fav_rematch", null)')
chk('PlayerBridge', pb2, 'fav_resolve 播到才单次本地匹配', 'int lh = findLocalFuzzy(t, a, lok, lKT, lKAT);')
chk('PlayerBridge', pb2, 'fav_resolve 本地命中即返本地url', 'return lurl;')
chk('R1Lights',       lights, '单颗桥接地址',   'PICK_URL = "http://127.0.0.1:8080/api/act?op=led1&v="')
# ---- v1.3.272：收藏点播严格链（书变歌/Hotline Bling 变 Drake 的根修）----
chk('PlayerBridge', pb2, 'play_song 收藏点播拦截', 'String wA = (args == null) ? "" : args.optString("artist", "").trim();')
chk('PlayerBridge', pb2, '严格链拿不下报错不盲播', '没搜到可信的版本（同名/翻唱都不可信）')
chk('PlayerBridge', pb2, 'favStrictPlay 存在',     'private static String favStrictPlay(String t, String a)')
chk('PlayerBridge', pb2, '严格链节目优先',         '"program".equals(kind)')
chk('PlayerBridge', pb2, '严格链打分搜歌',         'favSearchOne(t, a, 8000, kindF)')
chk('WebConsole',   wc,  'play_song 透传 artist',  'a.put("artist", was.trim());')
chk('PlayerBridge', pb2, '语音盲搜先打分挑',       'JSONObject pick = searchPick(b, q, q, "", searchMs, "");')
chk('PlayerBridge', pb2, '打分不可信退热度第1条',  '全场都不可信（unsure）才退回原来的「热度第 1 条」')
# ---- v1.3.272b：乱码/慢播双修（严格链快路径 + 搜书剥集号 + 书缓存）----
chk('PlayerBridge', pb2, '严格链快路径吃缓存',     'fav strict play (cached)')
chk('PlayerBridge', pb2, '搜书剥掉第N集',          'private static String abBookOnly(String t)')
chk('PlayerBridge', pb2, '书缓存字段',             'ABPICK_LOCK')
chk('PlayerBridge', pb2, '书缓存查询用裸书名',     '(a + " " + bt) : bt')
# ---- v1.3.272：整本集表缓存 + play-first（枚举转后台，点的这集先播）----
chk('PlayerBridge', pb2, '整本集表缓存字段',       'ABEP_LOCK')
chk('PlayerBridge', pb2, '整本拉取函数',           'private static JSONObject abAllEpisodes(String book, String albumId)')
chk('PlayerBridge', pb2, 'play-first 前台快搜',    'favSearchOne(t, a, 6000, "program")')
chk('PlayerBridge', pb2, '枚举转后台',             'warmBook(fbt, fa)')
chk('PlayerBridge', pb2, '预热线程',               '"abWarm"')
chk('PlayerBridge', pb2, '找书抽出复用',           'private static JSONObject searchPickBook(String bt, String a)')
chk('PlayerBridge', pb2, '定位写回抽出复用',       'private static String resolveFromBook(JSONObject pick, JSONObject d, String t, String a, String k)')
# ---- v1.3.273：整本预绑定（枚举完把收藏里同书各集批量写回，免 2h 缓存 + 重启不丢）----
chk('PlayerBridge', pb2, '整本预绑定函数',         'private static void bindAllEpisodes(JSONObject pick, JSONObject d)')
chk('PlayerBridge', pb2, '预绑定接 resolveFromBook', 'bindAllEpisodes(pick, d);')
chk('PlayerBridge', pb2, '预绑定接 warmBook',       'bindAllEpisodes(pick, dd);')
# ---- v1.3.217：开底部发 ambon ----
chk('R1Lights', lights, 'ambon 指令',   'ledWrite(ALL_URL + "ambon:" + hex6(sNightColor)')
chk('R1Lights', lights, 'ambon 理由',   '★ v1.3.217：以前这里发的是')
# ---- v1.3.216：关夜灯不再自己写黑 A 组 ----
chk('R1Lights', lights, '写黑只管顶部',   'private static void forceBlackTop()')
chk('R1Lights', lights, '不写黑A组',      '★ <b>不再写黑 A 组</b>（504）')
chk('R1Lights', lights, '关灯交回底部',   'ambBack();                  // ★ 底部交回 player，别自己写黑')
chk('R1Lights', lights, '只顶部也交回',   'ambBack();                  // ★ 同上：不亮的那组交给 player 收尾')
chk('R1Lights', lights, 'ambBack 方法',   'private static void ambBack()')
chk('R1Lights', lights, 'ambback 指令',   'ledWrite(ALL_URL + "ambback"')
# ---- v1.3.215：夜灯开关灯不打自己、关灯接回氛围灯 ----
chk('R1Lights', lights, '清场不再自抹',    '★ v1.3.215：这里**不再** show 504/505 写黑')
chk('R1Lights', lights, '恢复氛围灯延后',  'private static void restoreAmbientLater()')
chk('R1Lights', lights, '关灯调恢复',      'restoreAmbientLater();      // v1.3.215：最后再把原厂氛围灯接回来')
chk('R1Lights', lights, '恢复延时常量',    'AMB_RESTORE_DELAY_MS = 900L')
chk('R1Lights',       lights, '单颗记账',       'private static volatile int sPickIdx = 0;')
chk('R1Lights',       lights, '单颗点亮',       'private static void pick(int k, boolean on)')
chk('R1Lights',       lights, '单颗熄灭',       'private static void pickOff()')
chk('R1Lights',       lights, '收尾写黑',       'pickOff();')
chk('R1Lights',       lights, '单颗调试入口',   'v.startsWith("pick:")')
chk('R1Lights',       lights, '唤醒交回记账',   'sPickIdx = 0;')
chk('R1Lights',       lights, '状态回显 pick',  '+ " pick=" + sPickIdx')
# ---- v1.3.207：唤醒动画兜底 + 去重改口径 ----
chk('R1Lights',       lights, '动画时长常量',   'private static final long ANIM_MS = 5400L;')
chk('R1Lights',       lights, '动画起始时刻',   'private static volatile long sAnimTs = 0;')
chk('R1Lights',       lights, '唤醒刻时间戳',   'sAnimTs = System.currentTimeMillis();')
chk('R1Lights',       lights, '调试 state',     '"state".equals(v)')
chk('R1Lights',       lights, '状态回显动画',   '+ " animRest="')
# ---- v1.3.211：夜灯 ----
chk('R1Lights',        lights, '夜灯入口',       'public static String night(String v)')
chk('R1Lights',        lights, '夜灯顶部',       'private static volatile boolean sNightTop')
chk('R1Lights',        lights, '夜灯底部',       'private static volatile boolean sNightAmb')
chk('R1Lights',        lights, '夜灯 503',       'NIGHT_ON = 503')
chk('R1Lights',        lights, '夜灯 505',       'NIGHT_OFF = 505')
chk('R1Lights',        lights, '夜灯亮度上限',   '★ 上限取 <b>100</b>')
chk('R1Lights',        lights, '灯效全停闸门',   '夜灯期间**所有灯效全部停用**')
chk('R1Lights',        lights, '闸门放行夜灯',   'sNightBypass')
chk('R1Lights',        lights, '底部借 player',  'ALL_URL =')
chk('R1Lights',        lights, '夜灯上灯',       'private static void nightApply(')
chk('R1Lights',        lights, '夜灯持久化',     'night.txt')
chk('R1Lights',        lights, '夜灯恢复',       'public static void restoreNight()')
chk('R1Lights',        lights, '夜灯状态',       'public static String nightState()')
chk('R1Lights',        lights, '夜灯 MCP',       'nightMcp(')
chk('R1Lights',        lights, '夜灯状态 JSON',  'public static org.json.JSONObject nightJson()')
# ---- v1.3.212：清场改逐个 close ----
chk('R1Lights',        lights, '清场 id 表',     'private static final int[] CLEAR_IDS')
chk('R1Lights',        lights, '504 灭 A 组',    'AMB_OFF = 504')
chk('R1Lights',        lights, '彻底清场',       'private static void clearAllEffects()')
chk('R1Lights',        lights, '只清 A 组',      'private static void ambClear()')
chk('R1Lights',        lights, 'A 组清场 id',    'AMB_CLEAR_IDS')
chk('R1Lights',        lights, '闸门放行 504',   'arg1 != AMB_OFF')
chk('R1Lights',        lights, '改色先清 A 组',  'ambClear();        // v1.3.212')
chk('R1Lights',        lights, '清场说明',       'pending 复活')
# ---- v1.3.213：顶 / 底都直写 ----
chk('R1Lights',        lights, '顶部走直写',   'TOP_URL =')
chk('R1Lights',        lights, '顶部写色',     'private static void topWrite(')
chk('R1Lights',        lights, '写灯复用',     'private static void ledWrite(')
chk('R1Lights',        lights, '夜灯不 show 503', '不 show 503')
chk('R1Lights',        lights, '小飞那条路',   'lights_test set')
# ---- v1.3.214：总开关 vs 「亮哪一组」 ----
chk('R1Lights',        lights, 'part 字段',       'private static volatile String sNightSel')
chk('R1Lights',        lights, 'part 分支',       'v.startsWith("part:")')
chk('R1Lights',        lights, 'on 按 part 亮',   'nightApplySel();')
chk('R1Lights',        lights, '关着改 part 不点灯', '（当前夜灯是关着的，没有点灯）')
chk('R1Lights',        lights, '调试后同步 part', 'private static void syncSel()')
chk('R1Lights',        lights, 'part 归一化',     'private static String normSel(')
chk('R1Lights',        lights, 'status 带 on',    'o.put("on", nightOn())')
chk('R1Lights',        lights, 'status 带 part',  'o.put("part", sNightSel)')
chk('R1Lights',        lights, 'part 落盘',       '+ " part=" + sNightSel')
chk('R1Lights',        lights, 'part 恢复',       'sNightSel = normSel(p.trim())')
chk('R1Lights',        lights, 'MCP part',        'a.has("part")')
chk('XiaoZhiWs',       xz,     '夜灯工具',       '"night"')
chk('WebConsole',      wc,      '夜灯路由',       '"night".equals(op)')
chk('WebConsole',      wc,      '夜灯 status',    'o.put("night", R1Lights.nightJson())')
# ---- v1.3.210：聆听灯回退「完整动画」----
chk('R1Lights',        lights, '回退说明',       'v1.3.210：回退 —— 直接调<b>完整动画</b>')
chk('R1Lights',        lights, '聆听走完整动画', 'send(idx, ON);')
chk('R1Lights',        lights, '聆听日志',       '完整动画）')
chk('R1Lights',        lights, '已亮不重播',     '★ 已亮着就不重播')
chk('R1Lights',        lights, '动画戳清零',     'sAnimTs = 0;')
chk('R1Lights',        lights, '播报清动画戳',   'sAnimTs = 0;       // 动画已被 close 打断')
chk('R1Lights',        lights, '清残留直写',     'pickOff();         // 兜底清残留直写')
chk('R1Lights',        lights, '唤醒刻时间戳',   'sAnimTs = System.currentTimeMillis();')
chk('R1Lights',        lights, '调试 listen',    '完整动画）')
chkNo('R1Lights',      lights, '补偿已移除',     'scheduleFix()')
chkNo('R1Lights',      lights, '补写已移除',     'ensurePick()')
chkNo('R1Lights',      lights, '补偿常量已移除', 'FIX_DELAYS')
chkNo('R1Lights',      lights, '补偿任务已移除', 'fixTasks')
chkNo('R1Lights',      lights, '聆听不再直写',   'pick(idx, true);')
chkNo('R1Lights',      lights, '去重不再看单颗', 'if (sPickIdx == idx) {')
chkNo('R1Lights',      lights, '排期补写已移除', 'scheduleEnsure(')
# ---- v1.3.208：唤醒只播动画、灭灯路径清动画时间戳 ----
chkNo('R1Lights',      lights, '唤醒不再抢先直写', '唤醒时也**立刻直写一次**这一颗')
# ---- v1.3.209：抢灯补偿 ----
chk('SessionController', sc,  'setState 驱动聆听灯', 'R1Lights.setListening((s == State.LISTENING || s == State.CONNECTING)')
chk('SessionController', sc,  '告别语不算在听',   '&& !farewellActive);')
chk('WebConsole',     wc,     'set 收 listenLight', '"listenLight"')
chk('WebConsole',     wc,     '状态带 listenLight', 'a.put("listenLight"')
chk('ui',             u,      '聆听灯开关',       'swLsL')

raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.10/6] 自检：web 脱离 8080（v1.3.173 自有队列 + 总线曲名 + play_at）=="
python - <<'PCHECK'
import io
def rd(p):
    return io.open(p, encoding='utf-8', errors='replace').read()
u    = rd('ui/ui.html')
pb   = rd('v2src/cn/workbuddy/r1client/PlayerBridge.java')
wb   = rd('v2src/cn/workbuddy/r1client/WakeupBridge.java')
wc   = rd('v2src/cn/workbuddy/r1client/WebConsole.java')
ok = True
def chk(where, src, name, kw):
    global ok
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
def chkNo(where, src, name, kw):
    """负面断言：这段必须已经不存在。"""
    global ok
    if kw in src:
        print('  STILL    ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
    else:
        print('  OK(去掉) ' + name)
# 后端：自有队列
chk('PlayerBridge', pb, '队列缓存字段',   'lastIdx')
chk('PlayerBridge', pb, '总线曲目入口',   'public static void onBusSong(')
chk('PlayerBridge', pb, 'music_id 反查',  'private static int indexOfMusicId(')
chk('PlayerBridge', pb, '队列 JSON',      'public static JSONObject queueJson(')
chk('PlayerBridge', pb, '队列内跳转',     'public static String playAt(int i)')
chk('PlayerBridge', pb, 'subType=15 跳播', 'snap, radio, i, 15')
chk('PlayerBridge', pb, '兜底 rotate',     'play_at_rotate')
chk('PlayerBridge', pb, 'subType 可选',    'int startIndex, int subType')
chk('PlayerBridge', pb, 'dispatch play_at', '"play_at".equals(op)')
chk('PlayerBridge', pb, 'status 带队列',  'o.put("qTotal"')
chk('PlayerBridge', pb, 'status 带歌手',  'o.put("curArtist"')
# 后端：总线接线 + 路由
chk('WakeupBridge', wb, '曲目事件转发',   'PlayerBridge.onBusSong(')
chk('WakeupBridge', wb, 'JSON 取字段',    'private static String jsonField(')
chk('WebConsole', wc,   '/api/queue 路由', '"/api/queue"')
chk('WebConsole', wc,   'op=play_at 路由', '"play_at".equals(op)')
# v1.3.173：镜像端口（8080 也能开我们的控制台）
chk('WebConsole', wc,   '镜像端口字段',    'mirrorPort')
chk('WebConsole', wc,   '镜像 accept 循环', 'private static void serveMirror(')
chk('WebConsole', wc,   '公共 accept 循环', 'private static void acceptLoop(')
chk('WebConsole', wc,   '镜像开关文件',    '"mirror_port"')
# v1.3.173：配合 player 侧控制台 —— device 自停接口 + 8080 默认让给 player
chk('WebConsole', wc,   'op=halt 路由',    '"halt".equals(op)')
chk('WebConsole', wc,   '自杀让位 player',  'killProcess(android.os.Process.myPid())')
chk('WebConsole', wc,   '8080 默认关',      'int mirrorPort = 0')
# 前端：不再死等 8080
chk('ui', u, '8080 缺席标记',   'wsAbsent')
chk('ui', u, '自有队列拉取',    'function loadQueue(')
chk('ui', u, '点播走 play_at',  'act("play_at"')
chk('ui', u, '队列双数据源',    'wsOK ? PLIST : (Q.list')
chkNo('ui', u, '不再死等 8080', '正在等音箱播放服务')
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

python - <<'PCHECK'
d = open('v22_merged.dex', 'rb').read()
for t in [b'onBusSong', b'queueJson', b'play_at', b'/api/queue', b'songName', b'music_id']:
    n = d.count(t)
    print('  %-12s in dex: %d' % (t.decode(), n))
    assert n >= 1, t
print('  dex symbols PRESENT: OK')
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.11/6] 自检：在线搜歌 + 实际歌曲信息（v1.3.175）=="
python - <<'PCHECK'
p = 'v2src/cn/workbuddy/r1client/PlayerBridge.java'
s = open(p, encoding='utf-8').read()
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8').read()
wb = open('v2src/cn/workbuddy/r1client/WakeupBridge.java', encoding='utf-8').read()
x = open('v2src/cn/workbuddy/r1client/XiaoZhiWs.java', encoding='utf-8').read()
b = open('v2src/cn/workbuddy/r1client/XzBootstrap.java', encoding='utf-8').read()
h = open('ui/ui.html', encoding='utf-8').read()
ok = True
def chk(name, kw, src=None):
    global ok
    src = s if src is None else src
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ')')
        ok = False
# ---- 版本（v1.3.323：改为从源码动态读取，避免升版本后自检必然 FAIL 而中断构建）----
import re as _re
_m = _re.search(r'VERSION = "([^"]+)"', b)
_ver = _m.group(1) if _m else '?'
chk('VERSION ' + _ver, 'VERSION = "' + _ver + '"', b)
# ---- v1.3.304：跳书修复（abQueueProgLazy 钉状态 + 收藏 program 钉 loader）+ 安全网收紧 ----
chk('abQueueProgLazy 定义', 'private static String abQueueProgLazy(', s)
chk('ProgPlay 走 abQueueProgLazy', 'getDeclaredMethod("abQueueProgLazy"',
    open('v2src/cn/workbuddy/r1client/ProgPlay.java', encoding='utf-8').read())
chk('playFavOne 钉 loader', 'abLoaderSave(bk0, al, sc, 1, abLoaderSize(), 0, e0, e0);', s)
chk('program slot[4] 记书名', '★ v1.3.302：program track 的 slot[4] 记书名', s)
chk('安全网 loaderExact', 'boolean loaderExact = lb.length() > 0 && la.length() > 0', s)
chk('回退仅 loaderExact', 'if (alb.isEmpty() && loaderExact) {', s)
# ---- v1.3.304：Prog.add 加完立即生成骨架（预加载空列表秒开）----
pgs = open('v2src/cn/workbuddy/r1client/Prog.java', encoding='utf-8').read()
chk('Prog.add 即时骨架注释', '★ v1.3.302：加完立即生成骨架', pgs)
chk('Prog.add 调 setEpsSkeleton', 'setEpsSkeleton(albumId, count, source);', pgs)
# ---- v1.3.304：未绑定集占位 URL 不丢行（队列断档 ⇒ 点上一集跳 677/队列 630/651/665 的根修）----
chk('ProgLazy 占位注释', '★ v1.3.304：未绑定集不丢行', s)
chk('ProgPrev 占位注释', '★ v1.3.304：未绑定集占位 URL，播到才解析', s)
chk('ProgPrev 定位兜底', 'pos0 = n - 1;', s)
chk('ProgPrev 兜底日志', 'not in batch => tail pos=', s)
chk('ProgPrev 带 album 参数', 'abQueueBookPrev(d.optString("book", book), album,', s)
# ---- v1.3.306：收藏/节目点击连排（收藏列表窗口，条数取本地数/在线数大的）----
chk('favCacheRow 定义', 'private static String[] favCacheRow(JSONObject it', s)
chk('收藏窗口预算取大', 'int budget = (lm == 0) ? Integer.MAX_VALUE : Math.max(lm, onlineMax());', s)
chk('收藏窗口统一注释', '收藏列表窗口（本地/在线两个分支统一）', s)
chk('ProgLazy 在线数', 'int cap = onlineMax();          // ★ v1.3.306', s)
chk('StepProg 窗口在线数', 'int w = onlineMax();', s)
# ---- v1.3.314：SP 里老格式单条记忆就地迁移成「按书一张表」----
chk('迁移旧格式', 'listen pos 迁移旧格式 -> ', s)
# ---- v1.3.313：听书记忆不再过期（存住），上限 8→50 本，写入打日志 ----
chk('记忆不过期', 'private static JSONObject livePosOf(String book) {', s)
chk('记忆上限 50', 'while (ks.size() > 50) {', s)
chk('记忆写入日志', 'Log.i(TAG, "listen pos " + book', s)
# ---- v1.3.312：听书记忆改「按书一张表」（交叉听书后每本各记各的）----
chk('记忆表读取', 'return o;                                // «书名key: {book,ep,pos,ts}» 的一张表', s)
chk('记忆表按书取', 'JSONObject o = m.optJSONObject(k);', s)
# ---- v1.3.311：收藏过的书续播走 ProgPlay（NAS 按页取集列表会漏集）----
chk('续播优先 ProgPlay', 'String r2 = ProgPlay.play(a2);', s)
chk('续播 ProgPlay 打日志', 'abResume via ProgPlay ep=', s)
# ---- v1.3.310：换书续播不再拿「当前在播那本」的集号去续（交叉听书错集）----
chk('换书归零全局集号', 'last = 0;                                // 换书 ⇒ 全局集号作废', s)
chk('换书取收藏里的版本', 'album = pa;                          // 收藏过的书优先用收藏里的版本', s)
chk('lastListenEp 定义', 'public static int lastListenEp(String book)', s)
chk('换书按本书重取集号', 'last = lastListenEp(book);           // 没收藏的书：看 SP 那份最近听书记忆', s)
# ---- v1.3.309：不在「我的节目」里的书也记「听到第几秒」（SP 记忆，与收藏无关）----
chk('liveListenNow 定义', 'public static String[] liveListenNow()', s)
chk('noteListenPos 定义', 'public static void noteListenPos(String book, int ep, int posSec)', s)
chk('lastListenPos 定义', 'public static int lastListenPos(String book, int ep)', s)
chk('续播 SP 兜底', 'int lp = lastListenPos(d.optString("book", book), e0.optInt("ep", 0));', s)
ppg = open('v2src/cn/workbuddy/r1client/ProgProgress.java', encoding='utf-8').read()
chk('ProgProgress 记 SP', 'PlayerBridge.noteListenPos(live[0], ep, pos);', ppg)
chk('ProgProgress 读 liveListenNow', 'String[] live = PlayerBridge.liveListenNow();', ppg)
chk('ProgProgress v1.3.337 设备身份上报', 'j.put("devId", devId());', ppg)
chk('ProgProgress v1.3.343 MAC 身份', 'String mac = PlayerBridge.mac12();', ppg)
# ---- v1.3.346：拉流 URL 带 dev 设备身份（群晖 docker 下 R1 播放记录误落网页桶根修）----
chk('v1.3.346 withDevIdentity 定义', 'private static String withDevIdentity(String url)', s)
chk('v1.3.346 song 挂 dev 身份', 'withDevIdentity(url)', s)
chk('v1.3.346 dev 参数名', '"&devName=" + dn', s)
# ---- v1.3.347：续播读取带 MAC（服务端 v1.7.38 设备分组按组严格隔离）----
chk('v1.3.347 /v1/resume 带 dev', '"&dev=" + m', s)
chk('v1.3.347 /v1/history 带 dev', '"/v1/history" + (m.length() > 0 ? ("?dev=" + m)', s)
chk('ProgProgress v1.3.338 型号固定 R1', 'private static String devModel()', ppg)
chk('ProgProgress v1.3.340 结算上报', 'public static void srvFinal(String reason)', ppg)
# ---- v1.3.308：语音续播定位到「上次听到的那一秒」+ 听书进度自记 ----
chk('ab_resume 读 lastPos', 'int lp = pe.optInt("lastPos", 0);', s)
chk('ab_resume 通知 player 定位', 'askPlayerResumeSeek(posSec, e0.optInt("ep", 0));', s)
chk('续播定位走 player 守卫', '/api/ab/resume?pos=', s)
chk('liveProgNow 定义', 'public static String[] liveProgNow()', s)
chk('playerPosSec 定义', 'public static int playerPosSec()', s)
chk('ProgProgress 启动', 'ProgProgress.start();', b)
chk('Prog.findByAlbum 定义', 'public static JSONObject findByAlbum(String albumId)', pgs)
chk('换集归零 lastPos', 'it.put("lastPos", 0);      // 换集 ⇒ 从头开始', pgs)
# ---- v1.3.307：队列 /r/prog 占位行入列即后台预填（只暖所在页，不拉整本）----
chk('ProgPrefetch 定义', 'static void progPrefetchKick()', s)
chk('ProgPrefetch 调用', 'progPrefetchKick();', s)
chk('ProgPrefetch 占位前缀', 'PROG_URL_PREFIX = "http://127.0.0.1:8080/r/prog?"', s)
# ---- v1.3.305：abNext/abPrev 缓存直通（NAS 分页失败跌回过期 SP ⇒ 677/690 错位的根修）----
chk('abStepProg 定义', 'private static String abStepProg(boolean prev', s)
chk('缓存直通 next', 'abStepProg(false, book, album, lr)', s)
chk('缓存直通 prev', 'abStepProg(true, book, album, lr)', s)
chk('lastEp 兜底 next', 'abNext lastEp fallback cur=', s)
chk('lastEp 兜底 prev', 'abPrev lastEp fallback cur=', s)
chk('窗口去重归一', 'TreeMap<Integer, JSONObject> win', s)
# ---- v1.3.304：tail-kick 防误触三道门（点节目被自作主张续播 ep220/顶回旧书的根修）----
chk('lastPushTs 记录', 'lastPushTs = System.currentTimeMillis();', s)
chk('recentPushMs 定义', 'public static boolean recentPushMs(long windowMs)', s)
chk('isQueueTailTitle 定义', 'public static boolean isQueueTailTitle(String title)', s)
chk('queueTailBook 定义', 'public static String queueTailBook()', s)
chk('tk 门① recent push', 'PlayerBridge.recentPushMs(30000)', wb)
chk('tk 门② 队尾判据', 'PlayerBridge.isQueueTailTitle(prev)', wb)
chk('tk 书名取队列元数据', 'PlayerBridge.queueTailBook()', wb)
chk('tk 门③ Prog albumId', 'not in Prog (no albumId) => skip', wb)
chk('tk 全角空格剥离', 'bk.replace("　", " ").trim()', wb)
# ---- v1.3.276：ab_ver_hist 按书记版本（续播只说集数不再盲挑）----
chk('abHistSave hook', 'abHistSave(book, album);', s)
chk('abHistFind', 'private static String abHistFind(String bq)', s)
chk('abPlay hist 候选', 'histAlbum = abHistFind(bq);', s)
chk('abResume hist 兜底', 'String ha = abHistFind(book);', s)
# ---- v1.3.279：random 系入口剔除有声书/节目; list 节目单列 ----
chk('random 过滤节目', 'isProgramRow(tracks.get(ix))', s)
chk('abLikely 判据', 'AB_EP_RE.matcher(t).find()', s)
chk('list 节目单列', '集有声书/节目', s)
chk('randomIdx 同步', 'synchronized (tracks)', s)

# ---- v1.3.278：status 本地记录过期改报 player 实值; play_at 工具 ----
chk('status 278 自愈', 'curTitle = lTitle;', s)
chk('status 278 markT', 'final int markT = sb.length();', s)
chk('play_index 警告', '有声书/评书跳集绝对不要用', x)
chk('play_at 工具', 'tool("play_at",', x)
chk('play_at 返回', 'OK 已跳到播放列表第 " + n + " 首', s)
chk('play_index 返回注记', '播放队列已换成曲库列表', s)
chk('abNext live 基准', 'liveNowRow()', s)
chk('abNext fast bus', 'abNext fast bus cur=" + lep', s)
chk('abNext idem 护栏', 'abNext idem jump ep=" + next', s)
chk('abPrev idem 护栏', 'abPrev idem jump ep=" + prev', s)
chk('abPrev fast bus', 'abPrev fast bus cur=" + lep', s)
chk('abEpNo 集号解析', 'abCnNum(g.replaceAll', s)
chk('abResume live 续播', 'abResume live ep=', s)
chk('next 节目转下一集', 'next->abNext', s)
chk('ab_prev 整批定位', 'abQueueBookPrev', s)
chk('ab_prev 登记分发', '"ab_prev".equals(op)', w)
chk('status 278 去重括号', 'lTitle.startsWith(\"《\")', s)

# ---- v1.3.277：小智视角队列以 player 真队列为准 ----
chk('playerLiveQueue', 'private static JSONObject playerLiveQueue(int limit)', s)
chk('queue live-first', 'JSONObject live = playerLiveQueue(lim);', s)
chk('play_at 本地跳播', 'op=play_at&index=', s)
chk('status 镜像过期纠偏', 'boolean mirrorStale = lTitle.length() > 0', s)
chk('WebConsole queue 双登记', 'return PlayerBridge.dispatch("queue", a);', w)
# ---- v1.3.194：网络流蓄水 5 秒（起播不再卡 3~4 秒）----
chk('蓄水已回退到 800ms', 'private static final long NET_STREAM_KICK_MS = 800L;', s)
# 该块只定义了 chk(name, kw, src)，没有 chkNo —— 手写一条反向断言
if 'NET_STREAM_KICK_MS = 5000L;' in s:
    print('  MISSING  蓄水不该还是 5000L'); ok = False
else:
    print('  OK       蓄水不再是 5000L')
# v1.3.196：专辑占位名洗白 + 电台清残留 + queue_all
#   ★ 特征串一律用源码里的**唯一注释文字**，不抄缩进、不抄带转义的原文
#     （pv38 那三条 FAIL 全栽在空格数差 1 / 多转义一层）
chk('专辑洗白方法', 'private static String sanitizeAlbum(String a, String title)', s)
chk('notePlayingFull 走洗白', 'curAlbum = sanitizeAlbum(album, curTitle);', s)
chk('notePlayingBus 走洗白', 'curAlbum = sanitizeAlbum(e.length > 4 ? e[4] : "", curTitle);', s)
chk('电台切台清残留', 'v1.3.196：切台', s)
chk('全部电台连播清残留', 'v1.3.196：同 playRadio', s)
chk('queue_all op', 'v1.3.196：「全部载入播放列表」', s)
chk('queue_all 记账', '"queue_all".equals(op))', s)
# v1.3.197：路由表必须登记 queue_all，否则掉到底就是 "unknown op queue_all"
#   （本块只定义了 s，WebConsole 单独读一次）
_w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
chk('queue_all 路由', 'v1.3.197：「全部载入播放列表」', _w)
chk('queue_all 转 dispatch', 'PlayerBridge.dispatch("queue_all", a)', _w)
# ★ 反向断言：以后谁再在 WebConsole 里加 dispatch 分支却忘了路由，能被抓住
if 'PlayerBridge.dispatch("queue_all", a)' not in _w:
    print('  MISSING  WebConsole 没转 op=queue_all'); ok = False
# v1.3.198：在线结果整批入队 + 留空搜索洗牌
chk('queue_online op', 'v1.3.198：「在线搜索结果 → 全部载入播放列表」', s)
chk('只取链的助手', 'private static String onlineUrl(String title, String artist, String source, String rid,', s)
chk('惰性地址兜底', 'b + "/r/" + source + "/" + rid', s)
chk('queue_online 路由', 'PlayerBridge.dispatch("queue_online", a)', _w)
chk('搜索支持洗牌', 'public static JSONObject searchJson(String q, int lim, boolean shuffle)', s)
chk('洗牌后再截断', 'ord != null ? ord[j] : j', s)
chk('WebConsole 接 shuffle', 'PlayerBridge.searchJson(q.get("k"), sl, shuf)', _w)
chk('裸网址走蓄水', 'kickPlay("play_url", NET_STREAM_KICK_MS, 2);', s)
chk('电台走蓄水(2处)', 'kickPlay(why, NET_STREAM_KICK_MS, 2);', s)
chk('没有残留 800,5', '800, 5', s) if False else None
# ---- v1.3.199：语音软开关（省电模式）—— 只停「KWS + 收音 + 小智本体」，web 点播不受影响 ----
_v = open('v2src/cn/workbuddy/r1client/VoicePower.java', encoding='utf-8', errors='replace').read()
chk('VoicePower 旗标名', 'FLAG = "voice_off"', _v)
chk('VoicePower 存引擎', 'public static void noteEngine(Object engine)', _v)
chk('doStopWakeup 受控', 'public static boolean allowStopWakeup()', _v)
chk('doEnterWakeup 受控', 'public static boolean blockEnterWakeup()', _v)
chk('兜底直调 j.b()', 'sb.append(" j.b=").append(invoke(j, "b"));', _v)
chk('停时关 WS', 'XzBootstrap.dropWs("voice-off")', _v)
chk('voice op 路由', 'v1.3.199：语音软开关（省电模式）。', _w)
chk('voice op 分支', 'if ("voice".equals(op)) {', _w)
chk('启动读旗标', 'VoicePower.boot(app, new File(app.getFilesDir(), VoicePower.FLAG).exists());', b)
chk('唤醒被省电态拦', 'wakeup ignored (省电模式)', b)
chk('状态带 voice 段', 'o.put("voice", VoicePower.json());', b)
# ---- v1.3.200：我的收藏 ----
_f = open('v2src/cn/workbuddy/r1client/Fav.java', encoding='utf-8', errors='replace').read()
_x = open('v2src/cn/workbuddy/r1client/XiaoZhiWs.java', encoding='utf-8', errors='replace').read()
chk('Fav 主本在 device', 'FILE = "r1_fav.json"', _f)
chk('Fav key 归一化', 'return t + "|" + norm(artist);', _f)
chk('Fav 原子写', 'tmp.renameTo(f)', _f)
chk('Fav 上限 5000', 'MAX = 5000', _f)
chk('Fav 强制写字段', 'public static void force(String k, String field, String value) {', _f)
chk('Fav 真名也能定位', 'public static JSONObject hit(String k) {', _f)
chk('Fav 在线缓存查歌手', '!artistSame(artist, it.optString("rartist", ""))', _f)
chk('Fav 手动绑定豁免清洗', '"1".equals(it.optString("rman", ""))', _f)
chk('Fav 清缓存含 url', '"source", "rid", "url", "nsTs"', _f)
chk('收藏入列', 'public static String add(JSONObject it)', _f)
chk('取消收藏', 'public static String del(String title, String artist)', _f)
chk('收藏列表 JSON', 'public static JSONObject json()', _f)
chk('/api/fav 路由', 'path.equals("/api/fav")', _w)
chk('增减/切换路由', 'if ("fav_add".equals(op) || "fav_del".equals(op) || "fav_toggle".equals(op)) {', _w)
chk('收藏当前曲路由', 'if ("fav_add_cur".equals(op)) {', _w)
chk('取消当前曲路由', 'if ("fav_del_cur".equals(op)) {', _w)
chk('播收藏路由', 'if ("fav_play".equals(op) || "play_fav".equals(op)) {', _w)
chk('播收藏本地优先', 'private static int findLocal(String title, String artist)', s)
chk('playFav 入口', 'public static String playFav()', s)
chk('收藏当前歌(语音)', 'public static String favAdd(JSONObject args)', s)
chk('取消收藏(语音)', 'public static String favDel(JSONObject args)', s)
chk('play_fav 分支', 'if ("play_fav".equals(op)) {', s)
chk('当前曲问 player', 'http://127.0.0.1:8080/api/fav?op=cur', s)
chk('MCP 工具 fav_add', 'tools.put(tool("fav_add"', _x)
chk('MCP 工具 fav_del', 'tools.put(tool("fav_del"', _x)
chk('MCP 工具 play_fav', 'tools.put(tool("play_fav"', _x)
chk('Fav 初始化', 'Fav.init(app);', b)
# ---- v1.3.201：光杆条目在线补源 ----
_f = open('v2src/cn/workbuddy/r1client/Fav.java', encoding='utf-8', errors='replace').read()
chk('Fav.patch 补空字段', 'public static void patch(String k, String field, String value)', _f)
chk('patch 只填空不覆盖', '已经有值，别覆盖', _f)
chk('在线搜源', 'private static JSONObject favSearchOne(String title, String artist, int ms)', s)
chk('负缓存判据', 'public static boolean skipSearch(JSONObject it)', _f)
chk('负缓存标记', 'public static void markNoSrc(String k)', _f)
chk('负缓存时长', 'NOSRC_TTL = 6 * 3600 * 1000L', _f)
# ---- v1.3.203（二）：key 归一化把韩文/符号歌名清成空串 ⇒ 互相误配 ----
chk('norm 保留所有字母数字', 'if (Character.isLetterOrDigit(c)) {', _f)
chk('全符号曲名兜底', 't = "~" + (title == null ? "" : title.trim().toLowerCase());', _f)
chk('读档重算 key', 'it.put("k", key(t, it.optString("artist", "")));', _f)
# v1.3.234：导入期已零搜索 ⇒ 负缓存不再剔歌，这里翻成反向断言
if 'if (Fav.skipSearch(it)) {' in s:
    print('  STILL    导入期负缓存剔歌应已撤掉'); ok = False
else:
    print('  OK       导入期负缓存剔歌已撤掉')
# ---- v1.3.203（三）：本地段 key 索引（导入 3.5s -> 毫秒级）----
chk('本地 key 索引缓存', 'private static volatile HashMap<String, Integer> sLocFull', s)
chk('索引按版本失效', 'sLocVer != sIdxVer', s)
chk('loadIndex 作废旧索引', 'sIdxVer++;', s)
chk('findLocal 查表', 'Integer v = full.get(Fav.key(title, artist));', s)
chk('搜不到就记住', 'Fav.markNoSrc(k);', s)
chk('四件套不取链', 'String ou = b + "/r/" + src + "/" + rid', s)
chk('补源也不取链', 'PENDING_URL + enc(pk)', s)
chk('搜源走 NAS /api/search', '"/api/search?q=" + URLEncoder.encode(q.trim(), "UTF-8")', s)
chk('曲名对得上优先', 'want.equals(Fav.key(s.optString("title", ""), ""))', s)
chk('播收藏收集光杆条目', 'need.add(new String[]{title, artist,', s)
chk('补源总预算 15s', 'PENDING_URL + enc(pk)', s)
chk('补到源就入列', 'got.add(new String[]{e[0], e[1], PENDING_URL + enc(pk), e[2], e[3], e[0],', s)
chk('补源回写收藏', 'Fav.patch(k, "source", src);', s)
# chk('没源再回库兜底', 'int h = findLocalFuzzy(need.get(i)[0], need.get(i)[1], keep, KT, KAT);', s)  # v1.3.230 已删回库兜底，改由 fav_resolve 播到才单次匹配
# ★ smali 两处钩子必须真的打进 uni_smali（忘了跑 patch_voice_power.py 就装旧引擎）
_sm = open('uni_smali/com/phicomm/speaker/device/custom/speech/SpeechManager.smali',
           encoding='utf-8', errors='replace').read()
_ne = open('uni_smali/com/unisound/vui/engine/NativeANTEngine.smali',
           encoding='utf-8', errors='replace').read()
chk('smali: 存引擎钩子', 'VoicePower;->noteEngine(Ljava/lang/Object;)V', _sm)
chk('smali: doStopWakeup 受控', 'VoicePower;->allowStopWakeup()Z', _ne)
chk('smali: doEnterWakeup 受控', 'VoicePower;->blockEnterWakeup()Z', _ne)
# ★ v1.3.254：顶部按键动作钩子必须真的打进 uni_smali（漏了就等于没接管双击/单击）
_kc = open('uni_smali/com/phicomm/speaker/device/custom/keyevent/PhicommKeyEventController.smali',
           encoding='utf-8', errors='replace').read()
chk('smali: 单击→TopKey.onClick', 'Lcn/workbuddy/r1client/TopKey;->onClick()V', _kc)
chk('smali: 双击→TopKey.onDoubleClick', 'Lcn/workbuddy/r1client/TopKey;->onDoubleClick()V', _kc)
# ★ v1.3.256：实体键暂停/恢复必须带记账 op 打给 player —— 否则 PCtl.sUserPaused
#   没人置位，phase() 吃「进度 4 秒内变过 ⇒ 当在播」的宽限，网页暂停显示慢 ~4 秒
_kt = open('v2src/cn/workbuddy/r1client/TopKey.java',
           encoding='utf-8', errors='replace').read()
chk('TopKey: 暂停带记账 op', 'notifyPlayer("toppause")', _kt)
chk('TopKey: 恢复带记账 op', 'notifyPlayer("topresume")', _kt)
# ★ 分支语义别写反：省电态才跑原厂实现 ⇒ 必须是 if-nez 跳去 :jp2_run
if 'if-eqz v0, :jp2_run' in _ne:
    print('  MISSING  doStopWakeup 分支写反了（if-eqz 会反过来：正常态去停唤醒）'); ok = False
else:
    print('  OK       doStopWakeup 分支方向正确')
# ---- v1.3.193：首脚 play 延迟可调（files/kick_delay_ms），用来验证「先攒够再开闸」----
chk('可调延迟入口', 'private static long firstKickDelay(long def)', s)
chk('flag 文件名', 'new File(app.getFilesDir(), "kick_delay_ms")', s)
chk('首脚用可调延迟', 'Thread.sleep(i == 0 ? firstKickDelay(delayMs) : 1000)', s)
# ---- v1.3.192：起播只踢一次（重复 kick 会让 player 重开声卡 ⇒ 起播卡 4 秒）----
chk('早退判据', 'if (musicActiveWithin(1200))', s)
chk('停止补踢日志', '后已在出声 ⇒ 停止补踢', s)
chk('轮询助手', 'private static boolean musicActiveWithin(long windowMs)', s)
chk('轮询判据 isMusicActive', 'am.isMusicActive()', s)
chk('注释已纠偏', 'play 幂等', s)  # 旧结论被推翻，注释里保留警示
chk('旧的幂等说法已删（non-flaky 校验靠上面几条）', 'kick play (', s)
# ---- v1.3.191：让路者（给 USC native 线程降 nice，把 CPU 还给播放链路） ----
g = open('v2src/cn/workbuddy/r1client/CpuGovernor.java', encoding='utf-8', errors='replace').read()
chk('CpuGovernor 类', 'public final class CpuGovernor', g)
chk('线程扫描 proc/self/task', 'new File("/proc/self/task").list()', g)
chk('stat 解析 nice', 'int nice = Integer.parseInt(f[16]);', g)
chk('降级入口', 'public static String cool(int nice)', g)
chk('降级调用', 'Process.setThreadPriority(t.tid, nice)', g)
chk('记录原 nice', 'LinkedHashMap<Integer, Integer> ORIG', g)
chk('恢复入口', 'public static String restore()', g)
chk('剖析入口', 'public static String probe(long winMs)', g)
chk('RT 线程识别', 't.prio < 0', g)
chk('cool op 路由', '"cool".equals(op)', w)
chk('cool v=probe', '"probe".equals(cv)', w)
chk('cool v=on', '"on".equals(cv)', w)
chk('cool v=off', '"off".equals(cv)', w)
# ---- v1.3.190：歌词多候选 —— 一首歌多个 rid，逐个试到有词的那个 ----
chk('候选列表函数', 'private static List<String[]> ridCandidates(String title, String artist)', s)
chk('候选缓存', 'ridListCache', s)
chk('去重助手', 'private static boolean hasRid(List<String[]> l, String[] rr)', s)
chk('逐个试到有词', 'for (int ci = 0; ci < cands.size(); ci++)', s)
chk('命中日志', 'lyric 命中 ', s)
chk('无词报尝试过', '这首歌没有歌词（试过 ', s)
chk('搜索取前 5 条', '&n=5&source=all', s)
chk('标题歌手对得上才收', 'boolean okT = kt.equals(songKey(s.optString("title", "")))', s)
# ---- v1.3.189：总线回推也要换封面（以前只改曲名 ⇒ 切歌后 curPic 还是上一首的） ----
chk('总线回推带封面', 'v1.3.189：封面也必须跟着这一首走', s)
chk('无封面时清空', 'curPic = "";', s)
chk('异步补封面', 'if (curPic.length() == 0 && curTitle.length() > 0)', s)
# ---- v1.3.188：封面 + 歌词（落雪经 NAS mini-lx 中转；/api/lrc 给页面用） ----
chk('歌词接口', 'public static JSONObject lyricJson(String title, String artist)', s)
chk('rid 解析 rel', 'private static String[] parseRel(String rel)', s)
chk('曲目→source/rid', 'private static String[] ridOf(String title, String artist)', s)
chk('封面取 NAS /api/pic', '"/api/pic?rid="', s)
chk('歌词取 NAS /api/lyric', '"/api/lyric?rid="', s)
chk('搜索结果带封面', 'jstr(s, "img")', s)
chk('起播补封面', 'fetchPicAsync(e[0], e[1])', s)
chk('status 带 curPic', 'o.put("curPic", curPic)', s)
chk('/api/lrc 路由', 'path.equals("/api/lrc")', w)
chk('JSON null 归一', 'private static String jstr(JSONObject o, String k)', s)
# ---- v1.3.187：入库去重（同名同歌手复用旧下标，跨源同曲合并；额度不再被重复搜索吃光） ----
chk('入库前查重', 'int ex = findSameOnline(it);', s)
chk('查重函数', 'private static int findSameOnline(String[] it)', s)
chk('曲名归一函数', 'private static String songKey(String s)', s)
chk('复用不占额度', 'reused++;', s)
chk('去重日志', 'online append 去重：复用库里已有', s)
# ---- v1.3.186：额度回收补完（判据=本次装不下；回收量覆盖缺口；计数按真实条目重数） ----
chk('判据=本次装不下', 'int need = onlineAppends + items.size() - ONLINE_APPEND_CAP;', s)
chk('回收量覆盖缺口', 'evictOldestOnline(Math.max(ONLINE_APPEND_CAP / 2, need))', s)
chk('只回收真 append（online/rand）', '"online".equals(e[7]) || "rand".equals(e[7])', s)
chk('计数按真实条目重数', 'onlineAppends = countOnlineAppends();', s)
chk('回收函数存在', 'private static int evictOldestOnline(int target)', s)
chk('重数函数存在', 'private static int countOnlineAppends()', s)
chk('javadoc 同步', '回收最早收录', s)
# ---- v1.3.184：play_artist 命中过滤在线条目（文案「本地 X 首」必须名副其实） ----
chk('play_artist 命中过滤在线', 'if (ix < 0 || ix >= tracks.size() || isOnlineTrack(tracks.get(ix))) {\n                        hits.remove(i);')
chk('play_artist ERR 报本地数', 'int locN2 = 0;')
# ---- v1.3.181：搜索曲库只出本地（在线 append 的条目过滤掉） ----
chk('searchJson 只搜本地(空词)', 'if (e.length > 7 && e[7] != null && e[7].length() > 0) {\n                        continue;')
chk('searchJson 只搜本地(有关键词)', 'local.add(Integer.valueOf(ix));')
# ---- v1.3.182：小智的 list/search 也只认本地（dispatch 是与 searchJson 独立的路径） ----
chk('isOnlineTrack 助手', 'private static boolean isOnlineTrack(String[] e)')
chk('artistSummary 本地开关', 'artistSummary(int maxArtists, boolean localOnly)')
chk('list 本地在线分报', 'OK 本地共 ')
chk('list 在线收录说明', '首在线曲库的歌（不属于本地')
chk('voice search 过滤在线', 'if (ix >= 0 && ix < tracks.size() && !isOnlineTrack(tracks.get(ix)))')
chk('voice search 没找到报本地数', '相关的本地歌，本地曲库共 ')
# ---- v1.3.183：启动加载的在线池段也标 via="pool"（真凶：100 首池歌 via="" 冒充本地） ----
chk('loadIndex 池段打标', 'String via = (k >= cut) ? "pool" : "";')
chk('list 文案改在线曲库', '首在线曲库的歌（不属于本地')
# ---- PlayerBridge：在线搜歌三件套 ----
chk('nasBase 基址推导', 'static String nasBase()')
chk('onlineSearchJson 在线搜', 'public static JSONObject onlineSearchJson(String q, int lim)')
chk('playOnlineItem 六参版', 'playOnlineItem(String title, String artist, String source, String rid,')
chk('album/duration 透传', 'it.put("album", s.optString("album", ""))')
chk('tracks String[8] 带 via', 'album == null ? "" : album, duration == null ? "" : duration, "", "online"')
chk('notePlayingFull 上报', 'private static void notePlayingFull(String title, String album, String duration, String via)')
chk('curAlbum 字段', 'private static volatile String curAlbum')
chk('curVia online 角标', 'private static volatile String curVia')
chk('池内命中按曲目上报', 'notePlayingTrack(hit)')
chk('loadIndex 读 album', 't.optString("album", "")')
chk('status 输出 curAlbum', 'o.put("curAlbum", curAlbum)')
chk('queueJson 带 album', 'it.put("album", e.length > 4 ? e[4] : "")')
chk('语音兜底 playOnlineSong', 'public static String playOnlineSong(String q)')
# ---- WebConsole：三个新 op + online=1 ----
chk('op=play_song', '"play_song".equals(op)', w)
chk('op=play_online', '"play_online".equals(op)', w)
chk('op=search_online', '"search_online".equals(op)', w)
chk('/api/search online=1', '"1".equals(q.get("online"))', w)
# ---- v1.3.176：online=1 无条件在线搜（不再要求池内 0 命中） ----
if 'sj.optInt("total", 0) == 0' in w:
    print('  MISSING  不再要求池内 0 命中（还在判 total==0）')
    ok = False
else:
    print('  OK       不再要求池内 0 命中')
chk('在线条数可配', 'q.get("olimit")', w)
chk('搜不到也给空壳', 'on.put("total", 0)', w)
chk('在线超时 15s', '&source=all", 15000)', s)
chk('在线列表自己截断', 'out.length() >= lim', s)
# ---- v1.3.177：语音点歌「在线优先于最接近的」 ----
chk('本地命中置信判定', 'private static boolean localHitSolid(String k, int idx)', s)
chk('不准就改用在线', 'playOnlineSong(q, 4000, 8000)', s)
chk('语音短预算版', 'public static String playOnlineSong(String q, int searchMs, int urlMs)', s)
chk('取链超时可传', 'String album, String duration, int urlMs)', s)
# ---- XiaoZhiWs：MCP 描述纠偏 ----
chk('play_song 描述：点名直接调', '用户**点名要听某首歌**时，直接调这个', x)
chk('search 描述：只覆盖池子', '它**只覆盖本地曲库池**', x)
# ---- ui.html：在线搜歌 UI ----
chk('在线搜歌按钮', 'doSearchOnline()', h)
chk('fillNpMeta 实际信息', 'function fillNpMeta(m)', h)
chk('npMeta 元素', 'id="npMeta"', h)
chk('SRES_ON 在线结果', 'var SRES_ON', h)
chk('playOnlineRes 播在线', 'function playOnlineRes(j)', h)
# ---- v1.3.178：在线随机池 / 歌手连播含在线 / 播放信息不再残留 ----
chk('在线随机池拉取', 'private static List<String[]> fetchOnlinePool(int n, boolean fresh)', s)
chk('fresh=1 换一批', '/index.json?n=" + ask + (fresh ? "&fresh=1" : "")', s)
chk('randPool 缓存', 'private static final List<String[]> randPool', s)
chk('randPoolJson 给 web', 'public static JSONObject randPoolJson(int n)', s)
chk('op=random_online', '"random_online".equals(op)', s)
chk('op=play_rand_at', '"play_rand_at".equals(op)', s)
chk('点一首连整批进队列', 'pushSongs("play_rand_at", hitFirstIdx(hit, keep.size()))', s)
chk('在线并行取链', 'private static List<String[]> resolveOnlineUrls(', s)
chk('取链并发 4（12 会被限流）', 'URL_CONC = 4', s)
chk('取链错峰启动', 'URL_STAGGER = 250L', s)
chk('取链整体预算', 'long left = budgetMs - (System.currentTimeMillis() - t0)', s)
chk('歌手在线预算 4s+5s', 'onlineTracksFor(q, onlineMax(), 4000, 5000)', s)
chk('歌手在线补充', 'private static List<String[]> onlineTracksFor(String q, int max, int searchMs, long budgetMs)', s)
chk('play_artist 混在线', 'pick.addAll(oi)', s)
chk('play_artist 在线兜底', 'play_artist->online', s)
chk('在线 append 上限', 'ONLINE_APPEND_CAP', s)
chk('via 随曲目走', 'private static void notePlayingTrack(int idx)', s)
chk('推播按曲目上报', 'notePlayingTrack(idxs.get(at).intValue())', s)
chk('总线补实际信息', 'private static void notePlayingBus(', s)
chk('总线反查库内下标', 'ti = lastIdx.get(k).intValue()', s)
chk('总线不再抹掉信息', 'notePlayingBus(title, singer, ti)', s)
chk('曲名一换就清残留', 'curAlbum = "";\n            curDuration = "";\n            curVia = "";', s)
chk('随机批 via=rand', 'jstr(t, "pic"), "rand"', s)
chk('ui 区分在线随机/搜到', 'm.curVia === "rand"', h)
chk('/api/search random=1', '"1".equals(q.get("random"))', w)
chk('op=random_online 路由', '"random_online".equals(op)', w)
chk('op=play_rand_at 路由', '"play_rand_at".equals(op)', w)
chk('MCP 新增 random_online', 'tool("random_online"', x)
chk('play_artist 描述含在线', '本地曲库 + 在线曲库一起放', x)
chk('random 描述指向 random_online', '请用 random_online', x)
chk('ui 留空走随机', 'rnd ? "&random=1', h)
chk('ui playRandAt', 'function playRandAt(j)', h)
# v1.3.179：在线曲目改走惰性地址（推列表不再逐条取链）
chk('歌手在线条数走上限', 'onlineTracksFor(q, onlineMax(), 4000, 5000)', s)
chk('惰性探针', 'private static boolean lazyOk(String b)', s)
chk('惰性构造曲目', 'private static List<String[]> lazyTracks(', s)
chk('优先惰性', 'if (lazyOk(b)) {\n                return lazyTracks(b, cand);', s)
chk('老 NAS 回落取链', '// 老 mini-lx（没有 /r/ 路由）照原路逐条取链，功能不倒退。', s)
chk('单曲取链超时后改推惰性', 'url.length() == 0 && lazyOk(b)', s)
chk('单曲惰性话术', '（在线，起播时缓冲一下）', s)
chk('单曲预算 8s', 'return playOnlineSong(q, 8000, 8000)', s)
chk('不支持只信 10 分钟', 'now - lazyCheckedAt < 600000', s)
# v1.3.179：mini-lx 与 r1-music 合并成一个容器（R1XiaoZhi_MediaServer）
chk('默认曲库指向 8100', 'http://192.168.2.189:8100/index.json', s)
chk('在线池走 /online.json', 'b + "/online.json?n=" + ask', s)
chk('老 NAS 回落 index.json', 'b + "/index.json?n=" + ask + (fresh ? "&fresh=1" : "")', s)
chk('404 不当有效响应', 'if (t0.has("tracks")) {', s)
chk('★探针自己吞 404', '            try {\n                String s = httpGet(b + "/online.json?n=" + ask + (fresh ? "&fresh=1" : ""),', s)
chk('在线库关闭视为拉不到', 'o.optBoolean("off", false)', s)
chk('本地段单独留档', 'private static final List<String[]> localTracks', s)
chk('loadIndex 记本地段', 'nl.add(nt.get(nt.size() - 1))', s)
chk('onlineFrom 分界', 'o.has("onlineFrom") ? o.optInt("onlineFrom", 0) : -1', s)
chk('老源按 source 猜', '"mini-lx".equals(o.optString("source", "")) ? 0 : ta.length()', s)
chk('随机在线留本地段', 'tracks.addAll(keep);\n                        tracks.addAll(pool);', s)
chk('随机在线只推在线段', 'shuffledIdx(keep.size())', s)
chk('点一首只推在线段', 'hitFirstIdx(hit, keep.size())', s)
# v1.3.179（防封禁）：只有「旧 NAS 没有惰性地址」时才真的逐条取链，那时必须收着
chk('旧路径最多取 8 条链', 'int cap = Math.min(cand.size(), LEGACY_URL_MAX);', s)
chk('旧路径并发 2', 'cand.subList(0, cap), LEGACY_URL_CONC, 9000, budgetMs', s)
# v1.3.181：曲目数量上限统一（设置页两栏：本地 / 在线）
#   在线那一栏管住**所有**从 NAS 拉在线歌的途径：按歌手连播补的在线曲目（原 20）、
#   「随机播放在线音乐」（原 100）、页面「在线搜歌」（原 20）—— 以前三个写死的数。
#   在线只是拉列表（起播才取链），多拿不花时间 ⇒ 默认给到 NAS 上限 300。
chk('onlineMax 读取', 'public static int onlineMax()', s)
chk('onlineMax 默认 300', 'DEFAULT_ONLINE_MAX = 300', s)
chk('在线上限写盘', 'public static String setOnlineMax(int v)', s)
chk('本地上限写盘', 'public static String setPlaylistMax(int v)', s)
chk('playlistMax 可读', 'public static int playlistMax()', s)
chk('写文件工具 writeLineFile', 'public static boolean writeLineFile(String name, String val)', b)
chk('随机在线默认走上限', 'cnt = onlineMax();', s)
chk('随机批默认走上限', 'int rn = PlayerBridge.onlineMax();', w)
chk('在线搜歌默认走上限', 'int n = PlayerBridge.onlineMax();', w)
chk('set 支持 onlineMax', 'q.get("onlineMax")', w)
chk('set 支持 playlistMax', 'q.get("playlistMax")', w)
chk('status 报 limits', 'lm.put("onlineMax", PlayerBridge.onlineMax())', w)
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

python - <<'PCHECK'
d = open('v22_merged.dex', 'rb').read()
for t in [b'playOnlineSong', b'playOnlineItem', b'onlineSearchJson', b'nasBase',
          b'\xe5\x9c\xa8\xe7\xba\xbf\xe6\x90\x9c\xe5\x88\xb0\xe7\x9a\x84']:
    n = d.count(t)
    print('  %-24s in dex: %d' % (t.decode('utf-8','replace'), n))
    assert n >= 1, t
print('  dex symbols PRESENT: OK')
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.12/6] 自检：闹钟（v1.3.245）=="
python - <<'PCHECK'
import io
def rd(p):
    return io.open(p, encoding='utf-8', errors='replace').read()
al   = rd('v2src/cn/workbuddy/r1client/Alarm.java')
xz   = rd('v2src/cn/workbuddy/r1client/XiaoZhiWs.java')
wc   = rd('v2src/cn/workbuddy/r1client/WebConsole.java')
boot = rd('v2src/cn/workbuddy/r1client/XzBootstrap.java')
ok = True
def chk(where, src, name, kw):
    global ok
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
def chkNo(where, src, name, kw):
    global ok
    if kw in src:
        print('  STILL    ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
    else:
        print('  OK(去掉) ' + name)
# ---- Alarm：数据 / 调度 / 三类时间 ----
chk('Alarm', al, '主本文件名',    'private static final String FILE = "r1_alarms.json";')
chk('Alarm', al, '5 秒调度',      'private static final long TICK_MS = 5000L;')
chk('Alarm', al, 'ring 有效期',   'RING_HOLD_MS')
chk('Alarm', al, '线程名',        'new Thread(new Runnable()')
chk('Alarm', al, '每周算法',      'private static int isoDow(Calendar c)')
chk('Alarm', al, '一次性绝对时刻', 'private static long onceAt(JSONObject args, int h, int m)')
# ---- v1.3.251：全局默认铃声（设一次记住，之后 web / 语音建的闹钟都默认用它）----
chk('Alarm', al, '默认铃声字段',     'private static JSONObject sDflt = null;')
chk('Alarm', al, '默认铃声存盘',     'o.put("dflt", sDflt);')
chk('Alarm', al, '默认铃声读回',     'o.optJSONObject("dflt")')
chk('Alarm', al, '设铃声先落默认',   'setDflt(random ? "" : k, random ? "" : t, random ? "" : ar);')
chk('Alarm', al, '无闹钟也能设铃声', '默认铃声已设为')
chk('Alarm', al, '新建闹钟套默认',   'JSONObject d = dfltSong();')
chk('Alarm', al, 'json 带 dfltSong', 'o.put("dfltSong"')
chk('Alarm', al, 'dflt op 已登记',   'if ("dflt".equals(op)) {')
chkNo('Alarm', al, '旧的「没有闹钟就报错」已去掉', '没有开着的闹钟 —— 先加一个闹钟，再给它设铃声')
chk('Alarm', al, '周几解析',      'static JSONArray parseDaysText(String s)')
chk('Alarm', al, '连续段压缩',    'private static String compactDays(JSONArray ds)')
chk('Alarm', al, '统一入口',      'public static String mcp(String op, JSONObject args)')
# ---- Alarm：响铃播歌 ----
chk('Alarm', al, '响铃播歌线程',   '"r1-alarm-play"')
chk('Alarm', al, '指定歌走收藏源', 'PlayerBridge.dispatch("play_fav_one", g)')
chk('Alarm', al, '随机收藏兜底',   'alarm play(rand-fav)')
chk('Alarm', al, '最后退回曲库',   'PlayerBridge.dispatch("random", g)')
# ---- Alarm：关闭 / 停用语义 ----
chk('Alarm', al, '关闭只停这次',   'return "OK 已关闭闹钟"')
chk('Alarm', al, '关闭不动开关',   '这个闹钟本身还开着，明天照样响')
chk('Alarm', al, '关闭一次性改口径', '这是一次性的，响过就结束了')
chk('Alarm', al, '停用不报下次',     'it.put("nextText", on ? (nx > 0 ? whenText(nx, now) : "—") : "已停用");')
chk('Alarm', al, '语音序号同序',     'JSONArray a = sortedForShow();')
chk('Alarm', al, '按 id 找一条',     'private static JSONObject byId(String id)')
chk('Alarm', al, '停用走 toggle',  'OK 已" + (on ? "启用" : "停用")')
# ---- 装配 ----
chk('XzBootstrap', boot, '闹钟装配',   'Alarm.init(app);')
_vm = __import__('re').search(r'VERSION = "([^"]+)"', boot)
_ver = _vm.group(1) if _vm else '?'
chk('XzBootstrap', boot, '版本 ' + _ver, 'VERSION = "' + _ver + '"')
# ---- v1.3.258：手动服务器 Token（files/server_token）----
chk('XzBootstrap', boot, 'serverToken() 读取器', 'public static String serverToken()')
chk('XzBootstrap', boot, 'onToken 手动优先', 'if (serverToken().length() > 0)')
chk('XzBootstrap', boot, 'applyServerFlags 设手动 token', 'String stk = serverToken();')
# ---- WebConsole：web 唯一入口 ----
chk('WebConsole', wc, '/api/alarm 路由', 'if (path.equals("/api/alarm")) {')
chk('WebConsole', wc, 'alarm_* 转发',   'if (op.startsWith("alarm_")) {')
chk('WebConsole', wc, '落到 Alarm.mcp', 'return Alarm.mcp(op.substring(6), a);')
chk('WebConsole', wc, 'server_token 落盘', 'writeOrDelete("server_token", stk);')
chk('WebConsole', wc, 'status 透出 serverToken', 'sv.put("serverToken"')
# ---- XiaoZhiWs：语音入口 + 三件事的描述边界 ----
chk('XiaoZhiWs', xz, '工具前缀',      'private static final String ALARM_PREFIX = "self.r1alarm.";')
chk('XiaoZhiWs', xz, '注册工具表',    'addAlarmTools(tools);')
chk('XiaoZhiWs', xz, '分发到 Alarm',  'Alarm.mcp(name.substring(ALARM_PREFIX.length()), args)')
chk('XiaoZhiWs', xz, 'add 描述',      '添加一个**闹钟**')
chk('XiaoZhiWs', xz, 'list 描述',     '查询**现在有哪些闹钟、都开着没有**')
chk('XiaoZhiWs', xz, 'toggle 描述',   '**停用 / 启用**某个闹钟')
chk('XiaoZhiWs', xz, 'del 描述',      '**彻底删除**一个闹钟')
chk('XiaoZhiWs', xz, 'dismiss 描述',  '**关闭正在响的闹钟**')
chk('XiaoZhiWs', xz, 'dismiss 不动开关', '**闹钟本身照旧开着**')
chk('XiaoZhiWs', xz, '正响时别用 toggle', '必须用 dismiss')
pb   = rd('v2src/cn/workbuddy/r1client/PlayerBridge.java')
# ---- v1.3.259：playlist_max 0=不限 + 真实生效 WS/token；v1.3.260：语音点歌已在队列则跳播 ----
chk('XzBootstrap', boot, 'wsEffective 覆盖优先', 'String o = wsOverride();')
chk('XzBootstrap', boot, 'tokenEffective 手动优先', 'public static String tokenEffective()')
chk('XzBootstrap', boot, 'ws.getToken 透出', 'return (ws == null) ? "" : ws.getToken();')
chk('WebConsole', wc, 'playlistMax 0 也落盘', 'if (pv >= 0) {')
chk('PlayerBridge', pb, 'setPlaylistMax 0=不限', 'XzBootstrap.writeLineFile("playlist_max", "0")')
chk('PlayerBridge', pb, 'maxPlaylist 0=全库', 'if (v <= 0) {')
chk('PlayerBridge', pb, '语音点歌已在队列则跳播', 'play_song 已在队列中')
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi


echo "== [5.13/6] 自检：闹钟铃声缓存与音量（v1.3.250）=="
python - <<'PCHECK2'
import io
def rd(p):
    return io.open(p, encoding='utf-8', errors='replace').read()
al   = rd('v2src/cn/workbuddy/r1client/Alarm.java')
vol  = rd('v2src/cn/workbuddy/r1client/AlarmVol.java')
ac   = rd('v2src/cn/workbuddy/r1client/AlarmCache.java')
pb   = rd('v2src/cn/workbuddy/r1client/PlayerBridge.java')
xz   = rd('v2src/cn/workbuddy/r1client/XiaoZhiWs.java')
boot = rd('v2src/cn/workbuddy/r1client/XzBootstrap.java')
ok = True
def chk(where, src, name, kw):
    global ok
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
def chkNo(where, src, name, kw):
    global ok
    if kw in src:
        print('  STILL    ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
    else:
        print('  OK(去掉) ' + name)


# ---- AlarmVol：系统闹钟音量通道 + 保底抬量 + 还原 ----
chk('AlarmVol', vol, '独立类',          'final class AlarmVol')
chk('AlarmVol', vol, '闹钟流常量',      'STREAM_ALARM = AudioManager.STREAM_ALARM')
chk('AlarmVol', vol, '媒体流常量',      'STREAM_MUSIC = AudioManager.STREAM_MUSIC')
chk('AlarmVol', vol, '读百分比',        'static int get(int stream)')
chk('AlarmVol', vol, '保底抬量(媒体流)', 'static synchronized String guard(int pct)')
chk('AlarmVol', vol, '关闭还原',        'static synchronized String restore()')
chk('AlarmVol', vol, '只还原动过的',    'if (!sMusicTouched || sMusicOrig < 0)')
chk('AlarmVol', vol, '状态文本',        'static String desc()')
chk('AlarmVol', vol, '欠还原查询',      'static synchronized boolean isTouched()')

# ---- Alarm：三段式响铃 ----
chk('Alarm', al, '响铃先抬音量',   'AlarmVol.guard(Math.max(sVol, VOL_MUSIC_MIN));')
chk('Alarm', al, '缓存入口',       'if ("cache".equals(op)) {')
chk('Alarm', al, '关闭还原音量',   'String volBack = closeRing();')
chk('Alarm', al, '收尾文案',       'alarm maintain: 响铃结束，还原音量')
chk('Alarm', al, '歌播完续播',     '歌播完了但闹钟还开着，续播一首')
# ---- v1.3.247：收尾必须无条件还原音量 ----
chk('Alarm', al, '收尾无条件还原',   'AlarmVol.restore();\n            RingPlayer.stop();')
chk('Alarm', al, '试响窗口常量',     'private static final long TEST_HOLD_MS = 5 * 60 * 1000L;')
chk('Alarm', al, '试响开窗口',       'sHoldUntil = sNagAt + TEST_HOLD_MS;')
chk('Alarm', al, '窗口内不动',       'if (now <= sHoldUntil) {')
chk('Alarm', al, '关闭关窗口',       'sHoldUntil = 0L;')
chkNo('Alarm', al, '不再说「响铃超时」收尾', 'alarm maintain: 响铃超时，收提示音并还原音量')
chkNo('Alarm', al, 'vol 不再说抬闹钟流', '响铃时按它抬系统闹钟流')

# ---- Alarm：三池随机（含在线）----
chk('Alarm', al, '三池随机',       'private static boolean randomAnyPool()')
chk('Alarm', al, '三池洗牌',       'Collections.shuffle(order);')
chk('Alarm', al, '在线随机池',     'PlayerBridge.dispatch("random_online", g)')
chk('Alarm', al, '在线只抽 3 首',  'g.put("count", 3);')
chk('Alarm', al, '收藏随机池',     'private static String playRandomFav()')
chk('Alarm', al, '本地随机池',     'private static String playRandomLocal()')
chkNo('Alarm', al, '不再固定「收藏→本地」顺序', '先随机收藏里的一首，收藏为空再退回本地曲库')

# ---- Alarm：音量设置 ----
chk('Alarm', al, '默认 50（用户嫌 80 太高）', 'static final int VOL_DEFAULT = 50;')
chk('Alarm', al, '媒体保底 30',    'private static final int VOL_MUSIC_MIN = 30;')
chk('Alarm', al, '音量落盘',       'o.put("vol", sVol)')
chk('Alarm', al, '音量读盘',       'int v = o.optInt("vol", -1);')
chk('Alarm', al, '音量下限 20',    'if (v < 20) {')
chk('Alarm', al, 'vol op',         'if ("vol".equals(op)) {')
chk('Alarm', al, 'json 带音量',    'o.put("alarmVol", AlarmVol.get(AlarmVol.STREAM_ALARM));')
chk('Alarm', al, 'json 带缓存数',  'o.put("cache", AlarmCache.count());')
chk('Alarm', al, 'json 带缓存目录', 'o.put("cacheDir", AlarmCache.dir() == null')
chk('Alarm', al, 'json 带缓存key', 'o.put("cacheKeys", ck);')
chk('Alarm', al, '条目标已缓存',   'it.put("cached", sk.length() > 0 && AlarmCache.has(sk));')
chk('Alarm', al, '收尾看欠不欠',   'boolean had = AlarmVol.isTouched();')

# ---- v1.3.248：铃声缓存（用户：主动设的缓存，随机不缓存）----
chk('AlarmCache', ac, '独立类',     'final class AlarmCache')
chk('AlarmCache', ac, '目录名',     'static final String DIR_NAME = "R1Alarm"')
chk('AlarmCache', ac, '账本文件',   'private static final String INDEX = "index.json"')
chk('AlarmCache', ac, '单曲上限',   'private static final long MAX_BYTES')
chk('AlarmCache', ac, '外部存储优先', 'Environment.getExternalStorageDirectory()')
chk('AlarmCache', ac, '/sdcard 兜底', 'new File("/sdcard/" + DIR_NAME)')
chk('AlarmCache', ac, '按键查文件', 'static synchronized String fileOf(String k)')
chk('AlarmCache', ac, '每次都验存在', 'if (!f.isFile() || f.length() <= 0) {')
chk('AlarmCache', ac, '只剩账就销账', 'items.remove(k);')
chk('AlarmCache', ac, '先写 .part',  'File tmp = new File(d, name + ".part");')
chk('AlarmCache', ac, '落盘改名',   'if (!tmp.renameTo(out)) {')
chk('AlarmCache', ac, '后台下载',   'static void saveAsync(final String k, final String title')
chk('AlarmCache', ac, '清单',       'static synchronized JSONArray list()')
chk('AlarmCache', ac, '可读文件名', 'String name = base + "." + ext;')
chkNo('AlarmCache', ac, '不用哈希当文件名', 'name = shortHash(k) + "." + ext;')

# ---- Alarm：缓存优先 / 重放 / 两处触发缓存 ----
chk('Alarm', al, '本地缓存优先', 'alarm play(ring-ch) ')
chk('Alarm', al, '兜底还认 play_local（独立通道起不来时）', 'PlayerBridge.dispatch("play_local", g)')
chk('Alarm', al, '缓存兜底',     'playAnyCached("ring-fallback")')
chk('Alarm', al, '歌播完重放',   'alarm maintain: 独立通道断了，重新起播')
chk('Alarm', al, '随机补一首',   'private static String cacheOneRandom()')
chk('Alarm', al, '目录空才补',   'if (AlarmCache.count() > 0) {')
chk('Alarm', al, '指定即缓存',   'cacheSongAsync(k, t, ar);')
chk('Alarm', al, '已缓存不重下', 'if (AlarmCache.has(k)) {')
chk('Alarm', al, '解析走 favResolve', 'String u = PlayerBridge.favResolve(g);')
chk('Alarm', al, '开机补缓存',   'warmCacheLater();')
chk('Alarm', al, '缓存初始化',   'AlarmCache.init(app);')
chkNo('Alarm', al, '不再有提示音', 'AlarmTone')

# ---- PlayerBridge：play_local（用户：mediaplayer 也得支持播本地歌）----
chk('PlayerBridge', pb, 'play_local op', 'if ("play_local".equals(op)) {')
chk('PlayerBridge', pb, '容忍 file://', 'if (f.startsWith("file://")) {')
chk('PlayerBridge', pb, '推 file url',  'String url = "file://" + file.getAbsolutePath();')
chk('PlayerBridge', pb, '本地不走探活', 'private static String playLocal(JSONObject args)')
chk('PlayerBridge', pb, 'v1.3.336 书级共享 srvResume', 'private static JSONObject srvResume(String book)')
chk('PlayerBridge', pb, 'v1.3.336 abResume 先问服务端', 'abResume srv shared ep=')
chk('PlayerBridge', pb, 'v1.3.339 续播统一 10s', 'ps = lp2 - 10;')
chk('PlayerBridge', pb, 'v1.3.339 prog 服务端同步', 'public static JSONArray srvSyncProg(JSONArray lst)')
chk('PlayerBridge', pb, 'v1.3.340 暂停/停止结算', 'ProgProgress.srvFinal(op)')
chk('PlayerBridge', pb, 'v1.3.341 history 20s 缓存', 'sHistCache != null && now - sHistCacheTs < 20000L')
_wc341 = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
chk('WebConsole', _wc341, 'v1.3.341 手动暂停通知 op', '"usr_pause".equals(op)')
chk('WebConsole', _wc341, 'v1.3.341 手动起播通知 op', '"usr_play".equals(op)')
_kt341 = open('v2src/cn/workbuddy/r1client/TopKey.java', encoding='utf-8', errors='replace').read()
chk('TopKey', _kt341, 'v1.3.341 实体键暂停记账', 'MusicResume.noteUserPause()')
chk('TopKey', _kt341, 'v1.3.341 实体键恢复清账', 'MusicResume.noteUserPlay()')
chk('AlarmCache', ac, '清空按 key 收', 'String k = keys.get(i);')
chkNo('AlarmCache', ac, '不按文件名删账', 'items.remove(names.get(i));')
chk('AlarmCache', ac, '读索引剪幽灵', 'if (nm.length() == 0 || !new File(d, nm).isFile()) {')
chk('AlarmCache', ac, '幽灵账有日志', '条失效账（文件已不在）')
chk('Alarm', al, 'json 带 cacheList', 'o.put("cacheList", AlarmCache.list());')
chk('Alarm', al, 'json 带 ringSong', 'o.put("ringSong", ringSongText());')
chk('Alarm', al, 'nextUp 取最小', 'private static JSONObject nextUp(JSONArray a, long now)')
chk('Alarm', al, 'setSong 读 k', 'String k = str(args, "k");')

# ---- v1.3.252：闹钟铃声走独立播放通道（不占用主播放器）----
rp = open('v2src/cn/workbuddy/r1client/RingPlayer.java', encoding='utf-8').read()
chk('RingPlayer', rp, '自建 MediaPlayer',   'm = new MediaPlayer();')
chk('RingPlayer', rp, '走媒体流',           'm.setAudioStreamType(AudioManager.STREAM_MUSIC);')
chk('RingPlayer', rp, '先设流再给源',       'm.setDataSource(path);')
chk('RingPlayer', rp, '循环播',             'm.setLooping(true);')
chk('RingPlayer', rp, '起播后自检',         'if (!m.isPlaying()) {')
chk('RingPlayer', rp, '停也串行化',         'private static void stopLocked()')
chk('Alarm', al, '缓存歌走独立通道',  'ok = playByRing(f, title);')
chk('Alarm', al, '随机先吃缓存',      'ok = playRandomCached();')
chk('Alarm', al, '兜底也走独立通道',  'if (playByRing(f, it.optString("title", ""))) {')
chk('Alarm', al, '关闭走统一收尾',    'private static String closeRing() {')
chk('Alarm', al, '收尾停通道',        'RingPlayer.stop();       // v1.3.252')
chk('Alarm', al, '试响到点停通道',    'RingPlayer.stop();   // v1.3.252')
chk('Alarm', al, '出声判据含通道',    'if (RingPlayer.isPlaying() || PlayerBridge.isMusicActive()) {')
chk('Alarm', al, '断了重起同一条路',  '独立通道断了，重新起播')
chk('Alarm', al, 'json 带 ringCh',    'o.put("ringCh", RingPlayer.isPlaying()')
chk('Alarm', al, 'ring op 已登记',    'if ("ring".equals(op)) {')
chk('Alarm', al, 'ring 自测可查状态', 'playing=" + RingPlayer.isPlaying()')
chk('Alarm', al, '放歌前才 pause',    'if (PlayerBridge.isMusicActive()) {')
chkNo('Alarm', al, '缓存首选不再借主播放器', 'alarm play(cached) ')

# ---- v1.3.253：音乐 ⇄ 响铃互斥 + 先设音量再出声 ----
av = rd('v2src/cn/workbuddy/r1client/AlarmVol.java')
chk('AlarmVol', av, 'guard 改成设成',   'if (mu == pct) {')
chk('AlarmVol', av, '报用户音乐音量',   'static synchronized int userMusic() {')
chkNo('AlarmVol', av, '不再「至少抬到」', 'if (mu < 0 || mu >= pct) {')
chk('Alarm', al, '响铃前先精确设音量',  'AlarmVol.guard(Math.max(sVol, VOL_MUSIC_MIN));')
chk('Alarm', al, 'fire 先暂停音乐',     'alarm fire: 音乐在放，先暂停音乐')
chk('Alarm', al, '看护线程已起',        '"r1-alarm-watch"')
chk('Alarm', al, '看护判据=主播放器',   'boolean playing = PlayerBridge.isSoftPlaying();')
chk('Alarm', al, '先武装再判',          'if (!sWatchArmed) {')
chk('Alarm', al, '主播放器放铃声不自关', 'if (sRingOnMain) {')
chk('Alarm', al, '兜底记主播放器',      'sRingOnMain = true;')
chk('Alarm', al, '收尾统一函数',        'private static String closeRing() {')
chk('Alarm', al, 'dismiss 支持 quiet',  'args.optBoolean("quiet", false)')
chk('Alarm', al, 'yield op 已登记',     'if ("yield".equals(op)) {')
chk('Alarm', al, 'yield 先还原再放行',  'private static String yieldOp() {')
chk('Alarm', al, 'json 报 ringVolOn',   'o.put("ringVolOn", AlarmVol.isTouched());')
chk('Alarm', al, 'json 报 musicVolUser', 'o.put("musicVolUser", AlarmVol.userMusic());')
chk('Alarm', al, '到点未响的宽限',      'private static final long GRACE_MS = 60000L;')
chk('Alarm', al, 'once 不再被吞',       'if (at > last && from - at <= GRACE_MS) {')
chk('Alarm', al, 'daily 不再被吞',      'if (t0 > last && from - t0 <= GRACE_MS) {')
chk('XzBootstrap', boot, 'status 带响铃态', 'o.put("ringVolOn", AlarmVol.isTouched());')

# ---- 装配 / 语音工具 ----
chk('Alarm', al, '音量初始化', 'AlarmVol.init(app);')
_vm = __import__('re').search(r'VERSION = "([^"]+)"', boot)
_ver = _vm.group(1) if _vm else '?'
chk('XzBootstrap', boot, '版本 ' + _ver, 'VERSION = "' + _ver + '"')
chk('XiaoZhiWs', xz, 'vol 工具',       'tools.put(tool(ALARM_PREFIX, "vol",')
chk('XiaoZhiWs', xz, '随机含在线口径', '收藏、在线曲库、本地随机')
chk('XiaoZhiWs', xz, 'dismiss 提提示音', '把临时抬高的音量还原')
raise SystemExit(0 if ok else 1)
PCHECK2
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [6/6] janus 构建与验签 =="
export APKSHELL JANUS_DIR
"$PY" - <<'PYEOF'
import sys, os, re
# 2026-11-07：janus_poc.py 与 Unisound.apk 的位置由 shell 侧解析后经环境变量传入，
#   两套布局（工作树 R1/ 与发布源码包 源码/）都能用，不再假设 R1/diag/pandora。
JANUS_DIR = os.environ['JANUS_DIR']
sys.path.insert(0, JANUS_DIR)
import janus_poc
dex = open('v22_merged.dex', 'rb').read()
so = open('libr1opus.so', 'rb').read()
print(f'  dex={len(dex)}B so={len(so)}B prefix={len(dex)+len(so)}B')
info = janus_poc.build_janus(dex + so, os.environ['APKSHELL'], 'XiaoZhi_Unisound_V2.0.6.apk')
print('janus:', info, 'size', os.path.getsize('XiaoZhi_Unisound_V2.0.6.apk'))
r = janus_poc.verify_v1('XiaoZhi_Unisound_V2.0.6.apk', 'XiaoZhi_Unisound_V2.0.6')
janus_poc.show(r)
assert r['verdict'] == 'PASS', 'v1 verify FAIL'
d = open('XiaoZhi_Unisound_V2.0.6.apk','rb').read()
zi = d.find(b'\x50\x4b\x03\x04')
elfs = [m.start() for m in re.finditer(b'\x7fELF', d[:zi])]
print(f'  prefix={zi}B, ELF in prefix: {len(elfs)} @{elfs[:3]}')
assert len(elfs) >= 1, 'NO SO IN PREFIX'
print('DONE: XiaoZhi_Unisound_V2.0.6.apk')
PYEOF

# ================= 版本留档（每次构建成功自动存一份，历史版本绝不覆盖） =================
# 起因：2026-11-06 一天内连升 315~321 全是覆盖写，源码只留最新一份，
#       要回退 314 时发现没有存档，只能翻 handover zip 才救回来。
# 规矩：① 升版本号必须先有存档；② APK 必须按版本号命名；③ 台账 docs/版本台账.md 必须补条目。
VER=$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' v2src/cn/workbuddy/r1client/XzBootstrap.java | head -1)
if [ -n "$VER" ] && [ -f XiaoZhi_Unisound_V2.0.6.apk ]; then
  APK="release/xiaozhi_Unisound_v${VER}.apk"
  REL="release/$VER"
  if [ -f "$APK" ]; then
    echo "== [留档] $APK 已存在，历史版本不覆盖（本次未归档）=="
  else
    mkdir -p release "$REL"
    cp -f XiaoZhi_Unisound_V2.0.6.apk "$APK"
    tar czf "$REL/src.tar.gz" v2src build_v205.sh 2>/dev/null || true
    {
      echo "version: $VER"
      echo "built:   $(date '+%Y-%m-%d %H:%M:%S')"
      echo "apk:     $APK ($(stat -c %s "$APK") bytes)"
      echo "sha256:  $(sha256sum "$APK" | cut -d' ' -f1)"
    } > "$REL/MANIFEST.txt"
    echo "== [留档] $APK + $REL/src.tar.gz =="
    echo "   ★ 记得补 docs/版本台账.md 的 $VER 条目（起因 / 改动 / 验证）"
  fi
fi

echo "== [7/6] 源码树版本号还原（回到测试序列 1.3.x） =="
"$PY" - <<'RSTEOF'
import io, os
p = 'v2src/cn/workbuddy/r1client/XzBootstrap.java'
io.open(p, 'w', encoding='utf-8', newline='\n').write(
    io.open('_VERSION_dev.bak', encoding='utf-8').read())
os.remove('_VERSION_dev.bak')
print('  源码树 VERSION 已还原为测试序列')
RSTEOF

echo "RELEASE DONE: XiaoZhi_Unisound_V2.0.6.apk"
echo "  本版新增：播放记录/进度上报整条链路（暂停结算、起播即上报、"
echo "  按设备分组隔离、书级共享进度）、听书身份唯一化（书身份=子书名）、"
echo "  合集子书语音点名播、语音读时长/快进后退/跳到第几秒、"
echo "  续播本地库残卷自动降级到缓存/在线"
