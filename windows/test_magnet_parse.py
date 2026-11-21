# -*- coding: utf-8 -*-
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bt

HEX = "4f2e83c2674b2f36a880368f1415b9ff997ab848"
B32 = "J4XIHQTHJMXTNKEAG2HRIFNZ76MXVOCI"
V2 = "1220ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"

cases = [
    ("hex+x.pe",
     "magnet:?xt=urn:btih:%s&dn=t&x.pe=127.0.0.1:9001" % HEX,
     lambda m: m and m["hash_hex"] == HEX and m["peers"] == ["127.0.0.1:9001"]),
    ("base32", "magnet:?xt=urn:btih:%s&dn=t" % B32,
     lambda m: m and m["hash_hex"] == HEX),
    ("hybrid(v1+v2)",
     "magnet:?xt=urn:btih:%s&xt=urn:btmh:%s&dn=t" % (HEX, V2),
     lambda m: m and m["hash_hex"] == HEX),
    ("v2-only", "magnet:?xt=urn:btmh:%s&dn=t" % V2,
     lambda m: m is None and "v2" in bt.magnet_error("magnet:?xt=urn:btmh:" + V2)),
    ("no-xt", "magnet:?dn=only",
     lambda m: m is None and "xt=" in bt.magnet_error("magnet:?dn=only")),
    ("bad-hex", "magnet:?xt=urn:btih:" + "z" * 40,
     lambda m: m is None),
    ("ws",
     "magnet:?xt=urn:btih:%s&ws=http%%3A%%2F%%2Fx%%2Fa.bin" % HEX,
     lambda m: m and m["web_seeds"] == ["http://x/a.bin"] and m["hash_hex"] == HEX),
]

ok = True
for name, link, check in cases:
    m = bt.parse_magnet(link)
    res = bool(check(m))
    ok = ok and res
    print(("PASS" if res else "FAIL"), name)

print("ALL PASS" if ok else "SOME FAILED")
sys.exit(0 if ok else 1)
