<#
.SYNOPSIS
    Fix ZeroCon IME Registry Icons and Cache
#>

param([switch]$NoElevation)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin -and -not $NoElevation) {
    Write-Host "[ZeroCon IME] Elevating to Administrator to update TSF icon registration..." -ForegroundColor Yellow
    Start-Process powershell -Verb RunAs -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + $MyInvocation.MyCommand.Path + '"')
    exit 0
}

$icoPath = Join-Path $scriptDir "zeroconvert.ico"
if (-not (Test-Path $icoPath)) {
    Write-Host "[ZeroCon IME] Error: zeroconvert.ico not found at $icoPath" -ForegroundColor Red
    exit 1
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "    ZeroCon IME Icon & TSF Registration Update    " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

$tsfGuid = "{ffdefe79-2fc2-11ef-b16b-94e70b2c378c}"
$profileGuids = @(
    "{ffdefe7a-2fc2-11ef-b16b-94e70b2c378c}",
    "{FFDEFE7A-2FC2-11EF-B16B-94E70B2C378C}"
)

$basePaths = @(
    "HKLM:\SOFTWARE\Microsoft\CTF\TIP\$tsfGuid\LanguageProfile\0x00000411",
    "HKLM:\SOFTWARE\WOW6432Node\Microsoft\CTF\TIP\$tsfGuid\LanguageProfile\0x00000411"
)

foreach ($base in $basePaths) {
    if (Test-Path $base) {
        $children = Get-ChildItem -Path $base -ErrorAction SilentlyContinue
        foreach ($child in $children) {
            $childPath = $child.PSPath
            try {
                Set-ItemProperty -Path $childPath -Name "IconFile" -Value $icoPath -Force
                Set-ItemProperty -Path $childPath -Name "IconIndex" -Value 0 -Force
                Set-ItemProperty -Path $childPath -Name "Description" -Value "ZeroCon IME" -Force
                Write-Host ("  [OK] Updated: " + $childPath) -ForegroundColor Green
            } catch {
                Write-Host ("  [ERROR] Failed to update: " + $childPath + " : " + $_) -ForegroundColor Red
            }
        }
    }
}

# Also update CLSID default icon if any
$clsidPaths = @(
    "Registry::HKEY_CLASSES_ROOT\CLSID\$tsfGuid\DefaultIcon",
    "HKLM:\SOFTWARE\Classes\CLSID\$tsfGuid\DefaultIcon"
)
foreach ($cpath in $clsidPaths) {
    try {
        if (-not (Test-Path $cpath)) { New-Item -Path $cpath -Force | Out-Null }
        Set-ItemProperty -Path $cpath -Name "(default)" -Value ("$icoPath,0") -Force
        Write-Host ("  [OK] Updated CLSID DefaultIcon: " + $cpath) -ForegroundColor Green
    } catch {}
}

# Update Startup Shortcut Icon
$startupFolder = [Environment]::GetFolderPath("Startup")
$shortcutPath = Join-Path $startupFolder "ZeroConvertIME.lnk"
if (Test-Path $shortcutPath) {
    try {
        $wsh = New-Object -ComObject WScript.Shell
        $shortcut = $wsh.CreateShortcut($shortcutPath)
        $shortcut.IconLocation = "$icoPath,0"
        $shortcut.Save()
        Write-Host ("  [OK] Updated Startup Shortcut Icon: " + $shortcutPath) -ForegroundColor Green
    } catch {}
}

Write-Host "`nRestarting ZeroCon IME processes..." -ForegroundColor Yellow
& (Join-Path $scriptDir "Restart-ZeroConvertIME.ps1") -NoElevation

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "Done! TSF Icon updated to $icoPath" -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Cyan
