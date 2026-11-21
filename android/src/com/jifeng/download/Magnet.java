package com.jifeng.download;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 磁力链接解析：btih（hex / base32）、btmh（v2，只识别不下载）、dn、tr、x.pe、xl、ws。 */
public final class Magnet {

    public String raw = "";
    public String name = "";
    public byte[] infoHash;
    public String hashHex = "";
    public final List<String> trackers = new ArrayList<String>();
    /** 磁力链里直接给的 peer（x.pe=ip:port，BEP9），不靠 tracker/DHT 也能连。 */
    public final List<String> peers = new ArrayList<String>();
    /** ws= 的 Web Seed（HTTP 直链），有的话可以不依赖 P2P。 */
    public final List<String> webSeeds = new ArrayList<String>();
    /** v2 磁力的 sha256（btmh，64 位十六进制）。 */
    public String v2Hex = "";
    public long exactLength = -1;

    public static boolean looksLike(String s) {
        return s != null && (s.startsWith("magnet:?") || s.startsWith("magnet:"));
    }

    public static Magnet parse(String s) {
        if (!looksLike(s)) return null;
        Magnet m = new Magnet();
        m.raw = s;
        int qm = s.indexOf('?');
        String q = qm >= 0 ? s.substring(qm + 1) : "";
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            String k = decode(kv.substring(0, i)).trim().toLowerCase();
            String v = decode(kv.substring(i + 1)).trim();
            if ("xt".equals(k)) {
                String low = v.toLowerCase();
                String h = null;
                if (low.startsWith("urn:btih:")) h = v.substring(9);
                else if (low.startsWith("btih:")) h = v.substring(5);
                if (h != null) {
                    h = h.trim();
                    while (h.endsWith("=")) h = h.substring(0, h.length() - 1);
                    byte[] b = null;
                    if (h.length() == 40) b = hexToBytes(h);
                    else if (h.length() == 32) b = base32ToBytes(h);
                    if (b != null && b.length == 20 && m.infoHash == null) m.infoHash = b;
                } else {
                    String mh = null;
                    if (low.startsWith("urn:btmh:")) mh = v.substring(9);
                    else if (low.startsWith("btmh:")) mh = v.substring(5);
                    if (mh != null) {
                        mh = mh.trim().toLowerCase();
                        // multihash：0x12 0x20 + 32 字节 sha256（共 68 位十六进制）
                        if (mh.length() == 68 && mh.startsWith("1220")) m.v2Hex = mh.substring(4);
                        else if (mh.length() == 64) m.v2Hex = mh;
                    }
                }
            } else if ("dn".equals(k)) {
                m.name = v;
            } else if ("tr".equals(k)) {
                if (v.length() > 0) m.trackers.add(v);
            } else if ("ws".equals(k)) {
                if (v.length() > 0) m.webSeeds.add(v);
            } else if ("x.pe".equals(k)) {
                String a = v.trim();
                int c = a.lastIndexOf(':');
                if (c > 0 && c + 1 < a.length()) {
                    try {
                        Integer.parseInt(a.substring(c + 1).trim());
                        m.peers.add(a);
                    } catch (Exception ignored) {
                    }
                }
            } else if ("xl".equals(k)) {
                try {
                    m.exactLength = Long.parseLong(v.trim());
                } catch (Exception ignored) {
                }
            }
        }
        if (m.infoHash != null) {
            StringBuilder sb = new StringBuilder();
            for (byte b : m.infoHash) sb.append(String.format("%02x", b));
            m.hashHex = sb.toString();
        }
        return m;
    }

    public boolean valid() {
        return infoHash != null && infoHash.length == 20;
    }

    /** 只有 v2（btmh）没有 v1（btih）：识别出来但不下载。 */
    public boolean v2Only() {
        return infoHash == null && v2Hex.length() > 0;
    }

    /** 解析失败时给用户看的说明，避免一句「不是有效磁力链」糊过去。 */
    public String errorHint() {
        if (v2Only()) {
            return "这是 BitTorrent v2（btmh）磁力链，当前版本只支持 v1（btih）："
                    + "换一条同时带 btih 的混合链，或换 v1 资源。";
        }
        if (raw.indexOf("xt=") < 0) {
            return "这条 magnet 里没有 xt= 参数，确认链接复制完整了吗？";
        }
        return "磁力链里没找到 btih 信息哈希（xt=urn:btih: 40 位哈希），请确认复制完整。";
    }

    public String trackersFromMagnetOnly() {
        StringBuilder sb = new StringBuilder();
        for (String t : trackers) sb.append(t).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------ 编码工具
    static byte[] hexToBytes(String h) {
        if (h == null || h.length() % 2 != 0) return null;
        byte[] out = new byte[h.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    /** Base32（RFC4648，无填充）-> 字节，磁力链里 32 位长度。 */
    static byte[] base32ToBytes(String s) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        s = s.toUpperCase();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = chars.indexOf(s.charAt(i));
            if (v < 0) continue;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
            }
        }
        byte[] r = out.toByteArray();
        if (r.length > 20) {
            byte[] cut = new byte[20];
            System.arraycopy(r, 0, cut, 0, 20);
            return cut;
        }
        return r;
    }

    /** 百分号解码（磁力链的 dn 常含 %E4%B8%AD 这类中文）。 */
    static String decode(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                } catch (Exception e) {
                    out.write((byte) c);
                }
            } else if (c == '+') {
                out.write(' ');
            } else {
                out.write((byte) c);
            }
        }
        try {
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
