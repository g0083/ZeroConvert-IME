@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-android.ps1 %*
exit /b %errorlevel%
