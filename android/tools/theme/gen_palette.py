#!/usr/bin/env python
"""Aurora 主题调色板生成器。

规则来自 color-system skill：
  * 中性色族带色相，冷暖跟品牌色走（永远不出现纯灰）
  * 色度随亮度收敛：L≈0.58 处最高，越接近白/黑越低（避免荧光色）
  * 每个主题只有一个品牌色相；多色相只出现在"分类色/标签色"这类语义位
  * 亮/暗两套完整 token，暗色不是反色：背景 L≈0.145、卡片比背景更亮、品牌色提亮收彩度
  * 所有文字/控件配对过 WCAG 对比度闸门，不过就报错退出

产出：
  res/values/colors_theme_<slug>.xml        浅色 token
  res/values-night/colors_theme_<slug>.xml  深色 token
  res/values/themes.xml                     5 套主题样式（浅色）
  res/values-night/themes.xml               5 套主题样式（深色）
  res/values/attrs.xml                      主题属性声明

用法：
  python tools/theme/gen_palette.py --out android/app/src/main/res [--css] [--png]
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

# ------------------------------------------------------------------ 色彩数学


def oklch_to_linear(L, C, H):
    h = math.radians(H)
    a, b = C * math.cos(h), C * math.sin(h)
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_**3, m_**3, s_**3
    return (
        +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )


def gamma(x):
    x = 0.0 if x < 0 else 1.0 if x > 1 else x
    return 12.92 * x if x <= 0.0031308 else 1.055 * (x ** (1 / 2.4)) - 0.055


def in_gamut(rgb, eps=1e-4):
    return all(-eps <= v <= 1 + eps for v in rgb)


def oklch(L, C, H):
    """OKLCH → (r,g,b)；超色域时按色度二分回退到边界。"""
    lo, hi = 0.0, C
    rgb = oklch_to_linear(L, C, H)
    if not in_gamut(rgb):
        for _ in range(22):
            mid = (lo + hi) / 2
            if in_gamut(oklch_to_linear(L, mid, H)):
                lo = mid
            else:
                hi = mid
        rgb = oklch_to_linear(L, lo, H)
    return tuple(round(gamma(v) * 255) for v in rgb)


def hex_of(rgb):
    return "#%02X%02X%02X" % tuple(rgb[:3])


def with_alpha(rgb, alpha):
    return "#%02X%02X%02X%02X" % (round(alpha * 255), rgb[0], rgb[1], rgb[2])


def luminance(rgb):
    def lin(c):
        c /= 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def composite(fg, alpha, bg):
    return tuple(round(f * alpha + b * (1 - alpha)) for f, b in zip(fg[:3], bg[:3]))


# ------------------------------------------------------------------ 主题定义


THEMES = [
    dict(
        slug="aurora", name="极光", style="Aurora",
        neutral_h=278, neutral_c=0.010,
        accent_h=277, accent_c=0.150, accent_l=0.510,
        # 色斑是品牌色相的邻色（±60° 以内），不是随机彩虹
        blobs=(277, 312, 243, 197),
        note="冷中性 + 靛紫，App 默认身份色",
    ),
    dict(
        slug="ember", name="余烬", style="Ember",
        neutral_h=55, neutral_c=0.013,
        accent_h=42, accent_c=0.128, accent_l=0.545,
        blobs=(36, 58, 20, 84),
        note="暖砂中性 + 赤陶，夜间与阅读更柔",
    ),
    dict(
        slug="sage", name="苔原", style="Sage",
        neutral_h=158, neutral_c=0.011,
        accent_h=158, accent_c=0.108, accent_l=0.470,
        blobs=(158, 184, 132, 196),
        note="青灰中性 + 鼠尾草绿，克制耐看",
    ),
    dict(
        slug="cobalt", name="深海", style="Cobalt",
        neutral_h=245, neutral_c=0.009,
        accent_h=258, accent_c=0.170, accent_l=0.495,
        blobs=(258, 222, 285, 205),
        note="冷中性 + 钴蓝，与原配色最接近",
    ),
    dict(
        slug="noir", name="墨色", style="Noir",
        neutral_h=250, neutral_c=0.004,
        accent_h=250, accent_c=0.018, accent_l=0.360,
        blobs=(250, 250, 250, 250),
        note="近单色，只靠明度分层，最有编辑感",
    ),
]

GENRE_HUES = [22, 48, 74, 100, 126, 152, 178, 204, 230, 256, 282, 308]

# 色斑色度：浅色下是柔光，深色下是低亮度的辉光
BLOB_C_LIGHT = 0.072
BLOB_C_DARK = 0.070
BLOB_L_LIGHT = 0.775
BLOB_L_DARK = 0.615


def accent_chroma(c0, L):
    """色度曲线：中间高、两端低。"""
    d = (L - 0.58) / 0.42
    return c0 * max(0.32, 1 - d * d)


def light(t):
    nh, nc = t["neutral_h"], t["neutral_c"]
    ah, ac = t["accent_h"], t["accent_c"]

    canvas = oklch(0.966, nc * 0.55, nh)
    surface = oklch(0.998, nc * 0.25, nh)
    ink = oklch(0.215, nc * 1.5, nh)
    accent = oklch(t["accent_l"], ac, ah)

    tok = {
        "canvas": canvas,
        "surface": surface,
        "surface_sunken": oklch(0.947, nc * 0.85, nh),
        "surface_pressed": oklch(0.913, nc * 0.95, nh),
        "hero_placeholder": oklch(0.920, nc, nh),
        "ink": ink,
        "ink_secondary": oklch(0.487, nc * 1.2, nh),
        "ink_tertiary": oklch(0.545, nc * 1.1, nh),
        "ink_inverse": oklch(0.996, nc * 0.3, nh),
        "accent": accent,
        "accent_on": oklch(0.995, ac * 0.15, ah),
        "accent_soft": oklch(0.947, ac * 0.30, ah),
        "accent_deep": oklch(0.430, ac * 0.95, ah),
        "accent_cyan": oklch(0.700, 0.105, 210),
        "hairline": oklch(0.905, nc * 1.2, nh),
        "hairline_dark": with_alpha((255, 255, 255), 0.12),
        "scrim": with_alpha((0, 0, 0), 0.55),
        "nav_pill": oklch(0.930, nc * 1.1, nh),
        # 深色沉浸页（MV / 正在播放）：永远是深底浅字，但跟主题同一个色相偏置
        "dark_canvas": oklch(0.170, nc * 2.0, nh),
        "on_dark": oklch(0.985, nc * 0.4, nh),
        "on_dark_secondary": oklch(0.775, nc * 0.9, nh),
        "dark_pill": with_alpha((255, 255, 255), 0.14),
        "like": oklch(0.600, 0.180, 12),
        "progress_start": oklch(0.560, accent_chroma(ac, 0.56), ah),
        "progress_mid": oklch(t["accent_l"], accent_chroma(ac, t["accent_l"]), ah),
        "progress_end": oklch(0.635, accent_chroma(ac, 0.635), ah),
        # 标签
        "tag_brand_bg": oklch(0.946, ac * 0.30, ah),
        "tag_brand_text": oklch(0.470, ac * 0.95, ah),
        "tag_warm_bg": oklch(0.960, 0.035, 62),
        "tag_warm_text": oklch(0.500, 0.115, 55),
        "tag_neutral_bg": oklch(0.950, nc * 1.1, nh),
        "success": oklch(0.520, 0.115, 152),
        "warning": oklch(0.560, 0.125, 70),
        "danger": oklch(0.545, 0.150, 25),
    }
    blob_c = BLOB_C_LIGHT * (t["neutral_c"] / 0.010 if t["slug"] == "noir" else 1.0)
    for i, h in enumerate(t["blobs"]):
        tok[f"blob_{'abcd'[i]}"] = oklch(BLOB_L_LIGHT, blob_c, h)
    return tok


def dark(t):
    nh, nc = t["neutral_h"], t["neutral_c"]
    ah, ac = t["accent_h"], t["accent_c"]

    # 深色不是反色：背景 L≈0.225 的带色深灰（#1E1E22 一带），卡片比背景更亮
    canvas = oklch(0.225, nc * 1.9, nh)
    surface = oklch(0.275, nc * 1.9, nh)
    ink = oklch(0.945, nc * 1.2, nh)
    accent = oklch(0.735, accent_chroma(ac * 1.15, 0.735), ah)

    tok = {
        "canvas": canvas,
        "surface": surface,
        "surface_sunken": oklch(0.198, nc * 1.7, nh),
        "surface_pressed": oklch(0.345, nc * 1.9, nh),
        "hero_placeholder": oklch(0.330, nc * 1.9, nh),
        "ink": ink,
        "ink_secondary": oklch(0.740, nc * 1.0, nh),
        "ink_tertiary": oklch(0.660, nc * 0.9, nh),
        "ink_inverse": oklch(0.230, nc * 1.7, nh),
        "accent": accent,
        "accent_on": oklch(0.225, nc * 1.8, ah),
        "accent_soft": oklch(0.330, ac * 0.45, ah),
        "accent_deep": oklch(0.805, accent_chroma(ac, 0.805), ah),
        "accent_cyan": oklch(0.760, 0.100, 210),
        "hairline": with_alpha((255, 255, 255), 0.13),
        "hairline_dark": with_alpha((255, 255, 255), 0.18),
        "scrim": with_alpha((0, 0, 0), 0.72),
        "nav_pill": oklch(0.335, nc * 1.9, nh),
        "dark_canvas": oklch(0.175, nc * 1.9, nh),
        "on_dark": oklch(0.985, nc * 0.4, nh),
        "on_dark_secondary": oklch(0.780, nc * 0.9, nh),
        "dark_pill": with_alpha((255, 255, 255), 0.16),
        "like": oklch(0.700, 0.165, 12),
        "progress_start": oklch(0.760, accent_chroma(ac * 1.1, 0.76), ah),
        "progress_mid": oklch(0.700, accent_chroma(ac * 1.15, 0.70), ah),
        "progress_end": oklch(0.640, accent_chroma(ac * 1.15, 0.64), ah),
        "tag_brand_bg": oklch(0.345, ac * 0.48, ah),
        "tag_brand_text": oklch(0.815, accent_chroma(ac * 1.0, 0.815), ah),
        "tag_warm_bg": oklch(0.350, 0.048, 62),
        "tag_warm_text": oklch(0.800, 0.105, 62),
        "tag_neutral_bg": oklch(0.340, nc * 1.9, nh),
        "success": oklch(0.740, 0.115, 152),
        "warning": oklch(0.790, 0.120, 70),
        "danger": oklch(0.700, 0.145, 25),
    }
    blob_c = BLOB_C_DARK * (t["neutral_c"] / 0.010 if t["slug"] == "noir" else 1.0)
    for i, h in enumerate(t["blobs"]):
        tok[f"blob_{'abcd'[i]}"] = oklch(BLOB_L_DARK, blob_c, h)
    return tok


# 语义 token → 属性名
ATTR = {
    "canvas": "auCanvas",
    "surface": "auSurface",
    "surface_sunken": "auSurfaceSunken",
    "surface_pressed": "auSurfacePressed",
    "hero_placeholder": "auHeroPlaceholder",
    "ink": "auInk",
    "ink_secondary": "auInkSecondary",
    "ink_tertiary": "auInkTertiary",
    "ink_inverse": "auInkInverse",
    "accent": "auAccent",
    "accent_on": "auAccentOn",
    "accent_soft": "auAccentSoft",
    "accent_deep": "auAccentDeep",
    "accent_cyan": "auAccentCyan",
    "hairline": "auHairline",
    "hairline_dark": "auHairlineDark",
    "scrim": "auScrim",
    "nav_pill": "auNavPill",
    "dark_canvas": "auDarkCanvas",
    "on_dark": "auOnDark",
    "on_dark_secondary": "auOnDarkSecondary",
    "dark_pill": "auDarkPill",
    "like": "auLike",
    "progress_start": "auProgressStart",
    "progress_mid": "auProgressMid",
    "progress_end": "auProgressEnd",
    "tag_brand_bg": "auTagBrandBg",
    "tag_brand_text": "auTagBrandText",
    "tag_warm_bg": "auTagWarmBg",
    "tag_warm_text": "auTagWarmText",
    "tag_neutral_bg": "auTagNeutralBg",
    "success": "auSuccess",
    "warning": "auWarning",
    "danger": "auDanger",
    "blob_a": "auBlobA",
    "blob_b": "auBlobB",
    "blob_c": "auBlobC",
    "blob_d": "auBlobD",
}

# 对比度闸门：(说明, 前景 token, 背景 token, 最低比)
CHECKS = [
    ("正文 ink / canvas", "ink", "canvas", 7.0),
    ("正文 ink / surface", "ink", "surface", 7.0),
    ("次级 ink_secondary / canvas", "ink_secondary", "canvas", 4.5),
    ("次级 ink_secondary / surface", "ink_secondary", "surface", 4.5),
    ("弱化 ink_tertiary / canvas（仅大字/图标）", "ink_tertiary", "canvas", 3.0),
    ("主按钮 accent_on / accent", "accent_on", "accent", 4.5),
    ("链接 accent / canvas", "accent", "canvas", 3.0),
    ("链接 accent / surface", "accent", "surface", 3.0),
    ("标签 tag_brand_text / tag_brand_bg", "tag_brand_text", "tag_brand_bg", 4.5),
    ("标签 tag_warm_text / tag_warm_bg", "tag_warm_text", "tag_warm_bg", 4.5),
    ("深色页 on_dark / dark_canvas", "on_dark", "dark_canvas", 7.0),
    ("深色页 on_dark_secondary / dark_canvas", "on_dark_secondary", "dark_canvas", 4.5),
    ("危险色 danger / canvas", "danger", "canvas", 3.0),
    ("成功色 success / canvas", "success", "canvas", 3.0),
    ("分隔线 hairline / canvas", "hairline", "canvas", 1.15),
    ("卡片边线 surface_pressed / surface", "surface_pressed", "surface", 1.15),
]


def parse_hex(v):
    h = v.lstrip("#")
    if len(h) == 8:
        return (int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16)), int(h[0:2], 16) / 255.0
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)), 1.0


def resolve(v, base):
    if isinstance(v, tuple):
        return v
    rgb, a = parse_hex(v)
    return rgb if a >= 1.0 else composite(rgb, a, base)


def hex_value(v):
    return v if isinstance(v, str) else hex_of(v)


# ------------------------------------------------------------------ 输出


def emit_colors(t, tok, night):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f'<!-- 主题「{t["name"]}」{t["slug"]} · {"深色" if night else "浅色"}'
        f'（tools/theme/gen_palette.py 生成，勿手改） -->',
        "<resources>",
    ]
    for key, value in tok.items():
        lines.append(f'    <color name="theme_{t["slug"]}_{key}">{hex_value(value)}</color>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def emit_style(t, night, base=False):
    parent = "Theme.Material3.DayNight.NoActionBar" if base else "Theme.Aurora"
    name = "Theme.Aurora" if base else f'Theme.Aurora.{t["style"]}'
    slug = t["slug"]
    out = [f'    <style name="{name}" parent="{parent}">']
    # Material3 那套属性也跟着当前主题走，否则 Material 控件会留在默认紫色
    out += [
        f'        <item name="colorPrimary">@color/theme_{slug}_accent</item>',
        f'        <item name="colorOnPrimary">@color/theme_{slug}_accent_on</item>',
        f'        <item name="colorSecondary">@color/theme_{slug}_accent</item>',
        f'        <item name="colorOnSecondary">@color/theme_{slug}_accent_on</item>',
        f'        <item name="colorSurface">@color/theme_{slug}_surface</item>',
        f'        <item name="colorOnSurface">@color/theme_{slug}_ink</item>',
        f'        <item name="colorSurfaceVariant">@color/theme_{slug}_surface_sunken</item>',
        f'        <item name="colorOnSurfaceVariant">@color/theme_{slug}_ink_secondary</item>',
        f'        <item name="colorOutline">@color/theme_{slug}_hairline</item>',
        f'        <item name="colorError">@color/theme_{slug}_danger</item>',
        f'        <item name="android:textColorPrimary">@color/theme_{slug}_ink</item>',
        f'        <item name="android:textColorSecondary">@color/theme_{slug}_ink_secondary</item>',
    ]
    if base:
        out += [
            f'        <item name="android:windowBackground">@color/theme_{slug}_canvas</item>',
            '        <item name="android:statusBarColor">@android:color/transparent</item>',
            '        <item name="android:navigationBarColor">@android:color/transparent</item>',
            f'        <item name="android:windowLightStatusBar">{"false" if night else "true"}</item>',
            '        <item name="android:windowLightNavigationBar" tools:targetApi="27">'
            f'{"false" if night else "true"}</item>',
            '        <item name="android:windowLayoutInDisplayCutoutMode" tools:targetApi="28">shortEdges</item>',
            '        <item name="android:forceDarkAllowed" tools:targetApi="29">false</item>',
            '        <item name="android:windowAnimationStyle">@style/Animation.Aurora.Window</item>',
        ]
    for key, attr in ATTR.items():
        out.append(f'        <item name="{attr}">@color/theme_{slug}_{key}</item>')
    out.append("    </style>")
    return "\n".join(out)


def emit_themes(night):
    body = "\n\n".join(emit_style(t, night, base=(t["slug"] == "aurora")) for t in THEMES)
    return (
        '<resources xmlns:tools="http://schemas.android.com/tools">\n'
        "    <!--\n"
        "      5 套主题 × 亮/暗。每套只换调色板，组件全部引用 ?attr/au*，\n"
        "      所以换主题不需要改任何布局或代码。\n"
        f'      本文件是{"深色" if night else "浅色"}版本；另一半在 '
        f'values-{"night" if night else "night"} 对应位置。\n'
        "    -->\n\n"
        f"{body}\n</resources>\n"
    )


def emit_attrs():
    decl = "\n".join(f'    <attr name="{name}" format="color" />' for name in ATTR.values())
    return f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
      主题调色板属性。组件、布局、drawable 只准引用 ?attr/au*，
      不准直接引用 @color/theme_* —— 这是"换主题不用改 UI"的前提。
    -->
{decl}

    <!--
      排版只有五档：Display / Title / Section / RowTitle / Body / Caption。
      字号越大字距越紧，小字反而放松，否则挤成一团。
    -->
</resources>
"""


def emit_genre(night):
    colors = [
        oklch(0.560, 0.150, h) if night else oklch(0.700, 0.130, h) for h in GENRE_HUES
    ]
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- 12 个分类色相（24° 一档，明度/色度固定）：给分类方块当"彩色玻璃"用 -->',
        "<resources>",
    ]
    for i, rgb in enumerate(colors, start=1):
        lines.append(f'    <color name="genre_{i}">{hex_of(rgb)}</color>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def report(results):
    fails = 0
    for theme, mode, label, ratio, minimum, ok in results:
        if not ok:
            fails += 1
            print(f"FAIL {theme:<7} {mode:<5} {label:<42} {ratio:5.2f} < {minimum}")
    print(f'{"ALL PASS" if fails == 0 else f"{fails} FAILURES"}  '
          f'({len(results)} 组配对)')
    return fails


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="app/src/main/res")
    ap.add_argument("--json", help="额外导出 token JSON")
    args = ap.parse_args(argv)

    res = args.out
    os.makedirs(os.path.join(res, "values"), exist_ok=True)
    os.makedirs(os.path.join(res, "values-night"), exist_ok=True)

    results = []
    exported = {}
    for t in THEMES:
        for night in (False, True):
            tok = dark(t) if night else light(t)
            folder = "values-night" if night else "values"
            with open(os.path.join(res, folder, f'colors_theme_{t["slug"]}.xml'),
                      "w", encoding="utf-8") as fh:
                fh.write(emit_colors(t, tok, night))
            base = resolve(tok["canvas"], (255, 255, 255))
            for label, fg, bg, minimum in CHECKS:
                bg_rgb = resolve(tok[bg], base)
                fg_rgb = resolve(tok[fg], bg_rgb)
                ratio = contrast(fg_rgb, bg_rgb)
                results.append((t["slug"], "dark" if night else "light",
                                label, ratio, minimum, ratio >= minimum))
            exported.setdefault(t["slug"], {})["dark" if night else "light"] = {
                k: hex_value(v) for k, v in tok.items()
            }

    with open(os.path.join(res, "values", "themes.xml"), "w", encoding="utf-8") as fh:
        fh.write(emit_themes(night=False))
    with open(os.path.join(res, "values-night", "themes.xml"), "w", encoding="utf-8") as fh:
        fh.write(emit_themes(night=True))
    with open(os.path.join(res, "values", "attrs.xml"), "w", encoding="utf-8") as fh:
        fh.write(emit_attrs())
    with open(os.path.join(res, "values", "colors_genre.xml"), "w", encoding="utf-8") as fh:
        fh.write(emit_genre(night=False))
    with open(os.path.join(res, "values-night", "colors_genre.xml"), "w", encoding="utf-8") as fh:
        fh.write(emit_genre(night=True))

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(exported, fh, ensure_ascii=False, indent=2)

    for t in THEMES:
        print(f'  {t["slug"]:<7} {t["name"]}  {t["note"]}')
    return 1 if report(results) else 0


if __name__ == "__main__":
    sys.exit(main())