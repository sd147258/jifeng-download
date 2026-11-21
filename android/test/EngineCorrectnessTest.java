import com.jifeng.download.DownloadEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/**
 * 下载正确性验收（桌面 JVM）：
 *  - html / json 落地页在 prepare 阶段被拒，不落盘；
 *  - 防盗链 403 不重试并给中文原因，带 Referer 可正常下载；
 *  - Content-Type 推断扩展名；文件头是网页/接口页判失败并删临时文件；
 *  - Content-Length 不符判错且保留断点；
 *  - m3u8 选最高带宽、分片拼接字节数一致；
 *  - 分段数自适应；.jfstate 保存 Referer/Cookie，续传后仍带。
 */
public class EngineCorrectnessTest {

    static final byte[] RANDOM = new byte[200 * 1024];
    static final byte[] SEG0 = new byte[30 * 1024];
    static final byte[] SEG1 = new byte[20 * 1024];
    static final byte[] INIT = new byte[4 * 1024];
    static byte[] COMB;
    static final String HTML = "<!doctype html><html><head><title>登录</title></head>"
            + "<body>请先登录后再下载</body></html>";
    static final String JSON = "{\"code\":403,\"message\":\"need login\",\"data\":null}";
    static int fails = 0;

    public static void main(String[] args) throws Exception {
        new Random(7).nextBytes(RANDOM);
        new Random(8).nextBytes(SEG0);
        new Random(9).nextBytes(SEG1);
        new Random(10).nextBytes(INIT);
        COMB = concat(INIT, SEG0, SEG1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new Dispatcher());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        File dir = new File("test_dlc");
        deleteDir(dir);
        dir.mkdirs();

        // 1 html 落地页必须被拒，且不落盘
        DownloadEngine e1 = new DownloadEngine(dir, null);
        DownloadEngine.Task t1 = e1.add(base + "/page", null);
        e1.start(t1);
        wait(t1, 15000);
        check("html rejected", "error".equals(t1.status) && t1.error.contains("网页/接口数据"),
                t1.status + " " + t1.error);
        check("html no data file", !t1.file().exists(), t1.file().getAbsolutePath());

        // 2 json 错误体必须被拒
        DownloadEngine e2 = new DownloadEngine(dir, null);
        DownloadEngine.Task t2 = e2.add(base + "/error", null);
        e2.start(t2);
        wait(t2, 15000);
        check("json rejected", "error".equals(t2.status) && t2.error.contains("网页/接口数据"),
                t2.status + " " + t2.error);
        check("json no data file", !t2.file().exists(), t2.file().getAbsolutePath());

        // 3 防盗链：不带 Referer -> 403 致命，中文原因；带 Referer -> 正常下载
        DownloadEngine e3 = new DownloadEngine(dir, null);
        DownloadEngine.Task t3 = e3.add(base + "/hotlink", null);
        e3.start(t3);
        wait(t3, 20000);
        check("hotlink 403 fatal", "error".equals(t3.status) && t3.error.contains("防盗链"),
                t3.status + " " + t3.error);

        DownloadEngine e3b = new DownloadEngine(dir, null);
        DownloadEngine.Task t3b = e3b.add(base + "/hotlink", null, "http://page.example/album", "k=v");
        e3b.start(t3b);
        wait(t3b, 20000);
        check("hotlink with referer done", "done".equals(t3b.status), t3b.status + " " + t3b.error);
        check("hotlink size", t3b.file().length() == RANDOM.length, String.valueOf(t3b.file().length()));

        // 4 Content-Type 推断扩展名：URL 无扩展名，video/mp4 -> .mp4
        DownloadEngine e4 = new DownloadEngine(dir, null);
        DownloadEngine.Task t4 = e4.add(base + "/movie", null);
        e4.start(t4);
        wait(t4, 20000);
        check("content-type ext mp4", "done".equals(t4.status) && t4.filename.endsWith(".mp4"),
                t4.status + " " + t4.filename + " " + t4.error);

        // 5 文件头是网页错误页：成品校验判失败并删除临时文件，绝不发布
        DownloadEngine e5 = new DownloadEngine(dir, null);
        DownloadEngine.Task t5 = e5.add(base + "/htmlbody", null);
        e5.start(t5);
        wait(t5, 20000);
        check("error-page head rejected", "error".equals(t5.status) && t5.error.contains("网页/接口错误页"),
                t5.status + " " + t5.error);
        check("error-page temp deleted", !t5.file().exists(), t5.file().getAbsolutePath());

        // 6 Content-Length 不符：判错且保留断点状态（原始 Socket 主动关连接，确保收到 EOF）
        ServerSocket badlenSs = new ServerSocket();
        badlenSs.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread badlenAcceptor = new Thread(() -> {
            while (!badlenSs.isClosed()) {
                try {
                    Socket s = badlenSs.accept();
                    new Thread(() -> serveBadLen(s)).start();
                } catch (Exception ignored) {
                }
            }
        });
        badlenAcceptor.setDaemon(true);
        badlenAcceptor.start();
        String badlenUrl = "http://127.0.0.1:" + badlenSs.getLocalPort() + "/badlen";
        DownloadEngine e6 = new DownloadEngine(dir, null);
        DownloadEngine.Task t6 = e6.add(badlenUrl, null);
        e6.start(t6);
        wait(t6, 20000);
        check("badlen error", "error".equals(t6.status) && t6.error.contains("大小与服务器声明不符"),
                t6.status + " " + t6.error);
        check("badlen state kept", t6.stateFile().exists(), t6.stateFile().getAbsolutePath());

        // 7 m3u8：选最高带宽，拼接字节数 = init + seg0 + seg1，输出 .mp4
        DownloadEngine e7 = new DownloadEngine(dir, null);
        DownloadEngine.Task t7 = e7.add(base + "/master.m3u8", null);
        e7.start(t7);
        wait(t7, 30000);
        check("m3u8 done", "done".equals(t7.status), t7.status + " " + t7.error);
        check("m3u8 ext mp4", "mp4".equals(t7.ext) && t7.filename.endsWith(".mp4"),
                t7.ext + " " + t7.filename);
        long expect = INIT.length + SEG0.length + SEG1.length;
        check("m3u8 concat bytes", t7.file().length() == expect,
                t7.file().length() + " / " + expect);

        // 7b BYTERANGE：init 与分片都按字节区间从同一文件取，拼接结果必须与整文件一致
        DownloadEngine e7b = new DownloadEngine(dir, null);
        DownloadEngine.Task t7b = e7b.add(base + "/br.m3u8", null);
        e7b.start(t7b);
        wait(t7b, 30000);
        check("byterange m3u8 done", "done".equals(t7b.status), t7b.status + " " + t7b.error);
        check("byterange concat bytes", t7b.file().length() == COMB.length,
                t7b.file().length() + " / " + COMB.length);
        check("byterange content md5", md5(t7b.file()).equals(md5(COMB)), "");

        // 8 分段数自适应：<2MB 单流；2–8MB 4 段；≥8MB 8 段封顶；不支持 Range 单流
        check("plan 1MB -> 1", DownloadEngine.planCount(1L * 1024 * 1024, true) == 1, "");
        check("plan 5MB -> 4", DownloadEngine.planCount(5L * 1024 * 1024, true) == 4, "");
        check("plan 50MB -> 8", DownloadEngine.planCount(50L * 1024 * 1024, true) == 8, "");
        check("plan 200MB -> 8", DownloadEngine.planCount(200L * 1024 * 1024, true) == 8, "");
        check("plan no-range -> 1", DownloadEngine.planCount(20L * 1024 * 1024, false) == 1, "");

        // 9 .jfstate 保存 Referer/Cookie，重启扫描后续传仍带
        File dir9 = new File("test_dlc2");
        deleteDir(dir9);
        dir9.mkdirs();
        DownloadEngine e9a = new DownloadEngine(dir9, null);
        DownloadEngine.Task p9 = e9a.add(base + "/ok.bin", null,
                "http://page.example/share", "session=abc; uid=1");
        e9a.start(p9);
        long w0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - w0 < 20000 && p9.downloaded < 16 * 1024
                && "running".equals(p9.status)) {
            Thread.sleep(20);
        }
        e9a.pause(p9);
        Thread.sleep(400);
        DownloadEngine e9 = new DownloadEngine(dir9, null);
        java.util.List<DownloadEngine.Task> loaded = e9.loadAll();
        check("resume state found", loaded.size() == 1, String.valueOf(loaded.size()));
        DownloadEngine.Task t9 = loaded.isEmpty() ? null : loaded.get(0);
        if (t9 != null) {
            check("referer kept in state", "http://page.example/share".equals(t9.referer), t9.referer);
            check("cookie kept in state", "session=abc; uid=1".equals(t9.cookie), t9.cookie);
            e9.resume(t9);
            wait(t9, 60000);
            check("resume done", "done".equals(t9.status), t9.status + " " + t9.error);
            check("resume content", md5(t9.file()).equals(md5(RANDOM)), "");
        }

        server.stop(0);
        System.out.println(fails == 0 ? "ALL PASS" : fails + " fail(s)");
        if (fails > 0) System.exit(1);
    }

    static void check(String name, boolean ok, String extra) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (extra == null || extra.length() == 0 ? "" : " -> " + extra));
        if (!ok) fails++;
    }

    static void wait(DownloadEngine.Task t, long ms) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            if ("error".equals(t.status) || "done".equals(t.status) || "canceled".equals(t.status)) return;
            Thread.sleep(100);
        }
    }

    static String md5(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return java.util.Arrays.toString(md.digest(b));
    }

    static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        return java.util.Arrays.toString(md.digest());
    }

    static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    static void serveBadLen(Socket s) {
        try {
            InputStream in = s.getInputStream();
            byte[] buf = new byte[1024];
            StringBuilder req = new StringBuilder();
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 2000) {
                int n = in.read(buf);
                if (n < 0) break;
                req.append(new String(buf, 0, n));
                if (req.toString().endsWith("\r\n\r\n")) break;
            }
            OutputStream o = s.getOutputStream();
            String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                    + "Content-Length: 100000\r\nConnection: close\r\n\r\n";
            o.write(head.getBytes("UTF-8"));
            o.write(new byte[1000]);
            o.flush();
            s.close();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    static class Dispatcher implements HttpHandler {
        public void handle(HttpExchange ex) {
            try {
                String path = ex.getRequestURI().getPath();
                switch (path) {
                    case "/page":
                        body(ex, 200, "text/html; charset=utf-8", HTML.getBytes(StandardCharsets.UTF_8));
                        break;
                    case "/error":
                        body(ex, 200, "application/json; charset=utf-8", JSON.getBytes(StandardCharsets.UTF_8));
                        break;
                    case "/hotlink": {
                        String ref = ex.getRequestHeaders().getFirst("Referer");
                        if (ref == null || ref.length() == 0) {
                            body(ex, 403, "text/html; charset=utf-8", HTML.getBytes(StandardCharsets.UTF_8));
                        } else {
                            octet(ex, RANDOM);
                        }
                        break;
                    }
                    case "/movie":
                        octetTyped(ex, "video/mp4", RANDOM);
                        break;
                    case "/htmlbody":
                        octet(ex, HTML.getBytes(StandardCharsets.UTF_8));
                        break;
                    case "/ok.bin":
                        slowOctet(ex, RANDOM);
                        break;
                    case "/master.m3u8":
                        String master = "#EXT-X-STREAM-INF:BANDWIDTH=400000\n/v1.m3u8\n"
                                + "#EXT-X-STREAM-INF:BANDWIDTH=1500000\n/v3.m3u8\n"
                                + "#EXT-X-STREAM-INF:BANDWIDTH=800000\n/v2.m3u8\n";
                        body(ex, 200, "application/vnd.apple.mpegurl", master.getBytes(StandardCharsets.UTF_8));
                        break;
                    case "/v3.m3u8":
                        String media = "#EXT-X-MAP:URI=\"/init.mp4\"\n#EXTINF:4.0,\n/seg0.m4s\n"
                                + "#EXTINF:4.0,\n/seg1.m4s\n";
                        body(ex, 200, "application/vnd.apple.mpegurl", media.getBytes(StandardCharsets.UTF_8));
                        break;
                    case "/init.mp4":
                        octet(ex, INIT);
                        break;
                    case "/seg0.m4s":
                        octet(ex, SEG0);
                        break;
                    case "/seg1.m4s":
                        octet(ex, SEG1);
                        break;
                    case "/br.m3u8": {
                        String br = "#EXT-X-MAP:URI=\"/combo.mp4\",BYTERANGE=\"4096@0\"\n"
                                + "#EXTINF:4.0,\n#EXT-X-BYTERANGE:30720@4096\n/combo.mp4\n"
                                + "#EXTINF:4.0,\n#EXT-X-BYTERANGE:20480@34816\n/combo.mp4\n";
                        body(ex, 200, "application/vnd.apple.mpegurl", br.getBytes(StandardCharsets.UTF_8));
                        break;
                    }
                    case "/combo.mp4":
                        serveRange(ex, COMB, ex.getRequestHeaders().getFirst("Range"));
                        break;
                    default:
                        ex.sendResponseHeaders(404, -1);
                }
            } catch (Exception ignored) {
            } finally {
                ex.close();
            }
        }

        private void body(HttpExchange ex, int code, String type, byte[] b) throws Exception {
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(code, b.length);
            OutputStream out = ex.getResponseBody();
            out.write(b);
            out.close();
        }

        private void octet(HttpExchange ex, byte[] data) throws Exception {
            octetTyped(ex, "application/octet-stream", data);
        }

        /** 分小块慢速吐出，给暂停/续传用例留出可中断的窗口。 */
        private void slowOctet(HttpExchange ex, byte[] data) throws Exception {
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.sendResponseHeaders(200, data.length);
            OutputStream out = ex.getResponseBody();
            for (int i = 0; i < data.length; i += 4096) {
                int n = Math.min(4096, data.length - i);
                out.write(data, i, n);
                out.flush();
                Thread.sleep(10);
            }
            out.close();
        }

        private void serveRange(HttpExchange ex, byte[] data, String range) throws Exception {
            long start = 0;
            long end = data.length - 1;
            if (range != null && range.startsWith("bytes=")) {
                String v = range.substring(6);
                int d = v.indexOf('-');
                String s = v.substring(0, d);
                String e = v.substring(d + 1);
                if (s.length() > 0) start = Long.parseLong(s);
                if (e.length() > 0) end = Long.parseLong(e);
            }
            byte[] slice = Arrays.copyOfRange(data, (int) start, (int) end + 1);
            if (range != null) {
                ex.getResponseHeaders().set("Content-Range",
                        "bytes " + start + "-" + end + "/" + data.length);
                ex.sendResponseHeaders(206, slice.length);
            } else {
                ex.sendResponseHeaders(200, slice.length);
            }
            OutputStream out = ex.getResponseBody();
            out.write(slice);
            out.close();
        }

        private void octetTyped(HttpExchange ex, String type, byte[] data) throws Exception {
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            ex.sendResponseHeaders(200, data.length);
            OutputStream out = ex.getResponseBody();
            out.write(data);
            out.close();
        }
    }
}
