"""疾风下载 · 自研 BitTorrent 客户端（桌面端）
单文件种子 / 顺序分片 / SHA1 逐片校验 / 支持 HTTP + UDP tracker / ut_metadata 取种子信息。
不依赖任何第三方库；不做 DHT，也不做种（只下载）。
"""
import base64
import hashlib
import os
import random
import socket
import struct
import threading
import time
import urllib.parse
import urllib.request

BLOCK = 16384
META_PIECE = 16384
CONNECT_TIMEOUT = 12
READ_TIMEOUT = 25
META_BUDGET = 75.0
PIECE_TRIES = 3
MAX_PEERS = 40

PEER_ID = b"-JF0100-" + bytes(random.randint(0, 255) for _ in range(12))
EXT_BIT = 0x10  # reserved[5]


class BtError(Exception):
    pass


# ------------------------------------------------------------------ bencode
def bdecode(data, off=0):
    """返回 (对象, 结束位置)。"""
    if off >= len(data):
        raise BtError("bencode 数据不完整")
    c = data[off:off + 1]
    if c == b"i":
        end = data.index(b"e", off)
        return int(data[off + 1:end]), end + 1
    if c == b"l":
        off += 1
        out = []
        while True:
            if data[off:off + 1] == b"e":
                return out, off + 1
            v, off = bdecode(data, off)
            out.append(v)
    if c == b"d":
        off += 1
        out = {}
        while True:
            if data[off:off + 1] == b"e":
                return out, off + 1
            k, off = bdecode(data, off)
            v, off = bdecode(data, off)
            out[k if isinstance(k, bytes) else str(k).encode()] = v
    if c.isdigit():
        colon = data.index(b":", off)
        n = int(data[off:colon])
        start = colon + 1
        return data[start:start + n], start + n
    raise BtError("bencode 解析失败：%r" % (data[off:off + 16],))


def bencode(obj):
    if isinstance(obj, bytes):
        return str(len(obj)).encode() + b":" + obj
    if isinstance(obj, str):
        return bencode(obj.encode("utf-8"))
    if isinstance(obj, bool):
        return b"i1e" if obj else b"i0e"
    if isinstance(obj, int):
        return b"i" + str(obj).encode() + b"e"
    if isinstance(obj, list):
        return b"l" + b"".join(bencode(i) for i in obj) + b"e"
    if isinstance(obj, dict):
        out = b"d"
        for k in sorted(obj.keys()):
            kk = k if isinstance(k, bytes) else str(k).encode()
            out += bencode(kk) + bencode(obj[k])
        return out + b"e"
    raise TypeError(type(obj))


def dict_end(data):
    """按结构定位 bencode 字典结束位置（不靠第一个 e）。"""
    if not data or data[0:1] != b"d":
        return -1
    depth = 0
    i = 0
    while i < len(data):
        ch = data[i:i + 1]
        if ch == b"d" or ch == b"l":
            depth += 1
            i += 1
        elif ch == b"e":
            depth -= 1
            i += 1
            if depth == 0:
                return i
        elif ch == b"i":
            i = data.index(b"e", i) + 1
        elif ch.isdigit():
            colon = data.index(b":", i)
            n = int(data[i:colon])
            i = colon + 1 + n
        else:
            raise BtError("bencode 结构异常")
    return -1


# ------------------------------------------------------------------ magnet
def looks_like_magnet(text):
    if not text:
        return False
    return text.strip().lower().startswith("magnet:")


def magnet_error(text):
    """解析失败时给用户看的原因。"""
    low = (text or "").lower()
    if "btmh:" in low and "btih:" not in low:
        return ("这是 BitTorrent v2（btmh）磁力链，当前版本只支持 v1（btih）："
                "换一条同时带 btih 的混合链，或换 v1 资源。")
    if "xt=" not in low:
        return "这条 magnet 里没有 xt= 参数，确认链接复制完整了吗？"
    return "磁力链里没找到 btih 信息哈希（xt=urn:btih: 40 位哈希），请确认复制完整。"


def parse_magnet(text):
    if not text:
        return None
    t = text.strip()
    if not t.lower().startswith("magnet:"):
        return None
    q = t.split("?", 1)[1] if "?" in t else ""
    params = urllib.parse.parse_qs(q, keep_blank_values=True)
    raw = None
    v2_hex = ""
    for xt in params.get("xt", []):
        low = xt.strip().lower()
        h = ""
        if low.startswith("urn:btih:"):
            h = xt.strip()[9:]
        elif low.startswith("btih:"):
            h = xt.strip()[5:]
        if h:
            h = h.rstrip("=")
            if len(h) == 40:
                try:
                    raw = bytes.fromhex(h)
                except ValueError:
                    continue
            elif len(h) == 32:
                try:
                    raw = base64.b32decode(h.upper())
                except Exception:
                    continue
            if raw is not None and raw:
                break
            continue
        mh = ""
        if low.startswith("urn:btmh:"):
            mh = xt.strip()[9:]
        elif low.startswith("btmh:"):
            mh = xt.strip()[5:]
        mh = mh.strip().lower()
        if len(mh) == 68 and mh.startswith("1220"):
            v2_hex = mh[4:]
        elif len(mh) == 64:
            v2_hex = mh
    if not raw:
        return None
    info = {
        "info_hash": raw,
        "hash_hex": raw.hex(),
        "name": urllib.parse.unquote(params.get("dn", [""])[0] or ""),
        "trackers": [urllib.parse.unquote(x) for x in params.get("tr", [])],
        "length": int(params.get("xl", ["0"])[0] or 0),
        "peers": [x for x in params.get("x.pe", []) if ":" in x],
        "web_seeds": [urllib.parse.unquote(x) for x in params.get("ws", [])],
        "v2_hex": v2_hex,
    }
    return info


def decode_thunder(text):
    """thunder:// → 真实地址（Base64，去掉首尾 AA/ZZ 标记）。"""
    if not text:
        return ""
    t = text.strip()
    if not t.lower().startswith("thunder://"):
        return ""
    body = t[len("thunder://"):].strip()
    try:
        raw = base64.b64decode(body + "=" * (-len(body) % 4)).decode("utf-8", "ignore")
    except Exception:
        return ""
    if raw.startswith("AA") and raw.endswith("ZZ"):
        raw = raw[2:-2]
    return raw.strip()


# ------------------------------------------------------------------ tracker
def _pct(b):
    return urllib.parse.quote_from_bytes(b)


def announce_http(tracker, m, left, port=6881, event="started"):
    url = tracker
    url += ("&" if "?" in url else "?")
    url += "info_hash=%s&peer_id=%s&port=%d&uploaded=0&downloaded=%d&left=%d&compact=1&event=%s&numwant=%d" % (
        _pct(m["info_hash"]), _pct(PEER_ID), port, max(0, m["length"] - left), max(0, left), event, MAX_PEERS)
    req = urllib.request.Request(url, headers={"User-Agent": "JifengDownload/1.0"})
    with urllib.request.urlopen(req, timeout=CONNECT_TIMEOUT) as r:
        raw = r.read()
    d, _ = bdecode(raw)
    peers = d.get(b"peers")
    out = []
    if isinstance(peers, bytes):
        for i in range(0, len(peers) - 5, 6):
            ip = ".".join(str(x) for x in peers[i:i + 4])
            port2 = struct.unpack(">H", peers[i + 4:i + 6])[0]
            out.append((ip, port2))
    elif isinstance(peers, list):
        for p in peers:
            ip = p.get(b"ip", b"").decode("utf-8", "ignore")
            port2 = int(p.get(b"port", 0))
            if ip and port2:
                out.append((ip, port2))
    return out


def announce_udp(tracker, m, left, port=6881):
    u = urllib.parse.urlparse(tracker)
    host, qport = u.hostname, u.port or 80
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(CONNECT_TIMEOUT)
    try:
        tx = struct.unpack(">I", os.urandom(4))[0]
        s.sendto(struct.pack(">QII", 0x41727101980, 0, tx), (host, qport))
        data, _ = s.recvfrom(2048)
        if struct.unpack(">I", data[:4])[0] != 0:
            raise BtError("UDP tracker 连接被拒")
        conn_id = struct.unpack(">Q", data[8:16])[0]
        tx2 = struct.unpack(">I", os.urandom(4))[0]
        pkt = struct.pack(">QII", conn_id, 1, tx2)
        pkt += m["info_hash"] + PEER_ID
        pkt += struct.pack(">QQQ", 0, max(0, m["length"] - left), max(0, left))
        pkt += struct.pack(">IIiH", 2, 0, MAX_PEERS, port)
        s.sendto(pkt, (host, qport))
        data, _ = s.recvfrom(4096)
        if struct.unpack(">I", data[:4])[0] != 1:
            raise BtError("UDP tracker 无响应")
        out = []
        body = data[20:]
        for i in range(0, len(body) - 5, 6):
            ip = ".".join(str(x) for x in body[i:i + 4])
            port2 = struct.unpack(">H", body[i + 4:i + 6])[0]
            out.append((ip, port2))
        return out
    finally:
        try:
            s.close()
        except Exception:
            pass


def get_peers(m, left):
    out = []
    # 磁力链里直接给的 x.pe 先算进去，不依赖 tracker/DHT
    for a in m.get("peers", []) or []:
        try:
            host, _, port = a.rpartition(":")
            out.append((host.strip("[]"), int(port)))
        except Exception:
            continue
    for tr in m.get("trackers", [])[:6]:
        try:
            if tr.lower().startswith("udp://"):
                out.extend(announce_udp(tr, m, left))
            else:
                out.extend(announce_http(tr, m, left))
        except Exception:
            continue
    seen = set()
    uniq = []
    for ip, p in out:
        if (ip, p) in seen or p <= 0 or p > 65535:
            continue
        seen.add((ip, p))
        uniq.append((ip, p))
    if not uniq:
        # tracker 全挂 / 压根没 tr=：退到 DHT 按 info_hash 找 peer（BEP5）
        try:
            import dht
            uniq = dht.find_peers(m.get("hash_hex") or "", 18.0)
        except Exception:
            uniq = []
    return uniq


# ------------------------------------------------------------------ peer
class Peer(object):
    def __init__(self, addr, info_hash):
        self.addr = addr
        self.info_hash = info_hash
        self.sock = None
        self.they_ut_meta = 1
        self.metadata_size = 0

    def open(self):
        self.sock = socket.create_connection(self.addr, timeout=CONNECT_TIMEOUT)
        self.sock.settimeout(READ_TIMEOUT)
        hs = bytes([19]) + b"BitTorrent protocol" + bytes([0, 0, 0, 0, 0, EXT_BIT, 0, 0])
        hs += self.info_hash + PEER_ID
        self.sock.sendall(hs)
        their = self._recv_exact(68)
        if not their or their[0] != 19 or their[1:20] != b"BitTorrent protocol":
            raise BtError("握手失败")
        if their[28:48] != self.info_hash:
            raise BtError("种子哈希不匹配")
        self._send_ext_handshake()

    def _send_ext_handshake(self):
        d = bencode({b"m": {b"ut_metadata": 1}, b"v": b"Jifeng", b"metadata_size": 0})
        self.send(bytes([20, 0]) + d)
        self.send(bytes([2]))  # interested

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            try:
                chunk = self.sock.recv(n - len(buf))
            except socket.timeout:
                raise BtError("读超时")
            if not chunk:
                return None
            buf += chunk
        return buf

    def send(self, payload):
        self.sock.sendall(struct.pack(">I", len(payload)) + payload)

    def recv(self):
        hdr = self._recv_exact(4)
        if not hdr:
            return None, b""
        ln = struct.unpack(">I", hdr)[0]
        if ln == 0:
            return 0, b""
        if ln > 4 * 1024 * 1024:
            raise BtError("消息过大")
        body = self._recv_exact(ln)
        if body is None:
            return None, b""
        return body[0], body[1:]

    def read_ext_handshake(self, deadline):
        while time.time() < deadline:
            mid, payload = self.recv()
            if mid is None:
                raise BtError("连接断开")
            if mid == 20 and payload[:1] == b"\x00":
                d, _ = bdecode(payload[1:])
                m = d.get(b"m", {})
                self.they_ut_meta = int(m.get(b"ut_metadata", 1) or 0)
                self.metadata_size = int(d.get(b"metadata_size", 0) or 0)
                return True
        return False

    def fetch_metadata(self):
        deadline = time.time() + META_BUDGET
        self.read_ext_handshake(deadline)
        size = self.metadata_size
        if size <= 0 or self.they_ut_meta <= 0:
            raise BtError("对方没有元数据交换能力")
        chunks = {}
        need = (size + META_PIECE - 1) // META_PIECE
        tries = 0
        while len(chunks) < need:
            for idx in range(need):
                if idx in chunks:
                    continue
                req = bencode({b"msg_type": 0, b"piece": idx})
                self.send(bytes([20, self.they_ut_meta]) + req)
            got = 0
            while time.time() < deadline and got == 0:
                mid, payload = self.recv()
                if mid is None:
                    raise BtError("取元数据时连接断开")
                if mid == 20:
                    rest = payload[1:]  # 去掉扩展消息里的 ext_id
                    end = dict_end(rest)
                    if end > 0:
                        head, _ = bdecode(rest[:end])
                        if int(head.get(b"msg_type", 0)) == 1:
                            idx = int(head.get(b"piece", 0))
                            chunks[idx] = rest[end:end + META_PIECE]
                            got += 1
            tries += 1
            if tries > 4 and len(chunks) < need:
                raise BtError("元数据没有取全")
            if got == 0 and len(chunks) < need:
                raise BtError("元数据请求超时")
        info_raw = b"".join(chunks[i] for i in range(need))[:size]
        if hashlib.sha1(info_raw).digest() != self.info_hash:
            raise BtError("种子信息校验不通过")
        info, _ = bdecode(info_raw)
        return info

    def request(self, index, begin, length):
        self.send(bytes([6]) + struct.pack(">III", index, begin, length))
        while True:
            mid, payload = self.recv()
            if mid is None:
                raise BtError("连接断开")
            if mid == 7:
                idx, off = struct.unpack(">II", payload[:8])
                if idx != index or off != begin:
                    continue
                # 标准实现带 length 字段（12 字节头）；部分简易做种端省略 length（8 字节头）
                if len(payload) - 12 == length:
                    return payload[12:12 + length]
                return payload[8:8 + length]
            # 其它消息（bitfield/have/unchoke/keepalive）忽略

    def close(self):
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass
        self.sock = None


# ------------------------------------------------------------------ 下载
def _piece_count(info):
    n = int(info.get(b"length", 0))
    pl = int(info.get(b"piece length", 0))
    if n <= 0 or pl <= 0:
        raise BtError("种子信息不完整（只支持单文件种子）")
    return n, pl, (n + pl - 1) // pl


def _verified_prefix(path, info):
    """已有文件里连续校验通过的分片数（用于续传）。"""
    total, pl, count = _piece_count(info)
    pieces = info.get(b"pieces", b"")
    if not os.path.exists(path):
        return 0
    have = 0
    with open(path, "rb") as f:
        for idx in range(count):
            f.seek(idx * pl)
            chunk = f.read(min(pl, total - idx * pl))
            if len(chunk) != min(pl, total - idx * pl):
                break
            if hashlib.sha1(chunk).digest() != pieces[idx * 20:(idx + 1) * 20]:
                break
            have += 1
    return have


def download(m, info, path, on_progress=None, should_stop=None, throttle=None,
             log=None, peer_pool=None):
    """顺序下载到 path；返回 (已下载字节, 文件名)。"""
    total, pl, count = _piece_count(info)
    name = info.get(b"name", b"").decode("utf-8", "ignore") if info.get(b"name") else ""
    peers = peer_pool if peer_pool is not None else get_peers(m, total)
    if not peers:
        raise BtError("没有找到任何做种者（tracker 没返回 peer）")

    start_idx = _verified_prefix(path, info)
    f = open(path, "r+b" if os.path.exists(path) else "wb")
    try:
        f.truncate(total)
    except Exception:
        pass
    written = start_idx * pl
    if on_progress:
        on_progress(written, total)

    def _log(msg):
        if log:
            try:
                log(msg)
            except Exception:
                pass

    live = None
    cursor = 0
    fails = 0
    idx = start_idx
    try:
        while idx < count:
            if should_stop and should_stop():
                break
            if live is None:
                addr = peers[cursor % len(peers)]
                p = Peer(addr, m["info_hash"])
                try:
                    p.open()
                    live = p
                    _log("peer 已连接 %s:%d" % addr)
                except Exception as e:
                    p.close()
                    cursor += 1
                    fails += 1
                    if fails > len(peers) * 2:
                        raise BtError("peer 全都连不上（%s）" % e)
                    continue
            size = min(pl, total - idx * pl)
            piece = bytearray(size)
            begin = 0
            ok = True
            try:
                while begin < size:
                    if should_stop and should_stop():
                        ok = False
                        break
                    ln = min(BLOCK, size - begin)
                    if throttle:
                        throttle(ln)
                    block = live.request(idx, begin, ln)
                    if block is None or len(block) != ln:
                        raise BtError("分块不完整")
                    piece[begin:begin + ln] = block
                    begin += ln
                    written += ln
                    if on_progress:
                        on_progress(written, total)
                if not ok:
                    break
                if hashlib.sha1(bytes(piece)).digest() != info[b"pieces"][idx * 20:(idx + 1) * 20]:
                    raise BtError("第 %d 片校验不通过" % idx)
                f.seek(idx * pl)
                f.write(piece)
                f.flush()
                if idx % 5 == 0:
                    _log("分片 %d/%d 已写入" % (idx, count))
                idx += 1
            except Exception as e:
                _log("分片失败：%s" % e)
                written -= begin
                live.close()
                live = None
                cursor += 1
                fails += 1
                if fails > len(peers) * 2 + 4:
                    raise BtError("下载反复失败（%s）" % e)
                continue
    finally:
        if live:
            live.close()
        try:
            f.close()
        except Exception:
            pass
    return written, name, idx >= count
