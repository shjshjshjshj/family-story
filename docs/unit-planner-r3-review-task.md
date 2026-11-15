# R3 GPT review follow-up (dispatch only after original R3 is done)

DeepSeek implements product edits in phone.html/css/js/map.js only. GPT owns tests/review. No browser/emulator/build/stubs. Preserve public API and accepted domain/server. Ignore old generatedconceptimages. Vendor library is already provided and integrity-verified in app/src/main/assets/vendor; do not scan unrelated drives or download fallback code.

Map helper findings to fix:
- `point(value)` currently recognizes lat/lon only while domain DTO uses latitude/longitude. Normalize both consistently for origin and stops, preserve legal coordinate guards. Do not silently drop canonical origin marker.
- Road line signature must include tripId and the actual route geometry (or equally reliable changed-geometry key). Current length+first/last+routeRevision misses a new road shape with same endpoints and pointcount. Correctly update new route and record switches.
- Fit signature currently uses only pins, so a newly loaded road detouring outside pin bounds may not be visible. Fit once when pins OR actual geometry changes; never refit every poll, preserve manual pan/zoom between unchanged refreshes.
- Map interactive marker targets need48px (visual badge canremain34), native64px; set icon size/anchor and CSS coherently. Override Leaflet zoom controls to48px/mobile and64/native, don't rely on30px library defaults. Keep attribution visible.

If native window.confirm/prompt was used for new/switch/finish/remove/rename, replace these with small accessible HTML dialog/inline form using actual labeled buttons, Escape/cancel handling and stable focus. Android native WebView must work without depending on WebChromeClient JavaScript prompt handlers, and browser QA must not deadlock on native dialogs. One reusable confirmation dialog and existing newTrip title form are enough; no newframework. Honor all server safety/phase/approval guards.

Other GPT findings will be appended before dispatch. Do not preemptively redesign unrelated behavior. Summarize changes only.

Functional findings from phone.js (higher priority than cosmetics):
1. Poll loop starts only when a token exists at initial page load. A fresh visitor joining successfully gets one refresh but never starts polling. Ensure exactly one loop runs, including after first join/rejoin; refresh withouttoken can safelyreturn withoutnetwork. Do not start duplicate loops on repeated join.
2. renderHeader disables newOpen/openTrip/savedSelect on completed trips because editable excludescompleted. This traps users on their completed trip. Separate authority to create/switch trips (driver/editor, notactive) from editing current completed plan. Completed remains read-only but cancreate/switch. Include busy and parkeddriverguards coherently. Hide/disable completed-plan add/propose/edit/preview actions; native drivernotparked also cannotedit (serverstillfinal).
3. Preserve read-only browsing on transient disconnect: current recordCard uses mutation button factory and disables opening already-loadedrecords when offline. Local record/docent browsing shouldwork withoutAPI; newedits/search/networkpreview disabled.
4. Candidate closes immediately after starting an async edit, evenwhen409/validation/network failure occurs. Await submitEdit result and clearcandidate onlyonsuccess; keep name/coordinates andfocusavailableonfailure.
5. runSearch has no ordering guard: rapid requests can let older results overwrite newer query. Use request generation/token andloadingstate; ignore responses forsuperseded query orswitchedtrip. Keep editsunblockedasappropriate. Likewise ifroutebusy whilean already-previewed planchanges, retain onepending latest recalculation andrunafteroldrequest settles; current800ms timer drops updates while routeBusy. No429 retryloop; publicrate policyretained.
6. Record extra visits currently always say '최종 일정에 없음' whenever absentfrom the currentlydisplayedplan. This is false forplacesaddedafterstartbutpresentinfinal. Compare actualfinal/currentmembership andlabel '여행 중 추가' vs '최종 일정에서 제외' correctly. Null endtime oncompleted means '시각 기록 없음', not '진행 중'.

Markup/layout findings from the delivered HTML/CSS:
- At desktop grid, '여행 일정'/roleBadge and driverNotice are in the left column below map while their plancard is on the right. Move heading/notice into the top of plancard and simplify grid areas so map and its summary remain left, itinerary heading/list/actions right; mobile retains map→summary→itinerary order.
- Update session-lifetime copy for the upcoming persistent native sharing service: shared session ends when '공유 종료'/service ends, not simply when Activity leavesforeground. Describe saved plans/records as stored on vehicle (phone page is not durable offline storage). Reconnect help checks whether vehicle sharing is active, not 'keep appforeground' as a permanentrequirement.
- Searchcopy can simply say '장소 검색은 인터넷 연결이 필요해요.' Do not narrate internal request/autocomplete implementation in productflow. Provider/rate details belong docs/attribution.
- In /vehicle, brand href must remain /vehicle (driver session), not /phone. Hide duplicate web header and docent/family bottom destinations for vehicle mode; keep two web destinations 경로/기록 under the native4-item rail, with equal navcolumns. Phone retains4destinations.
- Picking a map point needs a visible temporary candidate marker. Clear on cancel/add/origin success. Keep existingroute pins/line unchanged until confirmed; a popup/inline labeled form retains keyboard equivalent.
