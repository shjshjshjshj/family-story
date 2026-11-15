# 캐빈 스토리 구현 기준 — 2026-11-14

> 사용자 정정으로 제품 범위/우선순위가 `planner-rebaseline.md`로 대체되었습니다. 아래의 오프라인 4곳/단일 여행/여행 기록 제외는 초기 구현 이력입니다. 공모전 강제 조건이 아닙니다.

입력 기획서의 사용자 요구 F1~F5를 제품 방향으로 사용한다. 기획서 내부의 제안·미검증 기술 설명은 확정 규정으로 취급하지 않는다. 기존 starter TTS 시험 앱은 보존하고 별도 Android 앱을 만든다.

## 공식 공모전과 프로젝트 전제 구분

- 공식: Java/Kotlin, API 28+, Gradle 8+, Pleos SDK 사용. 차량특화성·창의성·기술 활용성·사업성 평가.
- 공식: 기획서 필수, 산출물 선택. 저장소 제출 시 소스와 실행 가능한 release APK, README에 환경·실행법·화면 규격 기재. 제출/계정 공개/메일 전송은 이번 구현에 포함하지 않음.
- 프로젝트 전제: 3분 시연, 클라우드 없이 내장 여행 3곳+추가 후보 1곳. 공식 공모전이 오프라인 또는 3분을 강제한다고 주장하지 않음.
- 공식 확인: [개발 환경](https://appdevchallenge.auto-hmg.io/guide/development), [지원·제출](https://appdevchallenge.auto-hmg.io/guide/submission), [심사](https://appdevchallenge.auto-hmg.io/guide/judging-awards), [규정](https://appdevchallenge.auto-hmg.io/guide/rules), [FAQ](https://appdevchallenge.auto-hmg.io/faq).

## GPT 디자인 방향 / 구현 전 결정

차량은 Material의 supporting pane 패턴: 왼쪽 여행/가족 내비게이션, 가운데 다음 장소·최소 오디오 제어, 오른쪽 일정. 주행 중에는 탐색·본문·편집·권한을 차단하고 오디오 제어만 유지. 상태 미수신도 제한 상태. 정차 확인은 실제 차량 신호를 우선하고 데모 정차는 명시적인 별도 모드. 모바일은 단일 열: 여행 요약 → 그림책 → 장면 제어 → 일정/장소 탐색.

8dp 간격 단위, 카드 24dp 여백, 24dp 모서리, 본문 18~22sp, 차량 주 제목 36~48sp(화면 크기에 맞춤), 조작 영역 최소 64dp. 배경 #F6F5EF, 본문 #183E35, 주색 #216451, 보조면 #E7EEE6. 오류/대기는 텍스트와 색을 함께 표시. 휴대폰은 48px 이상 터치·키보드 포커스·상태 aria-live. 긴 글은 휴대폰, 차량에서는 음성.

참고로 실제 열람한 [Android Material canonical layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts)의 supporting pane 배치와 compact 단일 열 원칙을 사용한다. 원본 관광 사진을 복사하지 않고 앱 전용 경주 삽화와 여우 캐릭터를 벡터로 제작한다.

## 구현 범위와 검증

- 공통 여행 ID, 장소 ID, 로컬 영속화, 방문 상태, 일정 추가/삭제/순서 변경.
- Pleos ON_DEVICE 한국어 TTS, 문장 단위 이어듣기(중단 문장은 처음부터), 정지, 오디오 포커스 상실 시 중지.
- 휴대폰 브라우저 동승자 화면을 차량의 로컬 HTTP 서버에서 제공. 외부 계정/클라우드 없음. 초대 토큰, 기기별 세션, 운전자 권한 부여/철회, 버전 충돌 거부. LAN은 암호화되지 않으므로 개인 핫스폿/시연망 전용. 실제 휴대폰 테스트와 PC→에뮬레이터 검증을 구분.
- NaviHelper 연결/이벤트와 FusedLocation 수신을 실제 SDK로 시도. 요청과 성공 콜백 구분, 대응하지 않는 목적지 이벤트 무시. 실제 확인 없는 내비 적용 성공 표시 금지.
- 신규 상태/권한/충돌 로직은 기기 instrumentation으로 검증. APK 빌드, 실제 emulator 설치·TTS 로그·UI 캡처, HTTP 보안·동기화 검증. GPT가 범위·테스트·최종 디자인 검토를 소유.
- 여행 장기 기록, 전국 검색, 생성형 AI, 서버 TTS, 계정·클라우드 동기화는 제외.
