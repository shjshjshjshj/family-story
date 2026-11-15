# Cabin Story 차량용 IVI 앱 기획·UX·모듈 인터페이스 설계서 (design-vehicle-argon)

- **작성 모델**: `gemini-4-argon` (차량용 앱 / Pleos Connect 플랫폼 기획·UX·모듈 인터페이스 담당)
- **후속 구현 모델**: `gemini-4-hydrogen` (실제 Android Java / XML / Web 연동 구현 전담)
- **대상 패키지**: `kr.cabinstory` (`D:\Codex\현대차 공모전\cabin-story\app\src\main\java\kr\cabinstory`)
- **기준 문서**:
  - `D:\Codex\현대차 공모전\Cabin_Story_프로젝트_기획_정리.md`
  - `D:\Codex\현대차 공모전\cabin-story\docs\planner-rebaseline.md`
  - `D:\Codex\현대차 공모전\cabin-story\docs\unit-planner-r4-task.md`

---

## 1. 현황 진단 및 R4 차량용 IVI 전환의 핵심 과제

### 1.1 이미 완료된 R1~R3 기반 자산 (재사용 대상)
현재 코드베이스에서 데이터 모델, 오프라인/온라인 하이브리드 지도 라우팅, 로컬 HTTP 협업 서버, 모바일·차량 공용 플래너 웹 UI는 이미 구현되어 있다. `gemini-4-hydrogen`은 아래 자산을 재작성하지 않고 그대로 연결해야 한다.

| 계층 | 파일 | 구현 완료 내용 및 차량용 연동 포인트 |
|---|---|---|
| 데이터/스냅샷 | `TripStore.java` | 경주 기본 4곳 시드(도슨트·동화·루루 일러스트 메타 포함), 출발지(`origin`), 최대 20개 경유지(`stops`), 가족 제안 큐(`proposals`), 멤버 토큰/역할(`viewer`/`proposer`/`editor`/`revoked`), `routeRevision` vs `approvedRevision`, `startedPlan` / `completedPlan` 스냅샷, `visitEvents`(`completedAt` 최초 방문 시각 보존) |
| 지도/경로 엔진 | `MapService.java` | 경주 내장 명소·추천 장소 검색 + Photon 외부 지오코딩, FOSSGIS OSRM 도로 폴리라인 계산 + 직선 폴백, `routeCache` 기반 오프라인/재시도 대응 |
| 로컬 협업 서버 | `FamilyServer.java` | 포트 `8787`, `/phone` 및 `/vehicle` 정적 에셋 서빙(`phone.html`, `phone.css`, `phone.js`, `leaflet.*` 로컬 번들, `lulu/*.svg`, `lulu/*.png`), `driverToken`(프로세스 내 UUID) 발급 및 루프백 `127.0.0.1:8787` + `Bearer <driverToken>` 검증을 통한 `driver` 역할 부여 |
| 공용 플래너 웹 | `assets/phone.html`, `phone.css`, `phone.js` | `/vehicle` 경로 진입 시 `location.hash`에서 `driverToken` 추출 후 `sessionStorage['cabin-driver-session']` 저장 및 URL 해시 즉시 제거, Leaflet 실제 도로 폴리라인 렌더링, `window.CabinVehicle.requestNavigation()` 브리지 호출 버튼(`#requestNavi`) 내장 |
| 음성 도슨트 | `DocentPlayer.java` | Pleos `TextToSpeech`(`Mode.ON_DEVICE`, `TextToSpeechLanguage.KR`) 연동, Android `TextToSpeech` 폴백, `AudioFocusRequest`(`USAGE_MEDIA`/`CONTENT_TYPE_SPEECH`) 기반 덕킹/일시정지 |

### 1.2 차량용 앱(`MainActivity`, `VehicleBridge`)의 미해결 문제 4가지
1. **내비게이션 전환 시 세션·웹서버 강제 종료 문제**:
   - 현재 `MainActivity`가 `TripStore`, `FamilyServer`, `VehicleBridge`, `DocentPlayer`를 직접 소유하고 `onStart()`에서 시작, `onStop()`에서 `server.stop()`, `vehicle.stop()`, `docent.release()`를 호출한다.
   - 운전자가 경로를 승인하고 Pleos 순정 내비게이션으로 화면을 전환하면 `MainActivity.onStop()`이 호출되어 `FamilyServer:8787`이 내려가고 가족 휴대폰 연결이 모두 끊기며, 차량 SDK 이벤트 수신과 도슨트 오디오가 중단된다.
2. **차량 IVI 화면과 웹 플래너의 단절**:
   - `FamilyServer`와 `phone.js`에는 이미 `/vehicle` 운전자 전용 뷰가 준비되어 있으나, `MainActivity`는 여전히 R1 이전의 정적 카드 리스트(`여행 홈`, `함께 짠 일정`)만 렌더링하여 차량 대화면에서 실제 도로 경로 폴리라인과 경유지 편집/승인 화면을 볼 수 없다.
3. **단일 목적지 내비 전달 한계 및 검증 타깃 충돌**:
   - `VehicleBridge.requestRoute(JSONObject place, int revision)`는 경유지 1곳만 `NaviHelper.requestRoute(RouteInfo)`로 전달한다.
   - Pleos `NaviHelper 3.0.5`가 지원하는 경유지 포함 경로 요청(`requestRouteWithWaypoints`, 경유지 최대 2곳 + 최종 목적지 1곳 = 최대 3곳)을 활용하지 못하고 있다.
   - 또한 `onDestinationInfo(DestinationInfo)` 콜백에서 내비 목적지 좌표를 도슨트 타깃(`target`, 즉 첫 번째 미방문 정류장)과 비교하고 있어, 다중 경유지 전달 시(내비 최종 목적지 = 2번째/3번째 미방문 정류장, 도슨트 타깃 = 1번째 미방문 정류장) 목적지 좌표 검증이 실패한다.
4. **빈 일정(`stops == empty`)에서의 Null 역참조 및 주행 안전 가드 미흡**:
   - 모든 경유지를 삭제하거나 완료해 `nextStop()`이 `null`일 때 `VehicleBridge.setTarget(null)`이 `place.optString("id")`를 호출하여 `NullPointerException` 위험이 있다.
   - 2초 주기의 기어 폴링(`gearRefresh`)마다 `listener.onVehicleUpdate()`가 무조건 호출되어 Activity 전체 뷰 트리와 `WebView`가 재생성될 위험이 있다.

---

## 2. 차량용 IVI 관점의 서비스 3대 핵심 축 정의

차량용 앱은 단순 원격 리모컨이 아니라 **운전자 최종 의사결정 콘솔 + 캐빈 오디오 허브 + 여정 기록 보관소**로서 아래 3대 핵심 가치를 일관되게 제공해야 한다.

### 2.1 축 1: 가족 협업 여행 경로 플래너 (Collaborative Route Planner)
- **플로우**: 출발지(`origin`) 설정 → 경유지(`stops`, 최대 20곳) 추가·순서 조정·삭제 → 실제 도로 경로 폴리라인(OSRM/직선 폴백) 및 총 거리·예상 소요시간 확인 → 가족 제안(`proposals`) 수락/거절 → 운전자 경로 승인(`approveRoute`: `approvedRevision = routeRevision`) → 미방문 경유지 최대 3곳 묶음 Pleos 내비게이션 전달.
- **권한 및 안전 규칙**:
   - 차량 화면(`/vehicle`)은 루프백 + `driverToken` 인증으로 `driver` 권한을 자동 획득한다.
   - 단, **P단(주차) 상태가 최근 6초 이내에 검증된 경우에만** 차량 화면에서 지도 드래그/줌, 장소 검색, 제안 수락/거절, 경유지 편집이 허용된다.
   - 주행 중(D/R/N 또는 P단 신호 6초 초과/미수신)에는 웹 플래너의 복잡한 조작을 잠그고, 상단 네이티브 안전 액션 바의 대형 버튼(64dp 이상) 또는 음성 도슨트 화면으로 안내한다.

### 2.2 축 2: 장소 기반 음성·그림책 도슨트 (Voice & Picture-Book Docent)
- **성인 역사/문화 해설 vs 키즈 동화 + 캐릭터 루루(Lulu)**:
   - **경주 기본 4대 명소**(대릉원 천마총 `gyeongju-daereungwon`, 첨성대 `gyeongju-cheomseongdae`, 동궁과 월지 `gyeongju-wolji`, 국립경주박물관 `gyeongju-museum`)는 성인 해설 스크립트(`adultStory`), 어린이 눈높이 요약(`kidsStory`), 3페이지 분량의 동화(`kidsPages`), 캐릭터 루루 SVG/PNG 일러스트(`luluAsset`, `photoAsset`), 퀴즈/대화 프롬프트(`adultPrompt`, `kidsPrompt`)를 모두 보유한다(`hasDocent = true`).
   - **검색/직접 추가한 일반 경유지**는 `hasDocent = false`(또는 `adultStory`/`kidsStory` 빈 문자열)로 저장된다.
- **도슨트 미보유 경유지 정직성 규칙 (Honest Empty-Script Rule)**:
   - `hasDocent == false`이거나 선택된 모드의 스크립트가 비어 있는 경유지에서는 임의의 가짜 해설이나 생성형 문구를 만들어 읽지 않는다.
   - 화면에 **"등록된 해설 없음 — 이 경유지는 위치·경로 안내와 방문 기록만 제공됩니다."** 배지를 명확히 표시하고, `[해설 듣기]` 버튼을 비활성화(`disabled`)하며, 도착/접근 트리거 발생 시에도 TTS 합성을 호출하지 않고 토스트/상태 줄로만 도착을 알린다.
- **차량 vs 모바일 역할 분담**:
   - **주행 중 차량 화면**: 운전자의 시선 분산을 막기 위해 긴 텍스트 본문과 페이지 넘김형 그림책을 숨기고, 현재 정류장 제목·거리·오디오 상태와 대형 오디오 제어 버튼(`[해설 듣기]`, `[일시정지/중지]`, `[성인 해설 / 아이 동화 전환]`, `[방문 완료 처리]`)만 노출한다.
   - **주차(P단) 중 차량 화면 및 동승자 휴대폰**: 루루(Lulu) 그림책 일러스트와 3페이지 키즈 동화 카드(`kidsPages`)를 함께 열람할 수 있다.
- **음성 합성(TTS) 및 음성 인식(STT) 연동 원칙**:
   - TTS는 검증 완료된 `DocentPlayer`(Pleos `TextToSpeech` `Mode.ON_DEVICE` 한국어 합성 + Android TTS 폴백 + `AudioFocus` 덕킹)를 그대로 사용한다.
   - STT(음성 명령/퀴즈 응답)는 선택적 확장 인터페이스로만 정의하며, Pleos `SpeechToText` 미연결·권한 거부 시에도 터치 UI만으로 모든 도슨트 제어와 질문 카드 확인이 완결되어야 한다.

### 2.3 축 3: 여행 경로 기록 (Trip Route Record)
- **스냅샷 기반 계획 대 실제 비교**:
   - 상시 백그라운드 GPS 궤적 수집은 수행하지 않는다(배터리·프라이버시·SDK 제약 준수).
   - 대신 **출발 시점 스냅샷(`startedPlan`)**과 **종료 시점 스냅샷(`completedPlan`)**, 그리고 각 경유지 방문 완료 시점의 **방문 이벤트(`visitEvents[]: { place, completedAt }`)**를 기록한다.
- **기록 보존 불변식**:
   - 이미 방문 완료된 정류장의 최초 `completedAt` 시각은 재방문·순서 변경 시에도 덮어쓰지 않으며, 레거시 데이터의 `completedAt: null`도 임의 시각으로 조작하지 않는다.
   - `[가족 공유 종료]`를 눌러 세션 서비스를 내려도 `TripStore`의 영속 파일(`trip-plan.json`)에 저장된 여행 계획과 방문 기록은 삭제되지 않는다.

---

## 3. Pleos Connect IVI UX·주행 안전·Material Design 시스템

### 3.1 디스플레이 규격 및 레이아웃 그리드
- **기준 해상도**: Pleos Connect IVI 가로형 와이드 디스플레이 **2560 × 1440 px (16:9 Landscape)**.
- **반응형 스케일링**:
   - `MainActivity`의 `scale` 계산식 `Math.max(0.84f, Math.min(1.15f, Math.min(widthDp / 960f, heightDp / 540f)))`을 유지한다.
   - **좌측 고정 Navigation Rail**: 기본 너비 `168dp * scale`, 시스템 글꼴 크기 확대(`configuration.fontScale >= 1.3f`) 시 레이블 줄바꿈 깨짐 방지를 위해 **`192dp * scale`**로 자동 확장한다.
   - **우측 메인 콘텐츠 영역**: `layout_weight="1"`, 내부 패딩 `18dp * scale`.
   - **최소 터치 타깃**: 모든 주행 중 조작 가능한 버튼·탭 아이템은 최소 높이 **`64dp`** 이상, 주요 주행 액션 CTA(`해설 듣기`, `일시정지`, `내비 전달`)는 **`72dp~84dp`** 높이를 확보한다.
   - **한 화면 정보 밀도 제한**: 주행 중 화면에는 핵심 액션 버튼을 최대 4개 이내로 제한하고, 중첩 스크롤(ScrollView 안의 ScrollView/WebView 스크롤 충돌)을 금지한다.

### 3.2 주차(P단) vs 주행(D/R/N/Unknown) 안전 상태 머신

```
                  [기어 == P && (now - lastGearTimestampMs <= 6000ms)]
                                         │
                   ┌─────────────────────┴─────────────────────┐
                   ▼ (True: PARKED_SAFE)                       ▼ (False: DRIVING_LOCK)
     ┌───────────────────────────┐               ┌───────────────────────────┐
     │       주차 안전 모드       │               │       주행 잠금 모드       │
     │ - 기본 탭: [경로 플래너]   │  ──기어 D/R/N─▶│ - 자동 전환: [음성 도슨트] │
     │ - WebView 편집/검색 활성   │  ◀──기어 P────│ - WebView 터치 차단 오버레이│
     │ - 가족 QR/초대 링크 표시   │               │ - QR/초대 상세 설정 숨김   │
     │ - 루루 그림책 프리뷰 활성  │               │ - 대형 오디오 버튼만 활성  │
     └───────────────────────────┘               └───────────────────────────┘
```

1. **P단 신선도(Freshness) 판정**:
   - `VehicleBridge.isParked()`는 `"P".equalsIgnoreCase(gear) && (SystemClock.elapsedRealtime() - lastGearTimestampMs <= 6_000L)`일 때만 `true`를 반환한다.
   - 기어 정보가 `D`, `R`, `N`이거나 SDK 미연결/타임아웃으로 6초 이상 갱신되지 않으면 즉시 **주행 잠금(Fail-Safe Driving Lock)** 상태로 간주한다.
2. **화면 전환 규칙 (`selectedTab` vs `safetyOverride`)**:
   - 앱 최초 진입 시 주차 상태이면 기본 탭은 `0: [경로 플래너]`, 주행 상태이면 `1: [음성 도슨트]`로 시작한다.
   - 운전자가 `[경로 플래너]` 또는 `[가족 연결]` 탭을 보고 있던 중 기어가 P단에서 주행(D/R/N)으로 바뀌면, 사용자의 원래 탭 인덱스(`userSelectedTab`)는 보존한 채 `safetyOverride = true`를 켜서 화면을 즉시 `[음성 도슨트]` 뷰로 전환한다.
   - 다시 P단으로 정차하면 `safetyOverride = false`로 해제되어 운전자가 보던 원래 탭(`[경로 플래너]` 등)으로 자연스럽게 복귀한다.
   - 만약 주행 중 동승자가 불가피하게 `[경로 플래너]` 탭을 직접 탭하더라도, 하단 `WebView` 위에는 반투명 주행 잠금 실드(`DrivingLockOverlay`: *"주행 중에는 지도 편집이 잠깁니다. 정차(P단) 후 경로를 수정하거나 동승자 휴대폰을 이용해 주세요."*)가 덮여 세밀한 지도 핀 조작과 키보드 입력을 물리적으로 차단한다. 상단 네이티브 안전 액션 바의 승인된 경로 내비 재전달 및 도슨트 이동 버튼만 동작한다.

### 3.3 컬러 팔레트 및 타이포그래피 토큰 (`docs/unit-planner-r4-task.md` & Material 기준 준수)
기존 `MainActivity`의 구형 녹색/베이지 색상(`#F4EFE6`, `#1F6B52`)을 전면 제거하고, 모바일/차량 웹 플래너(`phone.css`) 및 R4 명세와 통일된 **Clean Slate & Trust Blue** 토큰을 적용한다.

| 역할 (Token Role) | Hex 코드 | 적용 대상 | 대비 및 가독성 기준 |
|---|---|---|---|
| `bg.canvas` | `#F8FAFC` | 우측 메인 배경, 기본 캔버스 | 눈부심을 억제한 밝은 슬레이트 오프화이트 |
| `bg.surface` | `#FFFFFF` | 카드 표면, 상단 안전 액션 바, 도슨트 컨트롤 패널 | `bg.canvas`와 명확히 구분되는 순백색 표면 |
| `bg.rail` | `#0F2338` | 좌측 고정 Navigation Rail 배경 | 주행 중 시야 방해를 줄이는 딥 네이비 슬레이트 |
| `bg.cabinVoiceCard` | `#16324F` | 음성 도슨트 메인 히어로 카드 (주행 중 고대비 영역) | 백색 텍스트(`#FFFFFF`) 대비 명암비 12.6:1 충족 |
| `text.primary` | `#16324F` | 기본 제목·본문 텍스트 | `bg.surface`(`#FFFFFF`) 대비 명암비 12.6:1 (AAA) |
| `text.secondary` | `#475569` | 보조 설명, 주소, 타임스탬프 | `bg.surface` 대비 명암비 7.5:1 (AAA) |
| `accent.primary` | `#245AE8` | 주요 CTA 버튼(내비 전달, 해설 재생, 선택된 탭 인디케이터) | 백색 텍스트 대비 명암비 5.6:1 (AA Large/Normal 충족) |
| `accent.soft` | `#E8F0FE` | 선택된 카드 배지, 활성 상태 배경 하이라이트 | `accent.primary` 및 `text.primary`와 조화 |
| `semantic.success` | `#1B7F59` | P단 주차 안전 배지, 경로 승인 완료, 방문 완료 배지 | 녹색 신호 시맨틱 |
| `semantic.warning` | `#C76A19` | 주행 중 편집 잠금 배지, 재승인 필요(Revision 불일치), 3곳 분할 안내 | 주황 경고 시맨틱 |
| `semantic.danger` | `#A3312B` | 오류 안내, 멤버 권한 회수, 가족 공유 종료 확인 버튼 | 적색 위험 시맨틱 |
| `border.subtle` | `#CBD5E1` | 카드 및 액션 바 외곽선 (1dp) | 시인성 확보용 경계선 |

- **타이포그래피 스케일 (`sp * scale`)**:
  - 화면/정류장 대표 타이틀: `26sp` Bold (`#16324F` 또는 히어로 카드 내 `#FFFFFF`)
  - 섹션 제목 및 주요 상태 수치: `20sp` Bold
  - 버튼 레이블 및 본문: `17sp` Medium/Bold (최소 `15sp` 미만 사용 금지)
  - 보조 캡션/진단 수치: `15sp` Regular (`#475569`)

---

## 4. `CabinSessionService` 포그라운드 서비스 아키텍처 및 생명주기 명세

Pleos 순정 내비게이션이나 다른 IVI 앱이 전면으로 올라와 `MainActivity`가 `onStop()` 상태가 되어도 가족 휴대폰 웹 플래너 연결(`:8787`), 차량 기어/위치/내비 콜백 수신, 도슨트 오디오 재생이 끊기지 않도록 모든 런타임 상태를 **`CabinSessionService`**로 이관한다.

### 4.1 `AndroidManifest.xml` 권한 및 서비스 선언
`app/src/main/AndroidManifest.xml`에 아래 권한과 서비스, 네트워크 보안 설정을 등록한다.

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application
    ...
    android:networkSecurityConfig="@xml/network_security_config">

    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|density|fontScale">
        ...
    </activity>

    <service
        android:name=".CabinSessionService"
        android:enabled="true"
        android:exported="false"
        android:foregroundServiceType="connectedDevice|mediaPlayback" />
</application>
```

- **Cleartext 네트워크 제한 (`app/src/main/res/xml/network_security_config.xml`)**:
  - 전역 `usesCleartextTraffic="true"`는 사용하지 않는다.
  - 루프백 `127.0.0.1`과 `localhost`만 cleartext HTTP를 허용하고, 외부 도메인(OSM 타일, Photon, FOSSGIS OSRM)은 HTTPS만 사용한다.
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <network-security-config>
      <base-config cleartextTrafficPermitted="false" />
      <domain-config cleartextTrafficPermitted="true">
          <domain includeSubdomains="false">127.0.0.1</domain>
          <domain includeSubdomains="false">localhost</domain>
      </domain-config>
  </network-security-config>
  ```

### 4.2 `CabinSessionService` 컴포넌트 소유 구조 및 시작 시퀀스

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    CabinSessionService (Foreground)                     │
│  - Notification: "가족 기기와 여행 계획 공유 중" + Action("공유 종료")     │
│  - Owns: TripStore, FamilyServer(:8787), VehicleBridge, DocentPlayer    │
└───────────────┬─────────────────────────────────────────┬───────────────┘
                │ Local Binder (CabinBinder)              │ Loopback HTTP (:8787)
                ▼                                         ▼
┌───────────────────────────────┐         ┌───────────────────────────────┐
│         MainActivity          │         │   Embedded WebView (/vehicle) │
│ - 4-Tab Left Navigation Rail  │────────▶│ - URL: 127.0.0.1:8787/vehicle │
│ - Native Safety Action Bar    │ JSBridge│   #<driverToken>              │
│ - Docent / Family / Diag Views│◀────────│ - @JavascriptInterface        │
└───────────────────────────────┘         │   window.CabinVehicle         │
                                          └───────────────────────────────┘
```

1. **즉시 포그라운드 승격 (ANR 및 `ForegroundServiceDidNotStartInTimeException` 방지)**:
   - `onStartCommand()` 진입 즉시(무거운 SDK 초기화나 소켓 바인딩 이전) 채널 ID `cabin_session_channel`(`IMPORTANCE_LOW`, 무음)로 알림을 생성하고 `startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`(API 레벨 분기 포함)를 호출한다.
   - 알림 제목: **"캐빈 스토리 — 가족 기기와 여행 계획 공유 중"**
   - 알림 본문: 현재 연결 주소(`http://<LAN_IP>:8787/phone`) 및 다음 정류장 요약
   - 알림 액션 버튼: **`[공유 종료]`** (`ACTION_STOP_SESSION = "kr.cabinstory.action.STOP_SESSION"` PendingIntent)
   - 반환값: **`START_NOT_STICKY`** (사용자가 명시적으로 종료하거나 시스템이 정리한 뒤 유령 서버가 자동 부활하지 않도록 보장).
2. **비동기 바인딩 계약 (`MainActivity` ↔ `CabinSessionService`)**:
   - `MainActivity.onStart()`에서 `ContextCompat.startForegroundService(this, serviceIntent)` 호출 후 `bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)`를 수행한다.
   - `MainActivity`는 `onCreate()` 시점에 서비스가 즉시 바인딩되어 있다고 가정하지 않고, `ServiceConnection.onServiceConnected()`가 도착하기 전에는 경량 로딩 상태(*"차량 세션 서비스를 준비하고 있습니다..."*)를 표시한 뒤 바인딩 완료 즉시 UI와 `WebView`를 연결한다.
   - `MainActivity.onStop()`에서는 `unbindService(connection)`와 UI 리스너 해제만 수행하며, **`FamilyServer`, `VehicleBridge`, `DocentPlayer`는 절대 중지하지 않는다.**
3. **명시적 종료 시퀀스 (`stopSessionAndExit()`)**:
   - 알림의 `[공유 종료]` 액션 또는 `[가족 연결]` 탭의 `[가족 공유 종료]` 확인 버튼을 누르면:
     1. `DocentPlayer.stop()` 및 `release()`
     2. `VehicleBridge.stop()`
     3. `FamilyServer.stop()` (8787 포트 즉시 해제)
     4. `stopForeground(STOP_FOREGROUND_REMOVE)` 및 `stopSelf()`
     5. 바인딩된 `MainActivity`에 `onSessionStopped()` 콜백을 전달하여 세션 종료 안내 배너(*"가족 공유가 종료되었습니다. 여행 기록은 기기에 안전하게 보관됩니다."*)와 `[세션 다시 시작]` 버튼을 표시한다.
     6. `TripStore`의 저장 데이터(`trip-plan.json`)는 건드리지 않는다.
4. **세션 재시작 시 `driverToken` 갱신 동기화**:
   - 서비스가 종료되었다가 다시 시작되면 `FamilyServer`가 새로운 `driverToken`을 생성한다.
   - `CabinSessionService.getDriverVehicleUrl()`이 반환하는 `http://127.0.0.1:8787/vehicle#<newDriverToken>`이 이전 로드된 토큰과 다를 경우, `MainActivity`는 `WebView`의 `sessionStorage.removeItem('cabin-driver-session')`을 실행하거나 새 해시 URL로 `webView.loadUrl(newUrl)`을 호출해 만료된 토큰으로 403 오류가 발생하지 않게 한다.

### 4.3 콜백 디바운싱 및 UI 부분 갱신 규칙 (2초 기어 폴링 보호)
`VehicleBridge`는 2초마다 `gearRefresh` Runnable을 실행한다. 이 틱마다 화면 전체를 다시 그리거나 `WebView`를 리로드하면 지도 조작이 불가능해진다. 따라서 `CabinSessionService`는 아래 3종류의 이벤트를 분리하여 `SessionListener`로 전달한다.

```java
public interface SessionListener {
    /**
     * 기어(P/D/R/N), 주차 가능 여부(isParked), 내비 상태 문자열, 현재 타깃 정류장 ID,
     * 마지막 거리 버킷(10m 단위), 마지막 에러가 실제로 변경되었을 때만 호출.
     */
    void onVehicleStateChanged(VehicleUiSnapshot snapshot);

    /**
     * TripStore의 stateRevision / routeRevision / approvedRevision / visitEvents 등이
     * 변경되었을 때 호출.
     */
    void onTripDataChanged(int stateRevision, int routeRevision, int approvedRevision);

    /**
     * 사용자가 명시적으로 [공유 종료]를 실행했을 때 호출.
     */
    void onSessionStopped();
}
```
- **핵심 불변식**: `onVehicleStateChanged`나 `onTripDataChanged`가 호출되더라도 **기존에 생성된 `WebView` 인스턴스는 파괴하거나 재생성하지 않는다.** 네이티브 상단 안전 액션 바의 텍스트/버튼 활성 상태와 주행 잠금 오버레이의 `visibility`만 부분 갱신(in-place update)한다.

---

## 5. 좌측 4탭 Navigation Rail 및 화면별 상세 UX 명세

### 5.1 좌측 고정 Navigation Rail (공통 셸)
좌측 레일(`168dp * scale`, `fontScale >= 1.3f` 시 `192dp * scale`, 배경 `#0F2338`)은 상단부터 아래 요소를 배치한다.

1. **브랜드 & 주행 상태 헤더 카드**:
   - 타이틀: `Cabin Story` (`20sp` Bold, `#FFFFFF`)
   - 실시간 주행/주차 배지:
     - 주차(P단 6초 이내): 배경 `#1B7F59`, 텍스트 **`P 주차 · 편집 가능`**
     - 주행/신호 미확인: 배경 `#C76A19`, 텍스트 **`주행 안전 잠금 · 음성 안내 모드`**
2. **4개 주 메뉴 탭 버튼 (각 높이 최소 `68dp * scale`, 간격 `10dp`)**:
   - **탭 0: `경로 플래너`** (부제: `지도 · 경유지 · 내비`)
   - **탭 1: `음성 도슨트`** (부제: `역사 해설 · 루루 동화`)
   - **탭 2: `가족 연결`** (부제: `QR/링크 · 권한 관리`)
   - **탭 3: `연동 진단`** (부제: `Pleos SDK · 데모 제어`)
3. **하단 세션 상태 요약 푸터**:
   - 서버 상태(`공유 서버 :8787 실행 중`), 참여 가족 수(`가족 N명 연결`), 승인 상태(`승인 완료 #R` 또는 `재승인 필요`).

---

### 5.2 탭 0: `[경로 플래너]` (하이브리드 네이티브 안전 바 + 임베디드 `/vehicle` WebView)

우측 영역 전체를 수직 `LinearLayout`으로 구성하되, **상단 고정 네이티브 안전 액션 바(Native Safety Action Bar)**와 **하단 `FrameLayout`(임베디드 `WebView` + 주행 잠금 오버레이)**로 분할한다. 중첩 스크롤이 발생하지 않도록 상단 바는 고정 높이를 갖고 하단 `WebView`가 나머지 높이(`layout_weight="1"`)를 100% 사용한다.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ [상단 네이티브 안전 액션 바] (#FFFFFF, border #CBD5E1, padding 14dp)         │
│  ● 다음 정류장: #1 대릉원 (천마총) · 320m  | 승인 상태: 승인됨 (Rev 4)        │
│  ┌──────────────────────────┐ ┌────────────────────┐ ┌────────────────────┐  │
│  │ 다음 경유지 내비 전달(3곳)│ │ 현재 정류장 도슨트 │ │ 방문 완료 / 다음으로│  │
│  └──────────────────────────┘ └────────────────────┘ └────────────────────┘  │
├──────────────────────────────────────────────────────────────────────────────┤
│ [하단 임베디드 WebView 영역] (http://127.0.0.1:8787/vehicle#<driverToken>)   │
│  - 좌측/상단: 출발지·경유지 리스트, 제안함 수락/거절, 여정 시작/종료/초기화  │
│  - 우측/중앙: Leaflet 실제 도로 폴리라인 지도 + 장소 검색 시트               │
│                                                                              │
│  (주행 중 D/R/N 전환 시 반투명 DrivingLockOverlay가 WebView 터치를 차단)     │
└──────────────────────────────────────────────────────────────────────────────┘
```

#### A. 상단 네이티브 안전 액션 바 (Native Safety Action Bar)
WebView 내부 버튼이 작은 화면이나 로딩 지연에 영향받지 않도록, 운전자가 가장 자주 쓰는 핵심 액션 3가지를 상단 네이티브 바에 항상 노출한다.
1. **상태 요약 줄**:
   - 첫 번째 미방문 경유지 순번·이름·거리(예: `다음 #2 첨성대 · 180m`), 전체 진행률(`방문 1 / 4곳`), 경로 승인 배지(`승인됨` `#1B7F59` vs `경로 변경됨 · 승인 필요` `#C76A19`).
   - 미방문 경유지가 4곳 이상일 때는 **`내비 전달 시 전체 N곳 중 다음 3곳을 먼저 안내합니다`** 안내 문구를 함께 표시한다.
2. **네이티브 액션 버튼 3종 (높이 `64dp * scale`)**:
   - **버튼 1 (`btnNativeSendNavi`)**:
     - 미승인 상태(`approvedRevision != routeRevision`)이고 주차(P단) 중일 때: **`경로 승인 후 내비 전달 (최대 3곳)`** → 클릭 시 `store.approveRoute(true)` 수행 후 즉시 `requestConfirmedNavigation()` 호출.
     - 승인 완료 상태일 때: **`다음 경유지 내비 전달 (N곳)`** (주행 중에도 이미 승인된 경로의 다음 미방문 1~3곳 재전달은 허용).
   - **버튼 2 (`btnNativeOpenDocent`)**: **`현재 정류장 도슨트 열기`** → 즉시 탭 1(`[음성 도슨트]`)로 전환.
   - **버튼 3 (`btnNativeCompleteStop`)**: **`현재 정류장 방문 완료`** → 첫 번째 미방문 정류장을 `store.setVisited(id, true)` 처리하고 다음 미방문 정류장으로 타깃을 넘김.

#### B. 하단 임베디드 `WebView` 및 보안/브리지 계약
1. **`WebSettings` 하드닝**:
   - `javaScriptEnabled = true`
   - `domStorageEnabled = true` (`sessionStorage['cabin-driver-session']` 사용 필수)
   - `allowFileAccess = false`, `allowContentAccess = false`, `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`
   - `mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW`
   - `setSupportMultipleWindows(false)`
2. **`WebViewClient` 호스트 허용목록 (Allowlist)**:
   - **최초 문서(Main Frame) 네비게이션**: 오직 `http://127.0.0.1:8787/vehicle` (및 해시 포함)만 허용한다. 그 외 URL로의 최상위 프레임 이동은 `shouldOverrideUrlLoading`에서 차단한다.
   - **하위 리소스(Subresource) 요청**:
     - 로컬 에셋/API: `http://127.0.0.1:8787/*`
     - 지도 타일(HTTPS): `https://tile.openstreetmap.org/*`, `https://a.tile.openstreetmap.org/*`, `https://b.tile.openstreetmap.org/*`, `https://c.tile.openstreetmap.org/*`
     - 장소 검색(HTTPS): `https://photon.komoot.io/*`
     - 도로 경로(HTTPS): `https://routing.openstreetmap.de/*`
     - 위 허용목록 외의 외부 요청은 `shouldInterceptRequest`에서 빈 `WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", ...)`로 차단한다.
3. **`@JavascriptInterface` (`window.CabinVehicle`) 계약**:
   `phone.js`의 `bind()` 함수는 이미 `window.CabinVehicle && typeof window.CabinVehicle.requestNavigation === 'function'`을 검사하여 `#requestNavi` 버튼을 활성화하도록 구현되어 있다. 이에 맞춰 아래 인터페이스를 등록한다.
   ```java
   public final class CabinVehicleJsBridge {
       @JavascriptInterface
       public String requestNavigation() {
           // UI 스레드/서비스를 통해 requestConfirmedNavigation() 실행 후
           // 사용자에게 표시할 한국어 결과 메시지 반환
       }

       @JavascriptInterface
       public boolean isParked() {
           return sessionService != null && sessionService.isParked();
       }

       @JavascriptInterface
       public void openDocentTab() {
           mainHandler.post(() -> selectTab(TAB_DOCENT));
       }
   }
   ```
   - `webView.addJavascriptInterface(new CabinVehicleJsBridge(), "CabinVehicle")`는 반드시 첫 `loadUrl("http://127.0.0.1:8787/vehicle#" + driverToken)` 호출 **이전에** 등록한다.

---

### 5.3 탭 1: `[음성 도슨트]` (주행 안전 오디오 허브 & 주차 중 루루 그림책 뷰)

1. **상단 현재 정류장 히어로 카드 (`#16324F` 배경, `#FFFFFF` 텍스트)**:
   - 현재 타깃 정류장 순번·이름·카테고리·남은 거리(예: `280m 접근 중` 또는 `도착 반경 내`), 오디오 재생 상태(`재생 중 · Pleos 온디바이스 한국어 TTS`, `일시정지`, `대기 중`) 표시.
   - 경유지가 비어 있거나 모두 방문 완료된 경우: **"예정된 다음 정류장이 없습니다. 경로 플래너에서 경유지를 추가하거나 여정을 완료해 주세요."** 안내 표시.
2. **도슨트 보유 여부에 따른 분기 (`hasDocent` 검증)**:
   - **`hasDocent == true` (경주 4대 명소 등 해설 보유 장소)**:
     - 모드 토글(높이 `64dp`): **`[성인 역사·문화 해설]`** vs **`[아이 눈높이 동화 (루루)]`**
     - 오디오 액션 바(높이 `72dp`):
       - **`[지금 해설 듣기]`** (`accent.primary` `#245AE8`)
       - **`[음성 중지]`**
       - **`[방문 완료 · 다음 장소로]`** (`semantic.success` `#1B7F59`)
     - **주행 중(Driving) 하단 영역**: 긴 텍스트 본문을 숨기고, 동승자와 나눌 수 있는 **한 줄 대화 질문 배너**(`adultPrompt` 또는 `kidsPrompt`, `20sp` 굵은 글씨)만 큼직하게 표시한다.
     - **주차 중(Parked) 하단 영역**: 성인/아이 해설 전문 카드와 함께, 아이 동화 모드 선택 시 **루루(Lulu) 일러스트 이미지(`assets/lulu/*.png` 또는 `.svg`) + 3페이지 동화 카드(`kidsPages` 1/3, 2/3, 3/3 이전·다음 버튼)** 프리뷰를 표시한다.
   - **`hasDocent == false` (검색/직접 추가한 일반 장소)**:
     - 경고 배지(`#FEF3C7` 배경, `#C76A19` 텍스트): **"등록된 도슨트 해설 없음"**
     - 안내 문구: *"이 장소는 검색 또는 직접 추가로 등록된 경유지입니다. 내비게이션 길안내와 방문 시각 기록은 정상 동작하며, 음성 해설은 등록된 명소에서만 재생됩니다."*
     - `[지금 해설 듣기]` 버튼은 비활성화(`enabled = false`, 투명도 `0.45f`)하고, `[방문 완료 · 다음 장소로]` 및 `[이 경유지 내비 전달]` 버튼만 활성화한다.

---

### 5.4 탭 2: `[가족 연결]` (초대 링크 · 참여 가족 권한 관리 · 공유 종료)

- **주행 중 보호**: 주행 중(D/R/N)에는 QR/URL 상세 설정과 멤버 권한 변경 목록을 숨기고, *"주행 중에는 가족 연결 설정이 잠깁니다. 동승자 휴대폰에서는 기존 연결로 계속 경로를 제안할 수 있습니다."* 안내 카드만 표시한다.
- **주차(P단) 중 구성**:
  1. **초대 주소 카드**:
     - 접속 URL: `http://<차량_Wi-Fi/핫스팟_IP>:8787/phone#<invitationToken>` (대형 코드 텍스트 `22sp` Bold + `[초대 링크 새로고침]` 버튼).
     - **생명주기 안내 문구 (업데이트 필수)**: 기존의 *"앱이 화면에 열려 있는 동안에만 서버가 동작합니다"* 문구를 제거하고, **"차량 공유 서비스가 실행 중이므로 Pleos 내비게이션 화면으로 전환해도 가족 휴대폰 연결이 계속 유지됩니다."**로 명확히 표시한다.
  2. **참여 가족 멤버 목록 및 권한 제어**:
     - 각 멤버별 현재 역할(`viewer`, `proposer`, `editor`, `revoked`) 표시 및 즉시 변경 버튼 제공.
  3. **명시적 세션 종료 카드**:
     - **`[가족 공유 종료]`** 버튼(`#A3312B`) 클릭 시 인라인 확인 바(*"가족 기기와의 실시간 공유 웹서버(:8787)와 백그라운드 세션을 종료할까요? 저장된 여행 경로와 방문 기록은 삭제되지 않습니다."*)를 띄우고, `[확인: 공유 종료]` 선택 시 `CabinSessionService.stopSessionAndExit()`를 호출한다.

---

### 5.5 탭 3: `[연동 진단]` (Pleos SDK 실측 상태 및 에뮬레이터 데모 분리)

- **원칙**: 실제 Pleos SDK 연결 상태와 에뮬레이터 시연용 가상 입력을 섞어서 표시하지 않는다.
- **카드 1 — Pleos SDK 실측 상태**:
  - `Vehicle SDK`: 실제 `Vehicle.initialize` 성공 여부, 마지막 수신 기어 값, 마지막 기어 타임스탬프 경과 시간(`Ns 전`), 속도(`km/h`).
  - `NaviHelper SDK (v3.0.5)`: 실제 `NaviHelper.initialize` 성공 여부, 마지막 전달 방식(`단일 목적지` vs `경유지 포함 N곳`), 목적지 좌표 검증 결과, 마지막 에러 코드.
  - `TextToSpeech SDK`: Pleos 온디바이스 한국어 TTS 연결 여부 vs Android TTS 폴백 여부, AudioFocus 상태.
- **카드 2 — 에뮬레이터 전용 시연 컨트롤 (`isEmulator() == true`일 때만 활성)**:
  - `[에뮬레이터 P단/D단 토글 (demoParked)]`: 실제 차량 기어 신호가 없을 때만 동작하며, 실차 신호가 들어오면 즉시 해제됨을 명시.
  - `[현재 정류장 180m 접근 시뮬레이션 (demoApproach)]`: 첫 번째 미방문 정류장 도착 트리거 및 도슨트 자동 안내 테스트용.

---

## 6. `VehicleBridge` 다중 경유지 내비게이션 연동 및 SDK 인터페이스 명세

### 6.1 Pleos SDK `NaviHelper 3.0.5` 바이트코드 검증 시그니처
로컬 SDK AAR(`app/libs/pleos-navi-sdk-release.aar`)의 `javap` 역컴파일로 확인된 실제 시그니처는 다음과 같다.

```java
// 1. 최종 목적지 정보 (주의: 첫 번째 인자가 latitude, 두 번째 인자가 longitude)
public final class ai.pleos.playground.navi.api.model.RouteInfo {
    public RouteInfo(
        double latitude,
        double longitude,
        Double displayLatitude,
        Double displayLongitude,
        String poiName,
        String poiId,
        String address,
        String poiSubId,
        RouteOption routeOption,
        String poiProvider,
        boolean isMobileRequest
    );
}

// 2. 경유지 인덱스 Enum (최대 2개의 중간 경유지 지원: FIRST, SECOND)
public enum ai.pleos.playground.navi.constant.WaypointIndex {
    FIRST, SECOND, ALL
}

// 3. 중간 경유지 요청 모델
public final class ai.pleos.playground.navi.api.model.RequestWaypointInfo {
    public RequestWaypointInfo(
        double latitude,
        double longitude,
        String poiName,
        WaypointIndex waypointIndex,
        String poiId,
        String address,
        String poiSubId,
        String poiProvider
    );
}

// 4. 경유지 포함 경로 요청 래퍼
public final class ai.pleos.playground.navi.api.model.RouteWithWaypointsRequest {
    public RouteWithWaypointsRequest(
        RouteInfo destination,
        java.util.List<RequestWaypointInfo> waypoints
    );
}

// 5. NaviHelper 호출 메서드
public final class ai.pleos.playground.navi.NaviHelper {
    public void requestRoute(RouteInfo routeInfo);
    public void requestRouteWithWaypoints(RouteWithWaypointsRequest request);
}
```

### 6.2 미방문 경유지 슬라이싱(1~3곳) 및 호출 알고리즘

운전자가 내비 전달을 요청하면 `TripStore.unvisitedStops()`에서 아직 방문 완료되지 않은(`visited == false`) 경유지 목록 `remaining`을 순서대로 가져온다.

| 미방문 경유지 수 (`remaining.size()`) | 슬라이싱 대상 (`batch`) | Pleos `NaviHelper` 호출 방식 | 도슨트 타깃 (`target`) vs 내비 검증 목적지 (`requestedDestination`) |
|---|---|---|---|
| **0곳** | 없음 | 호출하지 않고 즉시 `"안내할 미방문 경유지가 없습니다."` 반환 | `target = null`, `requestedDestination = null` |
| **1곳** (`[S0]`) | `[S0]` (1곳) | `naviHelper.requestRoute(toRouteInfo(S0))` | `target = S0`, `requestedDestination = S0` |
| **2곳** (`[S0, S1]`) | `[S0, S1]` (2곳) | `waypoints = [toWaypoint(S0, WaypointIndex.FIRST)]`<br>`destination = toRouteInfo(S1)`<br>`naviHelper.requestRouteWithWaypoints(new RouteWithWaypointsRequest(destination, waypoints))` | `target = S0` (첫 방문지),<br>`requestedDestination = S1` (내비 최종 목적지) |
| **3곳** (`[S0, S1, S2]`) | `[S0, S1, S2]` (3곳) | `waypoints = [toWaypoint(S0, FIRST), toWaypoint(S1, SECOND)]`<br>`destination = toRouteInfo(S2)`<br>`naviHelper.requestRouteWithWaypoints(new RouteWithWaypointsRequest(destination, waypoints))` | `target = S0` (첫 방문지),<br>`requestedDestination = S2` (내비 최종 목적지) |
| **4곳 이상** (`[S0, S1, S2, ... Sn]`) | 앞 3곳 `[S0, S1, S2]` | 3곳과 동일하게 `FIRST(S0)`, `SECOND(S1)`, `destination(S2)` 전달 + **분할 안내 문구 필수 포함** | `target = S0`,<br>`requestedDestination = S2` |

#### 4곳 이상일 때의 명시적 안내 문구 규격
- `remaining.size() > 3`일 때 반환 메시지 및 상단 상태 바 텍스트:
  - **"전체 N곳 중 다음 3곳(S0 → S1 → S2)을 Pleos 내비게이션에 전달했습니다. 이후 구간(N-3곳)은 경유지 방문 후 다시 요청합니다."**
- 이를 통해 운전자가 왜 20개 전체가 한 번에 들어가지 않았는지 명확히 인지하고, 3곳 주행 중/후 상단 바의 `[다음 경유지 내비 전달]` 버튼 한 번으로 나머지 구간을 이어서 전달할 수 있게 한다.

### 6.3 `target`과 `requestedDestination` 분리 및 콜백 검증 로직
기존 `VehicleBridge`의 가장 큰 버그였던 단일 `target` 공유 문제를 해결하기 위해 필드를 두 개로 분리한다.

1. **`private JSONObject target` (다음 방문할 첫 번째 미방문 정류장 = 도슨트 & 근접 반경 타깃)**:
   - 항상 `remaining.get(0)`을 가리킨다.
   - `setTarget(@Nullable JSONObject place)`는 `place == null`일 때 즉시 `this.target = null; this.distanceMeters = -1; this.approaching = false;`로 안전하게 초기화하고 리턴하여 Null 역참조를 원천 차단한다.
   - 차량 GPS/위치 업데이트 또는 `onWayPointArrived` / `onDestinationArrived` 수신 시 `target`과의 거리가 **250m 이내**이면 도착/접근 트리거(`onStopApproaching(target)`)를 발생시킨다.
2. **`private JSONObject requestedDestination` (내비게이션에 전달한 이번 배치의 마지막 정류장)**:
   - `batch.get(batch.size() - 1)`을 가리킨다.
   - `onDestinationInfo(DestinationInfo info)` 콜백에서는 `target`이 아니라 **`requestedDestination`의 위경도와 `info.getLatitude()`, `info.getLongitude()` 사이의 거리가 200m 이내인지** 검증한다.
   - 검증 성공 시:
     - `routeConfirmed = true`, `awaitingDestinationInfo = false`, `confirmedRevision = pendingRevision`
     - `batch.size() == 1`이면: `"Pleos 내비 목적지 확인: " + requestedDestination.optString("title")`
     - `batch.size() > 1`이면: `String.format("Pleos 내비 경유지 %d곳 + 목적지(%s) 확인 완료", batch.size() - 1, requestedDestination.optString("title"))`
3. **`onWayPointArrived(WaypointArrivedInfo)` 연동**:
   - 중간 경유지 도착 콜백 수신 시 현재 `target`(첫 번째 미방문 정류장)의 방문 완료 및 도슨트 안내 트리거(`onStopApproaching(target)`)를 실행하고, 다음 미방문 정류장으로 `target`을 갱신한다.
4. **12초 타임아웃 및 에뮬레이터 가드**:
   - 내비 요청 후 12초(`routeTimeout`) 안에 `onDestinationInfo` 확인이 오지 않으면 `awaitingDestinationInfo = false`, `routeConfirmed = false`로 전환하고 *"Pleos 내비게이션 응답 시간 초과(12초) — 내비 앱 실행 상태를 확인 후 다시 시도해 주세요."*를 표시한다.
   - 에뮬레이터 데모 폴백(`!naviReady && isEmulator()`)은 실제 SDK 초기화가 불가능한 에뮬레이터 환경에서만 동작하며, 이때도 상태 문구에 **`[에뮬레이터 시뮬레이션] 내비 전달됨 (실제 SDK 미연결)`**이라고 정직하게 표기한다.

---

## 7. `gemini-4-hydrogen` 구현 체크리스트 및 수용 기준 (Acceptance Criteria)

후속 구현 세션(`gemini-4-hydrogen`)은 아래 파일별 수정 명세와 수용 기준을 순서대로 이행하고 검증해야 한다.

### 7.1 파일별 구현 작업 목록
| 순번 | 대상 파일 | 구분 | 핵심 구현 내용 |
|---|---|---|---|
| 1 | `app/src/main/res/xml/network_security_config.xml` | 신규 | `127.0.0.1`, `localhost`만 cleartext 허용, 기본 cleartext 차단 |
| 2 | `app/src/main/AndroidManifest.xml` | 수정 | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` 권한 추가, `networkSecurityConfig` 연결, `CabinSessionService` 등록 |
| 3 | `app/src/main/java/kr/cabinstory/VehicleBridge.java` | 수정 | `setTarget(@Nullable JSONObject)` null 가드 추가; `requestRoute(List<JSONObject> destinations, int totalRemainingCount, int revision)` 다중 경유지(1~3곳) 슬라이싱 및 `NaviHelper.requestRouteWithWaypoints` 호출 구현; `target`(도슨트용 첫 경유지)과 `requestedDestination`(내비 검증용 마지막 경유지) 분리; `onWayPointArrived` 연동; 변경 감지용 스냅샷 비교 헬퍼 추가 |
| 4 | `app/src/main/java/kr/cabinstory/CabinSessionService.java` | 신규 | 포그라운드 서비스 구현(`START_NOT_STICKY`, 즉시 알림 표시 + `[공유 종료]` 액션); `TripStore`, `FamilyServer`, `VehicleBridge`, `DocentPlayer` 소유권 이관; 로컬 `Binder` 및 디바운싱된 `SessionListener` 콜백 제공; 다중 경유지 내비 전달 및 도슨트 자동 재생 오케스트레이션 |
| 5 | `app/src/main/java/kr/cabinstory/MainActivity.java` | 전면 개편 | `CabinSessionService` 바인딩 기반 4탭 좌측 Navigation Rail(`경로 플래너`, `음성 도슨트`, `가족 연결`, `연동 진단`) 구현; 탭 0에 상단 네이티브 안전 액션 바 + 하단 단일 인스턴스 `WebView`(`127.0.0.1:8787/vehicle#<driverToken>`, `@JavascriptInterface CabinVehicle`, 호스트 허용목록, 주행 잠금 오버레이) 탑재; 탭 1에 `hasDocent` 정직성 처리 및 주차 시 루루 그림책 프리뷰 탑재; 탭 2에 `[가족 공유 종료]` 확인 플로우 추가; R4 Material 컬러 토큰 전면 적용 |

### 7.2 필수 검증 시나리오 (Acceptance Criteria)
1. **빌드 및 정적 검증**:
   - `./gradlew assembleDebug`가 경고/오류 없이 성공해야 한다.
2. **포그라운드 서비스 & 내비 전환 생존성**:
   - 앱 실행 후 홈 화면이나 Pleos 내비게이션으로 전환해 `MainActivity.onStop()`이 호출되어도 `http://127.0.0.1:8787/phone` 및 `/vehicle` 응답이 유지되고, 상단 알림의 `[공유 종료]` 또는 `[가족 연결]` 탭의 `[가족 공유 종료]`를 눌렀을 때만 포트 `8787`이 닫히며 `trip-plan.json` 기록은 보존되어야 한다.
3. **차량 `/vehicle` WebView 및 2초 기어 폴링 안정성**:
   - 탭 0(`[경로 플래너]`)에서 Leaflet 지도와 경주 기본 경로 폴리라인이 정상 렌더링되고, 2초 기어 폴링 중에도 `WebView`가 리로드되거나 깜빡이지 않아야 한다.
   - 기어가 P단에서 D단으로 바뀌면 즉시 주행 잠금(도슨트 뷰 자동 전환 또는 `WebView` 터치 차단 오버레이)이 작동하고, 다시 P단으로 돌아오면 원래 보던 탭으로 복귀해야 한다.
4. **다중 경유지 내비 전달 (1곳 / 2~3곳 / 4곳 이상)**:
   - 미방문 경유지 1곳일 때 `requestRoute`, 2~3곳일 때 `requestRouteWithWaypoints`(`FIRST`, `SECOND` + `destination`)가 호출되고, `onDestinationInfo`에서 마지막 경유지(`requestedDestination`) 좌표로 정상 검증되어야 한다.
   - 미방문 경유지가 4곳 이상일 때 앞 3곳만 전달되면서 *"전체 N곳 중 다음 3곳. 이후 구간은 다시 요청합니다."* 안내가 표시되어야 한다.
5. **도슨트 정직성 및 빈 일정 방어**:
   - 검색/직접 추가한 일반 장소(`hasDocent == false`)가 현재 타깃일 때 *"등록된 해설 없음"*이 표시되고 가짜 TTS 음성이 재생되지 않아야 한다.
   - 모든 경유지를 삭제하거나 방문 완료하여 미방문 정류장이 0곳일 때 크래시(`NullPointerException`) 없이 빈 상태 안내가 표시되어야 한다.
