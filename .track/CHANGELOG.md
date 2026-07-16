# Changelog

## [Unreleased]

### Fixed (Batch 2 — 6 thread-safety fixes)
- **Keyboard.kt** — `runBlocking` in onClick → `coroutineScope.launch` (blocked main thread on button tap)
- **SpendsRepository.kt** — `computeStreak()` → `suspend fun` (removed `runBlocking` from I/O function)
- **Locale.kt** — `syncOverrideLocale()` → `suspend fun` (was blocking in `LaunchedEffect`)
- **Theme.kt** — `syncTheme()` → `suspend fun` (same)
- **RecurringReceiver.kt** — `@AndroidEntryPoint` with injected DAOs (removed manual Room DB creation)
- **SyncReceiver.kt** — same pattern
- **build.yml** — restored CI workflow

### Fixed (Batch 1 — 9 bug fixes)
- **SpendsRepository.kt** — `.first().first()` → `.first().firstOrNull()` with null check (crash on empty lists)
- **SpendsRepository.kt** — `.last()` → `.lastOrNull()` with null check
- **BudgetConstructor.kt** — `as Date` → `as? Date` with `?: return`
- **BottomSheets.kt** — `as Date?` → `as? Date`
- **RecurringReceiver.kt** — LiveData `.value` → `.asFlow().first()` (background thread access)
- **Keyboard.kt** — `runBlocking` → `coroutineScope.launch` (UI thread)
- **CurrentSpendEditor.kt** — `runBlocking` → `coroutineScope.launch` (UI thread)
- **SettingsRepository.kt** — `[type]!!` → `.getValue(type)` (meaningful error instead of NPE)
- **SpendsRepository.kt** — Merged split DataStore edits into single `edit {}` blocks
- **BudgetConstructor.kt/Wallet.kt** — Added `finishPeriodDate` key to `remember` (stale data)
- **rememberExportCSV.kt** — Observe dates as state, null-safe formatting

### Fixed (Initial batch — 6 bugs + crash)
- **BottomSheetWrapper.kt** — Added `LocalActivityResultRegistryOwner provides LocalActivityResultRegistryOwner.current!!` to `CompositionLocalProvider` (Settings crash)
- **SettingsRepository.kt** — Fixed TUTORIAL_STAGE crash when no cached stages exist
- **NotificationScheduler.kt** — Fixed monthly alarm scheduling (was using day-of-week instead of day-of-month)
- **History.kt** — Fixed undo race condition
- **SpendsViewModel.kt** — Fixed use of JavaScript `===`/`!==` operators (compiled but always evaluated to false)
- **SettingsRepository.kt** — Fixed key caching issue
- **EditorViewModel.kt** — Fixed observability issue

### Added
- **MainActivity.kt / MainScreen.kt** — Re-implemented notification detail screen wiring
