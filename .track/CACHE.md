# Cache — Quick Reference

## Build Commands
```powershell
# Quick compile check (faster than full build)
.\gradlew.bat :app:compileDebugKotlin

# Full debug build (always run this after changes)
.\gradlew.bat assembleDebug

# Clean build
.\gradlew.bat clean assembleDebug

# Run tests
.\gradlew.bat testDebug

# Lint
.\gradlew.bat lintDebug

# Spotless format check
.\gradlew.bat spotlessCheck
```

## Gradle on this machine (IMPORTANT — flaky daemons)
- **Full builds are SLOW (~30 min on a cold daemon)** — `compileDebugKotlin` alone can take 15+ min when the Kotlin compile daemon has to start cold.
- The opencode shell tool **kills the process tree when a command times out**, which often takes the Gradle daemon down mid-compile. A killed-but-alive daemon left in "busy" state will make later `gradlew` invocations hang or start yet another daemon ("1 busy ... could not be reused").
- **Workaround**: run the build **detached** and poll the log instead of blocking:
  ```powershell
  $log = "$env:TEMP\buckwheat_gradle_out.log"
  Remove-Item $log -ErrorAction SilentlyContinue
  $inner = "gradlew.bat :app:spotlessApply :app:testDebugUnitTest :app:assembleDebug > `"$log`" 2>&1"
  $p = Start-Process -FilePath "cmd.exe" -ArgumentList '/c',$inner -WorkingDirectory "D:\Just-try\buckwheat" -PassThru
  $p.Id | Out-File "$env:TEMP\buckwheat_gradle.pid"
  ```
  then poll `Get-Content $log -Tail 4` every ~100 s until `BUILD SUCCESSFUL`/`FAILED`. The detached daemon survives tool-call kills.
- **Never run two detached builds at once** (they spawn competing daemons that fight over locks). If a run stalls, `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` → `Get-Process java | Stop-Process -Force` to reset.
- **Avoid** `Start-Process -RedirectStandardOutput` with a `.bat` — it hangs. Use `cmd /c "gradlew.bat ... > log 2>&1"` instead.
- Test results XML: `app\build\test-results\testDebugUnitTest\*.xml` (per-suite `tests=`/`failures=`).

## Key File Paths (Upstream Master)
| Purpose | Path |
|---------|------|
| Main Activity | `app/.../MainActivity.kt` |
| App ViewModel | `app/.../data/AppViewModel.kt` |
| Spends ViewModel | `app/.../data/SpendsViewModel.kt` |
| Editor ViewModel | `app/.../editor/EditorViewModel.kt` |
| Main Repository | `app/.../di/SpendsRepository.kt` |
| Settings Repository | `app/.../di/SettingsRepository.kt` |
| Room Database | `app/.../di/DatabaseModule.kt` |
| Hilt Module | `app/.../di/AppModule.kt` |
| Transaction DAO | `app/.../data/dao/TransactionDao.kt` |
| Storage DAO | `app/.../data/dao/StorageDao.kt` |
| Keyboard | `app/.../keyboard/Keyboard.kt` |
| Voice Input Parser | `app/.../keyboard/VoiceInputParser.kt` |
| Voice AI Parser | `app/.../keyboard/VoiceAi.kt` |
| Voice AI Settings Sheet | `app/.../settings/VoiceAiSettingsSheet.kt` |
| Voice Widget Commit Service | `app/.../widget/voice/VoiceWidgetCommitService.kt` |
| Voice Widget Design Setting | `app/.../settings/VoiceWidgetDesignSetting.kt` |
| Midnight Widget Refresh Scheduler | `app/.../widget/WidgetRefreshScheduler.kt` |
| Midnight Widget Refresh Receiver | `app/.../widget/WidgetRefreshReceiver.kt` |
| Recalc Budget VM | `app/.../recalcBudget/RecalcBudgetViewModel.kt` |
| Voice Parser Tests | `app/src/test/java/.../keyboard/VoiceInputParserTest.kt` |
| Budget Constructor | `app/.../wallet/BudgetConstructor.kt` |
| Wallet | `app/.../wallet/Wallet.kt` |
| Bottom Sheets | `app/.../home/BottomSheets.kt` |
| Theme | `app/.../ui/Theme.kt` |
| Locale | `app/.../ui/Locale.kt` |
| Export CSV | `app/.../wallet/rememberExportCSV.kt` |
| Currency Editor | `app/.../wallet/CurrencyEditor.kt` |
| Goals Sheet | `app/.../settings/GoalsSheet.kt` |
| Goals VM | `app/.../settings/GoalsViewModel.kt` |
| Recurring Payments Sheet | `app/.../settings/RecurringPaymentsSheet.kt` |
| Recurring Payments VM | `app/.../settings/RecurringPaymentsViewModel.kt` |
| Tags Management Sheet | `app/.../settings/TagsManagementSheet.kt` |
| Tags Management VM | `app/.../settings/TagsManagementViewModel.kt` |
| SavingsGoal DAO | `app/.../data/dao/SavingsGoalDao.kt` |
| Recurring DAO | `app/.../data/dao/RecurringDao.kt` |
| BudgetPeriod DAO | `app/.../data/dao/BudgetPeriodDao.kt` |
| RecurringTemplate entity | `app/.../data/entities/RecurringTemplate.kt` |
| Recurring due alerts scheduler | `app/.../notifications/RecurringPaymentAlertScheduler.kt` |
| Recurring due alerts receiver | `app/.../notifications/RecurringPaymentAlertReceiver.kt` |
| Recurring due text builder | `app/.../notifications/RecurringDueContent.kt` |
| Recurring alert settings UI | `app/.../settings/RecurringPaymentAlertSetting.kt` |
| Recurring due content tests | `app/src/test/java/.../notifications/RecurringDueContentTest.kt` |
| Goal progress nudge logic | `app/.../settings/GoalProgressNudge.kt` |
| Goal progress notifier | `app/.../notifications/GoalProgressNotifier.kt` |
| Goal nudge tests | `app/src/test/java/.../notifications/GoalProgressNudgeTest.kt` |
| Spend digest content builder | `app/.../notifications/SpendDigestContent.kt` |
| Spend digest scheduler | `app/.../notifications/SpendDigestScheduler.kt` |
| Spend digest receiver | `app/.../notifications/SpendDigestReceiver.kt` |
| Spend digest settings UI | `app/.../settings/SpendDigestSetting.kt` |
| Spend digest content tests | `app/src/test/java/.../notifications/SpendDigestContentTest.kt` |
| Category drill-down helper | `app/.../data/categories/SpendCategorizer.kt` (`transactionMatchesCategory`) |
| Category drill-down sheet | `app/.../analytics/ViewerHistory.kt` (`CATEGORY_HISTORY_SHEET` const + `onlyCategoryKey`) |
| Category chips (tappable) | `app/.../analytics/categoriesChart/TagAmount.kt` + `SpendCategoriesCard.kt` (`onCategoryClick`) |
| SavingsGoal entity | `app/.../data/entities/SavingsGoal.kt` |
| BudgetPeriod entity | `app/.../data/entities/BudgetPeriod.kt` |
| ArchivedTransaction entity | `app/.../data/entities/ArchivedTransaction.kt` |
| Daily reminder scheduler | `app/.../notifications/DailyBudgetReminderScheduler.kt` |
| Daily reminder receiver | `app/.../notifications/DailyBudgetReminderReceiver.kt` |
| Reminder message builder | `app/.../notifications/DailyReminderContent.kt` |
| Boot reschedule receiver | `app/.../notifications/ReminderBootReceiver.kt` |
| Reminder settings UI | `app/.../settings/DailyBudgetReminderSetting.kt` |
| Reminder content tests | `app/src/test/java/.../notifications/DailyReminderContentTest.kt` |
| Backup codec | `app/.../backup/BackupData.kt` |
| Backup repository | `app/.../di/BackupRepository.kt` |
| Backup/restore UI | `app/.../settings/BackupRestoreSetting.kt` |
| Backup/restore VM | `app/.../settings/BackupRestoreViewModel.kt` |
| Backup codec tests | `app/src/test/java/.../backup/BackupDataTest.kt` |
| Backup repo tests | `app/src/test/java/.../di/BackupRepositoryTest.kt` |
| Share summary | `app/.../analytics/ShareSummary.kt` |
| Share summary tests | `app/src/test/java/.../analytics/ShareSummaryTest.kt` |
| Category selector (editor) | `app/.../editor/category/CategorySelector.kt` |
| SpendCategory enum | `app/.../data/categories/SpendCategory.kt` |
| Spend Categorizer (offline + AI) | `app/.../data/categories/SpendCategorizer.kt` |
| Categories VM | `app/.../data/categories/SpendCategoriesViewModel.kt` |
| Categories Card (analytics) | `app/.../analytics/categoriesChart/SpendCategoriesCard.kt` |
| Monthly trend card (analytics) | `app/.../analytics/SpendsTrendCard.kt` |
| Monthly trend tests | `app/src/test/java/.../analytics/SpendsTrendTest.kt` |
| Weekday breakdown card (analytics) | `app/.../analytics/SpendsWeekdayCard.kt` |
| Weekday breakdown tests | `app/src/test/java/.../analytics/SpendsWeekdayTest.kt` |
| Compare-to-last-period card | `app/.../analytics/CompareToLastPeriodCard.kt` |
| Compare-to-last-period tests | `app/src/test/java/.../analytics/CompareToLastPeriodTest.kt` |
| Overspending notifier | `app/.../notifications/OverspendingNotifier.kt` |
| Overspend notification setting | `app/.../settings/OverspendNotificationSetting.kt` |
| Emoji picker | `app/.../base/EmojiPicker.kt` |
| SavedCategory entity | `app/.../data/entities/SavedCategory.kt` |
| SavedCategory DAO | `app/.../data/dao/SavedCategoryDao.kt` |
| Categories management sheet/VM | `app/.../settings/CategoriesManagementSheet.kt` / `.../CategoriesManagementViewModel.kt` |
| Categorizer Tests | `app/src/test/java/.../data/categories/SpendCategorizerTest.kt` |
| Manifest | `app/.../AndroidManifest.xml` |
| Gradle (app) | `app/build.gradle.kts` |
| Gradle (root) | `build.gradle.kts` |

## Emulator UI Automation (API 36, 1280×2856)
- Editor sheet top edge ~y=1552 when collapsed; swipes crossing it re-expand the sheet (wallet scroll is fragile)
- **Reliable route to Analytics**: toggle debug via keyboard `"0"`×8, then `"."`, then the apply column (bottom-right vertical button, bounds ~[957,2010][1220,2772]) → snackbar "Debug ON" (persisted in settings DataStore `debug` key); relaunch app, debug icon appears top-left of editor ([33,189][177,333]); tap it → DebugMenu → "Open period summary screen" → Analytics (with the full-month `SpendsCalendar`)
- Keyboard key bounds (1280×2856): 0=[60,2542][622,2772], .=[658,2542][921,2772], apply/delete column=[957,2010][1220,2772]
- `uiautomator dump` can transiently fail with "null root node" — retry after 1s; text assertions limited since most Compose elements expose no text
- **Screenshot capture (PowerShell)**: PowerShell `>` redirection and `[byte[]]` casts corrupt the PNG ("Parameter is not valid") — use `cmd /c "\"<adb>\" exec-out screencap -p > \"<file>\""` and read it with System.Drawing. The image/read tools only say "Image read successfully" (model can't see pixels) — verify by structured `GetPixel` scans (dump non-background runs per row, e.g. pill surface `238,240,255` = background).
- **Placing a widget on the home screen via adb/uiautomator**: KEYCODE_HOME → long-press empty space (swipe 640 1200 640 1200 800) → tap "Widgets" → tap the app row → the drag MUST start on the widget **preview image** (not its text label, which sits ~below it): `input motionevent DOWN x y` → wait ~1.5s (long-press detach; the picker closes and the home screen appears) → several `MOVE` steps → `UP`. `input draganddrop` scrolls the picker instead of detaching — use motionevent staging. Verify binding: `dumpsys appwidget` (host=`nexuslauncher`, provider=`VoiceWidgetReceiver`). Force a re-render: `adb shell am broadcast -a updateAction -n com.danilkinkin.buckwheat/.widget.voice.VoiceWidgetReceiver`. Glance paints text as bitmaps, so uiautomator sees no text; pixel-sample the widget band (PowerShell System.Drawing) for content colors. Watch `logcat -s VoiceWidget` / `E GlanceAppWidget: Error` for composition failures. **On-device E2E is the authoritative widget check — Robolectric `widget.update()` never runs `provideGlance` (false-green).**
- **Voice widget chart E2E (API 36, 1280×2856)**: band `x[344..1202] y[572..902]`; the theme primary renders blue `73,93,146` on this emulator (dynamic color). Chart verified by scanning x[398..1039]: goal-guard line (onSurfaceVariant 55%) at y≈751 full width; bars at equal slot centers — e.g. a 3-day period (clamped window) shows bars at x≈505/721/933 with `combineColors` blue→red and today's zero bar as a 2.dp onSurface-18% stub; amount text (18sp primary) ~y700, "For today" caption ~y650, min/max captions ~y826-844, bottom-left blue wedge = wallpaper through the 48.dp rounded corner (not a bug).
- **Voice widget layout (committed `75e12c2`, big-percent design)**: caption "Left today" (10sp gray) → hero = **% of daily budget remaining** (primary, 18sp bold) → "of ₹X daily" (9sp gray, string `voice_widget_of_daily`) → 3dp rounded progress bar → 26dp min/max-spike chart → "Budget ₹X · Spent ₹Y · Left ₹Z" summary (9sp gray). Percent + bar fraction = `dailyRemaining / dailyBudget` where `dailyRemaining = (dailyBudget − series.last()).coerceAtLeast(0)`. On-device (API 36, 5×2): caption y622-642, hero y672-702, sub y732-764, bar y772-778 (fill to x1039 = full width when today's spend is 0), chart y791-866 (red max ring ≈(398,791), blue min ring ≈(713,842), today white dot ≈(1040,859)), summary y858-903. **PITFALL: Glance 1.1.1 `GlanceModifier.defaultWeight()` takes NO weight arg** — a weighted two-Box progress Row won't compile; draw bars into a bitmap (`drawProgressBarBitmap(context, widthDp, heightDp, fraction, fillColor, trackColor)` → drawRoundRect) and use `ContentScale.FillBounds`, exactly like `drawChartBitmap`.
- **Voice widget Graph-background design (committed `053dbc2`, pushed)**: 4th design `VoiceWidgetDesign.GRAPH_BG`. Whole surface = `drawStatusBackgroundBitmap` (300×120dp): pastel status base `statusColor(remainingFraction)` green `C9E7C5`→amber `FFE3B0`→red `FFC9C6`, chart area filled in `statusDeepColor(fraction)` @35% (deep ramp `4C8B48`→`D99A2B`→`D5534C`), dashed goal line @55%, ring+dot today marker in `2A2A2A`. Foreground: caption/`of daily`/summary `464646`, hero `$remainingPercent%` `2A2A2A` 20sp bold, ADDED caption `1B7A2F`. Background bitmap only used when `isGraphBg && stateSet`, else static preview pill (16dp-corner fix preserved); mid-column `VoiceWidgetChart` skipped. `combineColors(list, t)` = existing multi-stop blend helper. **E2E-verified on API 36** (fresh install, switch in Settings, ~8s re-render): widget `dailyBudget` = `voice-daily-budget-key` = the app's `dailyBudget` (`160.97` = ₹5000/32-day period distributed; note `spentFromDailyBudget` = 0 so remainingFraction = 1.0 → pure green). Pixel targets: base `201,231,197`, chart fill `158,199,153`, goal-line dash at top y=577 `133,180,129` (goalFraction = 1.0 → line at full height), hero "100%" dark, mic `73,93,146`. Percent-left regression: 0 green pixels → static pill restored.
- **Voice widget chart (committed `8c4ead0`, redesign `492d7c8`)**: drawn into a bitmap (Glance 1.1.1 has NO canvas/path composables — `androidx.glance.canvas` absent from the jars; `ContentScale` = `{ Fit, Crop, FillBounds }` only) via `drawChartBitmap(context, designWidthDp=280, chartHeightDp=26, ...)` in `VoiceWidget.kt`, rendered as `Image(provider=ImageProvider(bitmap), contentScale=ContentScale.FillBounds)`. Design palette: top `colorMax` (0xFFDD1414) → bottom `colorMin` (0xFF185ED6), night alphas 0.55/0.38/0.20, light 0.35/0.20/0.08; dashed goal line via `DashPathEffect(6dp,4dp)`; today marker = 8dp halo @0.2 + 4.5dp ring stroke 2dp + 3dp white dot on `fractions.last()`; **range markers** (since `492d7c8`) = colored ring 3.5dp stroke 1.5dp + 2dp white dot on the min/max days of `series.dropLast(1)` (past only; max = topColor, min = bottomColor; drawn before the today marker). Layout positions are shifted down ~46px by the added sub-line + progress bar since `75e12c2` (see the layout entry above for current y-ranges; pre-`75e12c2`: goal line ≈ y744, today marker ≈ (1040,812), red max ≈(398,745)-(411,769), blue min ≈(713,796)-(730,817), marker center = baseline).
- **Voice widget feedback E2E (API 36)**: mic button circle ≈ x[1048..1177] y[670..800] (center ≈ (1112,735)). Tap it → capture +1s: LISTENING ring (primary 25% over surface ≈ `167,176,208`) + inner primary circle + white `ic_equalizer` glyph, caption changes. On the emulator the recognizer is unavailable (`Connection to speech recognition service lost, no #startListening`) → the failure path resets the widget to IDLE and posts the failure notification; `logcat` shows FGS start (`ForegroundServiceTypeLoggerModule` for UID 10217). ADDED (green) / PROCESSING visuals need a real speech service — verify via unit tests (`VoiceWidgetCommitTest`). **Add-another behavior (committed `e8162a2`)**: the ADDED green check is now clickable (`actionRunCallback<VoiceWidgetMicCallback>()` → starts a fresh voice input) and `scheduleFeedbackReset(context)` auto-resets ADDED → IDLE ~4s after a successful commit (only while still ADDED). On-device ADDED E2E still needs a real speech service; user should confirm: wait ~4s after saying a spend → green check reverts to mic; tapping the green check immediately restarts listening.
- **Voice widget state keys** live in the Glance `PreferencesGlanceStateDefinition` datastore (NOT budgetDataStore), written by the shared `CommonWidgetReceiver.observeData` block for every widget: `voice-chart-series-key` (comma-joined plain BigDecimal, last 7 days clamped to period start), `voice-daily-budget-key`, `voice-feedback-state-key` (IDLE/LISTENING/PROCESSING/ADDED), `voice-feedback-text-key` (committed amount for the ADDED caption), plus the existing `stateBudgetPreferenceKey`/`todayBudgetPreferenceKey`/`currencyPreferenceKey`.
- **Voice widget permission-flow E2E (API 36, 1280×2856)**: the widget is a Row — left column = amount text, right end = 48dp circular mic button. Band `x[370..1205] y[575..900]`; scan for the primary color `73,93,146` → the rightmost solid cluster (row y=738: runs `1016..1091` + `1120..1159` around the white mic icon) → button center ≈ `(1087,738)`. To test the permission fix with BOTH `RECORD_AUDIO` + `POST_NOTIFICATIONS` revoked (`pm revoke pkg android.permission.POST_NOTIFICATIONS`): tap the mic → logcat must show `START ...cmp=com.danilkinkin.buckwheat/.MainActivity (has extras)` then `act=android.content.pm.action.REQUEST_PERMISSIONS ... GrantPermissionsActivity`; `uiautomator dump` shows the RECORD_AUDIO dialog first ("record audio?", "While using the app" bounds `[152,1351][1128,1519]`), grant → the POST_NOTIFICATIONS dialog follows ("Allow Buckwheat to send you notifications?", "Allow" `[152,1477][1128,1645]`). With both granted, tapping the mic must log `Background started FGS: Allowed ... allowWiu:52` and the result notification appears as `id=301 channel=voice_widget importance=3` in `dumpsys notification --noredact`. `pm grant`/`pm revoke` persist across `adb install -r` (RECORD_AUDIO does NOT — re-grant or re-revoke after reinstall).

## Git Workflow
```powershell
# Our fixes are on the 'our-fixes' branch (origin)
git checkout our-fixes    # to access saved work

# Fresh start on master
git checkout master
git pull upstream master
git push origin master
```

## App Versions
| Library | Version |
|---------|---------|
| Kotlin | 2.2.0 |
| AGP | 8.7.3 (downgraded from 8.11.1 for Android Studio Narwhal 2024.2.1) |
| Hilt | 2.57 |
| Room | 2.7.2 |
| Compose BOM | 1.8.3 |
| Material3 | 1.3.2 |
| DataStore | 1.1.7 |
| Min SDK | 29 |
| Target SDK | 36 |
| Compile SDK | 36 |
| App Version | 4.8.0 (versionCode 29) |

## Common Imports
```kotlin
import androidx.lifecycle.asFlow            // LiveData → Flow
import androidx.lifecycle.asLiveData         // Flow → LiveData
import androidx.lifecycle.livedata            // build LiveData from Flow
import kotlinx.coroutines.flow.first         // Flow.first() (suspend)
import kotlinx.coroutines.flow.map           // Flow.map
import kotlinx.coroutines.launch             // coroutineScope.launch
import androidx.compose.runtime.livedata.observeAsState  // Compose observation
```

## Key DataStore Keys (budgetDataStore)
- `budget` — current budget (String)
- `spent` — total spent (String)
- `dailyBudget` — daily budget (String)
- `spentFromDailyBudget` — spent from daily budget (String)
- `startPeriodDate` — period start (Long ms)
- `finishPeriodDate` — period finish (Long ms)
- `finishPeriodActualDate` — actual finish (Long ms, set when budget finished early)
- `lastChangeDailyBudgetDate` — last daily budget update (Long ms; also written by ASK one-shot handling)
- `lastRecurringAppliedDate` — last day recurring payments were applied (Long ms; backfill marker)
- `currency` — currency code (String)
- `restedBudgetDistributionMethod` — overspend handling (String)
- `hideOverspendingWarn` — overspend warning flag (Boolean)
- `overspendNotifiedStoreKey` — last overspend-crossing flag (Boolean; true while over; resynced to `spentFromDailyBudget > dailyBudget` inside every `edit {}` that mutates today's counter/budget — `addSpent`, `removeSpent` today-branch, `updateDailyBudget`, `setDailyBudget`; never cross it with `edit {}` blocks)
- `knownTags` — pipe-separated known tags (String)

## Key DataStore Keys (settingsDataStore)
- `theme` — ThemeMode name (String)
- `locale` — locale code (String)
- `TUTOR_*` — tutorial stage booleans
- `autoBackupInterval` — backup interval (Int)
- `debug` — debug-mode flag (String; toggled via keyboard "0"×8 + "." + apply)
- `voiceAiApiKeyStoreKey` — Voice AI / category AI API key (String; **deliberately excluded from backup exports** — `asBackupMap()` skips it, so restore never restores or clobbers the key)
- `voiceAiProviderUrlStoreKey` — OpenAI-compatible base URL (String; blank → `DEFAULT_VOICE_AI_PROVIDER_URL` = `https://openrouter.ai/api/v1/chat/completions`)
- `voiceAiModelStoreKey` — model name (String; blank or legacy `nvidia/nemotron-3-ultra-550b-a55b:free` → `DEFAULT_VOICE_AI_MODEL` = `openai/gpt-oss-20b:free`, via `normalizeVoiceAiModel` in `SettingsRepository.kt` since `96feeec`)
- `reminderEnabledStoreKey` — daily budget reminder toggle (Boolean)
- `reminderHourStoreKey` / `reminderMinuteStoreKey` — reminder time (Int); the daily reminder is a one-shot `setWindow` (10-min window) that `DailyBudgetReminderReceiver.rearmNextDay` re-arms at the stored time
- `recurringAlertEnabledStoreKey` / `recurringAlertHourStoreKey` / `recurringAlertMinuteStoreKey` — recurring-payment due-alert toggle + time (Int), defaults 09:00 (`RECURRING_ALERT_DEFAULT_HOUR/MINUTE`); one-shot `setWindow` + `rearmNextDay`
- `spendDigestEnabledStoreKey` (Boolean) / `spendDigestFrequencyStoreKey` (String `WEEKLY`/`MONTHLY`) / `spendDigestHourStoreKey` / `spendDigestMinuteStoreKey` (Int, default 20:00 `SPEND_DIGEST_DEFAULT_HOUR/MINUTE`) — weekly/monthly spend digest; one-shot `setWindow` re-armed +7 days / +1 calendar month by `SpendDigestReceiver.rearm`
- `goalMilestonesNotifiedStoreKey` — per-goal already-notified milestone buckets, serialized `"goalId:bucket;goalId:bucket"` (String; write via `getGoalNotifiedMilestones()`/`setGoalNotifiedMilestones()`)
- `overspendNotifyEnabledStoreKey` — instant overspend notification opt-in (Boolean)
