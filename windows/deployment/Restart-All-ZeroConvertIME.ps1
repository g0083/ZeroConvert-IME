<#
.SYNOPSIS
Restart this installation's IME processes and the current session's ctfmon.
.DESCRIPTION
Does not build, deploy binaries, change registration, or close input applications.
Applications holding an older TSF DLL must be restarted separately.
Use -WhatIf to inspect the planned restart without stopping anything.
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$InstallDirectory,
    [switch]$NoNotification
)

$ErrorActionPreference = 'Stop'

function Show-RestartNotification([string]$Message, [bool]$Failed = $false) {
    if ($NoNotification) { return }
    try {
        $popup = New-Object -ComObject WScript.Shell
        $icon = if ($Failed) { 16 } else { 64 }
        # The console retains the result after this notification disappears.
        $null = $popup.Popup($Message, 15, 'ZeroCon IME restart', $icon)
    } catch {
        Write-Warning "Could not display notification: $($_.Exception.Message)"
    }
}

try {
    # Resolve defaults in the script body (also works in Windows PowerShell 5.1).
    if ([string]::IsNullOrWhiteSpace($InstallDirectory)) {
        $InstallDirectory = $PSScriptRoot
        if (-not (Test-Path -LiteralPath (Join-Path $InstallDirectory 'launcher.exe'))) {
            $localInstallation = Join-Path $PSScriptRoot '..\..\ZeroConvertIME-windows'
            if (Test-Path -LiteralPath (Join-Path $localInstallation 'launcher.exe')) {
                $InstallDirectory = $localInstallation
            }
        }
    }
    $installPath = (Resolve-Path -LiteralPath $InstallDirectory).ProviderPath.TrimEnd('\')
    $launcherPath = Join-Path $installPath 'launcher.exe'
    $startScript = Join-Path $installPath 'Start-ZeroConvertIME.ps1'
    $ctfmonPath = Join-Path $env:SystemRoot 'System32\ctfmon.exe'
    foreach ($required in @($launcherPath, $startScript, $ctfmonPath)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "Required file missing: $required"
        }
    }

    $sessionId = (Get-Process -Id $PID).SessionId
    $installationPrefix = $installPath + '\'
    function Get-InstalledProcess([string]$Name) {
        Get-Process -Name $Name -ErrorAction SilentlyContinue | Where-Object {
            $_.SessionId -eq $sessionId -and $_.Path -and
            $_.Path.StartsWith($installationPrefix, [StringComparison]::OrdinalIgnoreCase)
        }
    }

    Write-Host "[ZeroCon IME] Installation: $installPath"
    Write-Host '[ZeroCon IME] Restart: launcher -> server/UI/model -> ctfmon -> launcher -> ctfmon'
    Write-Host '[ZeroCon IME] This restarts installed binaries; it does not build or copy edited sources.'
    if (-not $PSCmdlet.ShouldProcess($installPath, 'Restart IME and current-session text services')) {
        return
    }

    # ctfmon can run at a higher integrity level. Elevate before stopping any IME
    # processes, so denying the UAC prompt leaves the current IME intact.
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Write-Host '[ZeroCon IME] Windows requires administrator access to restart ctfmon. Accept the UAC prompt.'
        $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -InstallDirectory "{1}" -NoNotification' -f $PSCommandPath, $installPath
        $elevated = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Verb RunAs -WindowStyle Hidden -ArgumentList $arguments -PassThru
        # Start-Process -Wait waits for the process tree, including the newly
        # launched IME. Wait only for the short-lived restart worker instead.
        Write-Host '[ZeroCon IME] Restart in progress...'
        $waitTimer = [Diagnostics.Stopwatch]::StartNew()
        while (-not $elevated.WaitForExit(1000)) {
            if ($waitTimer.Elapsed.TotalSeconds -ge 120) {
                throw "Restart did not report completion within 120 seconds. Check $installPath\restart-ime.log before retrying."
            }
            if ([int]$waitTimer.Elapsed.TotalSeconds % 5 -eq 0) {
                Write-Host "[ZeroCon IME] Waiting for restart ($([int]$waitTimer.Elapsed.TotalSeconds)s)..."
            }
        }
        if ($elevated.ExitCode -ne 0) {
            throw "Elevated restart failed (exit code $($elevated.ExitCode)). See $installPath\restart-ime.log"
        }
        Write-Host "[ZeroCon IME] Restart complete. Details: $installPath\restart-ime.log" -ForegroundColor Green
        Write-Host '[ZeroCon IME] Reopen the input application if the reading overlay is still missing.'
        Show-RestartNotification "IME restart completed. If the reading overlay is still missing, reopen the input application."
        return
    }

    $transcriptStarted = $false
    try {
        Start-Transcript -LiteralPath (Join-Path $installPath 'restart-ime.log') -Force | Out-Null
        $transcriptStarted = $true
    } catch {
        Write-Warning "Could not create restart log: $($_.Exception.Message)"
    }

    $restoreTextServices = $false
    $restoreIme = $false
    try {
        $restoreIme = $true
        # Stop the supervisor first, so it cannot respawn children during shutdown.
        foreach ($name in @('launcher', 'azookey-server', 'ui', 'llama-server')) {
            $processes = @(Get-InstalledProcess $name)
            if ($processes.Count -gt 0) {
                Write-Host "[ZeroCon IME] Stopping $name (PID: $($processes.Id -join ', '))"
                $processes | Stop-Process -Force
                $processes | Wait-Process -Timeout 10 -ErrorAction Stop
            }
        }

        $restoreTextServices = $true
        $textServices = @(Get-Process -Name ctfmon -ErrorAction SilentlyContinue |
            Where-Object { $_.SessionId -eq $sessionId })
        if ($textServices.Count -gt 0) {
            $textServices | Stop-Process -Force
            $textServices | Wait-Process -Timeout 10 -ErrorAction Stop
        }

    } finally {
        # Restore the IME even if stopping a protected process failed midway.
        try {
            if ($restoreIme) {
                & "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File $startScript
                if ($LASTEXITCODE -ne 0) {
                    throw "Start-ZeroConvertIME.ps1 failed with exit code $LASTEXITCODE"
                }
            }
        } finally {
            if ($restoreTextServices) {
                Start-Process -FilePath $ctfmonPath -WindowStyle Hidden
            }
        }
    }

    foreach ($name in @('launcher', 'azookey-server', 'ui')) {
        if (-not (Get-InstalledProcess $name)) {
            throw "Expected process missing from this installation/session: $name"
        }
    }

    Write-Host '[ZeroCon IME] Restart complete. Switch to ZeroCon IME with Win+Space if necessary.' -ForegroundColor Green
    Write-Host '[ZeroCon IME] If the reading overlay is still missing, close and reopen the input application.'
    Write-Host '[ZeroCon IME] This script cannot unload an IME DLL already loaded in another application.'
    Show-RestartNotification "IME restart completed. If the reading overlay is still missing, reopen the input application."
} catch {
    Write-Host "[ZeroCon IME] Restart failed: $($_.Exception.Message)" -ForegroundColor Red
    Show-RestartNotification "IME restart failed: $($_.Exception.Message)" $true
    exit 1
} finally {
    if ($transcriptStarted) {
        Stop-Transcript | Out-Null
    }
}
