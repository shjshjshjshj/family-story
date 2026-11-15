"""Pre-generates demo media for the bundled places (run once on a dev PC).
General photo: TourAPI(한국관광공사) firstimage. Kids background: Gemini image model.
Keys come from local.properties and are never printed."""
import base64, io, json, os, sys, urllib.parse, urllib.request
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
props = dict(l.strip().split('=', 1) for l in io.open(os.path.join(ROOT, 'local.properties'), encoding='utf-8') if '=' in l and not l.startswith('#'))
PLACES = {'cheomseongdae': '경주 첨성대', 'daereungwon': '경주 대릉원', 'wolji': '경주 동궁과 월지', 'museum': '국립경주박물관'}
EN = {'cheomseongdae': 'Cheomseongdae, the ancient stone astronomical observatory tower of Silla in Gyeongju, Korea, on a grassy field',
      'daereungwon': 'Daereungwon, the big round grassy royal tomb mounds of Silla in Gyeongju, Korea, with pine trees and a walking path',
      'wolji': 'Donggung Palace and Wolji Pond in Gyeongju, Korea: traditional Korean pavilions reflected on a calm pond at dusk with warm lantern light',
      'museum': 'Gyeongju National Museum in Korea: a traditional tiled-roof museum building with a large bronze temple bell pavilion in the garden'}
def fetch(url, data=None, headers=None, timeout=120):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    return urllib.request.urlopen(req, timeout=timeout).read()
def save_jpeg(raw, path, width):
    from PIL import Image
    im = Image.open(io.BytesIO(raw)).convert('RGB')
    if im.width > width: im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    im.save(path, 'JPEG', quality=84, optimize=True)
    return im.size
credits = {}
for pid, keyword in PLACES.items():
    photo = os.path.join(ASSETS, 'media', 'photo', pid + '.jpg')
    if not os.path.exists(photo) or '--force' in sys.argv:
        u = ('https://apis.data.go.kr/B551011/KorService2/searchKeyword2?serviceKey=' + props['TOUR_API_KEY'] +
             '&MobileOS=AND&MobileApp=FamilyStory&_type=json&numOfRows=5&keyword=' + urllib.parse.quote(keyword))
        items = json.loads(fetch(u).decode('utf-8'))['response']['body']['items']
        items = items['item'] if items else []
        item = next((i for i in items if i.get('firstimage')), None)
        if not item: print('photo MISSING', pid); continue
        size = save_jpeg(fetch(item['firstimage']), photo, 1280)
        credits[pid] = {'title': item['title'], 'contentId': item['contentid'], 'license': item.get('cpyrhtDivCd', ''), 'source': '한국관광공사 TourAPI'}
        print('photo', pid, item['title'], size, item.get('cpyrhtDivCd'))
    kids = os.path.join(ASSETS, 'media', 'kids', pid + '.jpg')
    if not os.path.exists(kids) or '--force' in sys.argv:
        prompt = ("A warm children's storybook illustration of " + EN[pid] + ". Soft gouache and colored-pencil picture-book texture, "
                  "gentle rounded shapes, cozy pastel palette, friendly daytime mood, wide landscape 16:9 composition with open empty "
                  "ground space in the lower right for a character sticker. No people, no animals, no characters, no text, no letters.")
        body = json.dumps({'contents': [{'parts': [{'text': prompt}]}], 'generationConfig': {'responseModalities': ['IMAGE']}}).encode()
        model = props['GEMINI_IMAGE_MODEL'].replace('models/', '')
        try:
            out = json.loads(fetch('https://generativelanguage.googleapis.com/v1beta/models/' + model + ':generateContent', body,
                                   {'x-goog-api-key': props['GEMINI_API_KEY'], 'Content-Type': 'application/json'}).decode())
        except Exception as e:
            print('kids FAILED', pid, e); continue
        part = next((p for p in out['candidates'][0]['content']['parts'] if 'inlineData' in p), None)
        if not part: print('kids MISSING', pid, json.dumps(out)[:300]); continue
        print('kids', pid, save_jpeg(base64.b64decode(part['inlineData']['data']), kids, 1280))
if credits:
    path = os.path.join(ASSETS, 'media', 'credits.json')
    old = json.load(io.open(path, encoding='utf-8')) if os.path.exists(path) else {}
    old.update(credits)
    json.dump(old, io.open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
