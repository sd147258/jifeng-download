package com.jifeng.download;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 任务卡片列表：支持长按进入多选态，批量暂停 / 删除。 */
public class TaskAdapter extends BaseAdapter {

    public interface Actions {
        void onToggle(DownloadEngine.Task t);

        void onCancel(DownloadEngine.Task t);

        void onOpen(DownloadEngine.Task t);

        void onDelete(DownloadEngine.Task t);

        /** 多选数量变化（0 表示退出多选态）。 */
        void onSelectionChanged(int count);

        void onBatchPause(List<String> ids);

        void onBatchDelete(List<String> ids);
    }

    private final Context ctx;
    private final Actions actions;
    private final List<DownloadEngine.Task> data = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private boolean multiSelect;

    public TaskAdapter(Context ctx, Actions actions) {
        this.ctx = ctx;
        this.actions = actions;
    }

    public void setData(List<DownloadEngine.Task> list) {
        data.clear();
        data.addAll(list);
        // 刷新后清掉已不存在任务的选中态
        Set<String> alive = new HashSet<>();
        for (DownloadEngine.Task t : data) alive.add(t.id);
        selected.retainAll(alive);
        multiSelect = !selected.isEmpty();
        notifyDataSetChanged();
    }

    public boolean isMultiSelect() {
        return multiSelect;
    }

    public List<String> selectedIds() {
        return new ArrayList<>(selected);
    }

    public void clearSelection() {
        selected.clear();
        multiSelect = false;
        notifyDataSetChanged();
    }

    private void toggle(DownloadEngine.Task t) {
        if (selected.contains(t.id)) {
            selected.remove(t.id);
        } else {
            selected.add(t.id);
        }
        multiSelect = !selected.isEmpty();
        notifyDataSetChanged();
        actions.onSelectionChanged(selected.size());
    }

    public int getCount() {
        return data.size();
    }

    public DownloadEngine.Task getItem(int i) {
        return data.get(i);
    }

    public long getItemId(int i) {
        return i;
    }

    public View getView(int pos, View convert, ViewGroup parent) {
        Holder h;
        if (convert == null) {
            convert = LayoutInflater.from(ctx).inflate(R.layout.item_task, parent, false);
            h = new Holder();
            h.cardBox = convert.findViewById(R.id.cardBox);
            h.name = (TextView) convert.findViewById(R.id.name);
            h.status = (TextView) convert.findViewById(R.id.status);
            h.host = (TextView) convert.findViewById(R.id.host);
            h.bar = (ProgressBar) convert.findViewById(R.id.bar);
            h.meta = (TextView) convert.findViewById(R.id.meta);
            h.toggle = (TextView) convert.findViewById(R.id.toggle);
            h.cancel = (TextView) convert.findViewById(R.id.cancel);
            h.open = (TextView) convert.findViewById(R.id.open);
            h.error = (TextView) convert.findViewById(R.id.error);
            convert.setTag(h);
        } else {
            h = (Holder) convert.getTag();
        }
        final DownloadEngine.Task t = data.get(pos);

        boolean sel = selected.contains(t.id);
        if (sel) {
            h.cardBox.setBackgroundColor(ctx.getColor(R.color.panel2));
        } else {
            h.cardBox.setBackground(ctx.getDrawable(R.drawable.bg_card));
        }
        h.name.setText((sel ? "✓ " : "") + t.filename);
        h.host.setText(t.host());

        int color;
        String st;
        if ("running".equals(t.status)) {
            st = "下载中";
            color = ctx.getColor(R.color.teal_light);
        } else if ("preparing".equals(t.status)) {
            st = "解析中";
            color = ctx.getColor(R.color.amber);
        } else if ("paused".equals(t.status)) {
            st = "已暂停";
            color = ctx.getColor(R.color.amber);
        } else if ("done".equals(t.status)) {
            st = "已完成";
            color = ctx.getColor(R.color.teal_light);
        } else if ("error".equals(t.status)) {
            st = "出错";
            color = ctx.getColor(R.color.red);
        } else if ("canceled".equals(t.status)) {
            st = "已取消";
            color = ctx.getColor(R.color.dim2);
        } else {
            st = "等待开始";
            color = ctx.getColor(R.color.dim);
        }
        h.status.setText(st);
        h.status.setTextColor(color);

        h.bar.setProgress(t.percent());

        StringBuilder sb = new StringBuilder();
        if ("running".equals(t.status)) {
            sb.append(fmtSpeed(t.speed));
            if (t.supportsRange) {
                sb.append(" · ").append(t.activeSegments()).append(" 段");
            } else {
                sb.append(" · 单流");
            }
            if (t.total > 0) {
                sb.append("   ").append(t.percent()).append('%');
                sb.append("   ").append(fmtSize(t.downloaded)).append(" / ").append(fmtSize(t.total));
            } else {
                sb.append("   ").append(fmtSize(t.downloaded));
            }
        } else if ("done".equals(t.status)) {
            sb.append(fmtSize(t.total)).append("   已保存到 下载/疾风下载");
            if (t.finishedAt > 0) sb.append("   ").append(fmtWhen(t.finishedAt));
        } else if ("paused".equals(t.status) || "error".equals(t.status)) {
            sb.append(fmtSize(t.downloaded)).append(" / ").append(fmtSize(t.total));
        } else if ("preparing".equals(t.status)) {
            sb.append("正在解析链接…");
        } else if ("canceled".equals(t.status)) {
            sb.append("已取消");
        } else {
            sb.append("等待开始");
        }
        h.meta.setText(sb.toString());

        boolean canToggle = "running".equals(t.status) || "preparing".equals(t.status)
                || "paused".equals(t.status) || "error".equals(t.status);
        if ("running".equals(t.status) || "preparing".equals(t.status)) {
            h.toggle.setText("暂停");
        } else if ("paused".equals(t.status) || "error".equals(t.status)) {
            h.toggle.setText("error".equals(t.status) ? "重试" : "继续");
        } else {
            h.toggle.setText("暂停");
        }
        h.toggle.setEnabled(canToggle);
        h.toggle.setAlpha(canToggle ? 1f : 0.35f);
        h.toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                actions.onToggle(t);
            }
        });

        boolean finished = "done".equals(t.status) || "canceled".equals(t.status);
        if (finished) {
            h.cancel.setText("删除");
            h.cancel.setEnabled(true);
            h.cancel.setAlpha(1f);
            h.cancel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    actions.onDelete(t);
                }
            });
        } else {
            h.cancel.setText("取消");
            h.cancel.setEnabled(true);
            h.cancel.setAlpha(1f);
            h.cancel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    actions.onCancel(t);
                }
            });
        }

        // 长按进入 / 退出多选；监听挂在整行根视图上，不被按钮的点击消费。
        convert.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                toggle(t);
                return true;
            }
        });
        convert.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (multiSelect) {
                    toggle(t);
                } else {
                    actions.onOpen(t);
                }
            }
        });

        boolean hasFile = "done".equals(t.status) && (t.publicUri.length() > 0 || t.file().exists());
        h.open.setEnabled(hasFile);
        h.open.setAlpha(hasFile ? 1f : 0.35f);
        h.open.setText(hasFile && isMedia(t.filename) ? "播放" : "打开");
        h.open.setTextColor(hasFile ? ctx.getColor(R.color.text) : ctx.getColor(R.color.dim2));
        h.open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                actions.onOpen(t);
            }
        });

        if ("error".equals(t.status) && t.error != null && t.error.length() > 0) {
            h.error.setVisibility(View.VISIBLE);
            h.error.setText(t.error);
        } else {
            h.error.setVisibility(View.GONE);
        }
        return convert;
    }

    static class Holder {
        View cardBox;
        TextView name;
        TextView status;
        TextView host;
        ProgressBar bar;
        TextView meta;
        TextView toggle;
        TextView cancel;
        TextView open;
        TextView error;
    }

    static boolean isMedia(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        String[] exts = {".mp4", ".mkv", ".mov", ".flv", ".webm", ".m4v", ".3gp", ".ts", ".avi",
                ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus"};
        for (String e : exts) if (n.endsWith(e)) return true;
        return false;
    }

    /** 完成时间：11-20 15:40（当天只显示时间）。 */
    static String fmtWhen(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        java.util.Calendar now = java.util.Calendar.getInstance();
        int y = c.get(java.util.Calendar.YEAR), ny = now.get(java.util.Calendar.YEAR);
        int d = c.get(java.util.Calendar.DAY_OF_YEAR), nd = now.get(java.util.Calendar.DAY_OF_YEAR);
        String hm = String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
        if (y == ny && d == nd) return "今天 " + hm;
        String md = String.format("%02d-%02d", c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
        return (y == ny ? md : y + "-" + md) + " " + hm;
    }

    public static String fmtSize(long n) {
        if (n < 0) return "—";
        if (n < 1024) return n + " B";
        double v = n;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }

    public static String fmtSpeed(double bps) {
        if (bps <= 0) return "0 B/s";
        return fmtSize((long) bps) + "/s";
    }
}
