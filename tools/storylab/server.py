"""Local page for reviewing generated kids content. Loopback only: http://127.0.0.1:8790

  python server.py
"""
import http.server, io, json, os, re, threading, time, urllib.parse
import pipeline, spec

HERE = os.path.dirname(os.path.abspath(__file__))
SPRITES = os.path.join(pipeline.ROOT, "app", "src", "main", "assets", "sprites")
TYPES = {".html": "text/html; charset=utf-8", ".json": "application/json; charset=utf-8", ".png": "image/png", ".jpg": "image/jpeg"}
busy = threading.Lock()

class Handler(http.server.BaseHTTPRequestHandler):
    def _send(self, code, body, kind="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code); self.send_header("Content-Type", kind); self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(data)
    def _file(self, base, relative):
        path = os.path.normpath(os.path.join(base, relative))
        if not path.startswith(os.path.normpath(base) + os.sep) or not os.path.isfile(path): return self._send(404, {"error": "없음"})
        self._send(200, open(path, "rb").read(), TYPES.get(os.path.splitext(path)[1], "application/octet-stream"))
    def do_GET(self):
        path = urllib.parse.unquote(urllib.parse.urlparse(self.path).path)
        if path == "/": return self._file(HERE, "index.html")
        if path == "/api/options": return self._send(200, {"purposes": spec.PURPOSES, "ages": spec.AGES, "frames": spec.FRAMES, "busy": busy.locked()})
        if path == "/api/runs":
            runs = []
            for name in sorted(os.listdir(pipeline.OUT), reverse=True) if os.path.isdir(pipeline.OUT) else []:
                try:
                    s = json.load(io.open(os.path.join(pipeline.OUT, name, "run.json"), encoding="utf-8"))
                    runs.append({"id": s["id"], "status": s["status"], "route": s["route"], "places": len(s["places"]), "seconds": s.get("seconds")})
                except Exception: pass
            return self._send(200, runs)
        m = re.fullmatch(r"/api/run/([A-Za-z0-9_-]{1,40})", path)
        if m: return self._file(pipeline.OUT, os.path.join(m.group(1), "run.json"))
        if path.startswith("/out/"): return self._file(pipeline.OUT, path[5:])
        if path.startswith("/sprites/"): return self._file(SPRITES, path[9:])
        self._send(404, {"error": "없음"})
    def do_POST(self):
        if urllib.parse.urlparse(self.path).path != "/api/run": return self._send(404, {"error": "없음"})
        if self.headers.get("Origin") not in (None, "http://127.0.0.1:8790", "http://localhost:8790"): return self._send(403, {"error": "이 페이지에서만 시작할 수 있어요."})
        try: body = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length", "0")), 20000)).decode("utf-8"))
        except Exception: return self._send(400, {"error": "요청을 읽지 못했어요."})
        route = [str(n).strip()[:40] for n in body.get("route", []) if str(n).strip()][:5]
        purpose = body.get("purpose") if body.get("purpose") in spec.PURPOSES else "auto"
        age = body.get("age") if body.get("age") in spec.AGES else "auto"
        if not route: return self._send(400, {"error": "장소를 한 곳 이상 적어 주세요."})
        if not busy.acquire(blocking=False): return self._send(409, {"error": "다른 생성이 진행 중이에요."})
        run_id = time.strftime("run-%m%d-%H%M%S")
        def work():
            try: pipeline.generate(run_id, route, purpose, age, bool(body.get("images", True)))
            finally: busy.release()
        threading.Thread(target=work, daemon=True).start()
        self._send(200, {"id": run_id})
    def log_message(self, *args): pass

if __name__ == "__main__":
    print("Story Lab: http://127.0.0.1:8790")
    http.server.ThreadingHTTPServer(("127.0.0.1", 8790), Handler).serve_forever()
