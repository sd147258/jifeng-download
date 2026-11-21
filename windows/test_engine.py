# -*- coding: utf-8 -*-
"""下载引擎测试：本地 HTTP 服务（支持 Range）+ 完整性校验 + 正确性 / 防盗链 / m3u8 / 限速 / 续传。"""
import concurrent.futures
import hashlib
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine

SIZE = 12 * 1024 * 1024
DATA = bytes(((i * 131 + (i >> 8) * 17) % 251) for i in range(SIZE))

SMALL_SIZE = 2 * 1024 * 1024
DATA_SMALL = bytes(((i * 73 + (i >> 6) * 11) % 251) for i in range(SMALL_SIZE))

MED_SIZE = 20 * 1024 * 1024
DATA_MED = bytes(((i * 31 + (i >> 5) * 19) % 251) for i in range(MED_SIZE))

SPEED_SIZE = 2 * 1024 * 1024
DATA_SPEED = bytes(((i * 41 + (i >> 7) * 23) % 251) for i in range(SPEED_SIZE))

FTYP_BODY = b"\x00\x00\x00\x18ftypmp42" + b"\x00" * (64 * 1024 - 12)


def ts_packets(n, seed=0):
    out = bytearray()
    for i in range(n):
        pkt = bytearray(188)
        pkt[0] = 0x47
        pkt[1] = 0x40
        pkt[2] = 0x00
        pkt[3] = 0x10
        for j in range(4, 188):
            pkt[j] = (seed + i * 188 + j) & 0xFF
        out += pkt
    return bytes(out)


TS_SEGS = [ts_packets(10, 1), ts_packets(10, 2), ts_packets(10, 3)]
TS_TOTAL = sum(len(s) for s in TS_SEGS)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    ranges_seen = []
    lock = threading.Lock()

    def log_message(self, *a):
        pass

    def _slow(self, data, step=64 * 1024, gap=0.12):
        for i in range(0, len(data), step):
            try:
                self.wfile.write(data[i:i + step])
                self.wfile.flush()
            except Exception:
                return
            time.sleep(gap)

    def _range_send(self, data, slow=True):
        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d+)-(\d*)", rng)
            start = int(m.group(1))
            end = int(m.group(2)) if m.group(2) else len(data) - 1
            end = min(end, len(data) - 1)
            with Handler.lock:
                Handler.ranges_seen.append((start, end))
            chunk = data[start:end + 1]
            self.send_response(206)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, len(data)))
            self.send_header("Content-Length", str(len(chunk)))
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            if slow:
                self._slow(chunk)
            else:
                self.wfile.write(chunk)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
        if slow:
            self._slow(data)
        else:
            self.wfile.write(data)

    def _throttle_send(self, data):
        """每连接人为限速的 Range 服务：用来验证多段并发吞吐高于单流。"""
        rng = self.headers.get("Range")
        start, end = 0, len(data) - 1
        status = 200
        if rng:
            m = re.match(r"bytes=(\d+)-(\d*)", rng)
            if m:
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else len(data) - 1
                end = min(end, len(data) - 1)
                status = 206
        chunk = data[start:end + 1]
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(chunk)))
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, len(data)))
        self.end_headers()
        step = 16 * 1024
        for i in range(0, len(chunk), step):
            try:
                self.wfile.write(chunk[i:i + step])
                self.wfile.flush()
            except Exception:
                return
            time.sleep(0.03)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path

        if path == "/norange.bin":
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(DATA)))
            self.end_headers()
            self.wfile.write(DATA)
            return
        if path == "/named":
            data = b"named payload" * 4096
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Content-Disposition",
                             "attachment; filename=\"fallback.bin\"; filename*=UTF-8''%E6%B5%8B%E8%AF%95.bin")
            self.end_headers()
            self.wfile.write(data)
            return
        if path == "/html":
            body = "<html><body>请先登录后再下载</body></html>".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/json":
            body = '{"code":401,"msg":"login required"}'.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/protected":
            ref = self.headers.get("Referer", "")
            if ref != self.server.base + "/page.html":
                self.send_error(403, "Forbidden")
                return
            self._range_send(DATA_SMALL, slow=False)
            return
        if path == "/short":
            body = DATA_SMALL[:100 * 1024]
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(1024 * 1024))  # 谎报 1MB，实际只发 100KB
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/noext":
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(len(FTYP_BODY)))
            self.end_headers()
            self.wfile.write(FTYP_BODY)
            return
        if path == "/htmlbody":
            body = "<html><body>防盗链拦截页 error</body></html>".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/m3u8/master.m3u8":
            body = ("#EXTM3U\n"
                    "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n"
                    "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nvariant.m3u8\n").encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/m3u8/low.m3u8":
            body = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\nseg0.ts\n#EXT-X-ENDLIST\n".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/m3u8/variant.m3u8":
            body = ("#EXTM3U\n#EXT-X-TARGETDURATION:2\n"
                    "#EXTINF:2.0,\nseg0.ts\n#EXTINF:2.0,\nseg1.ts\n#EXTINF:2.0,\nseg2.ts\n"
                    "#EXT-X-ENDLIST\n").encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/m3u8/fmp4.m3u8":
            body = ('#EXTM3U\n#EXT-X-MAP:URI="init.mp4"\n'
                    '#EXTINF:2.0,\nf1.mp4\n#EXTINF:2.0,\nf2.mp4\n#EXT-X-ENDLIST\n').encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/m3u8/init.mp4":
            body = b"\x00\x00\x00\x18ftypmp42init"
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path in ("/m3u8/f1.mp4", "/m3u8/f2.mp4"):
            body = b"segment-one" if path.endswith("f1.mp4") else b"segment-two"
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        m = re.match(r"/m3u8/seg(\d)\.ts$", path)
        if m:
            idx = int(m.group(1))
            body = TS_SEGS[idx]
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/small.bin":
            self._range_send(DATA_SMALL, slow=False)
            return
        if path == "/medium.bin":
            self._range_send(DATA_MED, slow=False)
            return
        if path == "/speed.bin":
            self._range_send(DATA_SPEED, slow=False)
            return
        if path == "/throttle.bin":
            self._throttle_send(DATA_SPEED)
            return
        if path == "/file.bin":
            self._range_send(DATA, slow=True)
            return
        self.send_error(404)


def wait(task, timeout=120, expect=("done", "error")):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if task.status in expect:
            return True
        time.sleep(0.2)
    return False


fails = []


def check(name, cond, extra=""):
    if cond:
        print("PASS", name)
    else:
        print("FAIL", name, extra)
        fails.append(name)


def md5_bytes(b):
    return hashlib.md5(b).hexdigest()


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = srv.server_address[1]
    srv.base = "http://127.0.0.1:%d" % port
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = srv.base
    tmp = tempfile.mkdtemp(prefix="jf_test_")
    cfg = {"dest_dir": tmp, "segments": 8}

    try:
        # 1 多线程分段
        Handler.ranges_seen.clear()
        m = engine.Manager(dict(cfg))
        t = m.add(base + "/file.bin", "seg.bin", tmp)
        check("task created", t is not None)
        ok = wait(t)
        check("multi-thread done", ok and t.status == "done", t.status + " " + t.error)
        check("multi-thread size", os.path.getsize(t.path) == SIZE, os.path.getsize(t.path) if os.path.exists(t.path) else -1)
        with open(t.path, "rb") as f:
            check("multi-thread content", md5_bytes(f.read()) == md5_bytes(DATA))
        check("used ranges", len(Handler.ranges_seen) >= 2, str(Handler.ranges_seen[:3]))
        check("no part file", not os.path.exists(t.part_path))
        check("segments counts", len(t.segments) >= 8, len(t.segments))  # 连接复用会继续二分，段数只增不减
        m.pause(t)

        # 2 暂停 / 续传
        Handler.ranges_seen.clear()
        m2 = engine.Manager(dict(cfg))
        t2 = m2.add(base + "/file.bin", "resume.bin", tmp)
        t0 = time.time()
        while time.time() - t0 < 20 and t2.status in ("pending", "preparing"):
            time.sleep(0.1)
        t0 = time.time()
        while time.time() - t0 < 20 and t2.downloaded < 1024 * 1024 and t2.status == "running":
            time.sleep(0.1)
        m2.pause(t2)
        ok = wait(t2, 15, ("paused", "error", "done"))
        check("paused", ok and t2.status in ("paused", "done"), t2.status)
        got = t2.downloaded
        check("pause kept bytes", got > 0, got)
        m2.resume(t2)
        ok = wait(t2)
        check("resume done", ok and t2.status == "done", t2.status + " " + t2.error)
        check("resume counter exact", t2.downloaded == t2.total, "%d / %d" % (t2.downloaded, t2.total))
        with open(t2.path, "rb") as f:
            check("resume content", md5_bytes(f.read()) == md5_bytes(DATA))

        # 3 不支持 Range 的服务器
        m3 = engine.Manager(dict(cfg))
        t3 = m3.add(base + "/norange.bin", "single.bin", tmp)
        ok = wait(t3)
        check("fallback done", ok and t3.status == "done", t3.status + " " + t3.error)
        check("fallback single seg", len(t3.segments) == 1 and not t3.supports_range, len(t3.segments))
        with open(t3.path, "rb") as f:
            check("fallback content", md5_bytes(f.read()) == md5_bytes(DATA))

        # 4 Content-Disposition 文件名
        m4 = engine.Manager(dict(cfg))
        t4 = m4.add(base + "/named", None, tmp)
        ok = wait(t4, 30)
        check("named done", ok and t4.status == "done", t4.status + " " + t4.error)
        check("named filename", t4.filename == "测试.bin", t4.filename)

        # 5 重启后续传
        m5 = engine.Manager(dict(cfg))
        t5 = m5.add(base + "/file.bin", "restart.bin", tmp)
        t0 = time.time()
        while time.time() - t0 < 20 and t5.status in ("pending", "preparing"):
            time.sleep(0.1)
        t0 = time.time()
        while time.time() - t0 < 20 and t5.downloaded < 512 * 1024 and t5.status == "running":
            time.sleep(0.1)
        m5.pause(t5)
        wait(t5, 10, ("paused", "done"))
        time.sleep(0.8)
        m6 = engine.Manager(dict(cfg))
        loaded = m6.load_existing(tmp)
        found = [x for x in loaded if x.filename == "restart.bin"]
        check("reload found task", len(found) == 1, len(loaded))
        if found:
            m6.resume(found[0])
            ok = wait(found[0])
            check("reload resume done", ok and found[0].status == "done", found[0].status + " " + found[0].error)
            with open(found[0].path, "rb") as f:
                check("reload content", md5_bytes(f.read()) == md5_bytes(DATA))

        # 6 取消清理
        m7 = engine.Manager(dict(cfg))
        t7 = m7.add(base + "/file.bin", "cancel.bin", tmp)
        time.sleep(1.0)
        m7.cancel(t7)
        time.sleep(1.5)
        check("cancel status", t7.status in ("canceled", "done"), t7.status)
        check("cancel removed file", not os.path.exists(t7.path))

        # ---------- 新增：下载正确性 ----------

        # 7 HTML 落地页被拒
        m8 = engine.Manager(dict(cfg))
        t8 = m8.add(base + "/html", None, tmp)
        ok = wait(t8, 30)
        check("html rejected", ok and t8.status == "error", t8.status + " " + t8.error)
        check("html reason chinese", "网页" in t8.error or "登录" in t8.error, t8.error)
        check("html no file", not os.path.exists(t8.path), t8.path)

        # 8 JSON 接口被拒
        m9 = engine.Manager(dict(cfg))
        t9 = m9.add(base + "/json", None, tmp)
        ok = wait(t9, 30)
        check("json rejected", ok and t9.status == "error", t9.status + " " + t9.error)
        check("json reason chinese", "接口" in t9.error or "登录" in t9.error, t9.error)
        check("json no file", not os.path.exists(t9.path), t9.path)

        # 9 防盗链：不带 Referer → 403；带 Referer → 200
        m10 = engine.Manager(dict(cfg))
        t10 = m10.add(base + "/protected", "noref.bin", tmp)
        ok = wait(t10, 30)
        check("403 without referer", ok and t10.status == "error" and "403" in t10.error, t10.status + " " + t10.error)
        check("403 reason hint referer", "Referer" in t10.error, t10.error)

        m11 = engine.Manager(dict(cfg))
        t11 = m11.add(base + "/protected", "withref.bin", tmp, referer=base + "/page.html")
        ok = wait(t11, 30)
        check("200 with referer", ok and t11.status == "done", t11.status + " " + t11.error)
        check("referer file size", os.path.exists(t11.path) and os.path.getsize(t11.path) == SMALL_SIZE,
              os.path.getsize(t11.path) if os.path.exists(t11.path) else -1)

        # 10 Content-Length 不符判错，保留续传文件
        m12 = engine.Manager(dict(cfg))
        t12 = m12.add(base + "/short", "short.bin", tmp)
        ok = wait(t12, 60)
        check("length mismatch error", ok and t12.status == "error", t12.status + " " + t12.error)
        check("mismatch keeps part", os.path.exists(t12.part_path), "part removed")
        check("mismatch reason", "大小校验" in t12.error or "未下完" in t12.error, t12.error)

        # 11 无扩展名靠 Content-Type 定扩展（video/mp4）
        m13 = engine.Manager(dict(cfg))
        t13 = m13.add(base + "/noext", None, tmp)
        ok = wait(t13, 30)
        check("content-type ext done", ok and t13.status == "done", t13.status + " " + t13.error)
        check("content-type ext mp4", t13.filename.lower().endswith(".mp4"), t13.filename)
        check("never txt", not t13.filename.lower().endswith(".txt"), t13.filename)

        # 12 文件头是 html → 判失败并删文件
        m14 = engine.Manager(dict(cfg))
        t14 = m14.add(base + "/htmlbody", "body.bin", tmp)
        ok = wait(t14, 30)
        check("html head rejected", ok and t14.status == "error", t14.status + " " + t14.error)
        check("html head file deleted", not os.path.exists(t14.path), t14.path)
        check("html head reason", "错误页" in t14.error or "网页" in t14.error, t14.error)

        # 13 m3u8：master 选最高带宽，TS 分片合并
        m15 = engine.Manager(dict(cfg))
        t15 = m15.add(base + "/m3u8/master.m3u8", None, tmp)
        ok = wait(t15, 60)
        check("m3u8 done", ok and t15.status == "done", t15.status + " " + t15.error)
        check("m3u8 segments count", t15.total_segments == 3, t15.total_segments)
        if t15.status == "done":
            check("m3u8 size", os.path.getsize(t15.path) == TS_TOTAL,
                  "%d / %d" % (os.path.getsize(t15.path) if os.path.exists(t15.path) else -1, TS_TOTAL))
            with open(t15.path, "rb") as f:
                head = f.read(4)
            check("m3u8 ts sync", head[0] == 0x47, head.hex())
            check("m3u8 ext", t15.filename.lower().endswith(".ts") or t15.filename.lower().endswith(".mp4"),
                  t15.filename)
            print("    m3u8 note:", t15.note or "(无)")

        # 13b fMP4（含 EXT-X-MAP）：init + 分片直拼 mp4
        m15b = engine.Manager(dict(cfg))
        t15b = m15b.add(base + "/m3u8/fmp4.m3u8", None, tmp)
        ok = wait(t15b, 60)
        check("fmp4 done", ok and t15b.status == "done", t15b.status + " " + t15b.error)
        check("fmp4 ext mp4", t15b.filename.lower().endswith(".mp4"), t15b.filename)
        if t15b.status == "done":
            with open(t15b.path, "rb") as f:
                merged = f.read()
            expect = b"\x00\x00\x00\x18ftypmp42init" + b"segment-one" + b"segment-two"
            check("fmp4 concat bytes", merged == expect, "%d / %d" % (len(merged), len(expect)))

        # 14 分段自适应（cfg segments=0 → 自动）
        auto_cfg = {"dest_dir": tmp, "segments": 0}
        m16 = engine.Manager(dict(auto_cfg))
        t16 = m16.add(base + "/small.bin", "small.bin", tmp)
        wait(t16, 60)
        check("adaptive small 2 seg", t16.status == "done" and len(t16.segments) >= 2,
              "%s %d" % (t16.status, len(t16.segments)))
        m17 = engine.Manager(dict(auto_cfg))
        t17 = m17.add(base + "/medium.bin", "medium.bin", tmp)
        wait(t17, 90)
        check("adaptive medium 4 seg", t17.status == "done" and len(t17.segments) >= 4,
              "%s %d" % (t17.status, len(t17.segments)))

        # 15 限速：不限速 vs 限 200KB/s
        m18 = engine.Manager({"dest_dir": tmp, "segments": 8, "rate_kbps": 0})
        t18 = m18.add(base + "/speed.bin", "speed_free.bin", tmp)
        t0 = time.time()
        wait(t18, 60)
        free_secs = max(0.001, time.time() - t0)
        free_speed = os.path.getsize(t18.path) / free_secs if os.path.exists(t18.path) else 0
        check("free done", t18.status == "done", t18.status + " " + t18.error)

        m19 = engine.Manager({"dest_dir": tmp, "segments": 8, "rate_kbps": 200})
        t19 = m19.add(base + "/speed.bin", "speed_limited.bin", tmp)
        t0 = time.time()
        wait(t19, 90)
        lim_secs = max(0.001, time.time() - t0)
        lim_speed = os.path.getsize(t19.path) / lim_secs if os.path.exists(t19.path) else 0
        check("limited done", t19.status == "done", t19.status + " " + t19.error)
        print("    free speed: %.1f KB/s, limited speed: %.1f KB/s" % (free_speed / 1024, lim_speed / 1024))
        check("limited below cap", lim_speed < 300 * 1024, "%.1f KB/s" % (lim_speed / 1024))
        check("limited slower than free", lim_speed < free_speed * 0.8,
              "free=%.1f limited=%.1f KB/s" % (free_speed / 1024, lim_speed / 1024))

        # 16 子进程强杀后重启续传，总字节数精确
        driver = os.path.join(tmp, "crash_driver.py")
        with open(driver, "w", encoding="utf-8") as f:
            f.write(
                "import os, sys, time\n"
                "sys.path.insert(0, %r)\n"
                "import engine\n"
                "url, dest, fname = sys.argv[1], sys.argv[2], sys.argv[3]\n"
                "m = engine.Manager({'dest_dir': dest, 'segments': 8})\n"
                "t = m.add(url, fname, dest)\n"
                "t0 = time.time()\n"
                "while time.time() - t0 < 30 and t.downloaded < 700 * 1024 and t.status == 'running':\n"
                "    time.sleep(0.05)\n"
                "time.sleep(1.6)\n"
                "os._exit(3)\n" % os.path.dirname(os.path.abspath(__file__)))
        proc = subprocess.run([sys.executable, driver, base + "/file.bin", tmp, "crash.bin"],
                              capture_output=True, timeout=60)
        if proc.returncode != 3:
            print("    driver stderr:", proc.stderr.decode("utf-8", "replace")[-800:])
        check("crash exited abrupt", proc.returncode == 3, "code=%s" % proc.returncode)
        time.sleep(0.8)
        m20 = engine.Manager(dict(cfg))
        loaded20 = m20.load_existing(tmp)
        found20 = [x for x in loaded20 if x.filename == "crash.bin"]
        check("crash reload found", len(found20) == 1, len(loaded20))
        if found20:
            m20.resume(found20[0])
            ok = wait(found20[0], 120)
            check("crash resume done", ok and found20[0].status == "done",
                  found20[0].status + " " + found20[0].error)
            check("crash resume exact", found20[0].downloaded == found20[0].total == SIZE,
                  "%d / %d" % (found20[0].downloaded, found20[0].total))
            with open(found20[0].path, "rb") as f:
                check("crash resume content", md5_bytes(f.read()) == md5_bytes(DATA))

        # ---------- 提速专项断言 ----------

        # 17 自适应段数三档 + 不支持 Range 退化单流
        check("adaptive 1mb single seg", engine.adaptive_segment_count(1_000_000, True, 0) == 1)
        check("adaptive 5mb two seg", engine.adaptive_segment_count(5_000_000, True, 0) == 2)
        check("adaptive 50mb four seg", engine.adaptive_segment_count(50_000_000, True, 0) == 4)
        check("adaptive norange single", engine.adaptive_segment_count(50_000_000, False, 0) == 1)

        # 18 二分重分段：区间连续不重叠，done 不重复计数
        m22 = engine.Manager({"dest_dir": tmp, "segments": 8})
        t22 = engine.Task(base + "/speed.bin", "split.bin", tmp, 8)
        t22.total = 4 * 1024 * 1024
        t22.supports_range = True
        t22.segments = [engine.Segment(0, t22.total - 1)]
        ex22 = concurrent.futures.ThreadPoolExecutor(max_workers=4)
        split_ok = m22._split_segment(t22, t22.segments[0], ex22, 0)
        check("split produced two", split_ok and len(t22.segments) == 2, len(t22.segments))
        sa, sb = t22.segments
        check("split contiguous", sa.end + 1 == sb.start and sb.end == t22.total - 1,
              "%d-%d / %d-%d" % (sa.start, sa.end, sb.start, sb.end))
        check("split no overlap", sa.start <= sa.end < sb.start)
        sa.done, sb.done = sa.size, sb.size
        check("split counter exact", sa.done + sb.done == t22.total,
              "%d / %d" % (sa.done + sb.done, t22.total))
        ex22.shutdown(wait=False)

        # 19 读超时一次后该段自动恢复，最终字节数精确
        orig_pooled = engine.pooled_get
        calls = {"n": 0}

        def flaky_pooled(url, headers, **kw):
            calls["n"] += 1
            if calls["n"] == 1:
                raise socket.timeout("read timed out")
            return orig_pooled(url, headers, **kw)

        engine.pooled_get = flaky_pooled
        try:
            m23 = engine.Manager({"dest_dir": tmp, "segments": 4})
            t23 = m23.add(base + "/speed.bin", "flaky.bin", tmp)
            ok = wait(t23, 60)
            check("read-timeout recovered", ok and t23.status == "done",
                  t23.status + " " + t23.error)
            check("read-timeout counter exact", t23.downloaded == t23.total == SPEED_SIZE,
                  "%d / %d" % (t23.downloaded, t23.total))
        finally:
            engine.pooled_get = orig_pooled

        # 20 限速场景：8 段并发吞吐明显高于单流
        m24 = engine.Manager({"dest_dir": tmp, "segments": 1})
        t24 = m24.add(base + "/throttle.bin", "thr1.bin", tmp)
        t0 = time.time()
        wait(t24, 90)
        single_sec = time.time() - t0
        check("throttle single done", t24.status == "done", t24.status + " " + t24.error)
        m25 = engine.Manager({"dest_dir": tmp, "segments": 8})
        t25 = m25.add(base + "/throttle.bin", "thr8.bin", tmp)
        t0 = time.time()
        wait(t25, 90)
        multi_sec = time.time() - t0
        check("throttle multi done", t25.status == "done", t25.status + " " + t25.error)
        print("    throttle single %.2fs, multi %.2fs" % (single_sec, multi_sec))
        check("throttle multi faster", multi_sec < single_sec * 0.75,
              "single=%.2f multi=%.2f" % (single_sec, multi_sec))

        print("\n%d fail(s)" % len(fails))
    finally:
        srv.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
    sys.exit(1 if fails else 0)
