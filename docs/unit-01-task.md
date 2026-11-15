# DeepSeek implementation unit U1 — family server lifetime

You are the implementation model. GPT owns architecture, design, tests and final review. Implement only the concrete unit below. Do not redesign the product, create agents, change provider/model, browse, publish, install dependencies, inspect user folders, or read signing/, .gradle/, build logs, private configuration or secrets.

Workspace: D:/Codex/현대차 공모전/cabin-story
Read only the relevant files: app/src/main/java/kr/cabinstory/FamilyServer.java, TripStore.java, MainActivity.java for caller lifetime, and app/src/androidTest/java/kr/cabinstory/FamilyServerTest.java. You may edit only FamilyServer.java and if strictly necessary its existing disconnectAll caller in MainActivity.java. Do not edit tests; GPT owns them.

The existing code largely works. Fix one concrete race: MainActivity currently clears sessions separately from FamilyServer.close(). An already accepted /api/join request can finish between the clearing and the shutdown, leaving a session in the retained TripStore that may survive when a new server is created. Make server shutdown invalidate its sessions and prevent a late join from repopulating them after shutdown. close() must remain safe if called twice. Do not hold the store lock during socket I/O and do not add a generic lifecycle framework. A small server-local synchronization boundary around join creation and session cleanup is sufficient. Remove the redundant MainActivity cleanup only if needed for a single ownership boundary. Existing request/body/origin/auth checks must be preserved.

Keep the change minimal and limited to this unit. Use your file edit tools, not a code-only answer. Do not run tests or broaden QA. Return exact changed paths and a concise explanation; report your actual provider/model. If no access or edits are possible, report the concrete error. Do not pretend that a successful response means any tests passed.
