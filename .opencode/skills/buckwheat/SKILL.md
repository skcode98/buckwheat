---
name: buckwheat
description: Buckwheat daily budget tracker — Kotlin Android app with Jetpack Compose, Room, Hilt, DataStore
license: MIT
compatibility: opencode
metadata:
  language: kotlin
  framework: android-compose
---

## Project Identity

- **App**: Buckwheat — daily budget tracker
- **Language**: Kotlin 2.2.0 | **UI**: Jetpack Compose + Material3 | **DI**: Dagger Hilt 2.57 (KSP)
- **Database**: Room 2.7.2 (version 13) | **State**: Preferences DataStore + Room
- **Min SDK**: 29 | **Target/Compile SDK**: 36 | **AGP**: 8.7.3

## Repository State

- **Current branch**: `master` — clean fork of upstream @ `4b60102`
- **Saved work**: `our-fixes` branch on origin
- **Upstream**: `https://github.com/danilkinkin/buckwheat.git`
- **Pushed (2026-08-07)**: `a147c2d` weekday breakdown, `3bbdd9c` overspend notification, `e7249c9` share summary + compare card, `9a75b05` full JSON backup/restore, `d389a0e` emoji picker. **131 unit tests green** (`testDebugUnitTest`)

## Core Conventions

### Coding Rules (NEVER break these)
- No `runBlocking` — use `viewModelScope.launch` / `coroutineScope.launch`
- No `!!` force-unwrap — use `as?` safe cast with null check
- No `.first()` on empty lists — use `.firstOrNull()`
- No `as T` unsafe cast — use `as? T`
- No LiveData `.value` on bg threads — use `.asFlow().first()`
- No `remember {}` without observed state keys
- No split `DataStore.edit {}` — combine into one block
- No manual Room DB creation — use `@AndroidEntryPoint` + injected DAOs

### State Management
- ViewModels use `MutableLiveData` / `LiveData` (not StateFlow)
- Composables observe via `.observeAsState(default)`
- Navigation: sheet-based stack in `AppViewModel.sheetStates` (no Jetpack Navigation)
- Custom keyboard replaces system keyboard for number input

### Data Layer
- Room entities: `Transaction` (type, value, date, comment, uid), `Storage` (legacy key-value), `SavedTag`, `SavedCategory` (custom categories, unique name + `emoji` column), `BudgetPeriod`, `ArchivedTransaction`, `RecurringTemplate`, `SavingsGoal`
- DataStore keys: budget, spent, dailyBudget, startPeriodDate, finishPeriodDate, spentFromDailyBudget, currency, overspendNotifiedStoreKey, etc.
- Settings DataStore: theme, locale, debug, tutorial stages, hideOverspendingWarn, reminderEnabled/hour/minute, overspendNotifyEnabledStoreKey, voice AI keys
- Packages: `backup/` (BackupData JSON codec + BackupRepository), `notifications/` (daily budget reminder + instant overspend), `analytics/` (trend, weekday, compare-to-last-period cards + ShareSummary)

## Common Mistakes & Fixes

### Tags Management shows empty despite having transactions with comments
**Root cause**: `TagsManagementViewModel` only read from `saved_tags` table (explicitly saved tags), ignoring comment-based tags in the `transactions` table.
**Fix**: Merge both sources via `SpendsRepository.getAllTags()` which already aggregates unique tag names from transaction comments + saved tags. Use a `TagItem` model with nullable `id` to distinguish auto-derived (no id) vs saved tags.

### Importing transactions without a budget causes data loss
**Root cause**: `addSpent()` used `!!` on DataStore keys that don't exist until a budget is set. NPE was silently caught, so transactions were stored in DB but DataStore budget values (spent, dailyBudget) were never updated. Later `setBudget()` checked DataStore `oldSpent > 0` — it was 0, so `archiveCurrentPeriod()` was skipped, then `transactionDao.deleteAll()` wiped imported data.
**Fix**:
1. `addSpent()` — use null-safe access on all DataStore keys; return early if budget not set (transaction already in DB)
2. `setBudget()` — also check `transactionDao.getAll()` for SPENT records before deciding to archive
3. `archiveCurrentPeriod()` — don't `return` when `finishDate`/`startDate` are null; compute from `spends.minOf/maxOf { it.date }`

### Past Periods sheet is empty
**Root cause**: Past periods (`budget_periods` table) are only populated by `archiveCurrentPeriod()`, which is called exclusively inside `setBudget()`. Importing transactions via CSV never creates a past period. The fix for data loss above ensures imports survive budget setup and get properly archived.

### App freezes / crashes on wallet screen (infinite recomposition)
**Root cause**: `SpendsViewModel.howMuchBudgetRest()` returned a **new** `MutableLiveData` (and launched a coroutine) on every call. `RestAndSpentBudgetCard` called it directly in the composable body, so every recomposition created a new LiveData → emission → recomposition → infinite loop.
**Fix**: Make the value a stable property. `restBudget` is now a `MediatorLiveData` derived from `budget`, `spent`, and `spentFromDailyBudget` LiveDatas; `howMuchBudgetRest()` just returns it. Rule: never construct a LiveData/Flow inside a composable body — hoist it to the ViewModel and cache it.

## Key Architecture

```
SpendsRepository (ALL business logic) ← SpendsViewModel → Composables
AppViewModel (sheet stack, snackbars, tutors, confetti)
EditorViewModel (state machine: ADD/EDIT, IDLE/CREATING_SPENT/EDIT_SPENT/COMMITTING_SPENT)
```

## Build & Test
```powershell
.\gradlew.bat assembleDebug  # Build
.\gradlew.bat testDebugUnitTest  # Robolectric JVM tests (no emulator needed)
.\gradlew.bat lintDebug      # Lint
.\gradlew.bat spotlessCheck  # Format check
```

## JVM Testing with Robolectric

This laptop has **no emulator** (VT-x disabled in BIOS, no system images). Robolectric runs the app's Android code on the desktop JVM — used to verify `SpendsRepository` budget logic locally.

- Deps in `app/build.gradle.kts`: `testImplementation("org.robolectric:robolectric:4.15.1")` + `androidx.test:core`, `kotlinx-coroutines-test`, `junit`.
- `testOptions.unitTests.isIncludeAndroidResources = true` and `all { it.jvmArgs("-ea") }` (Kotlin `assert()` is a no-op without `-ea`).
- Robolectric 4.16 needs JDK 21 for SDK 36; this machine has JDK 17 → pin `@Config(sdk = [35])` and use 4.15.1.
- Test fakes live in `app/src/test/.../di/` (`FakeTransactionDao`, `FakeSavedTagDao`, `FakeBudgetPeriodDao`, `FakeGetCurrentDateUseCase`). The DataStore-backed `SpendsRepository` works under Robolectric because each test gets a fresh Application context → fresh DataStore instance.
- `androidTest/` instrumented tests require an emulator/device; the `src/test` suite is the portable copy.

## Session Compaction Recovery

After opencode compacts a session, the `compaction-fix.ts` plugin injects preserved state (active task, recent changes, decisions) into the compaction prompt. Follow these steps to recover:

1. Read `.track/MEMORY.md` — restores full context, active section, and decisions
2. Read `.track/CHANGELOG.md` — shows what changed before compaction
3. Read `.track/CACHE.md` — build commands and references
4. Update `.track/.session-state.json` with `nextMove` and `files` before significant work
5. If `.session-state.json` is stale, manually note the current task in MEMORY.md's Active section

## When to use Context7 MCP
When you need to look up Kotlin, Android, Jetpack Compose, Room, Hilt, or Gradle documentation, use the `context7` MCP server to search official docs.
