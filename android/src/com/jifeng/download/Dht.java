package com.jifeng.download;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 极简 Mainline DHT（BEP5）客户端：按 info_hash 找 peer。
 *
 * 做法：随机 20 字节节点 id，从引导节点开始按「XOR 距离」从近到远迭代 get_peers，
 * 收 values（peer 的 ip:port）和 nodes（26 字节一条：20 字节 id + 4 字节 ip + 2 字节 port），
 * 把新节点加入候选、始终问离目标最近的 8 个，直到拿到 peer 或用完预算。
 *
 * 不做的：不做 announce（不往外宣告自己）、不做公网可访问的 DHT 节点、不做 BEP44。
 * 无账号、无门槛；出网 UDP 被封时降级为「没找到源」。
 */
public final class Dht {

    /** 自检 / 自定义引导节点（host:port），默认空。 */
    public static final List<String> EXTRA = new ArrayList<String>();
    public static boolean DEBUG = false;

    private static final String[] DEFAULT_BOOT = {
            "router.bittorrent.com:6881",
            "router.utorrent.com:6881",
            "dht.transmissionbt.com:6881",
            "dht.libtorrent.org:25401",
            "dht.aelitis.com:6881",
            "router.bitcomet.com:6881",
    };

    /** 同一个 info_hash 10 分钟内复用的 peer 列表：重复点「继续」不用再走一轮 DHT。 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final java.util.Map<String, Object[]> CACHE =
            new java.util.LinkedHashMap<String, Object[]>();

    private static final int MAX_QUERIES = 128;
    private static final int MAX_CAND = 400;
    private static final int MAX_PEERS = 200;
    private static final int BATCH = 16;
    private static final long ROUND_MS = 2200;

    /** 上一次找到的节点地址：下次直接拿它们当起点，手机网络下比只靠引导节点快得多。 */
    private static final List<String> NODE_POOL = new ArrayList<String>();
    private static final int POOL_MAX = 160;
    /** 最近一次找源的统计，失败时拼进错误提示，方便判断是「没人做种」还是「UDP 出不去」。 */
    public static volatile String lastStats = "";
    /** 最近一次找源收到多少个节点回应：>0 说明 UDP 出站是通的。 */
    public static volatile int lastResp = 0;

    private Dht() {
    }

    public static List<String> bootstrapNodes() {
        List<String> out = new ArrayList<String>();
        synchronized (EXTRA) {
            out.addAll(EXTRA);
        }
        for (String b : DEFAULT_BOOT) out.add(b);
        return out;
    }

    private static class Cand {
        String addr;
        byte[] id;   // 20 字节节点 id，引导节点先不知道 -> null

        Cand(String a, byte[] i) {
            addr = a;
            id = i;
        }
    }

    private static int cmpDist(Cand a, Cand b, byte[] target) {
        byte[] da = dist(a, target);
        byte[] db = dist(b, target);
        for (int i = 0; i < 20; i++) {
            int x = da[i] & 0xFF;
            int y = db[i] & 0xFF;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static byte[] dist(Cand c, byte[] target) {
        byte[] out = new byte[20];
        if (c.id == null) {
            for (int i = 0; i < 20; i++) out[i] = (byte) 0xFF;
            return out;
        }
        for (int i = 0; i < 20; i++) out[i] = (byte) (c.id[i] ^ target[i]);
        return out;
    }

    /** 按 40 位十六进制 info_hash 找 peer，budgetMs 是总预算，返回 "ip:port" 列表（可能为空）。 */
    /** 测试用：清掉 peer 缓存。 */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> findPeers(String hashHex, long budgetMs) {
        List<String> peers = new ArrayList<String>();
        byte[] ih = hex20(hashHex);
        if (ih == null) return peers;
        String key = hashHex.toLowerCase();
        synchronized (CACHE) {
            Object[] hit = CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_TTL_MS) {
                return new ArrayList<String>((List<String>) hit[1]);
            }
        }
        byte[] selfId = random20();
        DatagramSocket sock = null;
        Set<String> asked = new HashSet<String>();
        Map<String, byte[]> ids = new HashMap<String, byte[]>();
        List<Cand> cands = new ArrayList<Cand>();
        synchronized (NODE_POOL) {
            for (String a : NODE_POOL) cands.add(new Cand(a, null));
        }
        for (String b : bootstrapNodes()) {
            cands.add(new Cand(b, null));
        }
        int resp = 0;
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout((int) ROUND_MS);
            long deadline = System.currentTimeMillis() + Math.max(2000, budgetMs);
            int txSeq = 0;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline
                    && asked.size() < MAX_QUERIES && peers.size() < MAX_PEERS) {
                Collections.sort(cands, new Comparator<Cand>() {
                    public int compare(Cand a, Cand b) {
                        return cmpDist(a, b, ih);
                    }
                });
                List<Cand> batch = new ArrayList<Cand>();
                for (Cand c : cands) {
                    if (batch.size() >= BATCH) break;
                    if (asked.contains(c.addr)) continue;
                    batch.add(c);
                }
                if (batch.isEmpty()) break;
                for (Cand c : batch) asked.add(c.addr);
                for (Cand c : batch) {
                    String tid = "jf" + (txSeq++);
                    send(sock, c.addr, query(tid, selfId, ih));
                }
                long roundEnd = Math.min(deadline, System.currentTimeMillis() + ROUND_MS);
                while (System.currentTimeMillis() < roundEnd) {
                    DatagramPacket pack = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(pack);
                    } catch (java.net.SocketTimeoutException te) {
                        if (System.currentTimeMillis() >= roundEnd) break;
                        continue;
                    } catch (Exception e) {
                        break;
                    }
                    String from = pack.getAddress().getHostAddress() + ":" + pack.getPort();
                    try {
                        Object o = Bencode.decode(pack.getData(), pack.getOffset(), pack.getLength());
                        Map<String, Object> root = Bencode.map(o);
                        if (!"r".equals(Bencode.str(root.get("y")))) continue;
                        resp++;
                        Map<String, Object> r = Bencode.map(root.get("r"));
                        if (DEBUG) System.out.println("resp from " + from + " keys=" + root.keySet()
                                + " values=" + Bencode.list(r.get("values")).size()
                                + " nodes=" + ((r.get("nodes") instanceof byte[])
                                ? ((byte[]) r.get("nodes")).length : -1));
                        for (Object v : Bencode.list(r.get("values"))) {
                            if (!(v instanceof byte[])) continue;
                            String p = addrOf((byte[]) v);
                            if (p != null && !peers.contains(p)) {
                                peers.add(p);
                                if (peers.size() >= MAX_PEERS) break;
                            }
                        }
                        Object nodes = r.get("nodes");
                        if (nodes instanceof byte[]) {
                            byte[] nb = (byte[]) nodes;
                            for (int i = 0; i + 26 <= nb.length; i += 26) {
                                byte[] nid = new byte[20];
                                System.arraycopy(nb, i, nid, 0, 20);
                                String a = compactAddr(nb, i + 20);
                                if (a == null || a.equals(from)) continue;
                                if (ids.containsKey(a)) continue;
                                if (cands.size() >= MAX_CAND) continue;
                                ids.put(a, nid);
                                cands.add(new Cand(a, nid));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (!peers.isEmpty()) break;
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (!peers.isEmpty()) {
            synchronized (CACHE) {
                if (CACHE.size() > 24) CACHE.clear();
                CACHE.put(key, new Object[]{System.currentTimeMillis(), new ArrayList<String>(peers)});
            }
        }
        synchronized (NODE_POOL) {
            NODE_POOL.clear();
            for (Cand c : cands) {
                if (NODE_POOL.size() >= POOL_MAX) break;
                if (!NODE_POOL.contains(c.addr)) NODE_POOL.add(c.addr);
            }
        }
        lastStats = "询问 " + asked.size() + " 个节点，收到 " + resp + " 个回应，"
                + "学到 " + cands.size() + " 个节点，找到 " + peers.size() + " 个 peer";
        lastResp = resp;
        return peers;
    }

    // ------------------------------------------------------------------ 工具
    private static byte[] query(String tid, byte[] selfId, byte[] ih) {
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("id", selfId);
        a.put("info_hash", ih);
        Map<String, Object> q = new LinkedHashMap<String, Object>();
        q.put("t", tid.getBytes());
        q.put("y", "q");
        q.put("q", "get_peers");
        q.put("a", a);
        return Bencode.encode(q);
    }

    private static void send(DatagramSocket sock, String addr, byte[] payload) {
        try {
            int colon = addr.lastIndexOf(':');
            if (colon <= 0) return;
            String host = addr.substring(0, colon);
            int port = Integer.parseInt(addr.substring(colon + 1));
            sock.send(new DatagramPacket(payload, payload.length,
                    new InetSocketAddress(host, port)));
        } catch (Exception ignored) {
        }
    }

    private static String addrOf(byte[] v) {
        if (v == null || v.length < 6) return null;
        return ipv4(v) + ":" + (((v[4] & 0xFF) << 8) | (v[5] & 0xFF));
    }

    private static String compactAddr(byte[] nb, int at) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append('.');
            sb.append(nb[at + i] & 0xFF);
        }
        int port = ((nb[at + 4] & 0xFF) << 8) | (nb[at + 5] & 0xFF);
        if (port <= 0) return null;
        return sb.toString() + ":" + port;
    }

    private static String ipv4(byte[] v) {
        return (v[0] & 0xFF) + "." + (v[1] & 0xFF) + "." + (v[2] & 0xFF) + "." + (v[3] & 0xFF);
    }

    private static byte[] hex20(String hex) {
        if (hex == null || hex.length() != 40) return null;
        byte[] out = new byte[20];
        try {
            for (int i = 0; i < 20; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    private static byte[] random20() {
        byte[] b = new byte[20];
        new Random().nextBytes(b);
        return b;
    }
}
