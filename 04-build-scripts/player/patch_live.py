# -*- coding: utf-8 -*-
r"""
pl-1.6.160：真播 URL 打 live=1 标记，供 mediaserver 区分「真播」与「预取/探测」。

背景：device 推整张歌单给 player，引擎装载队列时对每首都发 GET 拿响应头做
格式探测/预取；mediaserver 来者不拒 ⇒ 没播的歌也进播放记录。抓包证实：
预取与真播在 HTTP 层字节数/时长几乎一致，服务端无法区分。

根治：player 在**真播**那一刻给拉流 URL 追加 &live=1，服务端只认带标记的 GET。
分界点在引擎层（真播与预取都从这层下发，且跨进程可靠）：
  · 真播：setDataSource(...)  → MultiExoPlayer/MultiMediaPlayer 会 play() + 设 currentPlayer
  · 预取：setPreparingDataSource(...) → 只 setMediaSource(url,false)，不播
本脚本只在 setDataSource 方法头插桩，预取路径一律不碰。

插桩内容（p1 = URL 参数，方法头到首次读 p1 之间无其它读取，安全）：
    invoke-static {p1}, Lcn/workbuddy/r1web/PCtl;->markLive(Ljava/lang/String;)Ljava/lang/String;
    move-result-object p1

幂等：带 MARK 注释就跳过。两套引擎都打（Exo 默认 / NetPlayer4 可选）。

用法： python patch_live.py <smali树根目录>
"""
import io
import os
import sys

MARK = 'r1web: 真播打 live 标记'

# ⚠ 寄存器坑：Exo 的 setDataSource 有 .registers 25，p1 落在 v19；
#   invoke-static（非 range）和 move-result-object 都只认 4-bit 寄存器（v0~v15）。
#   所以用低位空闲寄存器 v0 中转：方法头插桩时 v0 尚未被原代码占用，
#   原第一条指令会给 v0 重新赋值，而此时结果早已写回 p1，互不干扰。
HOOK = """    # {mark}
    move-object/from16 v0, p1
    invoke-static {{v0}}, Lcn/workbuddy/r1web/PCtl;->markLive(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0
    move-object/from16 p1, v0
"""

# (文件相对路径, 方法签名片段)
TARGETS = [
    ('com/phicomm/speaker/player/model/MultiExoPlayer.smali',
     'public setDataSource(Ljava/lang/String;Ljava/lang/String;IZJ)V'),
    ('com/phicomm/speaker/player/model/MultiMediaPlayer.smali',
     'public declared-synchronized setDataSource(Ljava/lang/String;IZ)V'),
]


def norm(t):
    return t.replace('\r\n', '\n')


def patch(path, sig):
    if not os.path.exists(path):
        print('  [FAIL] 没有 %s' % path)
        sys.exit(1)
    s = norm(io.open(path, encoding='utf-8', errors='ignore').read())
    if MARK in s:
        print('  [skip] %s 已打过 live 标记' % os.path.basename(path))
        return
    anchor = '.method ' + sig
    i = s.find(anchor)
    if i < 0:
        print('  [FAIL] %s 找不到方法 %s' % (path, sig))
        sys.exit(1)
    # 方法头之后第一个定位行：优先 .prologue，没有就退到 .registers
    j = -1
    for kw in ('.prologue', '.registers'):
        k = s.find('\n    ' + kw, i, i + 600)
        if k >= 0:
            j = k + 1
            break
    if j < 0:
        print('  [FAIL] %s 方法头没有 .prologue/.registers' % sig)
        sys.exit(1)
    eol = s.find('\n', j)
    if eol < 0:
        print('  [FAIL] 定位行异常')
        sys.exit(1)
    s = s[:eol + 1] + HOOK.format(mark=MARK) + '\n' + s[eol + 1:]
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)
    print('  [OK] %s 插入 markLive' % os.path.basename(path))


def verify(root):
    ok = True
    for rel, sig in TARGETS:
        p = os.path.join(root, rel)
        s = norm(io.open(p, encoding='utf-8', errors='ignore').read())
        has = MARK in s and 'PCtl;->markLive(Ljava/lang/String;)Ljava/lang/String;' in s
        print('  复核 %-26s %s' % (os.path.basename(rel), 'OK' if has else 'FAIL'))
        ok &= has
    return ok


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'smali_build'
    print('== 打 live 标记：%s ==' % root)
    for rel, sig in TARGETS:
        patch(os.path.join(root, rel), sig)
    if not verify(root):
        sys.exit(1)


if __name__ == '__main__':
    main()
