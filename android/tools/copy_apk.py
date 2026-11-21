# -*- coding: utf-8 -*-
"""把签名后的 APK 复制出中文文件名副本。

单独用 Python 处理中文文件名，避免 powershell.exe 按 ANSI 读取脚本时
把中文路径解码成乱码（历史问题：dist\\鐤鹃涓嬭浇.apk）。
用法: py -3 copy_apk.py <JifengDownload.apk 路径>
"""
import os
import shutil
import sys

src = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
dist = os.path.normpath(os.path.join(here, "..", "dist"))
os.makedirs(dist, exist_ok=True)

dst = os.path.join(dist, "疾风下载.apk")
shutil.copyfile(src, dst)

# 清理历史遗留的乱码副本（非预期的中文名 apk）。
expected = {"JifengDownload.apk", "疾风下载.apk"}
for name in os.listdir(dist):
    if name.lower().endswith(".apk") and name not in expected:
        try:
            os.remove(os.path.join(dist, name))
            print("removed stale:", name)
        except OSError:
            pass

print("copied ->", dst, os.path.getsize(dst), "bytes")
