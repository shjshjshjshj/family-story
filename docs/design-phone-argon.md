# Cabin Story (캐빈 스토리) — 휴대폰·차량 공유 플래너 및 기록 뷰 상세 설계서 (design-phone-argon)

**작성일:** 2026-11-14  
**설계 범위:** 동승자 모바일 웹(`/phone`) 및 정차 중 차량 임베디드 플래너·기록 웹뷰(`/vehicle`) 통합 기획·UX·Material Design·프론트엔드 상태 머신 명세  
**대상 파일(구현 단계 수정 대상):** `app/src/main/assets/phone.html`, `app/src/main/assets/phone.css`, `app/src/main/assets/phone.js`, `app/src/main/assets/map.js`  
**연동 백엔드/도메인(수정 금지·계약 준수):** `TripStore.java`, `FamilyServer.java`, `MapService.java`, `VehicleBridge.java`

---

## 1. 서비스 재정의 및 핵심 설계 원칙

### 1.1 제품 정체성: 3대 핵심 축의 결합
사용자의 핵심 피드백(*"경로를 짜는 과정이 아쉽네요. 루트도 안보이고 일반적으로 경로짜는 그런 개념이 아니에요"*, *"'협동 여행 경로 플래너' + '음성 도슨트' + '여행 경로 기록'의 성격이 합쳐진 서비스입니다."*)을 반영하여, 단순 장소 카드 목록이나 일러스트 대시보드가 아닌 **실제 지도 기반의 협동 여행 경로 플래너·도슨트·기록 플랫폼**으로 정의한다.

1. **협동 여행 경로 플래너 (Collaborative Route Planner)**
   - 출발지(Origin)와 순서가 있는 경유지·최종 목적지(Ordered Stops)를 지도 클릭 또는 장소 검색으로 자유롭게 구성한다.
   - 실제 도로망 기반 경로 폴리라인(FOSSGIS OSRM GeoJSON `LineString`)과 전체·구간별(Legs) 예상 거리 및 소요 시간을 지도와 요약 바에 즉시 시각화한다.
   - 가족 구성원은 역할(`driver`, `editor`, `proposer`, `viewer`)에 따라 실시간으로 같은 계획을 조회·제안·편집하고, 운전자의 일정 확인(`approve`)과 실제 차량 내비게이션 경로 요청(`requestNavigation`)을 명확히 분리한다.
2. **맥락 연결형 음성·그림책 도슨트 (Context-Linked Docent)**
   - 플래너의 각 방문지 행에서 즉시 도슨트 뷰로 진입할 수 있으되, 동승자의 단순 열람이 차량의 현재 안내 대상(`state.selected`)을 임의로 변경하지 않는다.
   - 공식 원고와 삽화가 내장된 경주 4대 장소(첨성대, 대릉원, 동궁과 월지, 국립경주박물관)는 성인 해설과 어린이 그림책(장면 페이저)을 제공하고, 사용자가 자유 검색·지정한 일반 장소는 허위 해설을 생성하지 않고 *"이 장소에는 등록된 해설이 없어요."* 상태를 정직하게 안내한다.
3. **스냅샷 기반 여행 경로 및 방문 기록 (Planned-Route & Visit Records)**
   - 지속적인 GPS 궤적 추적 없이, **여행 시작 시점의 계획 스냅샷(`startedPlan`)**, **여행 완료 시점의 최종 스냅샷(`completedPlan`)**, 그리고 **실제 방문 완료 처리한 장소와 최초 완료 시각(`visitEvents`)**을 보존한다.
   - 기록 상세 화면에서 시작 일정과 최종 일정의 경로·방문지 변화를 비교(Toggle)하고, 여행 중 추가된 장소와 최종 일정에서 제외된 장소를 정확히 구분하여 표시한다.

### 1.2 시각 자산 및 외부 공개 서비스 원칙
- **기존 WebGPT 컨셉 이미지 전면 배제:** `design/webgpt-concepts/*` 하위의 일러스트 이미지는 디자인 기준이나 화면 요소로 일절 사용하지 않는다. 플래너 첫 화면에 장식용 히어로 일러스트를 두지 않고 지도·경로·일정 목록을 최우선 배치한다. (단, 도슨트 탭 내부의 경주 4곳 전용 삽화 `/art/*.svg`, `/art/*.png`는 유지한다.)
- **무인증(API Key-less) 공개 지도·검색·경로 스택 준수:**
  - **지도 렌더링:** 로컬 번들링된 **Leaflet 1.9.4** (`/vendor/leaflet.js`, `/vendor/leaflet.css`)
  - **배경 타일:** **OpenStreetMap Standard Tile** (`https://tile.openstreetmap.org/{z}/{x}/{y}.png`). 화면 하단에 저작권 표시(`© OpenStreetMap contributors`) 및 `지도 오류 신고` 링크를 상시 노출하며, 브라우저 기본 캐시만 사용하고 대량 타일 프리페치/오프라인 다운로드를 금지한다.
  - **장소 검색:** 서버 프록시(`POST /api/search`)를 통한 **Photon 공개 검색**. 타이핑 중 자동완성(Autocomplete)이나 폴링 중 자동 요청을 엄격히 금지하고, 사용자가 명시적으로 `장소 검색` 버튼을 제출할 때만 호출한다.
  - **도로 경로 계산:** 서버 프록시(`POST /api/route`)를 통한 **FOSSGIS OSRM 공개 라우팅**. 서버가 검증·정규화한 현재 `tripId` 및 `routeRevision` 일치 `LineString`만 그리며, 계산 실패나 미계산 시 핀 사이를 임의의 직선(Straight line)으로 잇지 않는다.

---

## 2. Material Design 시스템 및 반응형 레이아웃 명세

### 2.1 컬러 토큰 (Color Roles)
밝고 명료한 Slate/White 서피스 위에 남색 잉크와 기능별 시맨틱 컬러(경로 파랑, 방문 완료 초록, 제안/최종 목적지 주황, 오류 빨강)를 일관되게 적용한다.

| 역할 (Role) | CSS 변수 | HEX 값 | 적용 대상 및 접근성 기준 |
|---|---|---|---|
| **Background Surface** | `--bg` | `#F8FAFC` | 앱 전체 기본 배경 (차분한 밝은 슬레이트) |
| **Card / Header Surface** | `--card` | `#FFFFFF` | 카드, 상단 헤더, 입력 필드, 모달 다이얼로그 배경 |
| **Outline / Divider** | `--line` | `#DCE3EC` | 카드 테두리, 리스트 구분선 (`1px solid`), 입력창 테두리는 `#8FA3B8` |
| **Primary Ink** | `--ink` | `#16324F` | 본문, 제목, 핵심 데이터 텍스트 (흰 배경 대비 명도비 11.8:1 AAA) |
| **Secondary Muted Ink** | `--muted` | `#55677A` | 부가 설명(`.small`), 주소, 메타데이터, 비활성 탭 라벨 (명도비 5.7:1 AA) |
| **Primary / Route Blue** | `--primary` / `--primary-soft` | `#245AE8` / `#E8EEFD` | 주요 CTA 버튼, 도로 경로 폴리라인(두께 5px, 투명도 0.85), 방문지 번호 핀, 활성 탭 |
| **Origin Pin Navy** | — | `#16324F` | 출발지(`출`) 마커 배경 (방문지 파란 핀과 시각적 구분) |
| **Candidate Pin Amber** | `--orange` | `#C76A19` (테두리 `#16324F`) | 지도 클릭 시 임시로 찍히는 후보 지점(`?`) 핀 |
| **Visited Green** | `--green` / `--green-soft` | `#1B7F59` / `#E6F3ED` | 방문 완료 배지, 완료된 방문지 좌측 강조 보더(`4px solid`), 도슨트 보유 태그 |
| **Proposal / Warning Orange** | `--orange` / `--orange-soft` | `#C76A19` / `#FBF0E4` | 가족 제안 카드 강조선, 최종 목적지 라벨, 연결 끊김·타일 오류·안전 배너(텍스트 `#7A430F`) |
| **Danger / Error Red** | `--danger` / `--danger-soft` | `#A3312B` / `#FBEAE8` | 폼 검증 오류 메시지(`role="alert"`), 위험 동작 강조 |
| **Focus Ring** | `--focus` | `#16324F` | 모든 포커스 가능 요소에 `outline: 3px solid var(--focus); outline-offset: 2px` |

### 2.2 타이포그래피 및 간격 규칙 (Typography & 8px Spacing)
- **폰트 패밀리:** `system-ui, -apple-system, "Noto Sans KR", sans-serif`
- **타입 스케일:**
  - 초대 화면 타이틀(`.join h1`): `28px` / line-height `1.35` / weight `800`
  - 상단 여행 제목(`#tripTitle`): 모바일 `26px`, `>=900px` `28px`, `>=1200px` `30px` / line-height `1.35` / weight `800`
  - 섹션 제목(`h2`): `22px` / line-height `1.4` / weight `700`
  - 카드·모달 제목(`h3`): `18px` / line-height `1.45` / weight `700`
  - 일정 행 제목·경로 요약(`.rowtitle`, `.routesummary`, `.stopname`, `.resultname`): `17px` / weight `700`
  - 기본 본문·버튼·입력창(`body`, `button`, `input`, `select`): `16px` (차량 모드는 버튼/입력 `17px`) / line-height `1.6`
  - 보조 안내 및 메타(`.small`, `.recordmeta`, `.proposalmeta`): `14px` / line-height `1.6`
  - 배지 및 아이브로우(`.pill`, `.eyebrow`): `12px~13px` / weight `700~800`
- **줄바꿈 및 오버플로 방어:**
  - 모든 텍스트와 버튼에 `word-break: keep-all; overflow-wrap: anywhere;`를 적용한다.
  - `320px` 최소 뷰포트 폭 및 브라우저 글자 크기 `200%` 확대 시에도 가로 스크롤바나 텍스트 잘림이 발생하지 않도록 모든 Flex/Grid 자식에 `min-width: 0`과 `flex-wrap: wrap`을 부여한다.
- **간격 및 반경:**
  - 8px 그리드 시스템(`4px`, `8px`, `12px`, `16px`, `20px`, `24px`).
  - 카드(`.card`, `.mapframe`) 모서리 반경 `16px`, 버튼·인풋·배너 모서리 반경 `12px`, 알약 배지(`.pill`) `999px`.

### 2.3 터치 타깃 및 접근성 크기 기준 (Touch Targets)
| 요소 구분 | 모바일 웹 (`/phone`) | 차량 임베디드 웹뷰 (`/vehicle`, `body.vehicle`) |
|---|---|---|
| 일반 버튼 / 입력창 / 셀렉트 / 하단 탭 | 최소 높이 `48px` | 최소 높이 `64px` (`font-size: 17px`) |
| 도슨트 장면 이동 원형 버튼 (`.pager button`) | `48px × 48px` | `64px × 64px` |
| 아코디언 요약 헤더 (`summary`) | 최소 높이 `48px` | 최소 높이 `64px` |
| **지도 마커 터치 영역 (`.cabin-pin`)** | **인터랙티브 히트박스 `48px × 48px`** (`iconSize: [48, 48]`, `iconAnchor: [24, 24]`), 내부 시각적 원형 배지는 `34px × 34px` 유지 | **인터랙티브 히트박스 `64px × 64px`** (`iconSize: [64, 64]`, `iconAnchor: [32, 32]`), 내부 시각적 원형 배지는 `40px × 40px` |
| **Leaflet 줌 컨트롤 (`.leaflet-control-zoom a`)** | **`48px × 48px`** (`line-height: 48px`, 기본 30px 덮어쓰기) | **`64px × 64px`** (`line-height: 64px`, `font-size: 24px`) |

### 2.4 반응형 레이아웃 및 `/vehicle` 전용 모드 명세

#### A. 모바일 단일 컬럼 (`< 900px`, `360×800` 기준)
- 상단부터 아래로 자연스러운 단일 열 흐름을 유지한다:
  1. `<header>`: 좌측 브랜드 링크(`Cabin Story`, `/phone`) + 우측 현재 모드·역할 배지(`#modeBadge`)
  2. 연결 상태 바(`#connection`) + 여행 헤더(`.tripbar`: 여행 제목, 상태 메타, `제목 바꾸기`, `새 여행`) + 저장된 여행 전환 카드(`.tripselect`)
  3. `#routePanel` 단일 열 그리드:
     - `map` (`.map-area`: 지도 `clamp(280px, 40vh, 460px)`, 타일 안내, 좌표 직접 입력 `<details>`, 선택 후보 지점 카드 `#candidate`)
     - `summary` (`.routebar`: 총 거리·예상 시간·공급자 및 `경로 미리보기` 버튼)
     - `legs` (`#legsBox`: 구간별 거리·시간 접힘 목록)
     - `plan` (`.plancard`: **카드 상단에 통합된 `여행 일정` 제목 + `#roleBadge` + `#driverNotice`**, 출발지 행, 방문지 순서 리스트 `#stopList`, `여행 시작`/`여행 완료` 버튼, 운전자 전용 `일정 확인`/`차량 내비로 요청` 버튼)
     - `search` (`.searchcard`: 장소 검색 폼 및 결과 목록)
     - `proposals` (`.proposals`: 가족 제안 목록 및 운전자 수락 버튼)
     - `history` (`.history`: 최근 일정 변경 내역)
  4. 하단 고정 내비게이션(`nav#navigation`): `[경로] / [도슨트] / [기록] / [가족]` 4등분 그리드(`repeat(4, minmax(0, 1fr))`).

#### B. 태블릿·데스크톱 2컬럼 그리드 (`>= 900px`, 차량 모드는 `>= 800px`)
- **기존 구현의 레이아웃 분리 결함 해결:** 기존에는 `.sectiontitle`(`여행 일정` 제목과 `#roleBadge`) 및 `#driverNotice`가 `#routePanel`의 직계 자식으로 선언되어, 2컬럼 전환 시 제목과 배너는 왼쪽 열 지도 아래에 남고 정작 일정 목록(`.plancard`)만 오른쪽 열 상단에 동떨어져 배치되는 시각적 분리 결함이 있었다.
- **수정 구조:** `.sectiontitle`(`<h2>여행 일정</h2>` + `#roleBadge`)과 `#driverNotice`를 **`.plancard` 내부 최상단으로 이동**한다.
- **단순화된 2컬럼 Grid Areas:**
  ```css
  @media (min-width: 900px) {
    #routePanel {
      grid-template-columns: minmax(0, 1.1fr) minmax(340px, 0.9fr);
      grid-template-areas:
        "map       plan"
        "summary   plan"
        "legs      plan"
        "search    plan"
        "proposals plan"
        "history   plan";
      align-items: start;
      gap: 16px 24px;
    }
    .plancard {
      position: sticky;
      top: 12px;
    }
    #map {
      height: min(52vh, 520px);
    }
  }
  ```
  이로써 좌측 열에는 **지도 → 경로 요약 → 구간별 상세 → 장소 검색 → 가족 제안 → 변경 내역**이 응집되고, 우측 열에는 **여행 일정 헤더(제목+역할 배지+차량 안전 배너) + 출발지 + 방문지 순서 목록 + 여행/운전자 액션**이 하나의 완결된 스티키 패널로 고정된다. 모바일(`<900px`)에서도 `map → summary → legs → plan → search → proposals → history` 순서가 그대로 유지된다.

#### C. 차량 임베디드 웹뷰 모드 (`/vehicle`, `body.vehicle`)
차량 네이티브 앱(`MainActivity`)은 이미 자체 상단 타이틀/안전 상태 바와 4개 네이티브 레일을 제공한다. 따라서 웹뷰가 `/vehicle` 경로로 열릴 때는 다음 규칙을 엄격히 적용한다:
1. **상단 웹 헤더 중복 제거 및 브랜드 링크 보호:**
   - `body.vehicle header { display: none !important; }`로 중복 상단 헤더를 숨긴다.
   - 코드 및 접근성 일관성을 위해 `phone.js` 초기화 시 `if (vehicle) document.querySelector('.brand').setAttribute('href', '/vehicle');`로 설정하여, 어떤 경우에도 운전자가 `/phone`(가족 초대 화면)으로 잘못 이동하지 않게 한다.
2. **하단 웹 내비게이션 2탭(`[경로] / [기록]`) 고정:**
   - `#docentTab`과 `#familyTab`에 `class="vehicle-hide"`를 부여하여 `body.vehicle .vehicle-hide { display: none !important; }`로 숨긴다.
   - `body.vehicle nav#navigation { grid-template-columns: repeat(2, minmax(0, 1fr)); }`을 적용해 하단 바에 **`[경로]`와 `[기록]` 두 개의 탭만 50:50 균등 너비, 최소 높이 `64px`**로 노출한다. (단, 일정 행에서 장소 이름을 눌렀을 때 보조 뷰로 `#docentPanel`을 여는 동작과 `← 경로 플래너로` 복귀 버튼은 정상 작동한다.)

---

## 3. 화면별 유저 플로우 및 컴포넌트 상세 설계

```
[초대 참여 (#join)] (토큰 없을 때 /phone 전용)
       │ POST /api/join 성공 → session 저장 & startPolling() 보장
       ▼
[상단 공통 여행 바 (.tripbar + .tripselect)]
  ├─ 제목 바꾸기 (renameTrip)  ─┐
  ├─ 새 여행 (newTrip)         ─┼─► 인라인 폼(#tripForm) + 커스텀 확인 모달(#confirmDialog)
  └─ 저장된 여행 불러오기      ─┘   (completed 여행에서도 새 여행/불러오기 항상 허용!)
       │
       ├──► 탭 1: [경로] (#routePanel)
       │      ├─ 지도 (#map): 출발지('출'), 방문지('1'..'N'), 임시 후보 핀('?'), OSRM 도로 폴리라인
       │      ├─ 지점 선택 패널 (#candidate) & 좌표 직접 입력 (.coords)
       │      ├─ 도로 경로 요약 바 (.routebar) & 구간별 거리/시간 (#legsBox)
       │      ├─ 여행 일정 카드 (.plancard): 출발지 + 방문지 순서/이동/삭제/방문완료 + 시작/완료/운전자 승인·내비 요청
       │      ├─ 장소 검색 (.searchcard): 명시적 검색 폼 + 100m 도슨트 장소 자동 매칭
       │      └─ 가족 제안 (.proposals) & 일정 변경 내역 (.history)
       │
       ├──► 탭 2: [도슨트] (#docentPanel) (오프라인에서도 로컬 열람 가능)
       │      ├─ 지원 장소(경주 4곳): 어린이 이야기(삽화 + 장면 페이저) / 어른의 이야기 + 출처 링크
       │      └─ 미지원 자유 장소: "이 장소에는 등록된 해설이 없어요" (#noDocent)
       │
       ├──► 탭 3: [기록] (#recordsPanel) (오프라인에서도 목록·상세·일정 토글 열람 가능)
       │      ├─ 기록 목록 (#recordList): 진행 중 여행 프리뷰 + 완료된 여행 아카이브 카드
       │      └─ 기록 상세 (#recordDetail): 시작 일정 vs 최종 일정 토글 + 저장된 경로 지도 + 방문 시각 및 '여행 중 추가'/'최종 일정에서 제외' 배지
       │
       └──► 탭 4: [가족] (#familyPanel) (/phone 전용 노출)
              ├─ 내 기기 역할 및 5대 권한 요약
              ├─ 참여 기기 목록 (운전자 정차 시 권한 변경/연결 해제)
              └─ 최근 변경 이력 & 공유 서비스 기준 연결 도움말
```

### 3.1 가족 초대 및 연결 화면 (`#join`)
- **노출 조건:** `/phone` 경로이면서 유효한 `session` 토큰이 없을 때(최초 진입 또는 `403` 세션 만료/권한 철회 시). `/vehicle`에서는 절대 노출하지 않는다.
- **구성 요소:**
  - 아이브로우 `COLLABORATIVE TRIP PLANNER`, 헤드라인 `함께 짜는 여행, 길 위의 이야기`
  - 입력 필드: `이 기기의 이름`(`maxlength="20"`, 필수), `차량 초대 코드`(`#invitation`, URL 해시 `#코드` 진입 시 자동 채움 후 `history.replaceState`로 주소창에서 즉시 제거)
  - 제출 버튼: `우리 여행에 참여하기 →`
  - **세션 수명주기 안내 문구(결함 수정 반영):**
    > *"차량과 같은 개인 Wi-Fi에서 연결해 주세요. 저장된 일정과 기록은 차량에 보관되며, 장소 검색과 도로 경로는 인터넷이 필요해요. 차량에서 공유를 종료하면 다시 초대받아야 해요."*

### 3.2 상단 연결 배너 및 여행 관리 헤더 (`.tripbar`, `.tripselect`, `#tripForm`)
- **연결 상태 배너 (`#connection`):**
  - 온라인: `● 차량과 연결됨 · 정차 중 (P단)` (파란 소프트 배너)
  - 오프라인(일시 끊김): `연결 끊김 · 마지막으로 받은 일정을 보여 주고 있어요.` (주황 소프트 배너, 기존 로드된 데이터로 일정·도슨트·기록 읽기 전용 탐색 유지)
- **여행 타이틀 바 (`.tripbar`):**
  - 현재 여행 제목(`#tripTitle`), 메타 요약(`계획 중 · 출발지 1곳 · 방문지 4곳 · 방문 완료 0곳`)
  - 우측 액션:
    - `제목 바꾸기 (#renameOpen)`: `canPlanEdit()`(`canAct() && state.status !== 'completed'`)일 때만 활성화.
    - `새 여행 (#newOpen)`: **`canSwitchTrip()`(`canAct() && state.status !== 'active'`)일 때 활성화.** 완료된(`completed`) 여행에서도 활성화되어 사용자가 완료된 여행에 갇히지 않고 새 여행을 시작할 수 있다.
- **저장된 여행 선택 바 (`.tripselect`):**
  - `<select id="savedSelect">`에 `state.savedTrips` 전체(`제목 · 상태 · 방문지 N곳`)를 렌더링하고 현재 `state.tripId`를 선택 상태로 표시한다.
  - `불러오기 (#openTrip)`: **`canSwitchTrip()`일 때 활성화.** 현재 여행과 다른 `tripId`를 선택해 누르면, 현재 여행에 출발지나 방문지가 있을 경우 커스텀 확인 모달을 거쳐 `openTrip` 액션을 호출한다.

### 3.3 탭 1: `[경로]` — 협동 여행 경로 플래너 (`#routePanel`)

#### (1) 지도 뷰 (`#map`) 및 지점 선택 워크플로우
- **핀(Marker) 체계:**
  - **출발지 핀 (`origin`):** 진남색(`#16324F`) 원형 배지, 라벨 `출`.
  - **방문지 핀 (`stop`):** 파란색(`#245AE8`) 원형 배지, 라벨 `1, 2, ... N`. 마지막 방문지는 팝업과 타이틀에 `· 최종 목적지` 표시.
  - **임시 후보 핀 (`candidate`):** 지도를 클릭하거나 좌표를 직접 입력해 `#candidate` 패널이 열려 있는 동안, 선택된 좌표에 주황색(`#C76A19`, 테두리 `#16324F`) 원형 배지(라벨 `?`)를 표시한다. 기존 출발지·방문지 핀이나 도로 폴리라인은 후보 확정 전까지 절대 변경·삭제하지 않는다.
- **후보 지점 카드 (`#candidate`):**
  - 지도 클릭 시 즉시 서버에 반영하지 않고 `#candidate` 카드를 노출한다.
  - 표시 정보: 위도/경도 소수점 5자리(`위도 35.83470 · 경도 129.21900`), 편집 가능한 `장소 이름` 입력창(기본값 `지도에서 지정한 장소`), 권한별 안내 문구.
  - 액션 버튼:
    - `출발지로 지정 (#pickOrigin)`: `canPlanEdit()`일 때 활성화.
    - `방문지에 추가 / 가족에게 제안 (#pickStop)`: `canPlanEdit()`이면 `방문지에 추가`, `proposer`이면 `가족에게 제안`으로 라벨을 표시하고 활성화.
    - `닫기 (#candidateClose)`: 후보 상태 취소 및 임시 후보 핀 제거.
  - **비동기 편집 실패 보호:** `출발지로 지정` 또는 `방문지에 추가/제안` 클릭 시 `await submitEdit(...)`의 반환값이 `true`(성공)일 때만 `closeCandidate()`를 호출한다. 네트워크 오류, `409` 충돌, 검증 실패 시에는 패널과 사용자가 입력한 장소 이름·좌표·임시 핀·포커스를 그대로 유지하여 재시도할 수 있게 한다.

#### (2) 도로 경로 미리보기 바 (`.routebar`, `#legsBox`)
- **상태별 요약 문구 (`#routeSummary`):**
  1. `routeBusy === true`: *"도로 경로를 계산하고 있어요…"*
  2. 유효한 `currentRoute()` 존재: *"`14.2km · 28분 · OSRM / FOSSGIS`"* + `#legsBox`(구간 1, 구간 2... 거리·시간) 노출
  3. 출발지 또는 방문지 미지정: *"출발지와 방문지를 정한 뒤 도로 경로를 미리 볼 수 있어요."*
  4. `state.route`가 없으나 일정이 바뀐 적 있거나 `previewedTrip === state.tripId`인 상태: *"일정이 바뀌어 도로 경로를 다시 미리 봐야 해요."*
  5. 아직 한 번도 미리보기를 실행하지 않은 상태: *"아직 도로 경로를 미리 보지 않았어요."*
- **자동 재계산 및 큐잉 규칙:**
  - 사용자가 최초 1회 `경로 미리보기` 버튼을 눌러 경로를 확인한 여행(`previewedTrip === state.tripId`)에서는 이후 출발지 설정, 방문지 추가/삭제, 순서 변경이 일어날 때마다 800ms 디바운스 후 자동으로 `requestRoute(true)`를 호출한다.
  - 만약 이전 `requestRoute`가 아직 진행 중(`routeBusy === true`)일 때 일정이 또 변경되면 타이머가 변경을 버리지 않도록 `pendingRouteRecalc = true` 플래그를 세우고, 진행 중인 요청의 `finally` 블록에서 최신 `routeRevision`으로 즉시 후속 1회 재계산을 수행한다.

#### (3) 여행 일정 카드 (`.plancard`)
- **카드 상단 헤더:** `<h2>여행 일정</h2>` + `<span id="roleBadge" class="pill">` + `<p id="driverNotice" class="statusbanner" hidden>`
- **출발지 행 (`.originrow`):** `출발지` 라벨 + 장소명(`#originName`) + 주소/안내(`#originAddress`)
- **방문지 순서 리스트 (`ol#stopList`):**
  - 각 항목(`<li>`) 상태 클래스: `.is-visited`(초록 보더), `.is-selected`(연파랑 배경), `.is-final`(마지막 항목 `· 최종 목적지` 주황 라벨).
  - 장소명 버튼(`.stopname`): 클릭 시 `openDocent(place.id)` 호출(도슨트 탭으로 전환).
  - 상태 텍스트(`.stopstatus`): `최종 목적지`, `방문 완료`, `다음 방문지`, `방문 예정`.
  - 순서/삭제 컨트롤(`canPlanEdit()`일 때 노출): `↑ 위로`, `↓ 아래로`, `일정에서 빼기`(커스텀 확인 모달 호출).
  - 방문 완료 컨트롤(`state.status === 'active' && canAct()`일 때 노출): `방문 완료로 표시`(미방문 시 활성) / `방문 완료됨`(완료 시 비활성).
- **여행 시작·완료 및 운전자 승인·내비 요청 영역:**
  - `여행 시작 (#startTrip)`: `planning` 상태에서 `출발지 + 방문지 1곳 이상 + 현재 routeRevision과 일치하는 도로 경로(routeOk) + 운전자 일정 확인(approvedRevision === routeRevision)`이 모두 충족될 때만 활성화. 미충족 사유는 `#startHint`에 즉시 안내.
  - `여행 완료 (#finishTrip)`: `active` 상태에서 노출. 클릭 시 커스텀 확인 모달(*"여행을 완료하면 기록으로 보관되고 일정은 수정할 수 없어요. 완료할까요?"*) 승인 후 `finishTrip` 실행.
  - 운전자 전용(`#driverActions`, `isDriver()`일 때 노출):
    - `일정 확인 (#approvePlan)`: 현재 `routeRevision`을 승인(`approve`). 이미 승인된 경우 `일정 확인 완료`로 표시.
    - `차량 내비로 요청 (#requestNavi)`: `window.CabinVehicle && typeof window.CabinVehicle.requestNavigation === 'function'`일 때만 노출하며, 정차 중이고 `state.status === 'active'`일 때 활성화.

#### (4) 장소 검색 카드 (`.searchcard`)
- **간결한 사용자 중심 안내 문구:**
  - `<p class="small">장소 검색은 인터넷 연결이 필요해요.</p>` (기존의 *"검색 버튼을 누를 때만 공개 장소 검색을 요청하고, 입력할 때마다 자동으로 검색하지 않아요"* 같은 내부 구현 설명 제거)
- **도슨트 장소 자동 매칭:**
  - 검색 결과의 장소명(공백 제거·소문자 정규화)이 내장 도슨트 장소(`state.places` 중 `hasDocent === true`)와 일치하고 좌표 거리가 `100m` 이내이면 `등록된 해설이 있는 장소` 초록 배지를 표시하고, 출발지/방문지/제안 등록 시 커스텀 장소가 아닌 기존 도슨트 `placeId`를 재사용하여 해설과 삽화가 그대로 연결되게 한다.

### 3.4 탭 2: `[도슨트]` — 성인·어린이 맞춤 장소 이야기 (`#docentPanel`)
- **상단 복귀 버튼:** `← 경로 플래너로 (#backToPlanner)`
- **콘텐츠 분기:**
  - **해설 보유 장소(`hasDocent && adult.length > 0`):**
    - `어린이 이야기` / `어른의 이야기` 세그먼트 토글(`role="group"`, `aria-pressed`).
    - 어린이 모드: 상단 삽화(`aspect-ratio: 5/3; object-fit: contain; background: #EEF2F7`), 장면 제목(`h3`), 본문(`p`), 하단 원형 페이저(`←`, `01 / 03`, `→`).
    - 어른 모드: 삽화 + 문단별 상세 해설 본문(페이저 숨김).
    - 하단 출처 아코디언(`#docentSource`): 유효한 `http(s)://` 출처 URL이 있을 때만 노출.
  - **해설 미보유 장소(`!hasDocent`):**
    - `#noDocent` 카드 노출: *"이 장소에는 등록된 해설이 없어요. 일정과 지도에는 그대로 남아 있고, 다른 지원 장소를 고르면 이야기를 들을 수 있어요."*
    - `#docentBody` 전체 숨김 및 `<img>` `src` 속성 제거(깨진 이미지 아이콘 방지).

### 3.5 탭 3: `[기록]` — 계획 경로 및 방문 완료 기록 (`#recordsPanel`)
- **기록 목록 뷰 (`#recordList`):**
  - 현재 여행이 `active` 상태이고 `startedPlan`이 존재하면 최상단에 `진행 중` 프리뷰 카드를 먼저 표시하고, 그 아래에 완료된 여행 기록(`state.records`)을 최신순(역순)으로 나열한다.
  - 각 카드의 `기록 자세히 보기` 버튼은 네트워크 상태(`connected`)나 편집 중(`busy`) 여부와 무관하게 **항상 활성화**한다.
- **기록 상세 뷰 (`#recordDetail`):**
  - 상단 `← 기록 목록 (#recordBack)` 버튼, 여행 제목(`#recordTitle`), 시작·종료 시각(`#recordTimes`).
  - **종료 시각 표기 규칙(결함 수정):**
    - `record.status === 'active'`인 진행 중 프리뷰만 종료 시각을 `'진행 중'`으로 표기한다.
    - 완료된 기록(`record.status === 'completed'`)에서 `completedAt`이 `null`(레거시 마이그레이션 등)이면 절대 `'진행 중'`이라고 표시하지 않고 **`'시각 기록 없음'`**으로 표기한다.
  - **시작 일정 vs 최종 일정 비교 토글 (`#planToggle`):**
    - `completedPlan`이 존재하고 `startedPlan`과 내용이 다를 때만 노출한다.
    - 토글 전환 시 `#recordMap`에 해당 스냅샷의 출발지·방문지 핀과 저장된 도로 폴리라인(`plan.route`)을 즉시 다시 그린다.
  - **방문지 및 추가/제외 방문 이벤트 표시 규칙(결함 수정):**
    1. 현재 선택된 스냅샷(`plan.stops`)의 방문지들을 순서대로(`1. 첨성대`, `2. 대릉원`...) 표시하며, `visitEvents`에 기록이 있으면 `방문 완료 · 2026. 11. 14. 오후 2:15`(시각이 `null`이면 `방문 완료 · 시각 기록 없음`), 없으면 `방문 기록 없음`을 표시한다.
    2. `visitEvents`에는 존재하지만 현재 표시 중인 `plan.stops`에는 없는 장소들을 하단에 추가 렌더링할 때, 실제 최종 일정(완료된 기록은 `record.completedPlan.stops`, 진행 중 기록은 현재 `state.stops`) 포함 여부에 따라 라벨을 정확히 분기한다:
       - 최종(또는 현재) 일정에 포함되어 있는 장소(즉, 현재 `시작 일정` 탭을 보고 있어서 목록에 없지만 여행 시작 후 추가되어 방문한 장소): **`{장소명} · 여행 중 추가`**
       - 최종(또는 현재) 일정에도 없는 장소(방문 완료 후 일정에서 삭제된 장소): **`{장소명} · 최종 일정에서 제외`**

### 3.6 탭 4: `[가족]` — 참여 기기 및 권한 안내 (`#familyPanel`)
- **이 기기 카드:** 내 기기 이름과 역할 배지, 5대 기능별 허용/불가 표, 역할 변경 정책 안내.
- **함께 참여한 기기 카드 (`#members`):**
  - 연결된 모든 가족 기기와 역할 표시(내 기기는 `· 이 기기` 및 `aria-current="true"`).
  - 운전자가 정차 중이고 연결된 상태(`isDriver() && parked() && connected`)일 때만 각 구성원 행 아래에 권한 선택 `<select>`(`조회 전용`, `변경 제안`, `일정 편집`, `연결 해제`)와 `권한 변경` 버튼을 표시한다.
- **연결 도움말 (`details.help`) 문구 최신화:**
  > - *"휴대폰과 차량이 같은 개인 Wi-Fi에 있는지, 차량에서 가족 공유가 켜져 있는지 확인해 주세요."*
  > - *"차량에서 공유를 종료했거나 권한이 철회되었다면 차량에서 새 초대를 받아야 해요. 저장된 일정과 여행 기록은 차량에 그대로 보관돼요."*

---

## 4. 권한·안전·여행 상태·네트워크 통합 상태 매트릭스

프론트엔드(`phone.js`)는 아래 5개의 권한/상태 판별 함수를 통해 UI 활성화 여부를 결정하며, 최종 권한 및 정차 여부는 서버(`TripStore.java` / `FamilyServer.java`)가 한 번 더 강제한다.

- `canEdit()`: `connected && (isDriver() || role() === 'editor')`
- `canAct()`: `canEdit() && (!isDriver() || parked())` (편집 권한이 있고, 운전자인 경우 정차 확인됨)
- **`canSwitchTrip()` (신설):** `canAct() && !!state && state.status !== 'active'` (새 여행 생성 및 저장된 여행 불러오기 권한 — **`completed` 상태에서도 허용!**)
- **`canPlanEdit()`:** `canAct() && !!state && state.status !== 'completed'` (현재 여행의 제목 변경, 출발지/방문지 추가·이동·삭제, 경로 미리보기 권한)
- **`canPropose()`:** `connected && ['driver', 'editor', 'proposer'].includes(role()) && (!isDriver() || parked()) && !!state && state.status !== 'completed'`

| 기능 / 액션 | `viewer` | `proposer` | `editor` (모바일) | `driver` (정차 중) | `driver` (주행/미확인) | 여행 `planning` | 여행 `active` | 여행 `completed` | 오프라인 (`!connected`) |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| 일정·도슨트·기록 로컬 열람 | O | O | O | O | O | O | O | O | **O (로컬 상태 유지)** |
| **새 여행 만들기 / 저장된 여행 불러오기** | X | X | **O** | **O** | X | **O** | X (진행중 불가) | **O (잠금 해제)** | X |
| 현재 여행 제목 바꾸기 | X | X | O | O | X | O | O | X | X |
| 장소 검색 (`/api/search`) | O | O | O | O | O | O | O | O (조회만) | X |
| 가족에게 장소 제안 (`propose`) | X | O | O | O | X | O | O | X | X |
| 출발지/방문지 추가·이동·삭제 | X | X | O | O | X | O | O | X | X |
| 도로 경로 미리보기 (`/api/route`) | O | O | O | O | O | O | O | X | X |
| 가족 제안 수락 (`accept`) | X | X | X | **O** | X | O | O | X | X |
| 운전자 일정 확인 (`approve`) | X | X | X | **O** | X | O | O | X | X |
| 여행 시작 (`startTrip`) | X | X | O* | O* | X | **O*** | X | X | X |
| 방문 완료 표시 (`visit`) | X | X | O | O | X | X | **O** | X | X |
| 여행 완료 (`finishTrip`) | X | X | O | O | X | X | **O** | X | X |
| 차량 내비로 요청 (`requestNavigation`) | X | X | X | **O** (브리지) | X | X | **O** | X | X |

*(\* `여행 시작`은 출발지 존재 + 방문지 1곳 이상 + 현재 `routeRevision`과 일치하는 도로 경로 존재 + `approvedRevision === routeRevision` 조건이 모두 충족되어야 활성화됨)*

---

## 5. 10가지 핵심 구현 결함 해결을 위한 상태 머신 및 함수별 수정 명세

`docs/unit-planner-r3-review-task.md`에서 식별된 기존 구현의 10가지 결함을 완벽히 해결하기 위한 파일별·함수별 상세 설계 명세이다. 다음 구현 단계(`gemini-4-hydrogen`)는 아래 명세를 그대로 코드에 반영한다.

### 결함 1: 신규 참가자 `/api/join` 성공 후 폴링 루프(`poll`)가 시작되지 않는 문제
- **현상 및 원인:** `phone.js` 하단 초기화 코드에서 페이지 최초 로드 시점에 `session`이 있을 때만 `poll()`을 호출하고, `#joinForm`의 `submit` 핸들러는 `await refresh(true)`만 1회 실행한 뒤 `poll()`을 시작하지 않는다. 그 결과 초대 코드로 처음 참여한 가족 기기는 첫 화면만 받고 이후 실시간 일정 변경을 전혀 수신하지 못한다.
- **상태 머신 설계:**
  - 모듈 스코프에 `let pollTimer = null, polling = false;`를 둔다.
  - `startPolling()` 함수를 정의하여 이미 루프가 돌고 있으면 중복 실행하지 않고, 루프가 없을 때만 단일 폴링 타이머를 기동한다.
  - `token()`이 비어 있을 때 `refresh()`가 호출되면 네트워크 요청 없이 즉시 반환한다.
- **함수별 수정 명세 (`phone.js`):**
  ```javascript
  let pollTimer = null;
  let polling = false;

  async function pollLoop() {
    if (polling) return;
    polling = true;
    try {
      if (token()) await refresh(false);
    } finally {
      polling = false;
      if (pollTimer !== null) clearTimeout(pollTimer);
      if (token()) {
        pollTimer = setTimeout(pollLoop, 1500);
      } else {
        pollTimer = null;
      }
    }
  }

  function startPolling() {
    if (pollTimer !== null || polling) return;
    pollLoop();
  }
  ```
  - `#joinForm` 제출 성공 시: `session = data.token; sessionStorage.setItem('cabin-session', session); await refresh(true); startPolling();` 호출.
  - 초기 진입 시: `if (token()) startPolling();` 호출.

### 결함 2: 완료된 여행(`completed`)에서 '새 여행' 및 '저장된 여행 불러오기'가 비활성화되어 갇히는 문제
- **현상 및 원인:** `TripStore.java`는 `status === "completed"`인 여행에서도 `newTrip`과 `openTrip` 액션을 명시적으로 허용(`boolean switching = action.equals("newTrip") || action.equals("openTrip");`)하고 있으나, `phone.js`의 권한 체크와 폼 열기 로직이 완료된 여행에서의 전환 권한(`canSwitchTrip`)과 현재 일정 편집 권한(`canPlanEdit`)을 명확히 분리하지 않아 사용자가 완료된 여행 화면에 갇히거나 일관성 없이 동작한다.
- **함수별 수정 명세 (`phone.js`):**
  - 권한 헬퍼 분리:
    ```javascript
    function canEdit() { return connected && (isDriver() || role() === 'editor'); }
    function canAct() { return canEdit() && (!isDriver() || parked()); }
    function canSwitchTrip() { return canAct() && !!state && state.status !== 'active'; }
    function canPlanEdit() { return canAct() && !!state && state.status !== 'completed'; }
    function canPropose() {
      return connected && ['driver', 'editor', 'proposer'].includes(role())
        && (!isDriver() || parked()) && !!state && state.status !== 'completed';
    }
    ```
  - `renderHeader()`:
    - `$('renameOpen').disabled = busy || !canPlanEdit();` (완료된 여행은 제목 변경 불가)
    - `$('newOpen').disabled = busy || !canSwitchTrip();` (**완료된 여행에서도 활성화!** 진행 중인 `active` 여행에서만 비활성)
    - `$('savedSelect').disabled = busy || !canSwitchTrip();`
    - `$('openTrip').disabled = busy || !canSwitchTrip() || ($('savedSelect').value === state.tripId);`
  - `startTripForm(mode)`:
    - `if (mode === 'new' ? !canSwitchTrip() : !canPlanEdit())`으로 분기 검사한다.
  - `#openTrip` 클릭 핸들러: `if (!state || !canSwitchTrip()) return;`으로 검사한다.

### 결함 3: 일시적 연결 끊김(오프라인) 시 이미 로드된 기록·도슨트 열람이 막히는 문제
- **현상 및 원인:** `phone.js`의 공통 `button(text, run, options)` 팩토리 함수가 `node.disabled = !!opts.disabled || busy || !connected;`로 작성되어 있어, 서버 API를 전혀 호출하지 않는 순수 로컬 UI 전환 버튼인 `기록 자세히 보기`(`recordCard`)까지 `!connected`(오프라인)나 `busy` 상태에서 비활성화된다.
- **함수별 수정 명세 (`phone.js`):**
  - `button(text, run, options)`에 `local: true` 옵션을 추가한다:
    ```javascript
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
    ```
  - `recordCard(record, progress)` 내부의 `기록 자세히 보기` 버튼 생성 시 `{ local: true, focus: 'record:' + record.tripId, label: (record.title || '기록') + ' 자세히 보기' }`를 전달하여 오프라인에서도 이미 메모리에 로드된 `state.records` 및 진행 중 기록을 자유롭게 열람할 수 있게 한다.
  - 정적 HTML 버튼인 `#recordBack`, `#planStart`, `#planEnd`, `#backToPlanner`, `#kidsMode`, `#adultMode`, `#previous`, `#next`, 그리고 방문지 행의 도슨트 열기 버튼(`.stopname`)도 `connected` 여부로 비활성화하지 않는다.

### 결함 4: 지도 후보 지점 패널(`#candidate`)에서 편집 실패 시 입력값과 좌표가 유실되는 문제
- **현상 및 원인:** `#pickOrigin` 및 `#pickStop` 클릭 이벤트 리스너가 비동기 함수인 `submitEdit(...)`를 `await`하지 않고 곧바로 동기적으로 `closeCandidate()`를 호출한다. 그 결과 서버에서 `409`(버전 충돌), 검증 오류, 네트워크 단절이 발생해 편집이 실패해도 후보 카드가 즉시 닫히고 사용자가 입력한 장소 이름과 선택한 좌표가 날아간다.
- **함수별 수정 명세 (`phone.js`):**
  ```javascript
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
        ? await submitEdit('add', { placeId: match.id }, '방문지를 추가했어요.')
        : await submitEdit('addCustom', { place }, '방문지를 추가했어요.');
    } else if (canPropose()) {
      ok = match
        ? await submitEdit('propose', { placeId: match.id }, '가족에게 장소를 제안했어요.')
        : await submitEdit('proposeCustom', { place }, '가족에게 장소를 제안했어요.');
    } else {
      message('이 기기에서는 장소를 추가할 수 없어요.');
      return;
    }
    if (ok) closeCandidate();
    else $('candidateName').focus();
  });
  ```

### 결함 5: 장소 검색 레이스 컨디션 및 경로 계산 중(`routeBusy`) 후속 일정 변경 시 자동 재계산 누락
- **현상 및 원인:**
  1. `runSearch`에 요청 순서(Generation token) 및 여행 전환 가드가 없어, 앞선 느린 검색 응답이 뒤늦게 도착하면 최신 검색 결과를 덮어쓰거나 다른 여행으로 전환한 뒤 이전 검색 결과가 표시될 수 있다.
  2. `maybeAutoPreview`에서 800ms 타이머 만료 시점에 `if (connected && !routeBusy) requestRoute(true);`만 실행하므로, 이전 경로 요청(`routeBusy === true`)이 아직 끝나지 않은 상태에서 사용자가 방문지 순서를 바꾸거나 장소를 추가하면 800ms 타이머가 아무 일도 하지 않고 종료되어 변경된 일정의 경로가 재계산되지 않는다.
- **상태 머신 및 함수별 수정 명세 (`phone.js`):**
  1. **장소 검색 세대 토큰(`searchSeq`) 및 `searchBusy` 분리:**
     ```javascript
     let searchSeq = 0, searchBusy = false;

     async function runSearch(event) {
       event.preventDefault();
       if (!state || !connected) return;
       const query = $('searchQuery').value.trim();
       if (query.length < 2) {
         searchStatusText = '장소 이름을 두 글자 이상 입력해 주세요.';
         renderSearch();
         return;
       }
       const seq = ++searchSeq;
       const tripId = state.tripId;
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
         searchStatusText = searchResults.length
           ? searchResults.length + '곳을 찾았어요. 주소를 확인하고 하나를 골라 주세요.'
           : '검색 결과가 없어요. 다른 이름으로 찾아보세요.';
       } catch (error) {
         if (seq !== searchSeq || !state || state.tripId !== tripId) return;
         searchStatusText = error.status === 429
           ? '검색 요청이 많아요. 잠시 뒤 다시 시도해 주세요.'
           : (error.message || '장소를 찾지 못했어요. 인터넷 연결을 확인해 주세요.');
       } finally {
         if (seq === searchSeq) {
           searchBusy = false;
           renderSearch();
         }
       }
     }
     ```
     - 여행이 바뀔 때(`previousTrip !== state.tripId`) `searchSeq++; searchBusy = false;`로 이전 검색 응답을 무효화한다.
  2. **경로 재계산 보류 큐(`pendingRouteRecalc`) 도입:**
     ```javascript
     let pendingRouteRecalc = false;

     function maybeAutoPreview(oldTripId, oldRouteRevision) {
       if (!state || state.tripId !== oldTripId || previewedTrip !== state.tripId) return;
       if (state.routeRevision === oldRouteRevision) return;
       if (!state.origin || !state.stops.length || state.status === 'completed') return;
       if (autoPreviewBlocked === state.routeRevision) return;
       clearTimeout(autoTimer);
       autoTimer = setTimeout(() => {
         if (!connected || !state || previewedTrip !== state.tripId) return;
         if (routeBusy) {
           pendingRouteRecalc = true;
         } else {
           requestRoute(true);
         }
       }, 800);
     }
     ```
     - `requestRoute(automatic)`의 `finally` 블록에서:
       ```javascript
       routeBusy = false;
       paint(renderPlan);
       if (pendingRouteRecalc && connected && state && state.status !== 'completed'
           && previewedTrip === state.tripId && state.origin && state.stops.length
           && autoPreviewBlocked !== state.routeRevision && !currentRoute()) {
         pendingRouteRecalc = false;
         clearTimeout(autoTimer);
         autoTimer = setTimeout(() => {
           if (connected && !routeBusy) requestRoute(true);
         }, 300);
       } else {
         pendingRouteRecalc = false;
       }
       ```
     - `429` 에러 발생 시 `autoPreviewBlocked = revision; pendingRouteRecalc = false;`로 설정해 429 재시도 루프를 엄격히 방지한다.

### 결함 6: 기록 상세(`renderRecordDetail`)에서 여행 중 추가된 방문지 라벨 및 완료 시각 표기 오류
- **현상 및 원인:**
  1. 사용자가 `시작 일정`(`recordPlanView === 'start'`)을 보고 있을 때, 여행 시작 이후에 추가되어 방문 완료된 장소는 `startedPlan.stops`에는 없고 `completedPlan.stops`(또는 진행 중 여행의 현재 `state.stops`)에는 존재한다. 그런데 기존 코드는 현재 화면에 표시 중인 `places` 배열에 없으면 무조건 `' · 최종 일정에 없음'`이라고 붙여, 최종 일정에 버젓이 있는 장소를 '최종 일정에 없음'으로 허위 표시한다.
  2. 완료된 기록(`record.status === 'completed'`)인데 `record.completedAt`이 `null`인 경우 `time(record.completedAt) || '진행 중'` 때문에 완료된 여행이 `'종료 진행 중'`으로 잘못 표시된다.
- **함수별 수정 명세 (`phone.js` — `renderRecordDetail`):**
  ```javascript
  const endLabel = record.status === 'active'
    ? '진행 중'
    : (time(record.completedAt) || '시각 기록 없음');
  $('recordTimes').textContent = '시작 ' + (time(record.startedAt) || '시각 기록 없음') + ' · 종료 ' + endLabel;

  // 최종(또는 현재) 일정의 방문지 ID 집합 계산
  const finalStops = (record.completedPlan && Array.isArray(record.completedPlan.stops))
    ? record.completedPlan.stops
    : (Array.isArray(record.stops) ? record.stops : []);
  const finalIds = new Set(finalStops.map(item => item && item.id).filter(Boolean));
  if (record.status === 'active' && state && state.tripId === record.tripId && Array.isArray(state.stops)) {
    state.stops.forEach(id => finalIds.add(id));
  }

  events.forEach(event => {
    const place = event.place || {};
    if (places.some(item => item.id === place.id)) return;
    const inFinal = place.id && finalIds.has(place.id);
    const badgeText = inFinal ? '여행 중 추가' : '최종 일정에서 제외';
    const item = element('li');
    item.append(element('p', (place.name || '이름 없는 장소') + ' · ' + badgeText, 'resultname'));
    item.append(element('p', '방문 완료 · ' + (time(event.completedAt) || '시각 기록 없음'), 'recordmeta recordvisited'));
    list.append(item);
  });
  ```

### 결함 7: `map.js`의 좌표 정규화(`lat/lon` vs `latitude/longitude`), 폴리라인 변경 감지, 우회 도로 `fitBounds`, 임시 후보 핀 및 터치 타깃 결함
- **현상 및 원인:**
  1. `point(value)`가 `value.lat`과 `value.lon`만 인식하고 도메인 DTO 표준 필드인 `value.latitude`와 `value.longitude`를 인식하지 못한다.
  2. `nextLineSignature`가 `[data.routeRevision, road.length, road[0], road[road.length - 1]]`만 비교하여, `tripId`가 바뀌거나(기록 전환 등) 시작·끝점과 점 개수가 같으면서 중간 경유 도로 모양만 바뀐 새 폴리라인을 다시 그리지 않는다.
  3. `fitSignature`가 `nextPinSignature`만 비교하여, 핀이 먼저 그려진 뒤 비동기로 도로 경로(`road`)가 로드되었을 때 도로가 핀 바운더리 바깥으로 크게 우회하더라도 지도가 `fitBounds`를 다시 수행하지 않는다.
  4. 지도 클릭 시 선택한 후보 지점을 나타내는 임시 마커(`candidate`)가 지도에 표시되지 않는다.
  5. 마커 아이콘 크기가 `34×34`이고 Leaflet 기본 줌 버튼이 `30×30`이어서 모바일 `48px` 및 차량 `64px` 최소 터치 타깃 기준에 미달한다.
- **함수별 수정 명세 (`map.js` & `phone.css`):**
  1. **`point(value)` 정규화 (`map.js`):**
     ```javascript
     function point(value) {
       if (!value) return null;
       const rawLat = value.lat !== undefined ? value.lat : value.latitude;
       const rawLon = value.lon !== undefined ? value.lon : value.longitude;
       if (rawLat === null || rawLat === undefined || rawLat === '' ||
           rawLon === null || rawLon === undefined || rawLon === '') return null;
       const lat = Number(rawLat), lon = Number(rawLon);
       if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
       if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
       return [lat, lon];
     }
     ```
     - `origin`과 `stops`, `candidate` 모두 이 `point()`를 통해 `{ name, address, lat: coords[0], lon: coords[1] }` 형태로 일관되게 정규화한다.
  2. **폴리라인 전체 좌표 시그니처 및 `fitBounds` 시그니처 통합 (`map.js`):**
     ```javascript
     const road = geometry(data.route, data.tripId, data.routeRevision);
     const nextLineSignature = road ? JSON.stringify([data.tripId, data.routeRevision, road]) : '';
     if (nextLineSignature !== lineSignature) {
       lineSignature = nextLineSignature;
       line.clearLayers();
       if (road) line.addLayer(global.L.polyline(road, { color: '#245AE8', weight: 5, opacity: 0.85 }));
     }

     const nextFitSignature = JSON.stringify([data.tripId, nextPinSignature, nextLineSignature]);
     if (data.fit !== false && nextFitSignature !== fitSignature) {
       const bounds = global.L.latLngBounds([]);
       if (origin) bounds.extend([origin.lat, origin.lon]);
       if (road) road.forEach(function (pair) { bounds.extend(pair); });
       stops.forEach(function (stop) { bounds.extend([stop.lat, stop.lon]); });
       if (bounds.isValid()) {
         fitSignature = nextFitSignature;
         map.fitBounds(bounds, { padding: [36, 36], maxZoom: 16 });
       }
     }
     ```
     - 핀이나 실제 도로 경로 지오메트리가 바뀔 때 딱 1회 `fitBounds`를 수행하며, 이후 1.5초 폴링에서 핀과 경로가 동일하면 사용자의 수동 팬/줌(Pan/Zoom) 상태를 그대로 보존한다.
  3. **임시 후보 핀(`candidate`) 레이어 추가 (`map.js` & `phone.js`):**
     - `map.js`에 별도 `candidateLayer = global.L.layerGroup().addTo(map)` 및 `candidateSignature = ''`를 두고, `data.candidate`가 유효한 좌표이면 `pin('?', 'candidate', candidateObj)`를 `candidateLayer`에 렌더링한다(`fitBounds`는 변경하지 않음).
     - `phone.js`의 `openCandidate()` 및 `closeCandidate()` 마지막에 `renderMap()`을 호출하고, `routeMap.render({ tripId: state.tripId, routeRevision: state.routeRevision, origin, stops, route, candidate })`로 `candidate`를 전달한다.
  4. **마커 및 줌 컨트롤 48px(모바일) / 64px(차량) 터치 타깃 적용 (`map.js` & `phone.css`):**
     - `map.js`의 `pin()`에서 차량 모드 여부(`document.body && document.body.classList.contains('vehicle')`)를 확인하여 `const size = isVehicle ? 64 : 48;`, `iconSize: [size, size]`, `iconAnchor: [size / 2, size / 2]`로 설정한다.
     - `phone.css`에서 `.cabin-pin`은 투명한 `48px × 48px`(차량 `64px × 64px`) 히트박스 컨테이너가 되고, 내부 `span`이 시각적 원형 배지(`34px × 34px`, 차량 `40px × 40px`)를 그리도록 스타일링한다:
       ```css
       .cabin-pin {
         background: transparent;
         border: 0;
         display: flex;
         align-items: center;
         justify-content: center;
       }
       .cabin-pin span {
         width: 34px;
         height: 34px;
         border-radius: 50%;
         background: var(--primary);
         color: #fff;
         border: 3px solid #fff;
         display: flex;
         align-items: center;
         justify-content: center;
         font-weight: 800;
         font-size: 13px;
         box-shadow: 0 2px 8px #16324F55;
       }
       .cabin-pin.origin span { background: #16324F; }
       .cabin-pin.candidate span { background: var(--orange); border-color: #16324F; }
       .leaflet-touch .leaflet-control-zoom a,
       .leaflet-control-zoom a {
         width: 48px !important;
         height: 48px !important;
         line-height: 48px !important;
         font-size: 20px !important;
       }
       body.vehicle .cabin-pin span {
         width: 40px;
         height: 40px;
         font-size: 15px;
       }
       body.vehicle .leaflet-touch .leaflet-control-zoom a,
       body.vehicle .leaflet-control-zoom a {
         width: 64px !important;
         height: 64px !important;
         line-height: 64px !important;
         font-size: 24px !important;
       }
       ```

### 결함 8: 네이티브 `window.confirm` 제거 및 접근성 준수 커스텀 확인 모달 도입
- **현상 및 원인:** `phone.js`의 4곳(방문지 일정에서 빼기, 새 여행 만들기 전환, 저장된 여행 불러오기 전환, 여행 완료)에서 브라우저 네이티브 `window.confirm()`을 사용하고 있다. Android 임베디드 `WebView`는 `WebChromeClient.onJsConfirm` 핸들러가 없으면 `window.confirm`을 무시하거나 차단하며, 자동화 브라우저 QA에서도 데드락을 유발한다.
- **설계 및 마크업 명세 (`phone.html`, `phone.css`, `phone.js`):**
  - `phone.html` 하단(`</main>` 직후)에 재사용 가능한 커스텀 모달 요소를 배치한다:
    ```html
    <div id="confirmBackdrop" class="modal-backdrop" hidden>
      <div id="confirmDialog" class="card modal-dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirmTitle" aria-describedby="confirmDesc">
        <h3 id="confirmTitle">확인</h3>
        <p id="confirmDesc"></p>
        <div class="actions modal-actions">
          <button id="confirmOk" type="button" class="primary-inline">확인</button>
          <button id="confirmCancel" type="button" class="outline">취소</button>
        </div>
      </div>
    </div>
    ```
  - `phone.js`에 Promise 기반 `askConfirm(options)` 헬퍼를 구현한다:
    ```javascript
    let confirmResolver = null, lastFocusedBeforeModal = null;

    function askConfirm({ title, description, okText = '확인', cancelText = '취소' }) {
      if (confirmResolver) confirmResolver(false);
      lastFocusedBeforeModal = document.activeElement;
      $('confirmTitle').textContent = title || '확인';
      $('confirmDesc').textContent = description || '';
      $('confirmOk').textContent = okText;
      $('confirmCancel').textContent = cancelText;
      $('confirmBackdrop').hidden = false;
      $('confirmCancel').focus();
      return new Promise(resolve => {
        confirmResolver = resolve;
      });
    }

    function closeConfirm(result) {
      if (!confirmResolver) return;
      const resolve = confirmResolver;
      confirmResolver = null;
      $('confirmBackdrop').hidden = true;
      if (lastFocusedBeforeModal && typeof lastFocusedBeforeModal.focus === 'function') {
        lastFocusedBeforeModal.focus({ preventScroll: true });
      }
      resolve(result);
    }
    ```
  - **키보드 및 배경 이벤트 처리:**
    - `Escape` 키 입력 시 `closeConfirm(false)` 호출.
    - 모달 오픈 중 `Tab` / `Shift+Tab` 포커스가 `#confirmOk`와 `#confirmCancel` 사이에서만 순환하도록 포커스 트랩 적용.
    - **적용 대상 4곳:**
      1. 방문지 삭제(`remove`): 제목 `'일정에서 빼기'`, 설명 `'{장소명}을(를) 일정에서 뺄까요? 방문 기록은 지워지지 않아요.'`, 확인 버튼 `'일정에서 빼기'`
      2. 새 여행 전환(`#newOpen`): 현재 일정에 출발지 또는 방문지가 있을 때 제목 `'새 여행 만들기'`, 설명 `'현재 일정은 저장된 여행에 보관되고 새 여행으로 이동해요. 계속할까요?'`, 확인 버튼 `'계속하기'`
      3. 저장된 여행 불러오기(`#openTrip`): 제목 `'저장된 여행 불러오기'`, 설명 `'현재 일정은 저장된 여행에 보관하고 선택한 여행을 불러와요. 계속할까요?'`, 확인 버튼 `'불러오기'`
      4. 여행 완료(`#finishTrip`): 제목 `'여행 완료'`, 설명 `'여행을 완료하면 기록으로 보관되고 일정은 수정할 수 없어요. 완료할까요?'`, 확인 버튼 `'여행 완료'`

### 결함 9: 데스크톱 2컬럼 그리드에서 '여행 일정' 제목·역할 배지·차량 안내 배너가 왼쪽 열에 분리되는 문제
- **현상 및 원인:** `phone.html`에서 `<div class="sectiontitle"><h2>여행 일정</h2><span id="roleBadge" class="pill"></span></div>`와 `<p id="driverNotice" class="statusbanner" hidden></p>`가 `<section class="card plancard">` 바깥에 위치하고, `phone.css`의 `@media(min-width:900px)`에서 `"heading plan"`, `"notice plan"`으로 그리드 영역이 지정되어 제목/배너는 왼쪽 열 하단에, 실제 일정 카드는 오른쪽 열 상단에 분리되었다.
- **마크업 및 CSS 수정 명세 (`phone.html`, `phone.css`):**
  - `phone.html`에서 `.sectiontitle`과 `#driverNotice`를 `<section class="card plancard">`의 첫 자식 요소로 이동한다:
    ```html
    <section class="card plancard">
      <div class="sectiontitle plan-header">
        <h2>여행 일정</h2>
        <span id="roleBadge" class="pill"></span>
      </div>
      <p id="driverNotice" class="statusbanner" hidden></p>
      <div class="planrow originrow">
        ...
    ```
  - `phone.css`의 `#routePanel` 그리드 템플릿에서 `heading`과 `notice` 영역을 제거한다:
    - 모바일 기본: `grid-template-areas: "map" "summary" "legs" "plan" "search" "proposals" "history";`
    - `@media (min-width: 900px)` 및 `@media (min-width: 800px) body.vehicle`:
      `grid-template-areas: "map plan" "summary plan" "legs plan" "search plan" "proposals plan" "history plan";`

### 결함 10: `/vehicle` 임베디드 모드의 중복 상단 헤더·하단 탭 및 세션/검색 안내 문구 결함
- **수정 명세 (`phone.html`, `phone.css`, `phone.js`):**
  1. **`/vehicle` 헤더 및 네비게이션 정리:**
     - `<header class="vehicle-hide">`로 지정하고 `body.vehicle header { display: none !important; }`를 추가하여 차량 모드에서 중복 웹 헤더를 숨긴다.
     - `header a.brand`는 `vehicle` 모드일 때 `document.querySelector('.brand').setAttribute('href', '/vehicle')`로 설정한다.
     - 하단 내비게이션 `#docentTab`과 `#familyTab`에 `class="vehicle-hide"`를 추가하고, `body.vehicle nav { grid-template-columns: repeat(2, minmax(0, 1fr)); }`를 적용해 차량 모드에서는 `[경로]`와 `[기록]` 2개 탭만 균등 2열로 표시한다.
  2. **세션 수명주기 및 저장소 위치 문구 수정 (`phone.html`):**
     - `#join` 하단 문구: *"차량과 같은 개인 Wi-Fi에서 연결해 주세요. 저장된 일정과 기록은 차량에 보관되며, 장소 검색과 도로 경로는 인터넷이 필요해요. 차량에서 공유를 종료하면 다시 초대받아야 해요."*
     - `#familyPanel` 하단 `details.help` 문구: *"휴대폰과 차량이 같은 개인 Wi-Fi에 있는지, 차량에서 가족 공유가 켜져 있는지 확인해 주세요."* / *"차량에서 공유를 종료했거나 권한이 철회되었다면 차량에서 새 초대를 받아야 해요. 저장된 일정과 여행 기록은 차량에 그대로 보관돼요."*
  3. **장소 검색 안내 문구 간소화 (`phone.html`):**
     - `.searchcard` 안내 문구를 `<p class="small">장소 검색은 인터넷 연결이 필요해요.</p>` 한 줄로 간결하게 교체한다.

---

## 6. 구현 단계(gemini-4-hydrogen) 체크리스트 및 검증 기준

1. **파일 수정 범위 준수:** `app/src/main/assets/phone.html`, `phone.css`, `phone.js`, `map.js` 4개 파일만 수정하며, Java 서버/도메인 코드나 `vendor/leaflet*` 파일은 건드리지 않는다.
2. **구문 및 정적 검증:** 수정 완료 후 `node --check app/src/main/assets/phone.js` 및 `node --check app/src/main/assets/map.js`가 오류 없이 통과해야 한다.
3. **핵심 인수 시나리오 10선:**
   - [ ] 신규 참가자가 `/phone`에서 초대 코드로 참여 직후 새로고침 없이도 1.5초 폴링이 자동 시작되어 다른 기기의 변경 사항을 실시간 수신하는가?
   - [ ] 여행 완료(`completed`) 상태에서도 운전자(정차 중)와 `editor`가 `새 여행` 만들기 및 `저장된 여행 불러오기`를 수행할 수 있는가? (단, 완료된 여행 자체의 제목 변경·장소 추가·삭제·경로 미리보기는 비활성)
   - [ ] 일시적 연결 끊김(`connected === false`) 상태에서도 `[기록]` 탭에서 이미 로드된 여행 기록의 `기록 자세히 보기`, `시작 일정 / 최종 일정` 토글, `[도슨트]` 탭 열람이 정상 동작하는가?
   - [ ] 지도를 클릭하면 임시 후보 핀(`?`)과 `#candidate` 패널이 열리고, 서버 편집(`submitEdit`)이 성공했을 때만 패널이 닫히며 실패 시 입력한 이름과 좌표·핀이 보존되는가?
   - [ ] 빠른 연속 검색 시 이전 검색 응답이 최신 결과를 덮어쓰지 않으며, `routeBusy` 중에 발생한 일정 변경이 누락되지 않고 직전 경로 요청 종료 후 1회 재계산되는가?
   - [ ] 기록 상세 화면에서 여행 시작 후 추가되어 방문 완료된 장소가 `시작 일정` 조회 시 `'여행 중 추가'`로, 최종 일정에서 삭제된 방문 장소는 `'최종 일정에서 제외'`로 정확히 표시되며, 완료된 여행의 `completedAt === null`이 `'시각 기록 없음'`으로 표시되는가?
   - [ ] `map.js`가 `latitude/longitude`와 `lat/lon`을 모두 정규화하여 출발지(`출`) 핀을 누락하지 않고, 우회 도로 폴리라인 로드 시 `fitBounds`를 갱신하며, 마커와 줌 컨트롤이 모바일 `48px` / 차량 `64px` 크기를 충족하는가?
   - [ ] 모든 `window.confirm`이 제거되고 접근성(역할, 라벨, `Escape` 취소, 포커스 복원)을 갖춘 커스텀 모달(`#confirmDialog`)로 동작하는가?
   - [ ] `>=900px` 2컬럼 그리드에서 `여행 일정` 제목·역할 배지·차량 안전 배너가 우측 `.plancard` 상단 내부에 위치하여 좌우 열 분리 현상이 사라졌는가?
   - [ ] `/vehicle` 모드에서 상단 중복 웹 헤더가 숨겨지고 브랜드 링크가 `/vehicle`을 유지하며, 하단 내비게이션이 `[경로] / [기록]` 2열(`64px` 높이)로만 표시되는가?
