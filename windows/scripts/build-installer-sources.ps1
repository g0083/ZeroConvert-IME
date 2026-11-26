# Build application sources without changing the installed/running IME.
[CmdletBinding()]
param([string]$SwiftRuntimeDirectory, [switch]$CleanNpm)
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
Enable-FastRustLinker
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
$frontendRoot = Join-Path $windowsRoot 'frontend'
$buildMetaDir = Join-Path $windowsRoot 'target\azookey-build'
$tauriConfigPath = Join-Path $buildMetaDir 'tauri.build.conf.json'
if (-not (Test-Path -LiteralPath $tauriConfigPath -PathType Leaf)) {
    Invoke-BuildStep $windowsRoot 'node.exe' @('scripts/sync-version.mjs')
}

$pkgJsonHash = Get-ArtifactHash (Join-Path $frontendRoot 'package.json')
$pkgLockHash = Get-ArtifactHash (Join-Path $frontendRoot 'package-lock.json')
$npmStampPath = Join-Path $buildMetaDir 'frontend-npm-stamp.json'
$needsNpmCi = $CleanNpm -or -not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules\.package-lock.json') -PathType Leaf)
if (-not $needsNpmCi -and (Test-Path -LiteralPath $npmStampPath -PathType Leaf)) {
    try {
        $stamp = Get-Content -Encoding UTF8 -Raw -LiteralPath $npmStampPath | ConvertFrom-Json
        if ($stamp.packageJsonSha256 -ne $pkgJsonHash -or $stamp.packageLockSha256 -ne $pkgLockHash) {
            $needsNpmCi = $true
        }
    } catch {
        $needsNpmCi = $true
    }
} elseif (-not $needsNpmCi) {
    $needsNpmCi = $true
}

$llamaCpuDir = Join-Path $modernLlama.runtime 'llama_cpu'
$psExe = (Get-Process -Id $PID).Path
$wave1Steps = @(
    @{
        Name = 'swift'
        Directory = $swiftRoot
        Command = 'swift.exe'
        Arguments = @('build', '--scratch-path', '.build-modern', '-c', 'release', '--product', 'azookey-server', '-Xcc', '-D_CRT_USE_C_COMPLEX_H', '-Xcxx', '-D_CRT_USE_C_COMPLEX_H')
    },
    @{
        Name = 'client-x86'
        Directory = $windowsRoot
        Command = 'cargo.exe'
        Arguments = @('build', '--release', '--locked', '-p', 'azookey-windows', '--target', 'i686-pc-windows-msvc')
    },
    @{
        Name = 'keyboard-prior'
        Directory = $windowsRoot
        Command = $psExe
        Arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'build-keyboard-prior.ps1'), '-RuntimeDirectory', $llamaCpuDir)
    },
    $(if ($needsNpmCi) {
        @{
            Name = 'frontend-assets'
            Directory = $frontendRoot
            Command = 'cmd.exe'
            Arguments = @('/d', '/c', 'npm.cmd ci --no-audit --no-fund && npm.cmd run build')
        }
    } else {
        @{
            Name = 'frontend-assets'
            Directory = $frontendRoot
            Command = 'npm.cmd'
            Arguments = @('run', 'build')
        }
    })
)
Invoke-ParallelBuildSteps $wave1Steps

if ($needsNpmCi) {
    $null = [IO.Directory]::CreateDirectory($buildMetaDir)
    Write-ArtifactJson $npmStampPath @{
        packageJsonSha256 = $pkgJsonHash
        packageLockSha256 = $pkgLockHash
    }
}

Push-Location $swiftRoot
try {
    $binOutput = & swift.exe build --scratch-path .build-modern -c release --product azookey-server --show-bin-path
    if ($LASTEXITCODE -ne 0) { throw 'Cannot locate Swift build output.' }
    $swiftBin = ($binOutput | Select-Object -Last 1).Trim()
} finally { Pop-Location }
$importLibrary = Join-Path $swiftBin 'azookey-server.lib'
if (-not (Test-Path -LiteralPath $importLibrary -PathType Leaf)) { throw "Missing Swift import library: $importLibrary" }
$linkDirectory = Join-Path $windowsRoot 'crates\server\target'
$null = Copy-ArtifactIfChanged $importLibrary (Join-Path $linkDirectory 'azookey-server.lib')

Invoke-BuildStep $windowsRoot 'cargo.exe' @('build', '--release', '--locked', '-p', 'azookey-windows', '-p', 'azookey-server', '-p', 'ui', '-p', 'launcher')

$tauriBaseConf = Get-Content -Encoding UTF8 -Raw -LiteralPath $tauriConfigPath | ConvertFrom-Json
$prebuiltConfObj = [ordered]@{
    version = $tauriBaseConf.version
    build = [ordered]@{
        beforeBuildCommand = ''
    }
}
$prebuiltConfPath = Join-Path $buildMetaDir 'tauri.build.prebuilt.conf.json'
$prebuiltConfText = ($prebuiltConfObj | ConvertTo-Json -Depth 6) + "`n"
if (-not (Test-Path -LiteralPath $prebuiltConfPath -PathType Leaf) -or
    ([IO.File]::ReadAllText($prebuiltConfPath) -ne $prebuiltConfText)) {
    [IO.File]::WriteAllText($prebuiltConfPath, $prebuiltConfText, (New-Object Text.UTF8Encoding($false)))
}
$tauriCmd = Join-Path $frontendRoot 'node_modules\.bin\tauri.cmd'
Invoke-BuildStep $frontendRoot $tauriCmd @('build', '--no-bundle', '--config', '../target/azookey-build/tauri.build.prebuilt.conf.json')

$files = [ordered]@{
    'keyboard-prior.exe' = (& (Join-Path $PSScriptRoot 'build-keyboard-prior.ps1') -RuntimeDirectory $llamaCpuDir)
    'azookey_windows.dll' = Join-Path $windowsRoot 'target\release\azookey_windows.dll'
    'x86\azookey_windows.dll' = Join-Path $windowsRoot 'target\i686-pc-windows-msvc\release\azookey_windows.dll'
    'azookey-server.exe' = Join-Path $windowsRoot 'target\release\azookey-server.exe'
    'ui.exe' = Join-Path $windowsRoot 'target\release\ui.exe'
    'launcher.exe' = Join-Path $windowsRoot 'target\release\launcher.exe'
    'frontend.exe' = Join-Path $windowsRoot 'target\release\frontend.exe'
    'azookey-server.dll' = Join-Path $swiftBin 'azookey-server.dll'
    'zeroconvert.ico' = Join-Path $windowsRoot 'crates\client\res\zeroconvert.ico'
}
foreach ($file in Get-ChildItem -LiteralPath $SwiftRuntimeDirectory -Filter '*.dll' -File) { $files[$file.Name] = $file.FullName }
$fileKeys = @($files.Keys)
$filePaths = [string[]]@($fileKeys | ForEach-Object { $files[$_] })
foreach ($path in $filePaths) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Build output missing: $path" }
}
$digests = [ZeroConArtifactCrypto]::HashFilesParallel($filePaths)
$receiptFiles = @{}
for ($i = 0; $i -lt $fileKeys.Count; $i++) {
    $receiptFiles[$fileKeys[$i]] = @{ path = $filePaths[$i]; sha256 = $digests[$i] }
}
$buildInfoPath = Join-Path $windowsRoot 'target/azookey-build/build-info.json'
$installerMetadataPath = Join-Path $windowsRoot 'target/azookey-build/AppVersion.iss'
$metaHashes = [ZeroConArtifactCrypto]::HashFilesParallel([string[]]@($buildInfoPath, $installerMetadataPath))
$receipt = @{
    assetLayout = 4; files = $receiptFiles
    buildInfo = @{ path = $buildInfoPath; sha256 = $metaHashes[0] }
    installerMetadata = @{ path = $installerMetadataPath; sha256 = $metaHashes[1] }
}
[IO.File]::WriteAllText((Join-Path $windowsRoot 'target\installer-source-build.json'), ($receipt | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
return $files
