# DeepSeek implementation unit U2 — resolved family proposals

Implement only this unit. GPT owns design, tests and review. Do not spawn agents, change model, browse, publish, install dependencies or read private configuration, signing/, caches or logs.

Read app/src/main/java/kr/cabinstory/TripStore.java and its callers in MainActivity.java/FamilyServer.java as needed. Read app/src/androidTest/java/kr/cabinstory/TripStoreTest.java for acceptance. You may edit **only TripStore.java**. Do not edit tests.

Concrete bug: two proposers can suggest the same currently absent place. When the driver accepts one proposal, that place enters the plan, but the other identical proposal remains in the UI and cannot be accepted. Clear **all** proposals for any place now in the plan after a successful `add` or `accept`. Keep failed edits atomic: a rejected/stale/unsaved edit must not discard proposals. Keep permissions, persistence, revisions, and change notifications intact. No new framework, no API changes, no extra features. Smallest complete edit.

Acceptance: testProposalRequiresDriverApproval creates two proposals for museum, accepts one, expects four stops and zero pending proposals. Existing tests stay unchanged by you. Do not run tests; GPT performs QA. Return changed path and concise explanation and actual provider/model. Edit the actual file, not a code-only answer.
