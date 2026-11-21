"""从 .torrent 里算 infohash（info dict 原始字节的 sha1）。"""
import hashlib
import sys


def skip(data, i):
    c = data[i:i + 1]
    if c == b"i":
        return data.index(b"e", i) + 1
    if c == b"l":
        i += 1
        while data[i:i + 1] != b"e":
            i = skip(data, i)
        return i + 1
    if c == b"d":
        i += 1
        while data[i:i + 1] != b"e":
            i = skip(data, i)
            i = skip(data, i)
        return i + 1
    j = data.index(b":", i)
    n = int(data[i:j])
    return j + 1 + n


def find_key(data, key):
    """在顶层 dict 里找 key 的 value 起点（只扫一层）。"""
    assert data[0:1] == b"d"
    i = 1
    while data[i:i + 1] != b"e":
        ks = i
        ke = skip(data, i)
        k = data[ks:ke]
        vs = ke
        ve = skip(data, vs)
        if k == (str(len(key)).encode() + b":" + key.encode()):
            return vs, ve
        i = ve
    return None


torrent = open(sys.argv[1], "rb").read()
r = find_key(torrent, "info")
print("info span", r)
print(hashlib.sha1(torrent[r[0]:r[1]]).hexdigest())
