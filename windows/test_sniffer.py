# -*- coding: utf-8 -*-
"""网页解码测试：本地 HTTP 服务造页面，覆盖跳转链 / 转义 / 各抽取源 / 择优 / 失败分类 / Referer 贯通。"""
import os
import shutil
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine
import sniffer

PAGE_HTML = """<html><head>
<meta property="og:video" content="https://cdn.example.com/og.mp4">
<meta property="og:video:secure_url" content="https://cdn.example.com/og.mp4">
</head><body>
<video src="/rel/video.mp4"></video>
<script>
window.__DATA__ = {
  "playAddr": "https:\\/\\/cdn.example.com\\/escaped\\u002Fplay.mp4",
  "play_addr": {"url_list": ["https:\\/\\/cdn.example.com\\/addr.mp4"]},
  "preview": "https://cdn.example.com/preview_1.mp4",
  "thumb": "https://cdn.example.com/thumb.jpg",
  "cover": "https://cdn.example.com/cover.mp4",
  "playwm": "https://cdn.example.com/playwm/wm.mp4"
}
</script>
</body></html>"""

PAGE2_HTML = """<html><body>
<video src="/protected.mp4"></video>
</body></html>"""

MASTER = ("#EXTM3U\n"
          "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n"
          "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nhi.m3u8\n")

PROT_BODY = b"protected-payload-" * 256


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    lock = threading.Lock()

    def log_message(self, *a):
        pass

    def _send(self, body, ctype="application/octet-stream", status=200, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if headers:
            for k, v in headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path

        if path == "/share":
            self.send_response(302)
            self.send_header("Location", "/page")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path == "/page":
            self._send(PAGE_HTML.encode("utf-8"), "text/html; charset=utf-8")
            return
        if path == "/rel/video.mp4":
            self._send(b"relative-video-body")
            return
        if path == "/master.m3u8":
            self._send(MASTER.encode("utf-8"), "application/vnd.apple.mpegurl")
            return
        if path in ("/low.m3u8", "/hi.m3u8"):
            self._send(b"#EXTM3U\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
            return
        if path == "/api":
            body = ('{"play_addr":{"url_list":["https://cdn.example.com/api.mp4"]}}').encode("utf-8")
            self._send(body, "application/json; charset=utf-8")
            return
        if path == "/page2":
            self._send(PAGE2_HTML.encode("utf-8"), "text/html; charset=utf-8")
            return
        if path == "/protected.mp4":
            ref = self.headers.get("Referer", "")
            if "/page2" not in ref:
                self.send_error(403, "Forbidden")
                return
            self._send(PROT_BODY)
            return
        if path == "/login.html":
            self._send("<html><body>请先登录</body></html>".encode("utf-8"),
                       "text/html; charset=utf-8")
            return
        if path == "/data.json":
            self._send('{"code":401,"msg":"login required"}'.encode("utf-8"),
                       "application/json; charset=utf-8")
            return
        if path == "/douyin":
            shell = ('{"_ROUTER_DATA":{"loaderData":{"video_(id)/page":'
                     '{"renderInSSR":1,"isSpider":false}}}}').encode("utf-8")
            self._send(shell, "text/html; charset=utf-8")
            return
        if path == "/kuaishou":
            self._send('{"srcNoMark":"https://ks.example.com/k.mp4"}'.encode("utf-8"),
                       "text/html; charset=utf-8")
            return
        if path == "/weibo":
            self._send('{"stream_url":"https://wb.example.com/w.mp4"}'.encode("utf-8"),
                       "text/html; charset=utf-8")
            return
        if path == "/bilibili":
            self._send('{"baseUrl":"https://bili.example.com/b.m4v"}'.encode("utf-8"),
                       "text/html; charset=utf-8")
            return
        if path == "/direct.mp4":
            self._send(b"direct-mp4-payload" * 16, "video/mp4")
            return
        if path == "/img.json":
            self._send('{"photoUrl":"https://x.example.com/a.png"}'.encode("utf-8"),
                       "application/json; charset=utf-8")
            return
        self.send_error(404)


def wait(task, timeout=30, expect=("done", "error")):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if task.status in expect:
            return True
        time.sleep(0.1)
    return False


fails = []


def check(name, cond, extra=""):
    if cond:
        print("PASS", name)
    else:
        print("FAIL", name, extra)
        fails.append(name)


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = srv.server_address[1]
    base = "http://127.0.0.1:%d" % port
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    tmp = tempfile.mkdtemp(prefix="jf_sniff_")

    try:
        # 1 整段分享文案抽链接（去尾部中文与标点）
        text = "快看这个视频！" + base + "/page，太好看了吧。"
        u = sniffer.extract_url(text)
        check("extract share text url", u == base + "/page", u)

        text2 = "链接：" + base + "/share （复制打开抖音）"
        check("extract strips trailing paren", sniffer.extract_url(text2) == base + "/share")
        check("extract no link", sniffer.extract_url("今天天气不错") == "")

        # 2 302 跳转链：最终 URL 记录，候选来自落地页
        r = sniffer.sniff(base + "/share")
        check("redirect ok", r["ok"], r["message"])
        check("redirect final url", r["page_url"].endswith("/page"), r["page_url"])
        check("redirect chain recorded", len(r["url_chain"]) == 2, str(r["url_chain"]))
        check("redirect candidate present", r["candidate"] is not None)

        # 3 unicode / 反斜杠转义被解开
        cands = sniffer.extract_candidates(PAGE_HTML, base + "/page")
        urls = [c["url"] for c in cands]
        check("unescape backslash slash", any("https://cdn.example.com/escaped/play.mp4" == u for u in urls), urls)
        check("no leftover escape", not any(("\\/" in u or "\\u002F" in u) for u in urls))

        # 4 og:video 抽取
        check("og:video extracted", any(u.endswith("/og.mp4") for u in urls), urls)

        # 5 <video src> 相对地址按 base 解析
        check("video src relative resolved", any(u == base + "/rel/video.mp4" for u in urls), urls)

        # 6 play_addr url_list 抽取
        check("play_addr url_list extracted", any(u.endswith("/addr.mp4") for u in urls), urls)

        # 7 m3u8 master 选最高带宽
        rm = sniffer.sniff(base + "/master.m3u8")
        check("master only m3u8 category", rm["category"] == "only_m3u8", rm["category"])
        check("master picks highest bandwidth",
              rm["candidate"] is not None and rm["candidate"]["url"].endswith("/hi.m3u8"),
              rm["candidate"]["url"] if rm["candidate"] else None)
        check("master bandwidth recorded", rm["candidate"] and rm["candidate"]["bandwidth"] == 2000000,
              rm["candidate"]["bandwidth"] if rm["candidate"] else None)

        # 8 pick_best 排除预览 / 封面 / 水印小图
        fake = [
            {"url": "https://x/preview_1.mp4", "type": "video", "source": "regex", "bandwidth": 0, "referer": ""},
            {"url": "https://x/thumb.jpg", "type": "video", "source": "regex", "bandwidth": 0, "referer": ""},
            {"url": "https://x/cover.mp4", "type": "video", "source": "regex", "bandwidth": 0, "referer": ""},
            {"url": "https://x/playwm/wm.mp4", "type": "video", "source": "regex", "bandwidth": 0, "referer": ""},
            {"url": "https://x/real.mp4", "type": "video", "source": "json", "bandwidth": 0, "referer": ""},
        ]
        best = sniffer.pick_best(fake)
        check("pick_best excludes preview", best is not None and best["url"].endswith("/real.mp4"),
              best["url"] if best else None)

        # 8b 真实页面择优：不能选中 preview / thumb / cover / playwm
        check("best not preview", r["candidate"] is not None and
              not sniffer.PREVIEW_RE.search(r["candidate"]["url"]), r["candidate"]["url"])

        # 9 失败分类
        r_login = sniffer.sniff(base + "/login.html")
        check("fail no_media", r_login["category"] == "no_media", r_login["category"])
        check("no_media chinese hint", "登录" in r_login["message"] or "直链" in r_login["message"],
              r_login["message"])

        r_json = sniffer.sniff(base + "/data.json")
        check("fail interface_data", r_json["category"] == "interface_data", r_json["category"])
        check("interface_data chinese hint", "接口" in r_json["message"], r_json["message"])

        r_forbid = sniffer.sniff(base + "/protected.mp4")
        check("fail forbidden 403", r_forbid["category"] == "forbidden", r_forbid["category"])
        check("forbidden hint referer cookie", "Referer" in r_forbid["message"] or "Cookie" in r_forbid["message"],
              r_forbid["message"])

        r_missing = sniffer.sniff(base + "/missing")
        check("fail invalid 404", r_missing["category"] == "invalid", r_missing["category"])

        r_nolink = sniffer.sniff("没有任何链接的一段文字")
        check("fail no_link", r_nolink["category"] == "no_link", r_nolink["category"])

        # 10 Referer 贯通：解码结果带页面 URL，引擎带 Referer 200，不带 403
        r2 = sniffer.sniff(base + "/page2")
        check("page2 candidate", r2["ok"] and r2["candidate"] is not None, r2["message"])
        check("candidate referer = page url",
              r2["candidate"] is not None and r2["candidate"]["referer"] == r2["page_url"],
              (r2["candidate"]["referer"] if r2["candidate"] else None, r2["page_url"]))

        m_noref = engine.Manager({"dest_dir": tmp, "segments": 4})
        t_noref = m_noref.add(r2["candidate"]["url"], "noref.mp4", tmp)
        wait(t_noref)
        check("no referer 403", t_noref.status == "error" and "403" in t_noref.error,
              t_noref.status + " " + t_noref.error)

        m_ref = engine.Manager({"dest_dir": tmp, "segments": 4})
        t_ref = m_ref.add(r2["candidate"]["url"], "withref.mp4", tmp,
                          referer=r2["candidate"]["referer"])
        wait(t_ref)
        check("with referer 200 done", t_ref.status == "done", t_ref.status + " " + t_ref.error)
        check("referer file size", os.path.exists(t_ref.path) and os.path.getsize(t_ref.path) == len(PROT_BODY),
              os.path.getsize(t_ref.path) if os.path.exists(t_ref.path) else -1)

        # 11 本地造数据：页面里已给出的直链字段仍能解析
        r_ks = sniffer.sniff(base + "/kuaishou")
        check("kuaishou srcNoMark", r_ks["ok"] and r_ks["candidate"]["url"].endswith("/k.mp4"),
              r_ks["candidate"]["url"] if r_ks["candidate"] else None)

        r_wb = sniffer.sniff(base + "/weibo")
        check("weibo stream_url", r_wb["ok"] and r_wb["candidate"]["url"].endswith("/w.mp4"),
              r_wb["candidate"]["url"] if r_wb["candidate"] else None)

        r_bi = sniffer.sniff(base + "/bilibili")
        check("bilibili baseUrl", r_bi["ok"] and r_bi["candidate"]["url"].endswith("/b.m4v"),
              r_bi["candidate"]["url"] if r_bi["candidate"] else None)

        # 11b 平台域名识别（短链 / 落地域都能命中）
        p_dy, m_dy = sniffer._detect_platform("https://v.douyin.com/KtxB1Wpc-XQ/")
        check("platform douyin short link", p_dy == "douyin" and "签名" in m_dy, p_dy)
        p_dy2, _ = sniffer._detect_platform(
            "https://www.iesdouyin.com/share/video/7708959326643308538/")
        check("platform douyin ies domain", p_dy2 == "douyin", p_dy2)
        p_bi, _ = sniffer._detect_platform("https://www.bilibili.com/video/BV1GJ411x7h7/")
        check("platform bilibili", p_bi == "bilibili", p_bi)
        p_xhs, _ = sniffer._detect_platform("https://www.xiaohongshu.com/explore/abc")
        check("platform xiaohongshu", p_xhs == "xiaohongshu", p_xhs)
        p_ks, _ = sniffer._detect_platform("https://v.kuaishou.com/abc123")
        check("platform kuaishou", p_ks == "kuaishou", p_ks)
        p_wb, _ = sniffer._detect_platform("https://t.cn/abc123")
        check("platform weibo", p_wb == "weibo", p_wb)

        # 11c 单页应用空壳检测：埋了状态块但无视频详情字段
        shell_body = ('{"_ROUTER_DATA":{"loaderData":{"video_(id)/page":'
                       '{"renderInSSR":1,"isSpider":false}}}}')
        check("router data shell detected", sniffer._is_shell(shell_body) is True)
        check("normal page not shell", sniffer._is_shell(PAGE_HTML) is False)

        # 11d 平台级失败分类：不硬解、不拼造地址
        cat, msg, plat = sniffer.classify_failure(
            "text/html", shell_body,
            "https://www.iesdouyin.com/share/video/7708959326643308538/")
        check("douyin platform_locked", cat == "platform_locked" and plat == "douyin", cat)
        check("douyin message sign hint", "签名" in msg and "保存本地" in msg, msg)

        bili_shell = ('<html><script>window.__INITIAL_STATE__={"playinfo":{}};</script></html>')
        cat2, msg2, plat2 = sniffer.classify_failure(
            "text/html", bili_shell, "https://www.bilibili.com/video/BV1GJ411x7h7/")
        check("bilibili platform_locked", cat2 == "platform_locked" and plat2 == "bilibili", cat2)
        check("bilibili message login hint", "登录" in msg2, msg2)

        cat3, _, plat3 = sniffer.classify_failure(
            "text/html", "<html>笔记正文</html>", "https://www.xiaohongshu.com/explore/abc")
        check("xhs platform_locked", cat3 == "platform_locked" and plat3 == "xiaohongshu", cat3)

        cat4, _, _ = sniffer.classify_failure(
            "text/html", shell_body, base + "/douyin")
        check("unknown shell not no_media", cat4 == "interface_data", cat4)

        # 11e 分享文案：中文描述 + 短链，链接完整抽出
        share = "哈哈哈这个视频太绝了，快来看：https://v.douyin.com/KtxB1Wpc-XQ/ 复制打开抖音"
        check("extract short link in cn text",
              sniffer.extract_url(share) == "https://v.douyin.com/KtxB1Wpc-XQ/",
              sniffer.extract_url(share))

        # 11b 图片扩展名排除；直链 URL 兜底识别
        r_img = sniffer.sniff(base + "/img.json")
        check("image ext rejected", r_img["candidate"] is None,
              (r_img["candidate"] or {}).get("url"))

        r_direct = sniffer.sniff(base + "/direct.mp4")
        check("direct mp4 recognized", r_direct["ok"] and r_direct["candidate"] is not None,
              r_direct["message"])
        check("direct source tag", r_direct["candidate"]["source"] == "direct",
              r_direct["candidate"]["source"] if r_direct["candidate"] else None)
        check("direct url kept", r_direct["candidate"]["url"].endswith("/direct.mp4"),
              r_direct["candidate"]["url"] if r_direct["candidate"] else None)

        # 12 描述文案
        desc = sniffer.describe(r2["candidate"])
        check("describe one-liner", "视频" in desc and "127.0.0.1" in desc, desc)

        print("\n%d fail(s)" % len(fails))
    finally:
        srv.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
    sys.exit(1 if fails else 0)
