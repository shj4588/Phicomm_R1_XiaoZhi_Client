# -*- coding: utf-8 -*-
"""修 javac「常量字符串过长」：GROUP_QR_B64 有 144k 字符，写成 "..." + "..." 的
**常量表达式**会被 javac 折成单个字符串常量，超过 class 常量池 65535 字节上限
（DONATE 只有 65k，侥幸没踩线）。

改法：分片后用 StringBuilder 逐片 append —— new StringBuilder(...) 不是常量表达式，
javac 不会折，每片各占一个 < 65535 的常量。

用法：python _fix_group_qr.py <jpg路径>
"""
import base64
import io
import re
import sys

SRC = r"C:\Users\shaoh\WorkBuddy\R1\janus_pandora2\v2src\cn\workbuddy\r1client\WebConsole.java"

PAT = re.compile(r'[ ]*private static final String GROUP_QR_B64 =\n.*?\n[ ]*;\n', re.S)

DOC = '''    /**
     * player pl-1.6.33：QQ 玩机讨论群二维码（群号 885906281，JPEG base64 内嵌）。
     * GET /group.png 解码返回 image/jpeg；控制台「赞赏」弹窗右侧展示。
     *
     * <p>★ 分片必须用 StringBuilder 拼：144k 字符若写成 {@code "..." + "..."} 的常量
     * 表达式，javac 会折成<b>一个</b>字符串常量，超过 class 常量池 65535 字节上限
     * ⇒ {@code 常量字符串过长}（DONATE 只有 65k，侥幸没踩线）。</p>
     */
    private static final String GROUP_QR_B64 =
            new StringBuilder()
%APP%
            .toString()
            ;
'''


def appends(b64, width=30000):
    out = []
    for i in range(0, len(b64), width):
        out.append('            .append("%s")' % b64[i:i + width])
    return "\n".join(out)


def main():
    jpg = sys.argv[1]
    b64 = base64.b64encode(io.open(jpg, "rb").read()).decode("ascii")
    print("b64 %d chars -> %d 片" % (len(b64), (len(b64) + 29999) // 30000))

    txt = io.open(SRC, encoding="utf-8", newline="").read()
    assert "\r" not in txt, "应为 LF"
    n = len(PAT.findall(txt))
    assert n == 1, "GROUP_QR_B64 块命中 %d 次" % n
    txt = PAT.sub(DOC.replace("%APP%", appends(b64)), txt, count=1)
    io.open(SRC, "w", encoding="utf-8", newline="").write(txt)
    print("OK 已改写为 StringBuilder 分片：", SRC)
    return 0


if __name__ == "__main__":
    sys.exit(main())
