'use strict';
const $ = id => document.getElementById(id);
const vehicle = /\/vehicle\/?$/.test(location.pathname);
const DRIVER_KEY = 'cabin-driver-session';
let session = sessionStorage.getItem('cabin-session') || '';
let driverSession = sessionStorage.getItem(DRIVER_KEY) || '';
let state = null, connected = false, busy = false, routeBusy = false;
let lastSignature = '', currentTab = 'route', placeId = '', scene = 0, kids = true;
let searchResults = [], searchStatusText = '', routeError = '', autoTimer = 0;
let autoPreviewBlocked = 0, candidate = null, tripFormMode = '', selectedRecordId = '', previewedTrip = '';
let recordPlanView = 'start', recordMap = null, routeMap = null;
let pollStarted = false, pollTimer = 0, searchSeq = 0, searchBusy = false;
let pendingRouteRecalc = false, confirmResolve = null, confirmTrigger = null;
const scrolls = { route: 0, docent: 0, records: 0, family: 0 };
const roleNames = { viewer: '보기만', proposer: '장소 담기', editor: '담기와 정리', driver: '운전자' };
const statusNames = { planning: '계획 중', active: '여행 중', completed: '완료' };

if (vehicle) {
  document.body.classList.add('vehicle');
  const brand = document.querySelector('.brand');
  if (brand) brand.setAttribute('href', '/vehicle');
  const fragment = location.hash.replace(/^#/, '');
  if (fragment) { driverSession = fragment; sessionStorage.setItem(DRIVER_KEY, fragment); }
  if (location.hash) history.replaceState(null, '', location.pathname);
  $('join').hidden = true;
  $('modeBadge').textContent = '차량 플래너';
} else if (/^#session=/.test(location.hash)) {
  // Embedded in the Family Story phone app: the app owns navigation, this page is the map planner only.
  session = decodeURIComponent(location.hash.slice(9));
  sessionStorage.setItem('cabin-session', session);
  history.replaceState(null, '', location.pathname);
  document.body.classList.add('embedded');
} else {
  const invitation = location.hash.slice(1);
  if (invitation) { $('invitation').value = invitation; history.replaceState(null, '', location.pathname); }
  $('modeBadge').textContent = '가족 여행';
}

function token() { return vehicle ? driverSession : session; }
function role() { return state && state.role ? state.role : (vehicle ? 'driver' : 'viewer'); }
function isDriver() { return role() === 'driver' && state && state.memberId === 'driver'; }
function parked() {
  const safety = String((state && state.vehicle && state.vehicle.safety) || '');
  return /정차|P단/.test(safety) && !/주행/.test(safety);
}
function canEdit() { return connected && (isDriver() || role() === 'editor'); }
function canAct() { return canEdit() && (!isDriver() || parked()); }
function canPlanEdit() { return canAct() && !!state && state.status !== 'completed'; }
function canPropose() { return connected && ['driver', 'editor', 'proposer'].includes(role()) && (!isDriver() || parked()); }

const element = (tag, text, className) => {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
};
const message = text => { $('message').textContent = text || ''; };
const brief = (text, ms) => { message(text); setTimeout(() => { if ($('message').textContent === text) message(''); }, ms || 6000); };

async function api(path, data, timeout) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout || 5000);
  const headers = { 'Content-Type': 'application/json' };
  if (token()) headers.Authorization = 'Bearer ' + token();
  try {
    let response;
    try {
      response = await fetch(path, {
        method: data ? 'POST' : 'GET',
        headers,
        body: data ? JSON.stringify(data) : undefined,
        signal: controller.signal,
        cache: 'no-store'
      });
    } catch (failure) {
      const error = new Error(controller.signal.aborted ? '요청 시간이 오래 걸려 취소했어요. 잠시 뒤 다시 시도해 주세요.' : '연결을 확인해 주세요. 같은 기기와 Wi-Fi 상태를 확인한 뒤 다시 시도해 주세요.');
      error.status = 0;
      throw error;
    }
    let json = {};
    try { json = await response.json(); } catch (ignored) { json = {}; }
    if (!response.ok) {
      const error = new Error(json.error || '요청을 처리하지 못했어요.');
      error.status = response.status;
      throw error;
    }
    return json;
  } finally {
    clearTimeout(timer);
  }
}

$('joinForm').addEventListener('submit', async event => {
  event.preventDefault();
  if (busy) return;
  busy = true;
  const submit = event.currentTarget.querySelector('button');
  submit.disabled = true;
  message('');
  try {
    const data = await api('/api/join', { name: $('name').value, invitation: $('invitation').value.trim() }, 8000);
    session = data.token;
    sessionStorage.setItem('cabin-session', session);
    await refresh(true);
    startPoll(true);
  } catch (error) {
    message(error.message || '연결하지 못했어요. 같은 Wi-Fi인지 확인해 주세요.');
  } finally {
    busy = false;
    submit.disabled = false;
  }
});

function signature() {
  if (!state) return '';
  return JSON.stringify([
    state.tripId, state.revision, state.routeRevision, state.approvedRevision, state.status,
    state.members, state.proposals, state.savedTrips, state.records,
    state.vehicle && state.vehicle.safety, state.route && state.route.computedAt, connected
  ]);
}

async function refresh(force) {
  if (!token()) return;
  try {
    const latest = await api('/api/state', null, 5000);
    const previousTrip = state ? state.tripId : '';
    state = latest;
    if (previousTrip && previousTrip !== state.tripId) {
      routeError = '';
      autoPreviewBlocked = 0;
      searchResults = [];
      searchStatusText = '';
      searchSeq++;
      searchBusy = false;
      pendingRouteRecalc = false;
      clearTimeout(autoTimer);
      autoTimer = 0;
      closeConfirm(false);
      closeCandidate();
      renderSearch();
    }
    connected = true;
    if (currentRoute()) previewedTrip = state.tripId;
    $('join').hidden = true;
    $('connected').hidden = false;
    $('navigation').hidden = false;
    $('familyStale').hidden = true;
    $('connection').className = 'connection ok';
    $('connection').textContent = '차량과 연결됨' + (vehicle ? '' : ' · ' + (state.vehicle && state.vehicle.safety ? state.vehicle.safety : '차량 상태 확인 중'));
    const next = signature();
    if (force || next !== lastSignature) {
      lastSignature = next;
      paint(renderAll);
    }
  } catch (error) {
    connected = false;
    lastSignature = '';
    $('familyStale').hidden = false;
    $('connection').className = 'connection error';
    $('connection').textContent = '연결 끊김 · 마지막으로 받은 일정을 보여 주고 있어요.';
    if (error.status === 403) {
      stopPoll();
      closeConfirm(false);
      if (vehicle) {
        driverSession = '';
        sessionStorage.removeItem(DRIVER_KEY);
        message('차량 연결 정보가 만료됐어요. 차량 앱에서 플래너를 다시 열어 주세요.');
      } else {
        session = '';
        sessionStorage.removeItem('cabin-session');
        $('join').hidden = false;
        $('connected').hidden = true;
        $('navigation').hidden = true;
        message('연결이 종료되었거나 권한이 철회됐어요. 차량에서 다시 초대받아 주세요.');
      }
    } else {
      message(error.message || '연결을 확인하고 있어요.');
    }
    paint(renderAll);
  }
}

function paint(render) {
  const active = document.activeElement;
  const key = active && active.dataset ? active.dataset.focus : '';
  render();
  if (key) {
    const next = Array.from(document.querySelectorAll('[data-focus]')).find(node => node.dataset.focus === key);
    if (next) next.focus({ preventScroll: true });
  }
}

function renderAll() {
  if (!state) return;
  renderHeader();
  renderPlan();
  renderDocent();
  renderRecords();
  renderFamily();
}

function renderHeader() {
  $('tripTitle').textContent = state.title || '이름 없는 여행';
  const visited = Array.isArray(state.visited) ? state.visited.length : 0;
  $('tripMeta').textContent = (statusNames[state.status] || state.status) + ' · 담은 곳 ' + state.stops.length + '곳 · 다녀온 곳 ' + visited + '곳';
  const who = isDriver() ? '운전자' : (roleNames[role()] || role());
  $('modeBadge').textContent = vehicle ? '차량 플래너' : '가족 여행';
  const editable = canAct();
  const canSwitch = editable && !busy && state.status !== 'active';
  $('renameOpen').disabled = !editable || busy || state.status === 'completed';
  $('newOpen').disabled = !canSwitch;
  $('openTrip').disabled = !canSwitch;
  $('savedSelect').disabled = !canSwitch;
  renderSavedTrips();
}

function renderSavedTrips() {
  const select = $('savedSelect');
  const key = JSON.stringify(state.savedTrips.map(trip => [trip.tripId, trip.title, trip.status]));
  if (select.dataset.key === key) return;
  select.dataset.key = key;
  select.replaceChildren();
  state.savedTrips.forEach(trip => {
    const option = element('option', (trip.title || '이름 없는 여행') + ' · ' + (statusNames[trip.status] || trip.status) + ' · ' + (trip.stopCount || 0) + '곳');
    option.value = trip.tripId;
    option.selected = trip.tripId === state.tripId;
    select.append(option);
  });
}

function currentRoute() {
  if (!state || !state.route) return null;
  if (state.route.tripId !== state.tripId) return null;
  if (state.route.routeRevision !== state.routeRevision) return null;
  return state.route;
}

function km(meters) {
  const value = Number(meters);
  if (!Number.isFinite(value) || value < 0) return '';
  return value >= 1000 ? (value / 1000).toFixed(1) + 'km' : Math.round(value) + 'm';
}
function minutes(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value) || value < 0) return '';
  const min = Math.round(value / 60);
  return min >= 60 ? Math.floor(min / 60) + '시간 ' + (min % 60) + '분' : min + '분';
}
function time(value) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return '';
  return new Date(value).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
}
function distanceMeters(a, b) {
  const rad = value => value * Math.PI / 180;
  const dLat = rad(b.latitude - a.latitude), dLon = rad(b.longitude - a.longitude);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(dLon / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}
function normalName(value) { return String(value || '').trim().toLowerCase().replace(/\s+/g, ''); }
function docentMatch(place) {
  if (!state || !place) return null;
  const name = normalName(place.name);
  if (!name) return null;
  return state.places.find(item => item.hasDocent && normalName(item.name) === name &&
    Number.isFinite(+item.latitude) && Number.isFinite(+item.longitude) &&
    distanceMeters({ latitude: +place.latitude, longitude: +place.longitude }, { latitude: +item.latitude, longitude: +item.longitude }) <= 100) || null;
}
function placeById(id) { return state ? state.places.find(place => place.id === id) : null; }
function orderedPlaces() { return state.stops.map(placeById).filter(Boolean); }

function button(text, run, options) {
  const opts = options || {};
  const node = element('button', text);
  node.type = 'button';
  node.disabled = !!opts.disabled || (!opts.local && (busy || !connected));
  if (opts.focus) node.dataset.focus = opts.focus;
  if (opts.className) node.className = opts.className;
  if (opts.label) node.setAttribute('aria-label', opts.label);
  if (run) node.addEventListener('click', run);
  return node;
}

function renderPlan() {
  if (!state) return;
  $('roleBadge').textContent = isDriver() ? '운전자' : (roleNames[role()] || role());
  const notice = $('driverNotice');
  if (vehicle) {
    notice.hidden = false;
    notice.classList.toggle('is-ok', parked());
    notice.textContent = (state.vehicle && state.vehicle.safety ? state.vehicle.safety : '차량 상태 확인 중') + (parked() ? ' · 편집할 수 있어요.' : ' · 정차 확인 전에는 편집할 수 없어요. 서버가 최종 확인해요.');
  } else {
    notice.hidden = true;
  }
  $('originName').textContent = state.origin ? state.origin.name : '출발지를 정해 주세요';
  $('originAddress').textContent = state.origin && state.origin.address ? state.origin.address : (state.origin ? '지정한 좌표' : '지도에서 고르거나 장소를 검색해 출발지로 지정하세요.');
  renderStops();
  renderRouteSummary();
  renderTripActions();
  renderProposals();
  const history = $('history');
  history.replaceChildren();
  (state.history || []).slice().reverse().forEach(line => history.append(element('li', line)));
  renderMap();
}

function renderStops() {
  const list = $('stopList');
  list.replaceChildren();
  $('stopsEmpty').hidden = state.stops.length > 0;
  const active = state.status === 'active';
  orderedPlaces().forEach((place, index) => {
    const last = index === state.stops.length - 1;
    const visited = state.visited.includes(place.id);
    const item = element('li', undefined, [visited ? 'is-visited' : '', place.id === state.selected ? 'is-selected' : ''].filter(Boolean).join(' '));
    const num = element('span', String(index + 1), 'stopnum');
    num.setAttribute('aria-hidden', 'true');
    item.append(num);
    const main = element('div', undefined, 'stopmain');
    const head = element('div', undefined, 'stophead');
    const name = element('button', place.name || '이름 없는 장소', 'stopname');
    name.type = 'button';
    name.dataset.focus = 'stop:' + place.id;
    name.setAttribute('aria-label', (place.name || '장소') + ' 도슨트 열기');
    name.addEventListener('click', () => openDocent(place.id));
    const statusText = visited ? '다녀왔어요' : (active && place.id === state.selected) ? '가는 중' : '';
    head.append(name, element('span', statusText, 'stopstatus'));
    main.append(head);
    if (place.address) main.append(element('p', place.address, 'small'));
    if (canAct() && state.status !== 'completed') {
      const actions = element('div', undefined, 'actions stopactions');
      actions.append(
        button('↑ 위로', () => submitEdit('move', { placeId: place.id, delta: -1 }, '방문 순서를 올렸어요.'), { disabled: index === 0, focus: 'up:' + place.id, label: (place.name || '장소') + ' 위로 이동', className: 'outline' }),
        button('↓ 아래로', () => submitEdit('move', { placeId: place.id, delta: 1 }, '방문 순서를 내렸어요.'), { disabled: index === state.stops.length - 1, focus: 'down:' + place.id, label: (place.name || '장소') + ' 아래로 이동', className: 'outline' }),
        button('빼기', async () => {
          if (await askConfirm('일정에서 뺄까요?', (place.name || '이 장소') + '를 일정에서 뺄까요? 방문 기록은 지워지지 않아요.', '빼기')) {
            submitEdit('remove', { placeId: place.id }, '일정에서 뺐어요.');
          }
        }, { focus: 'remove:' + place.id, label: (place.name || '장소') + ' 일정에서 빼기', className: 'ghost danger' })
      );
      main.append(actions);
    }
    if (active && canAct()) {
      const actions = element('div', undefined, 'actions visitaction');
      actions.append(button(visited ? '✓ 방문 완료됨' : '방문 완료로 표시', () => submitEdit('visit', { placeId: place.id }, '방문 시각을 기록했어요.'), {
        disabled: visited, focus: 'visit:' + place.id, label: (place.name || '장소') + ' 방문 완료로 표시'
      }));
      main.append(actions);
    }
    item.append(main);
    list.append(item);
  });
}

function renderRouteSummary() {
  const route = currentRoute();
  const summary = $('routeSummary'), status = $('routeStatus'), legs = $('legsBox');
  legs.hidden = true;
  if (routeBusy) {
    summary.textContent = '도로 경로를 계산하고 있어요…';
  } else if (route) {
    summary.textContent = km(route.distanceMeters) + ' · ' + minutes(route.durationSeconds);
    if (Array.isArray(route.legs) && route.legs.length) {
      legs.hidden = false;
      $('legs').replaceChildren();
      route.legs.forEach((leg, index) => {
        $('legs').append(element('li', '구간 ' + (index + 1) + ' · ' + km(leg.distanceMeters) + ' · ' + minutes(leg.durationSeconds)));
      });
    }
  } else if (!state.origin || !state.stops.length) {
    summary.textContent = '출발지와 방문지를 정한 뒤 도로 경로를 미리 볼 수 있어요.';
  } else if (state.route) {
    summary.textContent = '일정이 바뀌어 저장된 경로가 현재 일정과 달라요. 경로를 다시 미리 봐 주세요.';
  } else {
    summary.textContent = '아직 도로 경로를 미리 보지 않았어요.';
  }
  status.textContent = routeError || (route ? '경로 제공: ' + (route.provider || '공개 경로 서비스') + ' · 차량 내비 경로와 다를 수 있어요.' : '');
  status.classList.toggle('error', !!routeError);
  const preview = $('previewRoute');
  preview.disabled = routeBusy || busy || !connected || !state.origin || !state.stops.length || state.status === 'completed' || (isDriver() && !parked());
  preview.textContent = route ? '경로 다시 계산' : '경로 미리보기';
  const routeOk = !!route;
  const approved = state.approvedRevision === state.routeRevision;
  $('approvalText').textContent = '운전자 일정 확인 ' + (approved ? '완료' : '대기 중');
  $('approvalText').classList.add('approval');
  $('approvalText').classList.toggle('is-ok', approved);
  $('approvalText').classList.toggle('is-wait', !approved);
  const start = $('startTrip');
  const startable = canAct() && state.status === 'planning' && state.origin && state.stops.length > 0 && routeOk && approved;
  start.disabled = !startable || busy || !connected;
  $('startHint').textContent = state.status !== 'planning'
    ? (state.status === 'active' ? '여행이 진행 중이에요. 지도에서 방문 완료를 기록할 수 있어요.' : '완료된 여행은 수정할 수 없어요. 기록에서 확인해 주세요.')
    : !state.origin ? '출발지를 먼저 정해 주세요.'
    : !state.stops.length ? '방문지를 한 곳 이상 추가해 주세요.'
    : !routeOk ? '경로 미리보기 후 시작할 수 있어요.'
    : !approved ? '운전자가 일정을 확인한 뒤 시작할 수 있어요.'
    : startable ? '시작하면 지금 미리 본 경로와 순서가 여행에 저장돼요.' : '운전자 또는 편집 권한이 있어야 시작할 수 있어요.';
}

function renderTripActions() {
  $('finishTrip').hidden = state.status !== 'active';
  $('finishTrip').disabled = !canAct() || busy || !connected;
  $('driverActions').hidden = !isDriver();
  if (isDriver()) {
    $('approvePlan').disabled = !parked() || busy || !connected || state.status === 'completed';
    $('approvePlan').textContent = state.approvedRevision === state.routeRevision ? '일정 확인 완료' : '일정 확인';
    $('requestNavi').hidden = !(window.CabinVehicle && typeof window.CabinVehicle.requestNavigation === 'function');
    $('requestNavi').disabled = !parked() || busy || !connected || state.status !== 'active';
  }
}

function renderProposals() {
  const list = $('proposalList');
  list.replaceChildren();
  $('proposalsEmpty').hidden = state.proposals.length > 0;
  state.proposals.forEach(proposal => {
    const place = placeById(proposal.placeId);
    const item = element('li');
    item.append(element('p', place ? place.name : '확인할 수 없는 장소', 'resultname'));
    item.append(element('p', (proposal.name || '가족') + ' 제안 · 운전자 확인 대기', 'proposalmeta'));
    if (isDriver()) {
      item.append(button('이 제안 수락', () => submitEdit('accept', { memberId: proposal.memberId, proposalId: proposal.id }, '제안을 일정에 더했어요.'), {
        disabled: !parked() || state.status === 'completed', focus: 'accept:' + (proposal.id || proposal.memberId), label: (place ? place.name : '제안') + ' 수락'
      }));
    }
    list.append(item);
  });
}

function renderMap() {
  if (!state || $('routePanel').hidden) return;
  if (!window.CabinMap || !window.CabinMap.available()) {
    note('지도를 불러오지 못했어요. 담은 곳은 목록에서 볼 수 있어요.');
    return;
  }
  if (!routeMap) {
    routeMap = window.CabinMap.create($('map'), {
      onPick: point => openCandidate(point.lat, point.lon),
      onTileError: () => note('지도 배경을 불러오지 못했어요. 지점과 저장된 경로는 아래 일정과 요약에 그대로 남아 있어요.')
    });
  }
  const origin = state.origin ? { name: state.origin.name, address: state.origin.address, lat: state.origin.latitude, lon: state.origin.longitude } : null;
  const stops = orderedPlaces().map((place, index) => ({
    label: index + 1, name: place.name, address: place.address, lat: place.latitude, lon: place.longitude,
    final: index === state.stops.length - 1
  }));
  const route = currentRoute();
  const ok = routeMap.render({ tripId: state.tripId, routeRevision: state.routeRevision, origin, stops, route, candidate });
  if (!ok) note('지도를 불러오지 못했어요. 담은 곳은 목록에서 볼 수 있어요.');
  else if ($('mapNote').dataset.kind === 'component') $('mapNote').hidden = true;
}
function note(text) {
  $('mapNote').dataset.kind = /구성 요소/.test(text) ? 'component' : 'tile';
  $('mapNote').textContent = text;
  $('mapNote').hidden = false;
}

function openDocent(id) {
  if (!state) return;
  // The native shells own the guide: the phone app opens its own 안내 tab, the vehicle uses its player.
  if (vehicle) return;
  if (document.body.classList.contains('embedded')) {
    if (window.FamilyPhone && typeof window.FamilyPhone.openGuide === 'function') window.FamilyPhone.openGuide(String(id));
    return;
  }
  if (placeId !== id) scene = 0;
  placeId = id;
  renderDocent();
  tab('docent');
}

function renderDocent() {
  if (!state) return;
  const place = placeById(placeId) || placeById(state.selected) || orderedPlaces()[0] || null;
  if (place && place.id !== placeId) placeId = place.id;
  const hasStory = place && place.hasDocent && Array.isArray(place.adult) && place.adult.length;
  $('noDocent').hidden = !!hasStory;
  $('docentBody').hidden = !hasStory;
  $('placeName').textContent = place ? (place.name || '장소') : '도슨트';
  $('placeTag').textContent = place ? (place.category || '여행 장소') + (place.tag ? ' · ' + place.tag : '') : '';
  if (!hasStory) {
    $('art').hidden = true;
    $('art').removeAttribute('src');
    $('art').alt = '';
    $('storyText').replaceChildren();
    return;
  }
  const hasKids = Array.isArray(place.kids) && place.kids.length > 0;
  const kidPages = hasKids ? place.kids : [];
  if (!hasKids) kids = false;
  if (kids) scene = Math.max(0, Math.min(scene, kidPages.length - 1));
  if (place.art) $('art').src = '/art/' + place.art;
  else $('art').removeAttribute('src');
  $('art').alt = place.art ? place.name + ' 이야기 삽화' : '';
  $('art').hidden = !place.art;
  const copy = $('storyText');
  copy.replaceChildren();
  if (kids) {
    const page = kidPages[scene];
    copy.append(element('h3', page.title), element('p', page.text));
  } else {
    (place.adult || []).forEach(line => copy.append(element('p', line)));
  }
  $('kidsMode').disabled = !hasKids;
  $('kidsMode').setAttribute('aria-pressed', String(kids));
  $('adultMode').setAttribute('aria-pressed', String(!kids));
  $('pager').hidden = !kids;
  $('pageNumber').textContent = String(scene + 1).padStart(2, '0') + ' / ' + String(kidPages.length).padStart(2, '0');
  $('previous').disabled = scene === 0;
  $('next').disabled = scene === kidPages.length - 1;
  const safeSource = /^https?:\/\//i.test(place.sourceUrl || '');
  $('docentSource').hidden = !safeSource;
  if (safeSource) {
    $('sourceLink').href = place.sourceUrl;
    $('sourceLink').textContent = (place.sourceTitle || '공식 안내') + ' · 인터넷 필요 ↗';
  }
}

function renderRecords() {
  if (!state) return;
  const list = $('recordList');
  list.replaceChildren();
  $('recordDetail').hidden = !selectedRecordId;
  $('recordsHeading').textContent = selectedRecordId ? '기록 상세' : '여행 기록';
  if (selectedRecordId) {
    list.hidden = true;
    renderRecordDetail();
    return;
  }
  list.hidden = false;
  if (recordMap) { recordMap.destroy(); recordMap = null; }
  const progress = inProgress();
  if (progress) list.append(recordCard(progress, true));
  state.records.slice().reverse().forEach(record => list.append(recordCard(record, false)));
  if (!progress && !state.records.length) list.append(element('p', '아직 완료된 여행 기록이 없어요. 여행을 시작하고 방문 완료를 기록하면 여기에 남아요.', 'small'));
}

function inProgress() {
  if (!state || state.status !== 'active' || !state.startedPlan) return null;
  return {
    tripId: state.tripId, title: state.title, status: 'active', origin: state.startedPlan.origin,
    createdAt: state.createdAt, startedAt: state.startedAt, completedAt: null,
    stops: state.startedPlan.stops || [], startedPlan: state.startedPlan, completedPlan: null,
    visitEvents: state.visitEvents || []
  };
}
function recordCard(record, progress) {
  const card = element('article', undefined, 'card');
  card.append(element('h3', record.title || '이름 없는 여행'));
  card.append(element('p', (progress ? '진행 중 · ' : '완료 · ') + (record.stops ? record.stops.length : 0) + '곳 · ' + (record.visitEvents || []).length + '곳 방문 기록', 'recordmeta'));
  card.append(button('기록 자세히 보기', () => {
    selectedRecordId = record.tripId;
    recordPlanView = 'start';
    renderRecords();
    window.scrollTo({ top: 0, behavior: 'instant' });
  }, { disabled: false, local: true, focus: 'record:' + record.tripId, label: (record.title || '기록') + ' 자세히 보기' }));
  return card;
}
function findRecord(id) {
  if (state.status === 'active' && state.tripId === id) return inProgress();
  return state.records.find(record => record.tripId === id) || null;
}
function planToggleNeeded(record) {
  return record.completedPlan && JSON.stringify(record.startedPlan) !== JSON.stringify(record.completedPlan);
}
function renderRecordDetail() {
  const record = findRecord(selectedRecordId);
  if (!record) { selectedRecordId = ''; renderRecords(); return; }
  $('recordTitle').textContent = record.title || '이름 없는 여행';
  $('recordTimes').textContent = '시작 ' + (time(record.startedAt) || '시각 기록 없음') + ' · 종료 ' + (record.status === 'active' ? '진행 중' : (time(record.completedAt) || '시각 기록 없음'));
  const toggle = planToggleNeeded(record);
  $('planToggle').hidden = !toggle;
  $('planStart').setAttribute('aria-pressed', String(recordPlanView === 'start'));
  $('planEnd').setAttribute('aria-pressed', String(recordPlanView === 'end'));
  const plan = recordPlanView === 'end' && record.completedPlan ? record.completedPlan : (record.startedPlan || { origin: record.origin, stops: record.stops, route: null });
  const places = Array.isArray(plan.stops) ? plan.stops : [];
  renderRecordMap(record, plan, places);
  const events = record.visitEvents || [];
  const done = new Map();
  events.forEach(event => {
    const id = event.place && event.place.id;
    if (id && !done.has(id)) done.set(id, event.completedAt);
  });
  const list = $('recordStops');
  list.replaceChildren();
  places.forEach((place, index) => {
    const visited = done.has(place.id);
    const item = element('li');
    item.append(element('p', (index + 1) + '. ' + (place.name || '이름 없는 장소'), 'resultname'));
    item.append(element('p', visited ? '방문 완료 · ' + (time(done.get(place.id)) || '시각 기록 없음') : '방문 기록 없음', 'recordmeta ' + (visited ? 'recordvisited' : '')));
    list.append(item);
  });
  const finalPlaces = record.status === 'active'
    ? orderedPlaces()
    : (record.completedPlan && Array.isArray(record.completedPlan.stops) ? record.completedPlan.stops : (record.stops || []));
  events.forEach(event => {
    const place = event.place || {};
    if (places.some(item => item.id === place.id)) return;
    const inFinal = finalPlaces.some(item => item.id === place.id);
    const item = element('li');
    item.append(element('p', (place.name || '이름 없는 장소') + ' · ' + (inFinal ? '여행 중 추가' : '최종 일정에서 제외'), 'resultname'));
    item.append(element('p', '방문 완료 · ' + (time(event.completedAt) || '시각 기록 없음'), 'recordmeta recordvisited'));
    list.append(item);
  });
}
function renderRecordMap(record, plan, places) {
  if ($('recordDetail').hidden) return;
  if (!window.CabinMap || !window.CabinMap.available()) {
    $('recordMapNote').textContent = '지도 구성 요소를 불러오지 못했어요. 장소 순서는 아래 목록에서 확인할 수 있어요.';
    return;
  }
  if (!recordMap) {
    recordMap = window.CabinMap.create($('recordMap'), {
      onTileError: () => { $('recordMapNote').textContent = '지도 배경을 불러오지 못했어요. 저장된 장소와 순서는 아래 목록에 남아 있어요.'; }
    });
  }
  const route = plan.route && plan.route.geometry ? plan.route : null;
  const origin = plan.origin ? { name: plan.origin.name, address: plan.origin.address, lat: plan.origin.latitude, lon: plan.origin.longitude } : null;
  recordMap.render({
    tripId: record.tripId, routeRevision: route ? route.routeRevision : 0, origin, route,
    stops: places.map((place, index) => ({ label: index + 1, name: place.name, address: place.address, lat: place.latitude, lon: place.longitude }))
  });
  $('recordMapNote').textContent = route ? '저장된 계획 도로 경로예요. 실제 이동 궤적이나 차량 내비 경로와는 다를 수 있어요.' : '저장된 도로 경로가 없는 여행이에요. 장소 순서만 표시해요.';
  recordMap.invalidate();
}

function renderFamily() {
  if (!state) return;
  const me = state.members.find(member => member.id === state.memberId);
  const myRole = role();
  $('myDevice').textContent = isDriver() ? '운전자 · 차량 화면' : me ? me.name + ' · ' + (roleNames[myRole] || myRole) : '구성원 정보를 확인하고 있어요.';
  const permissions = $('permissions');
  permissions.replaceChildren();
  const rows = [
    ['일정과 기록 보기', '허용'],
    ['장소 검색', '허용'],
    ['순서 바꾸기·빼기', canAct() ? '허용' : '불가'],
    ['장소 담기', canPropose() ? '허용' : '불가'],
    ['운전자 일정 확인', isDriver() ? (parked() ? '허용' : '정차 후') : '불가']
  ];
  rows.forEach(([label, value]) => {
    const item = element('li');
    item.append(element('strong', label), element('span', value));
    permissions.append(item);
  });
  $('permissionNote').textContent = isDriver()
    ? '차량 화면에서는 서버가 정차 상태를 확인한 뒤 편집을 허용해요.'
    : '권한은 차량에서 운전자가 정차 중에만 바꿀 수 있어요. 이 화면에서 스스로 권한을 올릴 수 없어요.';
  const members = $('members');
  members.replaceChildren();
  if (!state.members.length) members.append(element('p', '표시할 가족 기기가 없어요.', 'small'));
  state.members.forEach(member => {
    const item = element('li');
    if (member.id === state.memberId) item.setAttribute('aria-current', 'true');
    const row = element('div', undefined, 'memberrow');
    row.append(element('span', member.name || '이름 없는 기기'), element('span', (roleNames[member.role] || member.role) + (member.id === state.memberId ? ' · 이 기기' : ''), 'membermeta'));
    item.append(row);
    if (isDriver() && parked() && connected) {
      const control = element('div', undefined, 'rolecontrol');
      const select = element('select');
      select.setAttribute('aria-label', (member.name || '가족 기기') + ' 권한');
      [['viewer', '보기만'], ['proposer', '장소 담기'], ['editor', '담기와 정리'], ['revoked', '연결 해제']].forEach(([value, label]) => {
        const option = element('option', label);
        option.value = value;
        option.selected = member.role === value;
        select.append(option);
      });
      control.append(select, button('권한 변경', () => changeRole(member.id, select.value), { focus: 'role:' + member.id, label: (member.name || '가족 기기') + ' 권한 변경' }));
      item.append(control);
    }
    members.append(item);
  });
  const history = $('familyHistory');
  history.replaceChildren();
  if (!state.history.length) history.append(element('li', '아직 변경 내역이 없어요.'));
  state.history.slice().reverse().forEach(line => history.append(element('li', line)));
  $('familyStale').hidden = connected;
}

async function changeRole(memberId, target) {
  if (!state || busy || !isDriver() || !parked()) return;
  busy = true;
  message('');
  try {
    await api('/api/role', { memberId, role: target }, 5000);
    brief('가족 기기 권한을 바꿨어요.');
    await refresh(true);
  } catch (error) {
    message(error.message || '권한을 바꾸지 못했어요.');
  } finally {
    busy = false;
    paint(renderAll);
  }
}

async function submitEdit(action, payload, noteText) {
  if (!state || busy || !connected) return false;
  if (vehicle && isDriver() && !parked() && action !== 'none') {
    message('정차 확인 후 편집할 수 있어요. 차량 상태가 바뀌면 다시 시도해 주세요.');
    return false;
  }
  const oldTripId = state.tripId, oldRouteRevision = state.routeRevision;
  busy = true;
  message('');
  paint(renderPlan);
  try {
    await api('/api/edit', Object.assign({ action, revision: state.revision }, payload), 8000);
    brief(noteText || '일정을 바꿨어요.');
    await refresh(true);
    maybeAutoPreview(oldTripId, oldRouteRevision);
    return true;
  } catch (error) {
    message(error.message || '변경하지 못했어요. 최신 일정을 다시 확인해 주세요.');
    if (error.status === 409 || error.status === 403) await refresh(true);
    return false;
  } finally {
    busy = false;
    paint(renderAll);
  }
}

function maybeAutoPreview(oldTripId, oldRouteRevision) {
  if (!state || state.tripId !== oldTripId || previewedTrip !== state.tripId) return;
  if (state.routeRevision === oldRouteRevision || state.status === 'completed') return;
  if (!state.origin || !state.stops.length) return;
  if (isDriver() && !parked()) return;
  if (autoPreviewBlocked === state.routeRevision) return;
  if (routeBusy) {
    pendingRouteRecalc = true;
    return;
  }
  clearTimeout(autoTimer);
  autoTimer = setTimeout(() => {
    autoTimer = 0;
    if (!connected) return;
    if (routeBusy) {
      pendingRouteRecalc = true;
      return;
    }
    requestRoute(true);
  }, 800);
}

async function requestRoute(automatic) {
  if (automatic) return;   // 0.7: no road preview on the plan page; the vehicle navigation owns the route
  if (!state || !connected) return;
  if (routeBusy) {
    pendingRouteRecalc = true;
    return;
  }
  if (state.status === 'completed') return;
  if (isDriver() && !parked()) {
    if (!automatic) {
      routeError = '정차 확인 후 경로를 계산할 수 있어요.';
      paint(renderPlan);
    }
    return;
  }
  if (!state.origin || !state.stops.length) {
    routeError = '출발지와 방문지를 먼저 정해 주세요.';
    paint(renderPlan);
    return;
  }
  const tripId = state.tripId, revision = state.routeRevision;
  let hit429 = false;
  routeBusy = true;
  routeError = '';
  paint(renderPlan);
  try {
    const result = await api('/api/route', { tripId, routeRevision: revision }, 25000);
    if (result.tripId !== tripId || result.routeRevision !== revision) {
      routeError = '일정이 바뀌었어요. 새 일정으로 다시 미리 봐 주세요.';
    } else {
      brief('도로 경로를 미리 봤어요.');
      await refresh(true);
    }
  } catch (error) {
    if (error.status === 429) hit429 = true;
    routeError = error.status === 429
      ? '경로 서비스 요청이 많아요. 잠시 뒤 다시 시도해 주세요.'
      : (error.status === 409 ? '일정이 바뀌었어요. 새 일정으로 다시 미리 봐 주세요.' : (error.message || '경로를 불러오지 못했어요.'));
    if (error.status === 429) autoPreviewBlocked = revision;
    if (error.status === 409) await refresh(true);
  } finally {
    routeBusy = false;
    const recalc = pendingRouteRecalc;
    pendingRouteRecalc = false;
    paint(renderPlan);
    if (recalc && !hit429 && connected && state && state.status !== 'completed' && state.origin && state.stops.length && (!isDriver() || parked()) && autoPreviewBlocked !== state.routeRevision) {
      requestRoute(true);
    }
  }
}

async function runSearch(event) {
  event.preventDefault();
  if (!state || !connected) return;
  const query = $('searchQuery').value.trim();
  if (query.length < 2) { searchStatusText = '장소 이름을 두 글자 이상 입력해 주세요.'; renderSearch(); return; }
  const seq = ++searchSeq, tripId = state.tripId;
  searchBusy = true;
  searchStatusText = '장소를 찾고 있어요…';
  searchResults = [];
  renderSearch();
  const payload = { query };
  if (state.origin && Number.isFinite(+state.origin.latitude) && Number.isFinite(+state.origin.longitude)) {
    payload.latitude = +state.origin.latitude;
    payload.longitude = +state.origin.longitude;
  }
  try {
    const result = await api('/api/search', payload, 25000);
    if (seq !== searchSeq || !state || state.tripId !== tripId) return;
    searchResults = Array.isArray(result.places) ? result.places : [];
    searchStatusText = searchResults.length ? searchResults.length + '곳을 찾았어요. 주소를 확인하고 하나를 골라 주세요.' : '검색 결과가 없어요. 다른 이름으로 찾아보세요.';
  } catch (error) {
    if (seq !== searchSeq || !state || state.tripId !== tripId) return;
    searchStatusText = error.status === 429 ? '검색 요청이 많아요. 잠시 뒤 다시 시도해 주세요.' : (error.message || '장소를 찾지 못했어요. 인터넷 연결을 확인해 주세요.');
  } finally {
    if (seq === searchSeq) {
      searchBusy = false;
      renderSearch();
    }
  }
}

function renderSearch() {
  $('searchStatus').textContent = searchStatusText;
  const list = $('searchResults');
  list.replaceChildren();
  searchResults.forEach((place, index) => {
    const match = docentMatch(place);
    const item = element('li');
    item.append(element('p', place.name || '이름 없는 장소', 'resultname'));
    item.append(element('p', place.address || '주소 정보 없음', 'resultaddress'));
    if (match) item.append(element('span', '등록된 해설이 있는 장소', 'resulttag'));
    const actions = element('div', undefined, 'actions');
    const listed = state.stops.includes(match ? match.id : '');
    if (listed) actions.append(element('p', '이미 담았어요.', 'small'));
    else if (canPlanEdit()) actions.append(button('담기', () => useSearchPlace(place, 'add'), { focus: 'search-add:' + index, label: (place.name || '장소') + ' 목록에 담기' }));
    else if (canPropose() && state.status !== 'completed') actions.append(button('담기', () => useSearchPlace(place, 'propose'), { focus: 'search-propose:' + index, label: (place.name || '장소') + ' 목록에 담기' }));
    else actions.append(element('p', state.status === 'completed' ? '마친 여행은 바꿀 수 없어요.' : '이 기기는 보기만 할 수 있어요. 차량의 설정 › 가족에서 바꿀 수 있어요.', 'small'));
    item.append(actions);
    list.append(item);
  });
}

function useSearchPlace(place, mode) {
  const name = String(place.name || '').trim();
  const lat = +place.latitude, lon = +place.longitude;
  if (!name || !Number.isFinite(lat) || !Number.isFinite(lon)) { message('장소 정보가 올바르지 않아요.'); return; }
  const match = docentMatch(place);
  const custom = { name, address: place.address || '', latitude: lat, longitude: lon };
  if (mode === 'origin') {
    if (match) submitEdit('setOrigin', { placeId: match.id }, '출발지를 정했어요. 경로를 다시 미리 봐 주세요.');
    else submitEdit('setOrigin', { place: custom }, '출발지를 정했어요.');
  } else if (mode === 'add') {
    if (match) submitEdit('add', { placeId: match.id }, '목록에 담았어요. 차량 화면에 바로 나와요.');
    else submitEdit('addCustom', { place: custom }, '목록에 담았어요. 차량 화면에 바로 나와요.');
  } else if (mode === 'propose') {
    if (match) submitEdit('propose', { placeId: match.id }, '목록에 담았어요. 차량 화면에 바로 나와요.');
    else submitEdit('proposeCustom', { place: custom }, '목록에 담았어요. 차량 화면에 바로 나와요.');
  }
}

function openCandidate(lat, lon, name) {
  if (!state) return;
  const initialName = name || '지도에서 지정한 장소';
  candidate = { lat: +lat, lon: +lon, name: initialName };
  $('candidateName').value = initialName;
  $('candidateCoord').textContent = '위도 ' + (+lat).toFixed(5) + ' · 경도 ' + (+lon).toFixed(5);
  $('candidate').hidden = false;
  $('candidateHelp').textContent = state.status === 'completed'
    ? '완료된 여행은 읽기 전용이에요. 기록은 그대로 보관돼요.'
    : canPropose()
      ? '지도를 누른 것만으로는 일정이 바뀌지 않아요. 아래 버튼을 눌러야 반영돼요.'
      : '이 기기에서는 일정을 바꿀 수 없어요. 운전자나 편집 권한이 있는 기기에서 추가해 주세요.';
  $('pickOrigin').disabled = !canPlanEdit();
  $('pickStop').disabled = !(canPlanEdit() || (canPropose() && state.status !== 'completed'));
  $('pickStop').textContent = '목록에 담기';
  $('candidateName').focus();
  $('candidateName').select();
  renderMap();
}

function candidatePlace() {
  if (!candidate) return null;
  const name = $('candidateName').value.trim() || '지도에서 지정한 장소';
  return { name, address: '', latitude: candidate.lat, longitude: candidate.lon };
}

$('candidateName').addEventListener('input', () => {
  if (!candidate) return;
  candidate.name = $('candidateName').value.trim() || '지도에서 지정한 장소';
  renderMap();
});
$('pickOrigin').addEventListener('click', async () => {
  const place = candidatePlace();
  if (!place || !canPlanEdit()) return;
  const match = docentMatch(place);
  const ok = match
    ? await submitEdit('setOrigin', { placeId: match.id }, '출발지를 정했어요. 경로를 다시 미리 봐 주세요.')
    : await submitEdit('setOrigin', { place }, '출발지를 정했어요.');
  if (ok) closeCandidate();
  else $('candidateName').focus();
});
$('pickStop').addEventListener('click', async () => {
  const place = candidatePlace();
  if (!place) return;
  const match = docentMatch(place);
  let ok = false;
  if (canPlanEdit()) {
    ok = match
      ? await submitEdit('add', { placeId: match.id }, '목록에 담았어요. 차량 화면에 바로 나와요.')
      : await submitEdit('addCustom', { place }, '목록에 담았어요. 차량 화면에 바로 나와요.');
  } else if (canPropose()) {
    ok = match
      ? await submitEdit('propose', { placeId: match.id }, '목록에 담았어요. 차량 화면에 바로 나와요.')
      : await submitEdit('proposeCustom', { place }, '목록에 담았어요. 차량 화면에 바로 나와요.');
  } else {
    message('이 기기에서는 장소를 추가할 수 없어요.');
    return;
  }
  if (ok) closeCandidate();
  else $('candidateName').focus();
});
$('candidateClose').addEventListener('click', closeCandidate);
function closeCandidate() { candidate = null; $('candidate').hidden = true; renderMap(); }

$('manualPick').addEventListener('click', () => {
  const latText = $('manualLat').value.trim(), lonText = $('manualLon').value.trim();
  const lat = Number(latText), lon = Number(lonText);
  const error = $('manualError');
  if (!latText || !lonText) { error.textContent = '위도와 경도를 모두 입력해 주세요.'; return; }
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) { error.textContent = '위도는 -90에서 90 사이 숫자여야 해요.'; return; }
  if (!Number.isFinite(lon) || lon < -180 || lon > 180) { error.textContent = '경도는 -180에서 180 사이 숫자여야 해요.'; return; }
  error.textContent = '';
  openCandidate(lat, lon, $('manualName').value.trim() || '지도에서 지정한 장소');
});

function startTripForm(mode) {
  if (!state || busy || !canAct()) { message('운전자 또는 편집 권한이 있어야 해요. 차량에서는 정차 확인도 필요해요.'); return; }
  if (mode === 'rename' && state.status === 'completed') { message('완료된 여행은 제목을 바꿀 수 없어요.'); return; }
  if (mode === 'new' && state.status === 'active') { message('진행 중인 여행을 먼저 완료해 주세요.'); return; }
  tripFormMode = mode;
  $('tripFormLabel').textContent = mode === 'new' ? '새 여행 제목' : '여행 제목';
  $('tripTitleInput').value = mode === 'new' ? '' : state.title;
  $('tripFormError').textContent = '';
  $('tripForm').hidden = false;
  $('tripTitleInput').focus();
}
$('renameOpen').addEventListener('click', () => startTripForm('rename'));
$('newOpen').addEventListener('click', async () => {
  if (!state || busy || !canAct() || state.status === 'active') return;
  if ((state.stops.length || state.origin) && !(await askConfirm('새 여행을 만들까요?', '현재 일정은 저장된 여행에 보관되고 새 여행으로 이동해요. 계속할까요?', '계속'))) return;
  startTripForm('new');
});
$('tripCancel').addEventListener('click', () => { $('tripForm').hidden = true; tripFormMode = ''; });
$('tripForm').addEventListener('submit', async event => {
  event.preventDefault();
  const title = $('tripTitleInput').value.trim();
  if (!title) { $('tripFormError').textContent = '여행 제목을 입력해 주세요.'; return; }
  const mode = tripFormMode;
  if (!mode) return;
  const ok = await submitEdit(mode === 'new' ? 'newTrip' : 'renameTrip', { title }, mode === 'new' ? '새 여행을 만들었어요.' : '여행 제목을 바꿨어요.');
  if (ok) { $('tripForm').hidden = true; tripFormMode = ''; }
});
$('openTrip').addEventListener('click', async () => {
  if (!state || busy || !canAct() || state.status === 'active') return;
  const tripId = $('savedSelect').value;
  if (!tripId || tripId === state.tripId) return;
  const target = state.savedTrips.find(trip => trip.tripId === tripId);
  if ((state.stops.length || state.origin) && !(await askConfirm('저장된 여행을 불러올까요?', '현재 일정은 저장된 여행에 보관하고 선택한 여행을 불러와요. 계속할까요?', '불러오기'))) return;
  await submitEdit('openTrip', { tripId }, (target ? target.title : '여행') + '을 불러왔어요.');
});
$('previewRoute').addEventListener('click', () => { autoPreviewBlocked = 0; requestRoute(false); });
$('startTrip').addEventListener('click', () => {
  if (!$('startTrip').disabled) submitEdit('startTrip', {}, '여행을 시작했어요. 방문 완료를 기록해 주세요.');
});
$('finishTrip').addEventListener('click', async () => {
  if ($('finishTrip').disabled || !state || busy || !canAct() || state.status !== 'active') return;
  if (!(await askConfirm('여행을 완료할까요?', '여행을 완료하면 기록으로 보관되고 일정은 수정할 수 없어요. 완료할까요?', '완료'))) return;
  submitEdit('finishTrip', {}, '여행을 완료하고 기록에 보관했어요.');
});
$('approvePlan').addEventListener('click', () => {
  if (!$('approvePlan').disabled) submitEdit('approve', {}, '일정을 확인했어요. 차량 내비 요청은 별도로 보내 주세요.');
});
$('requestNavi').addEventListener('click', () => {
  if ($('requestNavi').disabled || !parked() || busy || !connected || !state || state.status !== 'active') return;
  if (!window.CabinVehicle || typeof window.CabinVehicle.requestNavigation !== 'function') return;
  try {
    window.CabinVehicle.requestNavigation();
    brief('차량 화면에 내비 요청을 전달했어요. 차량에서 결과를 확인해 주세요.');
  } catch (error) {
    message('차량 내비 요청을 보내지 못했어요. 차량 화면에서 다시 시도해 주세요.');
  }
});
$('searchForm').addEventListener('submit', runSearch);
$('kidsMode').addEventListener('click', () => { kids = true; renderDocent(); });
$('adultMode').addEventListener('click', () => { kids = false; renderDocent(); });
$('previous').addEventListener('click', () => { if (scene > 0) scene--; renderDocent(); });
$('next').addEventListener('click', () => {
  const place = placeById(placeId);
  if (place && kids && Array.isArray(place.kids) && scene < place.kids.length - 1) scene++;
  renderDocent();
});
$('backToPlanner').addEventListener('click', () => tab('route'));
$('recordBack').addEventListener('click', () => {
  selectedRecordId = '';
  if (recordMap) { recordMap.destroy(); recordMap = null; }
  renderRecords();
});
$('planStart').addEventListener('click', () => { recordPlanView = 'start'; renderRecords(); });
$('planEnd').addEventListener('click', () => { recordPlanView = 'end'; renderRecords(); });

function tab(name) {
  if (!state && name !== 'route') return;
  if (currentTab !== name) scrolls[currentTab] = window.scrollY;
  currentTab = name;
  const panels = { route: 'routePanel', docent: 'docentPanel', records: 'recordsPanel', family: 'familyPanel' };
  Object.entries(panels).forEach(([key, id]) => { $(id).hidden = key !== name; });
  ['route', 'docent', 'records', 'family'].forEach(key => {
    const node = $(key + 'Tab');
    if (key === name) node.setAttribute('aria-current', 'page');
    else node.removeAttribute('aria-current');
  });
  if (name === 'route') { renderMap(); if (routeMap) routeMap.invalidate(); }
  if (name === 'records') { renderRecords(); if (recordMap) recordMap.invalidate(); }
  const heading = name === 'route' ? $('tripTitle') : name === 'docent' ? $('placeName') : name === 'records' ? $('recordsHeading') : $('familyHeading');
  requestAnimationFrame(() => window.scrollTo({ top: scrolls[name] || 0, behavior: 'instant' }));
  heading.focus({ preventScroll: true });
}
$('routeTab').addEventListener('click', () => tab('route'));
$('docentTab').addEventListener('click', () => tab('docent'));
$('recordsTab').addEventListener('click', () => tab('records'));
$('familyTab').addEventListener('click', () => tab('family'));

document.addEventListener('visibilitychange', () => {
  if (document.hidden) return;
  if (routeMap) routeMap.invalidate();
  if (recordMap) recordMap.invalidate();
  if (token()) refresh(false);
});

function closeConfirm(accepted) {
  if (!confirmResolve) {
    $('confirmBackdrop').hidden = true;
    return;
  }
  const resolve = confirmResolve;
  const trigger = confirmTrigger;
  confirmResolve = null;
  confirmTrigger = null;
  $('confirmBackdrop').hidden = true;
  resolve(!!accepted);
  if (trigger && typeof trigger.focus === 'function' && trigger.isConnected) {
    requestAnimationFrame(() => trigger.focus({ preventScroll: true }));
  }
}
function askConfirm(title, desc, okLabel) {
  if (confirmResolve) closeConfirm(false);
  confirmTrigger = document.activeElement;
  $('confirmTitle').textContent = title || '확인';
  $('confirmDesc').textContent = desc || '';
  $('confirmOk').textContent = okLabel || '계속';
  $('confirmBackdrop').hidden = false;
  requestAnimationFrame(() => $('confirmCancel').focus({ preventScroll: true }));
  return new Promise(resolve => { confirmResolve = resolve; });
}
$('confirmCancel').addEventListener('click', () => closeConfirm(false));
$('confirmOk').addEventListener('click', () => closeConfirm(true));
$('confirmBackdrop').addEventListener('click', event => {
  if (event.target === $('confirmBackdrop')) closeConfirm(false);
});
$('confirmDialog').addEventListener('keydown', event => {
  if (event.key === 'Escape') {
    event.preventDefault();
    closeConfirm(false);
    return;
  }
  if (event.key === 'Tab') {
    const cancel = $('confirmCancel'), ok = $('confirmOk');
    if (event.shiftKey && document.activeElement === cancel) {
      event.preventDefault();
      ok.focus();
    } else if (!event.shiftKey && document.activeElement === ok) {
      event.preventDefault();
      cancel.focus();
    } else if (document.activeElement !== cancel && document.activeElement !== ok) {
      event.preventDefault();
      cancel.focus();
    }
  }
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && confirmResolve) {
    event.preventDefault();
    closeConfirm(false);
  }
});

function stopPoll() {
  pollStarted = false;
  clearTimeout(pollTimer);
  pollTimer = 0;
}
function schedulePoll() {
  clearTimeout(pollTimer);
  if (!pollStarted || !token()) return;
  pollTimer = setTimeout(async () => {
    await refresh(false);
    schedulePoll();
  }, 1500);
}
function startPoll(delayFirst) {
  if (pollStarted || !token()) return;
  pollStarted = true;
  if (delayFirst) schedulePoll();
  else refresh(false).then(schedulePoll);
}

if (vehicle) {
  if (driverSession) startPoll(false);
  else message('차량 연결 정보가 없어요. 차량 앱에서 플래너를 다시 열어 주세요.');
} else if (session) {
  startPoll(false);
} else {
  $('join').hidden = false;
  $('connected').hidden = true;
}
