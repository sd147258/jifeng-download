import http.server, os, time, socketserver

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'mov_bbb.mp4')

class H(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_GET(self):
        size = os.path.getsize(PATH)
        self.send_response(200)
        self.send_header('Content-Type', 'video/mp4')
        self.send_header('Content-Length', str(size))
        # 故意不发 Accept-Ranges，模拟不支持分段的服务器
        self.end_headers()
        sent = 0
        with open(PATH, 'rb') as f:
            while sent < size:
                chunk = f.read(16 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
                sent += len(chunk)
                time.sleep(0.45)
        self.close_connection = True

    def log_message(self, *a):
        pass

class S(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

S(('127.0.0.1', 8766), H).serve_forever()
