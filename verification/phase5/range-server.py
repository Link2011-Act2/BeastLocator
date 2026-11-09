"""Owned loopback server for signed-APK Android resume tests; never accepts uploads."""
import argparse, hashlib, json, os, socket, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--log', type=Path, required=True)
p.add_argument('--port', type=int, default=37571)
p.add_argument('--mode', choices=['range','ignore-range','tamper'], default='range')
p.add_argument('--delay', type=float, default=.005)
a = p.parse_args()
size = a.apk.stat().st_size
etag = '"' + hashlib.sha256(a.apk.read_bytes()).hexdigest() + '"'
lock = threading.Lock()
counter = 0

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        global counter
        if self.path != '/apk': self.send_error(404); return
        with lock: counter += 1; number = counter
        value = self.headers.get('Range')
        start = 0
        if value:
            try:
                assert value.startswith('bytes=') and value.endswith('-')
                start = int(value[6:-1]); assert 0 <= start < size
            except Exception: self.send_error(416); return
        valid = not value or self.headers.get('If-Range') == etag
        if not valid: start = 0
        ranged = value is not None and valid and a.mode != 'ignore-range'
        if not ranged: start = 0
        self.send_response(206 if ranged else 200)
        self.send_header('Content-Length', str(size-start))
        self.send_header('ETag', etag)
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Connection', 'close')
        if ranged: self.send_header('Content-Range', f'bytes {start}-{size-1}/{size}')
        self.end_headers()
        sent = 0
        try:
            with a.apk.open('rb') as f:
                f.seek(start)
                limit = size//2 if number == 1 and not ranged else size-start
                while sent < limit:
                    b = f.read(min(32768, limit-sent))
                    if not b: break
                    self.wfile.write(b); self.wfile.flush(); sent += len(b)
                    time.sleep(a.delay)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError): pass
        finally:
            with lock:
                a.log.parent.mkdir(parents=True, exist_ok=True)
                with a.log.open('a', encoding='utf-8') as f:
                    f.write(json.dumps(dict(request=number, range=value, ifRange=self.headers.get('If-Range'),
                        status=206 if ranged else 200, start=start, sent=sent, size=size))+'\n')
            self.close_connection = True
    def log_message(self, *_): pass

print(json.dumps(dict(pid=os.getpid(), port=a.port, size=size, etag=etag)), flush=True)
ThreadingHTTPServer(('127.0.0.1', a.port), Handler).serve_forever()
