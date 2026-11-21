package com.jifeng.download;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本机流式服务：把正在下载的临时文件通过 127.0.0.1 的 HTTP 暴露给播放器，边下边播。
 * mp4 的 moov 索引常在尾部，这类文件做虚拟 faststart：单独取回 moov，喂给播放器时排在 mdat 前，
 * stco/co64 加上位移，于是播放器一开始就能读到索引，边下边播立刻出画面；真实文件不动。
 */
public class LocalStreamServer {

    private static LocalStreamServer inst;

    private final ServerSocket socket;
    private final Map<String, DownloadEngine.Task> tasks = new HashMap<>();
    private final Map<String, Mp4Faststart> layouts = new HashMap<>();
    private final Set<String> layoutTried = new HashSet<>();
    private final Map<String, ProxyCache> proxies = new HashMap<>();

    public static synchronized LocalStreamServer get() throws Exception {
        if (inst == null) inst = new LocalStreamServer();
        return inst;
    }

    private LocalStreamServer() throws Exception {
        socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "stream-http");
        t.setDaemon(true);
        t.start();
    }

    /** 注册任务并返回可播放的本地地址。 */
    public String urlFor(DownloadEngine.Task task) {
        synchronized (tasks) {
            tasks.put(task.id, task);
        }
        return "http://127.0.0.1:" + socket.getLocalPort() + "/" + task.id;
    }

    private void loop() {
        while (!socket.isClosed()) {
            try {
                final Socket s = socket.accept();
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        serve(s);
                    }
                }, "stream-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                return;
            }
        }
    }


    /**
     * 边播的数据源：已完成/已发布的成品直接用；下载中则用段临时件
     * （单流任务的段起始为 0，临时件就是从头顺序写入的字节，正好可以边写边读）。
     */
    private File sourceFor(DownloadEngine.Task t) {
        File done = t.file();
        if (done.exists() && done.length() > 0) return done;
        if (t.segments.size() == 1 && t.segments.get(0).start == 0) {
            t.streamSingle = true;
            File part = new File(t.destDir, t.filename + ".p0");
            int waited = 0;
            while (!part.exists() && waited < 12000
                    && !("done".equals(t.status) || "error".equals(t.status)
                    || "canceled".equals(t.status))) {
                try {
                    Thread.sleep(200);
                } catch (Exception ignored) {
                }
                waited += 200;
            }
            return part;
        }
        return done;
    }

    private Mp4Faststart layoutFor(DownloadEngine.Task t, File f) {
        // 源站不支持分片时没法单独把尾部索引取回来，直接顺序读（等到快下完自然会出画面）
        if (!t.supportsRange || t.total <= 0) return null;
        synchronized (layouts) {
            if (layoutTried.contains(t.id)) return layouts.get(t.id);
            layoutTried.add(t.id);
        }
        Mp4Faststart fs = null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            fs = Mp4Faststart.build(t, f, raf);
        } catch (Exception ignored) {
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
        }
        synchronized (layouts) {
            layouts.put(t.id, fs);
        }
        return fs;
    }



    /** 索引在尾部的 mp4：做一份「索引在前」的边下边拷副本，播放器直接读它。 */
    private ProxyCache proxyFor(DownloadEngine.Task t, File src, Mp4Faststart fs) {
        if (fs == null) return null;
        synchronized (proxies) {
            ProxyCache pc = proxies.get(t.id);
            if (pc != null) return pc;
        }
        try {
            ProxyCache pc = ProxyCache.create(t, src, fs);
            synchronized (proxies) {
                proxies.put(t.id, pc);
            }
            return pc;
        } catch (Exception e) {
            return null;
        }
    }

    private void serve(Socket s) {
        OutputStream out = null;
        RandomAccessFile raf = null;
        ProxyCache proxy = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String first = in.readLine();
            if (first == null) {
                s.close();
                return;
            }
            String[] p = first.split(" ");
            String path = p.length > 1 ? p[1] : "/";
            String id = path.startsWith("/") ? path.substring(1) : path;
            int q = id.indexOf('?');
            if (q >= 0) id = id.substring(0, q);

            long from = 0;
            long want = -1;
            String line;
            while ((line = in.readLine()) != null && line.length() > 0) {
                String low = line.toLowerCase();
                if (low.startsWith("range:")) {
                    String v = line.substring(6).trim();
                    int eq = v.indexOf('=');
                    String spec = eq >= 0 ? v.substring(eq + 1) : v;
                    int dash = spec.indexOf('-');
                    if (dash >= 0) {
                        String a = spec.substring(0, dash).trim();
                        String b = spec.substring(dash + 1).trim();
                        try {
                            if (a.length() > 0) from = Long.parseLong(a);
                        } catch (Exception ignored) {
                        }
                        try {
                            if (b.length() > 0) want = Long.parseLong(b);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            DownloadEngine.Task t;
            synchronized (tasks) {
                t = tasks.get(id);
            }
            File f = t == null ? null : sourceFor(t);
            out = s.getOutputStream();
            if (f == null || !f.exists()) {
                out.flush();
                return;
            }

            Mp4Faststart fs = layoutFor(t, f);
            long total = fs != null ? fs.virtualTotal : (t.total > 0 ? t.total : f.length());
            proxy = proxyFor(t, f, fs);
            if (proxy != null) fs = null;
            if (total <= 0) total = f.length();
            if (from > total - 1) from = Math.max(0, total - 1);
            long end = want > 0 ? Math.min(want, total - 1) : total - 1;
            long len = end - from + 1;

            String mime = t.filename != null && t.filename.toLowerCase().endsWith(".mp3")
                    ? "audio/mpeg" : "video/mp4";
            if (t.contentType != null && t.contentType.toLowerCase().startsWith("audio/")) mime = t.contentType;
            String head = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Type: " + mime + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Content-Range: bytes " + from + "-" + end + "/" + total + "\r\n"
                    + "Content-Length: " + len + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.flush();

            File src = proxy != null ? proxy.file() : f;
            raf = new RandomAccessFile(src, "r");
            long pos = from;
            long waitSince = 0;
            byte[] buf = new byte[64 * 1024];
            while (pos <= end) {
                long origin = pos;
                if (fs != null) {
                    if (pos >= fs.insertAt && pos < fs.insertAt + fs.moovLen) {
                        long off = pos - fs.insertAt;
                        int n = (int) Math.min(buf.length, Math.min(end - pos + 1, fs.moovLen - off));
                        out.write(fs.moov, (int) off, n);
                        out.flush();
                        pos += n;
                        continue;
                    }
                    origin = pos < fs.insertAt ? pos : pos - fs.moovLen;
                }
                long avail = proxy != null ? proxy.available() : raf.length();
                // 段临时件是预分配长度的，未写入区域是空洞；用真实下载进度封顶
                if (proxy == null && t.segments.size() == 1 && t.segments.get(0).start == 0
                        && t.downloaded < avail) avail = Math.max(0, t.downloaded);
                if (origin >= avail) {
                    if ("done".equals(t.status) || "error".equals(t.status)
                            || "canceled".equals(t.status)) break;
                    // 数据没追上：最多等 6 秒，再等下去播放器只会干挂到超时。
                    // 主动断开让它立刻报错，播放器会自动重连（届时有新数据可用）。
                    if (waitSince == 0) waitSince = System.currentTimeMillis();
                    else if (System.currentTimeMillis() - waitSince > 6000) break;
                    try {
                        Thread.sleep(120);
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                waitSince = 0;
                long n = Math.min(buf.length, Math.min(end - pos + 1, avail - origin));
                raf.seek(origin);
                int r = raf.read(buf, 0, (int) n);
                if (r <= 0) break;
                out.write(buf, 0, r);
                out.flush();
                pos += r;
            }
        } catch (Exception ignored) {
            // 播放器中断连接属于正常现象
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
