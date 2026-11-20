# 构建源码说明（V2.0.6）

这个目录是 V2.0.6 两个安装包**实际构建用的输入**，也是**能独立重建**的：
把工具链放进 `tools/`（或另指 `R1_TOOLS`），`bash build_rel_V206.sh` 就能出同样的 apk。
怎么准备环境见 **`环境说明.md`**；准备好了先跑 `bash build_check.sh` 自检。

```
04-build-scripts/
  tools/                       ← 工具链（不随包，自己放；详见 tools/README.md）
    jdk/  android.jar  r8.jar  smali/
  build_check.sh               ← 构建前自检，缺什么一次说清
  环境说明.md                  ← JDK/SDK/smali 怎么拿，坑在哪
  device/                      ← 主包 XiaoZhi_Unisound_V2.0.6（寄生在原厂 com.phicomm.speaker.device）
    v2src/                     ← 本项目写的注入代码（Java 源码）
    uni_smali/                 ← 原厂 Pandora 的 smali 基线（已打好本项目的钩子）
    ui/                        ← ui.html + gen_java.py（页面 → UiModern.java）
    javacp/                    ← 编译期依赖：okhttp / okio / orgjson / sherpa-onnx / kotlin-stdlib
    libr1opus.so               ← 编进前置 dex 的 opus 编码器
    keep_v23.pro               ← R8 压缩规则
    Unisound.apk               ← 原厂 Pandora 壳（24 MB，Janus 拼包的「尾巴」）
    build_v205.sh              ← 日常测试线构建脚本（产出 1.3.x 的自用包）
    build_rel_V206.sh          ← 本发布包的构建脚本（产出 2.0.6）
    _gen_rel_v206.py           ← 由 build_v205.sh 生成上面那份 release 脚本的工具
    janus_poc.py               ← Janus(CVE-2017-13156) 拼包 + v1 签名校验
  player/                      ← 控制台包 XiaoZhi_EchoService_V2.0.6（寄生在原厂 com.phicomm.speaker.player）
    plsrc/                     ← 注入代码（Java）
    ui/                        ← 网页前端：player.html（单文件，约 6000 行）+ gen_java.py
    stub/                      ← 编译期桩源码（原厂类的空壳，只给 javac 认类型，不进 dex）
    smali_echo/                ← 原厂 EchoService 的 smali 基线（构建时在副本上打补丁）
    smali_exo/                 ← ExoPlayer 改造用的 smali 树（构建时 cp 进 smali_build_<ver>）
    janus_poc.py               ← 同上（player 侧副本）
    build_pv81.sh              ← 日常测试线构建脚本（产出 pl-1.6.x 的自用包）
    build_rel_V206.sh          ← 本发布包的构建脚本（产出 pl-2.0.6）
    _gen_rel_v206.py           ← 同上（player 侧）
    _gen_assets.py             ← 从 device 源码里抽网页素材，生成 player 的 Assets.java
    _prep_exo_tree.py          ← 从 smali_echo 派生 smali_exo（ExoPlayer 相关改造用）
    patch_*.py                 ← 对 smali 副本做的定点补丁（14 个，含 patch_live.py 直播/转码流）
    EchoService_system.apk     ← 原厂 player 包（Janus 拼包的「尾巴」，1.1 MB）
```

## 两条版本序列

| 序列 | 版本号长什么样 | 谁产出 |
|---|---|---|
| 测试线（日常迭代） | device `1.3.x` / player `pl-1.6.x` | `build_v205.sh` / `build_pv81.sh` |
| 发布线 | device `2.0.x` / player `pl-2.0.x` | `build_rel_V206.sh` |

发布线的脚本是**生成**出来的：`_gen_rel_v206.py` 读当前测试线脚本，把产物名换掉，
并在 `javac` 之前临时把源码里的版本串改成 `2.0.6` / `pl-2.0.6`，构建结束后立刻还原。
**源码树永远停在测试序列**（本包里 `XzBootstrap.VERSION = 1.3.359`、
`Boot.VER = pl-1.6.163`），两条线不会串。

> 自 v1.3.323 起，测试线脚本的版本自检改成**从源码动态读取**，所以升级版本号不会再把
> 构建卡在「自检 MISSING」上；release 脚本也一并继承了这一点。

## 重建需要准备什么

构建脚本已经改成**跟着脚本位置走**，不再有写死的本机路径。工具链放 `04-build-scripts/tools/`，
或用 `R1_TOOLS=<目录>` 指过去；包内资产（`ui/`、`javacp/`、两个原厂壳等）都在。
逐项获取方式见 **`环境说明.md`**。

| 依赖 | 用途 | 脚本里的变量 |
|---|---|---|
| JDK 17（Temurin） | `javac` 编注入代码、`java` 跑 smali/baksmali/r8 | `JDK` / `JAVAC` / `JAVA` |
| `android.jar` | 编译时的 bootclasspath | `AJAR` |
| `r8.jar` | R8 压缩 + 出 dex | `R8` |
| smali / baksmali 2.5.2 及其依赖 jar（8 个） | smali ⇄ dex | `SM` / `SMALI_CP` / `BCP` / `SCP` |
| Python 3.6+ | 生成脚本、smali 补丁、Janus 拼包 | `PY` |
| 7-Zip（可选） | 打包压缩 | — |

原厂壳 `Unisound.apk`（Pandora）和 `EchoService_system.apk`（player）**已随包提供**，
分别放在 `device/` 和 `player/` 下。它们来自斐讯 R1 原厂固件，仅作互操作与学习用途。

## 怎么重建

```bash
# device：产出 XiaoZhi_Unisound_V2.0.6.apk（Janus 前置 dex + 原厂壳）
cd 04-build-scripts/device && bash build_rel_V206.sh

# player：产出 XiaoZhi_EchoService_V2.0.6.apk
cd 04-build-scripts/player && bash build_rel_V206.sh
```

两个脚本都是「先静态自检、再编译」，自检里有一堆 `chk '<标签>' '<必须出现的字符串>'`
断言 —— 改动代码后如果自检报 `MISSING`，说明你动的正好是被断言保护的那段行为，
要么改回来，要么同步改断言（**别直接删断言**）。

脚本对生成物很敏感：`plsrc/.../Assets.java`、`plsrc/.../Ui.java`、`ui/_preview.html`
每次构建都会重新生成。如果机器上有别的进程正打开它们（编辑器的预览、杀软扫描），
Windows 会拒绝覆盖写 ⇒ 构建报 `PermissionError`。把文件先改名再重跑即可：

```bash
mv plsrc/cn/workbuddy/r1web/Ui.java Ui.java.locked && bash build_rel_V206.sh
```

## 本版相对 V2.0.5 的源码变化（改哪儿了）

> V2.0.5 那一版这张表里写过 `AbPick.java` / `AbMem.java` / `AbLocal.java` —— **这些文件不存在**，
> 相关逻辑全在 `PlayerBridge.java` 里。下表按实际文件写。

| 模块 | 文件 | 变化 |
|---|---|---|
| 起播即上报（指纹轻探） | `device/v2src/.../ProgProgress.java`、`PlayerBridge.java` | 每 2 秒读 player 的 music 摘要算指纹（曲名\|curId\|playing\|pos>3s），变化或 15 秒到期才 tick；不再拉整张队列 |
| 位置/结算上报 | `device/v2src/.../ProgProgress.java`、`WebConsole.java`、`PlayerBridge.java` | `/v1/pos` 带 `devId`（`mac12()`）；网页暂停/停止/实体键/关机四条路都走 `srvFinal` |
| 书级共享进度 | `device/v2src/.../PlayerBridge.java` | `srvResume()` 问 `/v1/resume?q=&dev=`，命中就回写本地进度；`normBookKey()` 与服务端同口径 |
| 听书身份唯一化 | `device/v2src/.../PlayerBridge.java`、`player/plsrc/.../WebSrv.java`、`PCtl.java` | `queueItemBook()`：书名取自队列项 `album`（谁入队谁盖章）；合集子书的书身份 = 子书名 |
| 合集指名播 | `device/v2src/.../PlayerBridge.java`、`SessionController.java` | `collPick()` 按用户原话挑子书；`SessionController` 把原话留给 `abPlay` |
| 语音调进度 | `device/v2src/.../XiaoZhiWs.java`、`PlayerBridge.java`、`WebConsole.java` | 新工具 `seek_play`（sec/delta/只查）；`playerMusic()`/`progressText()`/`fmtHms()`；`op=progress`、`op=seek_play` |
| 续播降级 | `device/v2src/.../PlayerBridge.java` | `abResume` 集号 pick 失败 ⇒ 本地库 → `/api/ab/episodes`（缓存优先）→ ≤target 最近集 |
| 缺集救援 / 脏缓存 | `device/v2src/.../Prog.java`、`ProgPlay.java`、`player/plsrc/.../WebSrv.java` | 带 `ep` 透传做按需救援；脏占位行强制重问；`/api/abep`、`/api/abrev` |
| 手动暂停意图 | `player/plsrc/.../PCtl.java`、`DevCtl.java`、`device/v2src/.../TopKey.java` | `usrIntent()` 双向通知；`noteUserPause()`/`noteUserPlay()` 让 MusicResume 不自动恢复 |
| WebSrv 并发 | `player/plsrc/.../WebSrv.java` | 每连接一线程（原先串行 accept 是按钮延时的真凶）；转码流伪装成 VOD（带时长、可拖） |
| 转码 pill | `player/ui/player.html` | 播放页第二颗青色 pill，服务端降档转码时点亮 |

V2.0.5 已具备、本版继续沿用的：整本集表缓存 / play-first、我的节目整批占位、
下一集上一集队列快路径、tail-kick 三道门、本地有声书库优先。


## 没带上的东西（以及为什么）

| 东西 | 原因 |
|---|---|
| `out_pv*/`、`classes_pv*/`、`smali_build_*/`、`smali_xz27/` | 全是构建中间产物，脚本每次重建 |
| `*.apk`（除 `EchoService_system.apk`） | 产物本身就在 `../apps/` 里 |
| `_build_*.log`、各种 `_chk/_fav/_fp` 临时文件 | 调试残留 |
| 历史版本生成器 `_gen_build_pvNN.py`、`_mk_vNNN.py`（上百个） | 只是当时的改写记录，对重建没帮助 |

## 改代码的入口在哪

| 想改什么 | 看哪里 |
|---|---|
| 语音开关 / 灯效 / 灯号 | `device/v2src/.../R1Lights.java`、`VoicePower.java` |
| 收藏、播放队列、MCP 工具、有声书 | `device/v2src/.../PlayerBridge.java`、`Fav.java`、`ProgPlay.java` |
| 闹钟（调度、响铃、音量互斥） | `device/v2src/.../Alarm.java`、`AlarmVol.java`、`RingPlayer.java` |
| 顶部实体按键 | `device/v2src/.../TopKey.java` + `uni_smali/.../keyevent/PhicommKeyEventController.smali` |
| 网页（几乎所有界面） | `player/ui/player.html`（单文件，改完由 `gen_java.py` 塞进 `Ui.java`） |
| HTTP 接口 / SSE / 页面缓存 | `player/plsrc/.../WebSrv.java` |
| 播放控制 / 状态判定 | `player/plsrc/.../PCtl.java` |
| DLNA / AirPlay / 蓝牙开关 | `player/plsrc/.../Svc.java`、`Bt.java` |

## 版权与免责

本项目采用 **[PolyForm Noncommercial 1.0.0](../LICENSE)**（非商业许可）：可自用、可修改、
可送人，**不可商业使用**（卖刷好的机器、收费刷机、内置进商业产品都不行）。

本许可只覆盖本目录下由作者原创的构建脚本、补丁脚本与 `cn.workbuddy.r1client` /
`cn.workbuddy.r1web` 的 Java 源码。`uni_smali/`、`smali_echo/`、`EchoService_system.apk`、
`Unisound.apk` 的来源是斐讯 R1 原厂固件里的应用，仅作互操作与学习用途，**版权归原厂及其
授权方所有，不在本许可授权范围内**。第三方组件清单见 [NOTICE.md](../NOTICE.md)。
