# -*- coding: utf-8 -*-
"""生成应用图标：深底 + 青色圆角方块 + 白色下载箭头。"""
from PIL import Image, ImageDraw

S = 1024
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 圆角底板 + 竖向渐变
tile = Image.new("RGBA", (S, S), (0, 0, 0, 0))
td = ImageDraw.Draw(tile)
top, bottom = (58, 186, 146), (22, 104, 80)
for y in range(S):
    k = y / (S - 1)
    c = tuple(int(top[i] * (1 - k) + bottom[i] * k) for i in range(3)) + (255,)
    td.line([(0, y), (S, y)], fill=c)
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([8, 8, S - 8, S - 8], radius=int(S * 0.22), fill=255)
img.paste(tile, (0, 0), mask)

# 内描边
d.rounded_rectangle([8, 8, S - 8, S - 8], radius=int(S * 0.22), outline=(127, 224, 189, 120), width=10)

W = (255, 255, 255, 255)
# 箭杆
d.rounded_rectangle([S * 0.43, S * 0.20, S * 0.57, S * 0.52], radius=int(S * 0.03), fill=W)
# 箭头
d.polygon([(S * 0.30, S * 0.50), (S * 0.70, S * 0.50), (S * 0.50, S * 0.75)], fill=W)
# 托盘
d.rounded_rectangle([S * 0.26, S * 0.80, S * 0.74, S * 0.86], radius=int(S * 0.03), fill=W)

img.save("app-icon.png")
img.save("app.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("icon written")
