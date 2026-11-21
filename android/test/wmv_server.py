# -*- coding: utf-8 -*-
"""WMV 识别测试服务：三条路由覆盖「扩展名 / 魔数 / Content-Type」三种判定路径。

  /movie.wmv  真扩展名，Content-Type: video/x-ms-wmv
  /noext      无扩展名，Content-Type: application/octet-stream，但文件头是 ASF GUID
  /ctonly     无扩展名，Content-Type: video/x-ms-wmv，文件头是普通随机字节（只能靠 ctype 判）

用法： py -3 test/wmv_server.py [port]
"""
import os
import random
import socket
import sys
import threading

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8780
ASF = bytes([0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11,
             0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C])
BODY_SIZE = 1024 * 1024

random.seed(20261121)
PAYLOAD = bytes(random.getrandbits(8) for _ in range(BODY_SIZE))
ASF_BODY = ASF + PAYLOAD
PLAIN_BODY = PAYLOAD

ROUTES = {
    "/movie.wmv": ("video/x-ms-wmv", ASF_BODY),
    "/noext": ("application/octet-stream", ASF_BODY),
    "/ctonly": ("video/x-ms-wmv", PLAIN_BODY),
}

HERE = os.path.dirname(os.path.abspath(__file__))
REAL = os.path.join(HERE, "real.wmv")
if os.path.exists(REAL):
    with open(REAL, "rb") as f:
        ROUTES["/real.wmv"] = ("video/x-ms-wmv", f.read())
    print("real wmv loaded %d bytes" % os.path.getsize(REAL), flush=True)

REAL_WMA = os.path.join(HERE, "real.wma")
if os.path.exists(REAL_WMA):
    with open(REAL_WMA, "rb") as f:
        ROUTES["/real.wma"] = ("audio/x-ms-wma", f.read())
    print("real wma loaded %d bytes" % os.path.getsize(REAL_WMA), flush=True)


def handle(conn):
    try:
        data = conn.recv(4096).decode("latin-1")
        if not data:
            return
        path = data.split(" ")[1].split("?")[0]
        if path not in ROUTES:
            conn.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
            return
        ctype, body = ROUTES[path]
        rng = None
        for line in data.split("\r\n"):
            if line.lower().startswith("range:"):
                rng = line.split("=", 1)[1].strip()
        start, end = 0, len(body) - 1
        partial = False
        if rng and "-" in rng:
            a, b = rng.split("-", 1)
            start = int(a) if a else 0
            end = int(b) if b else len(body) - 1
            partial = True
            conn.sendall(("HTTP/1.1 206 Partial Content\r\n"
                          "Content-Type: %s\r\n"
                          "Accept-Ranges: bytes\r\n"
                          "Content-Range: bytes %d-%d/%d\r\n"
                          "Content-Length: %d\r\n\r\n"
                          % (ctype, start, end, len(body), end - start + 1)).encode())
            conn.sendall(body[start:end + 1])
            return
        conn.sendall(("HTTP/1.1 200 OK\r\nContent-Type: %s\r\nAccept-Ranges: bytes\r\n"
                      "Content-Length: %d\r\n\r\n" % (ctype, len(body))).encode())
        conn.sendall(body)
    except Exception as e:
        print("err", e)
    finally:
        try:
            conn.close()
        except Exception:
            pass


def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", PORT))
    s.listen(20)
    print("WMV TEST READY port=%d routes=%s" % (PORT, list(ROUTES)), flush=True)
    while True:
        c, _ = s.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()
