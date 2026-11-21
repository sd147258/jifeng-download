# -*- coding: utf-8 -*-
"""
疾风下载 · 下载引擎
不依赖 tkinter，方便单独测试。支持：
  - HTTP/HTTPS 多线程分段下载（Range）
  - 断点续传（分段进度写入 sidecar JSON）
  - 服务器不支持 Range 时自动退回单线程
  - Content-Type 校验：拒绝 HTML / JSON / JS 等网页与接口错误页
  - Referer / Cookie / 自定义请求头（防盗链场景）
  - 网络类错误指数退避重试；403/404/410 等不重试并给出中文原因
  - 完成后 Content-Length 校验 + 文件头嗅探修正扩展名 / 识别错误页
  - m3u8 播放列表：master 选最高带宽，分片 4 路并发合并
  - 分段数自适应、最多 2 个任务并行、全局限速令牌桶
纯标准库实现（urllib），不引入第三方 HTTP 库。
"""

import concurrent.futures
import http.client
import json
import os
import re
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import bt as btm
import ftp as ftpmod


def decode_thunder(text):
    return btm.decode_thunder(text)

CHUNK = 256 * 1024
READ_BUFFER = 512 * 1024
MAX_AUTO_SEGMENTS = 16       # 自适应模式下动态升段的上限
CONNECT_TIMEOUT = 15         # 建连超时（与读超时分开）
READ_TIMEOUT = 30           # 单段读超时；超时后该段从断点续传
SPLIT_MIN_BYTES = 512 * 1024  # 剩余区间大于该值才允许二分拆分
DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
PART_SUFFIX = ".jf.json"
MIN_SEG_BYTES = 256 * 1024
TAIL_MIN_BYTES = 128 * 1024   # 连接复用：剩余段小于该值就不再切

# 视为“网页 / 接口数据”、不可落盘的 Content-Type 顶层类型
REJECT_CONTENT_TYPES = {
    "text/html", "application/xhtml+xml", "application/xml",
    "application/json", "text/json", "text/javascript",
    "application/javascript", "application/ecmascript", "text/css",
}

RETRYABLE_STATUSES = {429, 500, 502, 503, 504}
NON_RETRYABLE_STATUSES = {400, 401, 403, 404, 410, 416}
MAX_ATTEMPTS = 3  # 网络类 / 5xx / 429 最多重试次数（不含首次）

VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".ts", ".m4v"}
AUDIO_EXTS = {".mp3", ".flac", ".wav", ".aac", ".m4a", ".ogg", ".opus", ".ape"}
ARCHIVE_EXTS = {".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz", ".iso", ".apk", ".exe", ".msi", ".dmg"}
DOC_EXTS = {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".epub", ".mobi", ".csv", ".srt", ".ass", ".txt"}


class RangeUnsupported(Exception):
    pass


class DownloadError(Exception):
    """带中文原因的下载错误。"""


class HttpStatusError(DownloadError):
    def __init__(self, status, reason=""):
        self.status = status
        super().__init__(reason or ("HTTP %d" % status))


# ---------------------------------------------------------------- 工具

def human_size(n):
    if n is None or n < 0:
        return "—"
    units = ["B", "KB", "MB", "GB", "TB"]
    v = float(n)
    i = 0
    while v >= 1024 and i < len(units) - 1:
        v /= 1024.0
        i += 1
    if i == 0:
        return "%d %s" % (n, units[i])
    return "%.1f %s" % (v, units[i])


def human_speed(n):
    if not n or n <= 0:
        return "0 B/s"
    return human_size(int(n)) + "/s"


def human_eta(sec):
    if sec is None or sec < 0 or sec > 86400 * 3:
        return "—"
    sec = int(sec)
    if sec < 60:
        return "%d 秒" % sec
    if sec < 3600:
        return "%d 分 %02d 秒" % (sec // 60, sec % 60)
    return "%d 小时 %02d 分" % (sec // 3600, (sec % 3600) // 60)


_ILLEGAL = re.compile(r'[\\/:*?"<>|\r\n\t]')


def safe_filename(name):
    name = (name or "").strip().strip(".")
    name = _ILLEGAL.sub("_", name)
    name = re.sub(r"\s+", " ", name)
    if not name:
        name = "download"
    if len(name) > 180:
        stem, ext = os.path.splitext(name)
        name = stem[:150] + ext
    return name


def unique_path(folder, filename):
    base, ext = os.path.splitext(filename)
    path = os.path.join(folder, filename)
    i = 1
    while os.path.exists(path) or os.path.exists(path + PART_SUFFIX):
        path = os.path.join(folder, "%s (%d)%s" % (base, i, ext))
        i += 1
    return path


# ---------------------------------------------------------------- 类型 / 文件头识别

CONTENT_TYPE_EXT = {