"""极简 Mainline DHT（BEP5）客户端：按 info_hash 找 peer，给桌面版磁力用。

从引导节点开始按 XOR 距离由近到远迭代 get_peers：收 values（peer 的 ip:port）
和 nodes（26 字节一条：20 字节 id + 4 字节 ip + 2 字节 port），把新节点并入候选，
始终问离目标最近的 8 个，拿到 peer 就收工。

不做 announce / BEP44 / 公网节点；无账号、无门槛，UDP 被封时降级为「没找到源」。
"""
import random
import socket
import struct
import time

import bt

BOOTSTRAP = [
    ("router.bittorrent.com", 6881),
    ("dht.transmissionbt.com", 6881),
    ("router.utorrent.com", 6881),
    ("dht.libtorrent.org", 25401),
]
MAX_QUERIES = 96
MAX_CAND = 400
MAX_PEERS = 200
BATCH = 8
ROUND = 1.8

EXTRA = []          # 自检 / 自定义引导节点 [(host, port)]

# 同一个 info_hash 10 分钟内复用 peer，重复「继续」不再走一轮 DHT
CACHE_TTL = 600
_CACHE = {}


def clear_cache():
    _CACHE.clear()


def _encode_query(tid, self_id, ih):
    return bt.bencode({
        b"t": tid,
        b"y": b"q",
        b"q": b"get_peers",
        b"a": {b"id": self_id, b"info_hash": ih},
    })


def _dist(nid, target):
    if nid is None:
        return b"\xff" * 20
    return bytes(a ^ b for a, b in zip(nid, target))


def _peers_from(resp):
    peers, nodes = [], []
    if not isinstance(resp, dict):
        return peers, nodes
    r = resp.get(b"r")
    if not isinstance(r, dict):
        return peers, nodes
    for v in r.get(b"values") or []:
        if isinstance(v, bytes) and len(v) >= 6:
            ip = ".".join(str(x) for x in v[:4])
            port = struct.unpack(">H", v[4:6])[0]
            if port > 0:
                peers.append((ip, port))
    nb = r.get(b"nodes")
    if isinstance(nb, bytes):
        for i in range(0, len(nb) - 25, 26):
            nid = nb[i:i + 20]
            ip = ".".join(str(x) for x in nb[i + 20:i + 24])
            port = struct.unpack(">H", nb[i + 24:i + 26])[0]
            if port > 0 and ip != "0.0.0.0":
                nodes.append((nid, (ip, port)))
    return peers, nodes


def find_peers(hash_hex, budget=12.0, extra=None, debug=False):
    """返回 [(ip, port)]，可能为空。budget 单位秒。"""
    if not hash_hex or len(hash_hex) != 40:
        return []
    key = hash_hex.lower()
    hit = _CACHE.get(key)
    if hit and time.time() - hit[0] < CACHE_TTL:
        return list(hit[1])
    ih = bytes.fromhex(hash_hex)
    self_id = bytes(random.getrandbits(8) for _ in range(20))
    ids = {}            # addr -> node id
    cands = []          # [(addr, id or None)]
    for n in list(extra or []) + list(EXTRA) + list(BOOTSTRAP):
        a = (n[0], int(n[1]))
        if a not in ids and a not in [c[0] for c in cands]:
            cands.append((a, None))
    asked = set()
    peers = []
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(ROUND)
    deadline = time.time() + max(2.0, budget)
    tx = 0
    try:
        while time.time() < deadline and len(asked) < MAX_QUERIES and len(peers) < MAX_PEERS:
            cands.sort(key=lambda c: _dist(c[1], ih))
            batch = [c for c in cands if c[0] not in asked][:BATCH]
            if not batch:
                break
            for a, _nid in batch:
                asked.add(a)
                tid = b"jf%d" % tx
                tx += 1
                try:
                    sock.sendto(_encode_query(tid, self_id, ih), a)
                except Exception:
                    pass
            round_end = min(deadline, time.time() + ROUND)
            while time.time() < round_end:
                try:
                    data, addr = sock.recvfrom(4096)
                except socket.timeout:
                    continue
                except Exception:
                    break
                try:
                    resp = bt.bdecode(data)
                except Exception:
                    continue
                if isinstance(resp, tuple):
                    resp = resp[0]
                got_peers, got_nodes = _peers_from(resp)
                if debug:
                    r = resp.get(b"r") if isinstance(resp, dict) else {}
                    print("resp from %s peers=%d nodes=%d" % (
                        str(addr), len(got_peers), len(got_nodes)))
                for p in got_peers:
                    if p not in peers:
                        peers.append(p)
                for nid, a in got_nodes:
                    if a in ids or len(cands) >= MAX_CAND:
                        continue
                    ids[a] = nid
                    cands.append((a, nid))
            if peers:
                break
    finally:
        try:
            sock.close()
        except Exception:
            pass
    if peers:
        if len(_CACHE) > 24:
            _CACHE.clear()
        _CACHE[key] = (time.time(), list(peers))
    return peers
