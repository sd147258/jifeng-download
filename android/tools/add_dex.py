# -*- coding: utf-8 -*-
"""把 classes.dex 注入 aapt2 link 产出的 APK（APK 本质是 zip）。"""
import shutil
import sys
import zipfile

base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("dex injected ->", out)
