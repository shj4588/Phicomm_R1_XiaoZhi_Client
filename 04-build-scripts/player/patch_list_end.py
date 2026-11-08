# -*- coding: utf-8 -*-
"""
列表循环播到队尾即停（不再回卷到第 0 首）。

现象：语音点一首在线歌 / 点名一集故事，播放列表只有 1 首，播完马上又从头放，
      和单曲循环一样停不下来；多首的列表播完也会从第一首重新开始。

根因：原厂引擎 getNextPosition（new_NetPlayer / NetPlayer4 各一份）在
      mode=2（PLAY_MODE_LIST_CYCLE，网页上的「顺序播放」）下写的是
      pos < size-1 ? pos+1 : 0 —— 队尾一律回卷；原厂又没有「播完停」这一档模式。

改法：两个方法头各插一道闸门 —— invoke-static PCtl.stopAtListEnd(mode, pos, size)Z
      （节目行返回 false，保持回卷给 device 的 tail-kick 续拉下一批）：
        · HandlePlayerBufferingCompleted：命中 ⇒ 不预缓冲「回卷后的第 0 首」，直接 return；
        · HandlePlayCompleted：命中 ⇒ stop()、reportPlayEnd、return（原地停）。
      不命中 ⇒ 原逻辑原样。手动「下一首」走 gotoNext(true…)，不经过这两处，照旧能回到第一首。
      不改 getNextPosition 本身：返回 -1 会顺着 openNext → setNextTrack(-1) 越界。
"""
import io, re, sys

MARK = 'r1web: 列表循环队尾即停'
MODEL = 'Lcom/phicomm/speaker/player/model/'

# 闸门公共段：读 mode / pos / size 交给 PCtl 判定；不命中跳到 :{lbl}
HEAD = """    # {mark}：队尾播完不回卷（单曲队列 = 单曲循环）
    iget v0, p0, {model}{cls};->mPlayMode:I

    iget v1, p0, {model}{cls};->mPlayPos:I

    iget-object v2, p0, {model}{cls};->mPlaylist:Ljava/util/List;

    if-eqz v2, :{lbl}

    invoke-interface {{v2}}, Ljava/util/List;->size()I

    move-result v2

    invoke-static {{v0, v1, v2}}, Lcn/workbuddy/r1web/PCtl;->stopAtListEnd(III)Z

    move-result v0

    if-eqz v0, :{lbl}

"""

# HandlePlayCompleted 命中：原地停 + 通知播放结束（同 patch_abnoskip 的收尾）
STOP = """    invoke-virtual {{p0}}, {model}{cls};->stop()Z

    iget-object v0, p0, {model}{cls};->mContext:Landroid/content/Context;

    invoke-static {{v0}}, Lcom/phicomm/speaker/player/helpers/MessageHelper;->reportPlayEnd(Landroid/content/Context;)V

    return-void

    :{lbl}
"""

# HandlePlayerBufferingCompleted 命中：不预缓冲下一首
SKIP = """    return-void

    :{lbl}
"""

# 闸门用到 v0..v2，方法又只有 this 一个参数 ⇒ 至少 4 个寄存器
MIN_REGS = 4


def norm(t):
    return t.replace('\r\n', '\n')


def insert_gate(s, cls, method, tail, lbl):
    anchor = '.method private %s()V' % method
    i = s.find(anchor)
    if i < 0:
        print('  [FAIL] %s: 找不到 %s' % (cls, method))
        sys.exit(1)
    m = re.compile(r'\.registers (\d+)\n').search(s, i)
    if not m or s.find('.end method', i) < m.start():
        print('  [FAIL] %s.%s: 找不到 .registers' % (cls, method))
        sys.exit(1)
    regs = max(int(m.group(1)), MIN_REGS)
    gate = (HEAD + tail).format(mark=MARK, model=MODEL, cls=cls, lbl=lbl)
    return s[:m.start()] + '.registers %d\n' % regs + gate + s[m.end():]


def patch(path, cls):
    s = norm(io.open(path, encoding='utf-8', errors='ignore').read())
    if MARK in s:
        print('  [OK] %s 已打过' % cls)
        return
    s = insert_gate(s, cls, 'HandlePlayerBufferingCompleted', SKIP, 'listend_buf_off')
    s = insert_gate(s, cls, 'HandlePlayCompleted', STOP, 'listend_done_off')
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)
    print('  [OK] %s 列表循环队尾即停闸门已插（缓冲完成 + 播放完成）' % cls)


def main():
    if len(sys.argv) < 3:
        print('usage: patch_list_end.py new_NetPlayer.smali NetPlayer4.smali')
        sys.exit(1)
    patch(sys.argv[1], 'new_NetPlayer')
    patch(sys.argv[2], 'NetPlayer4')


if __name__ == '__main__':
    main()
