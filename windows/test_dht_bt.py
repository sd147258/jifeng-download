import hashlib
import os
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dht
import engine

dht.EXTRA.append(("127.0.0.1", 6881))
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_dht_out")
shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT, exist_ok=True)

magnet = "magnet:?xt=urn:btih:4f2e83c2674b2f36a880368f1415b9ff997ab848&dn=mov_bbb.mp4"
mgr = engine.Manager(cfg={"dest_dir": OUT})
t = mgr.add(magnet)
start = time.time()
while time.time() - start < 120:
    if t.status in ("done", "error", "canceled"):
        break
    time.sleep(0.5)
print("status", t.status, "err", t.error)
print("total", t.total, "downloaded", t.downloaded, "peers", t.bt_peers, "secs %.1f" % (time.time() - start))
if os.path.exists(t.path):
    print("size", os.path.getsize(t.path), "md5", hashlib.md5(open(t.path, "rb").read()).hexdigest())
