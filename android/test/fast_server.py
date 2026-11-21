import http.server, os, re, socketserver

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'big.bin')

class H(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_GET(self):
        size = os.path.getsize(PATH)
        start, end = 0, size - 1
        rng = self.headers.get('Range')
        code = 200
        if rng:
            m = re.match(r'bytes=(\d*)-(\d*)', rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                if m.group(2):
                    end = int(m.group(2))
                code = 206
        end = min(end, size - 1)
        length = end - start + 1
        self.send_response(code)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Accept-Ranges', 'bytes')
        if code == 206:
            self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, size))
        self.send_header('Content-Length', str(length))
        self.end_headers()
        sent = 0
        with open(PATH, 'rb') as f:
            f.seek(start)
            while sent < length:
                chunk = f.read(min(64 * 1024, length - sent))
                if not chunk:
                    break
                self.wfile.write(chunk)
                sent += len(chunk)
        self.close_connection = True

    def log_message(self, *a):
        pass

class S(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

S(('127.0.0.1', 8768), H).serve_forever()
