package com.jifeng.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.database.Cursor;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 前台服务：最多 2 个任务并行，完成后把文件发布到系统「下载」目录。 */
public class DownloadService extends Service {

    public static final String ACTION_ADD = "com.jifeng.ADD";
    public static final String ACTION_PAUSE = "com.jifeng.PAUSE";
    public static final String ACTION_RESUME = "com.jifeng.RESUME";
    public static final String ACTION_CANCEL = "com.jifeng.CANCEL";
    public static final String ACTION_SYNC = "com.jifeng.SYNC";
    public static final String ACTION_DELETE = "com.jifeng.DELETE";
    public static final String ACTION_BATCH_DELETE = "com.jifeng.BATCH_DELETE";
    public static final String ACTION_BATCH_PAUSE = "com.jifeng.BATCH_PAUSE";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_IDS = "ids";
    public static final String EXTRA_FILE = "with_file";
    public static final String EXTRA_REFERER = "referer";
    public static final String EXTRA_COOKIE = "cookie";

    /** 删除失败的中文原因，供界面在刷新时取走提示。 */
    private static final ArrayList<String> deleteFailures = new ArrayList<>();

    public static List<String> drainDeleteFailures() {
        synchronized (deleteFailures) {
            ArrayList<String> out = new ArrayList<>(deleteFailures);
            deleteFailures.clear();
            return out;
        }
    }

    private static final String CHANNEL = "jifeng_downloads";
    private static final String CHANNEL_DONE = "jifeng_done";
    private static final int NOTIF_ID = 1001;
    private static final int MAX_PARALLEL = 2;
    private static final Object LOCK = new Object();
    private static DownloadService instance;
    private int lastStartId = -1;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    /** 因为断网被自动暂停的任务 id，网回来后自动续传。 */
    private final ArrayList<String> netPaused = new ArrayList<>();

    private DownloadEngine engine;
    private final ArrayList<DownloadEngine.Task> tasks = new ArrayList<>();
    private final ArrayList<DownloadEngine.Task> running = new ArrayList<>();
    private Handler main;
    private long lastNotify;

    public static List<DownloadEngine.Task> snapshot() {
        DownloadService s = instance;
        if (s == null) return new ArrayList<>();
        synchronized (LOCK) {
            return new ArrayList<>(s.tasks);
        }
    }

    public static boolean alive() {
        return instance != null;
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onCreate() {
        try {
            DownloadEngine.speedLimit =
                    getSharedPreferences("cfg", MODE_PRIVATE).getLong("speedKb", 0) * 1024L;
        } catch (Exception ignored) {
        }
        super.onCreate();
        instance = this;
        main = new Handler(Looper.getMainLooper());
        File dir = getExternalFilesDir("downloads");
        if (dir == null) dir = new File(getFilesDir(), "downloads");
        engine = new DownloadEngine(dir, new DownloadEngine.Callback() {
            public void onUpdate(DownloadEngine.Task t) {
                long now = System.currentTimeMillis();
                if (now - lastNotify > 700 || "done".equals(t.status) || "error".equals(t.status)) {
                    lastNotify = now;
                    updateNotification(t);
                }
            }

            public void onFinished(final DownloadEngine.Task t) {
                main.post(new Runnable() {
                    public void run() {
                        handleFinished(t);
                    }
                });
            }
        });
        List<DownloadEngine.Task> loaded = engine.loadAll();
        synchronized (LOCK) {
            for (DownloadEngine.Task t : loaded) {
                tasks.add(t);
                // 被强杀中断的任务：落盘状态仍为 running，重启后自动续传；
                // 用户主动暂停的任务状态为 paused，保持暂停等用户点继续。
                if ("running".equals(t.status)) {
                    engine.resume(t);
                    running.add(t);
                }
            }
        }
        if (!tasks.isEmpty()) pump();
        createChannel();
        setupNetworkWatch();
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent == null) {
            pump();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_ADD.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            if (url != null && url.length() > 0) {
                String referer = intent.getStringExtra(EXTRA_REFERER);
                String cookie = intent.getStringExtra(EXTRA_COOKIE);
                addTask(url, referer == null ? "" : referer, cookie == null ? "" : cookie);
            }
        } else if (ACTION_PAUSE.equals(action)) {
            DownloadEngine.Task t = find(intent.getStringExtra(EXTRA_ID));
            if (t != null) {
                engine.pause(t);
                updateNotification(t);
                pump();
            }
        } else if (ACTION_RESUME.equals(action)) {
            DownloadEngine.Task t = find(intent.getStringExtra(EXTRA_ID));
            if (t != null) {
                t.error = "";
                engine.resume(t);
                updateNotification(t);
            }
        } else if (ACTION_CANCEL.equals(action)) {
            DownloadEngine.Task t = find(intent.getStringExtra(EXTRA_ID));
            if (t != null) {
                engine.cancel(t);
                synchronized (LOCK) {
                    tasks.remove(t);
                    running.remove(t);
                }
                pump();
            }
        } else if (ACTION_DELETE.equals(action)) {
            DownloadEngine.Task t = find(intent.getStringExtra(EXTRA_ID));
            if (t != null) {
                boolean withFile = intent.getBooleanExtra(EXTRA_FILE, false);
                String reason = deleteTask(t, withFile);
                if (reason != null) {
                    synchronized (deleteFailures) {
                        deleteFailures.add(reason);
                    }
                }
                pump();
            }
        } else if (ACTION_BATCH_PAUSE.equals(action)) {
            ArrayList<String> ids = intent.getStringArrayListExtra(EXTRA_IDS);
            if (ids != null) {
                for (String id : ids) {
                    DownloadEngine.Task t = find(id);
                    if (t != null) {
                        engine.pause(t);
                        updateNotification(t);
                    }
                }
                pump();
            }
        } else if (ACTION_BATCH_DELETE.equals(action)) {
            ArrayList<String> ids = intent.getStringArrayListExtra(EXTRA_IDS);
            boolean withFile = intent.getBooleanExtra(EXTRA_FILE, true);
            if (ids != null) {
                for (String id : ids) {
                    DownloadEngine.Task t = find(id);
                    if (t != null) {
                        String reason = deleteTask(t, withFile);
                        if (reason != null) {
                            synchronized (deleteFailures) {
                                deleteFailures.add(reason);
                            }
                        }
                    }
                }
                pump();
            }
        }
        return START_NOT_STICKY;
    }

    /**
     * 删除一个任务：停引擎、清发布文件（按 URI 删并回查确认）、清私有临时/状态文件、
     * 从列表移除。返回非空中文原因表示有文件没删干净；全部清理成功返回 null。
     */
    private String deleteTask(DownloadEngine.Task t, boolean withFile) {
        engine.cancel(t);
        String reason = null;
        if (withFile) {
            reason = removeSavedFile(t);
        }
        try {
            t.file().delete();
        } catch (Exception ignored) {
        }
        try {
            t.stateFile().delete();
        } catch (Exception ignored) {
        }
        cleanupTempSiblings(t);
        synchronized (LOCK) {
            tasks.remove(t);
            running.remove(t);
        }
        return reason;
    }

    /** 优先按 MediaStore URI 删，删完回查确认真的没了；失败再按路径删。 */
    private String removeSavedFile(DownloadEngine.Task t) {
        String name = t.filename == null ? "" : t.filename;
        if (t.publicUri != null && t.publicUri.startsWith("folder:")) {
            return removeSavedFolder(name);
        }
        if (t.publicUri != null && t.publicUri.length() > 0) {
            Uri uri = Uri.parse(t.publicUri);
            try {
                getContentResolver().delete(uri, null, null);
                if (mediaStorePresent(uri)) {
                    return "系统下载目录里的「" + name + "」未能删除（文件可能被占用），可到文件管理手动删除。";
                }
                return null;
            } catch (Exception e) {
                return "删除「" + name + "」失败：" + DownloadEngine.friendly(e);
            }
        }
        try {
            java.io.File f = new java.io.File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "疾风下载/" + name);
            if (f.exists() && !f.delete() && f.exists()) {
                return "下载目录里的「" + name + "」未能删除（文件可能被占用），可到文件管理手动删除。";
            }
        } catch (Exception e) {
            return "删除「" + name + "」失败：" + DownloadEngine.friendly(e);
        }
        return null;
    }

    /** 删掉一个磁力种子目录：MediaStore 里按相对目录前缀删，老系统直接删文件。 */
    private String removeSavedFolder(String name) {
        String rel = Environment.DIRECTORY_DOWNLOADS + "/疾风下载/" + name;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{rel + "%"});
                return null;
            } catch (Exception e) {
                return "删除「" + name + "」目录失败：" + DownloadEngine.friendly(e);
            }
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "疾风下载/" + name);
            deleteRecursive(dir);
            return dir.exists() ? ("下载目录里的「" + name + "」没能删干净，可到文件管理手动删除。") : null;
        } catch (Exception e) {
            return "删除「" + name + "」目录失败：" + DownloadEngine.friendly(e);
        }
    }

    /** 回查 MediaStore：该 URI 是否仍有记录。查询异常按「仍在」处理，避免误报已删。 */
    private boolean mediaStorePresent(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns._ID}, null, null, null);
            return c != null && c.getCount() > 0;
        } catch (Exception e) {
            return true;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 清掉同一任务可能残留的分片临时件、转封装中间件与状态文件。 */
    private void cleanupTempSiblings(DownloadEngine.Task t) {
        try {
            File dir = t.file().getParentFile();
            if (dir == null || !dir.exists()) return;
            String stem = t.filename;
            int dot = stem.lastIndexOf('.');
            if (dot > 0) stem = stem.substring(0, dot);
            File[] kids = dir.listFiles();
            if (kids == null) return;
            for (File f : kids) {
                String n = f.getName();
                if (!n.startsWith(stem)) continue;
                if (n.endsWith(".jfstate") || n.endsWith(".part") || n.endsWith(".norm.mp4")
                        || n.endsWith(".ts")) {
                    f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private DownloadEngine.Task find(String id) {
        if (id == null) return null;
        synchronized (LOCK) {
            for (DownloadEngine.Task t : tasks) {
                if (id.equals(t.id)) return t;
            }
        }
        return null;
    }

    private void addTask(String url, String referer, String cookie) {
        String seedName = null;
        if (Magnet.looksLike(url)) {
            Magnet m = Magnet.parse(url);
            if (m != null && m.name.length() > 0) seedName = m.name;
        }
        DownloadEngine.Task t = engine.add(url, seedName, referer, cookie);
        if (Magnet.looksLike(url)) {
            t.kind = "bt";
            t.filename = seedName == null ? "" : BtDownloader.clean(seedName);
            t.segmentsCount = 1;
        }
        int cap = getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("segments", 8);
        t.segmentsCount = "bt".equals(t.kind) ? 1 : Math.max(1, Math.min(16, cap));
        synchronized (LOCK) {
            tasks.add(0, t);
        }
        pump();
    }

    private void pump() {
        synchronized (LOCK) {
            for (int i = running.size() - 1; i >= 0; i--) {
                DownloadEngine.Task rt = running.get(i);
                if (!"running".equals(rt.status) && !"preparing".equals(rt.status)) {
                    running.remove(i);
                }
            }
            for (DownloadEngine.Task t : tasks) {
                if (running.size() >= MAX_PARALLEL) break;
                if ("pending".equals(t.status)) {
                    running.add(t);
                    startForeground(NOTIF_ID, buildNotification(t, true));
                    engine.start(t);
                }
            }
        }
        synchronized (LOCK) {
            if (running.isEmpty()) {
                try {
                    stopForeground(true);
                } catch (Exception ignored) {
                }
                if (tasks.isEmpty() && lastStartId >= 0) stopSelfResult(lastStartId);
            }
        }
    }

    private void handleFinished(DownloadEngine.Task t) {
        synchronized (LOCK) {
            running.remove(t);
        }
        if ("done".equals(t.status)) {
            publishAsync(t);
            return;
        }
        if ("error".equals(t.status)) {
            notifyResult(t, false);
        }
        if ("canceled".equals(t.status)) {
            synchronized (LOCK) {
                tasks.remove(t);
            }
        }
        pump();
    }

    private void publishAsync(final DownloadEngine.Task t) {
        new Thread(new Runnable() {
            public void run() {
                String uri = "";
                try {
                    uri = publish(t);
                } catch (Exception e) {
                    t.error = "保存到下载目录失败：" + DownloadEngine.friendly(e);
                }
                final String u = uri;
                main.post(new Runnable() {
                    public void run() {
                        t.publicUri = u;
                        if (u.length() > 0) {
                            // 成品已进了系统下载目录（临时件由 publish 自己删干净），
                            // 这里保留一条「已完成」状态，重启 App 后历史记录还在。
                            t.finishedAt = System.currentTimeMillis();
                            try {
                                engine.save(t);
                            } catch (Exception ignored) {
                            }
                            try {
                                engine.pruneDone(60);
                            } catch (Exception ignored) {
                            }
                        }
                        if (u.length() > 0 && getSharedPreferences("cfg", MODE_PRIVATE)
                                .getBoolean("soundOn", true)) {
                            notifyResult(t, true);
                            try {
                                android.os.Vibrator v = (android.os.Vibrator)
                                        getSystemService(Context.VIBRATOR_SERVICE);
                                if (v != null) v.vibrate(180);
                            } catch (Exception ignored) {
                            }
                        }
                        updateNotification(t);
                        pump();
                    }
                });
            }
        }, "publish-" + t.id).start();
    }

    // ------------------------------------------------------------ 发布到「下载」
    /** 读 MediaStore 里这条记录的实际文件名。 */
    /** 磁力多文件种子：整个目录按原结构发布到 下载/疾风下载/<种子名>/。 */
    private String publishFolder(DownloadEngine.Task t, File src) throws IOException {
        ArrayList<File> all = new ArrayList<File>();
        collectFiles(src, all);
        if (all.isEmpty()) throw new IOException("种子目录里没有文件");
        String base = Environment.DIRECTORY_DOWNLOADS + "/疾风下载/" + t.filename;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = getContentResolver();
            for (File f : all) {
                String relPath = relativeOf(src, f);
                int cut = relPath.lastIndexOf('/');
                String dirPart = cut > 0 ? relPath.substring(0, cut) : "";
                String name = cut > 0 ? relPath.substring(cut + 1) : relPath;
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(DownloadEngine.extOfName(name)));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        dirPart.length() == 0 ? base : (base + "/" + dirPart));
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("系统下载目录拒绝写入");
                InputStream in = null;
                OutputStream out = null;
                try {
                    out = r.openOutputStream(uri);
                    in = new FileInputStream(f);
                    if (out == null) throw new IOException("无法写入下载目录");
                    copy(in, out);
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
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "疾风下载/" + t.filename);
            for (File f : all) {
                File dst = new File(dir, relativeOf(src, f));
                File parent = dst.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建下载子目录");
                }
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = new FileInputStream(f);
                    out = new FileOutputStream(dst);
                    copy(in, out);
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
                MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, null, null);
            }
        }
        deleteRecursive(src);
        return "folder:" + t.filename;
    }

    private static void collectFiles(File dir, ArrayList<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) collectFiles(f, out);
            else out.add(f);
        }
    }

    private static String relativeOf(File root, File f) {
        String a = root.getAbsolutePath();
        String b = f.getAbsolutePath();
        if (b.startsWith(a)) {
            b = b.substring(a.length());
            if (b.startsWith(File.separator)) b = b.substring(File.separator.length());
        }
        return b.replace('\\', '/');
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private String displayNameOf(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private String publish(DownloadEngine.Task t) throws IOException {
        File src = t.file();
        if (!src.exists()) throw new IOException("临时文件不存在");
        if ("bt".equals(t.kind) && t.btMulti) return publishFolder(t, src);

        // m3u8：TS 分片拼接件与 fMP4 直拼件都过一道 MediaExtractor+MediaMuxer 归一化，
        // 输出标准 MP4（fMP4 源 moov 时长为 0，分片 tfdt 携带时长，归一化后 moov 才会
        // 写入总时长，系统播放器/retriever 才能读到时长）；失败则保留原拼接件兜底。
        if ("m3u8".equals(t.kind)) {
            String base = t.filename;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            File mp4 = new File(src.getParentFile(), base + ".mp4");
            if (mp4.equals(src)) {
                mp4 = new File(src.getParentFile(), base + ".norm.mp4");
            }
            File remuxed = AndroidM3u8Post.remuxTsToMp4(src, mp4);
            if (remuxed != null) {
                src.delete();
                File finalMp4 = new File(src.getParentFile(), base + ".mp4");
                if (!remuxed.equals(finalMp4)) {
                    remuxed.renameTo(finalMp4);
                    remuxed = finalMp4;
                }
                src = remuxed;
                t.filename = remuxed.getName();
                t.ext = "mp4";
            }
        }

        // 兜底：任何路径都不允许产生 .txt / 无扩展名成品。
        if (DownloadEngine.extOfName(t.filename).length() == 0) {
            t.filename = t.filename + ".bin";
        }

        // 内部验收钩子：m3u8 成品在设备上用 MediaMetadataRetriever 读时长/分辨率，
        // 结果写 logcat（tag jifeng.m3u8），不改动 UI 与导出接口。
        if ("m3u8".equals(t.kind)) logMediaInfo(src, t.filename);

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, t.filename);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(t.ext));
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/疾风下载");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("系统下载目录拒绝写入");
            OutputStream out = null;
            InputStream in = null;
            try {
                out = r.openOutputStream(uri);
                in = new FileInputStream(src);
                if (out == null) throw new IOException("无法写入下载目录");
                copy(in, out);
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
            // MediaStore 遇到同名会自动改名（big.bin -> big (1).bin），把实际名字同步回卡片，
            // 否则界面上显示的名字和下载目录里的文件对不上。
            String real = displayNameOf(uri);
            if (real != null && real.length() > 0) t.filename = real;
            try {
                src.delete();
            } catch (Exception ignored) {
            }
            return uri.toString();
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "疾风下载");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建下载目录");
        File dst = uniqueFile(dir, t.filename);
        t.filename = dst.getName();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            copy(in, out);
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
        MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, null, null);
        try {
            src.delete();
        } catch (Exception ignored) {
        }
        return Uri.fromFile(dst).toString();
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (f.exists()) {
            f = new File(dir, base + " (" + i + ")" + ext);
            i++;
        }
        return f;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** 扩展名 -> MIME，必须与最终扩展名一致，避免 octet-stream 配 mp4。 */
    private static String mimeFor(String ext) {
        if (ext == null) ext = "";
        switch (ext) {
            case "mp4": return "video/mp4";
            case "m4a": return "audio/mp4";
            case "mov": return "video/quicktime";
            case "flv": return "video/x-flv";
            case "mp3": return "audio/mpeg";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "ts": return "video/mp2t";
            case "mkv": return "video/x-matroska";
            case "flac": return "audio/flac";
            case "rar": return "application/x-rar-compressed";
            case "7z": return "application/x-7z-compressed";
            case "ogg": return "audio/ogg";
            case "avi": return "video/x-msvideo";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m == null ? "application/octet-stream" : m;
        }
    }

    /** 验收用：用 MediaMetadataRetriever 读取成品时长/分辨率，写入 logcat。 */
    private void logMediaInfo(File f, String tag) {
        android.media.MediaMetadataRetriever mm = new android.media.MediaMetadataRetriever();
        try {
            mm.setDataSource(f.getAbsolutePath());
            String dur = mm.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = mm.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mm.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            android.util.Log.i("jifeng.m3u8", tag + " path=" + f.getName() + " size=" + f.length()
                    + " durationMs=" + dur + " video=" + w + "x" + h);
        } catch (Exception e) {
            android.util.Log.e("jifeng.m3u8", tag + " retriever failed: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
        } finally {
            try { mm.release(); } catch (Exception ignored) {}
        }
    }

    private int req(DownloadEngine.Task t) {
        return t.id == null ? 7 : Math.abs(t.id.hashCode());
    }

    /** 下载完成/失败的独立提醒：默认通道很低调，这条走会响的通道。 */
    private void notifyResult(DownloadEngine.Task t, boolean ok) {
        try {
            String nm = t.filename == null || t.filename.length() == 0 ? "文件" : t.filename;
            String title = ok ? ("下载完成：" + nm) : ("下载失败：" + nm);
            String text = ok ? (TaskAdapter.fmtSize(t.downloaded) + "，已存到 下载/疾风下载")
                    : (t.error == null || t.error.length() == 0 ? "下载失败" : t.error);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_DONE);
            else b = new Notification.Builder(this);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            if (ok) b.setDefaults(Notification.DEFAULT_ALL);
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int pi = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) pi |= PendingIntent.FLAG_IMMUTABLE;
            b.setContentIntent(PendingIntent.getActivity(this, req(t), open, pi));
            NotificationManager mgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (mgr != null) mgr.notify(2000 + (req(t) & 0xffff), b.build());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 通知
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "下载任务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationChannel done = new NotificationChannel(CHANNEL_DONE, "下载完成提醒",
                    NotificationManager.IMPORTANCE_DEFAULT);
            done.enableVibration(true);
            done.setShowBadge(true);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(ch);
                nm.createNotificationChannel(done);
            }
        }
    }

    private Notification buildNotification(DownloadEngine.Task t, boolean indeterminate) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);

        String text;
        if ("preparing".equals(t.status)) text = "正在解析链接…";
        else if ("done".equals(t.status)) text = "已完成";
        else text = TaskAdapter.fmtSpeed(t.speed) + "   " + TaskAdapter.fmtSize(t.downloaded)
                    + " / " + TaskAdapter.fmtSize(t.total);

        int pi = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pi |= PendingIntent.FLAG_IMMUTABLE;

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, pi);

        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(t.filename)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing("running".equals(t.status) || "preparing".equals(t.status))
                .setOnlyAlertOnce(true);
        if (t.total > 0) {
            b.setProgress(100, t.percent(), false);
        } else {
            b.setProgress(0, 0, indeterminate);
        }
        if ("running".equals(t.status) || "preparing".equals(t.status)) {
            Intent pauseIntent = new Intent(this, DownloadService.class)
                    .setAction(ACTION_PAUSE).putExtra(EXTRA_ID, t.id);
            b.addAction(android.R.drawable.ic_media_pause, "暂停",
                    PendingIntent.getService(this, 1, pauseIntent, pi));
            Intent allIntent = new Intent(this, DownloadService.class).setAction(ACTION_BATCH_PAUSE);
            b.addAction(android.R.drawable.ic_media_pause, "全部暂停",
                    PendingIntent.getService(this, 2, allIntent, pi));
        } else if ("paused".equals(t.status)) {
            Intent resumeIntent = new Intent(this, DownloadService.class)
                    .setAction(ACTION_RESUME).putExtra(EXTRA_ID, t.id);
            b.addAction(android.R.drawable.ic_media_play, "继续",
                    PendingIntent.getService(this, 3, resumeIntent, pi));
        }
        return b.build();
    }

    private void updateNotification(DownloadEngine.Task t) {
        if (t == null) return;
        boolean inRunning;
        synchronized (LOCK) {
            inRunning = running.contains(t);
        }
        if (!inRunning) return;
        if ("done".equals(t.status) || "error".equals(t.status) || "canceled".equals(t.status)) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(t, true));
        } catch (Exception ignored) {
        }
    }

    /** 盯着网络：断了先暂停在跑的任务，回来了自动接着下，避免无谓重试与空转耗电。 */
    private void setupNetworkWatch() {
        try {
            cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            netCb = new ConnectivityManager.NetworkCallback() {
                public void onLost(Network n) {
                    main.postDelayed(netDownCheck, 2500);
                }

                public void onAvailable(Network n) {
                    main.post(netUpCheck);
                }
            };
            cm.registerNetworkCallback(req, netCb);
        } catch (Throwable ignored) {
        }
    }

    /** 自检用：强制走一次「断网」分支，便于在无触摸环境验证暂停逻辑。 */
    static volatile boolean forceNetDown;

    public static void debugNetPause() {
        DownloadService s = instance;
        if (s == null) return;
        forceNetDown = true;
        s.main.post(s.netDownCheck);
    }

    public static void debugNetResume() {
        DownloadService s = instance;
        if (s == null) return;
        s.main.post(s.netUpCheck);
    }

    private final Runnable netDownCheck = new Runnable() {
        public void run() {
            if (forceNetDown) {
                forceNetDown = false;
            } else {
                try {
                    if (cm == null) return;
                    Network act = cm.getActiveNetwork();
                    if (act != null) {
                        NetworkCapabilities cap = cm.getNetworkCapabilities(act);
                        if (cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return;
                    }
                } catch (Throwable ignored) {
                    return;
                }
            }
            int paused = 0;
            synchronized (LOCK) {
                for (DownloadEngine.Task t : new ArrayList<>(running)) {
                    if ("running".equals(t.status) || "preparing".equals(t.status)) {
                        engine.pause(t);
                        if (!netPaused.contains(t.id)) netPaused.add(t.id);
                        paused++;
                    }
                }
            }
            if (paused > 0) {
                try {
                    stopForeground(true);
                } catch (Exception ignored) {
                }
                pump();
            }
        }
    };

    private final Runnable netUpCheck = new Runnable() {
        public void run() {
            ArrayList<String> ids;
            synchronized (LOCK) {
                ids = new ArrayList<>(netPaused);
                netPaused.clear();
            }
            if (ids.isEmpty()) return;
            for (String id : ids) {
                DownloadEngine.Task t = find(id);
                if (t != null && "paused".equals(t.status)) {
                    engine.resume(t);
                    synchronized (LOCK) {
                        if (!running.contains(t)) running.add(t);
                    }
                }
            }
            pump();
        }
    };

    public void onDestroy() {
        try {
            if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb);
        } catch (Throwable ignored) {
        }
        try {
            synchronized (LOCK) {
                for (DownloadEngine.Task t : running) {
                    if ("running".equals(t.status) || "preparing".equals(t.status)) {
                        engine.pause(t);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        instance = null;
        super.onDestroy();
    }
}
