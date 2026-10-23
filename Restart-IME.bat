@echo off
setlocal
echo Save or commit any unfinished IME composition before restarting.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\deployment\Restart-All-ZeroConvertIME.ps1" -InstallDirectory "%~dp0ZeroConvertIME-windows" %*
set "restartResult=%ERRORLEVEL%"
echo.
pause
exit /b %restartResult%
