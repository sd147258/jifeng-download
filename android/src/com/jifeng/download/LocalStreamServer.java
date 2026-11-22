package com.jifeng.download;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本机流式服务：把正在下载的文件通过 127.0.0.1 的 HTTP 暴露给播放器，边下边播。
 *
 * 支持两种任务形态：
 *  - 单流/成品/BT 落盘文件：直接顺序读，读到的长度按下载进度封顶；
 *  - HTTP 多段并行：各段写在 <文件名>.p<段起始>，这里按全局偏移映射到对应临时件，
 *    拼成一条虚拟字节流，段内只读到该段已经写入的部分。
 *
 * mp4 的 moov 索引常在尾部：单独向源站取回 moov，喂给播放器时排在 mdat 前，
 * stco/co64 加上位移，于是播放器一开始就能读到索引；真实文件不动。
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
        String u = "http://127.0.0.1:" + socket.getLocalPort() + "/" + task.id;
        Log.i("JfStream", "urlFor id=" + task.id + " -> " + u + " total=" + task.total + " segs=" + task.segments.size());
        return u;
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

    private Mp4Faststart layoutFor(DownloadEngine.Task t, StreamSource src) {
        // 源站不支持分片时没法单独把尾部索引取回来，直接顺序读（等快下完自然出画面）
        if (!t.supportsRange || t.total <= 0) return null;
        synchronized (layouts) {
            if (layoutTried.contains(t.id)) return layouts.get(t.id);
            layoutTried.add(t.id);
        }
        Mp4Faststart fs = null;
        try {
            fs = Mp4Faststart.buildFromHead(t, src.head(64 * 1024), src.total());
        } catch (Exception ignored) {
        }
        synchronized (layouts) {
            layouts.put(t.id, fs);
        }
        return fs;
    }

    /** 索引在尾部的 mp4：做一份「索引在前」的边下边拷副本，播放器直接读它。 */
    private ProxyCache proxyFor(DownloadEngine.Task t, StreamSource src, Mp4Faststart fs) {
        if (fs == null || !src.isSingle()) return null;
        synchronized (proxies) {
            ProxyCache pc = proxies.get(t.id);
            if (pc != null) return pc;
        }
        try {
            ProxyCache pc = ProxyCache.create(t, src.singleFile(), fs);
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
        StreamSource src = null;
        try {
            try {
                s.setSoTimeout(2500);
            } catch (Exception ignored) {
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String first = in.readLine();
            Log.i("JfStream", "conn " + first);
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
            int nh = 0;
            while (true) {
                try {
                    line = in.readLine();
                } catch (java.net.SocketTimeoutException ste) {
                    break;
                }
                if (line == null || line.length() == 0) break;
                nh++;
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
            out = s.getOutputStream();
            if (t == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                out.flush();
                return;
            }

            File done = t.file();
            src = StreamSource.open(t, done.exists() ? done : null);
            if (src == null) {
                out.write("HTTP/1.1 503 No Source Yet\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                out.flush();
                return;
            }

            Mp4Faststart fs = layoutFor(t, src);
            ProxyCache proxy = proxyFor(t, src, fs);
            if (proxy != null) {
                src.close();
                src = StreamSource.forProxy(proxy.file(), proxy, fs.virtualTotal);
                fs = null;
            }

            long total = fs != null ? fs.virtualTotal : src.total();
            if (total <= 0) total = Math.max(1, t.total);
            if (from > total - 1) from = Math.max(0, total - 1);
            long end = want > 0 ? Math.min(want, total - 1) : total - 1;
            long len = end - from + 1;

            String mime = t.filename != null && t.filename.toLowerCase().endsWith(".mp3")
                    ? "audio/mpeg" : "video/mp4";
            if (t.contentType != null && t.contentType.toLowerCase().startsWith("audio/")) mime = t.contentType;
            String mode = proxy != null ? "proxy" : (src.isSingle() ? "file" : "parts");
            Log.i("JfStream", "open id=" + t.id + " mode=" + mode + " segs=" + t.segments.size()
                    + " total=" + total + " range=" + from + "-" + end);
            String head = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Type: " + mime + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Content-Range: bytes " + from + "-" + end + "/" + total + "\r\n"
                    + "Content-Length: " + len + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.flush();

            long pos = from;
            long waitSince = 0;
            long served = 0;
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
                        served += n;
                        continue;
                    }
                    origin = pos < fs.insertAt ? pos : pos - fs.moovLen;
                }
                long ready = src.readEndAt(origin);
                if (origin >= ready) {
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
                long n = Math.min(buf.length, Math.min(end - pos + 1, ready - origin));
                if (fs != null && pos < fs.insertAt && pos + n > fs.insertAt) {
                    // 不能一口气跨过插入点，否则 moov 会被原始字节整块盖过去。
                    n = fs.insertAt - pos;
                }
                int r = src.readAt(origin, buf, 0, (int) n);
                if (r <= 0) break;
                out.write(buf, 0, r);
                out.flush();
                pos += r;
                served += r;
            }
            if (served > 0) {
                Log.i("JfStream", "served id=" + t.id + " bytes=" + served + " next=" + pos);
            }
        } catch (Exception ignored) {
            // 播放器中断连接属于正常现象
        } finally {
            try {
                if (src != null) src.close();
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
