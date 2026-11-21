"""极简 Mainline DHT 响应器（只够验证客户端的 get_peers → values）。

用法：py -3 dht_stub.py --port 6881 --peer 10.0.2.2:9003 --peer 127.0.0.1:9003
对任何 ping / find_node / get_peers 都回一个 20 字节随机 id，get_peers 附上 values。
"""
import argparse
import os
import random
import socket
import struct


def enc(o):
    if isinstance(o, bytes):
        return str(len(o)).encode() + b":" + o
    if isinstance(o, int):
        return b"i%de" % o
    if isinstance(o, str):
        return enc(o.encode())
    if isinstance(o, list):
        return b"l" + b"".join(enc(i) for i in o) + b"e"
    if isinstance(o, dict):
        return b"d" + b"".join(enc(k) + enc(v) for k, v in sorted(o.items())) + b"e"
    raise TypeError(type(o))


def dec(data):
    stack = []

    def val(i):
        c = data[i:i + 1]
        if c == b"i":
            j = data.index(b"e", i)
            return int(data[i + 1:j]), j + 1
        if c == b"l":
            i += 1
            out = []
            while data[i:i + 1] != b"e":
                v, i = val(i)
                out.append(v)
            return out, i + 1
        if c == b"d":
            i += 1
            out = {}
            while data[i:i + 1] != b"e":
                k, i = val(i)
                v, i = val(i)
                out[k if isinstance(k, bytes) else k.encode()] = v
            return out, i + 1
        j = data.index(b":", i)
        n = int(data[i:j])
        return data[j + 1:j + 1 + n], j + 1 + n

    return val(0)[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=6881)
    ap.add_argument("--peer", action="append", default=[])
    a = ap.parse_args()
    peer_bytes = []
    for p in a.peer:
        host, port = p.rsplit(":", 1)
        peer_bytes.append(socket.inet_aton(host) + struct.pack(">H", int(port)))
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", a.port))
    print("dht stub on %d, peers=%s" % (a.port, a.peer))
    while True:
        data, addr = s.recvfrom(2048)
        try:
            msg = dec(data)
        except Exception:
            continue
        if not isinstance(msg, dict):
            continue
        tid = msg.get(b"t", b"")
        q = msg.get(b"q", b"")
        r = {b"id": os.urandom(20)}
        if q == b"get_peers":
            r[b"values"] = peer_bytes
            r[b"nodes"] = b""
        elif q == b"find_node":
            r[b"nodes"] = b""
        elif q == b"ping":
            pass
        else:
            continue
        resp = enc({b"t": tid, b"y": b"r", b"r": r})
        s.sendto(resp, addr)
        print("answered %s from %s" % (q.decode(errors="ignore"), addr))


if __name__ == "__main__":
    main()
