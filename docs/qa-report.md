# Cabin Story QA 기록

> 아래는 초기 0.1 MVP의 검증 이력입니다. 최신 플래너 재구현과 중간/최종 빌드의 인수 상태·해시는 `planner-qa.md`를 기준으로 확인합니다. 아래의 '최종' 표현과 SHA는 당시 빌드만 가리킵니다.

기준일: 2026-11-14(KST). GPT가 테스트 범위·케이스·리뷰를 담당한다. 에뮬레이터 시계와 PC 기준일이 달라 logcat의 시각을 실제 한국 표준시로 해석하지 않는다.

## 확인한 동작

| 항목 | 결과 | 증거 / 한계 |
|---|---|---|
| Android debug·로컬 서명 release 빌드 | 통과 | build-final.log. 모델 전환 후 변경도 빌드에 포함 |
| 도메인·권한·HTTP 12개 instrumentation | 통과 | evidence/instrumentation.txt. U1 종료·U2 중복 제안 사례를 추가한 최종 실행도 통과 |
| U1 DeepSeek 구현 / GPT 인수 | 통과 | 실제 opencode-go/deepseek-v4.1-flash 호출. 서버 종료 세션 정리 수정. evidence/unit-01-tests.txt: HTTP 4개 통과 |
| U2 DeepSeek 구현 / GPT 인수 | 통과 | 여러 가족의 같은 장소 제안은 수락 또는 직접 추가 후 모두 해소. 도메인 8개 및 전체 12개 테스트 통과 |
| U6 DeepSeek 구현 / GPT 인수 | 통과 | source ZIP 생성과 경로 검사. private/generated 경로 제외, release APK·소스·Gradle wrapper 포함. 첫 실행의 .NET 호출 오류는 DeepSeek가 수정한 뒤 재검증 |
| U4 큰 글씨 배치 / GPT 인수 | 통과 | DeepSeek가 fontScale 1.3 이상에서 rail을 192dp로 확장. 1.5 실제 화면에서 '함께 짠 일정' 한 줄 표시·음성 버튼 유지. evidence/vehicle-font-150-final.png. 검사 뒤 원래 글씨 설정으로 복구 |
| 한국어 ON_DEVICE TTS | 통과 | evidence/app-runtime.log, 3문단 onDone. 일시정지·재시작 화면 확인. 청취 품질의 사람 평가는 별도 |
| Vehicle P단 | 통과 | SDK GEAR_PARK 실제 수신. evidence/vehicle-sdk-diagnostics.png |
| FusedLocation 위치 콜백 | 통과 | 권한 허용 뒤 SDK 좌표 수신. 에뮬레이터 기본 모의 위치이며 실차 GPS가 아님 |
| NaviHelper 정보 콜백·목적지 요청 | 부분 통과 | 내비 정보 수신, requestToNaviProvider(RequestRoute) 전송 확인. evidence/navigation-probe.txt. 요청 후 내비 앱으로 전환. 경로 탐색 완료·실제 도착은 미검증 |
| 로컬 편집 → 차량 실제 UI 반영 | 통과 | Python HTTP 클라이언트 + 차량 UI로 권한 부여. evidence/device-smoke.txt, vehicle-synced-plan.png |
| 운전자 확인·권한 철회 | 통과 | 차량에서 확인 후 버전 일치, 철회 토큰 HTTP 403 |
| 주행 제한 | 모의 시연 통과 | 앱 내부 모의 주행. 편집·권한 disabled, 삽화 제거 확인. 실제 주행 이벤트를 변경한 시험은 아님 |
| Edge 그림책·일반 원고·검색·편집 | 통과 | 실제 Edge 확장 연결로 검사. 장면 1→2→3, 일반 3문단, 검색, viewer/editor 권한, 박물관 추가. 콘솔 오류 없음 |
| 좁은 모바일 배치 | 통과 | 실제 DOM viewport 360px, scrollWidth 360px. evidence/phone-release-360.jpg. 장면 live region·탭 전환 시 scrollY 0 확인 |
| 넓은 모바일 배치 | 통과 | evidence/phone-release-wide.jpg. 실제 DOM width/scrollWidth 800px. contain 적용 후 장소·캐릭터 전체 표시 확인 |
| 연결 중단 | 통과 | 내비 앱으로 전환해 서버가 닫힐 때 마지막 일정 표시·편집 차단. 차량 복귀 후 만료 세션에 재연결 안내 |
| release APK 서명·실행 | 통과 | apksigner v2 검증. debug 앱을 제거한 뒤 release 설치. evidence/release-smoke.txt의 7개 실동작 검사 통과 |

## GPT 디자인 리뷰

차량의 다음 장소·음성 제어·일정의 위계가 설계와 맞고, Material supporting pane과 8dp 간격 기반의 카드·버튼을 유지한다. 정차 화면과 모의 주행 화면의 기능 제한을 실제 UI에서 확인했다. 정상·제한·연결 끊김·승인 대기를 텍스트와 조작 상태로 구분한다. 모바일 360px에서 수평 넘침이 없으며 본문은 한국어 단어를 우선 유지한다. 접근성 포커스, 48px 모바일 조작, 이미지 대체 텍스트, 장면 갱신 live region을 코드로 확인했다. 전체 TalkBack 및 운전자 주의 분산에 대한 인적 평가는 수행하지 않았다.

## 공모전 조건 확인

공식 개발 환경·지원 제출·규정·심사 페이지를 직접 열람했다. Java/API28+/Gradle8+ 기반이며 Pleos TTS·Vehicle·NaviHelper·FusedLocation을 사용한다. 미확인 기능을 성공으로 포장하지 않는다. 제출물 위치와 실행법을 README에 포함한다. 필수 접수양식·동의·서명과 실제 제출은 이번 작업에서 수행하지 않는다. 공식 자료 링크는 implementation-plan.md에 모았다.

## 남은 검증과 범위 한계

- 실물 휴대폰 ↔ 차량/에뮬레이터의 Wi-Fi 연결과 연결 복구, 실차, API28 단말은 미검증.
- 실제 위치를 경주로 이동시킨 접근/도착 자동 재생과 실제 내비 길 안내의 오디오 포커스 충돌은 미검증. 수신·요청과 기능 완료를 구분해야 한다.
- 동승자 화면은 브라우저이며 별도의 휴대폰 네이티브 APK가 아니다. 검색은 내장 장소 4곳. 어린이 음성의 개인 기기 재생, 계정·클라우드 동기화, 모든 경유지의 일괄 내비 적용은 구현하지 않았다.
- HTTP는 신뢰할 수 있는 개인 시연망 전용. 출시 전 TLS·인증·보호자/개인정보 정책이 필요하다.
- 기존 코드는 추가 모델 지시 전 GPT가 구현했다. 이후 변경은 docs/module-design.md에 따라 DeepSeek 구현 → GPT 인수로 전환했다.

## 최종 상태

오프라인 MVP는 빌드·설치·음성·로컬 공유·권한·휴대폰 화면 기준으로 동작을 확인했다. 공모전 접수 완료나 실차 출시 준비 완료를 의미하지 않는다. 최종 큰 글씨 보완(U4)과 산출물 파일 목록은 아래 완료 기록에 남긴다.

### 완료 기록

- 최종 release 재빌드·서명 확인·동일 서명 업데이트 설치 성공. 앱 패키지는 `kr.cabinstory`, 버전 `0.1.0`, DEBUGGABLE 플래그 없음.
- release APK SHA-256: `D90289C45BCC2EE66AAFF40CB8696F72E5AA9ADA9203840FFF87B962229C64E1`.
- lint: 오류 0, 경고 4. 동기 저장의 commit, 고정 target35, 차량 가로 화면, API28~30 backup 관련 정적 경고. 저장 실패 감지가 필요해 핵심 저장은 commit을 유지하고, backup은 allowBackup=false로 차단한다.
- source ZIP: `outputs/CabinStory-source-release.zip`. 앱 소스·테스트·문서·Gradle wrapper·release APK만 허용 목록으로 포함. 키·SDK jar·빌드 캐시·실행 로그·evidence 제외. 로컬 QA evidence는 현재 작업 폴더에 별도 보관.
- 모델 전환 후 실제 코드 수정: U1 가족 서버 수명, U2 중복 제안 해소, U6 소스 패키징, U4 큰 글씨 배치. 모든 유닛은 `opencode-go/deepseek-v4.1-flash`를 로컬 모델 경로로 실행하고 GPT가 인수했다. 직접 OpenCode 경로는 인증 오류였으며 최종 구현에는 성공한 로컬 경로를 사용했다.
