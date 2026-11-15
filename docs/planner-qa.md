# 협동 여행 플래너 재구현 — GPT 인수 기록

2026-11-14. 상태: 진행 중, 최종 제품 인수 전. 기존 컨셉 이미지는 사용 중지. 새 요구는 planner-rebaseline.md가 기준이다. 제품 코드 DeepSeek, 설계·테스트·검토 GPT.

## R1 여행 도메인/기록

- DeepSeek 실제 실행: opencode-go/deepseek-v4.1-flash, CLI evidence/deepseek-planner-r1.jsonl 및 repair 로그. 초기 구현의 desktop org.json 스텁 체크는 Android 호환 검증으로 인정하지 않았다.
- GPT 검토에서 Android checked JSONException 처리와 빈 여행의 첫 방문지 선택 누락을 발견. 같은 DeepSeek 모델이 TripStore.java만 수정했다.
- 실제 Android debug/release/test APK 및 lint 빌드 통과: evidence/build-planner-r1.log.
- GPT 소유 TripStoreTest 8개 + PlannerStoreTest 7개, 실제 에뮬레이터에서 통과. 새 여행/전환/영속화/원자성/좌표 검증/권한/기록 불변/최초 방문 시각/레거시 시각 미발명/잘못된 경로 버전 거부 확인.
- HTTP 기존 테스트 4개 중 1개는 새로운 editor 방문 권한/여행 시작 전 조건과 충돌. 실행 결과19개 중18개 통과, evidence/planner-tests.txt. GPT가 새 계약에 맞춰 테스트를 수정했으며 서버의 IllegalStateException→HTTP400 처리는 R2에서 인수한다. 이를 전체 통과로 기록하지 않는다.

## 공개 지도/경로 사전 확인

- Photon에서 '첨성대 경주' 공개 샘플 검색2건 수신.
- FOSSGIS OSRM에서 경주 샘플3지점의 도로 경로3,477.7m/530.8초/125좌표/2구간 수신. 좌표의 도로 스냅 거리도 별도로 반환됐다. 이는 현재 앱 화면 구현 완료나 실제 운행 검증이 아니다.
- 증거: evidence/public-map-probe.json. 실제 사용자 위치·여행 기록·토큰을 보내지 않았다.

## 남은 유닛

R2 공개 서비스/HTTP 통합, R3 지도 플래너/기록 UI, R4 차량/세션 수명/내비 경유지 연결, 최종 통합 UI·서명·패키징 인수가 남아 있다. 중간 APK를 완료본으로 안내하지 않는다. 실차·실물 휴대폰 LAN·실제 경로 완료는 아직 미검증이다.

## R2 공개 서비스·HTTP 인수

- DeepSeek MapService.java/FamilyServer.java 구현 및 GPT 검토 후 전송 오류503,2000m 초과 도로 스냅 거부, monotonic 속도/캐시 시계, 완료 여행 재계산 차단, 캐시 저장 실패와 충돌 구분을 같은 모델로 보완했다.
- 실제 Android 빌드/lint 통과(evidence/build-planner-r2.log). GPT 소유 도메인15/HTTP7/지도서비스5, **27개 계측 검사 모두 통과**(evidence/planner-r2-tests.txt). 지도서비스 검사는 공개 서버에 부하를 주지 않는 주입형 응답 fixture를 사용했다.
- 실제 앱의 인증된 HTTP API → Android HTTPS → Photon/OSRM 왕복도 별도로 통과. 공개 샘플검색2건, 도로경로5,292.7m/751.7초/50좌표/4구간 반환. evidence/android-map-live-probe.json. 화면 구현 완료/실제 차량 주행 성공과 구분한다.
- R1에서 남았던 HTTP 상태/편집자 방문 계약 충돌은 수정 후 전체 통과했다. R2 인수 완료. 다음은 R3/R4 및 통합 검증이다.
