# -*- coding: utf-8 -*-
"""生成各密度 launcher 图标（仓库里只存脚本，PNG 由本脚本产出）。

用法： py -3 -m pip install pillow
      py -3 tools/make_launcher_icons.py
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def draw(size):
    S = size * 8
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([8, 8, S - 8, S - 8], radius=int(S * 0.22), fill=255)
    tile = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    td = ImageDraw.Draw(tile)
    top, bottom = (58, 186, 146), (22, 104, 80)
    for y in range(S):
        k = y / (S - 1)
        td.line([(0, y), (S, y)],
                fill=tuple(int(top[i] * (1 - k) + bottom[i] * k) for i in range(3)) + (255,))
    img.paste(tile, (0, 0), mask)
    d.rounded_rectangle([8, 8, S - 8, S - 8], radius=int(S * 0.22), outline=(127, 224, 189, 120), width=10)
    W = (255, 255, 255, 255)
    d.rounded_rectangle([S * 0.43, S * 0.20, S * 0.57, S * 0.52], radius=int(S * 0.03), fill=W)
    d.polygon([(S * 0.30, S * 0.50), (S * 0.70, S * 0.50), (S * 0.50, S * 0.75)], fill=W)
    d.rounded_rectangle([S * 0.26, S * 0.80, S * 0.74, S * 0.86], radius=int(S * 0.03), fill=W)
    return img.resize((size, size), Image.LANCZOS)


for dens, px in SIZES.items():
    out = os.path.join(HERE, "res", "mipmap-" + dens)
    os.makedirs(out, exist_ok=True)
    draw(px).save(os.path.join(out, "ic_launcher.png"))
    print("wrote", dens, px)
