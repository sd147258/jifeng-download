"""最小 FTP 客户端（标准库 ftplib），给疾风下载桌面版用。

只做无门槛的部分：匿名 / user:pass@host、PASV 被动模式、SIZE、REST 续传、暂停/取消。
不做：FTPS（TLS）、主动模式、目录递归。
"""
import ftplib
import os
import urllib.parse


class Stop(Exception):
    """回调里抛出来中断传输（暂停 / 取消）。"""


def creds(url):
    p = urllib.parse.urlparse(url)
    host = p.hostname or ""
    port = p.port or 21
    user = urllib.parse.unquote(p.username) if p.username else "anonymous"
    pw = urllib.parse.unquote(p.password) if p.password else "anonymous@"
    path = urllib.parse.unquote(p.path) or "/"
    if not path.startswith("/"):
        path = "/" + path
    return host, port, user, pw, path


def fetch(url, filename, dest_dir, offset, on_data, should_stop, throttle=None):
    """下载到 dest_dir/filename。offset>0 用 REST 续传。返回 (总大小, 本次写入字节数)。"""
    host, port, user, pw, path = creds(url)
    ftp = ftplib.FTP()
    ftp.connect(host, port, timeout=15)
    try:
        ftp.login(user, pw)
        ftp.voidcmd("TYPE I")
        try:
            total = int(ftp.size(path))
        except Exception:
            total = -1
        start = 0
        if offset > 0 and (total < 0 or offset < total):
            try:
                ftp.sendcmd("REST %d" % offset)
                start = offset
            except Exception:
                start = 0
        target = os.path.join(dest_dir, filename)
        if start > 0 and not (os.path.exists(target) and os.path.getsize(target) >= start):
            start = 0
        mode = "ab" if start > 0 else "wb"
        written = [0]
        fh = open(target, mode)
        try:
            def cb(block):
                if should_stop():
                    raise Stop()
                fh.write(block)
                written[0] += len(block)
                on_data(len(block))
                if throttle:
                    throttle(len(block))

            ftp.retrbinary("RETR " + path, cb, blocksize=64 * 1024,
                           rest=(start if start > 0 else None))
        except Stop:
            pass
        finally:
            fh.close()
        return total, written[0]
    finally:
        try:
            ftp.close()
        except Exception:
            pass


def human_error(e):
    """把 ftplib 的异常翻成人话。"""
    if isinstance(e, ftplib.error_perm):
        msg = str(e)
        if msg.startswith("530"):
            return "FTP 登录失败：账号或密码不对（530）"
        if msg.startswith("550"):
            return "服务器上没有这个文件，或没有读取权限（550）"
        return "FTP 服务器拒绝操作：%s" % msg
    if isinstance(e, ftplib.error_temp):
        return "FTP 服务器临时不可用：%s" % str(e)
    if isinstance(e, ftplib.error_proto):
        return "FTP 应答不合规：%s" % str(e)
    if isinstance(e, ConnectionRefusedError):
        return "连不上 FTP 服务器：端口被拒绝（检查地址 / 端口）"
    if isinstance(e, TimeoutError) or "timed out" in str(e).lower():
        return "连接超时，稍后重试"
    if isinstance(e, OSError) and "resolve" in str(e).lower():
        return "无法解析域名，检查网络后重试"
    return str(e)
