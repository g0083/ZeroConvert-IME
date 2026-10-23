@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\scripts\prepare-distribution.ps1" %*
set "buildResult=%ERRORLEVEL%"
if /I not "%ZEROCON_NO_PAUSE%"=="1" pause
exit /b %buildResult%
