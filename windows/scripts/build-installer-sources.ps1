# Build application sources without changing the installed/running IME.
[CmdletBinding()]
param([string]$SwiftRuntimeDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'distribution.psm1') -Force
$windowsRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'modern-llama.ps1')
$modernLlama = Initialize-ModernLlama

function Invoke-BuildStep([string]$Directory, [string]$Command, [string[]]$Arguments) {
    Write-Host ("Build: $Command " + ($Arguments -join ' '))
    Push-Location $Directory
    try {
        & $Command @Arguments | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Build failed ($LASTEXITCODE): $Command $Arguments" }
    } finally { Pop-Location }
}

foreach ($command in @('swift.exe', 'cargo.exe', 'npm.cmd', 'node.exe')) {
    Get-Command $command -ErrorAction Stop | Out-Null
}
# Use runtime DLLs from the same Swift SDK, rather than a possibly older deployed runtime.
if (-not $SwiftRuntimeDirectory) {
    if ($env:SDKROOT -match '^(.*)[\\/]Platforms[\\/]([^\\/]+)[\\/]Windows\.platform[\\/]') {
        $SwiftRuntimeDirectory = Join-Path $Matches[1] ('Runtimes\' + $Matches[2] + '\usr\bin')
    } else {
        throw 'Cannot locate matching Swift runtime. Set SDKROOT or pass -SwiftRuntimeDirectory <usr\bin>.'
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $SwiftRuntimeDirectory 'swiftCore.dll') -PathType Leaf)) {
    throw "Swift runtime missing: $SwiftRuntimeDirectory"
}

$swiftRoot = Join-Path $windowsRoot 'server-swift'
Invoke-BuildStep $swiftRoot 'swift.exe' @('build', '--scratch-path', '.build-modern', '-c', 'release', '-Xcc', '-D_CRT_USE_C_COMPLEX_H', '-Xcxx', '-D_CRT_USE_C_COMPLEX_H')
Push-Location $swiftRoot
try {
    $binOutput = & swift.exe build --scratch-path .build-modern -c release --show-bin-path
    if ($LASTEXITCODE -ne 0) { throw 'Cannot locate Swift build output.' }
    $swiftBin = ($binOutput | Select-Object -Last 1).Trim()
} finally { Pop-Location }
$importLibrary = Join-Path $swiftBin 'azookey-server.lib'
if (-not (Test-Path -LiteralPath $importLibrary -PathType Leaf)) { throw "Missing Swift import library: $importLibrary" }
$linkDirectory = Join-Path $windowsRoot 'crates\server\target'
New-Item -ItemType Directory -Path $linkDirectory -Force | Out-Null
Copy-Item -LiteralPath $importLibrary -Destination (Join-Path $linkDirectory 'azookey-server.lib') -Force

Invoke-BuildStep $windowsRoot 'cargo.exe' @('build', '--release', '--locked', '-p', 'azookey-windows', '-p', 'azookey-server', '-p', 'ui', '-p', 'launcher')
Invoke-BuildStep $windowsRoot 'cargo.exe' @('build', '--release', '--locked', '-p', 'azookey-windows', '--target', 'i686-pc-windows-msvc')
$frontendRoot = Join-Path $windowsRoot 'frontend'
Invoke-BuildStep $frontendRoot 'npm.cmd' @('ci', '--no-audit', '--no-fund')
Invoke-BuildStep $frontendRoot 'npm.cmd' @('run', 'tauri:build')
Invoke-BuildStep $windowsRoot 'node.exe' @('scripts/generate-reverse-dictionary.mjs')

$files = [ordered]@{
    'keyboard-prior.exe' = (& (Join-Path $PSScriptRoot 'build-keyboard-prior.ps1'))
    'azookey_windows.dll' = Join-Path $windowsRoot 'target\release\azookey_windows.dll'
    'x86\azookey_windows.dll' = Join-Path $windowsRoot 'target\i686-pc-windows-msvc\release\azookey_windows.dll'
    'azookey-server.exe' = Join-Path $windowsRoot 'target\release\azookey-server.exe'
    'ui.exe' = Join-Path $windowsRoot 'target\release\ui.exe'
    'launcher.exe' = Join-Path $windowsRoot 'target\release\launcher.exe'
    'frontend.exe' = Join-Path $windowsRoot 'target\release\frontend.exe'
    'azookey-server.dll' = Join-Path $swiftBin 'azookey-server.dll'
    'zeroconvert.ico' = Join-Path $windowsRoot 'crates\client\res\zeroconvert.ico'
    'Dictionary\Reverse\reverse-v2.bin' = Join-Path $windowsRoot 'target\azookey-reverse-dictionary\reverse-v2.bin'
}
foreach ($file in Get-ChildItem -LiteralPath $SwiftRuntimeDirectory -Filter '*.dll' -File) { $files[$file.Name] = $file.FullName }
foreach ($name in $files.Keys) {
    if (-not (Test-Path -LiteralPath $files[$name] -PathType Leaf)) { throw "Build output missing: $($files[$name])" }
}
$receiptFiles = @{}
foreach ($name in $files.Keys) {
    $stream = [IO.File]::OpenRead($files[$name])
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { $digest = [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $algorithm.Dispose() }
    $receiptFiles[$name] = @{ path = $files[$name]; sha256 = $digest }
}
$buildInfoPath = Join-Path $windowsRoot 'target/azookey-build/build-info.json'
$installerMetadataPath = Join-Path $windowsRoot 'target/azookey-build/AppVersion.iss'
$receipt = @{
    assetLayout = 3; files = $receiptFiles
    buildInfo = @{ path = $buildInfoPath; sha256 = Get-ArtifactHash $buildInfoPath }
    installerMetadata = @{ path = $installerMetadataPath; sha256 = Get-ArtifactHash $installerMetadataPath }
}
[IO.File]::WriteAllText((Join-Path $windowsRoot 'target\installer-source-build.json'), ($receipt | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
return $files
