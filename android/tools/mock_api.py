"""Mock OpenAI-compatible API for the `emutest` build (emulator tests, no real key).

Serves GET/HEAD /openai/v1/models, POST /openai/v1/audio/transcriptions and
POST /openai/v1/chat/completions on 127.0.0.1:8765 (the emulator reaches it as
10.0.2.2). The mode is read from the file given as the second argument on every
request: "ok", "fail" (HTTP 503) or "hang" (no answer for 300 s). Each request is
logged on stdout: method, path, upload size and type, never the Authorization.

Usage: python3 -I mock_api.py LOGFILE MODEFILE
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG, MODE = sys.argv[1], sys.argv[2]
TEXT = "teste de ditado no emulador"
REFINED = "Teste de ditado no emulador."


def mode():
    try:
        return open(MODE).read().strip()
    except OSError:
        return "ok"


def log(line):
    with open(LOG, "a") as f:
        f.write(f"{time.strftime('%H:%M:%S')} {line}\n")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_HEAD(self):
        log(f"HEAD {self.path}")
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        log(f"GET {self.path}")
        self.reply(200, {"data": [{"id": "whisper-large-v3"}, {"id": "openai/gpt-oss-120b"}]})

    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(size)
        kind = "flac" if b'filename="audio.flac"' in body else ("wav" if b'filename="audio.wav"' in body else "-")
        m = mode()
        log(f"POST {self.path} bytes={size} upload={kind} mode={m}")
        if m == "hang":
            time.sleep(300)
            return
        if m == "fail":
            return self.reply(503, {"error": {"message": "mock: service unavailable"}})
        if self.path.endswith("/audio/transcriptions"):
            return self.reply(200, {"text": TEXT})
        if self.path.endswith("/chat/completions"):
            return self.reply(200, {"choices": [{"index": 0, "message": {"role": "assistant", "content": REFINED}, "finish_reason": "stop"}]})
        self.reply(404, {"error": {"message": "mock: unknown path"}})

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", 8765), Handler).serve_forever()
