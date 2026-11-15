"""Generates the kids content for one route: source text -> trip frame -> place episodes -> pictures -> cut stickers.

Everything is written under out/<run>/ and run.json is rewritten after every step so the page can follow along.
"""
import io, json, os, re, subprocess, sys, time, traceback, urllib.parse, urllib.request
from PIL import Image
import llm, spec

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, "out")
TOUR = "https://apis.data.go.kr/B551011/KorService2/"

def _tour_key():
    for line in io.open(os.path.join(ROOT, "local.properties"), encoding="utf-8"):
        if line.startswith("TOUR_API_KEY="): return line.split("=", 1)[1].strip()
    return ""

def _tour(path, query):
    url = TOUR + path + "?serviceKey=" + _tour_key() + "&MobileOS=AND&MobileApp=FamilyStory&_type=json&" + urllib.parse.urlencode(query)
    with urllib.request.urlopen(url, timeout=20) as r: return json.loads(r.read().decode("utf-8"))["response"]["body"]

def source(name):
    """Official overview of a place from the Korea Tourism Organization, the same source the car app uses."""
    # Only a sight or cultural facility whose title is this name (or ends with it, like "전주 경기전") is accepted.
    # A shop or lodging that merely carries the name is not the place, and a wrong source would make a wrong story.
    plain = lambda t: str(t or "").replace(" ", "")
    item = None
    for keyword in (name, name[:2] + " " + name[2:]):
        items = (_tour("searchKeyword2", {"numOfRows": 30, "keyword": keyword}).get("items") or {})
        items = items.get("item") if isinstance(items, dict) else None
        good = [i for i in (items or []) if str(i.get("contenttypeid")) in ("12", "14")
                and (plain(i.get("title")) == plain(name) or plain(i.get("title")).endswith(plain(name)))]
        if good: item = sorted(good, key=lambda i: len(plain(i.get("title"))))[0]; break
    if item is None: return None
    detail = _tour("detailCommon2", {"contentId": item.get("contentid", "")})
    overview = (detail.get("items") or {}).get("item", [{}])[0].get("overview", "")
    overview = re.sub(r"\s+", " ", re.sub(r"<[^>]*>", " ", overview).replace("&nbsp;", " ")).strip()
    return {"name": name, "matched": item.get("title", ""), "address": item.get("addr1", ""), "overview": overview, "photo": item.get("firstimage", "")}

class Run:
    def __init__(self, run_id, route, purpose, age, images):
        self.dir = os.path.join(OUT, run_id)
        os.makedirs(self.dir, exist_ok=True)
        self.state = {"id": run_id, "status": "running", "route": route, "purpose": purpose, "age": age, "images": images,
                      "models": {"text": llm.TEXT_MODEL, "image": llm.IMAGE_MODEL}, "log": [], "notes": [], "trip": None, "places": [], "started": time.time()}
        self.save()
    def save(self):
        tmp = os.path.join(self.dir, "run.json.tmp")
        io.open(tmp, "w", encoding="utf-8").write(json.dumps(self.state, ensure_ascii=False, indent=1))
        os.replace(tmp, os.path.join(self.dir, "run.json"))
    def log(self, message):
        self.state["log"].append({"t": round(time.time() - self.state["started"], 1), "m": message}); self.save()
    def note(self, where, message):
        self.state["notes"].append({"where": where, "m": message}); self.save()

def _timed(run, label, call):
    start = time.time()
    try:
        value = call()
        run.log("%s · %.0f초" % (label, time.time() - start))
        return value
    except Exception as e:
        run.log("%s 실패 · %s" % (label, str(e)[:160]))
        return None

def _save_scene(data, path):
    Image.open(io.BytesIO(data)).convert("RGB").resize((1280, 720), Image.LANCZOS).save(path, quality=88)

def _cut(sheet_bytes, folder, names, size, rows=2):
    sheet = os.path.join(folder, "_sheet.png")
    os.makedirs(folder, exist_ok=True)
    Image.open(io.BytesIO(sheet_bytes)).convert("RGB").save(sheet)
    done = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "cut_stickers.py"), sheet, folder, ",".join(names), str(size), "magenta", str(rows)],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    return done.returncode == 0, (done.stderr or done.stdout).strip()[-160:]

def generate(run_id, route, purpose="auto", age="auto", images=True):
    run = Run(run_id, route, purpose, age, images)
    try:
        places = []
        for name in route:
            info = _timed(run, "소개문 가져오기 · " + name, lambda: source(name))
            if not info or len(info["overview"]) < 80:
                run.note(name, "관광공사 소개문을 찾지 못해 이 장소는 건너뜀"); continue
            places.append(info)
        if not places: raise RuntimeError("소개문이 있는 장소가 없습니다.")

        notes = []
        raw = _timed(run, "여행 이야기 틀 정하기", lambda: llm.json_object(spec.trip_prompt(places, purpose, age)))
        trip = spec.check_trip(raw or {}, places, notes)
        for n in notes: run.note("여행 틀", n)
        run.state["trip"] = trip; run.save()

        for i, info in enumerate(places):
            pid = "p%d" % (i + 1)
            npc = trip["cast"][i]
            adult = spec.paragraphs(info["overview"])
            kit, notes = None, []
            for attempt in (1, 2):
                raw = _timed(run, "%s 원고 쓰기%s" % (info["name"], " (다시)" if attempt == 2 else ""),
                             lambda: llm.json_object(spec.place_prompt(info, i, len(places), trip, npc, places[i - 1]["name"] if i else "", purpose, age)))
                try:
                    notes = []
                    kit = spec.check_place(raw or {}, adult, notes); break
                except ValueError as e:
                    run.note(info["name"], "검증 탈락: %s" % e)
            if kit is None: run.note(info["name"], "원고를 쓰지 못해 건너뜀"); continue
            for n in notes: run.note(info["name"], n)
            for page in kit["kids"]:
                pose = page.pop("npc_pose", "")
                if pose: page["npc"] = {"id": npc["id"], "name": "%s %s" % (npc["kind"], npc["name"]), "pose": pose}
            place = {"id": pid, "name": info["name"], "matched": info["matched"], "address": info["address"], "summary": adult[0] if adult else "",
                     "adult": adult, "sourceTitle": "한국관광공사 TourAPI", "npc": npc, "assets": {"cuts": [], "npc": [], "props": []}, **kit}
            run.state["places"].append(place); run.save()

            if not images: continue
            folder = os.path.join(run.dir, pid)
            os.makedirs(folder, exist_ok=True)
            for cut in kit["cuts"]:
                data = _timed(run, "%s 배경 '%s' 그리기" % (info["name"], cut["id"]), lambda: llm.image(spec.scene_prompt(info["name"], cut["look"])))
                if data:
                    try: _save_scene(data, os.path.join(folder, "cut-%s.jpg" % cut["id"])); place["assets"]["cuts"].append(cut["id"])
                    except Exception as e: run.note(info["name"], "배경 '%s' 저장 실패: %s" % (cut["id"], e))
            data = _timed(run, "%s 친구 %s 그리기" % (info["name"], npc["name"]), lambda: llm.image(spec.npc_sheet_prompt(npc)))
            if data:
                ok, why = _cut(data, os.path.join(folder, "npc"), spec.POSES, 360)
                if ok: place["assets"]["npc"] = list(spec.POSES)
                else: run.note(info["name"], "친구 스티커 자르기 실패: " + why)
            if kit["props"]:
                data = _timed(run, "%s 소품 %d개 그리기" % (info["name"], len(kit["props"])), lambda: llm.image(spec.props_sheet_prompt(kit["props"])))
                if data:
                    ok, why = _cut(data, os.path.join(folder, "props"), [p["id"] for p in kit["props"]], 300)
                    if ok: place["assets"]["props"] = [p["id"] for p in kit["props"]]
                    else: run.note(info["name"], "소품 스티커 자르기 실패: " + why)
            run.save()
        run.state["status"] = "done"
    except Exception as e:
        run.state["status"] = "failed"
        run.state["error"] = str(e)[:300]
        run.log("중단: " + traceback.format_exc().strip().splitlines()[-1][:200])
    run.state["seconds"] = round(time.time() - run.state["started"], 1)
    run.save()
    return run.state

if __name__ == "__main__":
    names = sys.argv[1].split(",") if len(sys.argv) > 1 else ["경기전", "전주한옥마을", "덕진공원"]
    state = generate(time.strftime("run-%H%M%S"), names, images="--no-images" not in sys.argv)
    print(state["status"], state.get("seconds"), [p["name"] for p in state["places"]])
