# -*- coding: utf-8 -*-
"""Windows 端连接复用（动态分段）对照测试：固定 8 段，服务端第 8 个连接故意很慢。

用法：
    py -3 test_rebalance.py            # 开连接复用
    set JF_NO_TAIL=1 && py -3 ...      # 关连接复用（对照）
"""
import hashlib
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine  # noqa: E402

URL = "http://127.0.0.1:8770/mix.bin"
SRC = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    "..", "android", "test", "mix.bin"))
out = tempfile.mkdtemp(prefix="jf_reb_")
mgr = engine.Manager({"segments": 8, "dest_dir": out, "max_active": 1})
t0 = time.time()
task = mgr.add(URL, "mix.bin", out)
for _ in range(200):
    time.sleep(0.2)
    if task.status in ("done", "error", "canceled"):
        break
el = time.time() - t0
path = os.path.join(out, task.filename)
md5 = hashlib.md5(open(path, "rb").read()).hexdigest() if os.path.exists(path) else "missing"
src = hashlib.md5(open(SRC, "rb").read()).hexdigest() if os.path.exists(SRC) else "n/a"
print("TAIL=%s status=%s elapsed=%.2fs md5=%s src=%s match=%s segments=%d size=%d"
      % ("off" if os.environ.get("JF_NO_TAIL") else "on", task.status, el, md5, src,
         md5 == src, len(task.segments), os.path.getsize(path) if os.path.exists(path) else -1))
