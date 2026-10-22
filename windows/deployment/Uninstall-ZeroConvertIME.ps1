<#
.SYNOPSIS
    ZeroCon IME (Windows) Uninstallation Script
#>

param(
    [switch]$NoElevation
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Check Administrator Privileges
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin -and -not $NoElevation) {
    Write-Host "[ZeroCon IME] Administrator privileges required. Elevating..." -ForegroundColor Yellow
    Start-Process powershell -Verb RunAs -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + $MyInvocation.MyCommand.Path + '"')
    exit 0
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "      ZeroCon IME (Windows) Uninstallation        " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# 1. Stop Server
Write-Host "[1/3] Stopping running processes..." -ForegroundColor Yellow
& (Join-Path $scriptDir "Stop-ZeroConvertIME.ps1")

# 2. Delete Startup Entries (Task, Run registry, Shortcut)
Write-Host "[2/3] Removing startup registrations..." -ForegroundColor Yellow
try {
    schtasks /delete /tn "ZeroConvert Startup" /f 2>$null | Out-Null
    Write-Host "  -> Removed 'ZeroConvert Startup' scheduled task" -ForegroundColor Green
} catch {}
Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "ZeroConvertIME" -ErrorAction SilentlyContinue
Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run" -Name "ZeroConvertIME" -ErrorAction SilentlyContinue

$startupFolder = [Environment]::GetFolderPath("Startup")
$shortcutPath = Join-Path $startupFolder "ZeroConvertIME.lnk"
if (Test-Path $shortcutPath) {
    Remove-Item -Path $shortcutPath -Force
    Write-Host "  -> Startup shortcut removed" -ForegroundColor Green
}

# 3. Unregister TSF DLLs
Write-Host "[3/3] Unregistering TSF IME components..." -ForegroundColor Yellow
$dll64 = Join-Path $scriptDir "azookey.dll"
$dll32 = Join-Path $scriptDir "azookey32.dll"

# Remove the per-user CTF state so Windows does not try to restore the removed IME.
$tipClsId = "{FFDEFE79-2FC2-11EF-B16B-94E70B2C378C}"
$profileGuid = "{FFDEFE7A-2FC2-11EF-B16B-94E70B2C378C}"
Remove-Item -LiteralPath "HKCU:\Software\Microsoft\CTF\TIP\$tipClsId" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath "HKCU:\Software\Microsoft\CTF\InputSession\00000411\$tipClsId" -Recurse -Force -ErrorAction SilentlyContinue
$userProfileKey = "HKCU:\Control Panel\International\User Profile"
$override = (Get-ItemProperty -Path $userProfileKey -Name "InputMethodOverride" -ErrorAction SilentlyContinue).InputMethodOverride
if ($override -and $override -like "*$tipClsId*") {
    Remove-ItemProperty -Path $userProfileKey -Name "InputMethodOverride" -ErrorAction SilentlyContinue
}

if (Test-Path $dll64) {
    Start-Process -FilePath "regsvr32.exe" -ArgumentList ('/u /s "' + $dll64 + '"') -Wait
    Write-Host "  -> 64-bit TSF TIP unregistered" -ForegroundColor Green
}
if (Test-Path $dll32) {
    Start-Process -FilePath "regsvr32.exe" -ArgumentList ('/u /s "' + $dll32 + '"') -Wait
    Write-Host "  -> 32-bit TSF TIP unregistered" -ForegroundColor Green
}

$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\ZeroConvertIME"
if (Test-Path $uninstallKey) {
    Remove-Item -Path $uninstallKey -Recurse -Force
    Write-Host "  -> Windows App list unregistered" -ForegroundColor Green
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "ZeroCon IME uninstallation complete." -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Cyan
