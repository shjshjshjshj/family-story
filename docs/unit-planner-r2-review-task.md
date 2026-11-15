# R2 GPT review follow-up

Allowed files MapService.java and FamilyServer.java only. DeepSeek implements, GPT compiles/tests. Do not build, run tests, create stubs or investigate runtime. Preserve auth/origin/lifecycle and API schemas.

Fix concrete review gaps:
1. MapService.Https currently propagates IOException/timeouts/invalid JSON as generic500 via FamilyServer's '변경을 저장하지 못했어요'. Wrap expected transport/JSON provider failures into MapService.ServiceException503 (preserve explicit429), with map-service Korean retry message. No raw upstream URL/body/sensitive data in error responses/logs. Thread interruptions shouldn't continue retries (there are no retries).
2. parseRoute currently checks waypoint count only. Validate every waypoint distance finite/nonnegative; reject excessive snapping >2000m with explanation to choose a vehicle-accessible point. A route must not silently attach to a remote road. Normal monument snap100m accepted. Do not invent geometry. GPT fixture has distance0 waypoints.
3. Gate and cache expiry should use monotonic Android elapsedRealtime (device wall-clock changes must not freeze rate limits or falsely expire cache). computedAt remains wall-clock record metadata.
4. For /api/route reject completed trip before making upstream request; historical record uses stored geometry. cacheRoute false must be409 only if trip/revision changed. If still same current planning/active trip and persist/validation failed, return503 '경로를 저장하지 못했어요' instead of wrong stale-edit message.
5. GET /api/state should authenticate actor before constructing full store.snapshot(), then add role. Keep driver/ordinary actor same contract, no token exposure.

Only minimal root fixes; summarize files, no verification commands. Finish follow-up only.
