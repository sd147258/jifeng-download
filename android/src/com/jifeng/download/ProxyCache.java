package com.jifeng.download;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 给播放器准备一份「索引在前」的临时副本：写入顺序是 原文件头部 + moov + mdat，
 * mdat 边下边拷，于是播放器一开始就能拿到索引，画面立刻出来。
 * 只用于边下边播，下载本身的临时件与成品不受影响。
 */
public class ProxyCache {

    private final File dst;
    private final RandomAccessFile out;
    private volatile long written;
    private volatile boolean stop;
    private final long mdatStart;


    /** 清掉一小时后仍残留的代理副本（上次没播完留下的）。 */
    private static void sweepStale(String dir, File keep) {
        try {
            File d = new File(dir);
            File[] list = d.listFiles();
            if (list == null) return;
            long cut = System.currentTimeMillis() - 3600L * 1000L;
            for (File f : list) {
                if (f.equals(keep)) continue;
                if (!f.getName().endsWith(".fast.mp4")) continue;
                if (f.lastModified() < cut) f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public static ProxyCache create(DownloadEngine.Task t, File src, Mp4Faststart fs) throws Exception {
        File dst = new File(t.destDir, t.filename + ".fast.mp4");
        sweepStale(t.destDir, dst);
        RandomAccessFile out = new RandomAccessFile(dst, "rw");
        out.setLength(0);
        RandomAccessFile in = new RandomAccessFile(src, "r");
        try {
            // 先把 moov 之前的头部原样抄过来（等它落地）
            long prefixEnd = fs.insertAt;
            long waited = 0;
            while (t.downloaded < prefixEnd && waited < 3000
                    && !("error".equals(t.status) || "canceled".equals(t.status))) {
                Thread.sleep(50);
                waited += 50;
            }
            in.seek(0);
            byte[] b = new byte[4096];
            long pos = 0;
            while (pos < prefixEnd) {
                int r = in.read(b, 0, (int) Math.min(b.length, prefixEnd - pos));
                if (r <= 0) break;
                out.write(b, 0, r);
                pos += r;
            }
            out.write(fs.moov);
            ProxyCache pc = new ProxyCache(dst, out, fs.insertAt);
            pc.written = fs.insertAt + fs.moovLen;
            pc.start(t, src);
            return pc;
        } finally {
            in.close();
        }
    }

    private ProxyCache(File dst, RandomAccessFile out, long mdatStart) {
        this.dst = dst;
        this.out = out;
        this.mdatStart = mdatStart;
    }

    public File file() {
        return dst;
    }

    public long available() {
        return written;
    }

    private void start(final DownloadEngine.Task t, final File src) {
        Thread th = new Thread(new Runnable() {
            public void run() {
                RandomAccessFile in = null;
                long total = t.total;
                try {
                    in = new RandomAccessFile(src, "r");
                    long pos = mdatStart;
                    byte[] b = new byte[32 * 1024];
                    while (!stop) {
                        long avail = Math.min(t.downloaded, total > 0 ? total : Long.MAX_VALUE);
                        if (pos >= avail) {
                            if ("done".equals(t.status) || "error".equals(t.status)
                                    || "canceled".equals(t.status)) break;
                            Thread.sleep(40);
                            continue;
                        }
                        long n = Math.min(b.length, avail - pos);
                        in.seek(pos);
                        int r = in.read(b, 0, (int) n);
                        if (r <= 0) break;
                        out.write(b, 0, r);
                        written += r;
                        pos += r;
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        if (in != null) in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "proxy-" + t.id);
        th.setDaemon(true);
        th.start();
    }

    public void shutdown() {
        stop = true;
        try {
            out.close();
        } catch (Exception ignored) {
        }
    }
}
