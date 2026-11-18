#!/usr/bin/env python
"""渲染 5 套主题 × 亮暗 的比对图，用于挑色。

    python gen_preview.py --tokens tokens.json --out themes.png
"""

from __future__ import annotations

import argparse
import json
import os

from PIL import Image, ImageDraw, ImageFont

FONTS = [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc"]
ORDER = [("aurora", "极光", "靛紫"), ("ember", "余烬", "赤陶"), ("sage", "苔原", "鼠尾草绿"),
         ("cobalt", "深海", "钴蓝"), ("noir", "墨色", "单色")]

CELL_W, CELL_H, GAP, MARGIN, HEADER = 336, 226, 14, 26, 84


def font(size, bold=False):
    for path in ([FONTS[1]] if bold else FONTS) + FONTS:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size, index=0)
            except OSError:
                continue
    return ImageFont.load_default()


def rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def rgba(h):
    h = h.lstrip("#")
    if len(h) == 8:
        return rgb(h[2:]) + (int(h[0:2], 16),)
    return rgb(h) + (255,)


def over(fg, bg):
    c, a = rgba(fg), rgba(fg)[3] / 255.0
    return tuple(round(c[i] * a + bg[i] * (1 - a)) for i in range(3))


def cell(t, name, note):
    bg = rgb(t["canvas"])
    img = Image.new("RGBA", (CELL_W, CELL_H), bg + (255,))
    blobs = Image.new("RGBA", (CELL_W, CELL_H), (0, 0, 0, 0))
    bd = ImageDraw.Draw(blobs)
    for key, cx, cy, r, a in (("blob_a", -30, -40, 230, 46), ("blob_b", CELL_W + 30, 10, 240, 42),
                              ("blob_c", -10, CELL_H + 50, 220, 38), ("blob_d", CELL_W - 10, CELL_H, 150, 26)):
        c = rgba(t[key])
        bd.ellipse((cx - r, cy - r, cx + r, cy + r), fill=c[:3] + (a,))
    img = Image.alpha_composite(img, blobs).convert("RGB")
    d = ImageDraw.Draw(img)

    d.text((20, 16), "聆听", font=font(25, True), fill=rgb(t["ink"]))
    d.text((20, 50), f"{name} · {note}", font=font(12), fill=rgb(t["ink_secondary"]))

    # 卡片（玻璃感：把 surface 叠到画布上）
    gx, gy, gw, gh = 18, 74, CELL_W - 36, 116
    card = over(t["surface"], rgb(t["canvas"]))
    d.rounded_rectangle((gx, gy, gx + gw, gy + gh), 18, fill=card, outline=over(t["hairline"], rgb(t["surface"])))
    d.text((gx + 14, gy + 12), "最近播放", font=font(14, True), fill=rgb(t["ink"]))
    d.text((gx + 14, gy + 36), "副标题走 secondary", font=font(11), fill=rgb(t["ink_secondary"]))
    d.text((gx + 14, gy + 54), "弱化信息 tertiary", font=font(11), fill=rgb(t["ink_tertiary"]))

    # 主按钮 + 标签
    bx, by = gx + 14, gy + gh - 42
    d.rounded_rectangle((bx, by, bx + 92, by + 30), 15, fill=rgb(t["accent"]))
    d.text((bx + 13, by + 7), "播放全部", font=font(12, True), fill=rgb(t["accent_on"]))
    d.rounded_rectangle((bx + 102, by, bx + 158, by + 30), 15, fill=rgb(t["tag_brand_bg"]))
    d.text((bx + 112, by + 7), "会员", font=font(11, True), fill=rgb(t["tag_brand_text"]))

    # 进度条（同色相明度递进）
    p0, p2 = rgb(t["progress_start"]), rgb(t["progress_end"])
    for i in range(150):
        k = i / 149
        col = tuple(round(p0[j] + (p2[j] - p0[j]) * k) for j in range(3))
        d.line((bx + 168 + i, by + 14, bx + 168 + i, by + 17), fill=col)

    # 色样
    sx, sy = 20, CELL_H - 18
    for i, key in enumerate(("blob_a", "blob_b", "blob_c", "blob_d")):
        d.rounded_rectangle((sx + i * 26, sy, sx + i * 26 + 22, sy + 7), 3, fill=rgb(t[key]))
    for i, key in enumerate(("accent", "accent_soft", "ink_tertiary")):
        d.rounded_rectangle((sx + 118 + i * 26, sy, sx + 118 + i * 26 + 22, sy + 7), 3, fill=rgb(t[key]))
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tokens", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--theme-filter", default="")
    args = ap.parse_args()

    data = json.load(open(args.tokens, encoding="utf-8"))
    themes = [x for x in ORDER if not args.theme_filter or x[0] in args.theme_filter.split(",")]

    width = MARGIN * 2 + CELL_W * 2 + GAP
    height = HEADER + MARGIN + len(themes) * CELL_H + (len(themes) - 1) * GAP + MARGIN
    page = Image.new("RGB", (width, height), "#eceef0")
    d = ImageDraw.Draw(page)
    d.text((MARGIN, 20), "Aurora · 5 套主题 × 亮/暗", font=font(21, True), fill="#14171a")
    d.text((MARGIN, 50), "左：浅色    右：深色    文字与控件对比度全部通过 WCAG 闸门",
           font=font(12), fill="#5a6066")

    for i, (slug, name, note) in enumerate(themes):
        y = HEADER + MARGIN + i * (CELL_H + GAP)
        page.paste(cell(data[slug]["light"], name, note), (MARGIN, y))
        page.paste(cell(data[slug]["dark"], name, note), (MARGIN + CELL_W + GAP, y))

    page.save(args.out)
    print("written", args.out, page.size)


if __name__ == "__main__":
    main()