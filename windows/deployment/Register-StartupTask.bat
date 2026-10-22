@echo off
setlocal
echo ========================================================
echo   ZeroConvert IME - Startup Auto-Launch Registration
echo ========================================================
echo.
echo No administrator rights are required (per-user task).
echo.

cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Register-StartupTask.ps1"
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Registration reported errors. See messages above.
    pause
    exit /b 1
)

echo.
echo [Test] Launching via Task Scheduler...
schtasks /Run /TN "ZeroConvert Startup"

echo.
echo ========================================================
echo Registration Complete!
echo launcher.exe will start at every logon and is re-checked every 5 minutes.
echo ========================================================
echo.
pause
