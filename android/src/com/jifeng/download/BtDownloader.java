package com.jifeng.download;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 自研极简 BitTorrent 下载器（磁力链接）：HTTP/UDP tracker 找 peer → BEP9 交换 info 元数据
 * → 顺序拉取 piece（16KB 分块）→ SHA1 校验。只做下载，不做种；只支持单文件种子。
 * 找源顺序：先 tracker（tr=），tracker 全挂或压根没 tr= 时退到 DHT（BEP5）。
 */
public class BtDownloader {

    private static final int BLOCK = 16 * 1024;
    private static final int META_PIECE = 16 * 1024;
    private static final int CONNECT_TIMEOUT = 12000;
    private static final int READ_TIMEOUT = 25000;
    private static final int META_BUDGET_MS = 75000;
    /** DHT（BEP5）找源：默认开；自检可关。 */
    public static volatile boolean dhtEnabled = true;
    /** DHT 找源预算，自检可缩短。 */
    public static volatile long dhtBudgetMs = 18000;
    /** 自检：跳过 tracker，只走 DHT。 */
    public static volatile boolean forceDht = false;
    /** 自检：只做 DHT 找源，不真下载（用真实种子验证 DHT 连通性）。 */
    public static volatile boolean dhtProbeOnly = false;
    /** 自检：只通过 DHT 拿种子元数据，不下载文件（验证 DHT → peer → metadata 全链路）。 */
    public static volatile boolean metaProbeOnly = false;
    private static final int PIECE_TRIES = 3;

    private final DownloadEngine engine;
    private final DownloadEngine.Task t;
    private final byte[] peerId;

    private Info curInfo;
    private java.io.File curRoot;
    private final java.util.HashMap<String, RandomAccessFile> curHandles =
            new java.util.HashMap<String, RandomAccessFile>();

    private long lastFire;
    private long speedBytes;
    private long speedAt;

    public BtDownloader(DownloadEngine engine, DownloadEngine.Task t) {
        this.engine = engine;
        this.t = t;
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Random r = new Random();
        StringBuilder sb = new StringBuilder("-JF0100-");
        for (int i = 0; i < 12; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        peerId = sb.toString().getBytes();
        speedAt = System.currentTimeMillis();
    }

    static final String TAG = "JfBt";

    static void writeCrash(String msg) {
        try {
            android.util.Log.i(TAG, "crash " + msg);
        } catch (Throwable ignored) {
        }
    }

    private static void log(String m) {
        try {
            android.util.Log.i(TAG, m);
        } catch (Throwable ignored) {
        }
    }

    /** 本机有没有 IPv4 地址：IPv6-only 的 5G 网络连不上 DHT 的 IPv4 节点。 */
    private static boolean hasIpv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> ads = ni.getInetAddresses();
                while (ads.hasMoreElements()) {
                    java.net.InetAddress a = ads.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public void run() {
        try {
            inner();
        } catch (Throwable e) {
            log("fail: " + friendly(e));
            if (t.stop) return;
            engine.btFail(t, "磁力下载失败：" + friendly(e));
        }
    }

    private static String friendly(Throwable e) {
        String m = e.getMessage();
        if (m != null && m.length() > 0) return m;
        return e.getClass().getSimpleName();
    }

    private void inner() throws Exception {
        Magnet m = Magnet.parse(t.url);
        if (m == null || !m.valid()) {
            engine.btFail(t, m == null ? "不是磁力链接（应以 magnet:? 开头）" : m.errorHint());
            return;
        }
        if (t.filename == null || t.filename.length() == 0) {
            t.filename = m.name.length() > 0 ? clean(m.name) : ("磁力_" + m.hashHex.substring(0, 8));
        }
        t.kind = "bt";
        t.status = "running";
        log("start hash=" + m.hashHex.substring(0, 8) + " trackers=" + m.trackers.size());
        t.total = m.exactLength > 0 ? m.exactLength : t.total;
        t.error = "";
        tone();

        List<String> trackers = forceDht ? new ArrayList<String>() : new ArrayList<String>(m.trackers);
        LinkedHashSet<String> peers = new LinkedHashSet<String>();
        if (!forceDht) peers.addAll(m.peers);
        for (int i = 0; i < trackers.size() && i < 6; i++) {
            if (t.stop) {
                log("stop-return tracker");
                return;
            }
            try {
                peers.addAll(announce(trackers.get(i), m, t));
            } catch (Exception ignored) {
            }
        }
        log("tracker peers=" + peers.size());
        if (dhtEnabled && peers.isEmpty() && !t.stop) {
            log("dht lookup start");
            try {
                peers.addAll(Dht.findPeers(m.hashHex, dhtBudgetMs));
            } catch (Exception e) {
                log("dht error " + e);
            }
            log("dht peers=" + peers.size());
        }
        tone();
        if (dhtProbeOnly) {
            engine.btFail(t, "DHT 探测结束：找到 " + peers.size() + " 个 peer（自检模式，不下载）");
            return;
        }
        if (peers.isEmpty()) {
            String diag = Dht.lastStats.length() > 0 ? ("\nDHT 诊断：" + Dht.lastStats + "。") : "";
            String net = !hasIpv4()
                    ? "当前网络拿不到 IPv4 地址（IPv6-only），DHT 的 IPv4 节点连不上，换 Wi-Fi 再试。"
                    : (Dht.lastResp > 0
                    ? "DHT 是通的（有节点回应），说明这条链现在确实没有做种者，换一条资源更靠谱。"
                    : "一个回应都没收到，说明 UDP 出站被挡了（部分 5G / 校园网 / 公司网会挡），换 Wi-Fi 或换个网络再试。");
            String ws = m.webSeeds.isEmpty() ? ""
                    : ("\n这条链带 ws=（Web Seed）直链：" + m.webSeeds.get(0)
                    + "，可以复制它当普通链接直接下载。");
            log("fail: " + diag + " | " + net);
            engine.btFail(t, (trackers.isEmpty()
                    ? "这条磁力链没有 tr=（tracker），DHT 也没找到做种者。" + diag
                    + "也可能这会儿确实没人做种，稍后再试或换一条资源。" + net
                    : "tracker 和 DHT 都没找到做种者：可能这条种子已经没人做种，"
                    + "或者当前网络访问不了它的 tracker。" + diag + "换一条资源或稍后再试。") + ws);
            return;
        }

        Info info = null;
        long deadline = System.currentTimeMillis() + META_BUDGET_MS;
        List<String> tried = new ArrayList<String>();
        for (String addr : peers) {
            if (t.stop) {
                log("stop-return meta");
                return;
            }
            if (System.currentTimeMillis() > deadline) break;
            Peer p = null;
            try {
                p = new Peer(addr, m.infoHash, peerId);
                p.open();
                info = p.fetchInfo(m.infoHash);
                if (info != null) break;
                tried.add(addr);
            } catch (Exception e) {
                tried.add(addr);
            } finally {
                if (p != null && info == null) p.close();
            }
        }
        log("info=" + (info == null ? "null" : (info.name + " len=" + info.length)));
        if (info != null && metaProbeOnly) {
            engine.btFail(t, "元数据探测成功：" + info.name + " · " + info.length
                    + " 字节（自检模式，未下载文件）");
            return;
        }
        if (info == null) {
            engine.btFail(t, "连上了 peer 但拿不到种子信息（可能对方不支持元数据交换）。"
                    + "建议用专门的 BT 客户端，或换一条资源。");
            return;
        }

        if (t.filename == null || t.filename.length() == 0
                || t.filename.equalsIgnoreCase(m.name)) {
            t.filename = clean(info.name.length() > 0 ? info.name
                    : (m.name.length() > 0 ? m.name : ("磁力_" + m.hashHex.substring(0, 8))));
        }
        t.btMulti = info.files.size() > 1;
        if (t.btMulti && info.name.length() > 0) t.filename = clean(info.name);
        t.total = info.length;
        t.downloaded = 0;
        t.ext = "";
        tone();

        download(m, info, peers);
    }

    // ------------------------------------------------------------ 下载整片
    private void download(Magnet m, Info info, LinkedHashSet<String> peers) throws Exception {
        int pieceLen = (int) info.pieceLength;
        int count = info.pieceCount;
        // 单文件种子直接写任务文件；多文件种子建一个同名目录，按种子里的相对路径落盘
        curInfo = info;
        if (info.files.size() > 1) {
            curRoot = new java.io.File(t.destDir, t.filename);
            if (!curRoot.exists() && !curRoot.mkdirs()) {
                engine.btFail(t, "无法创建种子目录：" + t.filename);
                return;
            }
        } else {
            info.files.get(0).path = t.filename;
            curRoot = new java.io.File(t.destDir);
        }
        curHandles.clear();
        prepareFiles(info);
        List<String> peerList = new ArrayList<String>(peers);
        Peer live = null;
        int idx = 0;
        int peerCursor = 0;
        try {
            while (idx < count) {
                if (t.stop) {
                    log("stop-return outer");
                    tone();
                    return;
                }
                if (live == null) {
                    if (peerList.isEmpty()) {
                        engine.btFail(t, "所有 peer 都断开了，已下载的部分保留在临时文件里。可以稍后重试。");
                        return;
                    }
                    String addr = peerList.get(peerCursor % peerList.size());
                    Peer p = new Peer(addr, m.infoHash, peerId);
                    try {
                        p.open();
                        p.unlock();
                        live = p;
                    } catch (Exception e) {
                        p.close();
                        peerCursor++;
                        if (peerCursor > peerList.size() * 2) {
                            engine.btFail(t, "peer 全都连不上（可能被防火墙/运营商拦截）。"
                                    + "磁力下载对网络环境比较挑，建议换个网络再试。");
                            return;
                        }
                    }
                    continue;
                }
                int begin = 0;
                int size = Math.min(pieceLen, (int) (info.length - (long) idx * pieceLen));
                byte[] piece = new byte[size];
                boolean ok = true;
                try {
                    while (begin < size) {
                        if (t.stop) {
                            log("stop-return inner");
                            tone();
                            return;
                        }
                        int len = Math.min(BLOCK, size - begin);
                        long t0 = System.currentTimeMillis();
                        DownloadEngine.acquire(len);
                        long t1 = System.currentTimeMillis();
                        byte[] block = live.request(idx, begin, len);
                        long t2 = System.currentTimeMillis();
                        if (block == null || block.length != len) throw new IOException("分块不完整");
                        System.arraycopy(block, 0, piece, begin, len);
                        begin += len;
                        t.downloaded += len;
                        tone();
                    }
                    log("verify piece " + idx);
                    if (!info.match(idx, piece)) {
                        throw new IOException("piece " + idx + " 校验不通过");
                    }
                    writeAt((long) idx * pieceLen, piece);
                    if (idx % 5 == 0) log("piece " + idx + "/" + count + " downloaded=" + t.downloaded);
                    idx++;
                } catch (Throwable e) {
                    log("piece fail: " + e);
                    ok = false;
                }
                if (!ok) {
                    t.downloaded -= begin;
                    if (t.downloaded < 0) t.downloaded = 0;
                    live.close();
                    live = null;
                    peerCursor++;
                    if (peerCursor > peerList.size() * 2 + 4) {
                        engine.btFail(t, "下载反复失败（peer 给的数据校验不过或连接被断）。"
                                + "已下载部分保留，可以重试。");
                        return;
                    }
                }
            }
            t.downloaded = info.length;
            log("all pieces done, finishing");
            tone();
            engine.btFinish(t);
        } finally {
            log("download-exit");
            if (live != null) live.close();
            closeFiles();
        }
    }

    // ------------------------------------------------------------ 按文件布局写入
    private void prepareFiles(Info info) throws java.io.IOException {
        for (FileEnt fe : info.files) {
            java.io.File f = new java.io.File(curRoot, fe.path);
            java.io.File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (f.exists()) continue;
            RandomAccessFile r = new RandomAccessFile(f, "rw");
            try {
                if (fe.length > 0) r.setLength(fe.length);
            } finally {
                r.close();
            }
        }
    }

    private RandomAccessFile handle(FileEnt fe) throws java.io.IOException {
        RandomAccessFile r = curHandles.get(fe.path);
        if (r != null) return r;
        java.io.File f = new java.io.File(curRoot, fe.path);
        java.io.File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new java.io.IOException("无法创建目录：" + fe.path);
        }
        r = new RandomAccessFile(f, "rw");
        if (fe.length > 0 && r.length() != fe.length) r.setLength(fe.length);
        curHandles.put(fe.path, r);
        return r;
    }

    /** 全片偏移落在第几个文件（files 的 start 已按顺序累加）。 */
    private int fileAt(long off) {
        int lo = 0, hi = curInfo.files.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (curInfo.files.get(mid).start <= off) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** 按种子全片偏移写字节；一个分片跨两个文件时自动切开。 */
    private void writeAt(long off, byte[] data) throws java.io.IOException {
        int done = 0;
        while (done < data.length) {
            long at = off + done;
            FileEnt fe = curInfo.files.get(fileAt(at));
            long inFile = at - fe.start;
            int len = (int) Math.min((long) (data.length - done), fe.length - inFile);
            if (len <= 0) throw new java.io.IOException("落盘位置越界");
            RandomAccessFile r = handle(fe);
            r.seek(inFile);
            r.write(data, done, len);
            done += len;
        }
    }

    private void closeFiles() {
        for (RandomAccessFile r : curHandles.values()) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        curHandles.clear();
    }

    private void tone() {
        long now = System.currentTimeMillis();
        boolean willFire = (now - lastFire > 700) || t.downloaded >= t.total;
        long dt = now - speedAt;
        if (dt >= 700) {
            t.speed = (t.downloaded - speedBytes) * 1000.0 / dt;
            speedBytes = t.downloaded;
            speedAt = now;
        }
        if (willFire) {
            lastFire = now;
            try {
                engine.btFire(t);
            } catch (Throwable e) {
                log("fire fail: " + e);
            }
        }
    }

    // ------------------------------------------------------------ tracker
    private List<String> announce(String tracker, Magnet m, DownloadEngine.Task t) throws Exception {
        if (tracker.startsWith("udp://")) return announceUdp(tracker, m, t);
        return announceHttp(tracker, m, t);
    }

    private List<String> announceHttp(String tracker, Magnet m, DownloadEngine.Task t) throws Exception {
        StringBuilder url = new StringBuilder(tracker);
        url.append(tracker.contains("?") ? '&' : '?');
        url.append("info_hash=").append(pct(m.infoHash));
        url.append("&peer_id=").append(pct(peerId));
        url.append("&port=6881&uploaded=0&downloaded=").append(Math.max(0, t.downloaded));
        url.append("&left=").append(t.total > 0 ? Math.max(0, t.total - t.downloaded) : 0);
        url.append("&compact=1&event=started&numwant=40");
        java.net.URL u = new java.net.URL(url.toString());
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", DownloadEngine.UA);
        InputStream in = c.getInputStream();
        byte[] body = readAll(in);
        in.close();
        c.disconnect();
        Object o = Bencode.decode(body);
        return peersOf(Bencode.map(o));
    }

    private List<String> announceUdp(String tracker, Magnet m, DownloadEngine.Task t) throws Exception {
        String hostPort = tracker.substring(6);
        int slash = hostPort.indexOf('/');
        if (slash > 0) hostPort = hostPort.substring(0, slash);
        int colon = hostPort.lastIndexOf(':');
        String host = colon > 0 ? hostPort.substring(0, colon) : hostPort;
        int port = colon > 0 ? Integer.parseInt(hostPort.substring(colon + 1)) : 80;
        DatagramSocket s = new DatagramSocket();
        s.setSoTimeout(6000);
        try {
            byte[] connect = new byte[16];
            writeLong(connect, 0, 0x41727101980L);
            writeInt(connect, 8, 0);
            int tx = new Random().nextInt();
            writeInt(connect, 12, tx);
            s.send(new DatagramPacket(connect, 16, InetAddress.getByName(host), port));
            byte[] buf = new byte[2048];
            DatagramPacket rp = new DatagramPacket(buf, buf.length);
            s.receive(rp);
            if (readInt(buf, 0) != 0 || readInt(buf, 4) != tx) return new ArrayList<String>();
            long connId = readLong(buf, 8);

            byte[] req = new byte[98];
            writeLong(req, 0, connId);
            writeInt(req, 8, 1);
            writeInt(req, 12, tx);
            System.arraycopy(m.infoHash, 0, req, 16, 20);
            System.arraycopy(peerId, 0, req, 36, 20);
            writeLong(req, 56, Math.max(0, t.downloaded));
            writeLong(req, 64, t.total > 0 ? Math.max(0, t.total - t.downloaded) : 0);
            writeLong(req, 72, 0);
            writeInt(req, 80, 1);   // started
            writeInt(req, 84, 0);
            writeInt(req, 88, new Random().nextInt());
            writeInt(req, 92, -1);
            s.send(new DatagramPacket(req, 98, InetAddress.getByName(host), port));
            DatagramPacket rp2 = new DatagramPacket(buf, buf.length);
            s.receive(rp2);
            int len = rp2.getLength();
            List<String> out = new ArrayList<String>();
            for (int i = 20; i + 6 <= len; i += 6) {
                String ip = (buf[i] & 0xff) + "." + (buf[i + 1] & 0xff) + "."
                        + (buf[i + 2] & 0xff) + "." + (buf[i + 3] & 0xff);
                int p = ((buf[i + 4] & 0xff) << 8) | (buf[i + 5] & 0xff);
                if (p > 0 && !ip.startsWith("0.")) out.add(ip + ":" + p);
            }
            return out;
        } finally {
            s.close();
        }
    }

    private List<String> peersOf(Map<String, Object> resp) {
        List<String> out = new ArrayList<String>();
        Object peers = resp.get("peers");
        if (peers instanceof byte[]) {
            byte[] raw = (byte[]) peers;
            for (int i = 0; i + 6 <= raw.length; i += 6) {
                String ip = (raw[i] & 0xff) + "." + (raw[i + 1] & 0xff) + "."
                        + (raw[i + 2] & 0xff) + "." + (raw[i + 3] & 0xff);
                int p = ((raw[i + 4] & 0xff) << 8) | (raw[i + 5] & 0xff);
                if (p > 0) out.add(ip + ":" + p);
            }
        } else if (peers instanceof List) {
            for (Object o : Bencode.list(peers)) {
                Map<String, Object> d = Bencode.map(o);
                String ip = Bencode.str(d.get("ip"));
                long p = Bencode.num(d.get("port"));
                if (ip.length() > 0 && p > 0) out.add(ip + ":" + p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ peer 连接
    class Peer {
        final String addr;
        final byte[] infoHash;
        final byte[] myId;
        Socket sock;
        InputStream in;
        OutputStream out;
        int theirMetaId = -1;
        boolean unchoked;

        Peer(String addr, byte[] infoHash, byte[] myId) {
            this.addr = addr;
            this.infoHash = infoHash;
            this.myId = myId;
        }

        void open() throws IOException {
            String host = addr.substring(0, addr.lastIndexOf(':'));
            int port = Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1));
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            sock.setSoTimeout(READ_TIMEOUT);
            in = sock.getInputStream();
            out = sock.getOutputStream();
            // 握手
            byte[] hs = new byte[68];
            hs[0] = 19;
            System.arraycopy("BitTorrent protocol".getBytes(), 0, hs, 1, 19);
            // 保留位：第 5 字节置 0x10 表示支持扩展协议（BEP10）
            hs[20 + 5] = 0x10;
            System.arraycopy(infoHash, 0, hs, 28, 20);
            System.arraycopy(myId, 0, hs, 48, 20);
            out.write(hs);
            out.flush();
            byte[] rh = new byte[68];
            readFully(in, rh, 0, 68);
            if (rh[0] != 19) throw new IOException("不是 BT peer");
            byte[] got = new byte[20];
            System.arraycopy(rh, 28, got, 0, 20);
            if (!MessageDigest.isEqual(got, infoHash)) throw new IOException("info_hash 不匹配");
            // 扩展握手
            Map<String, Object> m = new java.util.HashMap<String, Object>();
            Map<String, Object> exts = new java.util.HashMap<String, Object>();
            exts.put("ut_metadata", 1L);
            m.put("m", exts);
            m.put("v", "Jifeng/3.2");
            m.put("reqq", 8L);
            sendExt(0, Bencode.encode(m));
        }

        void unlock() throws IOException {
            sendRaw(2, new byte[0]);   // interested
            long deadline = System.currentTimeMillis() + 15000;
            while (!unchoked) {
                if (System.currentTimeMillis() > deadline) throw new IOException("peer 一直不 unchoke");
                Msg mm = readMsg();
                if (mm == null) throw new IOException("peer 断开");
                if (mm.id == 1) unchoked = true;
            }
        }

        Info fetchInfo(byte[] infoHash) throws IOException {
            long deadline = System.currentTimeMillis() + 25000;
            byte[] meta = null;
            int total = -1;
            while (System.currentTimeMillis() < deadline) {
                Msg mm = readMsg();
                if (mm == null) return null;
                if (mm.id != 20) continue;
                byte[] payload = mm.payload;
                if (payload.length < 1) continue;
                int extId = payload[0] & 0xff;
                byte[] body = new byte[payload.length - 1];
                System.arraycopy(payload, 1, body, 0, body.length);
                if (extId == 0) {
                    Map<String, Object> hs = Bencode.map(Bencode.decode(body));
                    Object mp = hs.get("m");
                    if (mp instanceof Map) {
                        Object id = Bencode.map(mp).get("ut_metadata");
                        if (id instanceof Long) theirMetaId = (int) ((Long) id).longValue();
                    }
                    total = (int) Bencode.num(hs.get("metadata_size"));
                    if (theirMetaId <= 0 || total <= 0) return null;
                    meta = new byte[total];
                    int pieces = (total + META_PIECE - 1) / META_PIECE;
                    for (int i = 0; i < pieces; i++) {
                        Map<String, Object> req = new java.util.HashMap<String, Object>();
                        req.put("msg_type", 0L);
                        req.put("piece", (long) i);
                        byte[] enc = Bencode.encode(req);
                        byte[] ext = new byte[enc.length + 1];
                        ext[0] = (byte) theirMetaId;
                        System.arraycopy(enc, 0, ext, 1, enc.length);
                        sendRaw(20, ext);
                    }
                    continue;
                }
                if (extId == 1 && meta != null) {
                    // ut_metadata 回复：bencode 头 + 原始片段（头里可能含字母 e，必须按 bencode 结构定位）
                    int endHead = Bencode.endOf(body, 0);
                    if (endHead <= 0 || endHead > body.length) continue;
                    Map<String, Object> d = Bencode.map(Bencode.decode(body, 0, endHead));
                    long pieceNo = Bencode.num(d.get("piece"));
                    byte[] chunk = new byte[body.length - endHead];
                    System.arraycopy(body, endHead, chunk, 0, chunk.length);
                    int off = (int) pieceNo * META_PIECE;
                    System.arraycopy(chunk, 0, meta, off, Math.min(chunk.length, meta.length - off));
                    if (off + chunk.length >= meta.length) {
                        if (!MessageDigest.isEqual(sha1(meta), infoHash)) throw new IOException("元数据校验失败");
                        Info info = Info.parse(meta);
                        if (info != null) return info;
                    }
                }
            }
            return null;
        }

        byte[] request(int index, int begin, int len) throws IOException {
            byte[] req = new byte[12];
            writeInt(req, 0, index);
            writeInt(req, 4, begin);
            writeInt(req, 8, len);
            sendRaw(6, req);
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline) {
                Msg mm = readMsg();
                if (mm == null) throw new IOException("peer 断开");
                if (mm.id == 7 && mm.payload.length >= 8) {
                    int idx2 = readInt(mm.payload, 0);
                    int beg2 = readInt(mm.payload, 4);
                    if (idx2 == index && beg2 == begin) {
                        byte[] block = new byte[mm.payload.length - 8];
                        System.arraycopy(mm.payload, 8, block, 0, block.length);
                        return block;
                    }
                }
                if (mm.id == 1) unchoked = true;
            }
            throw new IOException("等不到数据块");
        }

        void sendExt(int extId, byte[] payload) throws IOException {
            byte[] p = new byte[payload.length + 1];
            p[0] = (byte) extId;
            System.arraycopy(payload, 0, p, 1, payload.length);
            sendRaw(20, p);
        }

        void sendRaw(int id, byte[] payload) throws IOException {
            byte[] msg = new byte[payload.length + 5];
            writeInt(msg, 0, payload.length + 1);
            msg[4] = (byte) id;
            System.arraycopy(payload, 0, msg, 5, payload.length);
            out.write(msg);
            out.flush();
        }

        Msg readMsg() throws IOException {
            byte[] hdr = new byte[4];
            try {
                readFully(in, hdr, 0, 4);
            } catch (IOException e) {
                return null;
            }
            int len = readInt(hdr, 0);
            if (len == 0) return new Msg(0, new byte[0]);
            if (len < 0 || len > 4 * 1024 * 1024) throw new IOException("长度异常");
            byte[] body = new byte[len];
            readFully(in, body, 0, len);
            byte[] payload = new byte[len - 1];
            System.arraycopy(body, 1, payload, 0, payload.length);
            return new Msg(body[0] & 0xff, payload);
        }

        void close() {
            try {
                if (sock != null) sock.close();
            } catch (Exception ignored) {
            }
        }
    }

    static class Msg {
        final int id;
        final byte[] payload;

        Msg(int id, byte[] payload) {
            this.id = id;
            this.payload = payload;
        }
    }

    // ------------------------------------------------------------ info 元数据
    static class FileEnt {
        String path = "";   // 相对种子的路径，已清洗
        long length;
        long start;         // 全片偏移起点
    }

    static class Info {
        String name = "";
        long length;        // 所有文件加起来的总长
        long pieceLength;
        int pieceCount;
        byte[] pieces;
        final List<FileEnt> files = new ArrayList<FileEnt>();

        boolean multi() {
            return files.size() > 1;
        }

        static Info parse(byte[] meta) {
            Object o = Bencode.decode(meta);
            Map<String, Object> d = Bencode.map(o);
            if (d.isEmpty()) return null;
            Info i = new Info();
            i.name = Bencode.str(d.get("name.utf-8"));
            if (i.name.length() == 0) i.name = Bencode.str(d.get("name"));
            i.pieceLength = Bencode.num(d.get("piece length"));
            Object p = d.get("pieces");
            if (p instanceof byte[]) i.pieces = (byte[]) p;
            if (i.pieces == null || i.pieceLength <= 0) return null;
            i.pieceCount = i.pieces.length / 20;

            long total = 0;
            List<Object> list = Bencode.list(d.get("files"));
            if (list != null) {
                for (Object e : list) {
                    Map<String, Object> fm = Bencode.map(e);
                    String path = Bencode.str(fm.get("path.utf-8"));
                    if (path.length() == 0) {
                        List<Object> segs = Bencode.list(fm.get("path"));
                        if (segs != null) {
                            StringBuilder sb = new StringBuilder();
                            for (Object sg : segs) {
                                if (sb.length() > 0) sb.append('/');
                                sb.append(Bencode.str(sg));
                            }
                            path = sb.toString();
                        }
                    }
                    String cleanPath = cleanPath(path);
                    long len = Bencode.num(fm.get("length"));
                    if (cleanPath.length() == 0) continue;
                    FileEnt fe = new FileEnt();
                    fe.path = cleanPath;
                    fe.length = Math.max(0, len);
                    fe.start = total;
                    total += fe.length;
                    i.files.add(fe);
                }
            }
            if (i.files.isEmpty()) {
                // 单文件种子：length 写在顶层
                long single = Bencode.num(d.get("length"));
                if (single <= 0) return null;
                FileEnt fe = new FileEnt();
                fe.path = i.name.length() > 0 ? i.name : "file";
                fe.length = single;
                fe.start = 0;
                i.files.add(fe);
                total = single;
            }
            if (total <= 0) return null;
            i.length = total;
            return i;
        }

        boolean match(int index, byte[] piece) {
            try {
                byte[] want = new byte[20];
                System.arraycopy(pieces, index * 20, want, 0, 20);
                return MessageDigest.isEqual(sha1(piece), want);
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** 种子里的相对路径：逐段清洗，挡掉 .. 与绝对路径。 */
    static String cleanPath(String path) {
        if (path == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String seg : path.replace('\\', '/').split("/")) {
            String s = clean(seg);
            if (s.length() == 0 || s.equals(".")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    /** 种子名可能带 / 或 .. 之类，落盘前统一清洗。 */
    static String clean(String name) {
        if (name == null) return "";
        String n = name.replace('\\', '_').replace('/', '_').replace("..", "_").trim();
        if (n.length() == 0) return "";
        if (n.length() > 120) n = n.substring(0, 120);
        return n;
    }

    // ------------------------------------------------------------ 小工具
    static byte[] sha1(byte[] d) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(d);
        } catch (Exception e) {
            return new byte[20];
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    static void readFully(InputStream in, byte[] dst, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(dst, off, len);
            if (n <= 0) throw new IOException("连接被关闭");
            off += n;
            len -= n;
        }
    }

    static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    static long readLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xffL);
        return v;
    }

    static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 24);
        b[off + 1] = (byte) (v >> 16);
        b[off + 2] = (byte) (v >> 8);
        b[off + 3] = (byte) v;
    }

    static void writeLong(byte[] b, int off, long v) {
        for (int i = 7; i >= 0; i--) {
            b[off + i] = (byte) v;
            v >>>= 8;
        }
    }

    static int indexOf(byte[] b, byte ch) {
        for (int i = 0; i < b.length; i++) if (b[i] == ch) return i;
        return -1;
    }

    static String pct(byte[] raw) {
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append('%').append(String.format("%02X", b & 0xff));
        return sb.toString();
    }
}
