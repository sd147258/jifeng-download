# -*- coding: utf-8 -*-
"""设备端验收用本地 HTTP 服务（宿主机 8000 端口，adb reverse 给模拟器访问）。

端点：
  /ok.bin   正常 octet-stream 随机文件（支持 Range）
  /page     text/html 落地页（应被拒）
  /error    application/json 错误体（应被拒）
  /hotlink  不带 Referer 返回 403 网页；带 Referer 返回正常 octet-stream
"""
import hashlib
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SIZE = 300 * 1024
DATA = bytearray(SIZE)
for i in range(SIZE):
    DATA[i] = (i * 31 + 7) % 251

HTML = "<!doctype html><html><head><title>登录</title></head><body>请先登录后再下载</body></html>"
JSON = '{"code":403,"message":"need login","data":null}'
# 解码落地页：og:video / 转义 play_addr / <video src> 三个字段都指向本地 /ok.bin
DECODE_HTML = (
    '<!doctype html><html><head>'
    '<meta property="og:video" content="http://127.0.0.1:8000/ok.bin">'
    '<script>var d={"play_addr":"http:\\u002F\\u002F127.0.0.1:8000\\u002Fok.bin?x=1"};</script>'
    '<video src="/ok.bin"></video></head><body>本地解码页</body></html>'
)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/ok.bin":
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
            length = end - start + 1
            self.send_response(206 if partial else 200)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(length))
            if partial:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, SIZE))
            self.end_headers()
            self.wfile.write(bytes(DATA[start:end + 1]))
            return
        if self.path == "/page":
            self._send(200, "text/html; charset=utf-8", HTML.encode("utf-8"))
            return
        if self.path == "/decode":
            self._send(200, "text/html; charset=utf-8", DECODE_HTML.encode("utf-8"))
            return
        if self.path == "/error":
            self._send(200, "application/json; charset=utf-8", JSON.encode("utf-8"))
            return
        if self.path == "/hotlink":
            ref = self.headers.get("Referer")
            if not ref:
                self._send(403, "text/html; charset=utf-8", HTML.encode("utf-8"))
            else:
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(SIZE))
                self.end_headers()
                self.wfile.write(bytes(DATA))
            return
        self.send_response(404)
        self.end_headers()


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", 8000), H)
    print("listening 8000", flush=True)
    srv.serve_forever()
