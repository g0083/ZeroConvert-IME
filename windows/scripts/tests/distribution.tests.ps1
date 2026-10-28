$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$scriptsRoot = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $scriptsRoot 'distribution.psm1') -Force
$repoRoot = [IO.Path]::GetFullPath((Join-Path $scriptsRoot '../..'))
$testRoot = Join-Path $repoRoot ('.local/distribution-tests/' + [guid]::NewGuid().ToString('N'))
$packageRoot = Join-Path $testRoot 'package'
$payload = Join-Path $packageRoot 'payload'
New-Item -ItemType Directory -Path $payload -Force | Out-Null
$required = @('frontend.exe','launcher.exe','ui.exe','azookey-server.exe','azookey-server.dll',
    'azookey.dll','azookey32.dll','settings.json',
    'models/zerocon-nano-v4-q8_0.gguf','models/zerocon-nano-v4-iq4nl-emb8.gguf','models/zerocon-model-corrector.gguf',
    'llama_cpu/llama-server.exe','llama_vulkan/llama-server.exe',('Dictionary/' + [char]0x3064 + '.louds'),('Dictionary/' + [char]0x309D + '.louds'),('Dictionary/' + [char]0x30FD + '.louds'))
foreach ($name in $required) {
    $file = Get-ArtifactPath $payload $name
    New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force | Out-Null
    [IO.File]::WriteAllText($file, "fixture: $name")
}
$build = [ordered]@{ version = '1.2.3.dev.1'; channel = 'validation'; revision = 'fixture'; installerGeneration = 2 }
Write-ArtifactJson (Join-Path $payload 'build-info.json') $build
[IO.File]::WriteAllText((Join-Path $packageRoot 'AppVersion.iss'), '#define MyAppVersion "1.2.3.dev.1"')
function Seal-Package {
    Write-ArtifactJson (Join-Path $payload 'package-manifest.json') @{
        schemaVersion = 1; build = $build; files = @(Get-ArtifactEntries $payload @('package-manifest.json'))
    }
    Write-ArtifactJson (Join-Path $packageRoot 'package.json') @{
        schemaVersion = 1; manifestSha256 = Get-ArtifactHash (Join-Path $payload 'package-manifest.json')
        installerMetadataSha256 = Get-ArtifactHash (Join-Path $packageRoot 'AppVersion.iss')
    }
}
$script:checks = 0
function Assert-True($Value, [string]$Label) {
    if (-not $Value) { throw "FAIL: $Label" }
    $script:checks++
}
function Assert-Rejected([scriptblock]$Action, [string]$Label) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-True $rejected $Label
}
Seal-Package
$package = Read-PreparedPackage $packageRoot
Assert-True ($package.Build.version -eq $build.version) 'build identity'
foreach ($unsafe in @('../outside','/absolute','C:/absolute','a/../outside','x:stream','a/./b')) {
    Assert-Rejected { Get-ArtifactPath $payload $unsafe } "reject $unsafe"
}
$original = [IO.File]::ReadAllText((Join-Path $payload 'frontend.exe'))
[IO.File]::WriteAllText((Join-Path $payload 'frontend.exe'), 'tampered')
Assert-Rejected { Read-PreparedPackage $packageRoot } 'reject changed executable'
[IO.File]::WriteAllText((Join-Path $payload 'frontend.exe'), $original)
[IO.File]::WriteAllText((Join-Path $payload 'extra.txt'), 'extra')
Assert-Rejected { Read-PreparedPackage $packageRoot } 'reject untracked payload file'
Move-Item -LiteralPath (Join-Path $payload 'extra.txt') -Destination (Join-Path $testRoot 'extra.txt')
$trial = Join-Path $testRoot 'trial'
& (Join-Path $scriptsRoot 'apply-trial-package.ps1') -PackageDirectory $packageRoot -TrialDirectory $trial -NoRestart -WhatIf
Assert-True (-not (Test-Path -LiteralPath $trial)) 'WhatIf makes no destination changes'
& (Join-Path $scriptsRoot 'apply-trial-package.ps1') -PackageDirectory $packageRoot -TrialDirectory $trial -NoRestart
$receipt = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $trial 'trial-deployment.json') | ConvertFrom-Json
Assert-True ($receipt.manifestSha256 -eq $package.Record.manifestSha256) 'deployment identity matches package'
[IO.File]::WriteAllText((Join-Path $trial 'personal-history.txt'), 'keep me')
& (Join-Path $scriptsRoot 'apply-trial-package.ps1') -PackageDirectory $packageRoot -TrialDirectory $trial -NoRestart
Assert-True ([IO.File]::ReadAllText((Join-Path $trial 'personal-history.txt')) -eq 'keep me') 'preserve unmanaged files'
# Lock a later replacement against rename, after at least one earlier file is replaced.
[IO.File]::WriteAllText((Join-Path $trial 'azookey.dll'), 'old DLL')
[IO.File]::WriteAllText((Join-Path $trial 'frontend.exe'), 'old frontend')
$locked = [IO.File]::Open((Join-Path $trial 'frontend.exe'), [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
try {
    Assert-Rejected { & (Join-Path $scriptsRoot 'apply-trial-package.ps1') -PackageDirectory $packageRoot -TrialDirectory $trial -NoRestart } 'locked file fails deployment'
} finally { $locked.Dispose() }
Assert-True ([IO.File]::ReadAllText((Join-Path $trial 'azookey.dll')) -eq 'old DLL') 'rollback restores earlier replaced DLL'
Assert-True ([IO.File]::ReadAllText((Join-Path $trial 'frontend.exe')) -eq 'old frontend') 'locked original preserved'
Assert-True ([IO.File]::ReadAllText((Join-Path $trial 'personal-history.txt')) -eq 'keep me') 'rollback preserves unmanaged file'
# Dependencies must not import app binaries, Swift DLLs, models, settings or logs.
$depSource = Join-Path $testRoot 'dependency-source'
foreach ($name in @('llama_cpu/llama-server.exe','llama_vulkan/llama-server.exe','vcruntime140.dll','frontend.exe','swiftCore.dll','settings.json','zenz.gguf','private.log')) {
    $file = Get-ArtifactPath $depSource $name
    New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force | Out-Null
    [IO.File]::WriteAllText($file, 'fixture')
}
$depDestination = Join-Path $testRoot 'dependencies'
& (Join-Path $scriptsRoot 'import-runtime-dependencies.ps1') -SourceDirectory $depSource -DestinationDirectory $depDestination
$dep = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $depDestination 'dependencies-manifest.json') | ConvertFrom-Json
Assert-True (@($dep.files).Count -eq 3) 'import only engines and VC runtime'
Assert-True (-not (Test-Path -LiteralPath (Join-Path $depDestination 'frontend.exe'))) 'do not import app binary'
# A disposable child verifies the Windows API stop path without touching an IME.
$child = Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile -Command Start-Sleep -Seconds 30' -WindowStyle Hidden -PassThru
try {
    $childPath = Get-ArtifactProcessPath $child.Id
    Assert-True ($childPath -like '*\powershell.exe') 'query child using limited process rights'
    Assert-True ([ZeroConDistributionProcess]::CanStop($child.Id)) 'preflight child stop rights'
    Assert-True ([ZeroConDistributionProcess]::IsRunning($child.Id)) 'child is running before stop'
    [ZeroConDistributionProcess]::Stop($child.Id)
    Assert-True ($child.WaitForExit(1000)) 'stop and wait for disposable child'
    Assert-True (-not [ZeroConDistributionProcess]::IsRunning($child.Id)) 'do not mistake retained exited process for a running one'
    [ZeroConDistributionProcess]::Stop($child.Id)
    Assert-True (-not [ZeroConDistributionProcess]::IsRunning($child.Id)) 'stopping an already exited process is harmless'
    [ZeroConDistributionProcess]::Stop(2147483647)
    Assert-True (-not [ZeroConDistributionProcess]::IsRunning(2147483647)) 'process disappearing before stop is harmless'
} finally {
    if (-not $child.HasExited) { Stop-Process -Id $child.Id -Force }
}
Write-Host "PASS: $script:checks distribution assertions; evidence: $testRoot"
