import hashlib
import os
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_ftp_out")
shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT, exist_ok=True)

m = engine.Manager(cfg={"dest_dir": OUT})
t = m.add("ftp://127.0.0.1:2201/mix.bin")
start = time.time()
while time.time() - start < 60:
    if t.status in ("done", "error", "canceled"):
        break
    time.sleep(0.5)
print("status", t.status, "err", t.error)
print("total", t.total, "downloaded", t.downloaded, "secs %.2f" % (time.time() - start))
p = t.path
print("path", p, "exists", os.path.exists(p))
if os.path.exists(p):
    h = hashlib.md5(open(p, "rb").read()).hexdigest()
    print("md5", h, "size", os.path.getsize(p))
m.shutdown() if hasattr(m, "shutdown") else None
