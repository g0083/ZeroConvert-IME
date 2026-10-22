<#
.SYNOPSIS
    Restart ZeroCon IME Background Processes and Refresh TSF
#>

param(
    [switch]$ReRegisterDLLs,
    [switch]$NoElevation
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($ReRegisterDLLs) {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin -and -not $NoElevation) {
        Write-Host "[ZeroCon IME] Administrator privileges required for DLL re-registration. Elevating..." -ForegroundColor Yellow
        Start-Process powershell -Verb RunAs -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + $MyInvocation.MyCommand.Path + '" -ReRegisterDLLs')
        exit 0
    }
}

Write-Host "[ZeroCon IME] Restarting ZeroCon IME..." -ForegroundColor Cyan
& (Join-Path $scriptDir "Stop-ZeroConvertIME.ps1")

if ($ReRegisterDLLs) {
    $dll64 = Join-Path $scriptDir "azookey.dll"
    $dll32 = Join-Path $scriptDir "azookey32.dll"
    if (Test-Path $dll64) {
        Start-Process -FilePath "regsvr32.exe" -ArgumentList ('/s "' + $dll64 + '"') -Wait
        Write-Host "  -> Re-registered 64-bit TSF DLL ($dll64)" -ForegroundColor Green
    }
    if (Test-Path $dll32) {
        $regsvr32_wow64 = Join-Path $env:SystemRoot "SysWOW64\regsvr32.exe"
        if (Test-Path $regsvr32_wow64) {
            Start-Process -FilePath $regsvr32_wow64 -ArgumentList ('/s "' + $dll32 + '"') -Wait
            Write-Host "  -> Re-registered 32-bit TSF DLL ($dll32)" -ForegroundColor Green
        }
    }
}

& (Join-Path $scriptDir "Start-ZeroConvertIME.ps1")
Write-Host "[ZeroCon IME] Restart complete." -ForegroundColor Green
