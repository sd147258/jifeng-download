# -*- coding: utf-8 -*-
"""疾风下载 · Windows 桌面端（tkinter GUI）"""

import json
import os
import queue
import re
import sys
import threading
import tkinter as tk
import tkinter.font as tkfont
import webbrowser
from tkinter import filedialog, messagebox, ttk

import engine
import sniffer

APP_NAME = "疾风下载"
CFG_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "JifengDownload")
CFG_PATH = os.path.join(CFG_DIR, "settings.json")
DEFAULT_DIR = os.path.join(os.path.expanduser("~"), "Downloads", "疾风下载")

BG = "#0d1117"
PANEL = "#161b22"
PANEL2 = "#1c232c"
LINE = "#242c37"
TEXT = "#e6edf3"
DIM = "#8b96a5"
DIM2 = "#6c7784"
TEAL = "#2f9e7a"
TEAL_L = "#7fe0bd"
RED = "#e06c75"
AMBER = "#e5b567"

FILE_EXT = re.compile(
    r"\.(zip|rar|7z|tar|gz|bz2|xz|exe|msi|apk|ipa|dmg|iso|pdf|epub|mobi|"
    r"mp4|mkv|avi|mov|wmv|flv|webm|ts|m3u8|mp3|flac|wav|aac|m4a|ogg|"
    r"docx?|xlsx?|pptx?|txt|csv|psd|ai|srt|ass|torrent|bin)(\?|#|$)",
    re.I,
)

STATUS_TEXT = {
    "pending": "排队中",
    "preparing": "解析中",
    "running": "下载中",
    "paused": "已暂停",
    "done": "已完成",
    "error": "出错",
    "canceled": "已取消",
}
STATUS_COLOR = {
    "pending": DIM,
    "preparing": AMBER,
    "running": TEAL_L,
    "paused": AMBER,
    "done": TEAL,
    "error": RED,
    "canceled": DIM2,
}

CATEGORIES = ["全部", "视频", "音频", "压缩包", "文档", "其他"]


def load_cfg():
    cfg = {
        "segments": 8,          # 默认 8 段；0/自动 = 按大小 + 实测带宽自适应
        "rate_kbps": 0,         # 0 = 不限速
        "max_active": 2,
        "dest_dir": DEFAULT_DIR,
        "clipboard": True,
        "ua": engine.DEFAULT_UA,
        "cookie": "",
        "headers": {},
    }
    try:
        with open(CFG_PATH, "r", encoding="utf-8") as f:
            saved = json.load(f)
        cfg.update({k: v for k, v in saved.items() if k in cfg})
    except Exception:
        pass
    try:
        os.makedirs(cfg["dest_dir"], exist_ok=True)
    except Exception:
        cfg["dest_dir"] = DEFAULT_DIR
        os.makedirs(cfg["dest_dir"], exist_ok=True)
    return cfg


def save_cfg(cfg):
    try:
        os.makedirs(CFG_DIR, exist_ok=True)
        with open(CFG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


class App:
    def __init__(self, root):
        self.root = root
        self.cfg = load_cfg()
        self.q = queue.Queue()
        self.cards = {}
        self.selected = set()
        self.filter_cat = "全部"
        self.clip_last = ""
        self._last_page_url = ""

        self.manager = engine.Manager(self.cfg, on_change=self._on_change)
        self.manager.set_rate_limit_kbps(self.cfg.get("rate_kbps", 0))
        self._build_ui()
        self._load_existing()
        self.root.after(400, self._poll)
        self.root.after(900, self._clip_tick)

    # ---------------------------------------------------------------- UI
    def _build_ui(self):
        self.root.title(APP_NAME)
        self.root.configure(bg=BG)
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        self.root.geometry("1040x720+%d+%d" % (max(0, (sw - 1040) // 2), max(0, (sh - 720) // 3)))
        self.root.minsize(880, 560)

        self.f_title = tkfont.Font(family="Microsoft YaHei UI", size=19, weight="bold")
        self.f_sub = tkfont.Font(family="Microsoft YaHei UI", size=9)
        self.f_body = tkfont.Font(family="Microsoft YaHei UI", size=10)
        self.f_small = tkfont.Font(family="Microsoft YaHei UI", size=9)
        self.f_card = tkfont.Font(family="Microsoft YaHei UI", size=10, weight="bold")
        self.f_big = tkfont.Font(family="Microsoft YaHei UI", size=12, weight="bold")

        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure("JF.Horizontal.TProgressbar",
                        troughcolor="#0b0f13", background=TEAL,
                        bordercolor="#0b0f13", lightcolor=TEAL, darkcolor=TEAL,
                        thickness=7)
        style.configure("JF.Vertical.TScrollbar", gripcount=0, background="#2b3540",
                        darkcolor="#2b3540", lightcolor="#2b3540", troughcolor=BG,
                        bordercolor=BG, arrowcolor=DIM2, arrowsize=0, width=8)
        style.map("JF.Vertical.TScrollbar",
                  background=[("active", "#3a4552"), ("pressed", TEAL)],
                  troughcolor=[("!active", BG)])
        style.layout("JF.Vertical.TScrollbar", [
            ("Vertical.Scrollbar.trough", {
                "children": [("Vertical.Scrollbar.thumb", {"expand": "1", "sticky": "nswe"})],
                "sticky": "ns"}),
        ])
        style.configure("JF.TSpinbox", fieldbackground="#0b0f14", background=PANEL2,
                        foreground=TEXT, arrowcolor=DIM, bordercolor="#2b3540",
                        lightcolor="#2b3540", darkcolor="#2b3540", insertcolor=TEXT, padding=5)
        style.configure("JF.TEntry", fieldbackground="#0b0f14", foreground=TEXT,
                        bordercolor="#2b3540", lightcolor="#2b3540", darkcolor="#2b3540",
                        insertcolor=TEXT, padding=6)

        # 顶栏
        head = tk.Frame(self.root, bg=BG)
        head.pack(fill="x", padx=22, pady=(18, 10))
        tk.Label(head, text=APP_NAME, bg=BG, fg=TEXT, font=self.f_title).pack(side="left")
        tk.Label(head, text="  动态分段 · 断点续传 · 磁力 DHT · FTP · m3u8", bg=BG, fg=DIM, font=self.f_sub).pack(side="left", pady=(5, 0))

        self.btn_settings = self._ghost_button(head, "设置", self.open_settings)
        self.btn_settings.pack(side="right", padx=(8, 0))
        self.btn_folder = self._ghost_button(head, "下载目录", self.open_dest)
        self.btn_folder.pack(side="right")

        # 添加栏
        add = tk.Frame(self.root, bg=PANEL, highlightbackground=LINE, highlightthickness=1)
        add.pack(fill="x", padx=22, pady=(0, 8))
        inner = tk.Frame(add, bg=PANEL)
        inner.pack(fill="x", padx=12, pady=(11, 6))
        inner2 = tk.Frame(add, bg=PANEL)
        inner2.pack(fill="x", padx=12, pady=(0, 11))

        self.url_var = tk.StringVar()
        self.entry = tk.Entry(inner, textvariable=self.url_var, bg="#0b0f14", fg=TEXT,
                              insertbackground=TEXT, relief="flat", font=self.f_body,
                              highlightthickness=1, highlightbackground="#2b3540",
                              highlightcolor=TEAL)
        self.entry.pack(side="left", fill="x", expand=True, ipady=8, padx=(2, 10))
        self.entry.bind("<Return>", lambda e: self.add_task())
        self.entry.bind("<FocusIn>", self._clear_hint)
        self.entry.bind("<FocusOut>", self._restore_hint)
        self._hint_on = True
        self._set_hint()

        self.btn_add = tk.Button(inner, text="开始下载", command=self.add_task,
                                 bg=TEAL, fg="white", activebackground="#26856a",
                                 activeforeground="white", relief="flat", bd=0,
                                 font=self.f_body, padx=18, pady=7, cursor="hand2")
        self.btn_add.pack(side="right")
        self.btn_decode = tk.Button(inner, text="网页解码", command=self.decode_task,
                                    bg=PANEL2, fg=TEXT, activebackground="#2a333f",
                                    activeforeground=TEXT, relief="flat", bd=0,
                                    font=self.f_body, padx=14, pady=7, cursor="hand2")
        self.btn_decode.pack(side="right", padx=(0, 8))
        tk.Button(inner, text="粘贴", command=self.paste, bg=PANEL2, fg=TEXT,
                  activebackground="#2a333f", activeforeground=TEXT, relief="flat",
                  bd=0, font=self.f_body, padx=14, pady=7, cursor="hand2").pack(side="right", padx=(0, 8))

        # Referer 行（防盗链场景）
        tk.Label(inner2, text="Referer", bg=PANEL, fg=DIM2, font=self.f_small).pack(side="left")
        self.referer_var = tk.StringVar()
        rentry = tk.Entry(inner2, textvariable=self.referer_var, bg="#0b0f14", fg=TEXT,
                          insertbackground=TEXT, relief="flat", font=self.f_small,
                          highlightthickness=1, highlightbackground="#2b3540",
                          highlightcolor=TEAL)
        rentry.pack(side="left", fill="x", expand=True, ipady=5, padx=(8, 0))
        tk.Label(inner2, text="可选：防盗链站点填文件所在网页地址", bg=PANEL, fg=DIM2,
                 font=self.f_small).pack(side="left", padx=(10, 0))
        self.btn_browser = tk.Button(inner2, text="浏览器打开原页", command=self._open_last_page,
                                     bg=PANEL2, fg=DIM, activebackground="#2a333f",
                                     activeforeground=TEXT, relief="flat", bd=0,
                                     font=self.f_small, padx=10, pady=4,
                                     cursor="hand2", state="disabled")
        self.btn_browser.pack(side="right", padx=(0, 10))
        self.decode_var = tk.BooleanVar(value=True)
        tk.Checkbutton(inner2, text="自动解码网页链接 / 分享文案", variable=self.decode_var,
                       bg=PANEL, fg=DIM, selectcolor=PANEL2, activebackground=PANEL,
                       activeforeground=TEXT, font=self.f_small, anchor="w",
                       highlightthickness=0, bd=0, cursor="hand2").pack(side="right")

        # 工具栏：分类筛选 + 批量操作
        bar = tk.Frame(self.root, bg=BG)
        bar.pack(fill="x", padx=22, pady=(2, 6))
        self.cat_buttons = {}
        for i, cat in enumerate(CATEGORIES):
            b = tk.Button(bar, text=cat, command=lambda c=cat: self.set_filter(c),
                          bg=PANEL2 if cat == self.filter_cat else BG,
                          fg=TEXT if cat == self.filter_cat else DIM,
                          activebackground=PANEL2, activeforeground=TEXT, relief="flat",
                          bd=0, font=self.f_small, padx=12, pady=5, cursor="hand2")
            b.pack(side="left", padx=(0, 6))
            self.cat_buttons[cat] = b

        batch = tk.Frame(bar, bg=BG)
        batch.pack(side="right")
        self._batch_button(batch, "全选", self.select_all)
        self._batch_button(batch, "批量暂停", self.batch_pause)
        self._batch_button(batch, "批量继续", self.batch_resume)
        self._batch_button(batch, "批量删除", self.batch_delete, danger=True)

        # 提示条
        self.tip_var = tk.StringVar(value="")
        self.tip = tk.Label(self.root, textvariable=self.tip_var, bg=BG, fg=TEAL_L, font=self.f_small, anchor="w")
        self.tip.pack(fill="x", padx=26)

        # 任务列表
        body = tk.Frame(self.root, bg=BG)
        body.pack(fill="both", expand=True, padx=22, pady=(4, 0))
        self.canvas = tk.Canvas(body, bg=BG, highlightthickness=0, bd=0)
        self.vbar = ttk.Scrollbar(body, orient="vertical", command=self.canvas.yview,
                                 style="JF.Vertical.TScrollbar")
        self.canvas.configure(yscrollcommand=self.vbar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.vbar.pack(side="right", fill="y")
        self.list_frame = tk.Frame(self.canvas, bg=BG)
        self.win = self.canvas.create_window((0, 0), window=self.list_frame, anchor="nw")
        self.list_frame.bind("<Configure>", self._on_list_config)
        self.canvas.bind("<Configure>", self._on_canvas_config)
        self.canvas.bind_all("<MouseWheel>", self._on_wheel)

        self.empty = tk.Label(self.list_frame, text="粘贴一个下载链接，点「开始下载」\n支持 HTTP / HTTPS / m3u8，自动分段加速",
                              bg=BG, fg=DIM2, font=self.f_body, justify="center", pady=60)
        self.empty.pack(fill="x")

        # 底栏
        foot = tk.Frame(self.root, bg=BG)
        foot.pack(fill="x", padx=24, pady=(6, 12))
        self.foot_left = tk.Label(foot, text="就绪", bg=BG, fg=DIM, font=self.f_small)
        self.foot_left.pack(side="left")
        self.foot_right = tk.Label(foot, text="仅用于下载你有权获取的文件", bg=BG, fg=DIM2, font=self.f_small)
        self.foot_right.pack(side="right")

        # 右键菜单
        self.menu = tk.Menu(self.root, tearoff=0, bg=PANEL2, fg=TEXT, activebackground=TEAL,
                            activeforeground="white", font=self.f_small)
        self.menu.add_command(label="打开文件", command=self._open_selected)
        self.menu.add_command(label="打开所在目录", command=self._open_selected_dir)
        self.menu.add_command(label="复制链接", command=self._copy_selected_url)

    def _batch_button(self, parent, text, cmd, danger=False):
        return tk.Button(parent, text=text, command=cmd, bg=PANEL,
                         fg=RED if danger else DIM, activebackground=PANEL2,
                         activeforeground=TEXT, relief="flat", bd=0,
                         font=self.f_small, padx=10, pady=5, cursor="hand2").pack(side="left", padx=3)

    def _ghost_button(self, parent, text, cmd):
        return tk.Button(parent, text=text, command=cmd, bg=BG, fg=DIM,
                         activebackground=BG, activeforeground=TEXT, relief="flat",
                         bd=0, font=self.f_small, padx=10, pady=4, cursor="hand2")

    def _set_hint(self):
        if not self.url_var.get():
            self._hint_on = True
            self.url_var.set("粘贴下载链接，例如 https://example.com/package.zip")
            self.entry.configure(fg=DIM2)

    def _clear_hint(self, _=None):
        if self._hint_on:
            self._hint_on = False
            self.url_var.set("")
            self.entry.configure(fg=TEXT)

    def _restore_hint(self, _=None):
        if not self.url_var.get().strip():
            self._set_hint()

    def _real_url(self):
        if self._hint_on:
            return ""
        return self.url_var.get().strip()

    # ---------------------------------------------------------------- 布局事件
    def _on_list_config(self, _=None):
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _on_canvas_config(self, event):
        self.canvas.itemconfigure(self.win, width=event.width)

    def _on_wheel(self, event):
        try:
            self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")
        except Exception:
            pass

    # ---------------------------------------------------------------- 任务
    def _load_existing(self):
        loaded = self.manager.load_existing(self.cfg["dest_dir"])
        for t in loaded:
            self._make_card(t)
        self._apply_filter()
        if loaded:
            self.set_tip("发现 %d 个未完成任务，点「继续」可断点续传" % len(loaded))

    def add_task(self):
        raw = self._real_url()
        if not raw:
            self._clear_hint()
            self.entry.focus_set()
            return
        if self.decode_var.get():
            self._start_decode(raw)
        else:
            self._enqueue_direct(raw)

    def decode_task(self):
        raw = self._real_url()
        if not raw:
            self._clear_hint()
            self.entry.focus_set()
            return
        self._start_decode(raw)

    def _enqueue_direct(self, raw):
        is_magnet = engine.btm.looks_like_magnet(raw)
        is_ftp = bool(re.match(r"^ftp://", raw, re.I))
        if not is_magnet and not is_ftp and not re.match(r"^https?://", raw, re.I):
            messagebox.showwarning(APP_NAME, "只支持 http / https / ftp 链接和磁力链（magnet:?xt=urn:btih:...）")
            return
        referer = self.referer_var.get().strip() or None
        cookie = self.cfg.get("cookie") or None
        self.url_var.set("")
        self._hint_on = False
        self._set_hint()
        task = self.manager.add(raw, None, self.cfg["dest_dir"],
                                referer=referer, cookie=cookie)
        self._make_card(task)
        self._apply_filter()
        if task.kind == "bt":
            m = engine.btm.parse_magnet(raw) or {}
            trs = len(m.get("trackers") or [])
            self.set_tip("已加入队列：磁力下载（%s…，%d 个 tracker）" % ((m.get("hash_hex") or "?")[:8], trs)
                         if trs else "已加入队列：磁力下载（这条链没有 tracker，可能找不到源）")
        elif task.kind == "ftp":
            self.set_tip("已加入队列：FTP 下载（单连接，支持断点续传）")
        else:
            self.set_tip("已加入队列：" + task.host())

    def _start_decode(self, raw_text):
        if engine.btm.looks_like_magnet(raw_text):
            self._enqueue_direct(raw_text)
            return
        th = engine.decode_thunder(raw_text) if hasattr(engine, "decode_thunder") else None
        if th and engine.btm.looks_like_magnet(th):
            self._enqueue_direct(th)
            return
        cookie = self.cfg.get("cookie") or None
        extra = self.cfg.get("headers") or None
        self.set_tip("正在解析网页链接…")
        self.btn_add.configure(state="disabled")
        self.btn_decode.configure(state="disabled")

        def worker():
            total = 0
            try:
                r = sniffer.sniff(raw_text, cookie=cookie, extra_headers=extra)
                c = r.get("candidate")
                if r.get("ok") and c:
                    ref = c.get("referer") or r.get("page_url") or None
                    try:
                        probe = engine.probe(c["url"],
                                             extra_headers={"Referer": ref} if ref else None)
                        total = probe.get("total") or 0
                    except Exception:
                        total = 0
            except Exception as e:
                r = {"ok": False, "category": "invalid",
                     "message": "解析出错：%s" % e, "candidate": None,
                     "page_url": "", "url_chain": []}
            self.root.after(0, lambda: self._on_decode_done(raw_text, r, total))

        threading.Thread(target=worker, daemon=True).start()

    def _on_decode_done(self, raw_text, r, total):
        self.btn_add.configure(state="normal")
        self.btn_decode.configure(state="normal")
        c = r.get("candidate")
        self._last_page_url = r.get("page_url") or ""
        self.btn_browser.configure(state="normal" if self._last_page_url else "disabled")
        if r.get("ok") and c:
            manual = self.referer_var.get().strip()
            referer = manual or c.get("referer") or r.get("page_url") or None
            cookie = self.cfg.get("cookie") or None
            task = self.manager.add(c["url"], None, self.cfg["dest_dir"],
                                    referer=referer, cookie=cookie)
            self._make_card(task)
            self._apply_filter()
            self.set_tip(sniffer.describe(c, r.get("page_url", ""), total))
            self.url_var.set("")
            self._hint_on = False
            self._set_hint()
            return
        # 解码失败：平台级锁定只给文案 + 浏览器兜底；其余直链回退直接尝试，不静默
        cat = r.get("category")
        msg = r.get("message", "解析失败")
        raw = sniffer.extract_url(raw_text)
        if cat == "platform_locked":
            self.set_tip(msg)
        elif raw and re.match(r"^https?://", raw, re.I) and cat != "no_link":
            self.set_tip(msg + " · 已按原链接直接尝试")
            self._enqueue_direct(raw)
        else:
            self.set_tip(msg)

    def _open_last_page(self):
        if not self._last_page_url:
            return
        try:
            webbrowser.open(self._last_page_url)
        except Exception as e:
            self.set_tip("浏览器打开失败：%s" % e)

    def paste(self):
        try:
            text = self.root.clipboard_get().strip()
        except Exception:
            text = ""
        if text and sniffer.extract_url(text):
            self._clear_hint()
            self.url_var.set(text)
            self.entry.configure(fg=TEXT)
            self.set_tip("已粘贴剪贴板内容，可点「网页解码」或「开始下载」")
        else:
            self.set_tip("剪贴板里没有可用的链接")

    def _on_change(self, task):
        try:
            self.q.put(task.id)
        except Exception:
            pass

    def _poll(self):
        ids = set()
        try:
            while True:
                ids.add(self.q.get_nowait())
        except queue.Empty:
            pass
        for tid in ids:
            card = self.cards.get(tid)
            task = self._task_by_id(tid)
            if card and task:
                self._refresh_card(card, task)
        self._refresh_footer()
        self.root.after(400, self._poll)

    def _task_by_id(self, tid):
        for t in self.manager.tasks:
            if t.id == tid:
                return t
        return None

    def _refresh_footer(self):
        active = [t for t in self.manager.tasks if t.status in ("running", "preparing")]
        speed = sum(t.speed for t in active)
        if active:
            self.foot_left.configure(text="%d 个任务进行中 · %s" % (len(active), engine.human_speed(speed)))
        else:
            done = len([t for t in self.manager.tasks if t.status == "done"])
            self.foot_left.configure(text="已下载 %d 个文件" % done if done else "就绪")

    # ---------------------------------------------------------------- 筛选 / 选择
    def set_filter(self, cat):
        self.filter_cat = cat
        for c, b in self.cat_buttons.items():
            on = c == cat
            b.configure(bg=PANEL2 if on else BG, fg=TEXT if on else DIM)
        self._apply_filter()

    def _visible(self, task):
        if self.filter_cat == "全部":
            return True
        return engine.category_of(task.filename) == self.filter_cat

    def _apply_filter(self):
        any_visible = False
        for tid, card in self.cards.items():
            task = self._task_by_id(tid)
            if not task:
                continue
            if self._visible(task):
                card["frame"].pack(fill="x", pady=(0, 8))
                any_visible = True
            else:
                card["frame"].pack_forget()
        if self.empty.winfo_manager():
            self.empty.pack_forget()
        if not any_visible:
            self.empty.configure(text="该分类下暂无任务")
            self.empty.pack(fill="x")
        self.list_frame.update_idletasks()
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _selected_tasks(self):
        return [t for t in self.manager.tasks if t.id in self.selected]

    def select_all(self):
        for t in self.manager.tasks:
            if self._visible(t):
                self.selected.add(t.id)
        self._refresh_selection_styles()
        self.set_tip("已选中 %d 个任务" % len(self._selected_tasks()))

    def _toggle_select(self, task, ctrl):
        if ctrl:
            if task.id in self.selected:
                self.selected.discard(task.id)
            else:
                self.selected.add(task.id)
        else:
            self.selected = {task.id}
        self._refresh_selection_styles()

    def _refresh_selection_styles(self):
        for tid, card in self.cards.items():
            on = tid in self.selected
            card["frame"].configure(highlightcolor=TEAL if on else LINE,
                                    highlightbackground=TEAL if on else LINE)
            card["check"].configure(text="☑" if on else "□")

    # ---------------------------------------------------------------- 卡片
    def _make_card(self, task):
        if task.id in self.cards:
            return

        card = tk.Frame(self.list_frame, bg=PANEL, highlightbackground=LINE, highlightthickness=1)
        card.pack(fill="x", pady=(0, 8))

        top = tk.Frame(card, bg=PANEL)
        top.pack(fill="x", padx=14, pady=(11, 2))
        check = tk.Label(top, text="□", bg=PANEL, fg=TEAL_L, font=self.f_card, cursor="hand2")
        check.pack(side="left", padx=(0, 8))
        name = tk.Label(top, text=task.filename, bg=PANEL, fg=TEXT, font=self.f_card, anchor="w")
        name.pack(side="left", fill="x", expand=True)
        pill = tk.Label(top, text="排队中", bg=PANEL, fg=DIM, font=self.f_small, padx=8)
        pill.pack(side="right")

        sub = tk.Label(card, text=task.host(), bg=PANEL, fg=DIM2, font=self.f_small, anchor="w")
        sub.pack(fill="x", padx=14)

        bar = ttk.Progressbar(card, style="JF.Horizontal.TProgressbar", orient="horizontal",
                              mode="determinate", maximum=100, value=0)
        bar.pack(fill="x", padx=14, pady=(9, 4))

        info = tk.Frame(card, bg=PANEL)
        info.pack(fill="x", padx=14)
        meta = tk.Label(info, text="", bg=PANEL, fg=DIM, font=self.f_small, anchor="w")
        meta.pack(side="left", fill="x", expand=True)

        actions = tk.Frame(info, bg=PANEL)
        actions.pack(side="right")

        toggle = tk.Button(actions, text="暂停", command=lambda: self.toggle(task), bg=PANEL2, fg=TEXT,
                           activebackground="#2b3540", activeforeground=TEXT, relief="flat", bd=0,
                           disabledforeground="#4b5563", font=self.f_small, padx=10, pady=4, cursor="hand2")
        toggle.pack(side="left", padx=3)
        cancel = tk.Button(actions, text="取消", command=lambda: self.cancel(task), bg=PANEL, fg=DIM,
                           activebackground=PANEL, activeforeground=TEXT, relief="flat", bd=0,
                           font=self.f_small, padx=8, pady=4, cursor="hand2")
        cancel.pack(side="left", padx=2)
        openb = tk.Button(actions, text="打开", command=lambda: self.open_item(task), bg=PANEL, fg=DIM,
                          activebackground=PANEL, activeforeground=TEXT, relief="flat", bd=0,
                          font=self.f_small, padx=8, pady=4, cursor="hand2")
        openb.pack(side="left", padx=2)
        removeb = tk.Button(actions, text="移除", command=lambda: self.remove(task), bg=PANEL, fg=DIM2,
                            activebackground=PANEL, activeforeground=RED, relief="flat", bd=0,
                            font=self.f_small, padx=8, pady=4, cursor="hand2")
        removeb.pack(side="left", padx=(2, 0))

        err = tk.Label(card, text="", bg=PANEL, fg=RED, font=self.f_small, anchor="w", justify="left")
        err.pack(fill="x", padx=14, pady=(2, 0))
        note = tk.Label(card, text="", bg=PANEL, fg=AMBER, font=self.f_small, anchor="w", justify="left")
        note.pack(fill="x", padx=14, pady=(0, 8))

        # 交互：单击选中，Ctrl 多选；双击打开；右键菜单
        def bind(widget, evt="<Button-1>"):
            widget.bind(evt, lambda e: self._on_card_click(task, e))
        for w in (card, top, check, name, sub, bar, meta, err, note):
            bind(w)
        card.bind("<Double-Button-1>", lambda e: self.open_item(task))
        name.bind("<Double-Button-1>", lambda e: self.open_item(task))
        card.bind("<Button-3>", lambda e: self._popup_menu(task, e))
        name.bind("<Button-3>", lambda e: self._popup_menu(task, e))
        check.bind("<Button-1>", lambda e: self._toggle_select(task, True))

        self.cards[task.id] = {
            "frame": card, "name": name, "pill": pill, "sub": sub, "bar": bar,
            "meta": meta, "toggle": toggle, "open": openb, "err": err, "note": note,
            "check": check,
        }
        self._refresh_card(self.cards[task.id], task)
        self._refresh_selection_styles()
        self.list_frame.update_idletasks()
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _on_card_click(self, task, event):
        ctrl = bool(event.state & 0x0004)  # Ctrl
        self._toggle_select(task, ctrl)

    def _popup_menu(self, task, event):
        if task.id not in self.selected:
            self.selected = {task.id}
            self._refresh_selection_styles()
        self.menu.entryconfigure("打开文件", state="normal" if task.status == "done" else "disabled")
        try:
            self.menu.tk_popup(event.x_root, event.y_root)
        finally:
            self.menu.grab_release()

    def _refresh_card(self, card, task):
        c = STATUS_COLOR.get(task.status, DIM)
        card["pill"].configure(text=STATUS_TEXT.get(task.status, task.status), fg=c)
        card["name"].configure(text=task.filename)

        if task.total > 0:
            card["bar"].configure(mode="determinate", value=task.percent)
        else:
            card["bar"].configure(mode="determinate", value=0)

        parts = []
        if task.status == "running":
            parts.append(engine.human_speed(task.speed))
            if task.total > 0:
                parts.append("%.1f%%" % task.percent)
                parts.append("%s / %s" % (engine.human_size(task.downloaded), engine.human_size(task.total)))
            else:
                parts.append("已下载 " + engine.human_size(task.downloaded))
            if task.eta:
                parts.append("剩余 " + engine.human_eta(task.eta))
        elif task.status == "done":
            parts.append(engine.human_size(task.total))
            parts.append("已保存")
        elif task.status == "paused":
            parts.append("%s / %s" % (engine.human_size(task.downloaded), engine.human_size(task.total)))
        elif task.status == "error":
            parts.append("%s / %s" % (engine.human_size(task.downloaded), engine.human_size(task.total)))
        elif task.status in ("preparing", "pending"):
            parts.append("正在解析链接…" if task.status == "preparing" else "等待开始")
        else:
            parts.append(engine.human_size(task.downloaded))

        if task.kind == "m3u8":
            parts.append("m3u8 · %d/%d 分片" % (sum(1 for d in task.m3u8.get("done", []) if d), task.total_segments))
        elif task.kind == "bt":
            peers = getattr(task, "bt_peers", 0)
            parts.append("磁力 · 顺序分片" + (" · %d 个做种者" % peers if peers else ""))
        else:
            live = len(task.segments) if task.segments else task.segments_count
            parts.append("%d 线程" % max(1, live))
        card["meta"].configure(text="   ·   ".join(parts))

        card["sub"].configure(text=task.host())

        if task.status == "running":
            card["toggle"].configure(text="暂停", fg=TEXT, state="normal")
        elif task.status in ("paused", "error"):
            card["toggle"].configure(text="继续", fg=TEAL_L, state="normal")
        else:
            card["toggle"].configure(text="暂停", fg=DIM2, state="disabled")
        card["open"].configure(fg=TEXT if task.status == "done" else DIM2)
        card["err"].configure(text=task.error if task.status == "error" and task.error else "")
        card["note"].configure(text=getattr(task, "note", ""))

    # ---------------------------------------------------------------- 操作
    def toggle(self, task):
        if task.status == "running" or task.status == "preparing":
            self.manager.pause(task)
            self.set_tip("已暂停：" + task.filename)
        elif task.status in ("paused", "error"):
            self.manager.resume(task)
            self.set_tip("继续下载：" + task.filename)
        self._refresh_card(self.cards[task.id], task)

    def cancel(self, task):
        if task.status in ("done", "canceled"):
            return
        if messagebox.askyesno(APP_NAME, "取消下载并删除已下载的临时文件？\n" + task.filename):
            self.manager.cancel(task)
            self.set_tip("已取消：" + task.filename)

    def open_item(self, task):
        if task.status == "done" and os.path.exists(task.path):
            try:
                os.startfile(task.path)
            except Exception:
                self.open_dest()
            return
        self.open_dest()

    def _open_selected(self):
        tasks = self._selected_tasks()
        if tasks:
            self.open_item(tasks[0])

    def _open_selected_dir(self):
        tasks = self._selected_tasks()
        if tasks and os.path.exists(tasks[0].path):
            try:
                os.startfile(os.path.dirname(tasks[0].path))
                return
            except Exception:
                pass
        self.open_dest()

    def _copy_selected_url(self):
        tasks = self._selected_tasks()
        if tasks:
            self.root.clipboard_clear()
            self.root.clipboard_append(tasks[0].url)
            self.set_tip("链接已复制")

    def remove(self, task):
        if task.status in ("running", "preparing"):
            if not messagebox.askyesno(APP_NAME, "任务还在下载，先暂停并移除？"):
                return
        self.manager.remove(task, delete_file=False)
        self.selected.discard(task.id)
        card = self.cards.pop(task.id, None)
        if card:
            card["frame"].destroy()
        self._apply_filter()
        self.set_tip("已从列表移除")

    # ---------------- 批量 ----------------
    def batch_pause(self):
        tasks = [t for t in self._selected_tasks() if t.status in ("running", "preparing", "pending")]
        if not tasks:
            self.set_tip("选中的任务里没有可暂停的")
            return
        for t in tasks:
            self.manager.pause(t)
        self.set_tip("已暂停 %d 个任务" % len(tasks))

    def batch_resume(self):
        tasks = [t for t in self._selected_tasks() if t.status in ("paused", "error")]
        if not tasks:
            self.set_tip("选中的任务里没有可继续的")
            return
        for t in tasks:
            self.manager.resume(t)
        self.set_tip("已继续 %d 个任务" % len(tasks))

    def batch_delete(self):
        tasks = [t for t in self._selected_tasks() if t.status != "canceled"]
        if not tasks:
            self.set_tip("没有选中要删除的任务")
            return
        names = "\n".join("· " + t.filename for t in tasks[:10])
        if len(tasks) > 10:
            names += "\n…等 %d 个" % len(tasks)
        if not messagebox.askyesno(
                APP_NAME,
                "确定删除选中的 %d 个任务及其已下载文件？\n\n%s\n\n此操作不可恢复。" % (len(tasks), names)):
            return
        for t in tasks:
            self.manager.remove(t, delete_file=True)
            self.selected.discard(t.id)
            card = self.cards.pop(t.id, None)
            if card:
                card["frame"].destroy()
        self._apply_filter()
        self.set_tip("已删除 %d 个任务" % len(tasks))

    def open_dest(self):
        try:
            os.makedirs(self.cfg["dest_dir"], exist_ok=True)
            os.startfile(self.cfg["dest_dir"])
        except Exception as e:
            messagebox.showerror(APP_NAME, "打不开目录：%s" % e)

    def set_tip(self, text):
        self.tip_var.set(text)
        self.root.after(4200, lambda: self.tip_var.set("") if self.tip_var.get() == text else None)

    # ---------------------------------------------------------------- 剪贴板
    def _clip_tick(self):
        if self.cfg.get("clipboard"):
            try:
                text = self.root.clipboard_get().strip()
            except Exception:
                text = ""
            if text and text != self.clip_last:
                self.clip_last = text
                if sniffer.extract_url(text):
                    if not self._real_url() and self.root.focus_get() is not self.entry:
                        self._clear_hint()
                        self.url_var.set(text)
                        self.entry.configure(fg=TEXT)
                        self.set_tip("已从剪贴板抓取链接，点「网页解码」或「开始下载」")
        self.root.after(1200, self._clip_tick)

    # ---------------------------------------------------------------- 设置
    def open_settings(self):
        win = tk.Toplevel(self.root)
        win.title("设置")
        win.configure(bg=BG)
        win.geometry("500x560")
        win.transient(self.root)
        win.grab_set()
        win.resizable(False, False)

        pad = tk.Frame(win, bg=BG)
        pad.pack(fill="both", expand=True, padx=22, pady=18)

        def label(text):
            tk.Label(pad, text=text, bg=BG, fg=DIM, font=self.f_small, anchor="w").pack(fill="x", pady=(10, 3))

        label("分段数（自动：小文件 1 段，大文件按大小起步、被单连接限速时自动升段，上限 16）")
        seg_var = tk.StringVar(value="自动" if not self.cfg.get("segments") else str(self.cfg["segments"]))
        seg = ttk.Combobox(pad, textvariable=seg_var, style="JF.TEntry", font=self.f_body,
                           values=["自动", "2", "4", "8", "16"], state="readonly")
        seg.pack(fill="x", ipady=3)

        label("全局限速（KB/s，0 表示不限速；保存后立即生效）")
        rate_var = tk.StringVar(value=str(self.cfg.get("rate_kbps", 0)))
        rate = ttk.Spinbox(pad, from_=0, to=100000, textvariable=rate_var, style="JF.TSpinbox",
                           font=self.f_body, justify="left")
        rate.pack(fill="x", ipady=3)

        label("同时并行下载的任务数（1–4，默认 2）")
        active_var = tk.StringVar(value=str(self.cfg.get("max_active", 2)))
        active = ttk.Spinbox(pad, from_=1, to=4, textvariable=active_var, style="JF.TSpinbox",
                             font=self.f_body, justify="left")
        active.pack(fill="x", ipady=3)

        label("保存目录")
        dir_row = tk.Frame(pad, bg=BG)
        dir_row.pack(fill="x")
        dir_var = tk.StringVar(value=self.cfg["dest_dir"])
        dentry = ttk.Entry(dir_row, textvariable=dir_var, style="JF.TEntry", font=self.f_body)
        dentry.pack(side="left", fill="x", expand=True, ipady=6)

        def browse():
            p = filedialog.askdirectory(initialdir=dir_var.get() or DEFAULT_DIR)
            if p:
                dir_var.set(p)

        tk.Button(dir_row, text="浏览", command=browse, bg=PANEL2, fg=TEXT, relief="flat", bd=0,
                  font=self.f_small, padx=12, pady=6, cursor="hand2").pack(side="left", padx=(6, 0))

        label("全局 Cookie（需要登录的站点可填，格式：key=value; key2=value2）")
        cookie_var = tk.StringVar(value=self.cfg.get("cookie", ""))
        centry = ttk.Entry(pad, textvariable=cookie_var, style="JF.TEntry", font=self.f_small)
        centry.pack(fill="x", ipady=6)

        label("自定义请求头（每行一个「Key: Value」，可选）")
        headers_text = tk.Text(pad, bg="#0b0f14", fg=TEXT, insertbackground=TEXT, relief="flat",
                                font=self.f_small, height=4, highlightthickness=1,
                                highlightbackground="#2b3540", padx=6, pady=6)
        custom = self.cfg.get("headers") or {}
        headers_text.insert("1.0", "\n".join("%s: %s" % (k, v) for k, v in custom.items()))
        headers_text.pack(fill="x")

        label("HTTP User-Agent（部分站点需要浏览器 UA）")
        ua_var = tk.StringVar(value=self.cfg.get("ua", engine.DEFAULT_UA))
        uentry = ttk.Entry(pad, textvariable=ua_var, style="JF.TEntry", font=self.f_small)
        uentry.pack(fill="x", ipady=6)

        clip_var = tk.BooleanVar(value=bool(self.cfg.get("clipboard", True)))
        tk.Checkbutton(pad, text="监听剪贴板，发现文件链接时自动填入", variable=clip_var,
                       bg=BG, fg=TEXT, selectcolor=PANEL2, activebackground=BG,
                       activeforeground=TEXT, font=self.f_small, anchor="w",
                       highlightthickness=0, bd=0).pack(fill="x", pady=(14, 0))

        btns = tk.Frame(pad, bg=BG)
        btns.pack(fill="x", pady=(18, 0))

        def save():
            seg_raw = seg_var.get().strip()
            try:
                n_seg = 0 if seg_raw == "自动" else int(seg_raw)
                if n_seg and not 1 <= n_seg <= 16:
                    raise ValueError
                n_rate = int(rate_var.get())
                if n_rate < 0:
                    raise ValueError
                n_active = int(active_var.get())
                if not 1 <= n_active <= 4:
                    raise ValueError
            except Exception:
                messagebox.showwarning(APP_NAME, "分段数 / 限速 / 并行任务数填写有误，请检查", parent=win)
                return
            d = dir_var.get().strip() or DEFAULT_DIR
            try:
                os.makedirs(d, exist_ok=True)
            except Exception as e:
                messagebox.showerror(APP_NAME, "目录不可用：%s" % e, parent=win)
                return
            # 解析自定义请求头
            headers = {}
            for line in headers_text.get("1.0", "end").splitlines():
                line = line.strip()
                if not line or ":" not in line:
                    continue
                k, v = line.split(":", 1)
                k, v = k.strip(), v.strip()
                if k:
                    headers[k] = v
            self.cfg["segments"] = n_seg
            self.cfg["rate_kbps"] = n_rate
            self.cfg["max_active"] = n_active
            self.cfg["dest_dir"] = d
            self.cfg["clipboard"] = bool(clip_var.get())
            self.cfg["ua"] = ua_var.get().strip() or engine.DEFAULT_UA
            self.cfg["cookie"] = cookie_var.get().strip()
            self.cfg["headers"] = headers
            self.manager.cfg = self.cfg
            self.manager.set_rate_limit_kbps(n_rate)
            self.manager.set_max_active(n_active)
            save_cfg(self.cfg)
            win.destroy()
            self.set_tip("设置已保存，限速与并行数立即生效")

        tk.Button(btns, text="保存", command=save, bg=TEAL, fg="white", relief="flat", bd=0,
                  font=self.f_body, padx=26, pady=7, cursor="hand2").pack(side="right")
        tk.Button(btns, text="取消", command=win.destroy, bg=PANEL2, fg=TEXT, relief="flat", bd=0,
                  font=self.f_body, padx=22, pady=7, cursor="hand2").pack(side="right", padx=(0, 8))

    # ---------------------------------------------------------------- 关闭
    def on_close(self):
        running = [t for t in self.manager.tasks if t.status in ("running", "preparing")]
        if running:
            if not messagebox.askyesno(APP_NAME, "还有 %d 个任务在下载，暂停并退出？" % len(running)):
                return
            for t in running:
                self.manager.pause(t)
        for t in self.manager.tasks:
            t.save_state(force=True)
        self.root.destroy()


def resource_path(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def main():
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass
    root = tk.Tk()
    ico = resource_path("app.ico")
    if os.path.exists(ico):
        try:
            root.iconbitmap(ico)
        except Exception:
            pass
    app = App(root)
    root.protocol("WM_DELETE_WINDOW", app.on_close)
    url_arg = next((a for a in sys.argv[1:] if re.match(r"^https?://", a, re.I) or engine.btm.looks_like_magnet(a)), None)
    if url_arg:
        app.url_var.set(url_arg)
        app.entry.configure(fg=TEXT)
        app._hint_on = False
        app.root.after(600, app.add_task)
    root.mainloop()


if __name__ == "__main__":
    main()
