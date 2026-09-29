# AGENTS.md

## Build rule (mandatory)
When running any Gradle build (`.\gradlew.bat assembleDebug`, `testDebugUnitTest`, etc.):
- Start build, redirect output to a log file in `C:\Users\suraj\AppData\Local\Temp\opencode\`
- Poll the log every 5 seconds
- Hard timeout 10 minutes total; if build still running after 10 min, kill it and report TIMEOUT
- Never run `gradlew` directly in a blocking foreground tool call without this monitoring