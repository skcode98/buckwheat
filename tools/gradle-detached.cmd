@echo off
REM Usage: tools\gradle-detached.cmd <gradle args>
REM Prints LOG=<path> and PID=<n>, then returns immediately.
REM The log file may not exist yet: the build runs in its own console.
REM Poll LOG every 5s until it contains a line starting with EXIT=; that line carries
REM the build exit code. No EXIT= line yet means the build is still running.
REM Hard timeout 10 minutes: taskkill /PID <n> /T /F kills this build and its
REM children only, leaving unrelated Gradle daemons alone, then report TIMEOUT.
setlocal

set "LOGDIR=C:\Users\suraj\AppData\Local\Temp\opencode"
if not exist "C:\Users\suraj\AppData\Local\Temp\" if defined TEMP set "LOGDIR=%TEMP%\opencode"

if not exist "%LOGDIR%\" mkdir "%LOGDIR%" >nul 2>&1
if not exist "%LOGDIR%\" (
  echo ERROR: cannot create log directory "%LOGDIR%" 1>&2
  exit /b 1
)

set "GD_LOGDIR=%LOGDIR%"
set "GD_ARGS=%*"
set "GD_SHELL=%COMSPEC%"
set "GD_WD=%~dp0.."

powershell -NoProfile -Command "$d=$env:GD_LOGDIR;$g=[guid]::NewGuid().ToString('N');$l=Join-Path $d ('gradle-'+$g+'.log');$q=[char]34;$a='gradlew.bat '+$env:GD_ARGS+' > '+$q+$l+$q+' 2>&1 & echo EXIT=!errorlevel!>>'+$q+$l+$q;$s=New-Object System.Diagnostics.ProcessStartInfo;$s.FileName=$env:GD_SHELL;$s.Arguments='/v:on /c '+$a;$s.UseShellExecute=$true;$s.WindowStyle=[System.Diagnostics.ProcessWindowStyle]::Minimized;$s.WorkingDirectory=$env:GD_WD;$p=[System.Diagnostics.Process]::Start($s);Set-Content -LiteralPath ($l+'.pid') -Value $p.Id -NoNewline;('LOG='+$l);('PID='+$p.Id)"

if errorlevel 1 (
  echo ERROR: failed to start Gradle, no log path was produced 1>&2
  exit /b 1
)

endlocal
