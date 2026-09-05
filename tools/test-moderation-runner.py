"""Prove an AI failure cannot prevent the next submission being processed."""
import json
import os
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ids = ["00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"]
reports = []
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass
    def answer(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(body).encode())
    def do_GET(self):
        self.answer(200, {"faces": [{"id": item, "validation": "passed"} for item in ids]})
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        item = self.path.split("/")[3]
        if self.path.endswith("/claim"):
            self.answer(200, {"claimed": True, "lease": "lease-" + item, "aiEnabled": True})
        elif self.path.endswith("/report"):
            reports.append((item, body["status"]))
            self.answer(200, {"ok": True})
        elif self.path.endswith("/ai-review"):
            assert self.headers.get("X-Moderation-Lease") == "lease-" + item
            self.answer(502 if item == ids[0] else 200, {"recommendation": "approve", "confidence": "high"})
        else:
            self.answer(404, {"error": "unexpected action"})

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    result = subprocess.run(["./gradlew", ":workbench:moderate", "--console=plain"],
        cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True, timeout=180,
        env={**os.environ, "BFG_CATALOG_URL": f"http://127.0.0.1:{server.server_port}", "BFG_MODERATOR_TOKEN": "isolated-test-token"})
    assert result.returncode != 0, "failed attempts must remain visible to the runner"
    assert (ids[0], "retry") in reports and (ids[1], "complete") in reports, reports
    assert "isolated-test-token" not in result.stdout + result.stderr
    print("Moderation failure isolation and credential-safe logging verified.")
finally:
    server.shutdown()
