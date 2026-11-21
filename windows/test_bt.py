"""桌面端 BT 模块自测：走本机 tracker + seeder，下完和源文件比 SHA1。
用法：先 py -3 ..\android\test\bt_swarm.py，再 py -3 test_bt.py
"""
import hashlib
import os
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bt  # noqa: E402

MAGNET_FILE = os.path.join(HERE, "..", "android", "test", "magnet.txt")
SRC = os.path.join(HERE, "..", "android", "test", "mov_bbb.mp4")


def main():
    magnet = open(MAGNET_FILE, encoding="utf-8").read().strip()
    m = bt.parse_magnet(magnet)
    print("magnet:", m["hash_hex"], m["name"], m["trackers"])
    peers = bt.get_peers(m, m["length"] or 0)
    print("peers:", peers)
    if not peers:
        print("RESULT no-peer")
        return 1

    info = None
    for addr in peers:
        p = bt.Peer(addr, m["info_hash"])
        try:
            p.open()
            info = p.fetch_metadata()
            print("metadata:", info.get(b"name"), int(info.get(b"length", 0)),
                  "piece", int(info.get(b"piece length", 0)))
            break
        except Exception as e:
            print("取元数据失败:", e)
        finally:
            p.close()
    if info is None:
        print("RESULT no-metadata")
        return 1

    outdir = tempfile.mkdtemp(prefix="jfbt_")
    path = os.path.join(outdir, "out.mp4")
    last = [0.0]
    t0 = time.time()

    def progress(done, total):
        now = time.time()
        if now - last[0] >= 0.5 or done >= total:
            last[0] = now
            print("  %.2f%% %d/%d" % (done * 100.0 / max(1, total), done, total))

    written, name, complete = bt.download(m, info, path, on_progress=progress,
                                          log=lambda s: print("  " + s))
    cost = time.time() - t0
    size = os.path.getsize(path) if os.path.exists(path) else 0
    same = False
    if os.path.exists(SRC) and size == os.path.getsize(SRC):
        same = hashlib.sha1(open(path, "rb").read()).hexdigest() == \
            hashlib.sha1(open(SRC, "rb").read()).hexdigest()
    print("RESULT complete=%s size=%d cost=%.1fs same=%s" % (complete, size, cost, same))
    return 0 if complete and same else 1


if __name__ == "__main__":
    sys.exit(main())
