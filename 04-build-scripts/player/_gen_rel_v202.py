#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""由 build_pv40.sh 生成 player release 构建脚本 build_rel_V202.sh。

与 _gen_rel_v201.py 同构（那份是 pv22 -> V2.0.1），只改四处：
  1. 头部说明：基线换成 pl-1.6.34（pv40）；
  2. 中间产物编号 V: pv40 -> pv202；
  3. 产物名 xiaozhi_EchoService_pv40.apk -> XiaoZhi_EchoService_V2.0.2.apk；
  4. javac 之前把 Boot.VER 注入成 pl-2.0.2，构建完立刻还原源码树。
"""
import io

s = io.open('build_pv40.sh', encoding='utf-8').read()

HEAD = """#!/bin/bash
# =============================================================================
#  jp2-player RELEASE V2.0.2 —— 与测试版(pl-1.6.x)分开的第二条版本序列。
#
#  基线 = 测试版 pl-1.6.34（pv40），相对 V2.0.1（pl-1.6.18 / pv22）的变化：
#    · 播放引擎换成 ExoPlayer 2.18（网页可切回 MediaPlayer，开关落 SP）
#    · 进度条增量锁相（匀速）/ 电台识别即时化（不再先显示「本地曲库」）
#    · 暂停即时反馈（sUserPaused 记账 + 前端乐观更新）/ pause 不再误报 ERR
#    · 码率 + 格式显示（AudioInfo：URL 正则 + HTTP HEAD）
#    · 氛围灯：多彩 = 氛围灯的子项（原厂 / 多彩二选一）+ 配色去白 + 不放歌不闪
#    · DLNA / AirPlay 状态补查 IPv6 端口表 + 事件驱动补开（5 秒窗口）
#    · 蓝牙三处隐患（代理守卫 / sink 假阴性 / 已配对重复卡片）
#    · 控制台：赞赏 / 讨论群两个独立弹窗 + 页脚明文项目地址
#  仅在用户明确要求出 release 时才执行本脚本。
#
#  ★ 版本号策略（2026-10-18 明确）：
#      release 序列 pl-2.0.x   <- 只有本脚本产出，产物名 XiaoZhi_EchoService_V2.0.2.apk
#      测试序列   pl-1.6.x     <- 日常迭代（build_pv40.sh 等），只在开发目录流转
#    本脚本在 javac 之前把 Boot.VER 临时改成 pl-2.0.2，
#    d8 构建完立刻还原 —— 源码树永远回到测试序列，两条线不会串。
# =============================================================================
"""
if s.startswith('#!'):
    s = HEAD + s.split('\n', 1)[1]
else:
    s = HEAD + s

# 1) 中间产物编号隔离
assert 'V=pv40\n' in s, 'V anchor'
s = s.replace('V=pv40\n', 'V=pv202\n', 1)

# 2) 产物名
old_out = 'OUT="xiaozhi_EchoService_$V.apk"'
new_out = 'OUT="XiaoZhi_EchoService_V2.0.2.apk"'
assert old_out in s, 'OUT anchor'
s = s.replace(old_out, new_out, 1)

# 3) 版本断言
old_chk = """chk "$SRCS/Boot.java"    '版本 pl-1.6.34'      'pl-1.6.34'"""
new_chk = """chk "$SRCS/Boot.java"    '版本 pl-2.0.2 (release)'      'pl-2.0.2'"""
assert old_chk in s, 'chk anchor'
s = s.replace(old_chk, new_chk, 1)

# 4) javac 之前注入 + 之后还原
MARK = 'echo "=========== [1/8] 静态自检 ==========="'
assert MARK in s, 'javac anchor'
BS = chr(92) + 'n'
INJ = (
    'echo "=========== [0.5/8] RELEASE 版本号注入：VER -> pl-2.0.2 ==========="\n'
    '"$PY" - <<\'INJEOF\'\n'
    'import io, re\n'
    'p = \'plsrc/cn/workbuddy/r1web/Boot.java\'\n'
    'src = io.open(p, encoding=\'utf-8\').read()\n'
    "io.open('_VER_dev.bak', 'w', encoding='utf-8', newline='" + BS + "').write(src)\n"
    's = re.sub(r\'public static final String VER = "[^"]*"\',\n'
    '           \'public static final String VER = "pl-2.0.2"\', src)\n'
    'assert \'VER = "pl-2.0.2"\' in s, \'release 版本注入失败\'\n'
    "io.open(p, 'w', encoding='utf-8', newline='" + BS + "').write(s)\n"
    'print("  VER -> pl-2.0.2  (release 序列；测试序列 pl-1.6.x 保留在源码树)")\n'
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
    'echo "RELEASE DONE: XiaoZhi_EchoService_V2.0.2.apk"\n'
)

io.open('build_rel_V202.sh', 'w', encoding='utf-8', newline='\n').write(s)
print('written build_rel_V202.sh')
