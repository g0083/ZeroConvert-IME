# Deploy the exact prepared package; do not build, register TSF, or touch AppData.
[CmdletBinding(SupportsShouldProcess)]
param([string]$PackageDirectory, [string]$UpdateDirectory, [string]$TrialDirectory, [switch]$NoRestart)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'distribution.psm1') -Force
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ($PackageDirectory -and $UpdateDirectory) { throw 'Choose a complete package or incremental update.' }
$implicitPackage = -not $PackageDirectory -and -not $UpdateDirectory
if (-not $PackageDirectory -and -not $UpdateDirectory) { $PackageDirectory = Get-LatestPackageDirectory $repoRoot }
if (-not $TrialDirectory) { $TrialDirectory = Join-Path $repoRoot 'ZeroConvertIME-windows' }
$trial = [IO.Path]::GetFullPath($TrialDirectory).TrimEnd('\')
$defaultTrial = Join-Path $repoRoot 'ZeroConvertIME-windows'
if (-not $trial.Equals($defaultTrial, [StringComparison]::OrdinalIgnoreCase) -and
    -not $trial.StartsWith((Join-Path $repoRoot '.local') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Trial destination must be ZeroConvertIME-windows or a subdirectory of .local in this workspace.'
}
# Reject a stale default before hashing the entire complete distribution.
if ($implicitPackage) {
    $manifestPath = Get-ArtifactPath $PackageDirectory 'payload/package-manifest.json'
    $manifestHeader = Get-Content -Encoding UTF8 -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    Assert-DefaultTrialPackageIsCurrent $trial ([pscustomobject]@{ Manifest = $manifestHeader })
}
if ($UpdateDirectory) {
    Import-Module (Join-Path $PSScriptRoot 'incremental-trial.psm1') -Force
    $package = Read-TrialUpdate $UpdateDirectory
} else { $package = Read-PreparedPackage $PackageDirectory }
$entries = @($package.Manifest.files)
if (-not $UpdateDirectory) {
    $entries += [pscustomobject]@{ path = 'package-manifest.json'; sha256 = $package.Record.manifestSha256; bytes = (Get-Item -LiteralPath (Join-Path $package.Payload 'package-manifest.json')).Length }
}
$removals = if ($UpdateDirectory) { @($package.Manifest.removals) } else { @() }
foreach ($entry in $removals) {
    $destination = Get-ArtifactPath $trial $entry.path
    if ((Test-Path -LiteralPath $destination) -and (Get-ArtifactHash $destination) -ne $entry.sha256) { throw "Refusing to remove an externally changed file: $($entry.path)" }
}
$changes = @()
foreach ($entry in $entries) {
    $destination = Get-ArtifactPath $trial $entry.path
    if ((Test-Path -LiteralPath $destination) -and -not (Test-Path -LiteralPath $destination -PathType Leaf)) { throw "Not a file: $destination" }
    if (-not (Test-Path -LiteralPath $destination) -or (Get-ArtifactHash $destination) -ne $entry.sha256) { $changes += $entry }
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backupRoot = Join-Path $repoRoot ".local/trial-backups/$stamp"
Write-Host "Package: $($package.Directory)"
Write-Host "Build: $($package.Build.version)"
Write-Host "Trial: $trial; changed files: $($changes.Count)"
if (-not $PSCmdlet.ShouldProcess($trial, 'Apply verified package with rollback backup; restart only this trial installation')) { return }
New-Item -ItemType Directory -Path $trial, $backupRoot -Force | Out-Null
# Stage all replacements before stopping a running IME.
$staging = Join-Path $backupRoot 'new'
foreach ($entry in $changes) {
    $staged = Get-ArtifactPath $staging $entry.path
    New-Item -ItemType Directory -Path (Split-Path -Parent $staged) -Force | Out-Null
    Copy-Item -LiteralPath (Get-ArtifactPath $package.Payload $entry.path) -Destination $staged
}
Test-ArtifactEntries $staging $changes
# A frontend-only edit must not restart the conversion engine or load models.
$changedPaths = @($changes | ForEach-Object { $_.path }) + @($removals | ForEach-Object { $_.path })
$frontendOnly = $UpdateDirectory -and @($changedPaths | Where-Object { $_ -notin @('frontend.exe','azookey-updater-helper.exe') }).Count -eq 0
$needsRuntime = -not $UpdateDirectory -or @($changedPaths | Where-Object { $_ -match '^(launcher\.exe|ui\.exe|azookey-server\.(exe|dll)|Dictionary/|EmojiDictionary/|models/|swift|Foundation|dispatch\.dll|BlocksRuntime\.dll|_Foundation)' }).Count -gt 0
$needsFrontend = $needsRuntime -or @($changedPaths | Where-Object { $_ -in @('frontend.exe','azookey-updater-helper.exe') }).Count -gt 0
$sessionId = (Get-Process -Id $PID).SessionId
$running = @()
foreach ($candidate in Get-Process -ErrorAction SilentlyContinue) {
    if ($candidate.SessionId -ne $sessionId -or $candidate.ProcessName -notmatch '^(launcher|azookey-server|ui|frontend(\.previous[^.]*)?|llama-server)$') { continue }
    if ($UpdateDirectory) {
        if ($candidate.ProcessName -match '^frontend') { if (-not $needsFrontend) { continue } }
        elseif (-not $needsRuntime) { continue }
    }
    $imagePath = Get-ArtifactProcessPath $candidate.Id
    if ($imagePath -and $imagePath.StartsWith($trial + '\', [StringComparison]::OrdinalIgnoreCase) -and
        [ZeroConDistributionProcess]::IsRunning($candidate.Id)) { $running += $candidate }
}
# CUDA's executable may live in the per-user backend cache. Include only model
# processes parented by the already verified trial processes, never all llama servers.
if ($running.Count) {
    foreach ($modelProcess in Get-CimInstance Win32_Process -Filter "Name = 'llama-server.exe'") {
        if ($modelProcess.SessionId -eq $sessionId -and $modelProcess.ParentProcessId -in @($running.Id) -and
            $modelProcess.ProcessId -notin @($running.Id) -and [ZeroConDistributionProcess]::IsRunning($modelProcess.ProcessId)) {
            $running += Get-Process -Id $modelProcess.ProcessId
        }
    }
}
# Check all access rights before stopping anything, including elevated/uiAccess processes.
foreach ($process in $running) {
    if (-not [ZeroConDistributionProcess]::CanStop($process.Id) -and [ZeroConDistributionProcess]::IsRunning($process.Id)) {
        throw "Cannot stop trial PID $($process.Id). Run Apply-Trial.bat from an administrator PowerShell; no running processes have been stopped."
    }
}
$restartLauncher = @($running | Where-Object { $_.ProcessName -in @('launcher','azookey-server','ui','llama-server') }).Count -gt 0
$restartFrontend = @($running | Where-Object ProcessName -eq 'frontend').Count -gt 0
$stoppedLauncher = $false
$stoppedFrontend = $false
$applied = New-Object 'Collections.Generic.List[object]'
try {
    # Stop launcher first, so it cannot respawn children during replacement.
    foreach ($process in @($running | Sort-Object @{ Expression = { if ($_.ProcessName -eq 'launcher') { 0 } else { 1 } } })) {
        Write-Host "Stopping trial $($process.ProcessName) (PID $($process.Id))"
        [ZeroConDistributionProcess]::Stop($process.Id)
        if ($process.ProcessName -in @('launcher','azookey-server','ui','llama-server')) { $stoppedLauncher = $true }
        if ($process.ProcessName -eq 'frontend') { $stoppedFrontend = $true }
    }
    foreach ($entry in $removals) {
        $destination = Get-ArtifactPath $trial $entry.path
        if (-not (Test-Path -LiteralPath $destination)) { continue }
        $backup = Get-ArtifactPath (Join-Path $backupRoot 'old') $entry.path
        New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
        Move-Item -LiteralPath $destination -Destination $backup
        $applied.Add([pscustomobject]@{ destination=$destination; backup=$backup; existed=$true })
    }
    foreach ($entry in $changes) {
        $destination = Get-ArtifactPath $trial $entry.path
        $backup = Get-ArtifactPath (Join-Path $backupRoot 'old') $entry.path
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination), (Split-Path -Parent $backup) -Force | Out-Null
        $existed = Test-Path -LiteralPath $destination
        if ($existed) { Move-Item -LiteralPath $destination -Destination $backup }
        $applied.Add([pscustomobject]@{ destination = $destination; backup = $backup; existed = $existed })
        Copy-Item -LiteralPath (Get-ArtifactPath $staging $entry.path) -Destination $destination
    }
    Test-ArtifactEntries $trial $entries
    foreach ($entry in $removals) {
        if (Test-Path -LiteralPath (Get-ArtifactPath $trial $entry.path)) { throw "Removed file still exists: $($entry.path)" }
    }
    $deployment = @{
        schemaVersion = 1; mode = $(if ($UpdateDirectory) { 'incremental' } else { 'full' }); appliedAt = [DateTimeOffset]::Now.ToString('o')
        packageDirectory = $package.Directory; manifestSha256 = $package.Record.manifestSha256
        build = $package.Build; backupDirectory = $backupRoot
        note = 'Managed package files verified. Existing applications may still hold the previous TSF DLL until reopened.'
    }
    $receipt = Join-Path $trial 'trial-deployment.json'
    if (Test-Path -LiteralPath $receipt) { Copy-Item -LiteralPath $receipt -Destination (Join-Path $backupRoot 'previous-trial-deployment.json') }
    Write-ArtifactJson (Join-Path $backupRoot 'applied-files.json') $applied.ToArray()
    Write-ArtifactJson $receipt $deployment
} catch {
    $failure = $_
    # Preserve failed new files as well as the old files; no recursive deletion.
    for ($index = $applied.Count - 1; $index -ge 0; $index--) {
        $item = $applied[$index]
        if (Test-Path -LiteralPath $item.destination) {
            $relative = $item.destination.Substring($trial.Length + 1)
            $failed = Get-ArtifactPath (Join-Path $backupRoot 'failed') $relative
            New-Item -ItemType Directory -Path (Split-Path -Parent $failed) -Force | Out-Null
            Move-Item -LiteralPath $item.destination -Destination $failed
        }
        if ($item.existed) { Move-Item -LiteralPath $item.backup -Destination $item.destination }
    }
    throw $failure
} finally {
    if (-not $NoRestart) {
        if ($restartLauncher -and $stoppedLauncher) { Start-Process -FilePath (Join-Path $trial 'launcher.exe') -WorkingDirectory $trial -WindowStyle Hidden }
        if ($restartFrontend -and $stoppedFrontend) { Start-Process -FilePath (Join-Path $trial 'frontend.exe') -WorkingDirectory $trial }
    }
}
Write-Host "Trial package verified: $($package.Record.manifestSha256)"
Write-Host "Backup: $backupRoot"
Write-Host 'Reopen input applications to load the updated TSF DLL. No registration or AppData changes were made.'
