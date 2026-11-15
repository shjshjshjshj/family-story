"""Exercise the actual vehicle UI and companion HTTP endpoint via adb (no cloud)."""
import json, os, re, subprocess, time, urllib.request, urllib.error
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path(os.environ.get('ANDROID_HOME', str(Path.home()/'AppData/Local/Android/Sdk'))) / 'platform-tools/adb.exe')
EVIDENCE = ROOT/'evidence'
EVIDENCE.mkdir(exist_ok=True)

def adb(*args):
    result = subprocess.run([ADB, *args], capture_output=True, check=True)
    return result.stdout.decode('utf-8', errors='replace')

def tree():
    adb('shell','uiautomator','dump','/sdcard/cabin-smoke.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/cabin-smoke.xml'))

def tap(label, exact=True):
    for node in tree().iter('node'):
        text = node.get('text','')
        if text == label if exact else label in text:
            if node.get('enabled') != 'true':
                raise AssertionError(f'Disabled: {label}')
            x1,y1,x2,y2 = map(int, re.findall(r'\d+',node.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
            time.sleep(.3)
            return
    raise AssertionError(f'UI text missing: {label}')

def capture(name):
    adb('shell','screencap','-p','/sdcard/cabin-smoke.png')
    adb('pull','/sdcard/cabin-smoke.png',str(EVIDENCE/f'{name}.png'))

def api(path, body=None, token=None):
    headers = {'Content-Type':'application/json'}
    if token:
        headers['Authorization'] = 'Bearer '+token
    request = urllib.request.Request('http://127.0.0.1:8787'+path, data=json.dumps(body).encode() if body else None, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

def main():
    adb('forward','tcp:8787','tcp:8787')
    adb('shell','am','start','-n','kr.cabinstory/.MainActivity')
    time.sleep(1)
    tap('여행 홈')
    capture('vehicle-home')
    tap('가족 연결')
    texts = [node.get('text','') for node in tree().iter('node')]
    url = next(text for text in texts if re.match(r'http://[^ ]+/phone#',text))
    invitation = url.split('#')[1]
    code,joined = api('/api/join',{'name':'QA 동승자','invitation':invitation})
    assert code == 200, joined
    token = joined['token']
    code,state = api('/api/state',token=token)
    assert code == 200 and state['tripId'] == 'gyeongju-family-01'
    code,_ = api('/api/edit',{'action':'add','placeId':'museum','revision':state['revision']},token)
    assert code == 403
    print('PASS: default viewer cannot edit',flush=True)
    tap('권한 관리');tap('일정 편집 허용')
    _,state = api('/api/state',token=token)
    old = state['revision']
    assert next(m for m in state['members'] if m['id']==joined['memberId'])['role']=='editor'
    code,result = api('/api/edit',{'action':'add','placeId':'museum','revision':old},token)
    assert code == 200, result
    _,state = api('/api/state',token=token)
    assert 'museum' in state['stops'] and state['approvedRevision'] < state['routeRevision']
    tap('여행 홈');capture('vehicle-synced-plan')
    assert any('국립경주박물관' in node.get('text','') for node in tree().iter('node'))
    print('PASS: phone HTTP edit reaches actual vehicle UI; driver confirmation pending',flush=True)
    code,_ = api('/api/edit',{'action':'remove','placeId':'museum','revision':old},token)
    assert code == 409
    code,_ = api('/api/edit',{'action':'remove','placeId':'museum','revision':state['revision']},token)
    assert code == 200
    print('PASS: stale edit rejected; latest edit accepted',flush=True)
    tap('함께 짠 일정');tap('변경된 일정 확인')
    _,state = api('/api/state',token=token)
    assert state['approvedRevision']==state['routeRevision']
    capture('vehicle-plan')
    tap('가족 연결');tap('권한 관리');tap('연결 및 권한 철회')
    assert api('/api/state',token=token)[0] == 403
    print('PASS: native driver approval and revocation reflected over HTTP',flush=True)
    tap('연동 진단')
    nodes = tree()
    if any(node.get('text')=='위치 접근 허용' for node in nodes.iter('node')):
        tap('위치 접근 허용')
        tap('허용')
    tap('연동 상태 새로고침')
    capture('vehicle-sdk-diagnostics')
    print('PASS: location permission path and SDK diagnostic display',flush=True)
    tap('여행 홈');tap('시연 모드');tap('모의 주행')
    nodes = tree()
    rail = [n for n in nodes.iter('node') if n.get('text')=='함께 짠 일정']
    assert rail and rail[0].get('enabled')=='false'
    assert not any(n.get('class')=='android.widget.ImageView' and n.get('content-desc','').endswith('창작 풍경 삽화') for n in nodes.iter('node'))
    capture('vehicle-driving-demo')
    tap('시연 모드');tap('실제 SDK')
    print('PASS: driving demo locks editing and removes visual story; restored actual SDK mode',flush=True)
    capture('vehicle-final')
    tap('이야기 듣기',exact=False)
    time.sleep(1)
    tap('잠시 멈추기',exact=False)
    capture('vehicle-audio-paused')
    tap('이야기 듣기',exact=False)
    deadline = time.monotonic()+50
    pid = adb('shell','pidof','kr.cabinstory').strip()
    while time.monotonic()<deadline:
        logs = adb('logcat','-d','--pid='+pid,'-s','CabinStory')
        if 'TTS onDone paragraph=2' in logs:
            break
        time.sleep(1)
    (EVIDENCE/'app-runtime.log').write_text(logs,encoding='utf-8')
    assert 'TTS onDone paragraph=2' in logs, 'Full audio playback was not observed'
    print('PASS: full Korean on-device TTS completion recorded',flush=True)

if __name__ == '__main__':
    main()
