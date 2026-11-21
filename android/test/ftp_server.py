"""极简 FTP 服务器（只够验证疾风下载的 FTP 客户端）。

支持 USER/PASS(匿名)/TYPE I/SIZE/REST/PASV/RETR/QUIT。
--cut N        每次传输只发 N 字节就断开（模拟掉线，看客户端能否续传）
--cut-once     只有第一次传输断流，之后正常（验证 REST 续传后能下完）
--slow KB      限速，用于观察进度
"""
import argparse, os, socket, threading, sys

ROOT = "."
CUT = 0
CUT_ONCE = False
CUT_DONE = False
SLOW = 0
DATA_PORT = 0
lock = threading.Lock()


def send(c, line):
    c.sendall((line + "\r\n").encode("utf-8"))


def handle(conn, addr):
    global CUT_DONE
    f = conn.makefile("rb")
    send(conn, "220 jifeng test ftp ready")
    rest = 0
    path = None
    pasv = None
    while True:
        line = f.readline()
        if not line:
            break
        try:
            cmd = line.decode("utf-8").strip()
        except Exception:
            cmd = line.decode("latin-1").strip()
        up = cmd.upper()
        if up.startswith("USER"):
            send(conn, "331 need password")
        elif up.startswith("PASS"):
            send(conn, "230 login ok")
        elif up.startswith("TYPE"):
            send(conn, "200 type set")
        elif up.startswith("SIZE"):
            arg = cmd[4:].strip().lstrip("/")
            p = os.path.join(ROOT, arg)
            if os.path.isfile(p):
                send(conn, "213 %d" % os.path.getsize(p))
            else:
                send(conn, "550 no such file")
        elif up.startswith("REST"):
            try:
                rest = int(cmd[4:].strip())
                send(conn, "350 restart at %d" % rest)
            except Exception:
                send(conn, "501 bad rest")
        elif up.startswith("PASV"):
            if pasv:
                try:
                    pasv.close()
                except Exception:
                    pass
            pasv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            pasv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            pasv.bind(("127.0.0.1", DATA_PORT))
            pasv.listen(1)
            port = pasv.getsockname()[1]
            send(conn, "227 Entering Passive Mode (127,0,0,1,%d,%d)" % (port // 256, port % 256))
        elif up.startswith("CWD"):
            send(conn, "250 ok")
        elif up.startswith("RETR"):
            arg = cmd[4:].strip()
            p = os.path.join(ROOT, arg.lstrip("/"))
            if not os.path.isfile(p):
                send(conn, "550 no such file")
                continue
            size = os.path.getsize(p)
            off = rest
            rest = 0
            send(conn, "150 opening binary mode (%d bytes from %d)" % (size, off))
            d, _ = pasv.accept()
            limit = size
            cutting = False
            with lock:
                if CUT > 0 and not (CUT_ONCE and CUT_DONE):
                    limit = min(size, off + CUT)
                    cutting = True
                    if CUT_ONCE:
                        CUT_DONE = True
            sent = 0
            try:
                with open(p, "rb") as fh:
                    fh.seek(off)
                    while off + sent < limit:
                        n = min(256 * 1024, limit - (off + sent))
                        b = fh.read(n)
                        if not b:
                            break
                        d.sendall(b)
                        sent += len(b)
                        if SLOW > 0:
                            import time
                            time.sleep(len(b) / float(SLOW * 1024))
            except Exception as e:
                print("data error:", e)
            try:
                d.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            d.close()
            if cutting:
                send(conn, "426 connection closed; transfer aborted")
            else:
                send(conn, "226 transfer complete")
        elif up.startswith("QUIT"):
            send(conn, "221 bye")
            break
        elif up.startswith("NOOP"):
            send(conn, "200 ok")
        else:
            send(conn, "502 not implemented: " + cmd[:20])
    try:
        conn.close()
    except Exception:
        pass


def main():
    global ROOT, CUT, CUT_ONCE, SLOW, DATA_PORT
    ap = argparse.ArgumentParser()
    ap.add_argument("port", type=int)
    ap.add_argument("root")
    ap.add_argument("--cut", type=int, default=0)
    ap.add_argument("--cut-once", action="store_true")
    ap.add_argument("--slow", type=int, default=0)
    ap.add_argument("--data-port", type=int, default=0)
    a = ap.parse_args()
    ROOT, CUT, CUT_ONCE, SLOW, DATA_PORT = a.root, a.cut, a.cut_once, a.slow, a.data_port
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", a.port))
    s.listen(8)
    print("ftp ready on %d root=%s cut=%d once=%s" % (a.port, ROOT, CUT, CUT_ONCE))
    sys.stdout.flush()
    while True:
        conn, addr = s.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
