param([switch]$SkipNative, [switch]$UniversalApk)
Import-Module Microsoft.PowerShell.Utility -ErrorAction SilentlyContinue
$ErrorActionPreference = 'Stop'
function Get-Sha256Hex([string]$filePath) {
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($filePath)
    try {
        $hashBytes = $sha256.ComputeHash($stream)
        [System.BitConverter]::ToString($hashBytes).Replace('-', '').ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $sha256.Dispose()
    }
}
$repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location $repoRoot
try {
    if (!$SkipNative) {
        foreach ($abi in @('arm64-v8a', 'x86_64')) {
            & (Join-Path $PSScriptRoot 'build-android-shared-engine.ps1') -Abi $abi
            if ($LASTEXITCODE -ne 0) { throw "Native engine build failed: $abi" }
        }
    }
    & node scripts/check-shared-engine.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Shared engine source/binary verification failed.' }
    & python scripts/check-android-native.py
    if ($LASTEXITCODE -ne 0) { throw 'Android native dependency verification failed.' }
    Push-Location android
    try {
        & .\gradlew.bat "-PuniversalApk=$($UniversalApk.IsPresent.ToString().ToLowerInvariant())" :app:testDebugUnitTest :rebuilt:testDebugUnitTest :rebuilt:lintDebug :rebuilt:assembleDebug :rebuilt:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'Android checks or APK build failed.' }
    } finally { Pop-Location }
    & python scripts/check-android-apk.py
    if ($LASTEXITCODE -ne 0) { throw 'APK runtime or model verification failed.' }
    # Read this build's manifest, never glob stale APKs from previous versions.
    $apkDirectory = Join-Path $repoRoot 'android/rebuilt/build/outputs/apk/debug'
    $outputs = Get-Content (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
    foreach ($element in $outputs.elements) {
        $apkPath = Join-Path $apkDirectory $element.outputFile
        [pscustomobject]@{ Path = $apkPath; Hash = (Get-Sha256Hex $apkPath) }
    }
    Write-Output 'Built the shared-engine Android debug APK. No device has been updated.'
} finally { Pop-Location }
