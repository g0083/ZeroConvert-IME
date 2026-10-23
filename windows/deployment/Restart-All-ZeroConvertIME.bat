@echo off
setlocal
echo Save or commit any unfinished IME composition before restarting.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Restart-All-ZeroConvertIME.ps1" %*
set "restartResult=%ERRORLEVEL%"
echo.
pause
exit /b %restartResult%
