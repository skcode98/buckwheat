# AGENTS.md

## Build rule (mandatory)
When running any Gradle build (`.\gradlew.bat assembleDebug`, `testDebugUnitTest`, etc.):
- Required entry point: `tools\gradle-detached.cmd <gradle args>` (repo root). Never invoke `gradlew` directly.
- Start build, redirect output to a log file in `C:\Users\suraj\AppData\Local\Temp\opencode\`
- It prints `LOG=<path>` and `PID=<n>`; the log file may not exist yet, because the build runs detached
- Poll the log every 5 seconds until it contains a line starting with `EXIT=`, which carries the build's exit code
- Hard timeout 10 minutes total; if build still running after 10 min, kill it and report TIMEOUT
- Kill it with `taskkill /PID <n> /T /F` using the printed PID; that kills only this build and its children, never unrelated Gradle daemons
- Never run `gradlew` directly in a blocking foreground tool call without this monitoring