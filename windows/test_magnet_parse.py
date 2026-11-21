# -*- coding: utf-8 -*-
"""自测：bt.py 磁力解析。直接 py -3 test_magnet_parse.py 跑。"""
import bt


def main():
    cases = [
        ("magnet:?xt=urn:btih:9c3848582e4880031d34231a5f154c686586b2d5&dn=ubuntu-22.04.4-desktop-amd64.iso&tr=https://tracker.example.com/announce",
         "9c3848582e4880031d34231a5f154c686586b2d5",
         "ubuntu-22.04.4-desktop-amd64.iso",
         ["https://tracker.example.com/announce"]),
        ("magnet:?xt=urn:btih:DEF000&tr=udp://tracker.opentrackr.org:1337/announce&tr2=https://tracker.example.org/announce",
         "def000", "", ["udp://tracker.opentrackr.org:1337/announce", "https://tracker.example.org/announce"]),
        ("magnet:?xt=urn:btih:ABCDEF&dn=视频", "abcdef", "视频", []),
    ]
    fails = 0
    for text, ih, name, trackers in cases:
        m = bt.magnet_error(text)
        if m.error:
            print("FAIL", text, "->", m.error)
            fails += 1
            continue
        if m.info_hash != ih or m.name != name or list(m.web_seeds) != trackers:
            print("FAIL", text, "->", m.info_hash, repr(m.name), list(m.web_seeds))
            fails += 1
        else:
            print("PASS", m.info_hash, repr(m.name), list(m.web_seeds))
    print("ALL PASS" if fails == 0 else "%d FAIL" % fails)
    raise SystemExit(1 if fails else 0)


if __name__ == "__main__":
    main()
