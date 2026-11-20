#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""由 build_pv81.sh 生成 player release 构建脚本 build_rel_V206.sh。

与 _gen_rel_v205.py 同构，只改四处：
  1. 头部说明：基线换成 pl-1.6.163（pv81）；
  2. 中间产物编号 V: pv81 -> pv206；
  3. 产物名 xiaozhi_EchoService_pv81.apk -> XiaoZhi_EchoService_V2.0.6.apk；
  4. javac 之前把 Boot.VER 注入成 pl-2.0.6，构建完立刻还原源码树。

★ 2026-11-20：V2.0.6（基线 pl-1.6.163），相对 V2.0.5（pl-1.6.148）新增：
  暂停结算上报、手动暂停不被小智自动恢复、队列项自带书名、缺集按需救援
  （/api/abep、/api/abrev）、续播 10 秒口径、转码流伪 VOD（可拖动 + 转码 pill）。
"""
import io

s = io.open('build_pv81.sh', encoding='utf-8').read()

HEAD = """#!/bin/bash
# =============================================================================
#  jp2-player RELEASE V2.0.6 —— 与测试版(pl-1.6.x)分开的第二条版本序列。
#
#  基线 = 测试版 pl-1.6.163（pv81），相对 V2.0.5（pl-1.6.148）的变化：
#    · 暂停/停止/实体键暂停 ⇒ 立即通知 device 结算上报（pl-1.6.156）：
#      以前要等下一拍才上报，暂停后进度会晚一拍、甚至丢最后那一段
#    · 手动暂停不自动恢复（pl-1.6.157）：网页/实体键显式暂停后，
#      小智说完话不会自作主张恢复播放；显式起播/切歌才清标记
#    · 按钮延时根治（pl-1.6.157）：WebSrv 每连接一线程，不再串行 accept
#    · 缺集按需救援（pl-1.6.151/152）：新增 /api/abep（点缺集时按需救援）、
#      /api/abrev（按服务端 rev 复查集表），缺集占位行与服务端同口径
#    · 队列项自带书名（pl-1.6.162）：本地书被记成上一本在线书的根治，
#      播什么书由入队方盖章，不再靠 player 猜
#    · 续播 10 秒口径（pl-1.6.154/158）：续播往前推 10 秒，文案与 device 统一
#    · 转码流伪 VOD（pl-1.6.163）：服务端降档转码出来的流带时长、可拖动，
#      播放页出「转码」pill，听得见也看得见
#    · 蓝牙灯（pl-1.6.150）：连上/断开的灯效收口到设备灯效服务
#    · 书列表缓存与扫盘指纹、三栏封顶、搜索删空回列表等沿用 V2.0.5
#  仅在用户明确要求出 release 时才执行本脚本。
#
#  ★ 版本号策略：
#      release 序列 pl-2.0.x   <- 只有本脚本产出，产物名 XiaoZhi_EchoService_V2.0.6.apk
#      测试序列   pl-1.6.x     <- 日常迭代（build_pv81.sh 等），只在开发目录流转
#    本脚本在 javac 之前把 Boot.VER 临时改成 pl-2.0.6，
#    d8 构建完立刻还原 —— 源码树永远回到测试序列，两条线不会串。
# =============================================================================
"""
if s.startswith('#!'):
    s = HEAD + s.split('\n', 1)[1]
else:
    s = HEAD + s

# 1) 中间产物编号隔离
assert 'V=pv81\n' in s, 'V anchor'
s = s.replace('V=pv81\n', 'V=pv206\n', 1)

# 2) 产物名
old_out = 'OUT="xiaozhi_EchoService_$V.apk"'
new_out = 'OUT="XiaoZhi_EchoService_V2.0.6.apk"'
assert old_out in s, 'OUT anchor'
s = s.replace(old_out, new_out, 1)

# 3) javac 之前注入 + 之后还原
MARK = 'echo "=========== [1/8] 静态自检 ==========="'
assert MARK in s, 'javac anchor'
BS = chr(92) + 'n'
INJ = (
    'echo "=========== [0.5/8] RELEASE 版本号注入：VER -> pl-2.0.6 ==========="\n'
    '"$PY" - <<\'INJEOF\'\n'
    'import io, os, re\n'
    'p = \'plsrc/cn/workbuddy/r1web/Boot.java\'\n'
    'src = io.open(p, encoding=\'utf-8\').read()\n'
    'if os.path.exists(\'_VER_dev.bak\'):\n'
    '    print(\'  [warn] 已有 _VER_dev.bak（上次构建没走到还原），沿用旧备份、不覆盖\')\n'
    'else:\n'
    "    io.open('_VER_dev.bak', 'w', encoding='utf-8', newline='" + BS + "').write(src)\n"
    's = re.sub(r\'public static final String VER = "[^"]*"\',\n'
    '           \'public static final String VER = "pl-2.0.6"\', src)\n'
    'assert \'VER = "pl-2.0.6"\' in s, \'release 版本注入失败\'\n'
    "io.open(p, 'w', encoding='utf-8', newline='" + BS + "').write(s)\n"
    'print("  VER -> pl-2.0.6  (release 序列；测试序列 pl-1.6.x 保留在源码树)")\n'
    'INJEOF\n\n'
)
s = s.replace(MARK, INJ + MARK, 1)

s = s.rstrip('\n') + (
    '\n\necho "=========== [9/8] 源码树版本号还原（回到测试序列 pl-1.6.x） ==========="\n'
    '"$PY" - <<\'RSTEOF\'\n'
    'import io, os\n'
    'p = \'plsrc/cn/workbuddy/r1web/Boot.java\'\n'
    "io.open(p, 'w', encoding='utf-8', newline='" + BS + "').write(\n"
    "    io.open('_VER_dev.bak', encoding='utf-8').read())\n"
    'os.remove(\'_VER_dev.bak\')\n'
    'print("  源码树 VER 已还原为测试序列")\n'
    'RSTEOF\n\n'
    'echo "RELEASE DONE: XiaoZhi_EchoService_V2.0.6.apk"\n'
    'echo "  本版新增：暂停结算上报、手动暂停不自动恢复、WebSrv 每连接一线程、"\n'
    'echo "  缺集按需救援 /api/abep 与 /api/abrev、队列项自带书名、"\n'
    'echo "  续播 10 秒口径、转码流伪 VOD（可拖动 + 转码 pill）"\n'
)

io.open('build_rel_V206.sh', 'w', encoding='utf-8', newline='\n').write(s)
print('written build_rel_V206.sh')
