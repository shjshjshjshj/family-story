"""Asks the local server for one picture and reports the shape of the answer (no image bytes are printed)."""
import json, sys, re
from probe import provider, call
base, key = provider()
model = sys.argv[1] if len(sys.argv) > 1 else "gemini-nano-banana-2.1"
prompt = "Create an image: one small friendly snail sticker with a thick white outline on a flat pure magenta (#FF00FF) background. Square image. No text."
status, text = call(base, key, "/chat/completions", {"model": model, "messages": [{"role": "user", "content": prompt}]}, timeout=240)
print("chat", status, len(text))
def shape(v, depth=0):
    if isinstance(v, dict): return {k: shape(x, depth + 1) for k, x in v.items()} if depth < 6 else "{...}"
    if isinstance(v, list): return [shape(x, depth + 1) for x in v[:3]]
    if isinstance(v, str): return v[:80] + ("...<%d chars>" % len(v) if len(v) > 80 else "")
    return v
try: print(json.dumps(shape(json.loads(text)), ensure_ascii=False)[:1800])
except Exception: print(text[:600])
status, text = call(base, key, "/images/generations", {"model": model, "prompt": prompt, "n": 1, "size": "1024x1024"}, timeout=240)
print("images", status, len(text))
try: print(json.dumps(shape(json.loads(text)), ensure_ascii=False)[:900])
except Exception: print(text[:400])
