@echo off
setlocal
echo Save settings edits and commit unfinished IME composition before applying an update.
echo Local source edits: use Build-Trial.bat alone; it builds AND applies automatically.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\scripts\apply-trial-package.ps1" %*
set "applyResult=%ERRORLEVEL%"
if /I not "%ZEROCON_NO_PAUSE%"=="1" pause
exit /b %applyResult%
