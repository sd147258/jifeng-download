package com.jifeng.download;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 主界面：粘贴/分享链接加任务，列表展示进度。 */
public class MainActivity extends Activity implements TaskAdapter.Actions {

    private static final Pattern FILE_RE = Pattern.compile(
            "\\\\.(zip|rar|7z|tar|gz|exe|apk|ipa|pdf|epub|mp4|mkv|avi|mov|mp3|flac|wav|docx?|xlsx?|pptx?|txt|csv|iso|bin)(\\\\?|#|$)",
            Pattern.CASE_INSENSITIVE);

    private EditText input;
    private TextView tip;
    private TextView speed;
    private TextView empty;
    private ListView list;
    private TaskAdapter adapter;
    private View selBar;
    private TextView selCount;
    private TextView tabActive;
    private TextView tabDone;
    private TextView tabOther;
    /** 0=进行中，1=已完成，2=失败/取消。 */
    private int group;
    private Handler handler;
    private boolean clipChecked;
    private boolean askedOnce;

    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            handler.postDelayed(this, 600);
        }
    };

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        input = (EditText) findViewById(R.id.urlInput);
        tip = (TextView) findViewById(R.id.tip);
        speed = (TextView) findViewById(R.id.speed);
        empty = (TextView) findViewById(R.id.empty);
        list = (ListView) findViewById(R.id.list);
        Button btnAdd = (Button) findViewById(R.id.btnAdd);
        Button btnPaste = (Button) findViewById(R.id.btnPaste);
        TextView btnGrab = (TextView) findViewById(R.id.btnGrabPage);
        TextView btnSettings = (TextView) findViewById(R.id.btnSettings);

        adapter = new TaskAdapter(this, this);
        list.setAdapter(adapter);
        handler = new Handler();

        selBar = findViewById(R.id.selBar);
        selCount = (TextView) findViewById(R.id.selCount);
        tabActive = (TextView) findViewById(R.id.tabActive);
        tabDone = (TextView) findViewById(R.id.tabDone);
        tabOther = (TextView) findViewById(R.id.tabOther);
        tabActive.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchGroup(0);
            }
        });
        tabDone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchGroup(1);
            }
        });
        tabOther.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchGroup(2);
            }
        });
        Button btnBatchPause = (Button) findViewById(R.id.btnBatchPause);
        Button btnBatchDelete = (Button) findViewById(R.id.btnBatchDelete);
        Button btnExitSel = (Button) findViewById(R.id.btnExitSel);
        btnBatchPause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBatchPause(adapter.selectedIds());
            }
        });
        btnBatchDelete.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBatchDelete(adapter.selectedIds());
            }
        });
        btnExitSel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                adapter.clearSelection();
                selBar.setVisibility(View.GONE);
            }
        });

        btnAdd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                submit();
            }
        });
        Button btnDecode = (Button) findViewById(R.id.btnDecode);
        btnDecode.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                decode();
            }
        });
        btnGrab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                grabPageFlow(false);
            }
        });
        btnPaste.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pasteFromClipboard(true);
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSettings();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit();
                    return true;
                }
                return false;
            }
        });

        startService(new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_SYNC));
        askPermissions();
        handleShare(getIntent());
        handlePlay(getIntent());
    }

    /** 调试/自检入口：带 play_uri 启动时直接进播放器，便于无触摸环境下验证。 */
    /** 调试：把本进程所有线程的栈打到 logcat（TAG JfDump）。 */
    private void dumpStacks() {
        new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                java.util.Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
                for (java.util.Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                    Thread th = e.getKey();
                    sb.append("TH ").append(th.getName()).append(" | ").append(th.getState()).append("\n");
                    int n = 0;
                    for (StackTraceElement st : e.getValue()) {
                        if (st.getClassName().startsWith("java.lang.Thread")) continue;
                        sb.append("    ").append(st.getClassName()).append(".")
                                .append(st.getMethodName()).append(":")
                                .append(st.getLineNumber()).append("\n");
                        if (++n >= 8) break;
                    }
                }
                String all2 = sb.toString();
                int step = 900;
                for (int i = 0; i < all2.length(); i += step) {
                    android.util.Log.i("JfDump", all2.substring(i, Math.min(all2.length(), i + step)));
                }
                android.util.Log.i("JfDump", "END total=" + all.size());
            }
        }, "JfDumpT").start();
    }

    private void handlePlay(Intent intent) {
        if (intent == null) return;
        // 自检开关按本次 Intent 重置：上一次自检留下的状态不能影响正常下载
        BtDownloader.forceDht = false;
        BtDownloader.metaProbeOnly = false;
        BtDownloader.dhtProbeOnly = false;
        BtDownloader.dhtEnabled = true;
        BtDownloader.dhtBudgetMs = 12000;
        Dht.DEBUG = false;
        synchronized (Dht.EXTRA) {
            Dht.EXTRA.clear();
        }
        DownloadEngine.assistEnabled = true;
        FtpDownloader.DEBUG = intent.getBooleanExtra("ftp_debug", false);
        if (intent.getBooleanExtra("dump", false)) dumpStacks();
        String th = intent.getStringExtra("thunder");
        if (th != null && th.length() > 0) {
            String real = Thunder.decode(th);
            input.setText(real == null ? "（未能还原）" : real);
            toast(real == null ? "不是有效的迅雷专用链" : "已还原为真实地址");
            return;
        }
        String net = intent.getStringExtra("nettest");
        if (net != null && net.length() > 0) {
            if ("pause".equals(net)) {
                DownloadService.debugNetPause();
                toast("已模拟断网：正在跑的任务应自动暂停");
            } else {
                DownloadService.debugNetResume();
                toast("已模拟网络恢复：应自动续传");
            }
            return;
        }
        int limitKb = intent.getIntExtra("limit_kb", -1);
        if (limitKb >= 0) {
            DownloadEngine.speedLimit = limitKb * 1024L;
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putLong("speedKb", limitKb).apply();
        }
        if (intent.hasExtra("noassist")) {
            DownloadEngine.assistEnabled = !intent.getBooleanExtra("noassist", true);
        }
        String batch = intent.getStringExtra("batch_text");
        if (batch != null && batch.length() > 0) {
            input.setText(batch);
            submit();
            return;
        }
        String dhtNode = intent.getStringExtra("dht_node");
        if (dhtNode != null && dhtNode.length() > 0) {
            for (String one : dhtNode.split(",")) {
                String a = one.trim();
                if (a.length() > 0) Dht.EXTRA.add(a);
            }
        }
        String noDht = intent.getStringExtra("no_dht");
        if (noDht != null) BtDownloader.dhtEnabled = !"1".equals(noDht) && !"true".equals(noDht);
        if (intent.getBooleanExtra("force_dht", false)) BtDownloader.forceDht = true;
        if (intent.getBooleanExtra("meta_only", false)) BtDownloader.metaProbeOnly = true;
        if (intent.getBooleanExtra("dht_debug", false)) Dht.DEBUG = true;
        if (intent.getBooleanExtra("dht_only", false)) BtDownloader.dhtProbeOnly = true;
        String dhtBudget = intent.getStringExtra("dht_ms");
        if (dhtBudget != null) {
            try {
                BtDownloader.dhtBudgetMs = Long.parseLong(dhtBudget.trim());
            } catch (Exception ignored) {
            }
        }
        String gp = intent.getStringExtra("grab_page");
        if (gp != null && gp.length() > 0) {
            input.setText(gp);
            grabPageFlow(!intent.getBooleanExtra("grab_ui", false));
            return;
        }
        String start = intent.getStringExtra("start_url");
        if (start != null && start.length() > 0) {
            addToQueue(start, "", "");
            input.setText(start);
            return;
        }
        if (intent.getBooleanExtra("stream_first", false)) {
            for (DownloadEngine.Task t : DownloadService.snapshot()) {
                if (!"done".equals(t.status) && t.segments.size() <= 1 && isMedia(t.filename)) {
                    onOpen(t);
                    return;
                }
            }
            toast("没有可边下边播的单流任务");
            return;
        }
        String d = intent.getStringExtra("decode_url");
        if (d != null && d.length() > 0) {
            input.setText(d);
            decode();
            return;
        }
        String u = intent.getStringExtra("play_uri");
        if (u == null || u.length() == 0) return;
        Intent p = new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_URI, u)
                .putExtra(PlayerActivity.EXTRA_NAME, "测试视频 mov_bbb.mp4")
                .putExtra(PlayerActivity.EXTRA_MIME, "video/mp4");
        startActivity(p);
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
        handlePlay(intent);
    }

    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (!clipChecked) {
            clipChecked = true;
            pasteFromClipboard(false);
        }
    }

    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------ 交互
    /** 超级解码：把网页/分享链接解析成可直接下载的文件地址。 */
    private void decode() {
        String raw = input.getText().toString().trim();
        if (raw.length() == 0) {
            raw = clipText();
        }
        if (raw.length() == 0) {
            toast("先粘贴网页链接或分享文案");
            return;
        }
        final String text = raw;
        setTip("超级解码中，正在抓取页面…");
        new Thread(new Runnable() {
            public void run() {
                final Sniffer.Result r = Sniffer.resolve(text);
                handler.post(new Runnable() {
                    public void run() {
                        if (r.hits.isEmpty()) {
                            if (r.m3u8Url != null && r.m3u8Url.length() > 0) {
                                // 页面只有切片：直接下 m3u8，由引擎合并成可播放的 mp4
                                setTip("识别到 m3u8 切片，开始下载并合并");
                                addToQueue(r.m3u8Url, r.finalUrl, "");
                                input.setText("");
                                toast("m3u8 已解析，开始下载");
                                return;
                            }
                            setTip("");
                            String reason = r.failReason == null || r.failReason.length() == 0
                                    ? "没解析到可下载的地址" : r.failReason;
                            showDecodeFailed(reason, r.seedUrl, r);
                            return;
                        }
                        Sniffer.Hit best = Sniffer.bestHit(r.hits);
                        if (best == null) {
                            setTip("");
                            showDecodeFailed("没解析到可下载的地址", r.seedUrl, r);
                            return;
                        }
                        if ("磁力".equals(best.kind)) {
                            setTip("识别到磁力链接，开始下载种子");
                        } else {
                            setTip("解码成功，开始下载");
                        }
                        addToQueue(best.url, best.referer, best.cookie);
                        input.setText("");
                        toast("磁力".equals(best.kind)
                                ? "磁力链接已加入队列，正在找源" : (best.kind + " 已解析，开始下载"));
                    }
                });
            }
        }, "sniffer").start();
    }

    /** 解码失败弹窗：给出原因，并提供「用浏览器打开」兜底入口。 */
    private void showDecodeFailed(String reason, String pageUrl) {
        showDecodeFailed(reason, pageUrl, null);
    }

    /** 带诊断细节的失败弹窗，方便把现场反馈回来。 */
    private void showDecodeFailed(String reason, String pageUrl, final Sniffer.Result r) {
        String msg = reason;
        if (r != null && (r.finalUrl.length() > 0 || r.seedUrl.length() > 0)) {
            msg = reason
                    + "\n\n落地地址：" + (r.finalUrl.length() > 0 ? r.finalUrl : r.seedUrl)
                    + "\n正文长度：" + r.bodyLen + " 字符"
                    + "\n命中的候选：" + r.hits.size() + " 条"
                    + (r.sawM3u8 ? "\n检测到 m3u8 切片" : "")
                    + (r.hops.isEmpty() ? "" : "\n跳转：" + r.hops.size() + " 次");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("未能解码")
                .setMessage(msg);
        final String copy = msg;
        b.setNeutralButton("复制详情", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("decode", copy));
                toast("详情已复制，可发给我定位");
            }
        });
        if (pageUrl != null && pageUrl.startsWith("http")) {
            final String url = pageUrl;
            b.setPositiveButton("用浏览器打开", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    openInBrowser(url);
                }
            });
        }
        b.setNegativeButton("关闭", null).show();
    }

    /** 入队：前台服务被系统拒绝启动时做一次降级重试，并给出明确提示。 */
    // ---------------------------------------------------------------- 整页抓取
    /** 抓当前输入框里那一页的所有可下载文件；autoAll=true 时直接全部入队（自检/无触摸环境用）。 */
    private void grabPageFlow(final boolean autoAll) {
        final String text = input.getText().toString().trim();
        if (text.length() == 0) {
            toast("先粘贴网页地址");
            return;
        }
        if (text.toLowerCase().startsWith("magnet:")) {
            toast("磁力链不用抓页面，直接点「下载」");
            return;
        }
        setTip("正在抓这一页的下载链接…");
        new Thread(new Runnable() {
            public void run() {
                final Sniffer.PageResult pr = Sniffer.grabPage(text, 80);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (pr.error.length() > 0) {
                            setTip("");
                            toast(pr.error);
                            return;
                        }
                        if (pr.links.isEmpty()) {
                            setTip("");
                            toast("这一页没找到可下载的文件（图片 / 视频 / 压缩包 / 文档都算）");
                            return;
                        }
                        setTip("这一页找到 " + pr.links.size() + " 个可下载文件");
                        if (autoAll) {
                            enqueueGrab(pr, null);
                            return;
                        }
                        showGrabDialog(pr);
                    }
                });
            }
        }, "jf-grab").start();
    }

    /** 勾选弹窗：默认全选，取消勾就只下剩下的。 */
    private void showGrabDialog(final Sniffer.PageResult pr) {
        final int n = pr.links.size();
        final String[] items = new String[n];
        final boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) {
            items[i] = labelOfLink(pr.links.get(i));
            checked[i] = true;
        }
        new AlertDialog.Builder(this)
                .setTitle("这一页有 " + n + " 个可下载文件")
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("下载选中的", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        enqueueGrab(pr, checked);
                    }
                })
                .setNeutralButton("全选", null)
                .setNegativeButton("取消", null)
                .show();
    }

    /** checked=null 表示全下；referer/cookie 一并带给引擎，防盗链页面也能过。 */
    private void enqueueGrab(Sniffer.PageResult pr, boolean[] checked) {
        int added = 0;
        for (int i = 0; i < pr.links.size(); i++) {
            if (checked != null && !checked[i]) continue;
            addToQueue(pr.links.get(i), pr.pageUrl, pr.cookie);
            added++;
        }
        if (added == 0) {
            toast("一个都没勾");
            setTip("");
            return;
        }
        setTip("已从这一页加入 " + added + " 个任务");
        toast("已加入 " + added + " 个下载任务");
    }

    private String labelOfLink(String u) {
        try {
            java.net.URL x = new java.net.URL(u);
            String p = x.getPath() == null ? "" : x.getPath();
            String name = p.substring(p.lastIndexOf('/') + 1);
            if (name.length() == 0) name = x.getHost();
            return name + "  ·  " + x.getHost();
        } catch (Exception e) {
            return u;
        }
    }

    private void addToQueue(String url, String referer, String cookie) {
        // 新任务入队后自动切到「进行中」，否则停在「已完成」页签会以为没加进去
        group = 0;
        adapter.clearSelection();
        selBar.setVisibility(View.GONE);
        if (url != null && url.startsWith("thunder://")) {
            String real = Thunder.decode(url);
            if (real != null && real.length() > 0) url = real;
        }
        Intent i = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_ADD)
                .putExtra(DownloadService.EXTRA_URL, url);
        if (referer != null && referer.length() > 0) i.putExtra(DownloadService.EXTRA_REFERER, referer);
        if (cookie != null && cookie.length() > 0) i.putExtra(DownloadService.EXTRA_COOKIE, cookie);
        try {
            startService(i);
        } catch (Exception e) {
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else throw e;
            } catch (Exception e2) {
                toast("系统拦住了后台启动下载，请回到 App 界面再点一次");
            }
        }
    }

    private void openInBrowser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (Exception e) {
            toast("没有可以打开该链接的浏览器");
        }
    }

    private String clipText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return "";
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return "";
        CharSequence t = cd.getItemAt(0).coerceToText(this);
        return t == null ? "" : t.toString().trim();
    }

    private void submit() {
        String raw = input.getText().toString().trim();
        List<String> many = Sniffer.splitLinks(raw);
        if (many.size() > 1) {
            submitBatch(many);
            return;
        }
        if (Magnet.looksLike(raw)) {
            Magnet m = Magnet.parse(raw);
            if (m == null || !m.valid()) {
                toast("磁力链里没找到 btih 信息哈希，请确认复制完整");
                return;
            }
            addToQueue(raw, "", "");
            input.setText("");
            setTip(m.trackers.isEmpty()
                    ? "已加入队列，但这条磁力链没有 tracker，可能找不到下载源"
                    : ("已加入队列：磁力下载（" + m.hashHex.substring(0, 8) + "…）"));
            askNotification();
            return;
        }
        if (Thunder.isEd2k(raw)) {
            toast("ed2k 电驴链接暂不支持，请用 http 直链");
            return;
        }
        if (raw.toLowerCase().startsWith("ftp://")) {
            addToQueue(raw, "", "");
            input.setText("");
            setTip("已加入队列：FTP 下载（单连接，支持断点续传）");
            askNotification();
            return;
        }
        String th = Thunder.decode(raw);
        String url = th != null ? th : Sniffer.extractUrl(raw);
        if (url.length() == 0) {
            toast("只支持 http / https 链接");
            return;
        }
        if (th != null) {
            input.setText(th);
            toast("已识别迅雷专用链，还原为真实地址");
        }
        // 「下载」按钮走智能路由：明确是文件直链才直接下，其余一律先超级解码再自动下载。
        if (!FILE_RE.matcher(url).find() && !url.toLowerCase().contains(".m3u8")) {
            decode();
            return;
        }
        addToQueue(url, "", "");
        input.setText("");
        setTip("已加入队列，正在解析链接…");
        askNotification();
    }

    /**
     * 批量下载：一次贴进来多条链接，逐条解析（磁力/迅雷直入，网页取最佳直链），
     * 能下的全部入队，最后报一句「成功几个、失败几个」。最多一次 10 条，多出来的留在输入框。
     */
    private void submitBatch(final List<String> links) {
        final int max = Math.min(links.size(), 10);
        final StringBuilder tail = new StringBuilder();
        for (int i = max; i < links.size(); i++) {
            if (tail.length() > 0) tail.append('\n');
            tail.append(links.get(i));
        }
        setTip("批量解析中：共 " + max + " 条链接…");
        new Thread(new Runnable() {
            public void run() {
                final List<String[]> jobs = new ArrayList<String[]>();
                final List<String> fails = new ArrayList<String>();
                for (int i = 0; i < max; i++) {
                    String link = links.get(i);
                    if (Magnet.looksLike(link)) {
                        Magnet m = Magnet.parse(link);
                        if (m == null || !m.valid()) {
                            fails.add("磁力链缺 btih");
                        } else {
                            jobs.add(new String[]{link, "", ""});
                        }
                        continue;
                    }
                    String th = Thunder.decode(link);
                    if (th != null) link = th;
                    Sniffer.Result r = Sniffer.resolve(link);
                    if (!r.hits.isEmpty()) {
                        Sniffer.Hit best = Sniffer.bestHit(r.hits);
                        if (best != null && best.url.length() > 0) {
                            jobs.add(new String[]{best.url,
                                    best.referer == null ? "" : best.referer,
                                    best.cookie == null ? "" : best.cookie});
                            continue;
                        }
                    }
                    if (r.m3u8Url != null && r.m3u8Url.length() > 0) {
                        jobs.add(new String[]{r.m3u8Url, r.finalUrl == null ? "" : r.finalUrl, ""});
                        continue;
                    }
                    fails.add(r.failReason == null || r.failReason.length() == 0
                            ? "没解析到可下载地址" : r.failReason);
                }
                handler.post(new Runnable() {
                    public void run() {
                        for (String[] j : jobs) addToQueue(j[0], j[1], j[2]);
                        input.setText(tail.toString());
                        setTip("批量加入 " + jobs.size() + " 个任务"
                                + (fails.isEmpty() ? "" : ("，" + fails.size() + " 条没解析成功")));
                        if (!fails.isEmpty()) toast("没解析成功：" + fails.get(0));
                        askNotification();
                    }
                });
            }
        }, "batch-decode").start();
    }

    private void pasteFromClipboard(boolean manual) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            if (manual) toast("剪贴板是空的");
            return;
        }
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return;
        CharSequence text = cd.getItemAt(0).coerceToText(this);
        if (text == null) return;
        String s = text.toString().trim();
        String url = Sniffer.extractUrl(s);
        if (url.length() == 0) {
            if (manual) toast("剪贴板里没有链接");
            return;
        }
        if (!manual && !FILE_RE.matcher(url).find()) return;
        input.setText(url);
        input.setSelection(url.length());
        setTip("已从剪贴板取到链接，点「下载」开始");
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        String url = Sniffer.extractUrl(text.toString());
        if (url.length() == 0) return;
        input.setText(url);
        submit();
        setTip("已接收分享链接");
    }

    private void showSettings() {
        int dp = (int) (getResources().getDisplayMetrics().density * 20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp, dp / 2, dp, 0);

        TextView label = new TextView(this);
        label.setText("分段并发上限（默认 8，引擎封顶 8）");
        label.setTextSize(13);
        box.addView(label);

        final EditText num = new EditText(this);
        num.setInputType(InputType.TYPE_CLASS_NUMBER);
        num.setText(String.valueOf(getSharedPreferences("cfg", MODE_PRIVATE).getInt("segments", 8)));
        box.addView(num);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("全局限速（KB/s，0 = 不限）");
        speedLabel.setTextSize(13);
        speedLabel.setPadding(0, dp / 2, 0, 0);
        box.addView(speedLabel);

        final EditText speedInput = new EditText(this);
        speedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        speedInput.setText(String.valueOf(getSharedPreferences("cfg", MODE_PRIVATE)
                .getLong("speedKb", 0)));
        box.addView(speedInput);

        final android.widget.CheckBox sound = new android.widget.CheckBox(this);
        sound.setText("下载完成时响铃 + 震动提醒");
        sound.setTextSize(13);
        sound.setPadding(0, dp / 2, 0, 0);
        sound.setChecked(getSharedPreferences("cfg", MODE_PRIVATE)
                .getBoolean("soundOn", true));
        box.addView(sound);

        TextView hint = new TextView(this);
        hint.setText("下载完成后文件会自动保存到「内部存储/Download/疾风下载」。分段越多不一定越快，服务器限速时 4 段就够。");
        hint.setTextSize(12);
        hint.setPadding(0, dp / 2, 0, 0);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int n;
                        try {
                            n = Integer.parseInt(num.getText().toString().trim());
                        } catch (Exception e) {
                            n = 8;
                        }
                        if (n < 1) n = 1;
                        if (n > 16) n = 16;
                        getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("segments", n).apply();
                        long kb = 0;
                        try {
                            kb = Long.parseLong(speedInput.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        if (kb < 0) kb = 0;
                        getSharedPreferences("cfg", MODE_PRIVATE).edit().putLong("speedKb", kb).apply();
                        DownloadEngine.speedLimit = kb * 1024L;
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                                .putBoolean("soundOn", sound.isChecked()).apply();
                        setTip(kb <= 0 ? "已保存：不限速" : ("已保存：限速 " + kb + " KB/s，立即生效"));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 列表刷新
    private long lastListSig = -1;

    private void refresh() {
        List<DownloadEngine.Task> tasks = DownloadService.snapshot();

        int activeN = 0, doneN = 0, badN = 0;
        List<DownloadEngine.Task> shown = new ArrayList<>();
        for (DownloadEngine.Task t : tasks) {
            int g = groupOf(t);
            if (g == 0) activeN++;
            else if (g == 1) doneN++;
            else badN++;
            if (g == group) shown.add(t);
        }
        // 进行中空了而别的分组有任务时，自动切过去，避免"任务好像不见了"
        if (group == 0 && activeN == 0) {
            int fallback = doneN > 0 ? 1 : (badN > 0 ? 2 : 0);
            if (fallback != 0) {
                group = fallback;
                shown.clear();
                for (DownloadEngine.Task t : tasks) {
                    if (groupOf(t) == group) shown.add(t);
                }
            }
        }
        long sig = 1;
        for (DownloadEngine.Task t : shown) {
            sig = sig * 31 + t.id.hashCode();
            sig = sig * 31 + t.status.hashCode();
            sig = sig * 31 + t.downloaded;
            sig = sig * 31 + t.total;
        }
        sig = sig * 31 + group;
        if (sig != lastListSig) {
            lastListSig = sig;
            adapter.setData(shown);
            tabActive.setText("进行中 " + activeN);
            tabDone.setText("已完成 " + doneN);
            tabOther.setText("失败 " + badN);
            paintTabs();
        }

        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (shown.isEmpty()) {
            if (group == 0) empty.setText("暂无进行中的任务\n粘贴链接或从浏览器分享过来即可开始");
            else if (group == 1) empty.setText("还没有已完成的任务\n下载成功后文件会保存在「下载/疾风下载」，点卡片即可播放");
            else empty.setText("没有失败或取消的任务\n解析失败的任务会出现在这里，并附带原因");
        }

        List<String> fails = DownloadService.drainDeleteFailures();
        for (String f : fails) toast(f);

        double total = 0;
        int running = 0;
        for (DownloadEngine.Task t : tasks) {
            if ("running".equals(t.status)) {
                total += t.speed;
                running++;
            }
        }
        if (running > 0) {
            speed.setText(running + " 个任务下载中 · " + TaskAdapter.fmtSpeed(total));
        } else if (!tasks.isEmpty()) {
            speed.setText(tasks.size() + " 个任务");
        } else {
            speed.setText("");
        }
    }

    /** 任务归组：0 进行中，1 已完成，2 失败/取消。 */
    private int groupOf(DownloadEngine.Task t) {
        if ("done".equals(t.status)) return 1;
        if ("error".equals(t.status) || "canceled".equals(t.status)) return 2;
        return 0;
    }

    private void switchGroup(int g) {
        if (group == g) return;
        group = g;
        adapter.clearSelection();
        selBar.setVisibility(View.GONE);
        refresh();
    }

    private void paintTabs() {
        TextView[] tabs = {tabActive, tabDone, tabOther};
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == group;
            if (on) {
                tabs[i].setBackground(getDrawable(R.drawable.bg_btn_mini));
                tabs[i].setTextColor(getColor(R.color.teal_light));
            } else {
                tabs[i].setBackgroundColor(0x00000000);
                tabs[i].setTextColor(getColor(R.color.dim));
            }
        }
    }

    private void setTip(String s) {
        tip.setText(s);
        handler.postDelayed(new Runnable() {
            public void run() {
                if (tip.getText() != null && tip.getText().toString().equals(s)) tip.setText("");
            }
        }, 4000);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 卡片操作
    public void onToggle(DownloadEngine.Task t) {
        String action;
        if ("running".equals(t.status) || "preparing".equals(t.status)) action = DownloadService.ACTION_PAUSE;
        else if ("paused".equals(t.status) || "error".equals(t.status)) action = DownloadService.ACTION_RESUME;
        else return;
        startService(new Intent(this, DownloadService.class).setAction(action).putExtra(DownloadService.EXTRA_ID, t.id));
        refresh();
    }

    public void onCancel(final DownloadEngine.Task t) {
        new AlertDialog.Builder(this)
                .setMessage("取消下载并删除已下载的部分？\n" + t.filename)
                .setPositiveButton("取消任务", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        startService(new Intent(MainActivity.this, DownloadService.class)
                                .setAction(DownloadService.ACTION_CANCEL)
                                .putExtra(DownloadService.EXTRA_ID, t.id));
                        refresh();
                    }
                })
                .setNegativeButton("继续下载", null)
                .show();
    }

    /** 删除任务：只删记录，或记录连同文件一起删。 */
    public void onDelete(final DownloadEngine.Task t) {
        final boolean hasFile = ("done".equals(t.status) && t.publicUri != null && t.publicUri.length() > 0)
                || t.file().exists();
        String msg = "任务：" + t.filename
                + "\n状态：" + statusText(t)
                + (hasFile ? "\n\n是否同时删除已下载的文件？" : "\n\n该任务没有可删除的文件。");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("删除任务")
                .setMessage(msg)
                .setPositiveButton(hasFile ? "删除记录和文件" : "删除记录", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        sendDelete(t, hasFile);
                        refresh();
                    }
                });
        if (hasFile) {
            b.setNeutralButton("只删记录", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    sendDelete(t, false);
                    refresh();
                }
            });
        }
        b.setNegativeButton("取消", null).show();
    }

    private void sendDelete(DownloadEngine.Task t, boolean withFile) {
        startService(new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_DELETE)
                .putExtra(DownloadService.EXTRA_ID, t.id)
                .putExtra(DownloadService.EXTRA_FILE, withFile));
    }

    private String statusText(DownloadEngine.Task t) {
        if ("done".equals(t.status)) return "已完成";
        if ("paused".equals(t.status)) return "已暂停";
        if ("error".equals(t.status)) return "出错";
        if ("canceled".equals(t.status)) return "已取消";
        if ("running".equals(t.status)) return "下载中";
        if ("preparing".equals(t.status)) return "解析中";
        return "等待开始";
    }

    public void onOpen(DownloadEngine.Task t) {
        // 正在下载的单流媒体：走本机 HTTP 边下边播，不用等下载完。
        if (!"done".equals(t.status) && isAsf(t.filename)) {
            toast("WMV/WMA 是微软 ASF 封装，不支持边下边播；下载完用其他播放器打开");
            return;
        }
        if (!"done".equals(t.status) && isMedia(t.filename)) {
            if (t.segments.size() > 1) {
                toast("这是多段并行的任务，等下载完再播放才不会有花屏");
                return;
            }
            if ("running".equals(t.status) || "preparing".equals(t.status) || "paused".equals(t.status)) {
                try {
                    String streamUrl = LocalStreamServer.get().urlFor(t);
                    Intent p = new Intent(this, PlayerActivity.class)
                            .putExtra(PlayerActivity.EXTRA_URI, streamUrl)
                            .putExtra(PlayerActivity.EXTRA_NAME, t.filename + "（边下边播）")
                            .putExtra(PlayerActivity.EXTRA_MIME, mimeFor(t.filename));
                    startActivity(p);
                    toast("已开始边下边播，播到未下载区域会稍等");
                    return;
                } catch (Exception e) {
                    toast("本机播放服务启动失败：" + e.getClass().getSimpleName());
                    return;
                }
            }
        }
        if (!("done".equals(t.status))) {
            toast("任务还没下载完成");
            return;
        }
        if (t.publicUri == null || t.publicUri.length() == 0) {
            toast("文件还没保存到下载目录");
            return;
        }
        if (t.publicUri.startsWith("folder:")) {
            toast("已保存到「下载/疾风下载/" + t.publicUri.substring(7) + "」，用文件管理器查看");
            return;
        }
        if (isAsf(t.filename)) toast("WMV/WMA 系统播放器解不了，已交给其他播放器打开");
        if (isMedia(t.filename) && !isAsf(t.filename)) {
            Intent p = new Intent(this, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_URI, t.publicUri)
                    .putExtra(PlayerActivity.EXTRA_NAME, t.filename)
                    .putExtra(PlayerActivity.EXTRA_MIME, mimeFor(t.filename));
            try {
                startActivity(p);
                return;
            } catch (Exception e) {
                toast("播放器启动失败，改用系统应用打开");
            }
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(t.publicUri), mimeFor(t.filename));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("没有可以打开该文件的应用");
        }
    }

    /** 微软 ASF 封装（wmv/wma/asf）：系统解码器基本不支持，直接交给外部播放器。 */
    private boolean isAsf(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".wmv") || n.endsWith(".wma") || n.endsWith(".asf");
    }

    /** 是否是可直接播放的音视频。 */
    private boolean isMedia(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        String[] exts = {".mp4", ".mkv", ".mov", ".flv", ".webm", ".m4v", ".3gp", ".ts", ".avi",
                ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus",
                ".wmv", ".wma", ".asf"};
        for (String e : exts) if (n.endsWith(e)) return true;
        return false;
    }

    /** 按扩展名给 MIME，避免系统拿 octet-stream 找不到应用。 */
    private String mimeFor(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase();
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".flv")) return "video/x-flv";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".ts")) return "video/mp2t";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".wmv")) return "video/x-ms-wmv";
        if (n.endsWith(".wma")) return "audio/x-ms-wma";
        if (n.endsWith(".asf")) return "video/x-ms-asf";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a") || n.endsWith(".aac")) return "audio/mp4";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------ 多选批量操作
    public void onSelectionChanged(int count) {
        if (count > 0) {
            selBar.setVisibility(View.VISIBLE);
            selCount.setText(getString(R.string.sel_title, count));
        } else {
            selBar.setVisibility(View.GONE);
        }
    }

    public void onBatchPause(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        Intent i = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_BATCH_PAUSE)
                .putStringArrayListExtra(DownloadService.EXTRA_IDS, new ArrayList<>(ids));
        startService(i);
        adapter.clearSelection();
        selBar.setVisibility(View.GONE);
        refresh();
    }

    public void onBatchDelete(final List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("删除选中的 " + ids.size() + " 个任务及其已下载文件？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(MainActivity.this, DownloadService.class)
                                .setAction(DownloadService.ACTION_BATCH_DELETE)
                                .putStringArrayListExtra(DownloadService.EXTRA_IDS, new ArrayList<>(ids))
                                .putExtra(DownloadService.EXTRA_FILE, true);
                        startService(i);
                        adapter.clearSelection();
                        selBar.setVisibility(View.GONE);
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 权限
    private void askPermissions() {
        if (askedOnce) return;
        askedOnce = true;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 11);
        }
    }

    private void askNotification() {
        askPermissions();
    }
}
