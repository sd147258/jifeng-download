package com.jifeng.download;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;

/**
 * 极简 FTP 下载（PASV 被动模式，单连接）。
 *
 * 支持：匿名 / user:pass@host、SIZE 取大小、REST 断点续传、暂停/取消、进度回调。
 * 不做：目录列表、主动模式、TLS（FTPS）、多连接分段（FTP 没有 Range 概念，靠 REST 只能续传）。
 */
public class FtpDownloader {
    private static final String TAG = "JfFtp";
    public static boolean DEBUG = false;

    public interface Sink {
        /** 每收到一块数据回调；返回 false 表示立刻停（暂停/取消）。 */
        boolean onData(int len);

        void onMeta(long total);
    }

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 25000;
    private static final int CHUNK = 64 * 1024;

    /** 下载到 out；offset>0 时用 REST 续传。返回服务器声明的总大小（未知给 -1）。 */
    public static long fetch(String ftpUrl, File out, long offset, Sink sink) throws IOException {
        String url = ftpUrl;
        String scheme = "ftp";
        int port = 21;
        String user = "anonymous";
        String pass = "anonymous@";
        String path = "/";

        // 手写解析，避免 URL/URI 对 ftp 用户名里的特殊字符处理不一致
        String rest = url.substring("ftp://".length());
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        path = slash < 0 ? "/" : rest.substring(slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            String ui = authority.substring(0, at);
            authority = authority.substring(at + 1);
            int colon = ui.indexOf(':');
            if (colon >= 0) {
                user = dec(ui.substring(0, colon));
                pass = dec(ui.substring(colon + 1));
            } else if (ui.length() > 0) {
                user = dec(ui);
                pass = "";
            }
        }
        int colon2 = authority.lastIndexOf(':');
        String host = authority;
        if (colon2 > 0) {
            try {
                port = Integer.parseInt(authority.substring(colon2 + 1));
                host = authority.substring(0, colon2);
            } catch (Exception ignored) {
                host = authority;
            }
        }
        path = dec(path);

        Socket ctrl = new Socket();
        Socket data = null;
        try {
            ctrl.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            ctrl.setSoTimeout(READ_TIMEOUT);
            InputStream cin = new BufferedInputStream(ctrl.getInputStream());
            OutputStream cout = ctrl.getOutputStream();

            Reply r = reply(cin);
            if (r.code != 220) throw new IOException("FTP 连接被拒（" + r.code + "）");

            r = cmd(cout, cin, "USER " + user);
            if (r.code == 331) {
                r = cmd(cout, cin, "PASS " + pass);
            }
            if (r.code != 230 && r.code != 202) {
                throw new IOException(r.code == 530
                        ? "FTP 登录失败（账号或密码不对）"
                        : ("FTP 登录失败（" + r.code + "）"));
            }

            r = cmd(cout, cin, "TYPE I");
            if (r.code != 200) throw new IOException("FTP 切换二进制模式失败（" + r.code + "）");

            long total = -1;
            r = cmd(cout, cin, "SIZE " + path);
            if (r.code == 213 && r.text != null) {
                String n = r.text.trim();
                int sp = n.indexOf(' ');
                if (sp >= 0) n = n.substring(sp + 1).trim();
                try {
                    total = Long.parseLong(n);
                } catch (Exception ignored) {
                }
            }
            if (sink != null) sink.onMeta(total);

            long startAt = 0;
            if (offset > 0) {
                r = cmd(cout, cin, "REST " + offset);
                if (r.code == 350) {
                    startAt = offset;
                } else {
                    // 服务器不认 REST：只能从头来，把已有半成品截断
                    RandomAccessFile raf = null;
                    try {
                        raf = new RandomAccessFile(out, "rw");
                        raf.setLength(0);
                    } finally {
                        if (raf != null) try { raf.close(); } catch (Exception ignored) {}
                    }
                }
            }

            r = cmd(cout, cin, "PASV");
            if (r.code != 227) throw new IOException("FTP 被动模式失败（" + r.code + "）");
            int[] hp = parsePasv(r.text);
            if (hp == null) throw new IOException("FTP 被动模式地址解析失败");

            data = new Socket();
            data.connect(new InetSocketAddress(ip(hp), hp[4]), CONNECT_TIMEOUT);
            data.setSoTimeout(READ_TIMEOUT);

            r = cmd(cout, cin, "RETR " + path);
            if (r.code != 150 && r.code != 125) {
                if (r.code == 550) throw new IOException("服务器上没有这个文件，或没有读取权限（550）");
                throw new IOException("FTP 取文件失败（" + r.code + "）");
            }

            InputStream din = new BufferedInputStream(data.getInputStream());
            boolean append = startAt > 0 && out.exists() && out.length() >= startAt;
            FileOutputStream fos = new FileOutputStream(out, append);
            long written = 0;
            boolean stopped = false;
            try {
                byte[] buf = new byte[CHUNK];
                int n;
                while ((n = din.read(buf, 0, buf.length)) > 0) {
                    fos.write(buf, 0, n);
                    written += n;
                    if (sink != null && !sink.onData(n)) {
                        stopped = true;
                        break;
                    }
                }
            } finally {
                try { fos.close(); } catch (Exception ignored) {}
            }

            if (stopped) {
                return total;
            }
            // 数据通道读完，控制通道应该给 226 / 250
            try {
                r = reply(cin);
                if (r.code != 226 && r.code != 250) {
                    // 有些服务器在 RETR 结束时给 426 再补 226，这里只当提示
                    android.util.Log.w("JfFtp", "RETR 结束码 " + r.code + " " + r.text);
                }
            } catch (Exception ignored) {
            }
            if (total > 0 && startAt + written < total) {
                throw new IOException("FTP 传输提前结束（" + (startAt + written) + " / " + total + " 字节）");
            }
            return total;
        } finally {
            if (data != null) try { data.close(); } catch (Exception ignored) {}
            try { ctrl.close(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ 协议小工具
    private static class Reply {
        int code;
        String text = "";

        Reply(int c, String t) {
            code = c;
            text = t;
        }
    }

    private static Reply cmd(OutputStream out, InputStream in, String line) throws IOException {
        out.write((line + "\r\n").getBytes("UTF-8"));
        out.flush();
        Reply rep = reply(in);
        if (DEBUG) {
            android.util.Log.i(TAG, (line.startsWith("PASS") ? "PASS ***" : line) + " -> " + rep.code);
        }
        return rep;
    }

    private static Reply reply(InputStream in) throws IOException {
        String first = line(in);
        if (first == null || first.length() < 3) throw new IOException("FTP 应答为空");
        int code;
        try {
            code = Integer.parseInt(first.substring(0, 3));
        } catch (Exception e) {
            throw new IOException("FTP 应答看不懂：" + first);
        }
        StringBuilder all = new StringBuilder(first);
        // 多行应答：220-xxx ... 220 xxx
        if (first.length() > 3 && first.charAt(3) == '-') {
            String prefix = first.substring(0, 3) + " ";
            while (true) {
                String l = line(in);
                if (l == null) break;
                all.append('\n').append(l);
                if (l.startsWith(prefix)) break;
            }
        }
        return new Reply(code, all.toString());
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return b == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private static int[] parsePasv(String text) {
        if (text == null) return null;
        int l = text.indexOf('(');
        int r = text.indexOf(')', l + 1);
        if (l < 0 || r < 0) return null;
        String[] parts = text.substring(l + 1, r).split(",");
        if (parts.length < 6) return null;
        try {
            int[] v = new int[6];
            for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(parts[i].trim());
            return new int[]{v[0], v[1], v[2], v[3], v[4] * 256 + v[5]};
        } catch (Exception e) {
            return null;
        }
    }

    private static String ip(int[] hp) {
        return hp[0] + "." + hp[1] + "." + hp[2] + "." + hp[3];
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
