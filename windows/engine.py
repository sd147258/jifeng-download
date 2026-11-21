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
    "video/mp4": ".mp4", "video/quicktime": ".mov", "video/x-flv": ".flv",
    "video/x-matroska": ".mkv", "video/webm": ".webm", "video/avi": ".avi",
    "audio/mp4": ".m4a", "audio/mpeg": ".mp3", "audio/x-mpeg": ".mp3",
    "audio/flac": ".flac", "audio/ogg": ".ogg", "audio/wav": ".wav",
    "audio/aac": ".aac", "application/pdf": ".pdf",
    "video/x-ms-wmv": ".wmv", "video/x-ms-asf": ".wmv",
    "application/vnd.ms-asf": ".wmv", "application/x-ms-asf": ".wmv",
    "audio/x-ms-wma": ".wma",
    "application/zip": ".zip", "application/x-zip-compressed": ".zip",
    "application/x-rar-compressed": ".rar", "application/vnd.rar": ".rar",
    "application/x-7z-compressed": ".7z", "application/gzip": ".gz",
    "application/x-tar": ".tar", "image/jpeg": ".jpg", "image/png": ".png",
    "image/gif": ".gif", "image/webp": ".webp",
}


def ext_from_content_type(ctype):
    """按 Content-Type 推断扩展名；推断不出用 .bin，永远不落 .txt。"""
    ctype = (ctype or "").split(";")[0].strip().lower()
    if not ctype:
        return ".bin"
    return CONTENT_TYPE_EXT.get(ctype, ".bin")


def ext_from_head(head):
    """按文件头魔数判断扩展名，判断不出返回空串。"""
    if not head:
        return ""
    b = head
    if len(b) >= 12 and b[4:8] == b"ftyp":
        return ".mp4"
    if b[:3] == b"ID3":
        return ".mp3"
    if len(b) >= 2 and b[0] == 0xFF and (b[1] & 0xE0) == 0xE0:
        return ".mp3"
    if b[:4] == b"\x1a\x45\xdf\xa3":
        return ".mkv"
    if b[:2] == b"PK" and len(b) >= 3 and b[2] in (3, 5, 7):
        return ".zip"
    if b[:6] == b"Rar!\x1a\x07":
        return ".rar"
    if b[:6] == b"7z\xbc\xaf\x27\x1c":
        return ".7z"
    if b[:5] == b"%PDF-":
        return ".pdf"
    if b[:4] == b"fLaC":
        return ".flac"
    if b[:4] == b"OggS":
        return ".ogg"
    if b[:4] == b"RIFF" and b[8:12] == b"AVI ":
        return ".avi"
    if b[:16] == b"\x30\x26\xb2\x75\x8e\x66\xcf\x11\xa6\xd9\x00\xaa\x00\x62\xce\x6c":
        return ".wmv"
    if b[:2] == b"\x1f\x8b":
        return ".gz"
    return ""


def sniff_web_body(head):
    """文件头明显是 HTML / JSON 错误页时返回类型标记，否则 None。"""
    if not head:
        return None
    stripped = head.lstrip()
    low = stripped.lower()
    if low.startswith(b"<!doctype") or low.startswith(b"<html") or low.startswith(b"<!--"):
        return "html"
    if low.startswith(b"<head") or low.startswith(b"<body"):
        return "html"
    if stripped[:1] in (b"{", b"["):
        return "json"
    return None


def category_of(filename):
    ext = os.path.splitext(filename)[1].lower()
    if ext in VIDEO_EXTS:
        return "视频"
    if ext in AUDIO_EXTS:
        return "音频"
    if ext in ARCHIVE_EXTS:
        return "压缩包"
    if ext in DOC_EXTS:
        return "文档"
    return "其他"


def filename_from_headers(url, headers):
    cd = headers.get("Content-Disposition") or headers.get("content-disposition") or ""
    name = ""
    if cd:
        m = re.search(r"filename\*\s*=\s*([^;]+)", cd, re.I)
        if m:
            raw = m.group(1).strip().strip('"')
            try:
                if "''" in raw:
                    raw = raw.split("''", 1)[1]
                name = urllib.parse.unquote(raw)
            except Exception:
                name = raw
        if not name:
            m = re.search(r'filename\s*=\s*"([^"]+)"', cd, re.I)
            if m:
                name = m.group(1)
            else:
                m = re.search(r"filename\s*=\s*([^;]+)", cd, re.I)
                if m:
                    name = m.group(1).strip().strip('"')
    if name:
        try:
            name = name.encode("latin-1").decode("utf-8")
        except Exception:
            pass
        return safe_filename(name)

    path = urllib.parse.urlparse(url).path
    base = os.path.basename(urllib.parse.unquote(path))
    ctype = (headers.get("Content-Type") or headers.get("content-type") or "")
    if base:
        if os.path.splitext(base)[1]:
            return safe_filename(base)
        # URL 没有扩展名：按 Content-Type 推断，推断不出用 .bin，绝不落 .txt
        return safe_filename(base) + ext_from_content_type(ctype)

    return "download" + ext_from_content_type(ctype)


# ---------------------------------------------------------------- HTTP

def _normalize_headers(extra):
    """把请求头键名规范化，方便统一取 Referer / Cookie。"""
    out = {}
    if extra:
        for k, v in extra.items():
            if v is None:
                continue
            out[str(k).strip()] = str(v)
    return out


def _build_headers(url, ua, extra=None, range_bytes=None, etag=None, keep_alive=False):
    h = {
        "User-Agent": ua or DEFAULT_UA,
        "Accept": "*/*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Accept-Encoding": "identity",
        "Connection": "keep-alive" if keep_alive else "close",
    }
    extra = _normalize_headers(extra)
    # 规范大小写后写入
    for key in ("Referer", "referer", "Cookie", "cookie", "Origin", "Authorization"):
        if key in extra:
            h[key if key[0].isupper() else "Referer" if key.lower() == "referer" else "Cookie"] = extra[key]
    for k, v in extra.items():
        if k not in ("Referer", "referer", "Cookie", "cookie"):
            h[k] = v
    if range_bytes:
        h["Range"] = range_bytes
    if etag:
        h["If-Range"] = etag
    return h


def _status_reason(status):
    if status == 403:
        return ("服务器拒绝访问（403）：资源可能有防盗链保护。请在下载框旁填写对应页面的 "
                "Referer（打开文件所在网页，复制浏览器地址栏 URL），或确认是否需要登录后再试")
    if status == 404:
        return "链接失效（404）：文件不存在，或分享链接 / 下载地址已过期"
    if status == 410:
        return "文件已被删除（410）：链接已永久失效，无法继续下载"
    if status == 401:
        return "需要登录（401）：请先在浏览器登录账号，复制带登录 Cookie 的链接，或在设置里填写 Cookie"
    if status == 416:
        return "请求的下载范围超出文件大小，服务器不支持该分段，请重试"
    if status == 400:
        return "请求地址无效（400）：请检查链接是否完整、有没有缺参数"
    if status in RETRYABLE_STATUSES:
        return "服务器临时繁忙（HTTP %d），已多次重试仍未成功，稍后再试" % status
    return "服务器返回错误（HTTP %d）" % status


def _is_network_error(e):
    if isinstance(e, HttpStatusError):
        return False
    low = str(e).lower()
    if isinstance(e, (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionResetError,
                      ConnectionRefusedError, BrokenPipeError, OSError)):
        return True
    if any(k in low for k in ("timed out", "timeout", "reset", "refused", "eof",
                              "connection", "temporarily", "getaddrinfo", "ssl")):
        return True
    return False


def open_url(url, headers, timeout=30, max_attempts=MAX_ATTEMPTS):
    """带指数退避重试的 GET。403/404/410/401/416 不重试，直接抛中文错误。"""
    last_exc = None
    for attempt in range(max_attempts + 1):
        resp = None
        try:
            req = urllib.request.Request(url, headers=headers)
            resp = urllib.request.urlopen(req, timeout=timeout)
            status = getattr(resp, "status", 200)
            if status in NON_RETRYABLE_STATUSES:
                raise HttpStatusError(status, _status_reason(status))
            if status in RETRYABLE_STATUSES:
                raise HttpStatusError(status, _status_reason(status))
            return resp
        except HttpStatusError:
            if resp is not None:
                try:
                    resp.close()
                except Exception:
                    pass
            raise
        except urllib.error.HTTPError as e:
            status = e.code
            try:
                e.close()
            except Exception:
                pass
            if status in NON_RETRYABLE_STATUSES:
                raise HttpStatusError(status, _status_reason(status))
            last_exc = HttpStatusError(status, _status_reason(status))
            if attempt >= max_attempts:
                raise last_exc
        except Exception as e:  # 网络类错误
            last_exc = e
            if resp is not None:
                try:
                    resp.close()
                except Exception:
                    pass
            if attempt >= max_attempts:
                raise DownloadError(_friendly_error(e))
        time.sleep(min(8.0, 0.8 * (2 ** attempt)))
    raise DownloadError(_friendly_error(last_exc))


# ---------------------------------------------------------------- 连接池
# 分段下载复用 Keep-Alive 连接，避免每段/每次重试都重走三次握手；
# 建连超时与读超时分开：慢连接在读超时后按段从断点续传，不会拖死整段。

class _ConnPool:
    def __init__(self):
        self._lock = threading.Lock()
        self._free = {}

    @staticmethod
    def _new(scheme, host, port, connect_timeout):
        if scheme == "https":
            ctx = ssl.create_default_context()
            conn = http.client.HTTPSConnection(host, port, timeout=connect_timeout, context=ctx)
        else:
            conn = http.client.HTTPConnection(host, port, timeout=connect_timeout)
        conn.connect()
        sock = conn.sock
        if sock is not None:
            try:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, READ_BUFFER)
            except Exception:
                pass
            sock.settimeout(READ_TIMEOUT)
        return conn

    def acquire(self, scheme, host, port, connect_timeout):
        key = (scheme, host, port)
        with self._lock:
            pool = self._free.get(key)
            if pool:
                while pool:
                    conn = pool.pop()
                    try:
                        if conn.sock is None:
                            raise OSError("closed")
                        conn.sock.settimeout(READ_TIMEOUT)
                        return conn
                    except Exception:
                        try:
                            conn.close()
                        except Exception:
                            pass
        return self._new(scheme, host, port, connect_timeout)

    def release(self, conn, key, drop=False):
        if drop:
            try:
                conn.close()
            except Exception:
                pass
            return
        try:
            with self._lock:
                self._free.setdefault(key, []).append(conn)
        except Exception:
            try:
                conn.close()
            except Exception:
                pass


_POOL = _ConnPool()


class _PooledResponse:
    """对齐 urllib 响应的最小接口：status / read(n) / getheader / close。"""

    def __init__(self, conn, resp, pool, key, reusable):
        self.conn = conn
        self.resp = resp
        self.pool = pool
        self.key = key
        self.status = resp.status
        self.reusable = reusable
        self._drop = False

    def read(self, n=-1):
        return self.resp.read(n)

    def getheader(self, name, default=None):
        return self.resp.getheader(name, default)

    def drop(self):
        self._drop = True

    def close(self):
        if self._drop or not self.reusable:
            self.pool.release(self.conn, self.key, drop=True)
        else:
            self.pool.release(self.conn, self.key)


def pooled_get(url, headers, connect_timeout=CONNECT_TIMEOUT, read_timeout=READ_TIMEOUT):
    """走连接池发起 GET；遇到重定向退回 urllib（跟随跳转）。"""
    p = urllib.parse.urlparse(url)
    scheme = (p.scheme or "http").lower()
    host = p.hostname
    port = p.port or (443 if scheme == "https" else 80)
    path = p.path or "/"
    if p.query:
        path += "?" + p.query
    key = (scheme, host, port)
    conn = _POOL.acquire(scheme, host, port, connect_timeout)
    try:
        conn.sock.settimeout(read_timeout)
        conn.request("GET", path, headers=headers)
        resp = conn.getresponse()
    except Exception:
        _POOL.release(conn, key, drop=True)
        raise
    if resp.status in (301, 302, 303, 307, 308):
        loc = resp.getheader("Location")
        _POOL.release(conn, key, drop=True)
        if loc:
            loc = urllib.parse.urljoin(url, loc)
            return open_url(loc, headers, timeout=read_timeout)
        raise HttpStatusError(resp.status, _status_reason(resp.status))
    reusable = (headers.get("Connection", "").lower() == "keep-alive")
    return _PooledResponse(conn, resp, _POOL, key, reusable)


def probe(url, ua=DEFAULT_UA, timeout=20, extra_headers=None):
    """探测大小 / 是否支持分段 / 文件名 / 类型；错误页直接拒绝。"""
    info = {"total": -1, "ranges": False, "filename": "", "etag": "", "type": "", "kind": "file"}
    headers = _build_headers(url, ua, extra_headers, range_bytes="bytes=0-0")
    req = urllib.request.Request(url, headers=headers)
    resp = None
    try:
        try:
            resp = urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError as e:
            try:
                e.close()
            except Exception:
                pass
            raise HttpStatusError(e.code, _status_reason(e.code))
        status = getattr(resp, "status", 200)
        h = resp.headers
        ctype = (h.get("Content-Type") or "").split(";")[0].strip().lower()

        if is_m3u8(url, ctype):
            info["kind"] = "m3u8"
            info["type"] = ctype
            info["filename"] = _m3u8_default_name(url)
            return info

        if status in NON_RETRYABLE_STATUSES:
            raise HttpStatusError(status, _status_reason(status))

        if ctype in REJECT_CONTENT_TYPES:
            raise DownloadError(
                "这个链接返回的是网页 / 接口数据（Content-Type: %s），不是文件："
                "可能需要登录、链接已过期，或被防盗链拦截。请打开原网页核对地址，"
                "或填写对应的 Referer / Cookie 后重试" % ctype)

        total = -1
        ranges = False
        if status == 206:
            ranges = True
            cr = h.get("Content-Range") or ""
            m = re.search(r"/(\d+)$", cr.strip())
            if m:
                total = int(m.group(1))
        else:
            try:
                total = int(h.get("Content-Length") or -1)
            except Exception:
                total = -1
        info["total"] = total
        info["ranges"] = ranges and total > 0
        info["filename"] = filename_from_headers(url, h)
        info["etag"] = h.get("ETag") or ""
        info["type"] = ctype
        return info
    finally:
        if resp is not None:
            try:
                resp.close()
            except Exception:
                pass


def is_m3u8(url, ctype=""):
    path = urllib.parse.urlparse(url).path.lower()
    if path.endswith(".m3u8"):
        return True
    return (ctype or "").lower() in (
        "application/vnd.apple.mpegurl", "application/x-mpegurl",
        "audio/mpegurl", "video/mpegurl", "application/mpegurl")


def _m3u8_default_name(url):
    base = os.path.basename(urllib.parse.unquote(urllib.parse.urlparse(url).path))
    stem = os.path.splitext(base)[0] if base else "video"
    return safe_filename(stem) + ".mp4"


# ---------------------------------------------------------------- 分段规划

def plan_segments(total, count):
    if total <= 0:
        return [Segment(0, -1)]
    count = max(1, int(count))
    count = min(count, max(1, total // MIN_SEG_BYTES))
    segs = []
    pos = 0
    for i in range(count):
        if i == count - 1:
            end = total - 1
        else:
            end = pos + (total // count) - 1
        segs.append(Segment(pos, end))
        pos = end + 1
    return segs


def adaptive_segment_count(total, ranges, prefer=0):
    """<2MB→1，<8MB→2，<64MB→4，<200MB→8，≥200MB→16；不支持 Range 退回 1。"""
    if not ranges or total <= 0:
        return 1
    if prefer and int(prefer) > 0:
        count = int(prefer)
    elif total < 2 * 1024 * 1024:
        count = 1
    elif total < 8 * 1024 * 1024:
        count = 2
    elif total < 64 * 1024 * 1024:
        count = 4
    elif total < 200 * 1024 * 1024:
        count = 8
    else:
        count = 16
    count = min(count, max(1, total // MIN_SEG_BYTES))
    return max(1, count)


class Segment:
    __slots__ = ("start", "end", "done")

    def __init__(self, start, end, done=0):
        self.start = start
        self.end = end          # inclusive; -1 表示未知长度（读到 EOF）
        self.done = done

    @property
    def size(self):
        if self.end < 0:
            return -1
        return self.end - self.start + 1

    def remaining(self):
        if self.end < 0:
            return -1
        return max(0, self.size - self.done)

    def to_dict(self):
        return {"start": self.start, "end": self.end, "done": self.done}

    @classmethod
    def from_dict(cls, d):
        return cls(int(d["start"]), int(d["end"]), int(d.get("done", 0)))


# ---------------------------------------------------------------- m3u8

def fetch_text(url, headers, timeout=20):
    resp = open_url(url, headers, timeout=timeout)
    try:
        body = resp.read()
    finally:
        resp.close()
    return body.decode("utf-8", errors="replace")


def parse_master(text):
    """返回 [(bandwidth, uri), ...]，按带宽降序。"""
    variants = []
    pending_bw = None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("#EXT-X-STREAM-INF"):
            m = re.search(r"BANDWIDTH=(\d+)", s)
            pending_bw = int(m.group(1)) if m else 0
        elif s and not s.startswith("#") and pending_bw is not None:
            variants.append((pending_bw, s))
            pending_bw = None
    variants.sort(key=lambda x: x[0], reverse=True)
    return variants


def parse_media_playlist(text):
    """返回 (map_uri, [segment_uri])。"""
    segments = []
    map_uri = None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("#EXT-X-MAP"):
            m = re.search(r'URI="([^"]+)"', s)
            if m:
                map_uri = m.group(1)
        elif s and not s.startswith("#"):
            segments.append(s)
    return map_uri, segments


def resolve_url(base, ref):
    return urllib.parse.urljoin(base, ref)


def find_ffmpeg():
    p = shutil.which("ffmpeg")
    if p:
        return p
    candidates = [
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "ffmpeg.exe"),
        os.path.join(getattr(sys, "_MEIPASS", ""), "ffmpeg.exe"),
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return None


# ---------------------------------------------------------------- 任务

class Task:
    def __init__(self, url, filename=None, dest_dir=".", segments=8, ua=DEFAULT_UA,
                 extra_headers=None, tid=None, kind="file"):
        self.id = tid or uuid.uuid4().hex[:12]
        self.url = url
        self.kind = kind          # file / m3u8 / bt
        self.bt_hash = ""
        self.bt_peers = 0
        self.segments_count = max(1, int(segments or 1))
        self.restart_requested = False
        self.requested_name = filename
        if filename:
            self.filename = safe_filename(filename)
        else:
            base = os.path.basename(urllib.parse.unquote(urllib.parse.urlparse(url).path))
            self.filename = safe_filename(base) if base else "download"
        self.dest_dir = dest_dir
        self.segments = []
        self.total = -1
        self.downloaded = 0
        self.status = "pending"   # pending/preparing/running/paused/done/error/canceled
        self.error = ""
        self.note = ""
        self.supports_range = False
        self.etag = ""
        self.content_type = ""
        self.ua = ua or DEFAULT_UA
        self.extra_headers = _normalize_headers(extra_headers)
        self.created_at = time.time()
        self.speed = 0.0
        self.lock = threading.RLock()
        self.stop_evt = threading.Event()
        self.generation = 0
        self._fh = None
        self.threads = []
        self.futures = []
        self._executor = None
        self.auto_split = False
        self.started_at = 0.0
        self.last_saved = 0.0
        self._last_downloaded = 0
        self._last_speed_t = time.time()
        # m3u8 状态
        self.m3u8 = {"map": "", "segments": [], "done": [], "sizes": []}
        self.total_segments = 0

    # ---------- 路径 / 状态 ----------
    @property
    def path(self):
        return os.path.join(self.dest_dir, self.filename)

    @property
    def part_path(self):
        return self.path + PART_SUFFIX

    @property
    def parts_dir(self):
        return self.path + ".jfparts"

    @property
    def percent(self):
        if self.kind == "m3u8" and self.total_segments > 0:
            done = sum(1 for d in self.m3u8.get("done", []) if d)
            return min(100.0, done * 100.0 / self.total_segments)
        if self.total <= 0:
            return 0.0
        return min(100.0, self.downloaded * 100.0 / self.total)

    @property
    def eta(self):
        if self.total <= 0 or self.speed <= 0:
            return None
        return max(0, (self.total - self.downloaded) / self.speed)

    def host(self):
        if self.kind == "bt":
            node = getattr(self, "bt_hash", "") or "磁力下载"
            return "磁力 · " + node
        try:
            return urllib.parse.urlparse(self.url).netloc
        except Exception:
            return ""

    def remaining(self):
        if self.total <= 0:
            return -1
        return max(0, self.total - self.downloaded)

    # ---------- 文件 ----------
    def open_file(self, mode="r+b"):
        if self._fh is None:
            # 带 128KB 写缓冲；不逐块 fsync，只在状态保存 / 收尾时落盘
            self._fh = open(self.path, mode, buffering=128 * 1024)
        return self._fh


    def close_file(self):
        if self._fh is not None:
            try:
                self._fh.flush()
                os.fsync(self._fh.fileno())
            except Exception:
                pass
            try:
                self._fh.close()
            except Exception:
                pass
            self._fh = None

    # ---------- 状态持久化 ----------
    def save_state(self, force=False):
        now = time.time()
        if not force and now - self.last_saved < 1.5:
            return
        self.last_saved = now
        data = {
            "id": self.id,
            "url": self.url,
            "kind": self.kind,
            "filename": self.filename,
            "total": self.total,
            "supports_range": self.supports_range,
            "etag": self.etag,
            "content_type": self.content_type,
            "status": self.status,
            "segments": [s.to_dict() for s in self.segments],
            "ua": self.ua,
            "extra_headers": self.extra_headers,
            "m3u8": self.m3u8,
            "total_segments": self.total_segments,
        }
        tmp = self.part_path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False)
            os.replace(tmp, self.part_path)
        except Exception:
            pass

    def cleanup_part(self):
        for p in (self.part_path, self.part_path + ".tmp"):
            try:
                if os.path.exists(p):
                    os.remove(p)
            except Exception:
                pass
        try:
            if os.path.isdir(self.parts_dir):
                shutil.rmtree(self.parts_dir, ignore_errors=True)
        except Exception:
            pass

    def delete_output(self):
        """删除主输出文件 + 断点临时文件。"""
        self.close_file()
        for p in (self.path, self.part_path, self.part_path + ".tmp"):
            try:
                if p and os.path.exists(p):
                    os.remove(p)
            except Exception:
                pass
        try:
            if os.path.isdir(self.parts_dir):
                shutil.rmtree(self.parts_dir, ignore_errors=True)
        except Exception:
            pass

    def seg_complete(self):
        if not self.segments:
            return False
        for s in self.segments:
            if s.end < 0:
                return False
            if s.done < s.size:
                return False
        return True


class Manager:
    """任务调度：队列（最多 2 个并行）→ 探测 → 分段 → 多线程下载 → 续传 / 暂停 / 取消。"""

    def __init__(self, cfg=None, on_change=None):
        self.cfg = cfg or {}
        self.on_change = on_change
        self.tasks = []
        self.lock = threading.RLock()
        self.cond = threading.Condition(self.lock)
        self.max_active = max(1, int(self.cfg.get("max_active", 2)))
        self.rate_limit = max(0, int(self.cfg.get("rate_kbps", 0) or 0)) * 1024
        self._bucket_lock = threading.Lock()
        self._tokens = float(self.rate_limit)
        self._bucket_t = time.time()
        self._sched_stop = threading.Event()
        threading.Thread(target=self._scheduler, daemon=True).start()

    # ---------------- 配置热更新 ----------------
    def set_rate_limit_kbps(self, kbps):
        self.rate_limit = max(0, int(kbps or 0)) * 1024
        with self._bucket_lock:
            self._tokens = float(self.rate_limit)
            self._bucket_t = time.time()

    def set_max_active(self, n):
        self.max_active = max(1, int(n or 2))
        self.cond.notify()

    # ---------------- 通知 ----------------
    def notify(self, task):
        cb = self.on_change
        if cb:
            try:
                cb(task)
            except Exception:
                pass
        with self.cond:
            self.cond.notify()

    # ---------------- 令牌桶限速 ----------------
    def throttle(self, n):
        limit = self.rate_limit
        if not limit or n <= 0:
            return
        with self._bucket_lock:
            now = time.time()
            elapsed = now - self._bucket_t
            self._tokens = min(float(limit), self._tokens + elapsed * limit)
            self._bucket_t = now
            if self._tokens >= n:
                self._tokens -= n
                return
            need = n - self._tokens
            time.sleep(need / limit)
            self._tokens = 0.0
            self._bucket_t = time.time()

    # ---------------- 队列调度 ----------------
    def _scheduler(self):
        while not self._sched_stop.is_set():
            with self.cond:
                active = [t for t in self.tasks if t.status in ("preparing", "running")]
                pending = [t for t in self.tasks if t.status == "pending"]
                slots = self.max_active - len(active)
                to_start = pending[:max(0, slots)]
            for t in to_start:
                threading.Thread(target=self._prepare, args=(t,), daemon=True).start()
            with self.cond:
                self.cond.wait(0.5)

    # ---------------- 添加 ----------------
    def add(self, url, filename=None, dest_dir=None, autostart=True,
            referer=None, cookie=None, extra_headers=None):
        cfg = self.cfg
        dest = dest_dir or cfg.get("dest_dir") or os.path.expanduser("~/Downloads")
        headers = dict(cfg.get("headers") or {})
        if referer:
            headers["Referer"] = referer
        if cookie:
            headers["Cookie"] = cookie
        if extra_headers:
            headers.update(extra_headers)
        is_bt = btm.looks_like_magnet(url)
        if is_bt:
            mm = btm.parse_magnet(url) or {}
            if filename is None:
                filename = mm.get("name") or ("磁力_" + (mm.get("hash_hex", "")[:8]))
        with self.lock:
            for t in self.tasks:
                if t.url == url:
                    if autostart and t.status in ("paused", "error", "pending"):
                        self.resume(t)
                    return t
        task = Task(
            url, filename, dest,
            cfg.get("segments", 0),
            cfg.get("ua") or DEFAULT_UA,
            headers,
        )
        task.auto_split = not int(cfg.get("segments", 0) or 0)
        with self.lock:
            if is_bt:
                task.kind = "bt"
                task.segments_count = 1
                task.bt_hash = (btm.parse_magnet(url) or {}).get("hash_hex", "")[:12]
        self.tasks.append(task)
        self.notify(task)
        return task

    # ---------------- FTP 下载（单连接 + REST 续传） ----------------
    def _ftp_fail(self, task, msg):
        task.status = "error"
        task.error = msg
        task.speed = 0.0
        task.save_state(force=True)
        self.notify(task)

    def _prepare_ftp(self, task, gen):
        if task.status == "canceled":
            return
        try:
            host, port, user, pw, path = ftpmod.creds(task.url)
        except Exception as e:
            return self._ftp_fail(task, "FTP 地址看不懂：%s" % e)
        if not host:
            return self._ftp_fail(task, "FTP 地址缺少主机名")
        name = task.requested_name or os.path.basename(path.rstrip("/")) or "ftp_download"
        task.kind = "ftp"
        task.supports_range = False
        task.filename = self._free_name(task, safe_filename(name))
        task.segments_count = 1
        task.status = "running"
        task.error = ""
        task.started_at = time.time()
        task._last_downloaded = task.downloaded
        task._last_speed_t = time.time()
        task.segments = [Segment(0, -1)]
        task.save_state(force=True)
        self.notify(task)
        th = threading.Thread(target=self._ftp_run, args=(task, gen), daemon=True)
        th.start()

    def _ftp_run(self, task, gen):
        attempts = 0
        while (not task.stop_evt.is_set()) and task.generation == gen \
                and task.status not in ("canceled", "paused"):
            have = os.path.getsize(task.path) if os.path.exists(task.path) else 0
            if task.total > 0 and have >= task.total:
                break
            task.downloaded = have

            def on_data(n):
                task.downloaded += n
                now = time.time()
                dt = now - task._last_speed_t
                if dt >= 0.5:
                    task.speed = max(0.0, (task.downloaded - task._last_downloaded) / dt)
                    task._last_downloaded = task.downloaded
                    task._last_speed_t = now
                    task.save_state()
                    self.notify(task)

            def stopped():
                return task.stop_evt.is_set() or task.generation != gen \
                    or task.status in ("canceled", "paused")

            try:
                total, written = ftpmod.fetch(
                    task.url, task.filename, task.dest_dir, have,
                    on_data, stopped, throttle=self.throttle)
                if total and total > 0:
                    task.total = total
                attempts = 0
                now_size = os.path.getsize(task.path) if os.path.exists(task.path) else 0
                if task.total > 0 and now_size >= task.total:
                    break
                if stopped():
                    break
                attempts += 1
                if attempts > 5:
                    return self._ftp_fail(task, "FTP 多次中断，文件没下完（可点继续重试）")
                time.sleep(min(2 ** attempts, 5))
            except Exception as e:
                if stopped():
                    break
                attempts += 1
                if attempts > 4:
                    return self._ftp_fail(task, ftpmod.human_error(e))
                time.sleep(min(2 ** attempts, 5))

        task.speed = 0.0
        if task.status == "canceled":
            try:
                if os.path.exists(task.path):
                    os.remove(task.path)
            except Exception:
                pass
            task.save_state(force=True)
            self.notify(task)
            return
        if task.stop_evt.is_set() or task.status == "paused":
            task.save_state(force=True)
            self.notify(task)
            return

        try:
            if task.total > 0:
                size = os.path.getsize(task.path) if os.path.exists(task.path) else 0
                if size < task.total:
                    raise IOError("文件没下完：%d / %d 字节" % (size, task.total))
            self._finalize_local_file(task)
            task.total = os.path.getsize(task.path)
            task.downloaded = task.total
            task.status = "done"
            task.error = ""
            task.cleanup_part()
        except Exception as e:
            task.status = "error"
            task.error = _friendly_error(e) if not isinstance(e, IOError) else str(e)
        task.save_state(force=True)
        self.notify(task)

    # ---------------- 磁力（BT）下载 ----------------
    def _fail_bt(self, task, msg):
        task.status = "error"
        task.error = msg
        task.save_state(force=True)
        self.notify(task)

    def _prepare_bt(self, task, gen):
        if task.status == "canceled":
            return
        try:
            m = btm.parse_magnet(task.url)
        except Exception as e:
            return self._fail_bt(task, "磁力链接解析失败：%s" % e)
        if not m:
            return self._fail_bt(task, "不是有效的磁力链接：缺 btih（种子哈希）")
        task.bt_hash = m["hash_hex"][:12]
        name = task.requested_name or (m["name"] or ("磁力_" + m["hash_hex"][:8]))
        task.filename = self._free_name(task, safe_filename(name))
        task.total = m["length"] or -1
        task.status = "running"
        task.error = ""
        task.started_at = time.time()
        self.notify(task)

        # 找做种者：tracker 偶尔要等一会才有人，退避重试 3 次
        peers = []
        for attempt in range(3):
            if task.stop_evt.is_set() or task.generation != gen:
                task.save_state(force=True)
                return
            try:
                peers = btm.get_peers(m, task.total if task.total > 0 else 0)
            except Exception:
                peers = []
            if peers:
                break
            time.sleep(1.5 + attempt * 2)

        if not peers:
            return self._fail_bt(task, "tracker 和 DHT 都没找到做种者：这条资源可能暂时没人做种，"
                                       "或者当前网络访问不了它的 tracker / 封了 DHT 的 UDP。稍后再试，或换一条资源。")
        task.bt_peers = len(peers)
        self.notify(task)

        info = None
        last_err = ""
        for addr in peers[:10]:
            if task.stop_evt.is_set() or task.generation != gen:
                task.save_state(force=True)
                return
            p = btm.Peer(addr, m["info_hash"])
            try:
                p.open()
                info = p.fetch_metadata()
                if info:
                    break
            except Exception as e:
                last_err = str(e)
            finally:
                p.close()
        if info is None:
            return self._fail_bt(task, "连上了做种者但取不到种子信息（对方可能不支持元数据交换）：%s" % last_err)

        if b"files" in info:
            return self._fail_bt(task, "这是多文件种子，桌面版目前只支持单文件种子。")
        total = int(info.get(b"length", 0) or 0)
        if total <= 0:
            return self._fail_bt(task, "种子信息里没有文件长度，无法下载。")
        real_name = info.get(b"name", b"").decode("utf-8", "ignore")
        task.filename = self._free_name(task, safe_filename(real_name or name))
        task.total = total
        task.downloaded = 0
        self.notify(task)

        t_ref = [time.time(), 0]

        def progress(done, tot):
            task.downloaded = done
            now = time.time()
            if now - t_ref[0] >= 0.5 or done >= tot:
                task.speed = max(0.0, (done - t_ref[1]) / max(0.001, now - t_ref[0]))
                t_ref[0] = now
                t_ref[1] = done
            task.save_state()
            self.notify(task)

        try:
            written, nm, complete = btm.download(
                m, info, task.path,
                on_progress=progress,
                should_stop=lambda: task.stop_evt.is_set() or task.status != "running",
                throttle=self.throttle)
        except Exception as e:
            if task.status == "paused" or task.stop_evt.is_set():
                task.save_state(force=True)
                return
            return self._fail_bt(task, "磁力下载失败：%s" % e)

        if task.status != "running":
            task.save_state(force=True)
            return
        if complete:
            task.downloaded = task.total
            task.speed = 0.0
            task.status = "done"
            task.error = ""
            task.save_state(force=True)
            self.notify(task)
        else:
            task.status = "paused"
            task.save_state(force=True)
            self.notify(task)

    def _prepare(self, task):
        if task.status == "canceled":
            return
        task.generation += 1
        gen = task.generation
        task.status = "preparing"
        self.notify(task)
        try:
            os.makedirs(task.dest_dir, exist_ok=True)
            if task.kind == "bt":
                self._prepare_bt(task, gen)
                return
            if str(task.url or "").lower().startswith("ftp://"):
                self._prepare_ftp(task, gen)
                return
            info = probe(task.url, task.ua, extra_headers=task.extra_headers)
            if task.generation != gen or task.stop_evt.is_set() or task.status == "paused":
                task.save_state(force=True)
                return

            if info.get("kind") == "m3u8":
                self._prepare_m3u8(task, info, gen)
                return

            if self._try_resume(task, info):
                if task.generation != gen or task.stop_evt.is_set():
                    task.save_state(force=True)
                    return
                task.status = "running"
                self.notify(task)
                self._run(task)
                return

            name = task.requested_name or info.get("filename") or "download"
            task.filename = self._free_name(task, safe_filename(name))
            task.total = info.get("total", -1)
            task.supports_range = bool(info.get("ranges")) and task.total > 0
            task.etag = info.get("etag") or ""
            task.content_type = info.get("type", "")

            prefer = self.cfg.get("segments", 0)
            count = adaptive_segment_count(task.total, task.supports_range, prefer)
            task.segments_count = count
            if task.total > 0 and task.supports_range:
                task.segments = plan_segments(task.total, count)
            elif task.total > 0:
                task.segments = [Segment(0, task.total - 1)]
            else:
                task.segments = [Segment(0, -1)]

            if task.total > 0:
                with open(task.path, "wb") as f:
                    f.truncate(task.total)
            else:
                with open(task.path, "wb"):
                    pass

            task.downloaded = 0
            task.save_state(force=True)
            if task.generation != gen:
                return
            task.status = "running"
            self.notify(task)
            self._run(task)
        except Exception as e:
            task.status = "error"
            task.error = _friendly_error(e)
            task.close_file()
            self.notify(task)

    def _free_name(self, task, filename):
        base, ext = os.path.splitext(filename)
        candidate = unique_path(task.dest_dir, filename)
        used = set(t.path for t in self.tasks if t is not task)
        i = 1
        while candidate in used or os.path.exists(candidate) or os.path.exists(candidate + PART_SUFFIX):
            candidate = os.path.join(task.dest_dir, "%s (%d)%s" % (base, i, ext))
            i += 1
        return os.path.basename(candidate)

    def _try_resume(self, task, info):
        if not os.path.exists(task.part_path) or not os.path.exists(task.path):
            return False
        try:
            with open(task.part_path, "r", encoding="utf-8") as f:
                state = json.load(f)
        except Exception:
            return False
        sid = state.get("id")
        if sid and sid != task.id:
            return False
        total = int(state.get("total", -1))
        size = os.path.getsize(task.path)
        if total > 0 and size != total:
            return False
        new_total = int(info.get("total", -1))
        if total > 0 and new_total > 0 and total != new_total:
            return False
        try:
            segs = [Segment.from_dict(d) for d in state.get("segments", [])]
        except Exception:
            return False
        if not segs:
            return False
        task.filename = state.get("filename", task.filename)
        task.total = total
        task.supports_range = bool(state.get("supports_range"))
        task.etag = state.get("etag", "")
        task.content_type = state.get("content_type", "")
        task.segments = segs
        task.downloaded = sum(s.done for s in segs)
        task.status = "paused"
        task.open_file("r+b")
        return True

    # ---------------- 普通文件运行 ----------------
    def _run(self, task):
        task.stop_evt.clear()
        task.generation += 1
        gen = task.generation
        task.status = "running"
        task.error = ""
        task.started_at = time.time()
        self.notify(task)
        task.futures = []
        pending = [s for s in task.segments if s.end < 0 or s.done < s.size]
        # 池上限取段数上限：动态升段 / 二分重分段产生的新任务能立刻拿到 worker
        workers = max(1, len(pending), MAX_AUTO_SEGMENTS)
        executor = concurrent.futures.ThreadPoolExecutor(max_workers=workers)
        task._executor = executor

        def _worker(seg):
            self._segment_loop(task, seg, gen, executor)

        for seg in pending:
            task.futures.append(executor.submit(_worker, seg))
        threading.Thread(target=self._monitor, args=(task, gen), daemon=True).start()

    def _split_segment(self, task, seg, executor, gen):
        """把一段剩余区间二分，后半段交给新任务（失败重分段 / 动态升段共用）。"""
        if not task.supports_range or seg.end < 0:
            return False
        remaining = seg.remaining()
        if remaining <= SPLIT_MIN_BYTES:
            return False
        half = remaining // 2
        mid = seg.start + seg.done + half
        new = Segment(mid, seg.end)
        seg.end = mid - 1
        with task.lock:
            task.segments.append(new)
        fut = executor.submit(self._segment_loop, task, new, gen, executor)
        task.futures.append(fut)
        return True

    def _take_tail(self, task, gen):
        """连接复用：本段下完的连接去切最大未完成段的后半截，避免某段早早完工、剩下的慢段独吞时间。"""
        if os.environ.get("JF_NO_TAIL"):
            return None            # 对照测试用
        if not task.supports_range or task.stop_evt.is_set():
            return None
        if task.status != "running":
            return None
        with task.lock:
            big, big_rest = None, 0
            for s in task.segments:
                if s.end < 0:
                    continue
                rest = s.remaining()
                if rest > big_rest and rest >= 2 * TAIL_MIN_BYTES:
                    big, big_rest = s, rest
            if big is None:
                return None
            mid = big.start + big.done + big_rest // 2
            nxt = Segment(mid, big.end)
            big.end = mid - 1
            task.segments.append(nxt)
            return nxt

    def _segment_loop(self, task, seg, gen, executor=None):
        attempts = 0
        splits = 0
        while not task.stop_evt.is_set() and task.generation == gen:
            if seg.end >= 0 and seg.done >= seg.size:
                return
            pos = seg.start + seg.done
            headers = _build_headers(task.url, task.ua, task.extra_headers,
                                     range_bytes="bytes=%d-%s" % (pos, "" if seg.end < 0 else seg.end),
                                     etag=task.etag, keep_alive=bool(task.supports_range))
            resp = None
            try:
                resp = pooled_get(task.url, headers)
                status = getattr(resp, "status", 200)
                if status == 200 and task.supports_range and task.total > 0:
                    raise RangeUnsupported()
                if status not in (200, 206):
                    raise HttpStatusError(status, _status_reason(status))

                while not task.stop_evt.is_set() and task.generation == gen:
                    if seg.end >= 0:
                        limit = min(CHUNK, seg.end - pos + 1)
                    else:
                        limit = CHUNK
                    data = resp.read(limit)
                    if not data:
                        break
                    self.throttle(len(data))
                    with task.lock:
                        if task.generation != gen or task.stop_evt.is_set():
                            return
                        fh = task.open_file()
                        fh.seek(pos)
                        fh.write(data)
                        seg.done += len(data)
                        task.downloaded += len(data)
                    pos += len(data)
                    if seg.end >= 0 and pos > seg.end:
                        break

                if task.generation != gen:
                    return
                if seg.end < 0:
                    if task.total <= 0:
                        task.total = task.downloaded
                    seg.end = seg.start + seg.done - 1
                    return
                if seg.done >= seg.size:
                    # 连接复用（IDM 式动态分段）：本段下完不退出线程，切走最大剩余段的后半截继续
                    nxt = self._take_tail(task, gen)
                    if nxt is None:
                        return
                    seg = nxt
                    attempts = 0
                    splits = 0
                    continue
                # 服务器提前断开：支持 Range 且剩余够大才二分重分段，否则从断点续传
                attempts += 1
                if (executor is not None and task.supports_range and splits < 2
                        and self._split_segment(task, seg, executor, gen)):
                    splits += 1
                    return
                if attempts > MAX_ATTEMPTS + 2:
                    raise DownloadError("多次重试后仍未下完，网络可能不稳定")
                time.sleep(min(8.0, 0.8 * (2 ** attempts)))
            except RangeUnsupported:
                if task.generation != gen:
                    return
                task.restart_requested = True
                task.stop_evt.set()
                return
            except HttpStatusError as e:
                if task.generation != gen:
                    return
                if resp is not None:
                    resp.drop()
                # 403/404/410/401/416 等不重试，直接给出中文原因
                if e.status in NON_RETRYABLE_STATUSES:
                    task.status = "error"
                    task.error = _friendly_error(e)
                    task.stop_evt.set()
                    self.notify(task)
                    return
                attempts += 1
                if task.stop_evt.is_set():
                    return
                if attempts > MAX_ATTEMPTS + 2:
                    task.status = "error"
                    task.error = _friendly_error(e)
                    task.stop_evt.set()
                    self.notify(task)
                    return
                time.sleep(min(8.0, 0.8 * (2 ** attempts)))
            except DownloadError as e:
                if task.generation != gen:
                    return
                if resp is not None:
                    resp.drop()
                attempts += 1
                if task.stop_evt.is_set():
                    return
                if attempts > MAX_ATTEMPTS + 2:
                    task.status = "error"
                    task.error = _friendly_error(e)
                    task.stop_evt.set()
                    self.notify(task)
                    return
                time.sleep(min(8.0, 0.8 * (2 ** attempts)))
            except Exception as e:
                if task.generation != gen:
                    return
                if resp is not None:
                    resp.drop()
                attempts += 1
                if task.stop_evt.is_set():
                    return
                if attempts > MAX_ATTEMPTS + 2:
                    task.status = "error"
                    task.error = _friendly_error(e)
                    task.stop_evt.set()
                    self.notify(task)
                    return
                time.sleep(min(8.0, 0.8 * (2 ** attempts)))
            finally:
                if resp is not None:
                    try:
                        resp.close()
                    except Exception:
                        pass

    def _monitor(self, task, gen):
        last = task.downloaded
        last_t = time.time()
        executor = task._executor
        while task.generation == gen and any(not f.done() for f in task.futures):
            time.sleep(0.35)
            if task.generation != gen:
                return
            now = time.time()
            # 自适应升段：自动档且未到段数上限时，把最大的在途段二分，
            # 靠更多并发连接对抗「服务器对单连接限速」。
            if (task.auto_split and task.supports_range and executor is not None
                    and len(task.segments) < MAX_AUTO_SEGMENTS
                    and now - task.started_at > 1.0):
                with task.lock:
                    cand = None
                    best = 0
                    for s in task.segments:
                        if s.end >= 0 and s.done < s.size:
                            r = s.remaining()
                            if r > best:
                                best, cand = r, s
                if cand is not None:
                    self._split_segment(task, cand, executor, gen)
            if now - last_t >= 0.5:
                task.speed = max(0.0, (task.downloaded - last) / (now - last_t))
                last = task.downloaded
                last_t = now
                task.save_state()
                self.notify(task)
            if task.status == "canceled":
                break
        task.speed = 0.0
        if executor is not None:
            executor.shutdown(wait=False)
        if task.generation != gen:
            return

        if task.restart_requested and task.status == "running":
            task.restart_requested = False
            self._reset_single(task)
            self._run(task)
            return

        if task.status == "canceled":
            task.delete_output()
            self.notify(task)
            return

        if task.status == "paused":
            task.save_state(force=True)
            self.notify(task)
            return

        if task.status == "error":
            task.save_state(force=True)
            self.notify(task)
            return

        if task.seg_complete():
            task.close_file()
            try:
                size = os.path.getsize(task.path)
                if task.total > 0 and size != task.total:
                    raise DownloadError(
                        "文件大小校验失败：实际 %d 字节，应为 %d 字节。临时文件已保留，可点「继续」重新补齐"
                        % (size, task.total))
                self._finalize_local_file(task)
                task.total = os.path.getsize(task.path)
                task.downloaded = task.total
                task.status = "done"
                task.error = ""
                task.cleanup_part()
            except DownloadError as e:
                task.status = "error"
                task.error = str(e)
                task.save_state(force=True)
            except Exception as e:
                task.status = "error"
                task.error = _friendly_error(e)
                task.save_state(force=True)
        else:
            task.status = "error"
            task.error = task.error or "下载未完成，可重新开始"
            task.save_state(force=True)
        self.notify(task)

    def _finalize_local_file(self, task):
        """文件头嗅探：识别错误页并删除；按魔数 / Content-Type 修正扩展名。"""
        with open(task.path, "rb") as f:
            head = f.read(32)
        kind = sniff_web_body(head)
        if kind:
            try:
                os.remove(task.path)
            except Exception:
                pass
            task.cleanup_part()
            raise DownloadError(
                "下载到的是网页 / 接口错误页（文件头为 %s），不是真正的文件。链接可能需要登录、已过期，"
                "或被防盗链拦截；已删除该无效文件，请核对地址后重试" % ("HTML" if kind == "html" else "JSON"))
        head_ext = ext_from_head(head)
        stem, ext = os.path.splitext(task.filename)
        old_part = task.part_path
        renamed = False
        if head_ext:
            if ext.lower() != head_ext:
                new_name = self._free_name(task, stem + head_ext)
                new_path = os.path.join(task.dest_dir, new_name)
                os.replace(task.path, new_path)
                task.filename = new_name
                renamed = True
        elif not ext:
            ct_ext = ext_from_content_type(task.content_type)
            new_name = self._free_name(task, stem + ct_ext)
            new_path = os.path.join(task.dest_dir, new_name)
            os.replace(task.path, new_path)
            task.filename = new_name
            renamed = True
        if renamed:
            for p in (old_part, old_part + ".tmp"):
                try:
                    if os.path.exists(p):
                        os.remove(p)
                except Exception:
                    pass

    def _reset_single(self, task):
        with task.lock:
            task.supports_range = False
            task.segments = [Segment(0, task.total - 1 if task.total > 0 else -1)]
            task.downloaded = 0
            fh = task.open_file()
            fh.seek(0)
            fh.truncate(0)
            if task.total > 0:
                fh.truncate(task.total)
        self.notify(task)

    # ---------------- m3u8 ----------------
    def _prepare_m3u8(self, task, info, gen):
        headers = _build_headers(task.url, task.ua, task.extra_headers)
        text = fetch_text(task.url, headers)
        variants = parse_master(text)
        media_url = task.url
        if variants:
            media_url = resolve_url(task.url, variants[0][1])
            text = fetch_text(media_url, headers)
        map_ref, refs = parse_media_playlist(text)
        seg_urls = [resolve_url(media_url, r) for r in refs]
        if not seg_urls:
            raise DownloadError("播放列表里没有可下载的分片，链接可能已失效")
        map_url = resolve_url(media_url, map_ref) if map_ref else ""

        # 续传：读取已保存的分片进度
        saved = self._load_m3u8_state(task)
        task.m3u8 = {"map": map_url, "segments": seg_urls,
                     "done": saved.get("done", [False] * len(seg_urls)),
                     "sizes": saved.get("sizes", [0] * len(seg_urls))}
        if len(task.m3u8["done"]) < len(seg_urls):
            task.m3u8["done"] += [False] * (len(seg_urls) - len(task.m3u8["done"]))
            task.m3u8["sizes"] += [0] * (len(seg_urls) - len(task.m3u8["sizes"]))
        task.total_segments = len(seg_urls)
        task.downloaded = sum(task.m3u8["sizes"])
        task.content_type = "application/vnd.apple.mpegurl"

        name = task.requested_name or info.get("filename") or "video"
        task.filename = self._free_name(task, safe_filename(name))
        task.save_state(force=True)
        if task.generation != gen:
            return
        task.status = "running"
        self.notify(task)
        self._run_m3u8(task, gen)

    def _load_m3u8_state(self, task):
        if not os.path.exists(task.part_path):
            return {}
        try:
            with open(task.part_path, "r", encoding="utf-8") as f:
                state = json.load(f)
            return state.get("m3u8") or {}
        except Exception:
            return {}

    def _seg_part_path(self, task, idx):
        return os.path.join(task.parts_dir, "seg_%04d.part" % idx)

    def _run_m3u8(self, task, gen):
        task.stop_evt.clear()
        task.threads = []
        pending = [i for i, d in enumerate(task.m3u8["done"]) if not d]
        sem = threading.Semaphore(4)

        def worker(idx):
            with sem:
                if task.stop_evt.is_set() or task.generation != gen:
                    return
                url = task.m3u8["segments"][idx]
                headers = _build_headers(url, task.ua, task.extra_headers)
                resp = None
                try:
                    resp = open_url(url, headers, timeout=30)
                    data = resp.read()
                    if not data:
                        raise DownloadError("分片 %d 内容为空" % idx)
                    os.makedirs(task.parts_dir, exist_ok=True)
                    with open(self._seg_part_path(task, idx), "wb") as f:
                        f.write(data)
                    with task.lock:
                        task.m3u8["done"][idx] = True
                        task.m3u8["sizes"][idx] = len(data)
                        task.downloaded += len(data)
                    task.save_state()
                    self.notify(task)
                except Exception as e:
                    if task.generation != gen or task.stop_evt.is_set():
                        return
                    task.status = "error"
                    task.error = _friendly_error(e) + "（分片 %d）" % idx
                    task.stop_evt.set()
                    self.notify(task)
                finally:
                    if resp is not None:
                        try:
                            resp.close()
                        except Exception:
                            pass

        for i in pending:
            t = threading.Thread(target=worker, args=(i,), daemon=True)
            t.start()
            task.threads.append(t)
        threading.Thread(target=self._m3u8_monitor, args=(task, gen), daemon=True).start()

    def _m3u8_monitor(self, task, gen):
        last = task.downloaded
        last_t = time.time()
        while task.generation == gen and any(t.is_alive() for t in task.threads):
            time.sleep(0.35)
            if task.generation != gen:
                return
            now = time.time()
            if now - last_t >= 0.5:
                task.speed = max(0.0, (task.downloaded - last) / (now - last_t))
                last = task.downloaded
                last_t = now
                self.notify(task)
            if task.status == "canceled":
                break
        task.speed = 0.0
        if task.generation != gen:
            return
        if task.status == "canceled":
            task.delete_output()
            self.notify(task)
            return
        if task.status == "paused":
            task.save_state(force=True)
            self.notify(task)
            return
        if task.status == "error":
            task.save_state(force=True)
            self.notify(task)
            return

        try:
            self._merge_m3u8(task)
            task.status = "done"
            task.error = ""
            task.cleanup_part()
        except Exception as e:
            task.status = "error"
            task.error = _friendly_error(e)
            task.save_state(force=True)
        self.notify(task)

    def _merge_m3u8(self, task):
        segs = task.m3u8["segments"]
        for i in range(len(segs)):
            if not task.m3u8["done"][i] or not os.path.exists(self._seg_part_path(task, i)):
                raise DownloadError("部分分片未下载完成，可点「继续」重试")

        is_fmp4 = bool(task.m3u8.get("map"))
        stem = os.path.splitext(task.filename)[0]
        final_name = self._free_name(task, stem + (".mp4" if is_fmp4 else ".ts"))
        final_path = os.path.join(task.dest_dir, final_name)

        if is_fmp4:
            # fMP4：初始化分片 + 媒体分片直接拼接为 mp4
            with open(final_path, "wb") as out:
                if task.m3u8.get("map"):
                    resp = open_url(task.m3u8["map"],
                                    _build_headers(task.m3u8["map"], task.ua, task.extra_headers),
                                    timeout=30)
                    try:
                        out.write(resp.read())
                    finally:
                        resp.close()
                for i in range(len(segs)):
                    with open(self._seg_part_path(task, i), "rb") as f:
                        while True:
                            buf = f.read(CHUNK)
                            if not buf:
                                break
                            out.write(buf)
        else:
            merged_ts = os.path.join(task.parts_dir, "merged.ts")
            with open(merged_ts, "wb") as out:
                for i in range(len(segs)):
                    with open(self._seg_part_path(task, i), "rb") as f:
                        while True:
                            buf = f.read(CHUNK)
                            if not buf:
                                break
                            out.write(buf)
            ffmpeg = find_ffmpeg()
            if ffmpeg:
                mp4_name = self._free_name(task, stem + ".mp4")
                mp4_path = os.path.join(task.dest_dir, mp4_name)
                proc = subprocess.run(
                    [ffmpeg, "-y", "-i", merged_ts, "-c", "copy", mp4_path],
                    capture_output=True, timeout=180)
                if proc.returncode == 0 and os.path.exists(mp4_path) and os.path.getsize(mp4_path) > 0:
                    try:
                        os.remove(merged_ts)
                    except Exception:
                        pass
                    final_path = mp4_path
                    final_name = mp4_name
                else:
                    task.note = "TS 分片已合并，但本机 ffmpeg 转封装失败，保留为 .ts"
            else:
                task.note = "未检测到 ffmpeg：TS 分片已合并为 .ts，可用常见播放器直接播放；安装 ffmpeg 后会自动转成 mp4"
                shutil.move(merged_ts, final_path)

        if not os.path.exists(final_path) or os.path.getsize(final_path) == 0:
            raise DownloadError("合并后的文件为空，分片数据可能不完整")
        task.filename = final_name
        task.total = os.path.getsize(final_path)
        task.downloaded = task.total

    # ---------------- 控制 ----------------
    def pause(self, task):
        if task.status in ("running", "preparing", "pending"):
            task.status = "paused"
            task.stop_evt.set()
            task.save_state(force=True)
            self.notify(task)

    def resume(self, task):
        if task.status not in ("paused", "error"):
            return
        task.stop_evt.clear()
        task.error = ""
        task.status = "pending"
        self.notify(task)

    def cancel(self, task):
        task.status = "canceled"
        task.stop_evt.set()
        if not any(not f.done() for f in task.futures):
            task.delete_output()
        self.notify(task)

    def remove(self, task, delete_file=False):
        self.cancel(task) if task.status in ("running", "preparing", "paused") else None
        with self.lock:
            if task in self.tasks:
                self.tasks.remove(task)
        if delete_file:
            for p in (task.path, task.part_path, task.part_path + ".tmp"):
                try:
                    if p and os.path.exists(p):
                        os.remove(p)
                except Exception:
                    pass
            try:
                if os.path.isdir(task.parts_dir):
                    shutil.rmtree(task.parts_dir, ignore_errors=True)
            except Exception:
                pass
        self.notify(task)

    # ---------------- 载入历史 ----------------
    def load_existing(self, folder):
        loaded = []
        try:
            names = os.listdir(folder)
        except Exception:
            return loaded
        for n in names:
            if not n.endswith(PART_SUFFIX):
                continue
            p = os.path.join(folder, n)
            try:
                with open(p, "r", encoding="utf-8") as f:
                    state = json.load(f)
                kind = state.get("kind", "file")
                m3 = state.get("m3u8") or {}
                if kind == "m3u8":
                    segs = []
                else:
                    segs = [Segment.from_dict(d) for d in state.get("segments", [])]
                    if not segs:
                        continue
                task = Task(state.get("url", ""), state.get("filename", ""), folder,
                            8, state.get("ua") or DEFAULT_UA, state.get("extra_headers") or {},
                            tid=state.get("id"), kind=kind)
                task.filename = state.get("filename", task.filename)
                task.total = int(state.get("total", -1))
                task.supports_range = bool(state.get("supports_range"))
                task.etag = state.get("etag", "")
                task.content_type = state.get("content_type", "")
                task.segments = segs
                task.m3u8 = m3
                task.total_segments = int(state.get("total_segments", 0))
                task.downloaded = sum(s.done for s in segs) or sum(m3.get("sizes", []))
                task.status = "paused"
                if os.path.exists(task.path) or kind == "m3u8":
                    loaded.append(task)
            except Exception:
                continue
        if loaded:
            with self.lock:
                self.tasks.extend(loaded)
        return loaded


def _friendly_error(e):
    if isinstance(e, HttpStatusError):
        return _status_reason(e.status)
    msg = str(e) or e.__class__.__name__
    low = msg.lower()
    if "getaddrinfo" in low or "name or service not known" in low:
        return "无法解析域名：检查网络连接，或确认链接拼写是否正确"
    if "timed out" in low or "timeout" in low:
        return "连接超时：网络较慢或服务器无响应，稍后重试"
    if "reset" in low:
        return "连接被重置：网络不稳定，或服务器中断了连接，稍后重试"
    if "refused" in low:
        return "连接被拒绝：服务器拒绝了访问，可能需要 Referer / Cookie 或已被限流"
    if "403" in msg:
        return "服务器拒绝访问（403）：可能被防盗链拦截，试试补 Referer"
    if "404" in msg:
        return "链接失效（404）：文件不存在或已过期"
    if "410" in msg:
        return "文件已被删除（410）：链接永久失效"
    if "ssl" in low or "certificate" in low:
        return "HTTPS 证书校验失败：检查系统时间，或换用浏览器可打开的链接"
    return msg
