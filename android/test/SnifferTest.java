import com.jifeng.download.Sniffer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;

public class SnifferTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name + " :: " + detail);
        }
    }

    static boolean hasUrl(Sniffer.Result r, String suffix) {
        for (Sniffer.Hit h : r.hits) if (h.url.contains(suffix)) return true;
        return false;
    }

    public static void main(String[] a) throws Exception {
        final int port = 18899;
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);

        s.createContext("/file", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                byte[] b = new byte[16];
                x.getResponseHeaders().set("Content-Type", "application/octet-stream");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.createContext("/page", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String html = "<html><head><meta property=\"og:video\" content=\"https://v.example.com/a.mp4\">"
                        + "<script>var d={\"play_addr\":\"https:\\u002F\\u002Fv2.example.com\\u002Fplay\\u002Fb.mp4?x=1&y=2\"};</script>"
                        + "<video src=\"/local/c.mp4\"></video></head><body>分享给你</body></html>";
                byte[] b = html.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                x.getResponseHeaders().add("Set-Cookie", "session=abc; Path=/");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.createContext("/redir", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                x.getResponseHeaders().set("Location", "/page2");
                x.sendResponseHeaders(302, -1);
                x.close();
            }
        });

        s.createContext("/page2", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String html = "<html><meta property=\"og:video\" content=\"https://v.example.com/redirected.mp4\"></html>";
                byte[] b = html.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                x.getResponseHeaders().add("Set-Cookie", "uid=9; Path=/");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.createContext("/noredir", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String html = "<html><body>登录后观看</body></html>";
                byte[] b = html.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.createContext("/json", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String body = "{\"code\":0,\"msg\":\"need login\"}";
                byte[] b = body.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", "application/json");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.createContext("/forbidden", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                x.sendResponseHeaders(403, -1);
                x.close();
            }
        });

        s.createContext("/gone", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                x.sendResponseHeaders(404, -1);
                x.close();
            }
        });

        s.createContext("/m3u8page", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String html = "<html><script>var u=\"https://x.example.com/a.m3u8\";</script></html>";
                byte[] b = html.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.createContext("/douyin", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String html = "<html><script>var d={\"video_id\":\"v123abc\","
                        + "\"play_addr\":{\"url_list\":[\"https://aweme.example/play/sd.mp4\"]}};</script></html>";
                byte[] b = html.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.createContext("/kuaishou", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String html = "<html><script>var d={\"srcNoMark\":\"https://k.example.com/nomark.mp4\","
                        + "\"photoUrl\":\"https://k.example.com/photo.jpg\"};</script></html>";
                byte[] b = html.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.createContext("/weibo", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String html = "<html><script>var d={\"stream_url\":\"https://w.example.com/v.mp4\"};</script></html>";
                byte[] b = html.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.createContext("/bili", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String html = "<html><script>var d={\"baseUrl\":\"https://b.example.com/play.m4s\","
                        + "\"backupUrl\":[\"https://b2.example.com/play.m4s\"]};</script></html>";
                byte[] b = html.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                x.sendResponseHeaders(200, b.length);
                OutputStream o = x.getResponseBody();
                o.write(b);
                o.close();
            }
        });

        s.start();

        // 1. 整段分享文案抽链接：去尾部中文与标点
        String share = "看这个视频太搞笑了 7.43 复制打开抖音…https://v.douyin.com/abc/ 看看吧，";
        check("extractUrl share text", "https://v.douyin.com/abc/".equals(Sniffer.extractUrl(share)),
                Sniffer.extractUrl(share));
        check("extractUrl plain", "https://v.example.com/a.mp4".equals(
                Sniffer.extractUrl("https://v.example.com/a.mp4")), Sniffer.extractUrl("https://v.example.com/a.mp4"));
        check("extractUrl empty", "".equals(Sniffer.extractUrl("没有链接")), "");

        // 2. 本地 HTML：og:video / unicode 转义 / 相对路径 / Referer / Cookie 贯通
        Sniffer.Result r = Sniffer.resolve("7.99 复制打开抖音 http://127.0.0.1:" + port + "/page 看看");
        check("html hits>=3", r.hits.size() >= 3, "hits=" + r.hits.size());
        check("unescape \\u002F direct link", hasUrl(r, "https://v2.example.com/play/b.mp4?x=1&y=2"),
                dumpUrls(r));
        check("relative video src resolved", hasUrl(r, "http://127.0.0.1:" + port + "/local/c.mp4"), dumpUrls(r));
        boolean refOk = !r.hits.isEmpty() && r.hits.get(0).referer.contains("/page");
        check("referer = page url", refOk, r.hits.isEmpty() ? "empty" : r.hits.get(0).referer);
        boolean cookieOk = r.cookie.contains("session=abc")
                && !r.hits.isEmpty() && r.hits.get(0).cookie.contains("session=abc");
        check("cookie collected and attached", cookieOk, r.cookie);

        // 3. 302 逐跳记录与最终 URL / 页面 Referer
        Sniffer.Result r2 = Sniffer.resolve("http://127.0.0.1:" + port + "/redir");
        check("redirect hops recorded", r2.hops.size() >= 1 && r2.hops.get(0).contains("/page2"),
                r2.hops.toString());
        check("redirect final hit", hasUrl(r2, "https://v.example.com/redirected.mp4"), dumpUrls(r2));
        check("redirect referer=page2", !r2.hits.isEmpty() && r2.hits.get(0).referer.contains("/page2"),
                r2.hits.isEmpty() ? "empty" : r2.hits.get(0).referer);

        // 4. 多候选择优：排除 playwm / thumb / cover / poster / _1.mp4 / preview
        java.util.List<Sniffer.Hit> picks = new java.util.ArrayList<>();
        picks.add(new Sniffer.Hit("https://x.example.com/real.mp4", "MP4", "t", "", ""));
        picks.add(new Sniffer.Hit("https://x.example.com/playwm/wm.mp4", "MP4", "t", "", ""));
        picks.add(new Sniffer.Hit("https://x.example.com/thumb.jpg", "文件", "t", "", ""));
        picks.add(new Sniffer.Hit("https://x.example.com/cover.jpg", "文件", "t", "", ""));
        picks.add(new Sniffer.Hit("https://x.example.com/preview_1.mp4", "MP4", "t", "", ""));
        picks.add(new Sniffer.Hit("https://x.example.com/real_1.mp4", "MP4", "t", "", ""));
        Sniffer.Hit best = Sniffer.bestHit(picks);
        check("bestHit picks clean mp4", best != null && best.url.equals("https://x.example.com/real.mp4"),
                best == null ? "null" : best.url);

        // 5. 失败分类
        check("no media html -> REASON_NO_MEDIA",
                Sniffer.REASON_NO_MEDIA.equals(Sniffer.resolve("http://127.0.0.1:" + port + "/noredir").failReason),
                Sniffer.resolve("http://127.0.0.1:" + port + "/noredir").failReason);
        check("m3u8 only -> REASON_M3U8_ONLY",
                Sniffer.REASON_M3U8_ONLY.equals(Sniffer.resolve("http://127.0.0.1:" + port + "/m3u8page").failReason),
                Sniffer.resolve("http://127.0.0.1:" + port + "/m3u8page").failReason);
        check("403 -> REASON_FORBIDDEN",
                Sniffer.REASON_FORBIDDEN.equals(Sniffer.resolve("http://127.0.0.1:" + port + "/forbidden").failReason),
                Sniffer.resolve("http://127.0.0.1:" + port + "/forbidden").failReason);
        check("404 -> REASON_REDIRECT",
                Sniffer.REASON_REDIRECT.equals(Sniffer.resolve("http://127.0.0.1:" + port + "/gone").failReason),
                Sniffer.resolve("http://127.0.0.1:" + port + "/gone").failReason);
        check("json -> REASON_JSON",
                Sniffer.REASON_JSON.equals(Sniffer.resolve("http://127.0.0.1:" + port + "/json").failReason),
                Sniffer.resolve("http://127.0.0.1:" + port + "/json").failReason);

        // 6. 平台字段：抖音 video_id 拼 play 地址；快手 / 微博 / B站
        Sniffer.Result rd = Sniffer.resolve("http://127.0.0.1:" + port + "/douyin");
        check("douyin video_id play url", hasUrl(rd, "https://aweme.snssdk.com/aweme/v1/play/?video_id=v123abc"),
                dumpUrls(rd));
        check("douyin url_list kept", hasUrl(rd, "https://aweme.example/play/sd.mp4"), dumpUrls(rd));

        Sniffer.Result rk = Sniffer.resolve("http://127.0.0.1:" + port + "/kuaishou");
        check("kuaishou srcNoMark", hasUrl(rk, "https://k.example.com/nomark.mp4"), dumpUrls(rk));

        Sniffer.Result rw = Sniffer.resolve("http://127.0.0.1:" + port + "/weibo");
        check("weibo stream_url", hasUrl(rw, "https://w.example.com/v.mp4"), dumpUrls(rw));

        Sniffer.Result rb = Sniffer.resolve("http://127.0.0.1:" + port + "/bili");
        check("bilibili baseUrl", hasUrl(rb, "https://b.example.com/play.m4s"), dumpUrls(rb));

        // 7. 直链文件
        Sniffer.Result rf = Sniffer.resolve("http://127.0.0.1:" + port + "/file");
        check("direct octet file", rf.hits.size() == 1 && rf.hits.get(0).url.contains("/file"), dumpUrls(rf));

        s.stop(0);
        System.out.println("SnifferTest PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    static String dumpUrls(Sniffer.Result r) {
        StringBuilder sb = new StringBuilder();
        for (Sniffer.Hit h : r.hits) sb.append(h.url).append(" | ");
        return sb.toString();
    }
}
