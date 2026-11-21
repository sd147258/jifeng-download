# -*- coding: utf-8 -*-
"""Windows 端 WMV 落盘名验证：/movie.wmv、/noext（只有 ASF 魔数）、/ctonly（只有 Content-Type）。"""
import os
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_wmv_out")
shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT, exist_ok=True)

BASE = "http://127.0.0.1:8780"
m = engine.Manager(cfg={"dest_dir": OUT})
cases = ["/movie.wmv", "/noext", "/ctonly"]
for route in cases:
    t = m.add(BASE + route)
    start = time.time()
    while time.time() - start < 60:
        if t.status in ("done", "error", "canceled"):
            break
        time.sleep(0.3)
    name = os.path.basename(getattr(t, "path", "") or "")
    ok = name.lower().endswith(".wmv")
    size = os.path.getsize(t.path) if t.path and os.path.exists(t.path) else 0
    print("%-12s status=%s name=%s size=%d ext_ok=%s secs=%.2f err=%s"
          % (route, t.status, name, size, ok, time.time() - start, t.error))
print("DONE")
