package com.jifeng.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Locale;

/** 站内播放器：播放已下载到 MediaStore 的音视频文件。 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_MIME = "mime";

    private VideoView video;
    private SeekBar seek;
    private TextView clock;
    private TextView title;
    private TextView btnPlay;
    private TextView btnFull;
    private TextView btnOther;
    private TextView btnSound;
    private int savedVol = -1;
    private TextView click;
    private TextView failMsg;
    private View ctrlBar;
    private TextView hud;
    private int seekFrom;
    private int seekTo;
    private boolean seeking;
    private long touchDownAt;

    private Handler handler;
    private boolean prepared;
    private boolean dragging;
    private boolean fullscreen;
    private String uriText = "";
    private String mime = "video/*";
    private String name = "";
    private boolean isStream;
    private int streamRetries;
    private android.content.SharedPreferences pref;
    private String posKey = "";
    private float touchX;
    private float touchY;
    private long lastTapAt;

    private final Runnable tick = new Runnable() {
        public void run() {
            step();
            handler.postDelayed(this, 500);
        }
    };

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        video = (VideoView) findViewById(R.id.video);
        seek = (SeekBar) findViewById(R.id.seek);
        clock = (TextView) findViewById(R.id.clock);
        title = (TextView) findViewById(R.id.title);
        btnPlay = (TextView) findViewById(R.id.btnPlay);
        btnFull = (TextView) findViewById(R.id.btnFull);
        btnOther = (TextView) findViewById(R.id.btnOther);
        btnSound = (TextView) findViewById(R.id.btnSound);
        click = (TextView) findViewById(R.id.btnClose);
        failMsg = (TextView) findViewById(R.id.failMsg);
        ctrlBar = findViewById(R.id.ctrlBar);
        hud = (TextView) findViewById(R.id.hud);
        handler = new Handler();

        Intent it = getIntent();
        uriText = it.getStringExtra(EXTRA_URI);
        name = it.getStringExtra(EXTRA_NAME);
        String m = it.getStringExtra(EXTRA_MIME);
        if (m != null && m.startsWith("audio")) mime = m;
        title.setText(name == null || name.length() == 0 ? "播放" : name);
        pref = getSharedPreferences("playpos", MODE_PRIVATE);
        posKey = "p:" + (name == null || name.length() == 0 ? uriText : name);
        isStream = uriText != null && uriText.startsWith("http://127.0.0.1");

        // 手势：左右滑快进/快退，双击暂停/继续，右侧上下划调音量，左侧上下划调亮度。
        video.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        touchX = e.getX();
                        touchY = e.getY();
                        touchDownAt = System.currentTimeMillis();
                        seeking = false;
                        seekTo = -1;
                        seekFrom = prepared ? video.getCurrentPosition() : 0;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float mdx = e.getX() - touchX;
                        float mdy = Math.abs(e.getY() - touchY);
                        if (!seeking && prepared && Math.abs(mdx) > 30 && Math.abs(mdx) > mdy * 1.5f) {
                            seeking = true;
                            dragging = true;
                            seekFrom = video.getCurrentPosition();
                            showHud();
                        }
                        if (seeking) {
                            int dur = video.getDuration();
                            int range = swipeRange(dur);
                            int w = v.getWidth() > 0 ? v.getWidth() : 1;
                            int delta = (int) (mdx / w * range);
                            seekTo = Math.max(0, Math.min(dur > 0 ? dur : 0, seekFrom + delta));
                            hud.setText((delta >= 0 ? "快进 +" : "快退 -") + fmt(Math.abs(delta))
                                    + "\n" + fmt(seekTo) + " / " + fmt(dur));
                            seek.setProgress(seekTo);
                            clock.setText(fmt(seekTo) + " / " + fmt(dur));
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        float dx = Math.abs(e.getX() - touchX);
                        float dy = e.getY() - touchY;
                        if (seeking) {
                            boolean quick = System.currentTimeMillis() - touchDownAt < 160;
                            seeking = false;
                            dragging = false;
                            if (prepared && seekTo >= 0) {
                                video.seekTo(seekTo);
                                seek.setProgress(seekTo);
                                clock.setText(fmt(seekTo) + " / " + fmt(video.getDuration()));
                                Log.i("JfPlay", "swipe seek -> " + seekTo + "ms from " + seekFrom
                                        + " (quick=" + quick + ")");
                            }
                            handler.removeCallbacks(hudHide);
                            handler.postDelayed(hudHide, 520);
                            return true;
                        }
                        if (dx < 40 && Math.abs(dy) < 40) {
                            long now = System.currentTimeMillis();
                            if (now - lastTapAt < 300) {
                                toggle();
                                lastTapAt = 0;
                            } else {
                                lastTapAt = now;
                            }
                        } else if (Math.abs(dy) > 60 && Math.abs(dy) > dx * 1.2f) {
                            adjust(-dy / 400f, e.getX() > v.getWidth() / 2);
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (seeking) {
                            seeking = false;
                            dragging = false;
                            hideHud();
                        }
                        return true;
                }
                return false;
            }
        });

        btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggle();
            }
        });
        btnFull.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setFullscreen(!fullscreen);
            }
        });
        btnSound.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showVolume();
            }
        });
        btnSound.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                toggleMute();
                return true;
            }
        });
        btnOther.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openElsewhere();
            }
        });
        click.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && prepared) {
                    int dur = video.getDuration();
                    if (dur > 0) video.seekTo(p);
                    clock.setText(fmt(p) + " / " + fmt(dur));
                }
            }

            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
            }
        });

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                prepared = true;
                try {
                    mp.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                    mp.setVolume(1f, 1f);
                } catch (Throwable ignored) {
                }
                refreshSound();
                failMsg.setVisibility(View.GONE);
                video.setVisibility(View.VISIBLE);
                int dur = video.getDuration();
                seek.setMax(dur > 0 ? dur : 1);
                long saved = pref.getLong(posKey, 0);
                if (saved > 3000 && dur > 0 && saved < dur - 2000) {
                    video.seekTo((int) saved);
                    clock.setText(fmt((int) saved) + " / " + fmt(dur));
                } else {
                    clock.setText("0:00 / " + fmt(dur));
                }
                video.start();
                btnPlay.setText("暂停");
                handler.removeCallbacks(tick);
                handler.post(tick);
                if (hud != null && !pref.getBoolean("hint_swipe", false)) {
                    hud.setText("左右滑动：快退 / 快进\n上下滑动：左亮度、右音量；双击：暂停/继续");
                    hud.setVisibility(View.VISIBLE);
                    handler.removeCallbacks(hudHide);
                    handler.postDelayed(hudHide, 3600);
                    pref.edit().putBoolean("hint_swipe", true).apply();
                }
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                btnPlay.setText("播放");
                seek.setProgress(seek.getMax());
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (isStream && streamRetries < 5) {
                    retryStream();
                    return true;
                }
                showFail(reason(what, extra) + (isStream
                        ? "\n\n这是边下边播：当前网速跟不上视频码率。建议等下载完成后再打开，或换到更快的网络。"
                        : ""));
                return true;
            }
        });
        video.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    failMsg.setVisibility(View.VISIBLE);
                    failMsg.setText("网络较慢，正在缓冲…\n下载追上播放进度后会接着放。");
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    if (prepared) failMsg.setVisibility(View.GONE);
                }
                return false;
            }
        });

        if (uriText == null || uriText.length() == 0) {
            showFail("没有收到要播放的文件地址");
            return;
        }
        try {
            video.setVideoURI(Uri.parse(uriText));
            video.requestFocus();
        } catch (Exception e) {
            showFail("这个文件打不开：" + e.getClass().getSimpleName());
        }

        // 边下边播时，mp4 的 moov 索引可能在文件尾部，系统播放器要等到它才会出画面。
        if (uriText.startsWith("http")) {
            handler.postDelayed(new Runnable() {
                public void run() {
                    if (prepared) return;
                    failMsg.setVisibility(View.VISIBLE);
                    failMsg.setText("正在缓冲…\n"
                            + "边下边播时，播放器要先拿到视频索引和前段数据才会出画面。\n"
                            + "网速偏慢时会多等一会儿，下载追上后自动开始。");
                    ctrlBar.setVisibility(View.VISIBLE);
                }
            }, 8000);
        }
    }

    /** 边下边播遇到 IO 错误（多半是网速跟不上）：退避几秒后重新连接，等下载追上进度。 */
    private void retryStream() {
        streamRetries++;
        prepared = false;
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        failMsg.setVisibility(View.VISIBLE);
        failMsg.setText("网络较慢，正在缓冲…（第 " + streamRetries + " 次）\n"
                + "下载追上播放进度后会自动继续。");
        ctrlBar.setVisibility(View.VISIBLE);
        final long delay = 3000L + 2000L * streamRetries;
        handler.postDelayed(new Runnable() {
            public void run() {
                if (isFinishing()) return;
                try {
                    video.setVideoURI(Uri.parse(uriText));
                    video.requestFocus();
                } catch (Exception ignored) {
                }
            }
        }, delay);
    }

    private final Runnable hudHide = new Runnable() {
        public void run() {
            if (hud != null) hud.setVisibility(View.GONE);
        }
    };

    /** 整屏宽度对应的快进/快退时长：短视频按 35% 时长，长片最多 2 分钟。 */
    private int swipeRange(int dur) {
        if (dur <= 0) return 30000;
        int r = (int) (dur * 0.35f);
        if (r < 10000) r = 10000;
        if (r > 120000) r = 120000;
        if (r > dur) r = dur;
        return r;
    }

    private void showHud() {
        if (hud == null) return;
        handler.removeCallbacks(hudHide);
        hud.setVisibility(View.VISIBLE);
    }

    private void hideHud() {
        if (hud == null) return;
        handler.removeCallbacks(hudHide);
        hud.setVisibility(View.GONE);
    }

    /** 播放/暂停切换。 */
    private void toggle() {
        if (!prepared) return;
        if (video.isPlaying()) {
            video.pause();
            btnPlay.setText("播放");
        } else {
            if (video.getCurrentPosition() >= video.getDuration() - 500) video.seekTo(0);
            video.start();
            btnPlay.setText("暂停");
        }
    }

    /** 定时刷新进度与时间，拖动时不回写，避免手指打架。 */
    private void step() {
        if (!prepared) return;
        int pos = video.getCurrentPosition();
        int dur = video.getDuration();
        if (!dragging && dur > 0) {
            seek.setMax(dur);
            seek.setProgress(pos);
            clock.setText(fmt(pos) + " / " + fmt(dur));
        }
    }

    /** 手势调节：右侧调音量，左侧调亮度。 */
    private void adjust(float ratio, boolean volume) {
        if (volume) {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int next = Math.max(0, Math.min(max, Math.round(cur + ratio * max)));
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0);
        } else {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            float b = lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
            lp.screenBrightness = Math.max(0.05f, Math.min(1f, b + ratio));
            getWindow().setAttributes(lp);
        }
    }

    private AudioManager am() {
        return (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    /** 控制条上的音量按钮文案跟着系统音量走。 */
    private void refreshSound() {
        if (btnSound == null) return;
        AudioManager a = am();
        int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = a.getStreamVolume(AudioManager.STREAM_MUSIC);
        btnSound.setText(cur <= 0 ? "已静音" : ("音量 " + cur + "/" + max));
    }

    /** 弹出音量滑杆：拖动即时改媒体音量，另有静音一键切换。 */
    private void showVolume() {
        final AudioManager a = am();
        final int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final int cur = a.getStreamVolume(AudioManager.STREAM_MUSIC);
        int pad = (int) (getResources().getDisplayMetrics().density * 18);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        final TextView val = new TextView(this);
        val.setTextSize(13);
        val.setText("当前音量 " + cur + " / " + max + (cur == 0 ? "（静音）" : ""));
        box.addView(val);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(cur);
        box.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                a.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
                val.setText("当前音量 " + p + " / " + max + (p == 0 ? "（静音）" : ""));
                refreshSound();
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        new AlertDialog.Builder(this)
                .setTitle("音量")
                .setView(box)
                .setPositiveButton("完成", null)
                .setNeutralButton("静音/恢复", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        toggleMute();
                    }
                })
                .show();
    }

    /** 一键静音/恢复，恢复时回填原音量。 */
    private void toggleMute() {
        AudioManager a = am();
        int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (a.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            savedVol = a.getStreamVolume(AudioManager.STREAM_MUSIC);
            a.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        } else {
            int back = savedVol > 0 ? savedVol : Math.max(1, max / 2);
            a.setStreamVolume(AudioManager.STREAM_MUSIC, back, 0);
        }
        refreshSound();
    }

    /** 记住播放进度，下次打开接着看。 */
    private void savePos() {
        if (!prepared || pref == null) return;
        int pos = video.getCurrentPosition();
        int dur = video.getDuration();
        if (pos > 3000 && dur > 0 && pos < dur - 2000) {
            pref.edit().putLong(posKey, pos).apply();
        } else if (dur > 0) {
            pref.edit().remove(posKey).apply();
        }
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        if (on) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            ctrlBar.setVisibility(View.GONE);
            btnFull.setText("退出全屏");
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            ctrlBar.setVisibility(View.VISIBLE);
            btnFull.setText("全屏");
        }
    }

    /** 全屏时点屏幕任意位置唤回控制条。 */
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (fullscreen && ev.getAction() == android.view.MotionEvent.ACTION_UP) {
            ctrlBar.setVisibility(View.VISIBLE);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void showFail(String why) {
        prepared = false;
        handler.removeCallbacks(tick);
        ctrlBar.setVisibility(View.GONE);
        video.setVisibility(View.GONE);
        failMsg.setVisibility(View.VISIBLE);
        failMsg.setText(why + "\n\n可以试试「用其他播放器打开」。");
        new AlertDialog.Builder(this)
                .setTitle("播放失败")
                .setMessage(why)
                .setPositiveButton("用其他播放器打开", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        openElsewhere();
                    }
                })
                .setNegativeButton("关闭", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .show();
    }

    private String reason(int what, int extra) {
        if (what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) {
            return "这个文件的编码或封装不被系统支持（比如部分 MKV/HEVC），可以试试别的播放器。";
        }
        if (what == MediaPlayer.MEDIA_ERROR_IO) {
            return "读不到这个文件，可能已被删除或移除，请回到列表重新下载。";
        }
        if (what == MediaPlayer.MEDIA_ERROR_MALFORMED) {
            return "文件内容不完整或格式不对，下载可能没有完整结束。";
        }
        if (what == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
            return "播放超时，文件可能读得很慢或已损坏。";
        }
        return "系统播放器解不了这个文件（错误码 " + what + "/" + extra + "）。";
    }

    private void openElsewhere() {
        if (uriText == null || uriText.length() == 0) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(uriText), mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("打不开")
                    .setMessage("设备上没有能播放这个格式的应用。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        super.onBackPressed();
    }

    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        savePos();
        refreshSound();
        if (prepared && video.isPlaying()) {
            video.pause();
            btnPlay.setText("播放");
        }
    }

    protected void onResume() {
        super.onResume();
        if (prepared) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(tick);
        savePos();
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        video = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
