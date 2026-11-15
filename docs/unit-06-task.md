# DeepSeek implementation unit U6 — source package

GPT owns design and QA; implement only the specified artifact-packaging unit. Do not spawn agents, change model, browse, read private configuration/signing material or install dependencies. Do not change app behavior or tests.

You may edit only `.gitignore` and create `package.ps1` at the workspace root. Read README.md and existing build.ps1 only for context; do not edit them. Do not run scripts or QA; GPT verifies them.

Create the smallest standard-PowerShell/.NET source packager, no dependencies or options. The archive output is `outputs/CabinStory-source-release.zip`. It must retain repository-relative paths. Include exact root files `.gitignore`, `README.md`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `build.ps1`, `package.ps1`; recursively include `app/src`, `docs`, `scripts`, `gradle`; and include only the release APK `apk/CabinStory_CabinStory_release.apk`, not debug APKs. Fail clearly if the release APK or required roots are absent. Exclude __pycache__ files. Never enumerate or include signing/, .gradle/, build/, app/build/, sdk-inspect/, config, logs or evidence. Do not delete or move directories. Standard System.IO.Compression ZipArchive or ZipFile is sufficient; preserve Korean filenames. Dispose archive/file handles on failure.

Add ignore entries for `outputs/`, `sdk-inspect/`, `__pycache__/`, `evidence/*.jsonl` to the existing .gitignore, retaining all current entries. These keep extracted proprietary SDK jars, transient model traces, archive output and Python caches out of future source publishing. The explicit ZIP allowlist is the final packaging boundary.

Return changed paths and concise behavior. Do not claim tests passed. Report actual provider/model. No extra framework, config, hooks, broad cleanup or features.
