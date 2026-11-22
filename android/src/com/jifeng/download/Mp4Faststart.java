package com.jifeng.download;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * mp4 快速启动（faststart）重排：把文件尾部的 moov 索引挪到 mdat 前面，
 * 并且不改动真实文件，只在给播放器吐字节时按虚拟布局拼出来。
 *
 * 虚拟布局（虚拟文件总长 = insertAt + moovLen + mdatSize）：
 *   [0, insertAt)                      原文件同位置（ftyp/free 等）
 *   [insertAt, insertAt+moovLen)        尾部取回来的 moov（stco/co64 已加上 moovLen）
 *   [insertAt+moovLen, 末尾)           原文件 [insertAt, mdatEnd) 的 mdat 数据
 */
public class Mp4Faststart {

    public long insertAt;      // moov 插入点（即 mdat 起始）
    public long moovLen;       // moov 长度
    public long virtualTotal;  // 虚拟文件总长
    public byte[] moov;        // 已调整过的 moov 字节

    private static final long MAX_MOOV_FETCH = 8L * 1024 * 1024;

    /** 已经是 faststart（moov 在前）或解析不了时返回 null，调用方退回直读模式。 */
    public static Mp4Faststart buildFromHead(DownloadEngine.Task t, byte[] headRaw, long avail) {
        try {
            long total = t.total;
            if (total <= 0 || avail < 64 || headRaw == null) return null;
            if (t.filename == null) return null;
            String name = t.filename.toLowerCase();
            if (!(name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov"))) return null;

            int headLen = (int) Math.min(avail, Math.min(headRaw.length, 64 * 1024));
            byte[] head = headRaw;
            int got = headLen;
            if (got < 128) return null;

            long mdatStart = -1;
            long mdatSize = -1;
            int p = 0;
            while (p + 8 <= got) {
                String type = str(head, p + 4);
                long size = u32(head, p);
                int hdr = 8;
                if (size == 1) {
                    if (p + 16 > got) return null;
                    size = u64(head, p + 8);
                    hdr = 16;
                } else if (size == 0) {
                    return null;
                }
                if ("moov".equals(type)) return null;          // 已经是索引在前
                if ("mdat".equals(type)) {
                    mdatStart = p;
                    mdatSize = size;
                    break;
                }
                if (size < hdr) return null;
                p += size;
            }
            if (mdatStart < 0 || mdatSize <= 0) return null;

            long moovStart = mdatStart + mdatSize;
            if (moovStart + 8 > total) return null;
            // 先只取 64 KB 够读出 moov 头；moov 更大再按精确长度补取，避免为几十 KB 的索引去拖几 MB。
            long fetchLen = Math.min(64 * 1024, total - moovStart);

            byte[] tail = fetchRange(t, moovStart, moovStart + fetchLen - 1);
            if (tail == null || tail.length < 8) return null;
            if (!"moov".equals(str(tail, 4))) return null;
            long moovSize = u32(tail, 0);
            int moovHdr = 8;
            if (moovSize == 1) {
                moovSize = u64(tail, 8);
                moovHdr = 16;
            }
            if (moovSize < moovHdr) return null;
            if (moovSize > tail.length) {
                tail = fetchRange(t, moovStart, moovStart + moovSize - 1);
                if (tail == null || tail.length < moovSize) return null;
            }
            if (moovStart + moovSize > total) return null;

            byte[] mo = new byte[(int) moovSize];
            System.arraycopy(tail, 0, mo, 0, (int) moovSize);
            adjustOffsets(mo, 0, mo.length, (int) moovSize);

            Mp4Faststart fs = new Mp4Faststart();
            fs.insertAt = mdatStart;
            fs.moovLen = moovSize;
            fs.moov = mo;
            fs.virtualTotal = mdatStart + moovSize + mdatSize;
            return fs;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 单独向源站要一段字节（带 Referer/Cookie/UA，防盗链也能过）。 */
    private static byte[] fetchRange(DownloadEngine.Task t, long from, long to) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(t.url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Range", "bytes=" + from + "-" + to);
            if (t.ua != null && t.ua.length() > 0) c.setRequestProperty("User-Agent", t.ua);
            if (t.referer != null && t.referer.length() > 0) c.setRequestProperty("Referer", t.referer);
            if (t.cookie != null && t.cookie.length() > 0) c.setRequestProperty("Cookie", t.cookie);
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return null;
            int needBytes = (int) (to - from + 1);
            int readCap = code == 206 ? needBytes : (int) Math.min(Integer.MAX_VALUE, from + needBytes);
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while (bos.size() < readCap && (n = is.read(b)) > 0) {
                bos.write(b, 0, Math.min(n, readCap - bos.size()));
            }
            is.close();
            byte[] data = bos.toByteArray();
            // 源站不支持 Range 时回的是全量，截掉前半段
            if (code == 200 && data.length > (int) (to - from + 1)) {
                int skip = (int) from;
                int len = (int) (to - from + 1);
                if (data.length < skip + len) return null;
                byte[] cut = new byte[len];
                System.arraycopy(data, skip, cut, 0, len);
                return cut;
            }
            return data;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final String[] CONTAINERS = {
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex", "moof", "traf"
    };

    /** 递归找到 stco/co64，把每个 chunk 偏移加上 delta（moov 插到前面造成的位移）。 */
    private static void adjustOffsets(byte[] b, int off, int len, int delta) {
        int p = off;
        int end = off + len;
        while (p + 8 <= end) {
            long size = u32(b, p);
            String type = str(b, p + 4);
            int hdr = 8;
            if (size == 1) {
                if (p + 16 > end) return;
                size = u64(b, p + 8);
                hdr = 16;
            } else if (size == 0) {
                size = end - p;
            }
            if (size < hdr || p + size > end) return;
            if ("stco".equals(type)) {
                int count = (int) u32(b, p + hdr + 4);
                int q = p + hdr + 8;
                for (int i = 0; i < count && q + 4 <= p + size; i++, q += 4) {
                    long v = u32(b, q) + delta;
                    b[q] = (byte) (v >>> 24);
                    b[q + 1] = (byte) (v >>> 16);
                    b[q + 2] = (byte) (v >>> 8);
                    b[q + 3] = (byte) v;
                }
            } else if ("co64".equals(type)) {
                int count = (int) u32(b, p + hdr + 4);
                int q = p + hdr + 8;
                for (int i = 0; i < count && q + 8 <= p + size; i++, q += 8) {
                    long v = u64(b, q) + delta;
                    b[q] = (byte) (v >>> 56);
                    b[q + 1] = (byte) (v >>> 48);
                    b[q + 2] = (byte) (v >>> 40);
                    b[q + 3] = (byte) (v >>> 32);
                    b[q + 4] = (byte) (v >>> 24);
                    b[q + 5] = (byte) (v >>> 16);
                    b[q + 6] = (byte) (v >>> 8);
                    b[q + 7] = (byte) v;
                }
            } else if (isContainer(type)) {
                adjustOffsets(b, p + hdr, (int) (size - hdr), delta);
            }
            p += size;
        }
    }

    private static boolean isContainer(String type) {
        for (int i = 0; i < CONTAINERS.length; i++) {
            if (CONTAINERS[i].equals(type)) return true;
        }
        return false;
    }

    private static String str(byte[] b, int p) {
        if (p + 4 > b.length) return "";
        return "" + (char) b[p] + (char) b[p + 1] + (char) b[p + 2] + (char) b[p + 3];
    }

    private static long u32(byte[] b, int p) {
        if (p + 4 > b.length) return -1;
        return ((long) (b[p] & 0xff) << 24) | ((long) (b[p + 1] & 0xff) << 16)
                | ((long) (b[p + 2] & 0xff) << 8) | (long) (b[p + 3] & 0xff);
    }

    private static long u64(byte[] b, int p) {
        if (p + 8 > b.length) return -1;
        long hi = u32(b, p);
        long lo = u32(b, p + 4);
        return (hi << 32) | (lo & 0xffffffffL);
    }
}
