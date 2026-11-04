@echo off
setlocal
cd /d "%~dp0"
where pwsh >nul 2>&1
if %errorlevel% equ 0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build-android.ps1 %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-android.ps1 %*
)
exit /b %errorlevel%
