<#
.SYNOPSIS
    Register ZeroCon IME auto-start (Task Scheduler + HKCU Run + Startup folder).

.DESCRIPTION
    - Task Scheduler : per-USER task (no administrator rights needed).
        * AtLogOn (delayed 10s so that the D: drive / Explorer / WebView2 are ready)
        * Repeats every 5 minutes. launcher.exe holds a per-session named pipe, so a
          second instance exits immediately, while a crashed / killed launcher is
          revived within 5 minutes (MultipleInstancesPolicy = IgnoreNew).
    - HKCU Run + StartupApproved : fail-safe, forced to "enabled".
    - Startup-folder shortcut    : fail-safe.

    The previous version used a "Users group" principal (S-1-5-32-545), which needs
    administrator rights; when the install script was run without elevation the task
    was silently NOT created (errors were hidden) and only the Run key remained.
    If that Run entry was skipped/disabled, the IME did not start after boot.
#>
param(
    [string]$TaskName = "ZeroConvert Startup"
)

$ErrorActionPreference = "Stop"
$scriptDir    = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcherPath = Join-Path $scriptDir "launcher.exe"
if (-not (Test-Path $launcherPath)) {
    throw "launcher.exe not found: $launcherPath"
}

$ok = $true
$userId = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name

# --- 1. Task Scheduler (current user) -------------------------------------
try {
    $action  = New-ScheduledTaskAction -Execute $launcherPath -WorkingDirectory $scriptDir
    $logon   = New-ScheduledTaskTrigger -AtLogOn -User $userId
    $logon.Delay = "PT10S"
    $repeat  = New-ScheduledTaskTrigger -Once -At (Get-Date).Date `
                  -RepetitionInterval (New-TimeSpan -Minutes 5)
    $settings = New-ScheduledTaskSettingsSet `
                  -MultipleInstances IgnoreNew `
                  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                  -StartWhenAvailable `
                  -ExecutionTimeLimit ([TimeSpan]::Zero) `
                  -Hidden:$false
    $principal = New-ScheduledTaskPrincipal -UserId $userId -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger @($logon, $repeat) `
        -Settings $settings -Principal $principal -Force | Out-Null

    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
    Write-Host "  -> Task Scheduler : '$TaskName' registered (state=$($task.State), user=$userId)" -ForegroundColor Green
} catch {
    $ok = $false
    Write-Warning "  -> Task Scheduler registration FAILED: $($_.Exception.Message)"
}

# --- 2. HKCU Run + StartupApproved ----------------------------------------
try {
    $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    Set-ItemProperty -Path $runKey -Name "ZeroConvertIME" -Value "`"$launcherPath`""
    $approved = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run"
    if (-not (Test-Path $approved)) { New-Item -Path $approved -Force | Out-Null }
    Set-ItemProperty -Path $approved -Name "ZeroConvertIME" `
        -Value ([byte[]](2,0,0,0,0,0,0,0,0,0,0,0)) -Type Binary
    Write-Host "  -> HKCU Run       : registered and approved" -ForegroundColor Green
} catch {
    $ok = $false
    Write-Warning "  -> HKCU Run registration FAILED: $($_.Exception.Message)"
}

# --- 3. Startup folder shortcut -------------------------------------------
try {
    $startupFolder = [Environment]::GetFolderPath("Startup")
    $shortcutPath  = Join-Path $startupFolder "ZeroConvertIME.lnk"
    $wsh = New-Object -ComObject WScript.Shell
    $sc  = $wsh.CreateShortcut($shortcutPath)
    $sc.TargetPath       = $launcherPath
    $sc.WorkingDirectory = $scriptDir
    $sc.Description      = "ZeroCon IME Server Launcher"
    $ico = Join-Path $scriptDir "zeroconvert.ico"
    if (Test-Path $ico) { $sc.IconLocation = "$ico,0" }
    $sc.Save()
    Write-Host "  -> Startup folder : $shortcutPath" -ForegroundColor Green
} catch {
    $ok = $false
    Write-Warning "  -> Startup shortcut FAILED: $($_.Exception.Message)"
}

if (-not $ok) { exit 1 }
