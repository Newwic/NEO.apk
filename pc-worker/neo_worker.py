#!/usr/bin/env python3
"""NEO PC Worker - stdlib only, no cloud, no pip install required."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import json, os

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "neo-config.json"
PROJECT_ROOT = Path(os.environ.get("NEO_PROJECT_ROOT", ROOT)).resolve()
HOST, PORT = "0.0.0.0", int(os.environ.get("NEO_WORKER_PORT", "8765"))

class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code); self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health": return self._send(200, {"ok": True, "project_root": str(PROJECT_ROOT)})
        if self.path == "/neo-config":
            try: return self._send(200, json.loads(CONFIG.read_text(encoding="utf-8")))
            except Exception as e: return self._send(500, {"error": str(e)})
        self._send(404, {"error":"not found"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0")); raw = self.rfile.read(n)
        try: body = json.loads(raw or b"{}")
        except Exception: return self._send(400, {"error":"bad json"})
        if self.path == "/task":
            task = str(body.get("task", "")).strip()
            return self._send(200, {"result": f"PC Worker ได้รับงาน: {task}\nWorker ออนไลน์แล้ว แต่การแก้ไฟล์/รันคำสั่งอัตโนมัติจะเปิดผ่าน tool whitelist ในขั้นถัดไป"})
        self._send(404, {"error":"not found"})

    def log_message(self, fmt, *args): print("[NEO]", fmt % args)

if __name__ == "__main__":
    print(f"NEO PC Worker: http://{HOST}:{PORT}")
    print(f"Live config: {CONFIG}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
