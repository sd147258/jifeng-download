"""多文件种子测试水蛭：tracker(9002) + seeder(9003)，一个 3 文件、跨分片边界的小种子。

文件布局（种子内相对路径）：
  part1.bin        300000 B
  sub/part2.bin    150000 B
  sub/tiny.txt       777 B
piece length = 65536，分片会跨文件，专门用来验证客户端的跨文件落盘。
"""
import hashlib
import os
import socket
import struct
import threading
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
PIECE = 65536
PEER_PORT = 9003
TRACKER_PORT = 9002

LAYOUT = [
    (['part1.bin'], 300000),
    (['sub', 'part2.bin'], 150000),
    (['sub', 'tiny.txt'], 777),
]

DATA = b''
PARTS = []
for idx, (path, size) in enumerate(LAYOUT):
    body = bytes(((i * (idx + 7) + idx * 13) % 251) for i in range(size))
    PARTS.append((path, body))
    DATA += body

PIECES = b''.join(hashlib.sha1(DATA[i:i + PIECE]).digest()
                  for i in range(0, len(DATA), PIECE))


def benc(x):
    if isinstance(x, bytes):
        return str(len(x)).encode() + b':' + x
    if isinstance(x, str):
        return benc(x.encode('utf-8'))
    if isinstance(x, bool):
        return b'i1e' if x else b'i0e'
    if isinstance(x, int):
        return b'i' + str(x).encode() + b'e'
    if isinstance(x, list):
        return b'l' + b''.join(benc(i) for i in x) + b'e'
    if isinstance(x, dict):
        out = b'd'
        for k in sorted(x.keys()):
            out += benc(k) + benc(x[k])
        return out + b'e'
    raise TypeError(type(x))


INFO = benc({
    b'files': [{b'length': size, b'path': [p.encode() for p in path]} for path, size in LAYOUT],
    b'name': b'movdir',
    b'piece length': PIECE,
    b'pieces': PIECES,
})
INFOHASH = hashlib.sha1(INFO).digest()


def recv_exact(s, n):
    buf = b''
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def send_msg(s, payload):
    s.sendall(struct.pack('>I', len(payload)) + payload)


def handle_peer(conn, addr):
    try:
        hs = recv_exact(conn, 68)
        if not hs or hs[0] != 19 or hs[1:20] != b'BitTorrent protocol':
            conn.close()
            return
        if hs[28:48] != INFOHASH:
            print('peer: info_hash mismatch from %s' % (addr,))
            conn.close()
            return
        resp = bytes([19]) + b'BitTorrent protocol' + bytes([0, 0, 0, 0, 0, 0x10, 0, 0])
        resp += INFOHASH + b'-PYSEED-000001234567'
        conn.sendall(resp)
        send_msg(conn, bytes([20, 0]) + benc(
            {'m': {'ut_metadata': 1}, 'metadata_size': len(INFO), 'v': 'py-seeder-multi'}))
        nbits = (len(PIECES) // 20 + 7) // 8
        bf = bytearray(b'\xff' * nbits)
        left = len(PIECES) // 20 % 8
        if left:
            bf[-1] = (0xff << (8 - left)) & 0xff
        send_msg(conn, bytes([5]) + bytes(bf))
        print('peer: handshake ok with %s (pieces=%d)' % (addr, len(PIECES) // 20))
        while True:
            hdr = recv_exact(conn, 4)
            if not hdr:
                break
            ln = struct.unpack('>I', hdr)[0]
            if ln == 0 or ln > 1024 * 1024:
                break
            body = recv_exact(conn, ln)
            if not body:
                break
            mid = body[0]
            if mid == 20:
                ext_id = body[1]
                payload = body[2:]
                if ext_id == 0:
                    continue
                if b'msg_typei0e' in payload:
                    piece_no = 0
                    idx = payload.find(b'piecei')
                    if idx >= 0:
                        start = idx + 6
                        end = payload.find(b'e', start)
                        if end > start:
                            piece_no = int(payload[start:end])
                    chunk = INFO[piece_no * 16384:(piece_no + 1) * 16384]
                    send_msg(conn, bytes([20, ext_id]) + benc(
                        {'msg_type': 1, 'piece': piece_no, 'total_size': len(INFO)}) + chunk)
                    print('peer: served metadata piece %d (%d bytes)' % (piece_no, len(chunk)))
            elif mid == 2:
                send_msg(conn, bytes([1]))
            elif mid == 6:
                idx, begin, length = struct.unpack('>III', body[1:13])
                off = idx * PIECE + begin
                block = DATA[off:off + length]
                send_msg(conn, bytes([7]) + struct.pack('>II', idx, begin) + block)
                print('peer: served block piece=%d begin=%d len=%d' % (idx, begin, len(block)))
    except Exception as e:
        print('peer error: %r' % (e,))
    finally:
        try:
            conn.close()
        except Exception:
            pass


def peer_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', PEER_PORT))
    srv.listen(20)
    print('peer listening on %d, info_hash=%s, total=%d' % (PEER_PORT, INFOHASH.hex(), len(DATA)))
    while True:
        c, a = srv.accept()
        threading.Thread(target=handle_peer, args=(c, a), daemon=True).start()


def handle_tracker(conn, addr):
    try:
        req = b''
        while b'\r\n\r\n' not in req:
            d = conn.recv(4096)
            if not d:
                return
            req += d
        line = req.split(b'\r\n')[0].decode('latin1')
        path = line.split(' ')[1]
        q = path.split('?', 1)[1] if '?' in path else ''
        raw = urllib.parse.unquote_to_bytes(q)
        if INFOHASH not in raw:
            print('tracker: info_hash 不匹配')
        peers = struct.pack('>4sH', socket.inet_aton('127.0.0.1'), PEER_PORT)
        body = benc({'interval': 30, 'peers': peers, 'complete': 1, 'incomplete': 0})
        conn.sendall(b'HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n'
                     b'Content-Length: ' + str(len(body)).encode() + b'\r\n\r\n' + body)
    except Exception as e:
        print('tracker error: %r' % (e,))
    finally:
        try:
            conn.close()
        except Exception:
            pass


def tracker_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', TRACKER_PORT))
    srv.listen(20)
    print('tracker listening on %d' % TRACKER_PORT)
    while True:
        c, a = srv.accept()
        threading.Thread(target=handle_tracker, args=(c, a), daemon=True).start()


if __name__ == '__main__':
    out = os.path.join(HERE, 'multi_src')
    if not os.path.isdir(out):
        os.makedirs(out)
    for path, body in PARTS:
        p = os.path.join(out, *path)
        parent = os.path.dirname(p)
        if not os.path.isdir(parent):
            os.makedirs(parent)
        with open(p, 'wb') as f:
            f.write(body)
    open(os.path.join(HERE, 'bt_multi_hash.txt'), 'w').write(INFOHASH.hex())
    open(os.path.join(HERE, 'magnet_multi.txt'), 'w').write(
        'magnet:?xt=urn:btih:%s&dn=movdir&tr=http://127.0.0.1:%d/announce'
        % (INFOHASH.hex(), TRACKER_PORT))
    threading.Thread(target=peer_server, daemon=True).start()
    threading.Thread(target=tracker_server, daemon=True).start()
    print('SWARM READY magnet:?xt=urn:btih:%s&dn=movdir&tr=http://127.0.0.1:%d/announce'
          % (INFOHASH.hex(), TRACKER_PORT))
    while True:
        time.sleep(1)
