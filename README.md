# Phicomm_R1_XiaoZhi_Client —— 源码、原厂包与逆向资料（release V2.0.2 快照）

这份是 **release V2.0.2** 的完整可重建快照（构建源码 + 构建要用到的原厂底包 / 补丁 / 脚本）。  
装机用的三个 apk 不在这里（在外层 `apps/`）；本目录给的是「它是怎么来的、怎么自己再编一遍」。

**对应关系**：

| 包                                 | 产物名                              | 包内版本号                         |
| --------------------------------- | -------------------------------- | ----------------------------- |
| device（小智本体，寄生在原厂 Pandora）        | `XiaoZhi_Unisound_V2.0.2.apk`    | `XzBootstrap.VERSION = 2.0.2` |
| player（Web 控制台，寄生在原厂 EchoService） | `XiaoZhi_EchoService_V2.0.2.apk` | `Boot.VER = pl-2.0.2`         |

---

## ★ 相比 V2.0.1 更新了什么

V2.0.1 的基线是 device `v1.3.190`（jp2 v144）+ player `pl-1.6.18`（pv22）；  
V2.0.2 的基线是 device `v1.3.198`（jp2 v151）+ player `pl-1.6.34`（pv40）。  
下面按「现象 → 根因 → 修法」列，都是 2026-10-19 一天里真机取证后落的。

### 一、设备端 device（1.3.190 → 1.3.198）

| # | 现象                                   | 根因                                                                                                                                                                             | 修法                                                                                                                                                                                                          |
| - | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 | 电台/直播流起播后「播 6 秒、停 3~4 秒」             | device 起播后**每秒补一脚 play（共 5 条）**，每条都让 player 走 `stopping → startInternal` + `pcm_close card 0/2/3` 重开三张声卡 ⇒ audio queue 反复清零 ⇒ `NuPlayer: buffer low, pausing...` → 4 秒后 resume | `kickPlay()` 发完一条后轮询 `AudioManager.isMusicActive()`（≤1.2s），**已经在出声就立刻收手**（1.3.192）；蓄水常量 `NET_STREAM_KICK_MS` 5000 → **800**（1.3.195，Exo 下 kick 已幂等，5 秒是纯白等）                                                 |
| 2 | 网页「全部载入播放列表」回 `unknown op queue_all` | `PlayerBridge.dispatch("queue_all")` 早实现了，但 **WebConsole 的 `/api/act` 路由表没登记** ⇒ 一路掉到方法末尾 `return "unknown op " + op`（与 `play_index` 是同款坑）                                     | WebConsole 补分支（ids / limit 原样透传），并在 build 脚本里加**反向断言**：dispatch 有、WebConsole 没有就 FAIL                                                                                                                       |
| 3 | 在线搜索结果无法整批入队（只能一首首点）                 | 在线结果只有 `source/rid`，**没有 url**，走不了 `queue_all`                                                                                                                                 | 新 op `queue_online`：推**惰性地址** `/r/<source>/<rid>?title=..&artist=..` ⇒ 一次取链 HTTP 都不用跑，播到哪首 NAS 才取哪首；**第一首例外**，正式取一次链保证点下去马上出声                                                                               |
| 4 | 搜索框留空点搜索，每次都是同几首                     | `searchJson` 没洗牌，永远从头截断                                                                                                                                                        | 加三参重载（**先洗后截断**）；player 的 `/api/search` 反代**补透传 `shuffle`**（又是「8088 直连正常、8080 静悄悄退化」那类漏参数）                                                                                                                  |
| 5 | 65 首本地歌的专辑显示 `Billboard Hot 100`     | 这是**真实 ID3 tag**，不是解析错误，但显示出来毫无意义                                                                                                                                              | `sanitizeAlbum()` 过滤占位专辑名（billboard / hot 100 / top 100 / various / unknown album）                                                                                                                          |
| 6 | 排查「谁在抢 CPU」没有工具                      | —                                                                                                                                                                              | 新增 `CpuGovernor.java`（1.3.191）：读 `/proc/self/task/*/stat` 算线程 CPU 与 nice，`op=cool&v=state/probe/on/off&n=<nice>`；进程内 `Process.setThreadPriority` 对自己线程有效（adb `renice` 缺 CAP_SYS_NICE 不行）。**仅排障用，默认不动任何优先级** |                                                                                                                                                             | 新增 `GROUP_QR_B64` 常量 + `/group.png` 路由（image/jpeg，7 天缓存）                                                                                                                                                    |

### 二、控制台端 player（pl-1.6.18 → pl-1.6.34）

| #  | 现象                                    | 根因                                                                                                                                                      | 修法                                                                                                                                                                                                                                                                                                                  |
| -- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1  | 在线流/电台起播慢、被补踢打断、FLAC 播不了              | 原厂 `MultiMediaPlayer`（两个 MediaPlayer + 回调状态机）对重复 `start()` 不幂等：每次都 `stopParparingPlayer` + reset + 重开声卡                                                 | **搬小飞的 ExoPlayer 2.18 适配层**（`MultiExoPlayer` + `new_NetPlayer` + 删除旧 exomedia/exoplayer2）⇒ 重复 start 只翻 PlayWhenReady，缓冲不再清零；FLAC 走 Exo 2.18 纯 Java 解码。网页 `op=engine&v=exo\|mp` 可切回 **MediaPlayer**，开关落 **SP `r1_engine_mp`**（files flag 在 `:NetPlayer` 子进程恒 false，踩过）                                               |
| 2  | 进度条「一下快一下慢」                           | 锁相用**毫秒绝对值**：`p` 是服务端采样时刻、`Date.now()` 是响应到达时刻，差一个 HTTP 往返（80~400ms 抖动）                                                                                 | 改**增量锁相**：只比「真值秒增量」与「显示秒增量」的差；换曲 / seek 落地时把 `POS_BASE_SEC=-1` 一起作废                                                                                                                                                                                                                                                 |
| 3  | 电台先显示「本地曲库」、显示「缓冲中」、还去匹配歌词封面          | ① `isRadio()` 有 `play_time>2000` 门槛；② 直播流 `dur=0/pos=0` ⇒ `phase()` 判成 loading/stalled；③ 台名（ICY 流标题）被当「歌名+歌手」去搜词搜图                                      | ① 去掉门槛 + 粘性记账 `sRadio`（认出来就锁住，换曲/stop/切引擎才忘）；② 直播流 `phase()` 直返 `playing`、`stallMs=0`；③ 电台**不给封面、不给歌词**，列表**不高亮**（点一下闪一下 + toast）                                                                                                                                                                                   |
| 4  | 点暂停后声音停了，图标和进度条还在跑，十几秒才变              | 状态靠 8 秒轮询 + `togglePlay()` 没乐观更新；且 `phase()` 里「进度 4 秒内变过 ⇒ 当在播」的宽限让暂停后仍报 playing                                                                        | 服务端加 **`sUserPaused`** 记账（`pause()` 置位、`phase()` 直返 idle，不问引擎；所有起播入口 `noteResume()` 清位）；前端乐观更新 + 800ms 后校准。实测 **+0.2s** 就变暂停态                                                                                                                                                                                       |
| 5  | `op=pause` 回 `ERR 暂停`（声音其实停了）         | 两层：① `EasyPlayer.pause()` 只在 **2=BUSY** 才下派，空闲态直接 return false；② 引擎侧 `pauseInternal` 在 mPlayState 0/4 返 0                                               | `PCtl.pause()` 先判 `playerStatus(p)!=2` ⇒ 直接回 OK（没在播 ≡ 已暂停）；`patch_pause_idem.py` 把 `new_NetPlayer.pause()` 的 `:cond_2d` 改成 `return 1`。**`start()` 不动**（起播失败必须如实报错）                                                                                                                                                  |
| 6  | 想知道现在播的是什么码率/格式                       | 原厂接口里没有                                                                                                                                                 | 新增 `AudioInfo.java`：① 反射 Exo `Format`（**跨进程拿不到**，`:NetPlayer` 里的类主进程反射不到）；② URL 正则（蜻蜓 `.../64k.mp3`）；③ HTTP HEAD 的 `Content-Length ÷ 时长` + `Content-Type`（异步 + 按 URL 缓存）。②③ 实际生效，**与引擎无关**                                                                                                                          |
| 7  | 氛围灯「点了没反应」/ 多彩发白 / 不放歌也在闪             | ① **原厂氛围灯总开关 `music_light_enable` 关着** ⇒ 原厂每帧送黑，钩子老实跟着写黑；② 多彩配色照抄原厂低饱和基色 ⇒ 淡蓝彩度 15%、浅粉 11%，肉眼就是白；③ 曾加常驻自驱定时器                                            | ① `Amb.enableFactoryAmbient()`（写 SP=1 → 反射 `PlayerVisualizerHelper.obtain` → 反射 msgcenter `sendMessage(4,64,1,null)`，幂等），诊断口 `op=amb7&v=probe` 能看基色与总开关；② `vivid()` 三道闸：`MIN_S=0.90` 拉饱和、`MAX_V=0.88` 压亮度、RGB 彩度 `(max-min)/max < 55%` **按比例**复检；③ 删掉常驻泵 —— 黑帧默认 `return false` 交回原厂灭灯，**仅在播歌且连续 3 秒全黑**才兜底 ⇒ 不放歌不闪 |
| 8  | 多彩改成氛围灯的**子项**（原厂 / 多彩二选一）            | 原来是并列的第二个开关，语义重复                                                                                                                                        | 页面改成氛围灯下面一行段控件 `segAmbMode`；总开关关着时整段压暗；选「多彩」而总开关还关着 ⇒ 顺手发 `op=amb&v=on`                                                                                                                                                                                                                                             |
| 9  | DLNA / AirPlay 状态显示「关」但其实是开的；推流停了不再补开 | ① `Svc.portOpen()` 只查 IPv4 表，而本机内核把 IPv4 socket 也写进 `tcp6`；② 补开只挂在 `PCtl.ensureBusy` 的 `if (remote)` 分支内，推流结束后那条路不走                                     | ① `dlnaUp()` 补查 `udp6`、`airUp()` 补查 `tcp6`；② 新钩子 `EasyPlayer.shutdownAllExternalConnectPlayer() → Boot.onExternalShutdown()` + `Svc.watchReopen()`（**5 秒窗口 / 500ms 采样**），配手动意图账本 `files/r1_svc_manual.txt`                                                                                                          |
| 10 | 蓝牙三处隐患（代码审查发现，非用户报障）                  | ① `reged` 守卫把 `getProfileProxy` 永久挡在门外；② connect/disconnect 只读静态 `sink`，页面没刷过一轮就是 null ⇒ 「A2DP 服务还没就绪」假阴性；③ found 栏用**扫描那一刻**的 bond 快照过滤 ⇒ 当场配对的设备出现两张卡 | ① 抽 `ensureProxy(Context)`，**不受 reged 守卫**；② 统一走 `ensureSink()`（自己的代理 > 借原厂 `mA2dpSink`，**10s TTL** 重借）；③ 改用「此刻 bonded 地址集合」过滤                                                                                                                                                                                      |
| 11 | 曲库想整批进播放列表；页面「按歌手连播」卡片多余              | —                                                                                                                                                       | 加「全部载入播放列表」（`op=queue_all`，受列表上限约束）；删掉页面「按歌手连播」卡片（语音 / MCP 的 `play_artist` 保留）                                                                                                                                                                                                                                      |                                                                                                                                                | 页脚**明文**「项目地址：<https://github.com/shj4588/Phicomm_R1_XiaoZhi_Client」；「赞赏」「讨论群」\*\*两个独立弹窗\*\*，点\*\*图片外任意处\*\*关闭（只有图片本身吃掉点击）>                                                                                                                                                                                         |

### 三、NAS 服务端（随包附带的 R1XiaoZhi_MediaServer）

| # | 现象                       | 根因                                                                                                                               | 修法                                                                                                                                                                                                                                                                                                                          |
| - | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 | 本地中文歌名/歌手变乱码             | 老 MP3 的 ID3 正文是 **GBK**，但 frame 的 encoding 字节写 0（=ISO-8859-1）；2026-10-17 两容器合并、本地曲库用 Node 重写（`local.js`）时**漏移植**了原来 Python 版那套修复 | `local.js` 新增 `toLatin1Bytes/gbkPairs/dbcsDec/fixMojibake/looksBroken`（`Buffer.from(s,'latin1')` 可无损反推字节，因为 `music-metadata` 的 `decodeLatin1` 是严格 1:1）；★ 比旧版多两条**误伤防线**：GBK 尾字节只认 `>=0x80`（挡 `Mötley Crüe`→`M鰐ley Cr黣`）、只有一对时必须落在 GB2312 汉字区（挡 `öü`→`鳇`）；memo 加 `v`（`CACHE_V=2`）⇒ 已存的 `files/local_cache.json` **就地修**，不用重扫 |
| 2 | 电台多一跳代理 / 服务器 24 小时常热烧带宽 | —                                                                                                                                | `RADIO_PROXY` 默认 **0（直连源站）**、`RADIO_WARM` 默认 **0（不预热不常热）**；`/api/radios` 恒吐**原始 URL**（吐代理地址会被电台编辑页存回去形成套娃）。服务端不做任何「替设备缓冲」的设计                                                                                                                                                                                                |

### 四、工程 / 构建

- 新增 release 脚本 **`04-build-scripts/{device,player}/build_rel_V202.sh`**（分别由 `_gen_rel_v202.py` 从 `build_v151.sh` / `build_pv40.sh` 派生）：javac 前注入 `2.0.2` / `pl-2.0.2`，产物落盘后立刻还原源码树。
- 新增补丁脚本：player `patch_exo_engine.py` / `patch_seek_exo.py` / `patch_pause_idem.py` / `patch_amb.py` / `patch_dbg_swallow.py`；device `patch_manifest_hascode.py`。
- ★★ 两个新踩的坑（写在脚本注释里了）：
  1. **javac「常量字符串过长」** —— base64 内嵌资源超过 65535 字节就挂（`"..." + "..."` 是**常量表达式**，javac 会折成一个字符串常量）。群二维码 144k 字符 ⇒ 必须写成  
     `new StringBuilder().append(片≤30000)...toString()`（非常量表达式，javac 不折）。
  2. **跑 `bash build_pvNN.sh` 别全局导出 `MSYS2_ARG_CONV_EXCL="*"`** —— Janus 那步的 Windows python 会收到 `/c/Users/...` 路径直接 `FileNotFoundError`（脚本自己只在 java/smali 那几行局部设）。

### 五、已知遗留（V2.0.2 未解，不影响日常使用）

| 项              | 说明                                                                                                                                             |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 电台起播停顿         | 换 Exo 引擎后实测 **1.5s 出声、零饥饿**；若用网页切回 MediaPlayer 引擎，仍会有 3~4 秒（NuPlayer 对无 Content-Length 直播流的起播水位只有 ~2 秒，64kbps 稳态供给 8.2 ≈ 消耗 8.0，零余量）。默认引擎是 Exo |
| 引擎开关持久化竞态      | `op=engine&v=exo` 当时生效，但 player 主进程重启后 SP 可能回到 mp。**只在手动切引擎时才会遇到**                                                                             |
| 在线歌码率/格式       | 落雪只认档位（128/192/320k），实际用哪档是运行时决定的；现在显示的是 HEAD 测的**真实字节率**，比档位声明可信。要接真实档位得改三层（NAS→device→player），暂不做                                            |
| 文件名「歌手 - 歌名」   | 既有 `splitTitle()` 约定是「歌名 - 歌手」，反着命名的文件会解析反（title=陈奕迅 / artist=浮夸）                                                                              |
| 手动关 DLNA 后的几分钟 | `stopServer()` 返回后 SSDP socket 有**延迟释放**，这段窗口里内核端口表仍显示占用。三路口径以 **SSDP M-SEARCH 主动探测**为准                                                        |



---

## 目录

| 目录                                          | 内容                                                                                            |
| ------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `01-parasite-src/device-v2src/`             | 主包（device）我们写的 Java 层：会话控制、音频采集/上行、TTS 播放、音乐让路、灯控、曲库与 MCP 桥接、控制台后端、`CpuGovernor` 排障           |
| `01-parasite-src/player-plsrc/`             | 控制台包（player）注入层：Web 控制台 :8080、播放控制、进度条、封面/歌词、码率格式、氛围灯、蓝牙 / DLNA / AirPlay                     |
| `02-reverse-smali/device-uni_smali/`        | 原厂 Pandora（`com.phicomm.speaker.device`）反编译后的 smali，**已含全部落地改动**                              |
| `02-reverse-smali/{device,player}-patches/` | 打在 smali 上的幂等补丁 `patch_*.py`，每个脚本头部写了「现象 → 根因 → 改在哪个类/方法」                                     |
| `03-stock-rom/`                             | 各版本原厂包（**未改动**），Janus 母本与对照用                                                                  |
| `04-build-scripts/device/`                  | 主包 release / 测试构建脚本 + `janus_poc.py` + R8 配置 + `libr1opus.so` + so 宿主构建脚本 + `ui/` + `javacp/` |
| `04-build-scripts/player/`                  | 控制台包 release / 测试构建脚本 + `ui/`（页面源码 + `gen_java.py`）+ 资源生成脚本                                   |
| `05-reverse-docs/`                          | 逆向报告、AEC 方案、诊断记录、真机取证、溯源取证等                                                                   |
| `R1XiaoZhi_MediaServer/`                    | NAS 音乐服务端（docker，8100）：本地曲库 / 在线搜索 / 歌词封面 / 电台。本地中文乱码修复就在这里的 `local.js`                       |

---

## 补丁脚本一览

device（打在 smali 上）：

| 脚本                          | 干了什么                                       |
| --------------------------- | ------------------------------------------ |
| `patch_wakeword.py`         | 原厂默认唤醒词「小讯小讯」→「小智小智」                       |
| `patch_enter_asr.py`        | 唤醒后直接 `enterASR`，跳过原厂提示音                   |
| `patch_light.py`            | 短路原厂呼吸灯（loading light），治「底部一直闪」            |
| `patch_boot_sound.py`       | 关掉原厂启动音乐 / 网络状态提示音                         |
| `patch_bindgate.py`         | 拆掉原厂「未绑定 / 无网络 / 待升级」三道唤醒闸门（云已死，闸门永真不了）    |
| `patch_light_hold.py`       | 掐掉 `onRecognizeStart()` 的灭灯，让唤醒拾音灯能定格在收音方向 |
| `patch_manifest_hascode.py` | 修 manifest 的 hasCode 标记（Janus 拼接侧需要）       |

player：

| 脚本                     | 干了什么                                                                                         |
| ---------------------- | -------------------------------------------------------------------------------------------- |
| `patch_smali.py`       | 三钩子：`Common.onCreate`→`Boot.attach`；`EasyPlayer.play`→`onPlay`；`playIndex(IZ)`→`onIndex`（幂等） |
| `patch_bt_scan.py`     | 拔掉原厂两处把 scan mode 强设 NONE 的 kick                                                             |
| `patch_bt_hold.py`     | 修「连上马上断」：`bootInit()` A2DP kick ＋ `BluetoothPlayer$2.run()` 的主凶分支                            |
| `patch_dlna_sync.py`   | DLNA 推流同步「正在播放」                                                                              |
| `patch_seek.py`        | AIDL 新增 seek 事务 0x15（拖动进度条）                                                                  |
| `patch_exo_engine.py`  | 搬小飞的 ExoPlayer 2.18 引擎（`MultiExoPlayer` + `new_NetPlayer`），删旧 exomedia/exoplayer2            |
| `patch_seek_exo.py`    | Exo 引擎下的 seek 落点                                                                             |
| `patch_pause_idem.py`  | `new_NetPlayer.pause()` 幂等（修 pause 误报 ERR 的第二层）                                              |
| `patch_amb.py`         | 氛围灯 `LedLight.setColor` 钩子（多彩逐颗接管）                                                           |
| `patch_dbg_swallow.py` | 排障用（默认不生效）：给原厂 12 处吞异常的点插 `printStackTrace`，`R8=1` 才开                                        |

寄生层自己的改动（`01-parasite-src/`）按版本在文件头注释里标了 `v1.3.xxx` / `pl-1.6.x`。

---

## 两条版本序列

- **release `V2.0.x`**：验收通过才出，只进发布目录，产物 `XiaoZhi_*_V2.0.x.apk`。
- **测试版 `v1.3.x` / `pl-1.6.x`**：日常迭代，只在开发目录流转，不进发布目录。

release 脚本在编译前把版本号临时改成 `2.0.2` / `pl-2.0.2`、产物落盘后立刻还原，  
所以源码树里躺着的永远是「下一个测试版」，两条序列不会串。

---

## 怎么重建

工具链不随包附带（体积大），脚本里写的是开发机绝对路径，换机器改脚本头部这几个变量：

```
JAVA_HOME  → 便携 JDK 17
R8 / D8    → r8.jar / d8 相关 jar
AJAR       → android.jar (API 22)
SMALI_CP   → smali / baksmali jar
PY         → python（Janus 拼接那步是 Windows python，别给它 MSYS 风格路径）
```

```bash
# 主包 device：release / 测试
bash 04-build-scripts/device/build_rel_V202.sh     # -> XiaoZhi_Unisound_V2.0.2.apk
bash 04-build-scripts/device/build_v151.sh         # -> xiaozhi_Unisound_v151.apk

# 控制台包 player：release / 测试
bash 04-build-scripts/player/build_rel_V202.sh     # -> XiaoZhi_EchoService_V2.0.2.apk
bash 04-build-scripts/player/build_pv40.sh         # -> xiaozhi_EchoService_pv40.apk

# 页面改动后（只改 player 一份）
python 04-build-scripts/player/ui/gen_java.py      # player.html -> Ui.java（自带 JS 语法检查）

# 静态资源（赞赏码 / 群二维码 / PWA 三件套）
python 04-build-scripts/player/_gen_assets.py      # 从 device WebConsole.java 抽到 player/Assets.java
```


```

主包链路：`javac` → `R8`（min-api 22）→ `baksmali` → 与原厂 `uni_smali` 合并 →
**钩子自检** → `janus_poc.py`（DEX + so 前置到原厂 APK 的 ZIP 目录之前，v1 签名验签 PASS）。

控制台包链路：`javac` → `d8` → `baksmali` → 与原厂 player dex 合并 + 打钩子 →
`smali` → `janus_poc.py` 拼接到 `EchoService_system.apk`。

> ★ 从 `build_*.sh` 派生新脚本时，全局替换产物名会**连底包路径一起继承**。
> 正常底包是 `diag/pandora/Unisound.apk`（134 条目）；一旦看到 **135 条目**，
> 说明误用了装不上那个注入版 `Unisound_lib.apk`。
>
> ★ 改 GBK 的 `.bat` 必须 `newline='\r\n'`（LF 行尾 cmd 打不开），且**不能用 Git Bash 跑**。

---

## 项目地址

https://github.com/shj4588/Phicomm_R1_XiaoZhi_Client
```
