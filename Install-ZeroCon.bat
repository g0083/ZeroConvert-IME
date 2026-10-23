@echo off
setlocal
set "installScript=%~dp0windows\scripts\install-with-legacy-removal.ps1"
if not exist "%installScript%" set "installScript=%~dp0install-with-legacy-removal.ps1"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%installScript%" -PackageDirectory "%~dp0."
set "installResult=%ERRORLEVEL%"
echo.
pause
exit /b %installResult%
