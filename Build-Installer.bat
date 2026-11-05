@echo off
setlocal
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

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\scripts\build-local-installer.ps1"%PASSTHROUGH_ARGS%
set "buildResult=%ERRORLEVEL%"
echo.
if not "%buildResult%"=="0" echo Installer creation failed. See the error above.
if "%NO_PAUSE%"=="0" pause
exit /b %buildResult%
