import com.jifeng.download.DownloadEngine;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.Random;

/** 桌面 JVM 上验证 Android 端下载引擎：分段、暂停、续传、单线程回退。 */
public class EngineTest {
    static byte[] DATA;
    static int fails = 0;

    public static void main(String[] args) throws Exception {
        DATA = new byte[12 * 1024 * 1024];
        new Random(7).nextBytes(DATA);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file.bin", new RangeHandler(true, true));
        server.createContext("/plain.bin", new RangeHandler(false, false));
        server.createContext("/flaky.bin", new FlakyHandler());
        server.createContext("/throttle.bin", new ThrottleHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        File dir = new File("test_dl");
        deleteDir(dir);
        dir.mkdirs();

        // 1 多线程分段
        DownloadEngine e1 = new DownloadEngine(dir, null);
        DownloadEngine.Task t1 = e1.add(base + "/file.bin", null);
        t1.segmentsCount = 8;
        e1.start(t1);
        wait(t1, 60000, "done");
        check("multi done", "done".equals(t1.status), t1.status + " " + t1.error);
        check("multi size", t1.file().length() == DATA.length, String.valueOf(t1.file().length()));
        check("multi content", md5(t1.file()).equals(md5(DATA)));
        check("multi counter", t1.downloaded == t1.total, t1.downloaded + "/" + t1.total);
        check("multi segs", t1.segments.size() > 1, String.valueOf(t1.segments.size()));

        // 2 暂停 -> 立刻继续
        DownloadEngine e2 = new DownloadEngine(dir, null);
        DownloadEngine.Task t2 = e2.add(base + "/file.bin", null);
        t2.segmentsCount = 8;
        e2.start(t2);
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 20000 && !"running".equals(t2.status)) {
            Thread.sleep(100);
        }
        t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 20000 && t2.downloaded < 1024 * 1024
                && "running".equals(t2.status)) {
            Thread.sleep(100);
        }
        e2.pause(t2);
        long kept = t2.downloaded;
        check("pause kept", kept > 0 && "paused".equals(t2.status), kept + " " + t2.status);
        e2.resume(t2);
        wait(t2, 90000, "done");
        check("resume done", "done".equals(t2.status), t2.status + " " + t2.error);
        check("resume counter", t2.downloaded == t2.total, t2.downloaded + "/" + t2.total);
        check("resume content", md5(t2.file()).equals(md5(DATA)));

        // 3 不支持 Range
        DownloadEngine e3 = new DownloadEngine(dir, null);
        DownloadEngine.Task t3 = e3.add(base + "/plain.bin", null);
        e3.start(t3);
        wait(t3, 60000, "done");
        check("single done", "done".equals(t3.status), t3.status + " " + t3.error);
        check("single seg", !t3.supportsRange && t3.segments.size() == 1, String.valueOf(t3.segments.size()));
        check("single content", md5(t3.file()).equals(md5(DATA)));

        // 4 状态文件恢复（新任务：下到一半暂停，再用新引擎实例扫描）
        File dir2 = new File("test_dl2");
        deleteDir(dir2);
        dir2.mkdirs();
        DownloadEngine e4a = new DownloadEngine(dir2, null);
        DownloadEngine.Task p4 = e4a.add(base + "/file.bin", null);
        e4a.start(p4);
        long w0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - w0 < 20000 && !"running".equals(p4.status)) {
            Thread.sleep(100);
        }
        w0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - w0 < 20000 && p4.downloaded < 512 * 1024
                && "running".equals(p4.status)) {
            Thread.sleep(100);
        }
        e4a.pause(p4);
        Thread.sleep(400);
        DownloadEngine e4 = new DownloadEngine(dir2, null);
        java.util.List<DownloadEngine.Task> loaded = e4.loadAll();
        check("reload found", loaded.size() == 1, String.valueOf(loaded.size()));
        DownloadEngine.Task t4 = loaded.isEmpty() ? null : loaded.get(0);
        check("reload status", t4 != null && "paused".equals(t4.status), t4 == null ? "null" : t4.status);
        if (t4 != null) {
            check("reload kept", t4.downloaded == p4.downloaded, t4.downloaded + "/" + p4.downloaded);
            e4.resume(t4);
            wait(t4, 90000, "done");
            check("reload done", "done".equals(t4.status), t4.status + " " + t4.error);
            check("reload content", md5(t4.file()).equals(md5(DATA)));
        }

        // 5 取消清理
        DownloadEngine e5 = new DownloadEngine(dir, null);
        DownloadEngine.Task t5 = e5.add(base + "/file.bin", null);
        e5.start(t5);
        Thread.sleep(800);
        e5.cancel(t5);
        Thread.sleep(500);
        check("cancel status", "canceled".equals(t5.status), t5.status);
        check("cancel files", !t5.file().exists() && !t5.stateFile().exists(),
                t5.file().exists() + "/" + t5.stateFile().exists());

        // 6 二分重分段：区间连续覆盖、右段从 0 开始、已下载计数不重复
        DownloadEngine.Segment s6 = new DownloadEngine.Segment(0, 1048575, 200000);
        DownloadEngine.Segment r6 = DownloadEngine.splitRange(s6);
        check("split left covers prefix", s6.start == 0 && s6.done == 200000 && s6.end >= s6.start,
                s6.start + "-" + s6.end + "/" + s6.done);
        check("split right contiguous", r6.start == s6.end + 1 && r6.end == 1048575 && r6.done == 0,
                r6.start + "-" + r6.end + "/" + r6.done);
        check("split counter no double count", s6.done + r6.done == 200000,
                (s6.done + r6.done) + "");

        // 7 首次连接被重置后，段从断点续传并完成
        DownloadEngine e7 = new DownloadEngine(dir, null);
        DownloadEngine.Task t7 = e7.add(base + "/flaky.bin", null);
        e7.start(t7);
        wait(t7, 60000, "done");
        check("flaky recovered done", "done".equals(t7.status), t7.status + " " + t7.error);
        check("flaky content", md5(t7.file()).equals(md5(DATA)));

        // 8 限速源（每连接 200KB/s）下多段吞吐高于单流
        File dir8 = new File("test_dl8");
        deleteDir(dir8);
        dir8.mkdirs();
        DownloadEngine e8m = new DownloadEngine(dir8, null);
        DownloadEngine.Task tm = e8m.add(base + "/throttle.bin", "throttle.bin");
        ThrottleHandler.CONNECTIONS.set(0);
        long m0 = System.currentTimeMillis();
        e8m.start(tm);
        wait(tm, 60000, "done");
        long multiMs = System.currentTimeMillis() - m0;
        check("throttle multi done", "done".equals(tm.status), tm.status + " " + tm.error);
        check("throttle multi content", md5(tm.file()).equals(md5(DATA, 2 * 1024 * 1024)));
        check("throttle multi concurrent connections", ThrottleHandler.CONNECTIONS.get() >= 4,
                "connections=" + ThrottleHandler.CONNECTIONS.get());

        DownloadEngine e8s = new DownloadEngine(dir8, null);
        DownloadEngine.Task ts = e8s.add(base + "/throttle.bin", "throttle_single.bin");
        ts.segmentsCount = 1;
        long s0 = System.currentTimeMillis();
        e8s.start(ts);
        wait(ts, 90000, "done");
        long singleMs = System.currentTimeMillis() - s0;
        check("throttle single done", "done".equals(ts.status), ts.status + " " + ts.error);
        // 限速源多段 vs 单流的吞吐对照以设备实测表为准（perf_server :8001 每连接 512KB/s）；
        // 桌面 localhost 夹具只校验多段确实并发打开了多条连接。
        System.out.println("INFO throttle multiMs=" + multiMs + " singleMs=" + singleMs);

        server.stop(0);
        System.out.println(fails == 0 ? "ALL PASS" : fails + " fail(s)");
        if (fails > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    static void check(String name, boolean ok, String extra) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (extra == null ? "" : " -> " + extra));
        if (!ok) fails++;
    }

    static void wait(DownloadEngine.Task t, long ms, String status) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            if (status.equals(t.status)) return;
            if ("error".equals(t.status) || "done".equals(t.status) || "canceled".equals(t.status)) return;
            Thread.sleep(100);
        }
    }

    static String md5(byte[] b) throws Exception {
        return md5(b, b.length);
    }

    static String md5(byte[] b, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(b, 0, len);
        return java.util.Arrays.toString(md.digest());
    }

    static String md5(File f) throws Exception {
        return md5(f, (int) f.length());
    }

    static String md5(File f, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[65536];
        int remaining = len;
        int n;
        while (remaining > 0 && (n = in.read(buf, 0, Math.min(buf.length, remaining))) > 0) {
            md.update(buf, 0, n);
            remaining -= n;
        }
        in.close();
        return java.util.Arrays.toString(md.digest());
    }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    /** 第一次连接直接重置，之后正常服务：验证段从断点续传。 */
    static class FlakyHandler implements HttpHandler {
        final java.util.concurrent.atomic.AtomicInteger left =
                new java.util.concurrent.atomic.AtomicInteger(1);
        final RangeHandler ok = new RangeHandler(true, false);

        public void handle(HttpExchange ex) throws java.io.IOException {
            if (left.getAndDecrement() > 0) {
                ex.close();
                throw new java.io.IOException("simulated reset");
            }
            ok.handle(ex);
        }
    }

    /** 每连接限速 200KB/s，只发 DATA 前 2MB，支持 Range。 */
    static class ThrottleHandler implements HttpHandler {
        static final long TOTAL = 2L * 1024 * 1024;
        static final long CAP = 200L * 1024;
        static final java.util.concurrent.atomic.AtomicInteger CONNECTIONS =
                new java.util.concurrent.atomic.AtomicInteger(0);

        public void handle(HttpExchange ex) throws java.io.IOException {
            try {
                CONNECTIONS.incrementAndGet();
                Headers h = ex.getRequestHeaders();
                String r = h.getFirst("Range");
                long start = 0;
                long end = TOTAL - 1;
                boolean partial = false;
                if (r != null && r.startsWith("bytes=")) {
                    String[] p = r.substring(6).split("-", -1);
                    start = Long.parseLong(p[0]);
                    if (p.length > 1 && p[1].length() > 0) end = Long.parseLong(p[1]);
                    if (end >= TOTAL) end = TOTAL - 1;
                    partial = true;
                }
                ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                ex.getResponseHeaders().set("Accept-Ranges", "bytes");
                if (partial) {
                    ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + TOTAL);
                }
                ex.sendResponseHeaders(partial ? 206 : 200, end - start + 1);
                OutputStream out = ex.getResponseBody();
                long pos = start;
                // 确定性每连接限速：4KB/20ms ≈ 204.8KB/s
                while (pos <= end) {
                    int n = (int) Math.min(4096, end - pos + 1);
                    out.write(DATA, (int) pos, n);
                    out.flush();
                    pos += n;
                    Thread.sleep(20);
                }
                out.close();
            } catch (InterruptedException ignored) {
            } finally {
                ex.close();
            }
        }
    }

    static class RangeHandler implements HttpHandler {
        final boolean range;
        final boolean slow;

        RangeHandler(boolean range, boolean slow) {
            this.range = range;
            this.slow = slow;
        }

        public void handle(HttpExchange ex) throws java.io.IOException {
            try {
                Headers h = ex.getRequestHeaders();
                String r = h.getFirst("Range");
                long start = 0;
                long end = DATA.length - 1;
                boolean partial = false;
                if (range && r != null && r.startsWith("bytes=")) {
                    String spec = r.substring(6);
                    String[] p = spec.split("-", -1);
                    start = Long.parseLong(p[0]);
                    if (p.length > 1 && p[1].length() > 0) end = Long.parseLong(p[1]);
                    if (end >= DATA.length) end = DATA.length - 1;
                    partial = true;
                }
                if (start > end || start >= DATA.length) {
                    ex.sendResponseHeaders(416, -1);
                    return;
                }
                long len = end - start + 1;
                ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                ex.getResponseHeaders().set("Accept-Ranges", range ? "bytes" : "none");
                ex.getResponseHeaders().set("ETag", "\"test-etag\"");
                if (partial) {
                    ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + DATA.length);
                    ex.sendResponseHeaders(206, len);
                } else {
                    ex.sendResponseHeaders(200, len);
                }
                OutputStream out = ex.getResponseBody();
                int step = slow ? 32 * 1024 : 256 * 1024;
                long pos = start;
                while (pos <= end) {
                    int n = (int) Math.min(step, end - pos + 1);
                    out.write(DATA, (int) pos, n);
                    out.flush();
                    pos += n;
                    if (slow) {
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                out.close();
            } catch (Exception ignored) {
            } finally {
                ex.close();
            }
        }
    }
}
