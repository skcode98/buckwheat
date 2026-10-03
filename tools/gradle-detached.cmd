@echo off
setlocal
if not exist "%TEMP%\opencode" mkdir "%TEMP%\opencode"
set LOG=%TEMP%\opencode\gradle-%RANDOM%.log
start "gradle" /min /D "%~dp0.." cmd /c "gradlew.bat %* > "%LOG%" 2>&1"
echo LOG=%LOG%
endlocal