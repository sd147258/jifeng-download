# -*- coding: utf-8 -*-
"""限速 Range 服务：给 Android 端做暂停/续传验收用。"""
import hashlib
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SIZE = 10 * 1024 * 1024
DATA = bytearray(SIZE)
for i in range(SIZE):
    DATA[i] = (i * 31 + 7) % 251
print("md5", hashlib.md5(bytes(DATA)).hexdigest(), flush=True)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        rng = self.headers.get("Range")
        start, end = 0, SIZE - 1
        partial = False
        if rng and rng.startswith("bytes="):
            spec = rng[6:]
            a, _, b = spec.partition("-")
            start = int(a)
            if b:
                end = min(int(b), SIZE - 1)
            partial = True
        if start > end:
            self.send_response(416)
            self.send_header("Content-Range", "bytes */%d" % SIZE)
            self.end_headers()
            return
        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        self.send_header("ETag", '"slow-test"')
        if partial:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, SIZE))
        self.end_headers()
        pos = start
        step = 32 * 1024
        try:
            while pos <= end:
                n = min(step, end - pos + 1)
                self.wfile.write(bytes(DATA[pos:pos + n]))
                self.wfile.flush()
                pos += n
                time.sleep(0.75)
        except Exception:
            return


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    srv = ThreadingHTTPServer(("127.0.0.1", port), H)
    print("listening", port, flush=True)
    srv.serve_forever()
