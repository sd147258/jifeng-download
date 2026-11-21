package com.jifeng.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * m3u8 下载器（纯 Java，不依赖 android.*）：
 *  - 解析 master playlist，选 BANDWIDTH 最高的一路；
 *  - 分片 4 路并发，单片最多重试 5 次（403/404/410 不重试）；
 *  - fMP4（含 #EXT-X-MAP 或 .m4s/.mp4 分片）：init 分片 + 媒体分片直接拼接为 .mp4；
 *  - TS 分片：先拼接为 .ts，转封装 MP4 由 Android 侧 {@link AndroidM3u8Post} 完成。
 *
 * 桌面 JVM 只验证「解析 / 下载 / 拼接字节正确」这部分；MediaExtractor/MediaMuxer 转封装
 * 必须在真机/模拟器上验证。
 */
public class M3u8Downloader {

    public interface Listener {
        void onProgress(DownloadEngine.Task t);

        void onDone(DownloadEngine.Task t);

        void onError(DownloadEngine.Task t, String msg);
    }

    static class Variant {
        int bandwidth;
        String url;
    }

    /** 一个分片（或 init 分片）的 URL 与字节区间；start<0 表示整文件。 */
    static class Seg {
        String url;
        long start = -1;
        long end = -1;
    }

    static class MediaPlaylist {
        String initMapUrl;
        long initStart = -1;
        long initEnd = -1;
        final List<Seg> segments = new ArrayList<>();
        boolean fmp4;
    }

    private static final Pattern BANDWIDTH_RE = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern MAP_URI_RE = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern MAP_BR_RE = Pattern.compile("BYTERANGE=\"(\\d+)(?:@(\\d+))?\"");
    private static final Pattern SEG_BR_RE = Pattern.compile("^#EXT-X-BYTERANGE:(\\d+)(?:@(\\d+))?");
    private static final int CONCURRENCY = 4;
    private static final int MAX_RETRY = 5;

    private final DownloadEngine.Fetcher fetcher;
    private final File dir;
    private final DownloadEngine.Task task;
    private final Listener listener;

    public M3u8Downloader(DownloadEngine.Fetcher fetcher, File dir,
                           DownloadEngine.Task task, Listener listener) {
        this.fetcher = fetcher;
        this.dir = dir;
        this.task = task;
        this.listener = listener;
    }

    public void run() {
        try {
            String playlistUrl = task.url;
            String body = fetcher.getText(playlistUrl, task.referer, task.cookie, task.ua);
            if (isMaster(body)) {
                List<Variant> variants = parseMaster(body, playlistUrl);
                if (variants.isEmpty()) throw new IllegalStateException("播放列表里没有可用清晰度");
                Variant best = pickBest(variants);
                playlistUrl = best.url;
                body = fetcher.getText(playlistUrl, task.referer, task.cookie, task.ua);
            }
            MediaPlaylist pl = parseMedia(body, playlistUrl);
            if (pl.segments.isEmpty()) throw new IllegalStateException("播放列表里没有分片地址");

            String ext = pl.fmp4 ? "mp4" : "ts";
            task.ext = ext;
            String base = DownloadEngine.nameFromUrl(playlistUrl);
            base = base.replaceAll("\\.m3u8$", "");
            task.filename = DownloadEngine.freeName(dir, DownloadEngine.applyExt(DownloadEngine.safe(base), ext));

            File out = task.file();
            List<File> parts = new ArrayList<>();
            long total = 0;

            // init 分片（fMP4）
            File initPart = null;
            if (pl.initMapUrl != null) {
                initPart = partFile(out, "init");
                if (!initPart.exists() || initPart.length() == 0) {
                    byte[] init = fetchWithRetry(pl.initMapUrl, pl.initStart, pl.initEnd);
                    writeBytes(initPart, init);
                }
                parts.add(initPart);
                total += initPart.length();
            }

            // 分片并发下载
            AtomicLong doneBytes = new AtomicLong(total);
            List<File> segParts = Collections.synchronizedList(new ArrayList<File>());
            List<String> failures = Collections.synchronizedList(new ArrayList<String>());
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
            CountDownLatch latch = new CountDownLatch(pl.segments.size());

            for (int k = 0; k < pl.segments.size(); k++) {
                final Seg seg = pl.segments.get(k);
                final int i = k;
                pool.submit(new Runnable() {
                    public void run() {
                        try {
                            File part = partFile(out, "seg" + i);
                            if (!part.exists() || part.length() == 0) {
                                byte[] bytes = fetchWithRetry(seg.url, seg.start, seg.end);
                                writeBytes(part, bytes);
                            }
                            segParts.add(part);
                            doneBytes.addAndGet(part.length());
                            task.downloaded = doneBytes.get();
                            synchronized (task.lock) {
                                task.segments.add(new DownloadEngine.Segment(i, i, part.length()));
                            }
                            listener.onProgress(task);
                        } catch (Exception e) {
                            failures.add("分片 " + i + "：" + DownloadEngine.friendly(e));
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }
            latch.await();
            pool.shutdown();

            if (!failures.isEmpty()) {
                cleanupParts(out, initPart, segParts);
                throw new IllegalStateException(failures.get(0));
            }

            // 按顺序拼接：init（如有）+ seg0..segN
            Collections.sort(segParts, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return partIndex(a) - partIndex(b);
                }
            });
            parts.addAll(segParts);
            concat(out, parts);
            cleanupParts(out, initPart, segParts);

            task.total = out.length();
            task.downloaded = out.length();
            listener.onDone(task);
        } catch (Exception e) {
            listener.onError(task, DownloadEngine.friendly(e));
        }
    }

    // ---------------------------------------------------------------- 解析
    static boolean isMaster(String body) {
        return body != null && body.contains("#EXT-X-STREAM-INF");
    }

    static List<Variant> parseMaster(String body, String baseUrl) {
        List<Variant> variants = new ArrayList<>();
        String[] lines = body.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
            Matcher m = BANDWIDTH_RE.matcher(line);
            int bw = 0;
            if (m.find()) {
                try { bw = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
            for (int j = i + 1; j < lines.length; j++) {
                String u = lines[j].trim();
                if (u.length() == 0) continue;
                if (u.startsWith("#")) continue;
                Variant v = new Variant();
                v.bandwidth = bw;
                v.url = resolve(baseUrl, u);
                variants.add(v);
                break;
            }
        }
        return variants;
    }

    static Variant pickBest(List<Variant> variants) {
        Variant best = variants.get(0);
        for (Variant v : variants) {
            if (v.bandwidth > best.bandwidth) best = v;
        }
        return best;
    }

    static MediaPlaylist parseMedia(String body, String baseUrl) {
        MediaPlaylist pl = new MediaPlaylist();
        String[] lines = body.split("\\r?\\n");
        // BYTERANGE 续接游标：未显式给 @offset 时，紧接上一个分片末尾。
        long cursor = 0;
        long pendingLen = -1;
        long pendingStart = -1;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#EXT-X-MAP")) {
                Matcher mu = MAP_URI_RE.matcher(line);
                if (mu.find()) pl.initMapUrl = resolve(baseUrl, mu.group(1));
                Matcher mb = MAP_BR_RE.matcher(line);
                if (mb.find()) {
                    long len = Long.parseLong(mb.group(1));
                    long off = mb.group(2) != null ? Long.parseLong(mb.group(2)) : 0;
                    pl.initStart = off;
                    pl.initEnd = off + len - 1;
                }
                continue;
            }
            if (line.startsWith("#EXT-X-BYTERANGE")) {
                Matcher m = SEG_BR_RE.matcher(line);
                if (m.find()) {
                    pendingLen = Long.parseLong(m.group(1));
                    pendingStart = m.group(2) != null ? Long.parseLong(m.group(2)) : cursor;
                }
                continue;
            }
            if (line.startsWith("#")) continue;
            Seg seg = new Seg();
            seg.url = resolve(baseUrl, line);
            if (pendingLen >= 0) {
                seg.start = pendingStart;
                seg.end = pendingStart + pendingLen - 1;
                cursor = seg.end + 1;
                pendingLen = -1;
                pendingStart = -1;
            }
            pl.segments.add(seg);
            String low = seg.url.toLowerCase();
            if (low.endsWith(".m4s") || low.endsWith(".mp4") || low.endsWith(".m4a")) pl.fmp4 = true;
        }
        if (pl.initMapUrl != null) pl.fmp4 = true;
        return pl;
    }

    private static String resolve(String base, String uri) {
        try {
            return new URL(new URL(base), uri).toString();
        } catch (Exception e) {
            return uri;
        }
    }

    // ---------------------------------------------------------------- 下载 / 拼接
    private byte[] fetchWithRetry(String url, long start, long end) throws Exception {
        int attempts = 0;
        while (true) {
            try {
                return fetcher.getBytes(url, task.referer, task.cookie, task.ua, start, end);
            } catch (DownloadEngine.HttpException e) {
                if (!e.retryable) throw e;
                attempts++;
                if (attempts > MAX_RETRY) throw e;
                Thread.sleep(500L * (1L << Math.min(attempts, 4)));
            } catch (Exception e) {
                attempts++;
                if (attempts > MAX_RETRY) throw e;
                Thread.sleep(500L * (1L << Math.min(attempts, 4)));
            }
        }
    }

    private static File partFile(File out, String tag) {
        return new File(out.getParentFile(), out.getName() + ".part." + tag);
    }

    private static void writeBytes(File part, byte[] bytes) throws Exception {
        FileOutputStream o = new FileOutputStream(part);
        try {
            o.write(bytes);
            o.flush();
        } finally {
            o.close();
        }
    }

    private static void concat(File out, List<File> parts) throws Exception {
        if (out.exists()) out.delete();
        RandomAccessFile raf = new RandomAccessFile(out, "rw");
        try {
            byte[] buf = new byte[64 * 1024];
            for (File part : parts) {
                if (!part.exists()) continue;
                RandomAccessFile in = new RandomAccessFile(part, "r");
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) raf.write(buf, 0, n);
                } finally {
                    in.close();
                }
            }
        } finally {
            raf.close();
        }
    }

    private static void cleanupParts(File out, File initPart, List<File> segParts) {
        if (initPart != null && initPart.exists()) initPart.delete();
        for (File p : segParts) {
            if (p != null && p.exists()) p.delete();
        }
        File[] all = out.getParentFile().listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.getName().startsWith(out.getName() + ".part.")) f.delete();
            }
        }
    }

    private static int partIndex(File f) {
        String n = f.getName();
        int i = n.lastIndexOf("seg");
        if (i < 0) return -1;
        try {
            return Integer.parseInt(n.substring(i + 3));
        } catch (Exception e) {
            return -1;
        }
    }
}
