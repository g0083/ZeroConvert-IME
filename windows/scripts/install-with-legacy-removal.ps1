# User-level bootstrap: never start this script as administrator.
[CmdletBinding()]
param([string]$PackageDirectory = $PSScriptRoot, [switch]$InspectOnly)
$ErrorActionPreference = 'Stop'
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run Install-ZeroCon.bat by normal double-click, not as administrator.'
}
$legacyRoot = [IO.Path]::GetFullPath((Join-Path $env:APPDATA 'Azookey')).TrimEnd('\')
$uninstaller = Join-Path $legacyRoot 'unins000.exe'
$keyRoot = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine, [Microsoft.Win32.RegistryView]::Registry64)
$key = $keyRoot.OpenSubKey('Software\Microsoft\Windows\CurrentVersion\Uninstall\{80B746D4-D74D-4345-8F81-47E06BCAB515}_is1')
$legacyPresent = $false
try {
    if ($key) {
        $location = ([string]$key.GetValue('InstallLocation', '')).Trim('"').TrimEnd('\')
        $version = [string]$key.GetValue('DisplayVersion', '')
        $legacyPresent = $location -ieq $legacyRoot -and $version -eq '0.1.0-alpha.1'
    }
} finally { if ($key) { $key.Dispose() }; $keyRoot.Dispose() }
if ($InspectOnly) { Write-Output "LegacyAlpha1Present=$legacyPresent"; return }
if ($legacyPresent) {
    if (-not [IO.File]::Exists($uninstaller)) {
        throw 'Legacy azooKey registration remains but its uninstaller is missing. Use Windows Installed apps to repair/remove it first.'
    }
    Write-Host 'Opening the installed azooKey alpha.1 uninstaller. Complete its normal removal wizard.'
    # ShellExecute from the original user; any required UAC approval belongs to
    # the old uninstaller itself, not to an elevated ZeroCon setup process.
    $process = Start-Process -FilePath $uninstaller -Verb Open -PassThru -Wait
    if ($process.ExitCode -ne 0) { throw "Old uninstaller ended with code $($process.ExitCode)." }
    Write-Host 'Restart Windows, then run this same Install-ZeroCon.bat again.'
    return
}
$installers = @(Get-ChildItem -LiteralPath $PackageDirectory -Filter 'ZeroConvert-IME-setup-*.exe' -File | Sort-Object LastWriteTime -Descending)
if ($installers.Count -eq 0) { throw 'Place the ZeroConvert-IME-setup-*.exe beside Install-ZeroCon.bat, then run it again.' }
Write-Host "Opening ZeroCon setup: $($installers[0].Name)"
Start-Process -FilePath $installers[0].FullName -Verb Open
