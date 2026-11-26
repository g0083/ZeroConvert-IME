@echo off
setlocal
rem %~dp0 must be captured before the argument loop: shifting past the last
rem argument replaces %0, which would make %~dp0 point at an argument.
set "REPO_ROOT=%~dp0"
set "NO_PAUSE=0"
if /I "%ZEROCON_NO_PAUSE:~0,1%"=="1" set "NO_PAUSE=1"
if /I "%ZEROCON_NO_PAUSE%"=="true" set "NO_PAUSE=1"
if defined CI set "NO_PAUSE=1"

set "PASSTHROUGH_ARGS="
:arg_loop
if "%~1"=="" goto arg_done
if /I "%~1"=="-NoPause" (
    set "NO_PAUSE=1"
) else if /I "%~1"=="/NoPause" (
    set "NO_PAUSE=1"
) else (
    set "PASSTHROUGH_ARGS=%PASSTHROUGH_ARGS% %1"
)
shift
goto arg_loop
:arg_done

where pwsh >nul 2>&1
if %ERRORLEVEL% equ 0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%REPO_ROOT%windows\scripts\prepare-distribution.ps1"%PASSTHROUGH_ARGS%
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%REPO_ROOT%windows\scripts\prepare-distribution.ps1"%PASSTHROUGH_ARGS%
)
set "buildResult=%ERRORLEVEL%"
if "%NO_PAUSE%"=="0" pause
exit /b %buildResult%
