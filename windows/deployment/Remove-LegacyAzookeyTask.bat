@echo off
echo ===================================================
echo   ZeroCon IME - Remove Legacy Azookey Startup Task
echo ===================================================
echo.
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [INFO] Requesting administrator privileges...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo [INFO] Deleting legacy 'Azookey Startup' task from Task Scheduler...
schtasks /delete /tn "Azookey Startup" /f
if %errorlevel% equ 0 (
    echo [SUCCESS] Legacy task 'Azookey Startup' was successfully removed!
) else (
    echo [INFO] Task was already removed or not found.
)

echo.
echo [INFO] Disabling legacy executables in %%APPDATA%%\Azookey...
powershell -NoProfile -Command ^
    "$oldDir = Join-Path $env:APPDATA 'Azookey';" ^
    "$files = @('ui.exe', 'launcher.exe', 'azookey-server.exe', 'launch.vbs');" ^
    "foreach ($f in $files) {" ^
    "    $p = Join-Path $oldDir $f;" ^
    "    if (Test-Path $p) {" ^
    "        Move-Item -Path $p -Destination ($p + '.old_azookey_backup') -Force -ErrorAction SilentlyContinue;" ^
    "        Write-Host ('  Disabled: ' + $f);" ^
    "    }" ^
    "}"

echo.
echo All legacy Azookey autostart items have been cleaned up.
pause
