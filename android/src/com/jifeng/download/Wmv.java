package com.jifeng.download;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * WMV/WMA（微软 ASF）软解：把系统解不了的 ASF 用内置 ffmpeg 转成站内播放器能播的格式
 * （有视频 → MP4：mpeg4 + AAC；纯音频 → M4A：AAC），再交给站内播放器。
 * ffmpeg 以 lib/<abi>/libffmpeg.so 打进 APK，安装后解到 nativeLibraryDir 直接 exec。
 */
public class Wmv {

    public static final String CONV_DIR = Environment.DIRECTORY_DOWNLOADS + "/疾风下载/转换";
    private static final String PREF = "wmvconv";

    public interface Listener {
        void onProgress(String note);

        void onDone(Uri uri, String shownName);

        void onError(String msg);
    }

    public static boolean needsConvert(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".wmv") || n.endsWith(".wma") || n.endsWith(".asf");
    }

    private static String binPath(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libffmpeg.so";
    }

    public static boolean available(Context c) {
        return new File(binPath(c)).exists();
    }

    /** 后台转码；srcUri 可以是 content:// 也可以是 file://。 */
    public static void convert(final Context ctx, final String srcUri, final String displayName,
                               final Listener l) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final Listener cb = new Listener() {
            public void onProgress(final String note) {
                ui.post(new Runnable() {
                    public void run() {
                        l.onProgress(note);
                    }
                });
            }

            public void onDone(final Uri uri, final String shown) {
                ui.post(new Runnable() {
                    public void run() {
                        l.onDone(uri, shown);
                    }
                });
            }

            public void onError(final String msg) {
                ui.post(new Runnable() {
                    public void run() {
                        l.onError(msg);
                    }
                });
            }
        };
        new Thread(new Runnable() {
            public void run() {
                File src = null;
                try {
                    if (!available(ctx)) {
                        cb.onError("安装包内没有 ffmpeg 软解库");
                        return;
                    }
                    File dir = new File(ctx.getCacheDir(), "wmv");
                    if (!dir.exists() && !dir.mkdirs()) {
                        cb.onError("缓存目录不可写");
                        return;
                    }
                    String base = stripExt(displayName);
                    String ext = extOf(displayName);
                    // 缓存文件名带上源扩展名，避免 real.wmv 与 real.wma 撞同一个产物
                    File videoOut = new File(dir, safe(base) + "_" + ext + ".mp4");
                    File audioOut = new File(dir, safe(base) + "_" + ext + ".m4a");

                    // 已经转好过：直接复用（同一文件不重复转）
                    Uri cached = cachedUri(ctx, videoOut, base + ".mp4");
                    if (cached == null) cached = cachedUri(ctx, audioOut, base + ".m4a");
                    if (cached != null) {
                        cb.onDone(cached, base + (videoOut.exists() ? ".mp4" : ".m4a"));
                        return;
                    }

                    src = new File(dir, "src_" + safe(base) + ".asf");
                    cb.onProgress("正在复制源文件…");
                    copyIn(ctx, Uri.parse(srcUri), src, displayName);

                    cb.onProgress("正在软解转码…（视频越长越慢）");
                    boolean ok = false;
                    if (hasVideo(ctx, src)) {
                        ok = runFfmpeg(ctx, src, videoOut, true, cb);
                    }
                    if (ok && videoOut.length() > 0) {
                        cb.onProgress("正在保存到 下载/疾风下载/转换 …");
                        Uri pub = publish(ctx, videoOut, base + ".mp4", "video/mp4");
                        if (pub == null) {
                            cb.onError("转换好了，但保存到下载目录失败");
                            return;
                        }
                        mark(ctx, videoOut, pub);
                        recycle(ctx, dir, videoOut);
                        cb.onDone(pub, base + ".mp4");
                        return;
                    }

                    // 视频流转不出来（纯 WMA、或视频流损坏）→ 只转音频
                    videoOut.delete();
                    cb.onProgress("没有可用的视频流，改转音频…");
                    ok = runFfmpeg(ctx, src, audioOut, false, cb);
                    if (!ok || audioOut.length() == 0) {
                        cb.onError("转码失败：这个文件的编码 ffmpeg 也解不了");
                        return;
                    }
                    cb.onProgress("正在保存到 下载/疾风下载/转换 …");
                    Uri pub = publish(ctx, audioOut, base + ".m4a", "audio/mp4");
                    if (pub == null) {
                        cb.onError("转换好了，但保存到下载目录失败");
                        return;
                    }
                    mark(ctx, audioOut, pub);
                    recycle(ctx, dir, audioOut);
                    cb.onDone(pub, base + ".m4a");
                } catch (Throwable e) {
                    Log.e("JfWmv", "convert failed: " + friendly(e));
                    cb.onError(friendly(e));
                } finally {
                    if (src != null) src.delete();
                }
            }
        }, "wmv-conv").start();
    }

    private static Uri cachedUri(Context ctx, File out, String shown) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String v = sp.getString("uri:" + out.getName(), "");
        if (out.exists() && out.length() > 0 && v.length() > 0) {
            Uri u = Uri.parse(v);
            if (readable(ctx, u)) return u;
        }
        return null;
    }

    private static void mark(Context ctx, File out, Uri pub) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString("uri:" + out.getName(), pub.toString()).apply();
    }

    /** 转完把缓存再让给下一个文件：总占用超过 256 MB 就清空（同名产物下次打开时再转）。 */
    private static void recycle(Context ctx, File dir, File keep) {
        try {
            long total = 0;
            File[] fs = dir.listFiles();
            if (fs == null) return;
            for (File f : fs) total += f.length();
            if (total <= 256L * 1024 * 1024) return;
            for (File f : fs) {
                if (!f.equals(keep)) f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 先探一次：有没有视频流。纯音频（WMA）直接走 -vn 分支，不用先白跑一趟视频转码。 */
    private static boolean hasVideo(Context ctx, File in) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binPath(ctx), "-hide_banner", "-i", in.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", ctx.getApplicationInfo().nativeLibraryDir);
            pb.environment().put("HOME", ctx.getCacheDir().getAbsolutePath());
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            boolean video = false;
            while ((line = r.readLine()) != null) {
                if (line.contains("Stream #") && line.contains("Video:")) video = true;
            }
            r.close();
            p.waitFor();
            return video;
        } catch (Throwable e) {
            Log.e("JfWmv", "probe failed: " + friendly(e));
            return true; // 探不出来就按有视频试，失败了还有音频兜底
        }
    }

    private static boolean runFfmpeg(Context ctx, File in, File out, boolean withVideo, Listener l) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(binPath(ctx));
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-i");
        cmd.add(in.getAbsolutePath());
        if (withVideo) {
            cmd.add("-c:v");
            cmd.add("mpeg4");
            cmd.add("-q:v");
            cmd.add("3");
        } else {
            cmd.add("-vn");
        }
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(out.getAbsolutePath());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", ctx.getApplicationInfo().nativeLibraryDir);
            pb.environment().put("HOME", ctx.getCacheDir().getAbsolutePath());
            Process p = pb.start();
            StringBuilder log = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            long t0 = System.currentTimeMillis();
            int lastNote = 0;
            while ((line = r.readLine()) != null) {
                if (log.length() < 4000) log.append(line).append('\n');
                int sec = (int) ((System.currentTimeMillis() - t0) / 1000);
                if (sec >= lastNote + 3) {
                    lastNote = sec;
                    l.onProgress("正在软解转码… 已用 " + sec + " 秒");
                }
            }
            r.close();
            int code = p.waitFor();
            if (code != 0 && log.length() > 0) {
                DownloadEngine.lastFfmpegLog = log.toString();
            }
            if (code != 0) {
                Log.e("JfWmv", "ffmpeg exit=" + code + " cmd=" + cmd + " out=" + log);
            }
            return code == 0;
        } catch (Throwable e) {
            DownloadEngine.lastFfmpegLog = friendly(e);
            Log.e("JfWmv", "ffmpeg exec failed: " + friendly(e) + " cmd=" + cmd);
            return false;
        }
    }

    private static void copyIn(Context ctx, Uri uri, File dst, String displayName) throws Exception {
        InputStream in = null;
        OutputStream out = null;
        try {
            ContentResolver r = ctx.getContentResolver();
            try {
                in = r.openInputStream(uri);
            } catch (Exception e) {
                // MediaStore 行被清掉了（用户在文件管理器里删过）：退回按文件名找本地文件
                Log.e("JfWmv", "openInputStream failed: " + friendly(e) + " -> " + uri);
                in = null;
            }
            if (in == null) {
                File local = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "疾风下载/" + displayName);
                if (!local.exists() || local.length() == 0) {
                    throw new java.io.FileNotFoundException("找不到源文件：" + displayName);
                }
                in = new FileInputStream(local);
            }
            out = new FileOutputStream(dst);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static Uri publish(Context ctx, File src, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, CONV_DIR);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            OutputStream out = null;
            InputStream in = null;
            try {
                out = r.openOutputStream(uri);
                in = new FileInputStream(src);
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Exception ignored) {
                }
                try {
                    if (out != null) out.close();
                } catch (Exception ignored) {
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            r.update(uri, done, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "疾风下载/转换");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File dst = new File(dir, name);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
        return Uri.fromFile(dst);
    }

    private static boolean readable(Context ctx, Uri u) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(u);
            return in != null;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String stripExt(String n) {
        if (n == null) return "转换";
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String extOf(String n) {
        if (n == null) return "asf";
        int dot = n.lastIndexOf('.');
        if (dot <= 0 || dot + 1 >= n.length()) return "asf";
        String e = n.substring(dot + 1).toLowerCase();
        return e.length() > 6 ? "asf" : safe(e);
    }

    private static String safe(String n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == ' ') sb.append(ch);
            else sb.append('_');
        }
        String s = sb.toString().trim();
        return s.length() == 0 ? "转换" : (s.length() > 60 ? s.substring(0, 60) : s);
    }

    static String friendly(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        if (e instanceof java.io.IOException && m != null && m.contains("ENOSPC")) return "存储空间不足";
        return e.getClass().getSimpleName() + (m == null ? "" : (": " + m));
    }
}
