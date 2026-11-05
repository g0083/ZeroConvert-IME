@echo off
setlocal
set "translationShell=powershell.exe"
where pwsh.exe >nul 2>&1
if not errorlevel 1 set "translationShell=pwsh.exe"
set "PSModulePath=%SystemRoot%\System32\WindowsPowerShell\v1.0\Modules;%PSModulePath%"
"%translationShell%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\scripts\start-translation-server.ps1" %*
set "translationResult=%ERRORLEVEL%"
if not "%translationResult%"=="0" pause
exit /b %translationResult%
