# Fast local development loop. Distribution builds remain separate and complete.
[CmdletBinding(SupportsShouldProcess)]
param([switch]$Force, [switch]$NoRestart)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'distribution.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'incremental-trial.psm1') -Force
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$windowsRoot = Join-Path $repoRoot 'windows'
$trialRoot = Join-Path $repoRoot 'ZeroConvertIME-windows'
$statePath = Join-Path $trialRoot 'trial-build-state.json'
$timer = [Diagnostics.Stopwatch]::StartNew()
if (-not (Test-Path -LiteralPath (Join-Path $trialRoot 'build-info.json'))) { throw 'Prepare and apply a complete package once before using Build-Trial.bat.' }
$previous = if (Test-Path -LiteralPath $statePath) { Get-Content -Encoding UTF8 -Raw -LiteralPath $statePath | ConvertFrom-Json } else { $null }
if ($previous -and $previous.schemaVersion -ne 1) { throw 'Unsupported incremental state. Use the complete package workflow to reset the trial.' }
$snapshot = Get-TrialInputSnapshot $repoRoot $(if ($previous -and -not $Force) { $previous.inputs } else { $null })
$changed = @($snapshot.PSObject.Properties | Where-Object {
    $Force -or -not $previous -or -not $previous.inputs.PSObject.Properties[$_.Name] -or $previous.inputs.($_.Name).hash -ne $_.Value.hash
} | ForEach-Object { $_.Name })
# Repair deployment drift even when source files did not change.
if ($previous) {
    foreach ($group in $previous.outputs.PSObject.Properties) {
        foreach ($entry in $group.Value) {
            $destination = [IO.Path]::GetFullPath((Join-Path $trialRoot $entry.path))
            if (-not $destination.StartsWith($trialRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid saved output path.' }
            $file = [IO.FileInfo]::new($destination)
            $cached = -not $Force -and $entry.PSObject.Properties['ticks'] -and $file.Exists -and
                $file.Length -eq $entry.bytes -and $file.LastWriteTimeUtc.Ticks.ToString() -eq $entry.ticks
            if (-not $cached -and (-not $file.Exists -or (Get-ArtifactHash (Get-ArtifactPath $trialRoot $entry.path)) -ne $entry.sha256)) { $changed += $group.Name; break }
        }
    }
}
$plan = @(Get-TrialBuildPlan $changed)
Write-Host ('Changed input groups: ' + ($changed -join ', '))
Write-Host ('Build/update targets: ' + ($plan -join ', '))
if (-not $plan.Count) { Write-Host "No changes; no build, copies or restarts ($([math]::Round($timer.Elapsed.TotalSeconds,1))s)."; return }
if (-not $PSCmdlet.ShouldProcess($trialRoot, 'Incrementally build changed components and apply a local development update')) { return }
$modernLlama = $null
if (@($plan | Where-Object { $_ -in @('runtime','swift','keyboard') }).Count) {
    . (Join-Path $PSScriptRoot 'modern-llama.ps1')
    $modernLlama = Initialize-ModernLlama
}
function Invoke-TrialStep([string]$Directory, [string]$Command, [string[]]$Arguments) {
    Write-Host ("Build: $Command " + ($Arguments -join ' '))
    Push-Location $Directory
    try { & $Command @Arguments | Out-Host; if ($LASTEXITCODE -ne 0) { throw "Build failed ($LASTEXITCODE): $Command" } }
    finally { Pop-Location }
}
$outputs = [ordered]@{}
if ($previous) { foreach ($group in $previous.outputs.PSObject.Properties) { $outputs[$group.Name] = @($group.Value) } }
if ('runtime' -in $plan -and -not $outputs.Contains('runtime')) {
    # Only retire files with a known original dependency hash; the apply step
    # refuses removals if a user has changed any of these files independently.
    $legacyManifest = Get-Content -Raw (Join-Path $repoRoot 'artifacts/runtime/windows/dependencies-manifest.json') | ConvertFrom-Json
    $outputs['runtime'] = @($legacyManifest.files | Where-Object { $_.path -match '^llama_(cpu|vulkan)/' })
}
$sources = [ordered]@{}
function Add-TrialOutput([string]$Group, [string]$Source, [string]$Relative) {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) { throw "Build output missing: $Source" }
    if (-not $sources.Contains($Group)) { $sources[$Group] = @() }
    $sources[$Group] += [pscustomobject]@{ source=$Source; path=$Relative; bytes=(Get-Item -LiteralPath $Source).Length; sha256=Get-ArtifactHash $Source }
}
$allTargets = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$pass = 0
while ($true) {
    $pass++
    foreach ($part in $plan) {
        $null = $allTargets.Add($part)
        # A changed dependency invalidates only its consumers. Retain other completed outputs.
        $sources.Remove($part)
    }
    foreach ($directory in @('dictionary','emoji')) {
        if ($directory -in $plan -and -not @($snapshot.$directory.files).Count) { throw "Source dictionary missing: $directory. Initialize dependencies before building." }
    }
    if ('models' -in $plan) {
        $catalog=Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $windowsRoot 'deployment/model-catalog.json') | ConvertFrom-Json
        foreach ($model in $catalog.models) {
            if ((Get-ArtifactHash (Join-Path $repoRoot ('artifacts/models/zerocon/'+$model.filename))) -ne $model.sha256) { throw "Canonical model hash mismatch: $($model.filename)" }
        }
    }
    $swiftRuntime = $null
    if ('keyboard' -in $plan) {
        $keyboardWorker = & (Join-Path $PSScriptRoot 'build-keyboard-prior.ps1')
        Add-TrialOutput 'keyboard' $keyboardWorker 'keyboard-prior.exe'
    }
    if ('swift' -in $plan) {
        if ($env:SDKROOT -notmatch '^(.*)[\\/]Platforms[\\/]([^\\/]+)[\\/]Windows\.platform[\\/]') { throw 'SDKROOT must identify the Swift Windows SDK.' }
        $swiftRuntime = Join-Path $Matches[1] ('Runtimes\' + $Matches[2] + '\usr\bin')
        Invoke-TrialStep (Join-Path $windowsRoot 'server-swift') 'swift.exe' @('build','--scratch-path','.build-modern','-c','release','-Xcc','-D_CRT_USE_C_COMPLEX_H','-Xcxx','-D_CRT_USE_C_COMPLEX_H')
        Push-Location (Join-Path $windowsRoot 'server-swift')
        try { $swiftBin = (& swift.exe build --scratch-path .build-modern -c release --show-bin-path | Select-Object -Last 1).Trim(); if ($LASTEXITCODE -ne 0) { throw 'Swift output lookup failed.' } }
        finally { Pop-Location }
        New-Item -ItemType Directory -Path (Join-Path $windowsRoot 'crates/server/target') -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $swiftBin 'azookey-server.lib') -Destination (Join-Path $windowsRoot 'crates/server/target/azookey-server.lib') -Force
        Add-TrialOutput 'swift' (Join-Path $swiftBin 'azookey-server.dll') 'azookey-server.dll'
        foreach ($file in Get-ChildItem -LiteralPath $swiftRuntime -Filter '*.dll' -File) { Add-TrialOutput 'swift' $file.FullName $file.Name }
    }
    $packages = @()
    foreach ($mapping in @(@('client','azookey-windows'),@('server','azookey-server'),@('ui','ui'),@('launcher','launcher'))) {
        if ($mapping[0] -in $plan) { $packages += @('-p',$mapping[1]) }
    }
    if ($packages.Count) { Invoke-TrialStep $windowsRoot 'cargo.exe' (@('build','--release','--locked') + $packages) }
    if ('client' -in $plan) {
        Invoke-TrialStep $windowsRoot 'cargo.exe' @('build','--release','--locked','-p','azookey-windows','--target','i686-pc-windows-msvc')
        Add-TrialOutput 'client' (Join-Path $windowsRoot 'target/release/azookey_windows.dll') 'azookey.dll'
        Add-TrialOutput 'client' (Join-Path $windowsRoot 'target/i686-pc-windows-msvc/release/azookey_windows.dll') 'azookey32.dll'
        Add-TrialOutput 'client' (Join-Path $windowsRoot 'crates/client/res/zeroconvert.ico') 'zeroconvert.ico'
    }
    foreach ($part in @('server','ui','launcher')) {
        if ($part -in $plan) {
            $name = if ($part -eq 'server') { 'azookey-server.exe' } else { "$part.exe" }
            Add-TrialOutput $part (Join-Path $windowsRoot "target/release/$name") $name
        }
    }
    if ('frontend' -in $plan) {
        $frontendRoot = Join-Path $windowsRoot 'frontend'
        $dependencyChanged = -not $previous -or -not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules/.package-lock.json'))
        if ($previous) {
            foreach ($name in @('windows/frontend/package.json','windows/frontend/package-lock.json')) {
                $before = @($previous.inputs.frontend.files | Where-Object path -eq $name)
                $after = @($snapshot.frontend.files | Where-Object path -eq $name)
                if (-not $before.Count -or -not $after.Count -or $before[0].sha256 -ne $after[0].sha256) { $dependencyChanged = $true }
            }
        }
        if ($dependencyChanged) { Invoke-TrialStep $frontendRoot 'npm.cmd' @('ci','--no-audit','--no-fund') }
        # Keep the current validation identity stable: changing it on every local edit
        # invalidates unrelated native build caches. Per-component hashes identify this update.
        $info = Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $trialRoot 'build-info.json') | ConvertFrom-Json
        $oldChannel=$env:AZOOKEY_BUILD_CHANNEL; $oldNumber=$env:AZOOKEY_BUILD_NUMBER
        try {
            $env:AZOOKEY_BUILD_CHANNEL='validation'; $env:AZOOKEY_BUILD_NUMBER=$info.buildNumber
            Invoke-TrialStep $frontendRoot 'npm.cmd' @('run','tauri:build')
        } finally { $env:AZOOKEY_BUILD_CHANNEL=$oldChannel; $env:AZOOKEY_BUILD_NUMBER=$oldNumber }
        Add-TrialOutput 'frontend' (Join-Path $windowsRoot 'target/release/frontend.exe') 'frontend.exe'
        Add-TrialOutput 'frontend' (Join-Path $windowsRoot 'target/release/frontend.exe') 'azookey-updater-helper.exe'
    }
    if ('runtime' -in $plan) {
        foreach ($backend in @('llama_cpu','llama_vulkan')) {
            foreach ($entry in Get-ArtifactEntries (Join-Path $modernLlama.runtime $backend)) {
                Add-TrialOutput 'runtime' (Join-Path $modernLlama.runtime "$backend/$($entry.path)") "$backend/$($entry.path)"
            }
        }
    }
    if ('translation-model' -in $plan) {
        $models = @(
            @{ Path = 'artifacts/models/plamo-3-translate-0.8b/plamo-3-translate-0.8b-Q4_K_M.gguf'; Hash = '923b2f28a9856de1b8b3b610a7e59ded0b14d007466fd5dd526e6a9ecaadafdd'; Target = 'models/plamo-3-translate-0.8b-Q4_K_M.gguf' },
            @{ Path = 'artifacts/models/plamo-3-translate-2b/plamo-3-translate-2b-Q4_K_M.gguf'; Hash = '1ffd9073afcc70e35ccc942428aa2b73d7aa2b9ea779bf504b775369e0276c13'; Target = 'models/plamo-3-translate-2b-Q4_K_M.gguf' }
        )
        foreach ($m in $models) {
            $src = Join-Path $repoRoot $m.Path
            if (Test-Path -LiteralPath $src -PathType Leaf) {
                if ((Get-ArtifactHash $src) -ne $m.Hash) { throw "Translation model hash mismatch: $($m.Path)" }
                Add-TrialOutput 'translation-model' $src $m.Target
            }
        }
    }
    foreach ($dataGroup in @('dictionary','emoji','deployment','models')) {
        if ($dataGroup -notin $plan) { continue }
        $prefix = switch ($dataGroup) {
            'dictionary' { 'windows/server-swift/azooKey_dictionary_storage/Dictionary/' }
            'emoji' { 'windows/server-swift/azooKey_emoji_dictionary_storage/EmojiDictionary/' }
            'deployment' { 'windows/deployment/' }
            'models' { 'artifacts/models/zerocon/' }
        }
        foreach ($entry in $snapshot.$dataGroup.files) {
            if (-not $entry.path.StartsWith($prefix,[StringComparison]::Ordinal)) { continue }
            $name=$entry.path.Substring($prefix.Length)
            if ($dataGroup -eq 'dictionary') { $name='Dictionary/'+$name }
            if ($dataGroup -eq 'emoji') { $name='EmojiDictionary/'+$name }
            if ($dataGroup -eq 'models') { $name='models/'+$name }
            Add-TrialOutput $dataGroup (Join-Path $repoRoot $entry.path) $name
        }
        if ($dataGroup -eq 'dictionary') {
            Invoke-TrialStep $windowsRoot 'node.exe' @('scripts/generate-reverse-dictionary.mjs')
            Add-TrialOutput 'dictionary' (Join-Path $windowsRoot 'target/azookey-reverse-dictionary/reverse-v2.bin') 'Dictionary/Reverse/reverse-v2.bin'
        }
    }
    $afterSnapshot = Get-TrialInputSnapshot $repoRoot $snapshot
    $duringBuild = @(Get-TrialInputChanges $snapshot $afterSnapshot)
    if (-not $duringBuild.Count) { break }
    foreach ($change in $duringBuild) {
        Write-Host ("Sources changed during build [$($change.group)]: " + ($change.paths -join ', ')) -ForegroundColor Yellow
    }
    if ($pass -ge 3) {
        throw 'Sources kept changing across 3 build passes. Pause source edits and rerun Build-Trial.bat. No update was applied.'
    }
    $plan = @(Get-TrialBuildPlan @($duringBuild | ForEach-Object { $_.group }))
    Write-Host ('Additional build/update targets: ' + ($plan -join ', '))
    $snapshot = $afterSnapshot
}
$plan = @($allTargets | Sort-Object)
$changes=@(); $removals=@()
foreach ($group in $sources.Keys) {
    foreach ($entry in $sources[$group]) {
        $destination=Get-ArtifactPath $trialRoot $entry.path
        if (-not (Test-Path -LiteralPath $destination) -or (Get-ArtifactHash $destination) -ne $entry.sha256) { $changes += $entry }
    }
    if ($outputs.Contains($group)) {
        $currentPaths=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $sources[$group]) { $null=$currentPaths.Add($entry.path) }
        foreach ($old in $outputs[$group]) {
            if (-not $currentPaths.Contains($old.path)) { $removals += $old }
        }
    }
    $outputs[$group]=@($sources[$group] | Select-Object path,bytes,sha256)
}
if ($changes.Count -or $removals.Count) {
    $updateRoot=Join-Path $repoRoot ('.local/trial-updates/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    $payload=Join-Path $updateRoot 'payload'
    New-Item -ItemType Directory -Path $payload -Force | Out-Null
    foreach ($entry in $changes) {
        $destination=Get-ArtifactPath $payload $entry.path
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $entry.source -Destination $destination
    }
    $build=Get-Content -Encoding UTF8 -Raw -LiteralPath (Join-Path $trialRoot 'build-info.json') | ConvertFrom-Json
    Write-ArtifactJson (Join-Path $updateRoot 'update-manifest.json') @{
        schemaVersion=1; kind='incremental-trial'; build=$build; createdAt=[DateTimeOffset]::Now.ToString('o')
        groups=$plan; files=@($changes | Select-Object path,bytes,sha256); removals=@($removals)
    }
    & (Join-Path $PSScriptRoot 'apply-trial-package.ps1') -UpdateDirectory $updateRoot -NoRestart:$NoRestart
}
foreach ($group in @($outputs.Keys)) {
    $outputs[$group]=@($outputs[$group] | ForEach-Object {
        $file=[IO.FileInfo]::new((Join-Path $trialRoot $_.path))
        [pscustomobject]@{path=$_.path;bytes=$_.bytes;sha256=$_.sha256;ticks=$file.LastWriteTimeUtc.Ticks.ToString()}
    })
}
Write-ArtifactJson $statePath @{ schemaVersion=1; inputs=$afterSnapshot; outputs=$outputs; updatedAt=[DateTimeOffset]::Now.ToString('o') }
Write-Host "Incremental trial ready: $($changes.Count) changed files, $($removals.Count) removals ($([math]::Round($timer.Elapsed.TotalSeconds,1))s)."
