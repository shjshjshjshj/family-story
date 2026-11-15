"""The kids-content specification: what is asked of the model, and what is accepted from it.

Three layers, as agreed for the service:
  trip  - one narrative for the whole route (a frame, a goal, what each place hands over)
  place - one eight-page episode per place with its own character, props and scene cuts
  moment- values the vehicle already knows (order, previous place) filled in by code, never typed by the user
The page / quiz / mission rules are the same as StoryKit.java in the car app; fixed wording is assembled here.
"""
import re

ACTIVITIES = ["wave", "point", "walk", "think", "read", "cheer", "stargaze", "photo", "eat", "surprise", "sleep", "listen"]
POSES = ["wave", "point", "surprise", "cheer"]
PAGES, MIN_PAGES, QUIZZES, FACTS, MAX_PROPS = 8, 5, 3, 3, 5
FRAMES = {
    "collect": "모으기 - 장소마다 친구가 조각을 하나씩 건네고, 마지막 장소에서 모두 모여 완성된다",
    "riddle": "수수께끼 - 장소마다 실마리를 하나씩 얻어 마지막에 수수께끼를 푼다",
    "help": "돕기 - 곤란에 빠진 친구를 돕기 위해 장소마다 필요한 것을 하나씩 구한다",
    "letter": "편지 전하기 - 장소마다 친구에게 소식을 전하고 답장을 받아 다음 장소로 가져간다",
}
PURPOSES = {"auto": "균형 있게", "learn": "배움 위주(사실과 관찰을 조금 더)", "tour": "관광 위주(볼거리와 분위기를 조금 더)", "play": "놀이 위주(상상과 활동을 조금 더)"}
AGES = {"auto": "5~7세", "4-5": "4~5세(문장을 더 짧게, 쪽당 2문장)", "6-7": "6~7세", "8-9": "8~9세(조금 더 자세히)"}

STICKER_STYLE = ("Style: soft gouache and colored-pencil picture-book texture, gentle rounded shapes, warm pastel palette, "
                 "each figure has a thick clean white sticker outline. Background: perfectly flat solid pure magenta (#FF00FF), "
                 "no shadows, no gradients, no text, no letters, no borders. Square image.")
SCENE_STYLE = ("Create an image: a wide 16:9 children's storybook illustration in soft gouache and colored-pencil style, full painted scene. "
               "%s Leave open empty space in the lower left and lower right corners for character stickers. No people, no animals, no text, no letters.")

def trip_prompt(places, purpose, age):
    lines = ["너는 가족 자동차 여행 앱의 어린이 이야기 기획자다. 아래 여행의 장소들을 하나로 꿰는 이야기 틀을 정한다.",
             "", "[여행 장소 순서]"] + ["%d. %s - %s" % (i + 1, p["name"], p["overview"][:160]) for i, p in enumerate(places)] + [
             "", "[목적] " + PURPOSES[purpose], "[독자] " + AGES[age],
             "[길잡이] 여행 내내 함께하는 동물 캐릭터. 이름은 반드시 {g} 라고만 쓴다.",
             "", "[틀 후보] 이 가운데 장소들과 가장 잘 맞는 것 하나를 고른다:"] + ["- %s: %s" % kv for kv in FRAMES.items()] + [
             "", "[정할 것]",
             "- frame: 고른 틀의 id",
             "- title: 여행 이야기 제목(14자 이내)",
             "- goal: {g}가 이 여행에서 이루려는 일 한 문장(반말)",
             "- finale: 마지막 장소에서 이야기가 끝나는 장면 한 문장(반말)",
             "- cast: 장소마다 한 명씩, 그 장소에 사는 친구(NPC). 실존 인물이 아닌 동물이나 물건. 장소의 특징과 어울리게.",
             "  - name: 두 글자 한글 이름 / kind: 무엇인지 한글로(예: 별지기 부엉이) / look: 생김새를 영어 한 문장으로(색, 옷, 소품. 귀엽고 둥글게)",
             "  - gift: 이 친구가 {g}에게 건네는 것(한글, 틀과 맞게) / gift_look: 그 물건의 생김새를 영어 짧은 구로",
             "", "무섭거나 슬픈 설정, 실존 인물, 상표가 있는 캐릭터는 쓰지 않는다.",
             'JSON만 출력: {"frame":"","title":"","goal":"","finale":"","cast":[{"place":"","name":"","kind":"","look":"","gift":"","gift_look":""}]}']
    return "\n".join(lines)

def place_prompt(place, index, total, trip, npc, previous, purpose, age):
    return "\n".join([
        "너는 가족 자동차 여행 앱의 어린이 안내 원고를 쓰는 작가다. 아래 [소개]만 근거로 [장소]의 원고를 쓴다.",
        "",
        "[독자] %s 어린이. 차 안에서 소리로 듣고, 정차했을 때 그림책 화면으로 본다." % AGES[age],
        "[목적] " + PURPOSES[purpose],
        "[길잡이] 안내하는 동물 캐릭터의 이름은 반드시 {g} 라고만 쓴다.",
        "",
        "[여행 이야기] 제목: %s / 목표: %s" % (trip["title"], trip["goal"]),
        "[지금] 전체 %d곳 중 %d번째 장소. %s" % (total, index + 1, ("앞서 다녀온 곳: " + previous) if previous else "첫 장소다."),
        "[이 장소의 친구] %s (%s). 이 친구가 {g}에게 건네는 것: %s" % (npc["name"], npc["kind"], npc["gift"]),
        ("[끝맺음] 마지막 장소다. 8쪽에서 이렇게 끝난다: " + trip["finale"]) if index == total - 1 else "[끝맺음] 8쪽에서 친구가 선물을 건네고, 다음 장소가 기다린다는 말로 끝난다.",
        "",
        "[사실 규칙]",
        "- [소개]에 없는 연도, 숫자, 인물, 전설, 시설을 만들지 않는다. 확실하지 않으면 쓰지 않는다.",
        "- 운영 시간, 요금, 예약 같은 바뀌는 정보는 쓰지 않는다. 친구(NPC)와 선물은 상상이므로 사실처럼 설명하지 않는다.",
        "",
        "[말투] 다정한 반말. 문장은 짧게. 숫자는 한글로. 괄호, 영어, 기호, 이모지 금지. 무섭거나 슬픈 표현 금지.",
        "",
        "[pages] 그림책 %d쪽. 제목 12자 이내, 본문 2~4문장." % PAGES,
        "- 1쪽 만남: {g}가 인사하고 이곳을 한마디로 소개한다. 친구가 처음 나타난다.",
        "- 2~6쪽 발견: [소개]에서 아이가 신기해할 내용을 한 쪽에 하나씩. 크기는 아이가 아는 것에 빗대어.",
        "- 7쪽 약속: 보호자와 함께 다니기, 올라가거나 만지지 않고 눈으로 보기.",
        "- 8쪽 선물: [끝맺음]대로.",
        "- activity: 본문 속 {g}의 행동. " + ", ".join(ACTIVITIES) + " 중 하나.",
        "- npc_pose: 그 쪽에 친구가 나오면 " + ", ".join(POSES) + " 중 하나, 안 나오면 빈 문자열. cheer는 선물을 든 모습이라 8쪽에만 쓴다.",
        "- cut: 그 쪽의 배경. cuts에 정한 id 중 하나. props: 그 쪽에 등장하는 소품 id 0~2개.",
        "",
        "[cuts] 배경 그림 2장. 첫 번째 id는 반드시 main(장소 전체가 보이는 전경). 두 번째는 다른 시간대나 가까이 본 모습.",
        "- look: 그릴 내용을 영어 한두 문장으로. [소개]에 나온 모습만. 사람과 동물은 넣지 않는다.",
        "[props] 이야기에 나오는 소품 3~%d개. id는 영어 소문자 한 단어, name은 한글, look은 영어 짧은 구. 선물도 포함한다." % MAX_PROPS,
        "",
        "[quizzes] 가는 길에 낼 퀴즈 %d개. [소개]에 나온 사실로만." % QUIZZES,
        "- question 물음 한 문장 / option1, option2 짧은 보기(틀린 보기는 엉뚱하지만 무섭지 않게) / answer 1 또는 2(한쪽으로 몰리지 않게) / explain 정답을 한 문장으로('정답은' 금지)",
        "[lookout] 도착 직전 창밖에서 찾아볼 것. '창밖에 ~ 보이는지 찾아봐.' 꼴.",
        "[recall] 이곳을 떠날 때 떠올려 줄 한 문장. '~했지?' 꼴.",
        "[mission] 도착해서 눈으로 찾아볼 것. '~ 찾아볼까?' 또는 '~ 세어 볼까?' 꼴. 만지거나 올라가야 하는 일은 안 된다.",
        "",
        "[장소] " + place["name"],
        "[소개] " + place["overview"][:1500],
        "",
        'JSON만 출력: {"pages":[{"title":"","text":"","activity":"","npc_pose":"","cut":"","props":[]}],"cuts":[{"id":"main","look":""},{"id":"","look":""}],'
        '"props":[{"id":"","name":"","look":""}],"quizzes":[{"question":"","option1":"","option2":"","answer":1,"explain":""}],"lookout":"","recall":"","mission":""}'])

def _clean(v): return re.sub(r"\s+", " ", re.sub(r"[*_#`]", "", str(v or ""))).strip()
def _slug(v): return re.sub(r"[^a-z0-9]", "", str(v or "").lower())[:20]
def _english(v, limit=260):
    text = _clean(v)
    return text[:limit] if re.fullmatch(r"[\x20-\x7E]+", text or " ") else ""

def check_trip(raw, places, notes):
    frame = raw.get("frame") if raw.get("frame") in FRAMES else "collect"
    if frame != raw.get("frame"): notes.append("틀 id가 후보에 없어 '모으기'로 바꿈")
    trip = {"frame": frame, "title": _clean(raw.get("title"))[:20] or "우리 가족 여행", "goal": _clean(raw.get("goal"))[:120], "finale": _clean(raw.get("finale"))[:140], "cast": []}
    cast = raw.get("cast") or []
    for i, place in enumerate(places):
        c = cast[i] if i < len(cast) and isinstance(cast[i], dict) else {}
        name, look = _clean(c.get("name"))[:6], _english(c.get("look"))
        if not name or not look:
            notes.append("%s: 친구 설정이 비어 기본 친구로 대체" % place["name"])
            name, look, c = "토리", "a small round friendly squirrel with a little navy scarf", {"kind": "길 안내 다람쥐", "gift": "작은 도토리", "gift_look": "a small shiny acorn"}
        trip["cast"].append({"place": place["name"], "id": "npc%d" % (i + 1), "name": name, "kind": _clean(c.get("kind"))[:20] or "친구",
                             "look": look, "gift": _clean(c.get("gift"))[:20] or "작은 선물", "gift_look": _english(c.get("gift_look"), 80) or "a small wrapped gift"})
    return trip

def check_place(raw, adult, notes):
    """Returns the place kit in the app's shape, or raises ValueError when too little is usable."""
    cuts, seen = [], set()
    for c in (raw.get("cuts") or [])[:2]:
        cid, look = _slug(c.get("id") if isinstance(c, dict) else ""), _english(c.get("look") if isinstance(c, dict) else "")
        if not look: notes.append("배경 설명이 영어가 아니거나 비어 버림"); continue
        if not cuts: cid = "main"
        if not cid or cid in seen: cid = "cut%d" % (len(cuts) + 1)
        seen.add(cid); cuts.append({"id": cid, "look": look})
    if not cuts: raise ValueError("배경 설명이 없음")
    props, prop_ids = [], set()
    for p in (raw.get("props") or [])[:MAX_PROPS]:
        if not isinstance(p, dict): continue
        pid, look = _slug(p.get("id")), _english(p.get("look"), 100)
        if not pid or pid in prop_ids or not look: notes.append("소품 '%s' 버림(id 또는 설명 불량)" % p.get("id")); continue
        prop_ids.add(pid); props.append({"id": pid, "name": _clean(p.get("name"))[:12], "look": look})
    pages = []
    for p in (raw.get("pages") or []):
        if len(pages) == PAGES or not isinstance(p, dict): break
        title, text, activity = _clean(p.get("title")), _clean(p.get("text")), str(p.get("activity") or "").strip()
        if not title or len(title) > 24 or not (20 <= len(text) <= 320): notes.append("쪽 '%s' 버림(길이 %d자)" % (title[:10], len(text))); continue
        if re.search(r"[A-Za-z(){}\[\]]", text.replace("{g}", "")): notes.append("쪽 '%s': 영어·괄호가 섞여 있음(유지)" % title[:10])
        if activity not in ACTIVITIES: activity = "point"; notes.append("쪽 '%s': 동작 값을 기본값으로" % title[:10])
        page = {"title": title, "text": text, "activity": activity}
        cut = _slug(p.get("cut"))
        if cut in seen and cut != "main": page["cut"] = cut
        pose = str(p.get("npc_pose") or "").strip()
        if pose in POSES: page["npc_pose"] = pose
        chosen = [_slug(x) for x in (p.get("props") or []) if _slug(x) in prop_ids][:2]
        if chosen: page["props"] = chosen
        pages.append(page)
    if len(pages) < MIN_PAGES: raise ValueError("쓸 수 있는 쪽이 %d쪽뿐" % len(pages))
    for i, page in enumerate(pages):                      # cheer shows the gift, so it belongs to the last page only
        if page.get("npc_pose") == "cheer" and i != len(pages) - 1: page["npc_pose"] = "wave"
    pages[-1]["npc_pose"] = "cheer"
    quizzes = []
    for q in (raw.get("quizzes") or []):
        if len(quizzes) == QUIZZES or not isinstance(q, dict): break
        question, one, two = _clean(q.get("question")), re.sub(r"[.!?]+$", "", _clean(q.get("option1"))), re.sub(r"[.!?]+$", "", _clean(q.get("option2")))
        explain = re.sub(r"^정답은\s*", "", _clean(q.get("explain")))
        if not (6 <= len(question) <= 90) or not one or not two or one == two or len(one) > 40 or len(two) > 40 or q.get("answer") not in (1, 2) or not (0 < len(explain) <= 90):
            notes.append("퀴즈 버림: " + question[:20]); continue
        quizzes.append({"q": "%s 일 번, %s. 이 번, %s." % (question, one, two), "a": "정답은 %s 번! %s" % ("일" if q["answer"] == 1 else "이", explain), "answer": q["answer"]})
    if len({q["answer"] for q in quizzes}) == 1 and len(quizzes) > 1: notes.append("퀴즈 정답이 모두 %d번으로 몰림" % quizzes[0]["answer"])
    line = lambda v: _clean(v) if 8 <= len(_clean(v)) <= 90 else ""
    journey = {"kidsQuizzes": [{"q": q["q"], "a": q["a"]} for q in quizzes], "facts": facts(adult)}
    if line(raw.get("lookout")): journey["kidsLookout"] = line(raw.get("lookout"))
    if line(raw.get("recall")): journey["kidsRecall"] = line(raw.get("recall"))
    out = {"kids": pages, "journey": journey, "cuts": cuts, "props": props}
    mission = line(raw.get("mission"))
    if mission.endswith("?"): out["mission"] = {"question": mission, "activity": "point"}
    else: notes.append("미션 문장이 규격과 달라 버림")
    return out

def paragraphs(overview):
    """Same split as the car app: up to three paragraphs sized for speech."""
    out, current = [], ""
    for s in re.split(r"(?<=[.!?。])\s+", overview):
        if len(out) == 3: break
        if current and len(current) + len(s) > 170: out.append(current.strip()); current = ""
        current += s + " "
    if len(out) < 3 and current.strip(): out.append(current.strip())
    return out

def facts(adult):
    out = []
    for p in adult:
        end = p.find("다. ")
        s = p[:end + 2] if end > 0 else p
        if 15 <= len(s) <= 160 and len(out) < FACTS: out.append(s)
    return out

def npc_sheet_prompt(npc):
    return ("Create an image: a character sticker sheet for a children's storybook app. One character: %s. Show the SAME character in 4 poses arranged in a 2x2 grid "
            "with wide gaps between them: (1) waving hello, (2) pointing forward, (3) surprised with wide eyes, (4) happily holding %s. %s" % (npc["look"], npc["gift_look"], STICKER_STYLE))

def props_sheet_prompt(props):
    listing = ", ".join("(%d) %s" % (i + 1, p["look"]) for i, p in enumerate(props))
    return ("Create an image: a sticker sheet for a children's storybook app. %d separate objects in a grid of two rows with wide gaps between them, "
            "none of them touching: %s. %s" % (len(props), listing, STICKER_STYLE))

def scene_prompt(place_name, look):
    return SCENE_STYLE % ("The place is %s in Korea. %s" % (place_name, look))
