# R1 repair — two GPT acceptance findings

Use DeepSeek, same workspace. Allowed product file ONLY app/src/main/java/kr/cabinstory/TripStore.java. No tests or other product edits. GPT has updated tests and owns verification. Do NOT create/run stubs/harnesses or investigate SDK/JDK/cache; GPT has the working Android Gradle build and will run checks. Previous compilation against desktop org.json did not establish Android compatibility.

Fix these two root issues and every affected caller:
1. Actual Android Gradle compilation fails at copy(JSONObject): Android's JSONObject(String) constructor throws checked JSONException. The desktop org.json stub missed it. Catch/propagate JSONException correctly without silently returning null or swallowing errors. Check helpers/callers so real Android compilation can succeed. Keep atomic rollback.
2. Empty new trip starts selected="". add/addCustom/accept currently append first stop but don't set selected, so validate rejects the first added destination. Set selected appropriately when adding the first stop across the shared add/accept case and addCustom. Preserve current selected on subsequent additions.

Review source briefly for these fixes, edit only needed lines, summarize. No build/tests/tools beyond necessary file edit/read. Finish repair only.
