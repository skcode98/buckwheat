@echo off
echo ========================================
echo   Buckwheat E2E Test Launcher
echo ========================================
echo.
echo Emulator will auto-start if not running.
echo.

set /p clean="Clear app data before test? (y/N): "
if /i "%clean%"=="y" (
    powershell -ExecutionPolicy Bypass -File "%~dp0run.ps1" -Clean -Verbose
) else (
    powershell -ExecutionPolicy Bypass -File "%~dp0run.ps1" -Verbose
)

echo.
echo Press any key to exit...
pause >nul
