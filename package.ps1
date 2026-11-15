$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot.TrimEnd('\')
Set-Location -LiteralPath $root

$rootFiles = @('.gitignore','README.md','settings.gradle.kts','build.gradle.kts','gradle.properties','gradlew','gradlew.bat','build.ps1','package.ps1')
$rootFiles += @('app/build.gradle.kts','phone/build.gradle.kts')
$sourceDirs = @('app/src','phone/src','docs','scripts','gradle')
$apk = 'apk/FamilyStory_car_release.apk'
$phoneApk = 'apk/FamilyStory_phone_release.apk'

$required = @($rootFiles) + @($sourceDirs) + @($apk, $phoneApk)
$missing = @($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_)) })
if ($missing.Count -gt 0) { throw "필수 소스 경로가 없습니다: $($missing -join ', ')" }

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = Join-Path $root 'outputs\FamilyStory-source-release.zip'
New-Item -ItemType Directory -Path (Split-Path $zip) -Force | Out-Null
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }

$files = @($rootFiles | ForEach-Object { Get-Item -LiteralPath (Join-Path $root $_) })
$files += @($sourceDirs | ForEach-Object {
    Get-ChildItem -LiteralPath (Join-Path $root $_) -Recurse -File |
        Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' }
})
$files += Get-Item -LiteralPath (Join-Path $root $apk)
$files += Get-Item -LiteralPath (Join-Path $root $phoneApk)

$stream = $null; $archive = $null
try {
    $stream = [IO.File]::Open($zip, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    $archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
    foreach ($file in ($files | Sort-Object FullName -Unique)) {
        $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $relative, [IO.Compression.CompressionLevel]::Optimal)
    }
} catch {
    if ($archive) { $archive.Dispose(); $archive = $null }
    if ($stream) { $stream.Dispose(); $stream = $null }
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    throw
} finally {
    if ($archive) { $archive.Dispose() }
    if ($stream) { $stream.Dispose() }
}

Write-Output "생성: $zip ($($files.Count)개 파일)"
