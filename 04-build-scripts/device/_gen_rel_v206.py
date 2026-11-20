#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""由 build_v205.sh 生成 release 构建脚本 build_rel_V206.sh（device 侧）。

与 _gen_rel_v205.py 同构，只改四处：
  1. 头部说明：基线换成 v1.3.359（jp2 v206）；
  2. 产物名 xiaozhi_Unisound_v205.apk -> XiaoZhi_Unisound_V2.0.6.apk；
  3. javac 之前注入 VERSION=2.0.6，janus 构建完之后还原源码树；
  4. 结尾提示语换成 V2.0.6 的要点。

★ 2026-11-20：V2.0.6（基线 v1.3.359），相对 V2.0.5（v1.3.329）新增：
  播放记录/进度上报整条链路重做（暂停结算、起播即上报、按设备分组隔离、
  书级共享进度），听书身份唯一化（书身份=子书名），合集子书可语音点名播，
  语音读时长/快进后退/跳到第几秒，续播本地库残卷自动降级到缓存/在线。
"""
import io

s = io.open('build_v205.sh', encoding='utf-8').read()

HEAD = """#!/bin/bash
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
"""
if s.startswith('#!'):
    s = HEAD + s.split('\n', 1)[1]
else:
    s = HEAD + s

n = s.count('xiaozhi_Unisound_v205')
assert n >= 4, 'out name count %d' % n
s = s.replace('xiaozhi_Unisound_v205', 'XiaoZhi_Unisound_V2.0.6')
print('产物改名处数:', n)

MARK = 'echo "== [1/6] javac =="'
assert MARK in s, 'javac anchor'
BS = chr(92) + 'n'          # 让内层 python 源码里是 \\n
INJ = (
    'echo "== [0.9/6] RELEASE 版本号注入：VERSION -> 2.0.6 =="\n'
    '"$PY" - <<\'INJEOF\'\n'
    'import io, os, re\n'
    'p = \'v2src/cn/workbuddy/r1client/XzBootstrap.java\'\n'
    'src = io.open(p, encoding=\'utf-8\').read()\n'
    'if os.path.exists(\'_VERSION_dev.bak\'):\n'
    '    print(\'  [warn] 已有 _VERSION_dev.bak（上次构建没走到还原），沿用旧备份、不覆盖\')\n'
    'else:\n'
    '    io.open(\'_VERSION_dev.bak\', \'w\', encoding=\'utf-8\', newline=' + "'" + BS + "'" + ').write(src)\n'
    's = re.sub(r\'public static final String VERSION = "[^"]*"\',\n'
    '           \'public static final String VERSION = "2.0.6"\', src)\n'
    'assert \'VERSION = "2.0.6"\' in s, \'release 版本注入失败\'\n'
    'io.open(p, \'w\', encoding=\'utf-8\', newline=' + "'" + BS + "'" + ').write(s)\n'
    'print(\'  VERSION -> 2.0.6  (release 序列；测试序列 1.3.x 保留在源码树)\')\n'
    'INJEOF\n\n'
)
s = s.replace(MARK, INJ + MARK, 1)

s = s.rstrip('\n') + (
    '\n\necho "== [7/6] 源码树版本号还原（回到测试序列 1.3.x） =="\n'
    '"$PY" - <<\'RSTEOF\'\n'
    'import io, os\n'
    'p = \'v2src/cn/workbuddy/r1client/XzBootstrap.java\'\n'
    "io.open(p, 'w', encoding='utf-8', newline='" + BS + "').write(\n"
    "    io.open('_VERSION_dev.bak', encoding='utf-8').read())\n"
    'os.remove(\'_VERSION_dev.bak\')\n'
    'print(\'  源码树 VERSION 已还原为测试序列\')\n'
    'RSTEOF\n\n'
    'echo "RELEASE DONE: XiaoZhi_Unisound_V2.0.6.apk"\n'
    'echo "  本版新增：播放记录/进度上报整条链路（暂停结算、起播即上报、"\n'
    'echo "  按设备分组隔离、书级共享进度）、听书身份唯一化（书身份=子书名）、"\n'
    'echo "  合集子书语音点名播、语音读时长/快进后退/跳到第几秒、"\n'
    'echo "  续播本地库残卷自动降级到缓存/在线"\n'
)

io.open('build_rel_V206.sh', 'w', encoding='utf-8', newline='\n').write(s)
print('written build_rel_V206.sh')
