# -*- coding: utf-8 -*-
"""把 QQ 讨论群二维码（剪贴板 jpg）以 GROUP_QR_B64 常量 + /group.png 路由
注入 device 侧 WebConsole.java（player 侧 Assets.java 由 _gen_assets.py 从这里抽）。

用法：python _add_group_qr.py <jpg路径>
幂等：已存在 GROUP_QR_B64 则跳过。
"""
import base64
import io
import os
import sys

SRC = r"C:\Users\shaoh\WorkBuddy\R1\janus_pandora2\v2src\cn\workbuddy\r1client\WebConsole.java"
ANCHOR = '    private static final String TAG = "R1CLIENT";'
DONATE_ROUTE_END = '        if (path.equals("/donate.png")) {\n' \
    '            byte[] img = android.util.Base64.decode(DONATE_QR_B64, android.util.Base64.DEFAULT);\n' \
    '            write(out, "200 OK", "image/png", img);\n' \
    '            return;\n' \
    '        }\n'
DOC_LINE = " *   GET  /donate.png       赞赏码二维码（页面底部按钮弹出）"

CONST = '''    /**
     * player pl-1.6.33：QQ 玩机讨论群二维码（群号 885906281，JPEG base64 内嵌）。
     * GET /group.png 解码返回 image/jpeg；控制台「赞赏」弹窗右侧展示。
     */
    private static final String GROUP_QR_B64 =
%s
            ;
'''

ROUTE = '''        if (path.equals("/group.png")) {
            byte[] gimg = android.util.Base64.decode(GROUP_QR_B64, android.util.Base64.DEFAULT);
            write(out, "200 OK", "image/jpeg", gimg);
            return;
        }
'''


def b64_lines(data, width=30000):
    b = base64.b64encode(data).decode("ascii")
    out = []
    for i in range(0, len(b), width):
        chunk = b[i:i + width]
        tail = ' +"' if i + width < len(b) else ''
        # 与 DONATE 同款：行 = '            "...." +'（末行不带 +）
        prefix = '            "'
        out.append(prefix + chunk + ('' if tail else '"'))
        if tail:
            out[-1] = prefix + chunk + '" +'
    return "\n".join(out)


def main():
    jpg = sys.argv[1]
    data = io.open(jpg, "rb").read()
    print("jpg: %d bytes -> b64 %d chars" % (len(data), len(base64.b64encode(data))))

    txt = io.open(SRC, encoding="utf-8", newline="").read()
    assert "\r" not in txt, "device WebConsole.java 应为 LF"

    if "GROUP_QR_B64" in txt:
        print("SKIP: GROUP_QR_B64 已存在")
        return 0

    # ① 常量
    assert txt.count(ANCHOR) == 1, "TAG 锚点异常"
    txt = txt.replace(ANCHOR, CONST % b64_lines(data) + ANCHOR)

    # ② 路由（紧跟 /donate.png 之后）
    assert txt.count(DONATE_ROUTE_END) == 1, "donate 路由锚点异常"
    txt = txt.replace(DONATE_ROUTE_END, DONATE_ROUTE_END + ROUTE)

    # ③ 头部路由清单
    assert txt.count(DOC_LINE) == 1, "doc 行锚点异常"
    txt = txt.replace(DOC_LINE, DOC_LINE
                      + "\n *   GET  /group.png        QQ 讨论群二维码（赞赏弹窗右侧）")

    io.open(SRC, "w", encoding="utf-8", newline="").write(txt)
    print("OK: 常量 + /group.png 路由 + doc 已写入", SRC)
    return 0


if __name__ == "__main__":
    sys.exit(main())
