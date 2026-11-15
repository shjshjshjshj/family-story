"""Text and picture generation through an OpenAI-compatible server on this PC (loopback only).

Set STORYLAB_LLM_URL (for example http://127.0.0.1:2048/v1) and STORYLAB_LLM_KEY. The key is never logged or written out.
"""
import base64, json, os, re, urllib.request, urllib.error

TEXT_MODEL = "gemini-4-argon"
IMAGE_MODEL = "gemini-nano-banana-2.1"

def _provider():
    base = os.environ.get("STORYLAB_LLM_URL", "").rstrip("/")
    if not base: raise RuntimeError("STORYLAB_LLM_URL이 없습니다. 이 PC의 생성 서버 주소를 넣어 주세요.")
    if not re.match(r"^http://(127\.0\.0\.1|localhost)(:\d+)?/", base + "/"):
        raise RuntimeError("생성 서버 주소가 이 PC(loopback)가 아니어서 호출하지 않았습니다.")
    return base, os.environ.get("STORYLAB_LLM_KEY", "")

def _post(path, body, timeout):
    base, key = _provider()
    req = urllib.request.Request(base + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        try: detail = json.loads(detail)["error"]["message"]
        except Exception: pass
        raise RuntimeError("생성 서버 HTTP %d: %s" % (e.code, str(detail)[:200]))

def text(prompt, model=TEXT_MODEL, timeout=240):
    """One answer as plain text."""
    out = json.loads(_post("/chat/completions", {"model": model, "messages": [{"role": "user", "content": prompt}]}, timeout))
    return out["choices"][0]["message"].get("content") or ""

def json_object(prompt, model=TEXT_MODEL):
    """One answer parsed as a JSON object; the model may wrap it in a code fence."""
    raw = text(prompt, model)
    a, b = raw.find("{"), raw.rfind("}")
    if a < 0 or b <= a: raise ValueError("JSON이 아닌 응답: " + raw[:120])
    return json.loads(raw[a:b + 1])

def image(prompt, model=IMAGE_MODEL, timeout=300):
    """One picture as bytes. This server only makes pictures over a streamed answer."""
    raw = _post("/chat/completions", {"model": model, "stream": True, "messages": [{"role": "user", "content": prompt}]}, timeout)
    content = []
    for line in raw.splitlines():
        if not line.startswith("data: ") or line.strip() == "data: [DONE]": continue
        try: delta = json.loads(line[6:])["choices"][0].get("delta", {})
        except Exception: continue
        piece = delta.get("content")
        if isinstance(piece, str): content.append(piece)
        elif isinstance(piece, list):
            for part in piece:
                url = (part.get("image_url") or {}).get("url") if isinstance(part, dict) else None
                if url: content.append(url)
                elif isinstance(part, dict) and part.get("text"): content.append(part["text"])
        for img in delta.get("images") or []:
            url = (img.get("image_url") or {}).get("url") if isinstance(img, dict) else None
            if url: content.append(url)
    found = re.search(r"data:image/(?:png|jpeg|webp);base64,([A-Za-z0-9+/=]+)", "".join(content)) or re.search(r"data:image/(?:png|jpeg|webp);base64,([A-Za-z0-9+/=]+)", raw)
    if not found: raise RuntimeError("그림이 응답에 없습니다: " + "".join(content)[:120])
    return base64.b64decode(found.group(1))
