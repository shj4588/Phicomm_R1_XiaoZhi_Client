# -*- coding: utf-8 -*-
"""build_v150.sh -> build_v151.sh（jp2-v1.3.197）

改动点：
  1) 头部插入 v1.3.197 说明
  2) VERSION chk: 1.3.196 -> 1.3.197
  3) 产物名 / 日志名 v150 -> v151
  4) 新增 chk：WebConsole 转 queue_all 路由（上一版漏了 ⇒ 页面点一下回 unknown op）
"""
import io, os

SRC = 'build_v150.sh'
DST = 'build_v151.sh'
d = open(SRC, 'rb').read().decode('utf-8')

NEW_HEAD = '''#!/bin/bash
# jp2-v1.3.197：补上 op=queue_all 的 WebConsole 路由（v1.3.196 漏了）
#   ★ 症状：控制台「搜索曲库 → 全部载入播放列表」点一下弹 "unknown op queue_all"。
#   ★ 根因：PlayerBridge.dispatch() 里 queue_all 早就实现了（v1.3.196），
#     但 **WebConsole 的 /api/act 路由表没登记它** ⇒ 一路掉到方法末尾的
#     `return "unknown op " + op`。这正是当年 play_index 栽过的同款坑
#     （build 脚本里那两行注释就是当年的记录）：dispatch 支持 ≠ WebConsole 认。
#   ★ 修：在 play_url 之前插一条 queue_all 分支，把 ids / limit 原样传给 dispatch。
#
# jp2-v1.3.196：专辑占位名过滤 + 电台切台清残留 + 「全部载入播放列表」
'''

assert d.startswith('#!/bin/bash\n# jp2-v1.3.196'), '头部锚点不匹配'
d = NEW_HEAD + d.split('\n', 1)[1]
assert d.startswith('#!/bin/bash\n'), 'shebang 丢了'
assert d.count('#!/bin/bash') == 1, 'shebang 重复'

# --- 2) 版本 chk ---
a = """chk('VERSION 1.3.196', 'VERSION = "1.3.196"', b)"""
b = """chk('VERSION 1.3.197', 'VERSION = "1.3.197"', b)"""
assert d.count(a) == 1, 'VERSION chk 锚点 %d' % d.count(a)
d = d.replace(a, b)

# --- 4) 新增 chk（跟在 queue_all 记账那条后面）---
a = """chk('queue_all 记账', '"queue_all".equals(op))', s)"""
b = """chk('queue_all 记账', '"queue_all".equals(op))', s)
# v1.3.197：路由表必须登记 queue_all，否则掉到底就是 "unknown op queue_all"
#   （本块只定义了 s，WebConsole 单独读一次）
_w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
chk('queue_all 路由', 'v1.3.197：「全部载入播放列表」', _w)
chk('queue_all 转 dispatch', 'PlayerBridge.dispatch("queue_all", a)', _w)
# ★ 反向断言：以后谁再在 WebConsole 里加 dispatch 分支却忘了路由，能被抓住
if 'PlayerBridge.dispatch("queue_all", a)' not in _w:
    print('  MISSING  WebConsole 没转 op=queue_all'); ok = False"""
assert d.count(a) == 1, '追加 chk 锚点 %d' % d.count(a)
d = d.replace(a, b)

# --- 3) 产物名 / 日志名 ---
n = d.count('xiaozhi_Unisound_v150')
assert n == 6, 'v150 产物名出现 %d 次' % n
d = d.replace('xiaozhi_Unisound_v150', 'xiaozhi_Unisound_v151')
d = d.replace('javac_v150.log', 'javac_v151.log')
d = d.replace('r8_v150.log', 'r8_v151.log')

open(DST, 'wb').write(d.encode('utf-8'))
t = open(DST, encoding='utf-8').read()
print('WROTE', DST, os.path.getsize(DST), 'bytes')
print('  v151     :', t.count('v151'))
print('  1.3.197  :', t.count('1.3.197'))
print('  残留 v150:', t.count('v150'))
