<#
.SYNOPSIS
    Display ZeroCon IME Status & Diagnostic Summary
#>

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "          ZeroCon IME (Windows) Status            " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# 1. Server Process
$serverProc = Get-Process -Name "azookey-server" -ErrorAction SilentlyContinue
if ($serverProc) {
    $wsMB = [math]::Round(($serverProc | Measure-Object -Property WorkingSet64 -Sum).Sum / 1MB, 1)
    Write-Host "[Server]     RUNNING (PID: $($serverProc.Id -join ', '), Memory: ${wsMB} MB)" -ForegroundColor Green
} else {
    Write-Host "[Server]     STOPPED" -ForegroundColor Red
}

# 2. UI Process
$uiProc = Get-Process -Name "ui" -ErrorAction SilentlyContinue
if ($uiProc) {
    Write-Host "[UI Overlay] RUNNING (PID: $($uiProc.Id -join ', '))" -ForegroundColor Green
} else {
    Write-Host "[UI Overlay] STOPPED (Run Start-ZeroConvertIME.ps1)" -ForegroundColor Red
}

# 3. Named Pipe Check
$pipes = [System.IO.Directory]::GetFiles("\\.\pipe\") | Where-Object { $_ -like "*azookey*" } | ForEach-Object { $_ -replace '^\\\\\.\\pipe\\', '' }
if ($pipes) {
    Write-Host "[Named Pipe] ACTIVE ($($pipes -join ', '))" -ForegroundColor Green
} else {
    Write-Host "[Named Pipe] NOT FOUND" -ForegroundColor Yellow
}

# 4. Model Check
$gguf = Join-Path $scriptDir "zenz.gguf"
if (Test-Path $gguf) {
    $sizeMB = [math]::Round((Get-Item $gguf).Length / 1MB, 1)
    Write-Host "[ZeroCon AI] PRESENT ($sizeMB MB - ZeroCon Model v3.2)" -ForegroundColor Green
} else {
    Write-Host "[ZeroCon AI] MISSING ($gguf)" -ForegroundColor Red
}

# 5. Settings Check
$settingsPath = Join-Path $env:APPDATA "Azookey\settings.json"
if (Test-Path $settingsPath) {
    try {
        $cfg = Get-Content -Path $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
        Write-Host "[Config]     enable=$($cfg.zenzai.enable), backend=$($cfg.zenzai.backend) ($settingsPath)" -ForegroundColor Green
    } catch {
        Write-Host "[Config]     INVALID JSON ($settingsPath)" -ForegroundColor Red
    }
} else {
    Write-Host "[Config]     NOT INITIALIZED ($settingsPath)" -ForegroundColor Yellow
}

# 6. TSF Registration Check
$clsidPath = "HKCR:\CLSID\{ffdefe79-2fc2-11ef-b16b-94e70b2c378c}\InProcServer32"
if (-not (Test-Path "HKCR:\")) {
    New-PSDrive -Name HKCR -PSProvider Registry -Root HKEY_CLASSES_ROOT -ErrorAction SilentlyContinue | Out-Null
}
if (Test-Path $clsidPath) {
    $regDll = (Get-ItemProperty -Path $clsidPath -Name "(default)" -ErrorAction SilentlyContinue)."(default)"
    Write-Host "[TSF 64-bit] REGISTERED -> $regDll" -ForegroundColor Green
} else {
    Write-Host "[TSF 64-bit] NOT REGISTERED (Run Install-ZeroConvertIME.ps1 as Admin)" -ForegroundColor Yellow
}

# 7. Auto-Start Check
$task = Get-ScheduledTask -TaskName "ZeroConvert Startup" -ErrorAction SilentlyContinue
if ($task) {
    Write-Host "[Auto-Start] Scheduled task REGISTERED (state: $($task.State))" -ForegroundColor Green
} else {
    Write-Host "[Auto-Start] Scheduled task MISSING (Run Register-StartupTask.ps1)" -ForegroundColor Yellow
}
$runEntry = (Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "ZeroConvertIME" -ErrorAction SilentlyContinue).ZeroConvertIME
if ($runEntry) {
    Write-Host "[Auto-Start] Run key REGISTERED -> $runEntry" -ForegroundColor Green
} else {
    Write-Host "[Auto-Start] Run key MISSING" -ForegroundColor Yellow
}

# 8. Default Input Method Check (the IME Windows restores at logon)
$ctfProfileKey = "HKCU:\Software\Microsoft\CTF\TIP\{FFDEFE79-2FC2-11EF-B16B-94E70B2C378C}\LanguageProfile\0x00000411\{FFDEFE7A-2FC2-11EF-B16B-94E70B2C378C}"
$ctfEnabled = $null
if (Test-Path -LiteralPath $ctfProfileKey) {
    $ctfEnabled = (Get-Item -LiteralPath $ctfProfileKey).GetValue("Enable")
}
if ($ctfEnabled -eq 1) {
    Write-Host "[Default IME] REGISTERED (Windows restores ZeroCon IME at logon)" -ForegroundColor Green
} else {
    Write-Host "[Default IME] NOT REGISTERED (Run Enable-DefaultIME.ps1) - the IME is not activated automatically at logon" -ForegroundColor Yellow
}
Write-Host "==================================================" -ForegroundColor Cyan
