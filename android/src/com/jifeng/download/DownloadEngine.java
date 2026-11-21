package com.jifeng.download;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 纯 Java 下载引擎：多线程 Range 分段 + 断点续传，不依赖 Android，可在桌面 JVM 上测试。
 * 状态用纯文本 sidecar（<文件名>.jfstate）保存，便于跨进程恢复。
 *
 * v1.8 提速要点：
 *  - 段数两级自适应：先按文件大小起步，运行期实测有余量再二分扩段（有上限，且不超过 Range 能力）；
 *  - 连接层：http 走裸 Socket 连接池（Keep-Alive 复用、TCP_NODELAY、独立 connect/read 超时），
 *    https 回退 HttpURLConnection；不再每段 disconnect 掉可复用连接；
 *  - 线程调度：段任务提交到受控固定线程池，不再每段裸 new Thread；
 *  - 段失败按剩余区间二分重分段，新段独立重试预算，而不是整段从头重来；
 *  - 落盘：每段写独立临时件（.p<起始偏移>），段间无全局写锁竞争，完成后顺序合并成品；
 *  - 读超时 / 连接重置只续传当前段剩余区间，不整段失败；
 *  - probe 只发 Range: bytes=0-0 并立即断开，不把响应体读进内存；不支持 Range 的服务器退化单流。
 *
 * 既有护栏不回退：HTML/JSON 落地页拒绝、绝不产 .txt、扩展名三级兜底、m3u8 合并 + 转封装、
 * 403/404/410 致命不重试、成品文件头校验、长度不符保留断点。
 */
public class DownloadEngine {

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final int CHUNK = 64 * 1024;
    private static final long MIN_SEG = 256 * 1024;

    /** 全局限速（字节/秒），0 表示不限。所有分段共享一个令牌桶。 */
    public static volatile long speedLimit = 0;
    private static final Object LIM_LOCK = new Object();
    private static long limStart;
    private static long limBytes;

    /** 令牌桶：累计发放量不允许超过「时长 × 限速」，多出来的部分自己睡觉等。 */
    static void acquire(long bytes) {
        long limit = speedLimit;
        if (limit <= 0) return;
        long sleep;
        synchronized (LIM_LOCK) {
            long now = System.currentTimeMillis();
            if (limStart == 0 || now - limStart > 3000) {
                limStart = now;
                limBytes = 0;
            }
            long elapsed = now - limStart;
            long allowed = elapsed * limit / 1000L;
            limBytes += bytes;
            if (limBytes <= allowed) return;
            sleep = (limBytes - allowed) * 1000L / limit;
        }
        if (sleep > 0) {
            try {
                Thread.sleep(Math.min(sleep, 5000));
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static final int MAX_SEGMENTS = 8;
    /** 连接复用允许切到多小：剩余段小于它就不再切，避免碎片过多 */
    private static final long MIN_TAIL = 128 * 1024;

    /** 连接复用（动态分段）开关：置 false 只用于对照测试 */
    public static volatile boolean assistEnabled = true;
    private static final int SEG_RETRY = 3;
    private static final int MAX_RETRY = 5;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final long SAVE_INTERVAL_MS = 1500;
    private static final long SAVE_INTERVAL_BYTES = 2L * 1024 * 1024;
    private static final long EXPAND_WARMUP_MS = 1200;
    public static final String SUFFIX = ".jfstate";

    public static final String MSG_REJECTED_TYPE =
            "这个链接返回的是网页/接口数据，不是文件：可能需要登录、链接已失效或被防盗链拦截";
    public static final String MSG_ERROR_PAGE =
            "下载到的是网页/接口错误页：可能需要登录、链接已失效或被防盗链拦截，已删除临时文件";

    public interface Callback {
        void onUpdate(Task t);

        void onFinished(Task t);
    }

    /** 桌面/Android 通用的 HTTP 取字节接口，便于 m3u8 下载器在无 android.* 依赖下测试。 */
    public interface Fetcher {
        byte[] getBytes(String url, String referer, String cookie, String ua) throws IOException;

        /** 区间拉取：start 含、end 含；start<0 表示不指定区间。 */
        byte[] getBytes(String url, String referer, String cookie, String ua, long start, long end) throws IOException;

        String getText(String url, String referer, String cookie, String ua) throws IOException;
    }

    /** 默认实现：HttpURLConnection（Android 与桌面 JVM 均可用），供 m3u8 分片使用。 */
    public static class JavaNetFetcher implements Fetcher {
        public byte[] getBytes(String url, String referer, String cookie, String ua) throws IOException {
            return getBytes(url, referer, cookie, ua, -1, -1);
        }

        public byte[] getBytes(String url, String referer, String cookie, String ua, long start, long end)
                throws IOException {
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                String range = null;
                if (start >= 0) {
                    range = end >= 0 ? "bytes=" + start + "-" + end : "bytes=" + start + "-";
                }
                c = open(url, referer, cookie, ua, range);
                int code = c.getResponseCode();
                if (code != 200 && code != 206) throw new HttpException(code, httpMessage(code), !fatalCode(code));
                in = c.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (c != null) c.disconnect();
            }
        }

        public String getText(String url, String referer, String cookie, String ua) throws IOException {
            return new String(getBytes(url, referer, cookie, ua), "UTF-8");
        }
    }

    public static class Segment {
        public long start;
        public long end;   // inclusive, -1 = 未知
        public long done;
        public final Object lock = new Object();

        public Segment(long s, long e, long d) {
            start = s;
            end = e;
            done = d;
        }

        public long size() {
            return end < 0 ? -1 : end - start + 1;
        }
    }

    public static class Task {
        public String id;
        public String url;
        public String filename;
        public String destDir;
        public String status = "pending";   // pending/preparing/running/paused/done/error/canceled
        public String error = "";
        public long total = -1;
        public volatile long downloaded;
        public volatile double speed;
        public int segmentsCount = MAX_SEGMENTS;
        public boolean supportsRange;
        public String etag = "";
        public String ua = UA;
        public String referer = "";
        public String cookie = "";
        public String contentType = "";
        public String ext = "";            // 最终扩展名（不含点），空表示未定
        public String kind = "file";       // file / m3u8
        public String publicUri = "";
        public boolean btMulti;           // 磁力多文件种子：成品是一个目录
        public long finishedAt;          // 完成时间（毫秒）；>0 且状态 done 表示历史记录
        public long savedAt;             // 状态文件修改时间，用于历史排序
        public final List<Segment> segments = new ArrayList<>();
        public final Object lock = new Object();
        public volatile boolean stop;
        public volatile int generation;
        public volatile boolean restart;
        public volatile boolean streamSingle;   // 已被播放器挂上，禁止再拆段（保证临时件是从头连续写入的）
        RandomAccessFile raf;
        long lastBytes;
        long lastTick;
        long lastSave;
        long lastSaveBytes;

        public File file() {
            return new File(destDir, filename);
        }

        public File stateFile() {
            return new File(destDir, filename + SUFFIX);
        }

        /** 尚未完成的段数（用于界面展示“N 段”）。 */
        public int activeSegments() {
            int n = 0;
            for (Segment s : segments) {
                if (s.end < 0 || s.done < s.size()) n++;
            }
            return n;
        }

        public int percent() {
            if (total <= 0) return 0;
            return (int) Math.min(100, downloaded * 100L / total);
        }

        public String host() {
            if (url != null && url.startsWith("magnet:")) return "磁力链接";
            try {
                return new URL(url).getHost();
            } catch (Exception e) {
                return "";
            }
        }
    }

    private final File dir;
    private final Callback cb;
    private final Fetcher fetcher;
    private final ExecutorService ioPool;

    public DownloadEngine(File dir, Callback cb) {
        this(dir, cb, new JavaNetFetcher());
    }

    public DownloadEngine(File dir, Callback cb, Fetcher fetcher) {
        this.dir = dir;
        this.cb = cb;
        this.fetcher = fetcher == null ? new JavaNetFetcher() : fetcher;
        if (!dir.exists()) dir.mkdirs();
        ThreadFactory tf = new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread th = new Thread(r);
                th.setDaemon(true);
                th.setName("jifeng-io");
                return th;
            }
        };
        this.ioPool = Executors.newFixedThreadPool(MAX_SEGMENTS, tf);
    }

    public File dir() {
        return dir;
    }

    // ---------------------------------------------------------------- 添加
    public Task add(String url, String name) {
        return add(url, name, "", "");
    }

    public Task add(String url, String name, String referer, String cookie) {
        Task t = new Task();
        t.id = Long.toHexString(System.currentTimeMillis()) + Integer.toHexString(url.hashCode());
        t.url = url;
        t.referer = referer == null ? "" : referer;
        t.cookie = cookie == null ? "" : cookie;
        t.destDir = dir.getAbsolutePath();
        t.filename = safe(name != null ? name : nameFromUrl(url));
        return t;
    }

    // ---------------------------------------------------------------- 启动/恢复
    public void start(final Task t) {
        if ("running".equals(t.status) || "preparing".equals(t.status)) return;
        t.stop = false;
        t.status = "preparing";
        fire(t);
        if ("bt".equals(t.kind)) {
            Thread th = new Thread(new Runnable() {
                public void run() {
                    new BtDownloader(DownloadEngine.this, t).run();
                }
            }, "JfBt-" + Integer.toHexString(t.id.hashCode()));
            th.setDaemon(true);
            th.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread th2, Throwable err) {
                    try {
                        android.util.Log.i("JfBt", "uncaught on " + th2.getName() + ": " + err);
                    } catch (Throwable ignored) {
                    }
                    BtDownloader.writeCrash(th2.getName() + " uncaught: " + err);
                }
            });
            th.start();
            return;
        }
        ioPool.execute(new Runnable() {
            public void run() {
                prepare(t);
            }
        });
    }

    // ---------------------------------------------------------------- 磁力任务回调（供 BtDownloader 用）
    void btFire(Task t) {
        fire(t);
    }

    void btFinish(Task t) {
        if (t.btMulti) {
            // 多文件种子：成品是一个目录，分片已逐片 SHA1 校验过，不能再走单文件的魔数改名逻辑
            t.status = "done";
            t.downloaded = t.total;
            t.error = "";
            if (t.stateFile().exists()) t.stateFile().delete();
        } else {
            finishArtifact(t);
        }
        fire(t);
        if (cb != null) cb.onFinished(t);
    }

    void btFail(Task t, String msg) {
        t.downloaded = 0;
        fail(t, msg);
        save(t);
        fire(t);
        if (cb != null) cb.onFinished(t);
    }

    private void prepare(Task t) {
        try {
            if (!dir.exists()) dir.mkdirs();
            if (t.url != null && t.url.toLowerCase().startsWith("ftp://")) {
                Thread th = new Thread(new Runnable() {
                    public void run() {
                        ftpRun(t);
                    }
                }, "jf-ftp");
                th.setDaemon(true);
                th.start();
                return;
            }
            Probe p = probe(t);
            if ("canceled".equals(t.status)) return;
            if (t.stop || "paused".equals(t.status)) {
                if (t.segments.isEmpty()) initMeta(t, p);
                save(t);
                fire(t);
                return;
            }
            t.contentType = p.contentType;

            if (isM3u8(t.url, p.contentType)) {
                t.kind = "m3u8";
                startM3u8(t, p);
                return;
            }

            String candidate = p.filename != null && p.filename.length() > 0
                    ? p.filename : nameFromUrl(t.url);
            if (rejectedType(p.contentType, candidate)) {
                t.status = "error";
                t.error = MSG_REJECTED_TYPE;
                closeQuiet(t);
                fire(t);
                if (cb != null) cb.onFinished(t);
                return;
            }

            if (tryResume(t, p)) {
                if (t.stop) {
                    save(t);
                    fire(t);
                    return;
                }
                t.status = "running";
                fire(t);
                run(t);
                return;
            }
            initMeta(t, p);
            save(t);
            if (t.stop) return;
            t.status = "running";
            fire(t);
            run(t);
        } catch (Exception e) {
            fail(t, friendly(e));
            closeQuiet(t);
            save(t);
            fire(t);
            if (cb != null) cb.onFinished(t);
        }
    }

    // ---------------------------------------------------------------- FTP 单流
    /**
     * FTP 没有 Range，只能一条连接从头/断点拉到底：SIZE 拿总长，REST 续传，
     * 每 64 KB 报一次进度；暂停/取消靠 stop 标志，中断后自动续传最多 5 轮。
     */
    private void ftpRun(final Task t) {
        t.kind = "ftp";
        t.supportsRange = false;
        t.status = "running";
        t.stop = false;
        t.speed = 0;
        t.lastBytes = t.downloaded;
        t.lastTick = System.currentTimeMillis();
        fire(t);
        int attempts = 0;
        while (!t.stop) {
            long have = t.file().exists() ? t.file().length() : 0;
            if (t.total > 0 && have >= t.total) break;
            try {
                t.downloaded = have;
                long total = FtpDownloader.fetch(t.url, t.file(), have, new FtpDownloader.Sink() {
                    public boolean onData(int len) {
                        t.downloaded += len;
                        long now = System.currentTimeMillis();
                        long dt = now - t.lastTick;
                        if (dt >= 500) {
                            t.speed = Math.max(0, (t.downloaded - t.lastBytes) * 1000.0 / dt);
                            t.lastBytes = t.downloaded;
                            t.lastTick = now;
                            fire(t);
                            if (now - t.lastSave > SAVE_INTERVAL_MS) {
                                t.lastSave = now;
                                save(t);
                            }
                        }
                        return !t.stop;
                    }

                    public void onMeta(long size) {
                        if (size > 0) t.total = size;
                    }
                });
                if (total > 0) t.total = total;
                attempts = 0;
                long now = t.file().exists() ? t.file().length() : 0;
                if (t.total > 0 && now >= t.total) break;
                if (t.stop) break;
                attempts++;
                if (attempts > 5) {
                    fail(t, "FTP 多次中断，文件没下完（可点继续重试）");
                    return;
                }
                sleepBackoff(attempts);
            } catch (Exception e) {
                android.util.Log.i("JfFtp", "attempt " + attempts + " failed: " + e);
                if (t.stop) break;
                attempts++;
                if (attempts > 4) {
                    fail(t, friendly(e));
                    return;
                }
                try {
                    sleepBackoff(attempts);
                } catch (Exception ignored) {
                }
            }
        }
        t.speed = 0;
        if ("canceled".equals(t.status)) {
            t.file().delete();
            fire(t);
            if (cb != null) cb.onFinished(t);
            return;
        }
        if (t.stop || "paused".equals(t.status)) {
            save(t);
            fire(t);
            return;
        }
        t.total = t.file().exists() ? t.file().length() : t.total;
        t.downloaded = t.total;
        finishArtifact(t);
        fire(t);
        if (cb != null) cb.onFinished(t);
    }

    private void fail(Task t, String msg) {
        t.status = "error";
        t.error = msg;
        t.stop = true;
    }

    private void initMeta(Task t, Probe p) throws IOException {
        String name = p.filename != null && p.filename.length() > 0
                ? p.filename : nameFromUrl(t.url);
        String ext = extOfName(name);
        if (ext.length() == 0) ext = extFromContentType(p.contentType);
        t.ext = ext;
        t.filename = freeName(applyExt(safe(name), ext));
        t.total = p.total;
        t.supportsRange = p.ranges && p.total > 0;
        t.etag = p.etag == null ? "" : p.etag;
        t.segments.clear();
        t.segments.addAll(plan(t.total, t.supportsRange, t.segmentsCount));
        synchronized (t.lock) {
            closeQuiet(t);
            t.downloaded = 0;
        }
        fire(t);
    }

    private boolean tryResume(Task t, Probe p) {
        File sf = t.stateFile();
        File df = t.file();
        if (!sf.exists() || !df.exists()) return false;
        Task s;
        try {
            s = parse(sf);
        } catch (Exception e) {
            return false;
        }
        if (s == null || !t.url.equals(s.url)) return false;
        if (df.exists() && s.total > 0 && df.length() != s.total) return false;
        if (p.total > 0 && s.total > 0 && p.total != s.total) return false;
        if (s.segments.isEmpty()) return false;
        t.filename = s.filename;
        t.total = s.total;
        t.supportsRange = s.supportsRange;
        t.etag = s.etag;
        t.referer = s.referer;
        t.cookie = s.cookie;
        t.contentType = s.contentType;
        t.ext = s.ext;
        t.kind = s.kind;
        t.segments.clear();
        t.segments.addAll(s.segments);
        // 续传校验：已下载部分对应的段临时件必须还在，否则整段重来。
        for (Segment seg : t.segments) {
            if (seg.done > 0 && !partFile(t, seg).exists()) return false;
        }
        long sum = 0;
        for (Segment seg : t.segments) sum += seg.done;
        t.downloaded = sum;
        synchronized (t.lock) {
            try {
                closeQuiet(t);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private void run(final Task t) {
        t.generation++;
        final int gen = t.generation;
        t.stop = false;
        t.status = "running";
        t.speed = 0;
        t.lastBytes = t.downloaded;
        t.lastTick = System.currentTimeMillis();
        for (final Segment s : t.segments) {
            if (s.end >= 0 && s.done >= s.size()) continue;
            ioPool.execute(new Runnable() {
                public void run() {
                    segmentLoop(t, s, gen);
                }
            });
        }
        Thread mt = new Thread(new Runnable() {
            public void run() {
                monitor(t, gen);
            }
        }, "jf-monitor");
        mt.setDaemon(true);
        mt.start();
        fire(t);
    }

    // ---------------------------------------------------------------- 段工作线程
    private void segmentLoop(Task t, Segment seg, int gen) {
        int attempts = 0;
        int eofRetries = 0;
        RandomAccessFile part = null;
        try {
            part = new RandomAccessFile(partFile(t, seg), "rw");
        } catch (IOException e) {
            fail(t, friendly(e));
            return;
        }
        while (!t.stop && gen == t.generation) {
            if (seg.end >= 0 && seg.done >= seg.size()) break;
            long pos = seg.start + seg.done;
            Channel.Response r = null;
            InputStream in = null;
            try {
                r = Channel.fetch(t.url, t.referer, t.cookie, t.ua,
                        "bytes=" + pos + "-" + (seg.end < 0 ? "" : String.valueOf(seg.end)),
                        CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
                int code = r.code;
                if (code == 200 && t.supportsRange && t.total > 0) {
                    t.restart = true;
                    t.stop = true;
                    return;
                }
                if (code != 200 && code != 206) {
                    if (fatalCode(code)) {
                        fail(t, httpMessage(code));
                        return;
                    }
                    throw new HttpException(code, httpMessage(code), true);
                }
                in = r.body;
                byte[] buf = new byte[CHUNK];
                while (!t.stop && gen == t.generation) {
                    long remain = seg.end >= 0 ? seg.end - pos + 1 : Long.MAX_VALUE;
                    int want = (int) Math.min(buf.length, remain);
                    if (speedLimit > 0) want = (int) Math.min(want, Math.max(4096, speedLimit / 64));
                    if (want <= 0) break;
                    acquire(want);   // 先按本块大小预扣额度，再读
                    int n = in.read(buf, 0, want);
                    if (n < 0) break;
                    synchronized (seg.lock) {
                        if (gen != t.generation || t.stop) return;
                        part.seek(seg.done);
                        part.write(buf, 0, n);
                        seg.done += n;
                        t.downloaded += n;
                    }
                    pos += n;
                }
                r.release(true);
                if (gen != t.generation) return;
                if (seg.end < 0) {
                    synchronized (seg.lock) {
                        t.total = seg.start + seg.done;
                        seg.end = t.total - 1;
                    }
                    return;
                }
                if (seg.done >= seg.size()) {
                    // 连接复用：干完本段不退出，切走最大未完成段的后一半继续跑
                    Segment next = takeTail(t);
                    if (next == null) return;
                    try { if (part != null) part.close(); } catch (Exception ignored) {}
                    part = new RandomAccessFile(partFile(t, next), "rw");
                    seg = next;
                    attempts = 0;
                    eofRetries = 0;
                    continue;
                }
                // 流提前结束（声明长度 vs 实际字节不符）：重试一次仍截断即判失败并保留断点。
                eofRetries++;
                if (eofRetries > 1) {
                    fail(t, "文件大小与服务器声明不符（实际 " + seg.done + " 字节 / 声明 "
                            + t.total + " 字节），已保留断点可继续");
                    return;
                }
                attempts++;
                if (attempts > SEG_RETRY) {
                    splitOrFail(t, seg);
                    return;
                }
                Thread.sleep(backoff(attempts));
            } catch (Exception e) {
                if (gen != t.generation || t.stop) return;
                if (e instanceof HttpException && !((HttpException) e).retryable) {
                    fail(t, e.getMessage());
                    return;
                }
                if (isFatalNetwork(e)) {
                    fail(t, friendly(e));
                    return;
                }
                attempts++;
                if (attempts > SEG_RETRY) {
                    splitOrFail(t, seg);
                    return;
                }
                try {
                    Thread.sleep(backoff(attempts));
                } catch (InterruptedException ignored) {
                }
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (r != null) r.release(false);
            }
        }
        try {
            if (part != null) part.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 段重试预算耗尽：剩余区间足够大就一分为二，右半段交新 worker（独立重试预算）；
     * 剩余太小则保留断点判失败。
     */
    private void splitOrFail(Task t, Segment seg) {
        long remaining = seg.end - (seg.start + seg.done) + 1;
        if (remaining < MIN_SEG) {
            fail(t, "多次重试仍未完成：" + t.filename);
            return;
        }
        Segment right = splitRange(seg);
        // 左段区间被缩短，截断其临时件，去掉旧预分配的尾部零字节。
        File pf = partFile(t, seg);
        if (pf.exists()) {
            RandomAccessFile lp = null;
            try {
                lp = new RandomAccessFile(pf, "rw");
                lp.setLength(Math.max(0, seg.size()));
            } catch (IOException ignored) {
            } finally {
                try { if (lp != null) lp.close(); } catch (Exception ignored) {}
            }
        }
        synchronized (t.lock) {
            t.segments.add(right);
        }
        final int gen = t.generation;
        ioPool.execute(new Runnable() {
            public void run() {
                segmentLoop(t, right, gen);
            }
        });
    }

    /**
     * 纯函数：把段剩余区间一分为二，左段保留已下载前缀，右段从 0 开始。
     * 测试可见，用于校验二分重分段的区间覆盖与续传计数。
     */
    public static Segment splitRange(Segment seg) {
        long remaining = seg.end - (seg.start + seg.done) + 1;
        long mid = seg.start + seg.done + remaining / 2;
        Segment right = new Segment(mid, seg.end, 0);
        seg.end = mid - 1;
        return right;
    }

    /**
     * 连接复用（IDM 式动态分段）：某条连接把手上这段干完，不退出线程，
     * 而是切走当前最大未完成段的后一半继续下，避免"某段早早下完、剩下的大段还在慢慢爬"。
     * 返回新段（从 mid 开始、已从原段尾部切走）；没有可切的返回 null。
     */
    private Segment takeTail(Task t) {
        if (!assistEnabled || !t.supportsRange || t.total <= 0 || t.streamSingle) return null;
        if (t.stop || !"running".equals(t.status)) return null;
        synchronized (t.lock) {
            Segment big = null;
            long bigRest = 0;
            for (Segment s : t.segments) {
                if (s.end < 0) continue;
                long rest = s.size() - s.done;
                if (rest > bigRest && rest >= 2 * MIN_TAIL) {
                    big = s;
                    bigRest = rest;
                }
            }
            if (big == null) return null;
            Segment right = splitRange(big);
            truncatePart(t, big);
            t.segments.add(right);
            return right;
        }
    }

    /** 段区间被切短后，把它的临时件截到新区间长度，去掉预分配的尾部零字节。 */
    private void truncatePart(Task t, Segment seg) {
        File pf = partFile(t, seg);
        if (!pf.exists()) return;
        RandomAccessFile lp = null;
        try {
            lp = new RandomAccessFile(pf, "rw");
            lp.setLength(Math.max(0, seg.size()));
        } catch (IOException ignored) {
        } finally {
            try { if (lp != null) lp.close(); } catch (Exception ignored) {}
        }
    }

    private void monitor(Task t, int gen) {
        boolean alive = true;
        long warmupAt = System.currentTimeMillis() + EXPAND_WARMUP_MS;
        boolean expanded = false;
        while (alive && gen == t.generation) {
            alive = false;
            for (Segment s : t.segments) {
                if (s.end < 0 || s.done < s.size()) {
                    alive = true;
                    break;
                }
            }
            if (!alive) break;
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            if (gen != t.generation) return;
            long now = System.currentTimeMillis();
            long dt = now - t.lastTick;
            if (dt >= 300) {
                t.speed = Math.max(0, (t.downloaded - t.lastBytes) * 1000.0 / dt);
                t.lastBytes = t.downloaded;
                t.lastTick = now;
                if (now - t.lastSave > SAVE_INTERVAL_MS
                        || t.downloaded - t.lastSaveBytes > SAVE_INTERVAL_BYTES) {
                    t.lastSave = now;
                    t.lastSaveBytes = t.downloaded;
                    save(t);
                }
                fire(t);
            }
            if (!expanded && now >= warmupAt) {
                maybeExpand(t, gen);
                expanded = true;
            }
            if ("canceled".equals(t.status) || "error".equals(t.status)
                    || "paused".equals(t.status) || t.stop) break;
        }
        if (gen != t.generation) return;
        t.speed = 0;

        if (t.restart && "running".equals(t.status)) {
            t.restart = false;
            resetSingle(t);
            run(t);
            return;
        }
        if ("canceled".equals(t.status)) {
            fire(t);
            if (cb != null) cb.onFinished(t);
            return;
        }
        if (t.stop || "paused".equals(t.status)) {
            save(t);
            fire(t);
            return;
        }
        if ("error".equals(t.status)) {
            save(t);
            fire(t);
            if (cb != null) cb.onFinished(t);
            return;
        }
        if (complete(t)) {
            if (!mergeParts(t)) {
                long size = t.file().exists() ? t.file().length() : 0;
                t.status = "error";
                t.error = "文件合并不完整（实际 " + size + " 字节 / 声明 " + t.total
                        + " 字节），已保留断点可继续";
                save(t);
                fire(t);
                if (cb != null) cb.onFinished(t);
                return;
            }
            synchronized (t.lock) {
                closeQuiet(t);
            }
            finishArtifact(t);
        } else {
            long size = t.file().exists() ? t.file().length() : 0;
            if (t.total > 0 && size < t.total) {
                t.status = "error";
                t.error = "文件大小与服务器声明不符（实际 " + size + " 字节 / 声明 " + t.total
                        + " 字节），已保留断点可继续";
                save(t);
            } else {
                t.status = "error";
                if (t.error.length() == 0) t.error = "下载未完成，可继续";
            }
        }
        fire(t);
        if (cb != null) cb.onFinished(t);
    }

    /** 运行期扩段： warmed up 后，若段数未达上限且存在足够大的未完成段，二分最大段。 */
    private void maybeExpand(Task t, int gen) {
        if (!t.supportsRange) return;
        if (t.streamSingle) return;
        int cap = Math.max(1, Math.min(t.segmentsCount, MAX_SEGMENTS));
        synchronized (t.lock) {
            if (t.segments.size() >= cap) return;
            Segment big = null;
            long bigSize = 0;
            for (Segment s : t.segments) {
                if (s.end < 0 || s.done >= s.size()) continue;
                long sz = s.size() - s.done;
                if (sz > bigSize) { big = s; bigSize = sz; }
            }
            if (big == null || bigSize < 2 * MIN_SEG) return;
            long mid = big.start + big.done + bigSize / 2;
            Segment right = new Segment(mid, big.end, 0);
            big.end = mid - 1;
            t.segments.add(right);
            ioPool.execute(new Runnable() {
                public void run() {
                    segmentLoop(t, right, gen);
                }
            });
        }
    }

    /** 把各段临时件按区间顺序合并成成品文件，合并成功后删除段临时件。 */
    private boolean mergeParts(Task t) {
        File f = t.file();
        RandomAccessFile out = null;
        try {
            out = new RandomAccessFile(f, "rw");
            if (t.total > 0) out.setLength(t.total);
            byte[] buf = new byte[CHUNK];
            for (Segment s : t.segments) {
                File pf = partFile(t, s);
                if (!pf.exists()) return false;
                long sz = pf.length();
                if (sz != s.size()) return false;
                RandomAccessFile in = new RandomAccessFile(pf, "r");
                try {
                    long off = 0;
                    while (off < sz) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, sz - off));
                        if (n < 0) break;
                        out.seek(s.start + off);
                        out.write(buf, 0, n);
                        off += n;
                    }
                    if (off != sz) return false;
                } finally {
                    try { in.close(); } catch (Exception ignored) {}
                }
                pf.delete();
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    /** 成品校验：文件头判错误页 / 定真实扩展名 / 长度核对。 */
    private void finishArtifact(Task t) {
        File f = t.file();
        if (!f.exists()) {
            fail(t, "临时文件不存在");
            return;
        }
        byte[] head = readHead(f, 64);
        if (looksLikeErrorPage(head)) {
            String name = f.getName();
            synchronized (t.lock) {
                closeQuiet(t);
            }
            f.delete();
            if (t.stateFile().exists()) t.stateFile().delete();
            t.status = "error";
            t.error = MSG_ERROR_PAGE;
            t.filename = name;
            return;
        }

        String magicExt = sniffExt(head);
        String ext = t.ext;
        if (ext.length() == 0) ext = magicExt == null ? "" : magicExt;
        if (ext.length() == 0) ext = "bin";
        t.ext = ext;

        String newName = ensureExt(t.filename, ext);
        if (!newName.equals(t.filename)) {
            File renamed = new File(dir, newName);
            if (f.renameTo(renamed)) {
                File oldState = t.stateFile();
                t.filename = newName;
                if (oldState.exists()) oldState.delete();
                f = renamed;
            }
        }

        long size = f.length();
        if (t.total > 0 && size != t.total) {
            t.status = "error";
            t.error = "文件大小与服务器声明不符（实际 " + size + " 字节 / 声明 " + t.total
                    + " 字节），已保留断点可继续";
            save(t);
            return;
        }
        t.total = size;
        t.downloaded = size;
        t.status = "done";
        t.error = "";
        if (t.stateFile().exists()) t.stateFile().delete();
    }

    private void resetSingle(Task t) {
        synchronized (t.lock) {
            t.supportsRange = false;
            t.segments.clear();
            t.segments.add(new Segment(0, t.total - 1, 0));
            t.downloaded = 0;
            try {
                if (t.raf == null) t.raf = new RandomAccessFile(t.file(), "rw");
                t.raf.setLength(0);
                if (t.total > 0) t.raf.setLength(t.total);
            } catch (Exception ignored) {
            }
        }
        fire(t);
    }

    private boolean complete(Task t) {
        if (t.segments.isEmpty()) return false;
        for (Segment s : t.segments) {
            if (s.end < 0 || s.done < s.size()) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- m3u8 接入
    private void startM3u8(final Task t, Probe p) {
        // 强杀重启后，先从 sidecar 恢复文件名 / referer / cookie 等元数据。
        File sf = t.stateFile();
        if (sf.exists()) {
            try {
                Task s = parse(sf);
                if (s != null) {
                    t.filename = s.filename;
                    t.ext = s.ext;
                    t.kind = s.kind;
                    t.referer = s.referer;
                    t.cookie = s.cookie;
                    t.total = s.total;
                    t.segments.clear();
                    t.segments.addAll(s.segments);
                }
            } catch (Exception ignored) {
            }
        }
        t.status = "running";
        fire(t);
        final M3u8Downloader dl = new M3u8Downloader(fetcher, dir, t, new M3u8Downloader.Listener() {
            public void onProgress(Task task) {
                save(task);
                fire(task);
            }

            public void onDone(Task task) {
                finishM3u8(task);
            }

            public void onError(Task task, String msg) {
                fail(task, msg);
                fire(task);
                if (cb != null) cb.onFinished(task);
            }
        });
        ioPool.execute(new Runnable() {
            public void run() {
                dl.run();
            }
        });
    }

    /** m3u8 拼接完成后的成品校验（与普通下载同一套规则）。 */
    private void finishM3u8(Task t) {
        File f = t.file();
        if (!f.exists() || f.length() == 0) {
            fail(t, "m3u8 下载失败：没有拿到可播放的分片");
            fire(t);
            if (cb != null) cb.onFinished(t);
            return;
        }
        byte[] head = readHead(f, 64);
        if (looksLikeErrorPage(head)) {
            f.delete();
            if (t.stateFile().exists()) t.stateFile().delete();
            t.status = "error";
            t.error = MSG_ERROR_PAGE;
            fire(t);
            if (cb != null) cb.onFinished(t);
            return;
        }
        String magicExt = sniffExt(head);
        String ext = t.ext;
        if (ext.length() == 0) ext = magicExt == null ? "" : magicExt;
        if (ext.length() == 0) ext = "bin";
        t.ext = ext;
        t.filename = ensureExt(t.filename, ext);
        t.total = f.length();
        t.downloaded = f.length();
        t.status = "done";
        t.error = "";
        if (t.stateFile().exists()) t.stateFile().delete();
        fire(t);
        if (cb != null) cb.onFinished(t);
    }

    // ---------------------------------------------------------------- 控制
    public void pause(Task t) {
        if (!"running".equals(t.status) && !"preparing".equals(t.status) && !"pending".equals(t.status)) return;
        t.status = "paused";
        t.stop = true;
        save(t);
        fire(t);
    }

    public void resume(Task t) {
        if (!"paused".equals(t.status) && !"error".equals(t.status) && !"running".equals(t.status)) return;
        t.error = "";
        t.stop = false;
        if ("bt".equals(t.kind)) {
            t.status = "pending";
            start(t);
            return;
        }
        if ("m3u8".equals(t.kind)) {
            start(t);
            return;
        }
        if (t.segments.isEmpty() || "pending".equals(t.status)) {
            start(t);
            return;
        }
        synchronized (t.lock) {
            try {
                if (t.raf == null) t.raf = new RandomAccessFile(t.file(), "rw");
            } catch (Exception e) {
                t.segments.clear();
                start(t);
                return;
            }
        }
        if (complete(t)) {
            t.status = "done";
            t.downloaded = t.total;
            fire(t);
            if (cb != null) cb.onFinished(t);
            return;
        }
        run(t);
    }

    public void cancel(Task t) {
        t.status = "canceled";
        t.stop = true;
        t.generation++;
        synchronized (t.lock) {
            closeQuiet(t);
            File f = t.file();
            File s = t.stateFile();
            if (f.exists()) f.delete();
            if (s.exists()) s.delete();
            for (Segment seg : t.segments) {
                File pf = partFile(t, seg);
                if (pf.exists()) pf.delete();
            }
        }
        fire(t);
    }

    public void remove(Task t) {
        cancel(t);
    }

    // ---------------------------------------------------------------- 探测
    public static class Probe {
        public long total = -1;
        public boolean ranges;
        public String filename = "";
        public String etag = "";
        public String contentType = "";
    }

    private Probe probe(Task t) throws IOException {
        Probe p = new Probe();
        Channel.Response r = null;
        int attempts = 0;
        while (true) {
            try {
                r = Channel.fetch(t.url, t.referer, t.cookie, t.ua, "bytes=0-0",
                        CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
                int code = r.code;
                if (code == 200 || code == 206) break;
                if (fatalCode(code)) throw new HttpException(code, httpMessage(code), false);
                attempts++;
                if (attempts > MAX_RETRY) throw new HttpException(code, httpMessage(code), false);
                try { sleepBackoff(attempts); } catch (InterruptedException ie) { throw new IOException(ie); }
                r.release(false);
                r = null;
            } catch (IOException e) {
                if (e instanceof HttpException && !((HttpException) e).retryable) throw e;
                if (isFatalNetwork(e) || attempts >= MAX_RETRY) throw e;
                attempts++;
                try { sleepBackoff(attempts); } catch (InterruptedException ie) { throw new IOException(ie); }
                if (r != null) { r.release(false); r = null; }
            }
        }
        try {
            if (r.code == 206) {
                p.ranges = true;
                String cr = r.contentRange;
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0) {
                        try {
                            p.total = Long.parseLong(cr.substring(slash + 1).trim());
                        } catch (Exception ignored) {
                        }
                    }
                }
            } else {
                String ar = r.acceptRanges;
                p.ranges = ar != null && ar.trim().equalsIgnoreCase("bytes");
                p.total = r.contentLength;
            }
            p.contentType = normalizeType(r.contentType);
            p.filename = nameFromDisposition(r.disposition);
            p.etag = r.etag == null ? "" : r.etag;
            if (p.filename.length() == 0) p.filename = nameFromUrl(t.url);
            return p;
        } finally {
            if (r != null) r.release(false);
        }
    }

    // ---------------------------------------------------------------- 连接通道
    /**
     * 轻量 HTTP 通道：http 走裸 Socket 连接池（Keep-Alive 复用 + TCP_NODELAY），
     * https 回退 HttpURLConnection。probe 不读响应体；段请求读完 body 后按保活策略归还连接。
     */
    static class Channel {
        private static final Object POOL_LOCK = new Object();
        private static final HashMap<String, Deque<Socket>> POOL = new HashMap<>();

        static class Response {
            int code;
            String contentType = "";
            String etag = "";
            String contentRange = "";
            String disposition = "";
            String acceptRanges = "";
            long contentLength = -1;
            InputStream body;
            Socket socket;
            boolean pooled;

            /** body 读完后归还连接；fullyConsumed=false 表示连接不可复用，直接关闭。 */
            void release(boolean fullyConsumed) {
                if (body != null) try { body.close(); } catch (Exception ignored) {}
                body = null;
                if (socket == null) return;
                if (pooled && fullyConsumed) {
                    Channel.returnSocket(socket);
                } else {
                    try { socket.close(); } catch (Exception ignored) {}
                }
                socket = null;
            }
        }

        static Response fetch(String url, String referer, String cookie, String ua, String range,
                              int connectMs, int readMs) throws IOException {
            URL u = new URL(url);
            if (u.getProtocol().equalsIgnoreCase("https")) {
                return fetchHttps(u, referer, cookie, ua, range, connectMs, readMs);
            }
            return fetchHttp(u, referer, cookie, ua, range, connectMs, readMs);
        }

        private static Response fetchHttp(URL u, String referer, String cookie, String ua, String range,
                                          int connectMs, int readMs) throws IOException {
            String host = u.getHost();
            int port = u.getPort() == -1 ? 80 : u.getPort();
            String key = host + ":" + port;
            Socket s = borrow(key, host, port, connectMs);
            s.setSoTimeout(readMs);
            String path = u.getPath() + (u.getQuery() != null ? "?" + u.getQuery() : "");
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(port == 80 ? "" : ":" + port).append("\r\n");
            req.append("User-Agent: ").append(ua == null || ua.length() == 0 ? UA : ua).append("\r\n");
            req.append("Accept: */*\r\n");
            req.append("Accept-Encoding: identity\r\n");
            if (referer != null && referer.length() > 0) req.append("Referer: ").append(referer).append("\r\n");
            if (cookie != null && cookie.length() > 0) req.append("Cookie: ").append(cookie).append("\r\n");
            if (range != null) req.append("Range: ").append(range).append("\r\n");
            req.append("Connection: keep-alive\r\n\r\n");
            try {
                s.getOutputStream().write(req.toString().getBytes("ISO-8859-1"));
                s.getOutputStream().flush();

                BufferedInputStream bis = new BufferedInputStream(s.getInputStream());
                String statusLine = readHeaderLine(bis);
                if (statusLine == null) throw new IOException("空响应");
                Response resp = new Response();
                try {
                    resp.code = Integer.parseInt(statusLine.split(" ")[1]);
                } catch (Exception e) {
                    throw new IOException("响应状态无法解析: " + statusLine);
                }
                String line;
                while ((line = readHeaderLine(bis)) != null && line.length() > 0) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    String k = line.substring(0, colon).trim();
                    String v = line.substring(colon + 1).trim();
                    if ("Content-Type".equalsIgnoreCase(k)) resp.contentType = v;
                    else if ("ETag".equalsIgnoreCase(k)) resp.etag = v;
                    else if ("Content-Range".equalsIgnoreCase(k)) resp.contentRange = v;
                    else if ("Content-Disposition".equalsIgnoreCase(k)) resp.disposition = v;
                    else if ("Accept-Ranges".equalsIgnoreCase(k)) resp.acceptRanges = v;
                    else if ("Content-Length".equalsIgnoreCase(k)) {
                        try { resp.contentLength = Long.parseLong(v); } catch (Exception ignored) {}
                    }
                }
                resp.body = bis;
                resp.socket = s;
                resp.pooled = true;
                return resp;
            } catch (IOException e) {
                try { s.close(); } catch (Exception ignored) {}
                throw e;
            }
        }

        /** 按字节读一行 header（\r\n 或 \n），不吞 body 字节。 */
        private static String readHeaderLine(BufferedInputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') break;
                if (b != '\r') line.write(b);
            }
            if (b < 0 && line.size() == 0) return null;
            return new String(line.toByteArray(), "ISO-8859-1");
        }

        private static Response fetchHttps(URL u, String referer, String cookie, String ua, String range,
                                           int connectMs, int readMs) throws IOException {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", ua == null || ua.length() == 0 ? UA : ua);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (referer != null && referer.length() > 0) c.setRequestProperty("Referer", referer);
            if (cookie != null && cookie.length() > 0) c.setRequestProperty("Cookie", cookie);
            if (range != null) c.setRequestProperty("Range", range);
            Response resp = new Response();
            resp.code = c.getResponseCode();
            resp.contentType = c.getContentType();
            resp.etag = c.getHeaderField("ETag");
            resp.contentRange = c.getHeaderField("Content-Range");
            resp.disposition = c.getHeaderField("Content-Disposition");
            resp.acceptRanges = c.getHeaderField("Accept-Ranges");
            resp.contentLength = c.getContentLength();
            resp.body = c.getInputStream();
            resp.socket = null;
            resp.pooled = false;
            // 连接不可在裸通道里归还；body 关闭后由 HttpURLConnection 连接池复用。
            return resp;
        }

        private static Socket borrow(String key, String host, int port, int connectMs) throws IOException {
            synchronized (POOL_LOCK) {
                Deque<Socket> dq = POOL.get(key);
                if (dq != null) {
                    Socket s = dq.pollFirst();
                    if (s != null && !s.isClosed() && s.isConnected()) return s;
                }
            }
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.connect(new java.net.InetSocketAddress(host, port), connectMs);
            return s;
        }

        private static void returnSocket(Socket s) {
            if (s == null || s.isClosed()) return;
            String key;
            try {
                String host = s.getInetAddress().getHostAddress();
                key = host + ":" + s.getPort();
            } catch (Exception e) {
                try { s.close(); } catch (Exception ignored) {}
                return;
            }
            synchronized (POOL_LOCK) {
                Deque<Socket> dq = POOL.get(key);
                if (dq == null) { dq = new LinkedList<>(); POOL.put(key, dq); }
                if (dq.size() < MAX_SEGMENTS) dq.addLast(s);
                else try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static HttpURLConnection open(String url, String referer, String cookie, String ua, String range)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", ua == null || ua.length() == 0 ? UA : ua);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (referer != null && referer.length() > 0) c.setRequestProperty("Referer", referer);
        if (cookie != null && cookie.length() > 0) c.setRequestProperty("Cookie", cookie);
        if (range != null) c.setRequestProperty("Range", range);
        return c;
    }

    // ---------------------------------------------------------------- 类型 / 扩展名判定
    static boolean isM3u8(String url, String contentType) {
        String u = url == null ? "" : url.toLowerCase();
        if (u.contains(".m3u8")) return true;
        if (contentType != null && contentType.contains("mpegurl")) return true;
        return false;
    }

    static boolean rejectedType(String contentType, String filename) {
        if (contentType == null) return false;
        String l = contentType.toLowerCase().trim();
        if (isTextTarget(filename)) return false;
        if (l.startsWith("text/html")) return true;
        if (l.startsWith("application/json")) return true;
        if (l.startsWith("text/javascript") || l.startsWith("application/javascript")) return true;
        if (l.startsWith("text/plain")) return true;
        return false;
    }

    private static boolean isTextTarget(String filename) {
        String e = extOfName(filename);
        return e.equals("txt") || e.equals("csv") || e.equals("md") || e.equals("log")
                || e.equals("srt") || e.equals("json") || e.equals("xml");
    }

    static String extFromContentType(String ct) {
        if (ct == null) return "";
        String l = ct.toLowerCase().trim();
        int semi = l.indexOf(';');
        if (semi >= 0) l = l.substring(0, semi).trim();
        switch (l) {
            case "video/mp4": return "mp4";
            case "audio/mp4": return "m4a";
            case "video/quicktime": return "mov";
            case "video/x-flv": return "flv";
            case "audio/mpeg":
            case "audio/mp3":
            case "audio/x-mpeg": return "mp3";
            case "application/pdf": return "pdf";
            case "application/zip":
            case "application/x-zip-compressed": return "zip";
            case "video/mp2t": return "ts";
            case "video/x-matroska": return "mkv";
            case "audio/x-flac":
            case "audio/flac": return "flac";
            case "application/x-rar-compressed": return "rar";
            case "application/x-7z-compressed": return "7z";
            case "audio/ogg":
            case "application/ogg": return "ogg";
            case "video/x-msvideo": return "avi";
            case "video/x-ms-wmv":
            case "video/x-ms-asf":
            case "application/vnd.ms-asf":
            case "application/x-ms-asf": return "wmv";
            case "audio/x-ms-wma": return "wma";
            default: return "";
        }
    }

    /** 按文件头判定真实类型，返回扩展名（不含点），识别不出返回 null。 */
    static String sniffExt(byte[] b) {
        if (b == null || b.length < 4) return null;
        if (b.length >= 12 && b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70) {
            String brand = new String(b, 8, Math.min(4, b.length - 8)).toLowerCase();
            return brand.startsWith("m4a") ? "m4a" : "mp4";
        }
        if (b[0] == 0x25 && b[1] == 0x50 && b[2] == 0x44 && b[3] == 0x46) return "pdf";
        if (b[0] == 0x50 && b[1] == 0x4B) return "zip";
        if (b[0] == 0x52 && b[1] == 0x61 && b[2] == 0x72) return "rar";
        if (b.length >= 4 && b[0] == 0x37 && b[1] == 0x7A && b[2] == (byte) 0xBC && b[3] == (byte) 0xAF) return "7z";
        if (b[0] == 0x1A && b[1] == 0x45 && b[2] == (byte) 0xDF && b[3] == (byte) 0xA3) return "mkv";
        if (b[0] == 0x49 && b[1] == 0x44 && b[2] == 0x33) return "mp3";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return "mp3";
        if (b[0] == 0x66 && b[1] == 0x4C && b[2] == 0x61 && b[3] == 0x63) return "flac";
        if (b[0] == 0x4F && b[1] == 0x67 && b[2] == 0x67 && b[3] == 0x53) return "ogg";
        if (b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b.length >= 12 && b[8] == 0x41 && b[9] == 0x56 && b[10] == 0x49) return "avi";
        // ASF 头 GUID 30 26 B2 75 8E 66 CF 11 A6 D9 00 AA 00 62 CE 6C（wmv / wma 共用）
        if (b.length >= 16 && (b[0] & 0xFF) == 0x30 && (b[1] & 0xFF) == 0x26
                && (b[2] & 0xFF) == 0xB2 && (b[3] & 0xFF) == 0x75 && (b[4] & 0xFF) == 0x8E
                && (b[5] & 0xFF) == 0x66 && (b[6] & 0xFF) == 0xCF && (b[7] & 0xFF) == 0x11
                && (b[8] & 0xFF) == 0xA6 && (b[9] & 0xFF) == 0xD9 && (b[10] & 0xFF) == 0x00
                && (b[11] & 0xFF) == 0xAA && (b[12] & 0xFF) == 0x00 && (b[13] & 0xFF) == 0x62
                && (b[14] & 0xFF) == 0xCE && (b[15] & 0xFF) == 0x6C) return "wmv";
        return null;
    }

    /** 文件头是否为网页/接口错误页。 */
    static boolean looksLikeErrorPage(byte[] b) {
        if (b == null || b.length == 0) return false;
        int i = 0;
        while (i < b.length && b[i] <= 0x20) i++;
        if (i >= b.length) return false;
        String head = new String(b, i, Math.min(b.length - i, 64), java.nio.charset.StandardCharsets.UTF_8)
                .trim().toLowerCase();
        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<!do")) return true;
        if (b[i] == '{' || b[i] == '[') {
            for (int j = i; j < Math.min(b.length, i + 64); j++) {
                if (b[j] == '"') return true;
            }
        }
        return false;
    }

    private static byte[] readHead(File f, int n) {
        java.io.RandomAccessFile r = null;
        try {
            r = new java.io.RandomAccessFile(f, "r");
            byte[] b = new byte[n];
            int got = r.read(b);
            if (got < 0) return new byte[0];
            if (got < n) {
                byte[] trim = new byte[got];
                System.arraycopy(b, 0, trim, 0, got);
                return trim;
            }
            return b;
        } catch (Exception e) {
            return new byte[0];
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
        }
    }

    static String extOfName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    static String applyExt(String name, String ext) {
        if (ext == null || ext.length() == 0) return name;
        if (extOfName(name).length() > 0) return name;
        return name + "." + ext;
    }

    private static String ensureExt(String name, String ext) {
        if (ext == null || ext.length() == 0) return name;
        String cur = extOfName(name);
        if (cur.equals(ext)) return name;
        if (cur.length() > 0 && !cur.equals("txt") && !cur.equals("bin") && !cur.equals("html")
                && !cur.equals("htm") && !cur.equals("php")) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "." + ext;
    }

    private static String normalizeType(String ct) {
        if (ct == null) return "";
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim();
    }

    private static String nameFromDisposition(String cd) {
        if (cd == null) return "";
        try {
            String lower = cd.toLowerCase();
            int i = lower.indexOf("filename*=");
            if (i >= 0) {
                String v = cd.substring(i + 9).trim();
                int semi = v.indexOf(';');
                if (semi >= 0) v = v.substring(0, semi);
                v = v.replace("\"", "").trim();
                if (v.contains("''")) v = v.substring(v.indexOf("''") + 2);
                return safe(URLDecoder.decode(v, "UTF-8"));
            }
            i = lower.indexOf("filename=");
            if (i >= 0) {
                String v = cd.substring(i + 9).trim();
                int semi = v.indexOf(';');
                if (semi >= 0) v = v.substring(0, semi);
                v = v.replace("\"", "").trim();
                return safe(v);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static String nameFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            String base = path.substring(path.lastIndexOf('/') + 1);
            base = URLDecoder.decode(base, "UTF-8");
            return safe(base.length() > 0 ? base : "download");
        } catch (Exception e) {
            return "download";
        }
    }

    public static String safe(String name) {
        if (name == null) return "download";
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").replaceAll("\\s+", " ");
        if (s.length() == 0 || s.equals(".") || s.equals("..")) s = "download";
        if (s.length() > 120) {
            int dot = s.lastIndexOf('.');
            s = dot > 0 ? s.substring(0, Math.min(dot, 100)) + s.substring(dot) : s.substring(0, 100);
        }
        return s;
    }

    /** 段临时件：<文件名>.p<段起始偏移>，续传按偏移匹配。 */
    private File partFile(Task t, Segment seg) {
        return new File(t.destDir, t.filename + ".p" + seg.start);
    }

    /** 自适应段数：不支持 Range → 1；小文件（<2MB）单流；其余按容量起步，上限 cap。 */
    public static int planCount(long total, boolean ranges) {
        return planCount(total, ranges, MAX_SEGMENTS);
    }

    public static int planCount(long total, boolean ranges, int cap) {
        if (!ranges || total <= 0) return 1;
        int c = Math.max(1, Math.min(cap, MAX_SEGMENTS));
        int n;
        if (total < 2L * 1024 * 1024) n = 1;
        else if (total < 8L * 1024 * 1024) n = 4;
        else n = MAX_SEGMENTS;
        return Math.max(1, Math.min(n, (int) Math.max(1, total / MIN_SEG)));
    }

    private List<Segment> plan(long total, boolean ranges, int cap) {
        List<Segment> list = new ArrayList<>();
        if (!ranges || total <= 0) {
            list.add(new Segment(0, total > 0 ? total - 1 : -1, 0));
            return list;
        }
        int n = planCount(total, ranges, cap);
        long pos = 0;
        for (int i = 0; i < n; i++) {
            long end = (i == n - 1) ? total - 1 : pos + (total / n) - 1;
            list.add(new Segment(pos, end, 0));
            pos = end + 1;
        }
        return list;
    }

    private String freeName(String name) {
        return freeName(dir, name);
    }

    static String freeName(File dir, String name) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        String candidate = name;
        int i = 1;
        while (new File(dir, candidate).exists() || new File(dir, candidate + SUFFIX).exists()) {
            candidate = base + " (" + i + ")" + ext;
            i++;
        }
        return candidate;
    }

    // ---------------------------------------------------------------- 状态持久化
    void save(Task t) {
        synchronized (t.lock) {
            writeState(t);
        }
    }

    private void writeState(Task t) {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(enc(t.id)).append('\n');
        sb.append("url=").append(enc(t.url)).append('\n');
        sb.append("file=").append(enc(t.filename)).append('\n');
        sb.append("total=").append(t.total).append('\n');
        sb.append("ranges=").append(t.supportsRange ? 1 : 0).append('\n');
        sb.append("etag=").append(enc(t.etag)).append('\n');
        sb.append("referer=").append(enc(t.referer)).append('\n');
        sb.append("cookie=").append(enc(t.cookie)).append('\n');
        sb.append("contentType=").append(enc(t.contentType)).append('\n');
        sb.append("ext=").append(enc(t.ext)).append('\n');
        sb.append("kind=").append(enc(t.kind)).append('\n');
        sb.append("status=").append(enc(t.status)).append('\n');
        sb.append("segs=");
        for (int i = 0; i < t.segments.size(); i++) {
            Segment s = t.segments.get(i);
            if (i > 0) sb.append(';');
            sb.append(s.start).append(',').append(s.end).append(',').append(s.done);
        }
        sb.append('\n');
        sb.append("done=").append(t.downloaded).append('\n');
        sb.append("uri=").append(enc(t.publicUri)).append('\n');
        sb.append("when=").append(t.finishedAt).append('\n');
        sb.append("multi=").append(t.btMulti ? 1 : 0).append('\n');
        File tmp = new File(t.destDir, t.filename + SUFFIX + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            if (t.stateFile().exists()) t.stateFile().delete();
            tmp.renameTo(t.stateFile());
        } catch (Exception ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
            if (tmp.exists()) tmp.delete();
        }
    }

    /**
     * 扫描目录里的状态文件：未完成的任务（可续传）+ 已完成的历史记录。
     * 历史记录按最后修改时间倒序，新的在前。
     */
    public List<Task> loadAll() {
        List<Task> list = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return list;
        for (File f : files) {
            if (!f.getName().endsWith(SUFFIX)) continue;
            try {
                Task t = parse(f);
                if (t == null || t.segments.isEmpty()) continue;
                // 已完成的历史记录必须能定位到成品，否则只是一条空壳
                if ("done".equals(t.status) && (t.publicUri == null || t.publicUri.length() == 0)) continue;
                list.add(t);
            } catch (Exception ignored) {
            }
        }
        java.util.Collections.sort(list, new java.util.Comparator<Task>() {
            public int compare(Task a, Task b) {
                if (a.savedAt == b.savedAt) return 0;
                return a.savedAt < b.savedAt ? 1 : -1;
            }
        });
        return list;
    }

    /** 已完成历史只留最近 keep 条，更早的状态文件直接清掉（不动已保存的成品）。 */
    public void pruneDone(int keep) {
        File[] files = dir.listFiles();
        if (files == null || keep < 0) return;
        List<File> done = new ArrayList<>();
        for (File f : files) {
            if (!f.getName().endsWith(SUFFIX)) continue;
            try {
                Task t = parse(f);
                if (t != null && "done".equals(t.status)) done.add(f);
            } catch (Exception ignored) {
            }
        }
        if (done.size() <= keep) return;
        java.util.Collections.sort(done, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                long x = a.lastModified(), y = b.lastModified();
                if (x == y) return 0;
                return x < y ? 1 : -1;
            }
        });
        for (int i = keep; i < done.size(); i++) {
            try {
                done.get(i).delete();
            } catch (Exception ignored) {
            }
        }
    }

    private Task parse(File f) throws Exception {
        String text = readText(f);
        Task t = new Task();
        t.destDir = dir.getAbsolutePath();
        t.status = "paused";
        String[] lines = text.split("\n");
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq);
            String v = line.substring(eq + 1);
            if ("id".equals(k)) t.id = dec(v);
            else if ("url".equals(k)) t.url = dec(v);
            else if ("file".equals(k)) t.filename = dec(v);
            else if ("total".equals(k)) t.total = Long.parseLong(v.trim());
            else if ("ranges".equals(k)) t.supportsRange = "1".equals(v.trim());
            else if ("etag".equals(k)) t.etag = dec(v);
            else if ("referer".equals(k)) t.referer = dec(v);
            else if ("cookie".equals(k)) t.cookie = dec(v);
            else if ("contentType".equals(k)) t.contentType = dec(v);
            else if ("ext".equals(k)) t.ext = dec(v);
            else if ("kind".equals(k)) t.kind = dec(v);
            else if ("uri".equals(k)) t.publicUri = dec(v);
            else if ("when".equals(k)) t.finishedAt = Long.parseLong(v.trim());
            else if ("multi".equals(k)) t.btMulti = "1".equals(v.trim());
            else if ("status".equals(k)) {
                String st = dec(v);
                if (st.length() > 0) t.status = st;
            }
            else if ("segs".equals(k)) {
                for (String part : v.split(";")) {
                    if (part.trim().length() == 0) continue;
                    String[] a = part.split(",");
                    t.segments.add(new Segment(Long.parseLong(a[0]), Long.parseLong(a[1]), Long.parseLong(a[2])));
                }
            }
        }
        if (t.filename == null || t.url == null || t.segments.isEmpty()) return null;
        long sum = 0;
        for (Segment s : t.segments) sum += s.done;
        t.downloaded = sum;
        t.savedAt = f.lastModified();
        return t;
    }

    private static String readText(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(b, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private void closeQuiet(Task t) {
        try {
            if (t.raf != null) t.raf.close();
        } catch (Exception ignored) {
        }
        t.raf = null;
    }

    private void fire(Task t) {
        if (cb != null) {
            try {
                cb.onUpdate(t);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 错误分级
    static boolean fatalCode(int code) {
        return code == 403 || code == 404 || code == 410 || (code >= 400 && code < 500);
    }

    static String httpMessage(int code) {
        switch (code) {
            case 403: return "服务器拒绝访问（403）：可能被防盗链拦截，或需要登录/会员权限";
            case 404: return "链接不存在（404）：文件已被删除或分享链接已过期";
            case 410: return "链接已永久失效（410）";
            case 429: return "请求过于频繁（429），稍后重试";
            case 500: return "服务器内部错误（500），稍后重试";
            case 502: return "网关错误（502），稍后重试";
            case 503: return "服务暂不可用（503），稍后重试";
            case 504: return "网关超时（504），稍后重试";
            default: return "服务器返回错误（HTTP " + code + "）";
        }
    }

    private static boolean isFatalNetwork(Exception e) {
        String n = e.getClass().getName();
        return n.contains("UnknownHostException") || n.contains("MalformedURLException");
    }

    private static long backoff(int attempt) {
        long ms = 500L * (1L << Math.min(attempt, 4));
        return Math.min(ms, 8000L);
    }

    private static void sleepBackoff(int attempt) throws InterruptedException {
        Thread.sleep(backoff(attempt));
    }

    public static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String low = m.toLowerCase();
        if (low.contains("unknownhost") || low.contains("getaddrinfo")) return "无法解析域名，检查网络后重试";
        if (low.contains("timeout") || low.contains("timed out")) return "连接超时，稍后重试";
        if (low.contains("403")) return httpMessage(403);
        if (low.contains("404")) return httpMessage(404);
        if (low.contains("410")) return httpMessage(410);
        if (low.contains("429")) return httpMessage(429);
        if (low.contains("500") || low.contains("502") || low.contains("503") || low.contains("504")) return httpMessage(503);
        if (low.contains("ssl") || low.contains("certificate")) return "HTTPS 证书校验失败";
        if (low.contains("space")) return "存储空间不足";
        return m;
    }

    static class HttpException extends IOException {
        final int code;
        final boolean retryable;

        HttpException(int code, String msg, boolean retryable) {
            super(msg);
            this.code = code;
            this.retryable = retryable;
        }
    }
}
