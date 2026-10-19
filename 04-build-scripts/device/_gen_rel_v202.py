#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""由 build_v151.sh 生成 release 构建脚本 build_rel_V202.sh（device 侧）。

与 _gen_rel_v201.py 同构（那份是 v144 -> V2.0.1），只改四处：
  1. 头部说明：基线换成 v1.3.198（jp2 v151）；
  2. 版本号断言 1.3.198 -> 2.0.2；
  3. 产物名 xiaozhi_Unisound_v151.apk -> XiaoZhi_Unisound_V2.0.2.apk；
  4. javac 之前注入 VERSION=2.0.2，janus 构建完之后还原源码树。
"""
import io

s = io.open('build_v151.sh', encoding='utf-8').read()

HEAD = """#!/bin/bash
# =============================================================================
#  jp2 RELEASE V2.0.2 —— 与测试版(1.3.x)分开的第二条版本序列。
#
#  基线 = 测试版 v1.3.198（jp2 v151），相对 V2.0.1（v1.3.190 / v144）的变化：
#    · 曲库整批入队 op=queue_all + 占位专辑名过滤（v1.3.196）
#    · 在线搜索结果整批入队 op=queue_online（惰性地址 /r/<source>/<rid>）
#    · WebConsole 路由表补登记 queue_all（以前一路掉到 unknown op）
#    · 留空搜索随机化（searchJson 三参 shuffle，先洗后截断）
#    · 电台起播补踢收敛：出声即收手（v1.3.192）+ 蓄水回退 800ms（v1.3.195）
#    · CpuGovernor 诊断（v1.3.191，op=cool：读线程 nice / 手动降优先级取证）
#    · 控制台群二维码 GROUP_QR_B64 + /group.png 路由
#  仅在用户明确要求出 release 时才执行本脚本。
#
#  ★ 版本号策略（2026-10-18 明确）：
#      release 序列 2.0.x   <- 只有本脚本产出，产物名 XiaoZhi_Unisound_V2.0.2.apk
#      测试序列   1.3.x     <- 日常迭代（build_v151.sh 等），只在开发目录流转
#    本脚本在 javac 之前把 XzBootstrap.VERSION 临时改成 2.0.2，
#    janus 构建完立刻还原 —— 源码树永远回到测试序列，两条线不会串。
# =============================================================================
"""
if s.startswith('#!'):
    s = HEAD + s.split('\n', 1)[1]
else:
    s = HEAD + s

old_chk = 'chk(\'VERSION 1.3.198\', \'VERSION = "1.3.198"\', b)'
new_chk = 'chk(\'VERSION 2.0.2 (release)\', \'VERSION = "2.0.2"\', b)'
assert old_chk in s, 'chk anchor'
s = s.replace(old_chk, new_chk, 1)

n = s.count('xiaozhi_Unisound_v151')
assert n >= 4, 'out name count %d' % n
s = s.replace('xiaozhi_Unisound_v151', 'XiaoZhi_Unisound_V2.0.2')
print('产物改名处数:', n)

MARK = 'echo "== [1/6] javac =="'
assert MARK in s, 'javac anchor'
BS = chr(92) + 'n'          # 让内层 python 源码里是 \\n
INJ = (
    'echo "== [0.9/6] RELEASE 版本号注入：VERSION -> 2.0.2 =="\n'
    '"$PY" - <<\'INJEOF\'\n'
    'import io, re\n'
    'p = \'v2src/cn/workbuddy/r1client/XzBootstrap.java\'\n'
    'src = io.open(p, encoding=\'utf-8\').read()\n'
    'io.open(\'_VERSION_dev.bak\', \'w\', encoding=\'utf-8\', newline=' + "'" + BS + "'" + ').write(src)\n'
    's = re.sub(r\'public static final String VERSION = "[^"]*"\',\n'
    '           \'public static final String VERSION = "2.0.2"\', src)\n'
    'assert \'VERSION = "2.0.2"\' in s, \'release 版本注入失败\'\n'
    'io.open(p, \'w\', encoding=\'utf-8\', newline=' + "'" + BS + "'" + ').write(s)\n'
    'print(\'  VERSION -> 2.0.2  (release 序列；测试序列 1.3.x 保留在源码树)\')\n'
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
    'echo "RELEASE DONE: XiaoZhi_Unisound_V2.0.2.apk"\n'
)

io.open('build_rel_V202.sh', 'w', encoding='utf-8', newline='\n').write(s)
print('written build_rel_V202.sh')
