param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (Test-Path -LiteralPath 'C:\Program Files\Java\jdk-19\bin\java.exe') { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-19' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'JDK 17~23의 JAVA_HOME을 지정해 주세요.' }
New-Item -ItemType Directory -Path signing,apk,evidence -Force | Out-Null
$passwordFile = Join-Path $PSScriptRoot 'signing\password.txt'
if (-not (Test-Path -LiteralPath $passwordFile)) {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($bytes); $random.Dispose()
    [Convert]::ToBase64String($bytes) | Set-Content -LiteralPath $passwordFile -NoNewline
}
$env:CABIN_KEY_PASSWORD = Get-Content -LiteralPath $passwordFile -Raw
try {
    if (-not (Test-Path -LiteralPath 'signing\cabin-story.jks')) {
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore signing\cabin-story.jks -storepass:env CABIN_KEY_PASSWORD -keypass:env CABIN_KEY_PASSWORD -alias cabin-story -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Cabin Story Local Competition Build'
        if ($LASTEXITCODE -ne 0) { throw '서명 키 생성 실패' }
    }
    $tasks = @(':app:assembleRelease', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug')
    $hasPhone = Test-Path -LiteralPath phone
    if ($hasPhone) { $tasks += ':phone:assembleRelease' }
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw '빌드 실패' }
    Copy-Item -LiteralPath app\build\outputs\apk\release\app-release.apk -Destination apk\FamilyStory_car_release.apk
    if ($hasPhone) { Copy-Item -LiteralPath phone/build/outputs/apk/release/phone-release.apk -Destination apk/FamilyStory_phone_release.apk }
    Copy-Item -LiteralPath app\build\outputs\apk\debug\app-debug.apk -Destination apk\FamilyStory_car_debug.apk
    if ($Test) {
        $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        & $adb install -r apk\FamilyStory_car_debug.apk
        if ($LASTEXITCODE -ne 0) { throw 'Debug 설치 실패: release 앱이 설치돼 있다면 별도 QA 에뮬레이터를 사용하세요.' }
        & $adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
        if ($LASTEXITCODE -ne 0) { throw '테스트 설치 실패' }
        $userId = (& $adb shell am get-current-user).Trim()
        & $adb shell am instrument --user $userId -w -e class kr.cabinstory.TripStoreTest,kr.cabinstory.FamilyServerTest,kr.cabinstory.PlannerStoreTest,kr.cabinstory.MapServiceTest,kr.cabinstory.JourneyTest,kr.cabinstory.StoryKitTest kr.cabinstory.test/android.test.InstrumentationTestRunner | Tee-Object -FilePath evidence\instrumentation.txt
        if (-not (Select-String -LiteralPath evidence\instrumentation.txt -Pattern 'OK \(42 tests\)' -Quiet)) { throw '테스트 실패: evidence\instrumentation.txt 확인' }
    }
    Get-FileHash -LiteralPath apk\FamilyStory_car_release.apk -Algorithm SHA256 | Format-List
} finally { Remove-Item Env:CABIN_KEY_PASSWORD -ErrorAction SilentlyContinue }
