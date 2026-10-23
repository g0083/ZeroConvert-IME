@echo off
setlocal
set "diagnosticScript=%~dp0windows\scripts\diagnose-ime-install.ps1"
if not exist "%diagnosticScript%" set "diagnosticScript=%~dp0diagnose-ime-install.ps1"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%diagnosticScript%" -OutputDirectory "%~dp0."
set "diagnosticResult=%ERRORLEVEL%"
echo.
pause
exit /b %diagnosticResult%
