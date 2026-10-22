<#
.SYNOPSIS
    Start ZeroCon IME (launcher.exe supervises azookey-server.exe + ui.exe)

.DESCRIPTION
    launcher.exe is the only supported entry point. It owns the per-session pipe and the
    server/ui child processes (auto-restart on crash).

    Starting azookey-server.exe / ui.exe directly (the old fallback) produced "orphan"
    processes: the server owned the IPC pipe, so the server that launcher.exe spawned later
    died immediately with "Os { code: 5, PermissionDenied }" and was respawned in a loop,
    while the orphan kept running with a stale configuration / binary. To avoid this, any
    server/ui that is not supervised by a running launcher is stopped before launcher starts.
#>

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcherExe = Join-Path $scriptDir "launcher.exe"

# Ensure AppData settings exist
$appDataDir = Join-Path $env:APPDATA "Azookey"
if (-not (Test-Path $appDataDir)) {
    New-Item -ItemType Directory -Path $appDataDir -Force | Out-Null
}
$settingsPath = Join-Path $appDataDir "settings.json"
$defaultSettings = Join-Path $scriptDir "settings.json"
if (-not (Test-Path $settingsPath) -and (Test-Path $defaultSettings)) {
    Copy-Item -Path $defaultSettings -Destination $settingsPath -Force
}

if (-not (Test-Path $launcherExe)) {
    Write-Error "[ZeroCon IME] launcher.exe not found: $launcherExe"
    exit 1
}

$sessionId = (Get-Process -Id $PID).SessionId
$existingLauncher = Get-Process -Name "launcher" -ErrorAction SilentlyContinue |
    Where-Object { $_.SessionId -eq $sessionId -and $_.Path -eq $launcherExe }

if (-not $existingLauncher) {
    # Remove unsupervised (orphan) children that would block launcher's own server/ui.
    foreach ($name in @("azookey-server", "ui", "llama-server")) {
        $orphans = Get-Process -Name $name -ErrorAction SilentlyContinue |
            Where-Object { $_.SessionId -eq $sessionId -and $_.Path -like "$scriptDir*" }
        if ($orphans) {
            Write-Host "[ZeroCon IME] Stopping unsupervised $name (PID: $($orphans.Id -join ', '))..." -ForegroundColor Yellow
            $orphans | Stop-Process -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Milliseconds 500

    Write-Host "[ZeroCon IME] Starting launcher..." -ForegroundColor Cyan
    Start-Process -FilePath $launcherExe -WorkingDirectory $scriptDir -WindowStyle Hidden
} else {
    Write-Host "[ZeroCon IME] launcher is already running (PID: $($existingLauncher.Id -join ', '))." -ForegroundColor Green
}

# Wait up to 20s for server + ui (the model load can take a few seconds)
$deadline = (Get-Date).AddSeconds(20)
do {
    Start-Sleep -Milliseconds 500
    $runningServer = Get-Process -Name "azookey-server" -ErrorAction SilentlyContinue
    $runningUi = Get-Process -Name "ui" -ErrorAction SilentlyContinue
} while ((-not ($runningServer -and $runningUi)) -and (Get-Date) -lt $deadline)

if ($runningServer -and $runningUi) {
    Write-Host "[ZeroCon IME] Server & Candidate UI running (Server PID: $($runningServer.Id -join ', '), UI PID: $($runningUi.Id -join ', ')))." -ForegroundColor Green
} else {
    Write-Warning "[ZeroCon IME] Process status: Server=$($null -ne $runningServer), UI=$($null -ne $runningUi)"
    exit 1
}
