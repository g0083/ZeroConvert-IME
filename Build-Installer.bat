@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\scripts\build-local-installer.ps1" %*
set "buildResult=%ERRORLEVEL%"
echo.
if not "%buildResult%"=="0" echo Installer creation failed. See the error above.
if /I not "%ZEROCON_NO_PAUSE%"=="1" pause
exit /b %buildResult%
