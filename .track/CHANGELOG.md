# Changelog

## [Unreleased]

### Added
- Date/time pill now visible and editable on fresh entry (ADD mode) — `Editor.kt:50`, `DateTimeEditPill.kt:29`
- New spends in ADD mode use `editorViewModel.currentDate` instead of `Date()` — `Keyboard.kt:317`
- **Voice input feature** — microphone button on the keyboard:
  - New `VoiceInputParser.kt` — parses natural language (e.g. "tea 20 now", "lunch 150 yesterday at 2pm") into amount, comment, and date
  - New `ic_mic.xml` microphone vector icon
  - `RECORD_AUDIO` permission in `AndroidManifest.xml`
  - Mic bar at top of keyboard with speech recognition via `SpeechRecognizer`
  - Auto-parses transcription, fills editor fields, and commits the transaction immediately
- Finish date selector now allows past dates (`disableBeforeDate = null`) — `FinishDateSelector.kt:47`
- **Analytics calendar** — heatmap now shows one month at a time with `<` `>` navigation; each day is clickable. Clicking a day closes the analytics sheet and opens the editor with that date pre-set (uses `EditorViewModel` directly via shared Hilt scope)
- **Goal tracking** — `SavingsGoal` entity, DAO, bottom sheet (`GoalsSheet`), ViewModel (`GoalsViewModel`):
  - CRUD for savings goals with target amount and current progress
  - Linear progress indicator per goal
  - Manual allocation dialog (creates a SPENT transaction with the goal name)
- **Recurring payments** — `RecurringTemplate` entity, DAO, bottom sheet (`RecurringPaymentsSheet`), ViewModel (`RecurringPaymentsViewModel`):
  - CRUD for recurring expense templates with amount, comment, day-of-month
  - Enable/disable toggle per template
  - Auto-processed on day change in `SpendsViewModel.runChangeDayAction()`
- **Period-scoped analytics** — `Analytics.kt` and `History.kt` now use `periodSpends`/`periodTransactions` (filtered to current budget period) instead of all transactions
- **Date range queries** — `TransactionDao` now exposes `getAll(type, startDate, endDate)` and `getAll(startDate, endDate)` for period scoping
- **Tag management merge** — `TagsManagementSheet` now shows transaction-derived tags alongside saved tags; `TagItem` data class merges both sources

### Changed
- Fresh fork from upstream/master @ `4b60102` — clean slate
- All prior work saved to `our-fixes` branch
- Restored CI build workflow (`.github/workflows/build.yml`)
- Added `.track/` directory with AGENTS.md, CHANGELOG.md, ARCHITECTURE.md, MEMORY.md, CACHE.md, CODE_FLOW.md
- Added session compaction fix plugin (`.opencode/plugins/compaction-fix.ts`) — preserves task state, decisions, and context across opencode session compactions
- Added `.track/.session-state.json` — auto-managed state snapshot for compaction recovery
- Updated `.track/AGENTS.md` section 8 with compaction recovery protocol
- Updated `.opencode/skills/buckwheat/SKILL.md` with compaction recovery section
- Added `.track/.session-state.json` to `.gitignore`
- Added tag management feature (persistent tags via Room `saved_tags` table):
  - New `SavedTag` entity + `SavedTagDao` for persistent tag storage
  - Database migration 5→6 creates `saved_tags` table
  - New `TagsManagementSheet` bottom sheet with scrollable CRUD list (add, edit, delete)
  - "Tag Management" option added to Settings page (opens via gear icon → Settings)
  - Saved tags merged with transaction-derived tags in `SpendsRepository.getAllTags()`
  - Tags survive budget resets (no longer lost when `transactionDao.deleteAll()` is called)
  - New string resources for tag management UI
- DB version bumped to 8 — auto-migration 7→8 adds `recurring_templates` and `savings_goals` tables
- `SpendsViewModel` now injects `RecurringDao` for processing recurring payments on day change
- `SpendsViewModel` added `periodSpends` and `periodTransactions` (MediatorLiveData filtering by budget period)
- Added `filterByPeriod()` helper in `SpendsViewModel`

### Fixed
- **Main-thread blocking on startup reads** — `syncTheme` (`Theme.kt`) and `syncOverrideLocale` (`Locale.kt`) called `runBlocking { dataStore.data.first() }` on the main thread from `LaunchedEffect`; both are now `suspend` and read directly. `SpendsViewModel.init` also replaced its `runBlocking` pre-read with a `viewModelScope.launch`
- **`restBudget` flashed intermediate values on startup** — `SpendsViewModel.restBudget` recomputed on every source emission, briefly showing a wrong "rest" while the budget/spent flows streamed in; it now emits nothing until all three sources have produced their first value
- **Empty (no-spend) periods were never archived** — `SpendsRepository.archiveCurrentPeriod` bailed on `spends.isEmpty()`, dropping income-only periods from the archive history; it now archives whenever the period has any in-period rows (`inPeriod.isEmpty()` guard)
- **Re-importing the same CSV created duplicate transactions** — `SpendsRepository.importTransactions` now skips rows whose (type, value, date, comment) already exist and dedupes repeated rows within the file, so import is idempotent
- **Voice parser mangled thousands separators and misread comment numbers as times** — `VoiceInputParser`: the amount regex replaced "," with "." (so "1,234 rupees" parsed as 1.234) and any bare number in the comment ("2 coffees") was treated as a time of day, corrupting the transaction date. The raw amount now flows through `parseAmountToBigDecimal` (which distinguishes thousands vs decimal comma), and a time is only recognized with a strong signal: an "at" prefix, a colon-separated minute, or an explicit am/pm suffix
- **Mic button could double-start the speech recognizer** — `Keyboard.kt`: tapping the mic while already listening called `startListening()` again, which can crash the recognition session (`IllegalStateException`). A tap while `isListening` is now ignored
- **Widget and wallet crashed on non-terminating division** — `CommonWidgetReceiver.whatBudgetForDay` (`splitBudget / restDays`) and `Total` (`budget / days`) called `BigDecimal.divide` without a scale/rounding mode, throwing `ArithmeticException: Non-terminating decimal expansion` whenever the division did not terminate exactly. Both now use `.divide(..., scale, RoundingMode)` in a single call
- **Day-change action could run twice** — `SpendsViewModel.runChangeDayAction` is triggered from `init` and from the 5s polling loop; since it suspends on DataStore reads, two overlapping runs could both pass the "day changed" check and redistribute the budget / charge recurring payments twice. The whole action now runs under a `Mutex`
- **`setDailyBudget` crashed on missing counters** — `SpendsRepository.setDailyBudget` used `!!` on `spent` / `spentFromDailyBudget`, throwing an NPE if the keys were absent; now falls back to `BigDecimal.ZERO`
- **Duplicate tags could be created** — `SavedTag` now has a unique index on `name` (DB migration 8→9, deduping legacy rows first), `SavedTagDao` gained `getByName`/`existsByName`, and `TagsManagementViewModel` skips `addTag` when the name already exists and refuses to rename a tag onto an existing tag's name
- **Goal allocation could double-spend the budget** — `GoalsViewModel.allocateToGoal`: two allocations racing could both read the same `budgetRest`, both pass the `budgetRest < amount` guard, and together exceed the remaining budget. Allocations are now serialized with a `Mutex`; the check-then-act (rest check → goal update → `addSpent`) runs inside a single lock
- **Voice input while editing discarded the edit** — `Keyboard.kt onResults`: in EDIT mode, voice parsing only filled the amount field and called `addSpent`, so the commit appended a second spend while leaving the edited transaction untouched (and the amount was never committed to the edited record). The voice commit now mirrors the Apply button: in `EditMode.EDIT` it replaces the edited transaction (silent `removeSpent` + `addSpent` of the parsed copy); otherwise it appends as before
- **Period archive wiped out-of-period transactions** — `SpendsRepository.archiveCurrentPeriod`: it archived every row in the table, so CSV imports / backfilled spends outside the current period were swept into the archived period and vanished from the active History. It now filters to transactions within `[startPeriodDate, finishPeriodDate]` before archiving (and totals spent only from those); out-of-period rows stay in the active table
- **Removing a previous-day spend drained the wrong counter** — `SpendsRepository.removeSpent`: the branch was chosen by the transaction's date alone (`isSameDay`), so deleting a spend dated before today while today's daily-budget fold had not yet run subtracted from `spent` even though the value still lived in `spentFromDailyBudget`, driving `spent` negative and leaking the stale value. The counter is now selected by whether the fold already ran today (`lastChangeDailyBudgetDate`), with a floor of zero on both counters
- **ASK mode stuck → recurring double-charged every 5s** — `SpendsViewModel.kt:258-301`: if the user dismissed the "recalculate budget" sheet, `lastChangeDailyBudgetDate` never advanced, so the day-change branch re-ran every poll and re-inserted recurring transactions. Now the ASK branch records the day as handled (`SpendsRepository.markDailyBudgetDistributionHandled()`) and is a one-shot per day
- **Recurring payments skipped when app closed on due day** — `SpendsViewModel.processDueRecurringPayments()`: recurring is now tracked by its own `lastRecurringAppliedDate` DataStore key and backfills every day since the last application (up to 366 days), charging each due day's templates with the correct date, instead of only checking the current day-of-month
- **Crash from `setScale(2)` on 3+ decimal values** — `SpendsRepository.kt:96-108` and `numberExtensions.kt isEquals` used `setScale(2)` with default `UNNECESSARY`, throwing `ArithmeticException` whenever a stored value (e.g. from CSV `10.005`) had more than 2 decimals, killing the budget flows. Now `setScale(2, RoundingMode.HALF_EVEN)`; CSV import (`rememberImportCSV.kt:72`) also normalizes amounts via shared `parseAmountToBigDecimal()` (moved to `util/numberExtensions.kt`) and rounds to 2dp
- **Goals sheet crash on progress divide** — `GoalsSheet.kt:187-192`: `currentAmount / targetAmount` used `BigDecimal.divide()` without scale → `ArithmeticException` on non-terminating quotients (e.g. 100/300). Now `.divide(target, 2, RoundingMode.HALF_EVEN)`
- **Voice AI crash on malformed amount** — `Keyboard.kt:210-245`: AI output like `"150.5 USD"` / `"₹150"` / `"12,50"` crashed with `NumberFormatException` (`parsed.amount.toBigDecimal()`). Now sanitized via `parseAmountToBigDecimal()` (`VoiceInputParser.kt`), zero/blank guarded numerically, and the whole commit block is wrapped in try/catch
- **NPE crash on budget setup** — `Wallet.kt:53` `budget.value!!` and `Wallet.kt:308` `currency!!` in apply button handler could NPE if LiveData hadn't emitted yet; replaced with safe-access patterns
- **NPE crash on CSV export** — `rememberExportCSV.kt` used `remember { mutableStateOf(...) }` without keys, capturing null dates permanently; `DateTimeFormatter.format(null)` crashed. Changed to `observeAsState()` with null-safe formatting
- **NPE in CurrencyEditor** — `CurrencyEditor.kt:43` `spendsViewModel.currency.value!!` could NPE; replaced with safe default
- **Force-unwrap `spends!!`** in Wallet display — replaced `spends!!.isNotEmpty()` with `spends?.isNotEmpty() == true`
- **Force-unwrap `currency!!`** in Wallet currency caption — rewrote with `when (val c = currency)` null-safe pattern
- **Force-unwrap `showAllocateDialog!!`** in `GoalsSheet.kt:157` — replaced with local val capture pattern
- **Hardcoded UI strings** in `Settings.kt`, `GoalsSheet.kt`, `RecurringPaymentsSheet.kt` — moved all text to `strings.xml` with `stringResource()` references (10 new string resources added)
- **Missing stringResource imports** — added `stringResource` imports to `GoalsSheet.kt` and `RecurringPaymentsSheet.kt`
- **Voice AI call could hang forever** — the AI parse ran on `HttpURLConnection` with no connect/read timeout, so a dead provider URL could stall the main-dispatched coroutine indefinitely. New `keyboard/VoiceAi.kt` (`parseVoiceInputWithAiFallback`) adds 10s connect / 20s read timeouts, guarantees `disconnect()` in `finally`, builds the request via `JSONObject` (so quotes/newlines in the transcript are escaped instead of interpolated into a raw string), extracts JSON even when wrapped in markdown fences or prose, validates the response shape, and falls back to `now` for unparseable dates — every failure returns `null` so the offline parser takes over
- **`SpeechRecognizer` could NPE / crash on devices without a recognition service** — `Keyboard.kt` created the recognizer unconditionally. It now checks `SpeechRecognizer.isRecognitionAvailable()` first, creates it null-safely, and null-guards `startListening`/`destroy`
- **Stale voice result could commit twice / out of order** — an AI reply that arrived after the user re-tapped the mic (or tapped again while a result was being committed) would apply to a transaction from the previous session. `Keyboard.kt` now tracks a monotonic `voiceSession` id (bumped on every `startListening`), discards results whose session no longer matches, and blocks re-taps while a result is still processing (`isProcessing`)
- **Voice status messages were hardcoded English strings** — `"Listening..."`, `"No speech heard"`, `"Couldn't understand"`, `"Recognition failed"`, `"Voice input"` moved to `strings.xml` (`voice_*`); mic-tap now also reports microphone-permission denial and "recognition not available on this device"
- **Voice parser picked the FIRST number as the amount** — `"2 coffees 150"` parsed as 2. `VoiceInputParser` now strips the time-of-day expression before amount extraction (so `"5pm coffee 5"` yields 5, not a 17:00 time read as an amount), prefers the number tied to a currency marker (`₹`, `$`, `€`, `£`, `¥`, `rupees`, `dollars`, …), and otherwise takes the LAST number; time detection also scans all candidates instead of rejecting on the first weak match
- **Added `VoiceInputParserTest`** — 17 unit tests covering amount/comment/date extraction, thousands vs decimal separators, currency anchoring, am/pm and `at`-prefixed times, yesterday/tomorrow, and null inputs
- **AI voice-input review pass**:
  - `parseVoiceAiDate` now parses the common no-offset `ISO_LOCAL_DATE_TIME` (`2026-08-05T10:30:00` / `T10:30`) and `yyyy-MM-dd HH:mm:ss`; previously those fell back to "now", silently discarding the date the model returned. Also dropped the duplicate `yyyy-MM-dd` formatter (`ISO_LOCAL_DATE` is the same)
  - "Understanding…" (`voice_processing`) is shown while the AI/parse result is being applied, so the user isn't left staring at a silent keyboard for the up-to-20s AI call (taps are blocked during processing); it renders in the neutral color, not error red
  - Added 6 `parseVoiceAiDate` unit tests (ISO offset/local, space-separated with seconds, plain date, relative words anchored to `now`, blank/garbage fallback)
- **Test count**: `VoiceInputParserTest` now 23 cases (17 parser + 6 AI date), all green alongside `SpendsRepositoryTest` (18) and `assembleDebug`

### Known State
- This is a simpler baseline than `our-fixes`:
  - 2 Room tables (transactions, storage) — no recurring, periods, categories yet
  - No notification system, no sync, no reminder
  - These will need to be re-built or ported from `our-fixes`

## Removed (from our-fixes to fresh fork)
- Removed all notification system code (NotificationScheduler, notification channels, AlarmManager)
- Removed recurring transactions (RecurringDao, RecurringRepository, RecurringReceiver)
- Removed period tracking (PeriodDao, Period entity)
- Removed category/tag system (CategoryDao, Category entity, TagCategory)
- Removed sync/export (SyncManager, SyncReceiver)
- Removed reminder system (ReminderReceiver)
- Removed all prior fix commits (saved to our-fixes branch)
