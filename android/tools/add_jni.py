# -*- coding: utf-8 -*-
"""把 jni/<abi>/*.so 塞进 APK。manifest 里 extractNativeLibs=true，安装时会解到
nativeLibraryDir，所以 .so 可以 DEFLATE 压缩（体积小一半）；resources.arsc 仍必须 STORED。"""
import os
import sys
import zipfile

src_apk, jni_dir, out_apk = sys.argv[1], sys.argv[2], sys.argv[3]

items = []
for root, dirs, files in os.walk(jni_dir):
    for f in files:
        p = os.path.join(root, f)
        rel = os.path.relpath(p, jni_dir).replace("\\", "/")
        items.append(("lib/" + rel, p))

with zipfile.ZipFile(src_apk, "r") as zin, zipfile.ZipFile(out_apk, "w", zipfile.ZIP_DEFLATED) as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)
        zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        want_store = info.filename == "resources.arsc"
        zi.compress_type = zipfile.ZIP_STORED if want_store else zipfile.ZIP_DEFLATED
        zi.external_attr = info.external_attr
        zout.writestr(zi, data)
    for arc, path in items:
        zi = zipfile.ZipInfo(arc, date_time=(2026, 11, 21, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        with open(path, "rb") as f:
            zout.writestr(zi, f.read())
        print("add", arc, os.path.getsize(path))
print("jni injected ->", out_apk)
