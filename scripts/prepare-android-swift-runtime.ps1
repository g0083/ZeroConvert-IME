# Repair missing runtime files in the Windows SDK bundle using the checksummed,
# same-version official Android SDK. Never modify the installed toolchain.
param([string]$SwiftVersion = '6.4.1')
$ErrorActionPreference = 'Stop'
if ($SwiftVersion -ne '6.4.1') { throw 'Review the official SDK URL/checksum before changing Swift versions.' }
$repoRoot = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $repoRoot '.local/toolchains'
New-Item -ItemType Directory -Force $cache | Out-Null
$archive = Join-Path $cache 'swift-6.4.1-RELEASE_android.artifactbundle.tar.gz'
$expected = 'a356b79f8f1daa6ae3a66f4c556966ca20c0208b3659805cf30a83a0a31d6772'
if (!(Test-Path -LiteralPath $archive)) {
    & curl.exe --fail --location --retry 2 --output $archive 'https://download.swift.org/swift-6.4.1-release/android-sdk/swift-6.4.1-RELEASE/swift-6.4.1-RELEASE_android.artifactbundle.tar.gz'
    if ($LASTEXITCODE -ne 0) { throw 'Official Swift Android SDK download failed.' }
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -ne $expected) { throw 'Swift Android SDK checksum mismatch.' }
& python (Join-Path $PSScriptRoot 'extract-android-swift-runtime.py') $archive (Join-Path $repoRoot 'engine/build/runtime-overlay')
if ($LASTEXITCODE -ne 0) { throw 'Swift Android runtime extraction failed.' }
