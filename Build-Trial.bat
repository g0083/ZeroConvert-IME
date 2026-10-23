@echo off
setlocal
echo Save settings edits and commit unfinished IME composition before updating.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\scripts\build-trial.ps1" %*
set "buildResult=%ERRORLEVEL%"
if /I not "%ZEROCON_NO_PAUSE%"=="1" pause
exit /b %buildResult%
