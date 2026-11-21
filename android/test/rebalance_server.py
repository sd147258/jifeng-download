# -*- coding: utf-8 -*-
"""对照测试服务：同一文件、每连接限速不同，第 8 个连接故意很慢。
用来验证"动态分段/连接复用"能不能把慢段的剩余部分切给已经干完的快速连接。

用法： py -3 test/rebalance_server.py [port]
"""
import os
import random
import socket
import sys
import threading
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8770
SIZE = 8 * 1024 * 1024
RATE_FAST = 320 * 1024
RATE_SLOW = 30 * 1024

_lock = threading.Lock()
_ordinal = [0]

fh_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mix.bin")
if not os.path.exists(fh_path) or os.path.getsize(fh_path) != SIZE:
    random.seed(20261120)
    with open(fh_path, "wb") as f:
        left = SIZE
        while left > 0:
            n = min(left, 1 << 20)
            f.write(random.randbytes(n))
            left -= n


def rate_for(n):
    # 第 8 个连接慢，其余都快；之后的连接一律快
    return RATE_SLOW if n == 8 else RATE_FAST


def handle(conn, ordinal):
    try:
        conn.settimeout(10)
        req = b""
        while b"\r\n\r\n" not in req:
            chunk = conn.recv(4096)
            if not chunk:
                return
            req += chunk
        lines = req.split(b"\r\n")
        rng = None
        for line in lines[1:]:
            if line.lower().startswith(b"range:"):
                rng = line.split(b"=", 1)[1].strip()
        start, end = 0, SIZE - 1
        code = "200 OK"
        if rng:
            part = rng.decode().split("-")
            start = int(part[0]) if part[0] else 0
            end = int(part[1]) if len(part) > 1 and part[1] else SIZE - 1
            end = min(end, SIZE - 1)
            code = "206 Partial Content"
        length = end - start + 1
        rate = rate_for(ordinal)
        print("[conn %d] %s %d-%d rate=%dKB/s" % (ordinal, code, start, end, rate // 1024), flush=True)
        head = "HTTP/1.1 %s\r\nContent-Length: %d\r\nContent-Type: application/octet-stream\r\n" \
               "Accept-Ranges: bytes\r\nContent-Range: bytes %d-%d/%d\r\nConnection: close\r\r\n" \
               % (code, length, start, end, SIZE)
        conn.sendall(head.encode())
        with open(fh_path, "rb") as f:
            f.seek(start)
            sent = 0
            while sent < length:
                n = min(int(rate / 20), length - sent)   # 50ms 一批
                buf = f.read(n)
                if not buf:
                    break
                conn.sendall(buf)
                sent += len(buf)
                time.sleep(0.05)
    except Exception as e:
        print("[conn %d] err %s" % (ordinal, e), flush=True)
    finally:
        try:
            conn.close()
        except Exception:
            pass


def serve():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", PORT))
    s.listen(16)
    print("REBALANCE READY port=%d size=%d slow_conn=8" % (PORT, SIZE), flush=True)
    while True:
        conn, _ = s.accept()
        with _lock:
            _ordinal[0] += 1
            n = _ordinal[0]
        threading.Thread(target=handle, args=(conn, n), daemon=True).start()


if __name__ == "__main__":
    serve()
