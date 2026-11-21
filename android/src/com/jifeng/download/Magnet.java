package com.jifeng.download;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 磁力链接解析：btih（hex / base32）、显示名 dn、tracker 列表 tr、精确长度 xl。 */
public final class Magnet {

    public String raw = "";
    public String name = "";
    public byte[] infoHash;
    public String hashHex = "";
    public final List<String> trackers = new ArrayList<String>();
    public long exactLength = -1;

    public static boolean looksLike(String s) {
        return s != null && (s.startsWith("magnet:?") || s.startsWith("magnet:"));
    }

    public static Magnet parse(String s) {
        if (!looksLike(s)) return null;
        Magnet m = new Magnet();
        m.raw = s;
        String q = s.substring(s.indexOf('?') + 1);
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            String k = decode(kv.substring(0, i));
            String v = decode(kv.substring(i + 1));
            if ("xt".equals(k)) {
                String low = v.toLowerCase();
                if (low.startsWith("urn:btih:")) {
                    String h = v.substring(9);
                    if (h.length() == 40) {
                        m.infoHash = hexToBytes(h);
                    } else if (h.length() == 32) {
                        m.infoHash = base32ToBytes(h);
                    }
                }
            } else if ("dn".equals(k)) {
                m.name = v;
            } else if ("tr".equals(k)) {
                if (v.length() > 0) m.trackers.add(v);
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

    public String trackersFromMagnetOnly() {
        StringBuilder sb = new StringBuilder();
        for (String t : trackers) sb.append(t).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------ 编码工具
    static byte[] hexToBytes(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
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
