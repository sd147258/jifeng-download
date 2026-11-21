package com.jifeng.download;

import android.util.Base64;

/** 迅雷专用链识别：thunder://、magnet:?xt=urn:btih、ed2k。 */
public class Thunder {

    /** 把迅雷专用链还原成真实 http(s) 地址；不是迅雷链返回 null。 */
    public static String decode(String text) {
        if (text == null) return null;
        String s = text.trim();
        int at = s.toLowerCase().indexOf("thunder://");
        if (at < 0) return null;
        String b64 = s.substring(at + "thunder://".length()).trim();
        int end = 0;
        while (end < b64.length() && isB64(b64.charAt(end))) end++;
        b64 = b64.substring(0, end);
        if (b64.length() == 0) return null;
        try {
            byte[] raw = Base64.decode(pad(b64), Base64.DEFAULT);
            String u = new String(raw, "UTF-8").trim();
            // 迅雷标准封装是 AA + 真实地址 + ZZ
            if (u.startsWith("AA") && u.endsWith("ZZ") && u.length() > 4) {
                u = u.substring(2, u.length() - 2);
            }
            if (u.startsWith("http://") || u.startsWith("https://")) return u;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 磁力链：本 App 不做 BT 下载，给出可执行的替代建议。 */
    public static boolean isMagnet(String text) {
        return text != null && text.toLowerCase().contains("magnet:?xt=");
    }

    public static boolean isEd2k(String text) {
        return text != null && text.toLowerCase().startsWith("ed2k://");
    }

    private static boolean isB64(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '+' || c == '/' || c == '=' || c == '-';
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s.replace('-', '+').replace('_', '/'));
        while (sb.length() % 4 != 0) sb.append('=');
        return sb.toString();
    }
}
