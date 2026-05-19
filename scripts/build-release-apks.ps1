param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$viewerTarget = Join-Path $root "legacycam-viewer-release"
$cameraTarget = Join-Path $root "legacycam-camera-release"

New-Item -ItemType Directory -Force -Path $viewerTarget | Out-Null
New-Item -ItemType Directory -Force -Path $cameraTarget | Out-Null

if (-not $SkipBuild) {
    & (Join-Path $root "gradlew.bat") ":viewer-app:assembleRelease" ":camera-app:assembleRelease" "--no-daemon"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle release build gagal."
    }
}

$viewerSource = Join-Path $root "viewer-app\build\outputs\apk\release\viewer-app-release.apk"
$cameraSource = Join-Path $root "camera-app\build\outputs\apk\release\camera-app-release.apk"

if (-not (Test-Path $viewerSource)) {
    throw "Viewer APK tidak ditemukan: $viewerSource"
}

if (-not (Test-Path $cameraSource)) {
    throw "Camera APK tidak ditemukan: $cameraSource"
}

Copy-Item $viewerSource (Join-Path $viewerTarget "viewer-app-release.apk") -Force
Copy-Item $cameraSource (Join-Path $cameraTarget "camera-app-release.apk") -Force

Write-Output "Viewer APK  : $viewerTarget\viewer-app-release.apk"
Write-Output "Camera APK  : $cameraTarget\camera-app-release.apk"
