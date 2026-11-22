package com.jifeng.download;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 边下边播的字节源。
 *
 * 两种形态：
 *  1) 单文件：成品、BT 落盘文件、索引前置的代理副本 —— 直接顺序读；
 *  2) 多段临时件：HTTP 断点续传把文件切成 N 段，各写 <文件名>.p<段起始>，
 *     这里按「全局偏移 → 段」映射拼成一条虚拟字节流，段内只读到已写入的部分。
 */
public class StreamSource {

    private final DownloadEngine.Task task;
    private final File single;          // 单文件源；null 表示多段模式
    private final long singleTotal;
    private final ProxyCache proxy;     // 代理副本的写入进度
    private final List<DownloadEngine.Segment> segs = new ArrayList<>();
    private final Map<Long, RandomAccessFile> handles = new HashMap<>();

    private StreamSource(DownloadEngine.Task t, File single, long total, ProxyCache proxy) {
        this.task = t;
        this.single = single;
        this.singleTotal = total;
        this.proxy = proxy;
    }

    /** 有成品文件就直接读成品；否则按段拼（下载中的 HTTP 任务）。没有可用源返回 null。 */
    public static StreamSource open(DownloadEngine.Task t, File doneFile) {
        try {
            if (doneFile != null && doneFile.exists() && doneFile.length() > 0) {
                long total = t.total > 0 ? t.total : doneFile.length();
                return new StreamSource(t, doneFile, total, null);
            }
            if (t.total > 0 && t.segments.size() > 0) {
                StreamSource s = new StreamSource(t, null, t.total, null);
                List<DownloadEngine.Segment> copy = new ArrayList<>(t.segments);
                for (int i = 1; i < copy.size(); i++) {
                    DownloadEngine.Segment key = copy.get(i);
                    int j = i - 1;
                    while (j >= 0 && copy.get(j).start > key.start) {
                        copy.set(j + 1, copy.get(j));
                        j--;
                    }
                    copy.set(j + 1, key);
                }
                s.segs.addAll(copy);
                return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 索引前置的代理副本：可用长度跟着 ProxyCache 的写入进度走。 */
    public static StreamSource forProxy(File f, ProxyCache pc, long total) {
        return new StreamSource(null, f, total, pc);
    }

    public boolean isSingle() {
        return single != null;
    }

    public File singleFile() {
        return single;
    }

    public long total() {
        return singleTotal;
    }

    public boolean emptyParts() {
        if (segs.isEmpty()) return true;
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).done > 0) return false;
        }
        return true;
    }

    /** 从 pos 开始能连续读到哪（不含）。 */
    public long readEndAt(long pos) {
        if (!isSingle()) {
            DownloadEngine.Segment s = segAt(pos);
            if (s == null) return pos;
            long segEnd = s.end >= 0 ? s.end + 1 : singleTotal;
            long ready = s.start + Math.min(s.done, Math.max(0, segEnd - s.start));
            return Math.min(segEnd, ready);
        }
        if (proxy != null) return Math.min(singleTotal, proxy.available());
        long avail;
        if (task != null && !"done".equals(task.status)) {
            avail = Math.min(task.downloaded, singleTotal);
        } else {
            avail = singleTotal;
        }
        return Math.max(0, avail);
    }

    /** 读一段：读到的字节数可能小于 len（到段边界或未下载处为止）。 */
    public int readAt(long pos, byte[] b, int off, int len) throws Exception {
        long end = readEndAt(pos);
        if (pos < 0 || end <= pos || len <= 0) return 0;
        int n = (int) Math.min(len, end - pos);
        RandomAccessFile raf;
        long seek;
        if (isSingle()) {
            raf = singleHandle();
            seek = pos;
        } else {
            DownloadEngine.Segment s = segAt(pos);
            if (s == null) return 0;
            raf = partHandle(s);
            seek = pos - s.start;
            n = (int) Math.min(n, (s.end >= 0 ? s.end + 1 : singleTotal) - pos);
        }
        raf.seek(seek);
        return raf.read(b, off, n);
    }

    /** 只取文件头一小段，用来解析 ftyp/mdat，判需不需要 faststart。 */
    public byte[] head(int max) {
        byte[] buf = new byte[max];
        int got = 0;
        try {
            while (got < max) {
                int r = readAt(got, buf, got, max - got);
                if (r <= 0) break;
                got += r;
            }
        } catch (Exception ignored) {
        }
        if (got <= 0) return new byte[0];
        if (got == max) return buf;
        byte[] cut = new byte[got];
        System.arraycopy(buf, 0, cut, 0, got);
        return cut;
    }

    public void close() {
        for (RandomAccessFile r : handles.values()) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        handles.clear();
    }

    private DownloadEngine.Segment segAt(long pos) {
        for (int i = 0; i < segs.size(); i++) {
            DownloadEngine.Segment s = segs.get(i);
            if (pos >= s.start && (s.end < 0 || pos <= s.end)) return s;
        }
        return null;
    }

    private RandomAccessFile singleHandle() throws Exception {
        RandomAccessFile r = handles.get(-1L);
        if (r == null) {
            r = new RandomAccessFile(single, "r");
            handles.put(-1L, r);
        }
        return r;
    }

    private RandomAccessFile partHandle(DownloadEngine.Segment s) throws Exception {
        RandomAccessFile r = handles.get(s.start);
        if (r == null) {
            File f = new File(task.destDir, task.filename + ".p" + s.start);
            r = new RandomAccessFile(f, "r");
            handles.put(s.start, r);
        }
        return r;
    }
}
