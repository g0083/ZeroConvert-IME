<#
.SYNOPSIS
    ZeroCon IME (Windows) Installation Script
#>

param(
    [switch]$NoElevation,
    [switch]$SkipServerStart
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# 1. Check Administrator Privileges
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin -and -not $NoElevation) {
    Write-Host "[ZeroCon IME] Administrator privileges required for TSF DLL registration. Elevating..." -ForegroundColor Yellow
    $argList = '-NoProfile -ExecutionPolicy Bypass -File "' + $MyInvocation.MyCommand.Path + '"'
    if ($SkipServerStart) { $argList += " -SkipServerStart" }
    Start-Process powershell -Verb RunAs -ArgumentList $argList
    exit 0
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "     ZeroCon IME (Windows) Setup & Installer      " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# 2. Stop existing server / UI if running
Write-Host "[1/5] Stopping any running ZeroCon IME / Azookey processes..." -ForegroundColor Yellow
& (Join-Path $scriptDir "Stop-ZeroConvertIME.ps1")

# Clean up legacy Azookey startup task if present
try {
    schtasks /delete /tn "Azookey Startup" /f 2>$null | Out-Null
    Write-Host "  -> Removed legacy 'Azookey Startup' scheduled task" -ForegroundColor Green
} catch {}

# Clean up legacy Roaming executables to prevent console popup
$legacyFiles = @("ui.exe", "launcher.exe", "azookey-server.exe", "launch.vbs")
foreach ($lf in $legacyFiles) {
    $legacyPath = Join-Path $env:APPDATA "Azookey\$lf"
    if (Test-Path $legacyPath) {
        $backupPath = "$legacyPath.old_azookey_backup"
        Move-Item -Path $legacyPath -Destination $backupPath -Force -ErrorAction SilentlyContinue
        Write-Host "  -> Disabled legacy executable: $lf" -ForegroundColor Green
    }
}

# Clean up duplicate HKCU Run entry if exists
Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "ZeroCon IME" -ErrorAction SilentlyContinue

# 3. Configure %APPDATA%\Azookey\settings.json
Write-Host "[2/5] Configuring AppData settings..." -ForegroundColor Yellow
$appDataDir = Join-Path $env:APPDATA "Azookey"
if (-not (Test-Path $appDataDir)) {
    New-Item -ItemType Directory -Path $appDataDir -Force | Out-Null
}
$settingsPath = Join-Path $appDataDir "settings.json"
$defaultSettings = Join-Path $scriptDir "settings.json"
if (-not (Test-Path $settingsPath) -and (Test-Path $defaultSettings)) {
    Copy-Item -Path $defaultSettings -Destination $settingsPath -Force
    Write-Host "  -> Installed default settings.json to $settingsPath" -ForegroundColor Green
} else {
    Write-Host "  -> Existing settings.json preserved at $settingsPath" -ForegroundColor Green
}

# 4. Register TSF DLLs (64-bit & 32-bit)
Write-Host "[3/5] Registering TSF IME components..." -ForegroundColor Yellow
$dll64 = Join-Path $scriptDir "azookey.dll"
$dll32 = Join-Path $scriptDir "azookey32.dll"

if (Test-Path $dll64) {
    $p64 = Start-Process -FilePath "regsvr32.exe" -ArgumentList ('/s "' + $dll64 + '"') -Wait -PassThru
    if ($p64.ExitCode -eq 0) {
        Write-Host "  -> 64-bit TSF TIP registered ($dll64)" -ForegroundColor Green
    } else {
        Write-Warning ("  -> regsvr32 (64-bit) returned exit code " + $p64.ExitCode)
    }
}

if (Test-Path $dll32) {
    $regsvr32_wow64 = Join-Path $env:SystemRoot "SysWOW64\regsvr32.exe"
    if (Test-Path $regsvr32_wow64) {
        $p32 = Start-Process -FilePath $regsvr32_wow64 -ArgumentList ('/s "' + $dll32 + '"') -Wait -PassThru
        if ($p32.ExitCode -eq 0) {
            Write-Host "  -> 32-bit TSF TIP registered ($dll32)" -ForegroundColor Green
        } else {
            Write-Warning ("  -> regsvr32 (32-bit) returned exit code " + $p32.ExitCode)
        }
    }
}

# Update TSF LanguageProfile Icon & Description in Registry
$icoPath = Join-Path $scriptDir "zeroconvert.ico"
$tsfGuid = "{ffdefe79-2fc2-11ef-b16b-94e70b2c378c}"
$tipPaths = @(
    "HKLM:\SOFTWARE\Microsoft\CTF\TIP\$tsfGuid\LanguageProfile\0x00000411\{ffdefe7a-2fc2-11ef-b16b-94e70b2c378c}",
    "HKLM:\SOFTWARE\WOW6432Node\Microsoft\CTF\TIP\$tsfGuid\LanguageProfile\0x00000411\{ffdefe7a-2fc2-11ef-b16b-94e70b2c378c}"
)
foreach ($tp in $tipPaths) {
    if (Test-Path $tp) {
        if (Test-Path $icoPath) {
            Set-ItemProperty -Path $tp -Name "IconFile" -Value $icoPath -ErrorAction SilentlyContinue
            Set-ItemProperty -Path $tp -Name "IconIndex" -Value 0 -ErrorAction SilentlyContinue
        }
        Set-ItemProperty -Path $tp -Name "Description" -Value "ZeroCon IME" -ErrorAction SilentlyContinue
    }
}

# Add ZeroCon IME to user's InputMethodTips so it appears in language switcher / Win+Space
try {
    $langList = Get-WinUserLanguageList
    $jaLang = $langList | Where-Object { $_.LanguageTag -eq "ja" }
    if ($jaLang) {
        $tipString = "0411:{ffdefe79-2fc2-11ef-b16b-94e70b2c378c}{ffdefe7a-2fc2-11ef-b16b-94e70b2c378c}"
        if ($jaLang.InputMethodTips -notcontains $tipString) {
            $jaLang.InputMethodTips.Add($tipString)
            Set-WinUserLanguageList $langList -Force
            Write-Host "  -> Added ZeroCon IME to Windows InputMethodTips" -ForegroundColor Green
        }
    }
} catch {
    Write-Warning "  -> Could not update InputMethodTips: $_"
}

# Register in Windows Installed Apps (HKCU Uninstall)
$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\ZeroConvertIME"
if (-not (Test-Path $uninstallKey)) {
    New-Item -Path $uninstallKey -Force | Out-Null
}
Set-ItemProperty -Path $uninstallKey -Name "DisplayName" -Value "ZeroCon IME"
Set-ItemProperty -Path $uninstallKey -Name "DisplayVersion" -Value "3.2.0"
Set-ItemProperty -Path $uninstallKey -Name "Publisher" -Value "ZeroCon Project"
if (Test-Path $icoPath) {
    Set-ItemProperty -Path $uninstallKey -Name "DisplayIcon" -Value $icoPath
}
Set-ItemProperty -Path $uninstallKey -Name "InstallLocation" -Value $scriptDir
$uninstCmd = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $scriptDir "Uninstall-ZeroConvertIME.ps1") + '"'
Set-ItemProperty -Path $uninstallKey -Name "UninstallString" -Value $uninstCmd
Set-ItemProperty -Path $uninstallKey -Name "NoModify" -Value 1 -Type DWord
Set-ItemProperty -Path $uninstallKey -Name "NoRepair" -Value 1 -Type DWord

# 5. Register Startup in Task Scheduler & Registry for Auto-Launch on Boot
Write-Host "[4/5] Configuring Auto-Startup registration..." -ForegroundColor Yellow

# 5.1-5.3 Task Scheduler (per-user, no admin needed) + HKCU Run + Startup shortcut.
# NOTE: errors are no longer hidden. Previously a failed `schtasks /Create` (the XML used the
# Users-group principal, which needs admin) was swallowed and reported as success.
$taskName = "ZeroConvert Startup"
$launcherPath = Join-Path $scriptDir "launcher.exe"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptDir "Register-StartupTask.ps1") -TaskName $taskName
if ($LASTEXITCODE -ne 0) {
    Write-Warning "  -> Auto-startup registration reported errors (see messages above)."
}

# 5.2 Register the TSF profile so Windows restores ZeroCon IME as the Japanese input
#     method at logon. Without this, every sign-in falls back to MS-IME and the user
#     has to pick ZeroCon IME manually with Win+Space every time.
Write-Host "  -> Registering the ZeroCon IME input method for automatic activation..." -ForegroundColor Yellow
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptDir "Enable-DefaultIME.ps1")
if ($LASTEXITCODE -ne 0) {
    Write-Warning "  -> Default input method registration reported errors (see messages above)."
}

# 6. Start Server
if (-not $SkipServerStart) {
    Write-Host "[5/5] Starting ZeroCon IME Server..." -ForegroundColor Yellow
    & (Join-Path $scriptDir "Start-ZeroConvertIME.ps1")
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "ZeroCon IME installation completed!" -ForegroundColor Green
Write-Host "Switch keyboard layout using Win + Space to select 'ZeroCon IME'." -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
