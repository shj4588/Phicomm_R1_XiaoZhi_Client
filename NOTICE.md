# NOTICE

本仓库是「斐讯 R1 音箱 → 小智语音助手」的**寄生式改造**工程。它不可避免地把三类版权归属
完全不同的东西放在了一起，因此 **LICENSE 只覆盖其中一类**：

- ✅ **作者原创**：`04-build-scripts/` 下的构建脚本、补丁脚本、Janus 拼包脚本，以及
  `cn.workbuddy.r1client` / `cn.workbuddy.r1web` 两个包的 Java 源码 —— 按
  [PolyForm Noncommercial 1.0.0](LICENSE) 授权（非商业用途）。
- ❌ **原厂代码与产物**：版权归斐讯（Phicomm）/ 云知声（Unisound）等原权利人，
  **作者不主张任何权利，也不授予任何权利**。见第一节。
- ⚠️ **第三方组件**：各自保留原许可，见第二节。

---

## 一、不在本许可授权范围内的部分

| 路径 | 内容 | 权利人 |
|---|---|---|
| `03-stock-rom/*.apk` | 原厂 Pandora（`Pandora.com.phicomm.speaker.device.apk`）、`EchoService.apk`、`EchoService_system.apk`、`Unisound*.apk`、历史版本 `r1-client-v1.3.64.apk` | 斐讯 / 云知声 |
| `04-build-scripts/device/Unisound.apk` | Janus 拼包用的原厂壳 | 云知声 |
| `04-build-scripts/player/EchoService_system.apk` | player 包后半段的原厂本体 | 斐讯 |
| `02-reverse-smali/` | 由上述 APK 反编译得到的 smali（`device-uni_smali`）与补丁脚本操作的原始代码（`device-patches`、`player-patches`） | 斐讯 / 云知声（衍生作品） |
| `05-reverse-docs/` 内的原厂 so 与抓包留档 | `libUni4micHalJNI.so`、`libUniMicArray.so`、`audio.primary.rk30board.so` 等 | 斐讯 / 云知声 |

这些文件的作用是**留档与可复现**：本项目通过 Janus（CVE-2017-13156）把自有代码前置进原厂包，
必须持有原厂包才能构建。它们不是本项目可以再许可的东西。

因此，任何人从本仓库取用这部分内容，**只能依据他自己对当地法律的判断**（例如对自己拥有的
设备做互操作研究），与本项目的许可无关。若你是上述权利人并希望这些留档被移除，
请开 issue，会立即处理。

---

## 二、第三方组件

| 组件 | 版本 | 许可 | 用途 |
|---|---|---|---|
| [OkHttp](https://square.github.io/okhttp/) | 4.x | Apache-2.0 | device 侧 HTTP 客户端 |
| [Okio](https://square.github.io/okio/) | — | Apache-2.0 | OkHttp 依赖 |
| [Kotlin stdlib](https://kotlinlang.org/) | 2.0.21 | Apache-2.0 | OkHttp / coroutines 运行时 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | — | Apache-2.0 | 离线 KWS 唤醒词检测 |
| [JSON-java (org.json)](https://github.com/stleary/JSON-java) | 20231013 | JSON License | 服务端 / 本地库 JSON 解析 |
| [libopus](https://opus-codec.org/) (`libr1opus.so`) | 1.x | BSD-3-Clause | Opus 编解码（无 root 由 SoHost 解出） |
| [smali / baksmali](https://github.com/JesusFreke/smali) | 构建期 | BSD-3-Clause | 反编译 / 回编译 |
| [apktool](https://apktool.org/) | 构建期 | Apache-2.0 | 资源与 AndroidManifest 处理 |
| Android SDK `android.jar` | 编译期占位 | — | 仅用于 javac classpath，不随产物分发 |

说明：

- **JSON License** 含「The Software shall be used for Good, not Evil」条款，被 FSF 视为非自由许可，
  但与开源许可并存分发是常见做法，此处按原样保留。
- OkHttp / Okio / Kotlin stdlib / sherpa-onnx / libopus 会被打进最终 APK。分发 APK 时
  **需同时附带本 NOTICE 与各自许可原文**，这是 Apache-2.0 第 4 条与 BSD-3-Clause 的要求。

---

## 三、使用限制

本项目用到了 [Janus（CVE-2017-13156）](https://www.cve.org/CVERecord?id=CVE-2017-13156)
签名绕过，把自有 dex 前置进原厂 APK，使代码运行在 `platform_app` 域以取得原厂四麦阵列 HAL
的访问权限。这意味着：

1. **只能用于你本人拥有的设备。** 刷别人的机器、或刷机后转售，都不在本项目意图内，
   也不受本许可支持（PolyForm Noncommercial 明确禁止商业用途，包括「刷好机器去卖」）。
2. **不用于规避付费内容或他人设备的授权校验。** 本项目不含任何音乐、有声书资源；
   媒体内容需自备，且需遵守各平台条款。
3. 刷机有变砖风险，风险自负。原厂固件 `3448` 是可回退的前提，请先备份。

---

## 四、无担保

按许可条款，本软件按「现状」提供，不附带任何明示或默示担保。作者不对刷机导致的设备损坏、
数据丢失、账号异常或任何间接损失承担责任。
