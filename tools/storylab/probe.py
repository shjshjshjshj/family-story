"""Checks the local generation server (STORYLAB_LLM_URL): which endpoints answer and which models it lists. Prints no secrets."""
import json, os, sys, urllib.request, urllib.error

def provider():
    return os.environ.get("STORYLAB_LLM_URL", "").rstrip("/"), os.environ.get("STORYLAB_LLM_KEY", "")

def call(base, key, path, body=None, timeout=120):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return e.code, e.read().decode("utf-8", "replace")[:600]
    except Exception as e: return 0, "%s: %s" % (type(e).__name__, e)

if __name__ == "__main__":
    base, key = provider()
    print("base", base, "| key set:", bool(key))
    status, text = call(base, key, "/models", timeout=20)
    print("GET /models", status)
    if status == 200:
        ids = [m.get("id") for m in json.loads(text).get("data", [])]
        print(len(ids), "models;", [i for i in ids if any(w in i for w in ("argon", "banana", "image", "flash-lite"))][:20])
    model = sys.argv[1] if len(sys.argv) > 1 else "gemini-4-argon"
    for path, body in (("/chat/completions", {"model": model, "messages": [{"role": "user", "content": "한 단어로만 답해: 안녕"}]}),
                       ("/responses", {"model": model, "input": "한 단어로만 답해: 안녕"})):
        status, text = call(base, key, path, body)
        print("POST", path, status, text[:400].replace("\n", " "))
