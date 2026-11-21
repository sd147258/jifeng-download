package com.jifeng.download;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 极简 bencode 解码器：字典 -> HashMap&lt;String,Object&gt;，列表 -> ArrayList，整数 -> Long，字符串 -> byte[]。 */
public final class Bencode {

    private final byte[] b;
    private int p;
    private int end;

    private Bencode(byte[] data, int off, int len) {
        b = data;
        p = off;
        end = off + len;
    }

    public static Object decode(byte[] data) {
        return decode(data, 0, data.length);
    }

    public static Object decode(byte[] data, int off, int len) {
        Bencode d = new Bencode(data, off, len);
        return d.value();
    }

    /** 从 off 开始解一段 bencode，返回这段数据结束后的下一个字节位置（用于拆分头与负载）。 */
    public static int endOf(byte[] data, int off) {
        Bencode d = new Bencode(data, off, data.length - off);
        d.value();
        return d.p;
    }

    public static String str(Object o) {
        if (o == null) return "";
        if (o instanceof byte[]) {
            byte[] raw = (byte[]) o;
            try {
                return new String(raw, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
        return String.valueOf(o);
    }

    public static long num(Object o) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof Number) return ((Number) o).longValue();
        return 0;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new HashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    private Object value() {
        if (p >= end) return null;
        byte c = b[p];
        if (c == 'i') {
            p++;
            long v = Long.parseLong(new String(sliceTo('e')));
            p++;
            return v;
        }
        if (c == 'l') {
            p++;
            List<Object> out = new ArrayList<Object>();
            while (p < end && b[p] != 'e') out.add(value());
            p++;
            return out;
        }
        if (c == 'd') {
            p++;
            Map<String, Object> out = new HashMap<String, Object>();
            while (p < end && b[p] != 'e') {
                Object k = value();
                Object v = value();
                out.put(str(k), v);
            }
            p++;
            return out;
        }
        if (c >= '0' && c <= '9') {
            int colon = indexOf(':');
            int n = Integer.parseInt(new String(b, p, colon - p));
            p = colon + 1;
            byte[] s = new byte[n];
            System.arraycopy(b, p, s, 0, Math.min(n, Math.max(0, end - p)));
            p += n;
            return s;
        }
        p++;
        return null;
    }

    private int indexOf(char ch) {
        for (int i = p; i < end; i++) if (b[i] == ch) return i;
        return end - 1;
    }

    private byte[] sliceTo(char ch) {
        int i = p;
        while (i < end && b[i] != ch) i++;
        byte[] s = new byte[i - p];
        System.arraycopy(b, p, s, 0, s.length);
        p = i;
        return s;
    }

    /** 只用于把 info dict 原样再拼回去（磁力链少数场景），保留原始字节顺序不可靠，故提供最小编码。 */
    public static byte[] encode(Object o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        enc(o, out);
        byte[] r = out.toByteArray();
        return r;
    }

    @SuppressWarnings("unchecked")
    private static void enc(Object o, ByteArrayOutputStream out) {
        if (o instanceof byte[]) {
            byte[] s = (byte[]) o;
            out.write(String.valueOf(s.length).getBytes(), 0, String.valueOf(s.length).length());
            out.write(':');
            for (byte x : s) out.write(x);
        } else if (o instanceof String) {
            enc(((String) o).getBytes(), out);
        } else if (o instanceof Long || o instanceof Integer) {
            out.write('i');
            byte[] n = String.valueOf(o).getBytes();
            for (byte x : n) out.write(x);
            out.write('e');
        } else if (o instanceof List) {
            out.write('l');
            for (Object i : (List<Object>) o) enc(i, out);
            out.write('e');
        } else if (o instanceof Map) {
            out.write('d');
            Map<String, Object> m = (Map<String, Object>) o;
            List<String> keys = new ArrayList<String>(m.keySet());
            java.util.Collections.sort(keys);
            for (String k : keys) {
                enc(k, out);
                enc(m.get(k), out);
            }
            out.write('e');
        }
    }
}
