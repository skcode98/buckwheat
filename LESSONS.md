# Buckwheat — Code Fix Log & Lessons

## Issue 1: Wallet Apply silently skips when `currency` LiveData is null

**File:** `wallet/Wallet.kt:310-324`

**Problem:** `observeAsState()` without default returns `null` until DataStore emits. The guard `if (currentCurrency != null ... )` skips `setBudget()`/`changeBudget()` entirely — sheet closes, nothing saved, user sees nothing wrong.

**Fix:** Isolated `changeDisplayCurrency()` behind a `currentCurrency != null` check; budget-setting (`setBudget`/`changeBudget`) runs unconditionally. They don't need currency.

**Lesson:** Don't couple independent operations behind a single null guard. `changeDisplayCurrency` writes a preference; `setBudget` writes budget data. If one is unavailable, the other should still proceed.

---

## Issue 2: BudgetConstructor `remember {}` blocks have no keys → stale state

**Files:** `wallet/BudgetConstructor.kt:66-117`

**Problem:** `rawBudget`, `budgetCache`, `dateToValue`, `showUseSuggestion` use `remember {}` without keys. They compute once and never update when LiveData values change during an edit session.

**Fix:**
- `rawBudget`/`budgetCache` — added `budget` as key so they reset when the external budget changes.
- `dateToValue` — use `finishPeriodDate` (observed state with default `Date()`) instead of `spendsViewModel.finishPeriodDate.value` (raw LiveData, may be null).
- `showUseSuggestion` — replaced `remember { mutableStateOf(...) }` with `remember { derivedStateOf { ... } }` so it auto-recomputes when `budget`, `budgetCache`, `finishPeriodDate`, or `startPeriodDate` change. The manual `showUseSuggestion = false` in onClick is no longer needed — `derivedStateOf` yields `false` naturally after the click changes `budgetCache` and `dateToValue`.

**Lesson:** `remember {}` without keys captures a snapshot. If it depends on observed state that can change, either add keys or use `derivedStateOf`. Prefer `derivedStateOf` when the value is a pure function of other state and should never be set manually.

---

## Issue 3: `periodSpends` MediatorLiveData emits unfiltered data before DataStore is ready

**File:** `data/SpendsViewModel.kt:45-62`

**Problem:** `MediatorLiveData` starts with no value (`null`). Room emits before DataStore, so `filterByPeriod(list, null, null)` returns the full unfiltered list. The Wallet briefly shows all transactions, then recomposes with filtered data.

**Fix:**
1. Initialize `value = emptyList()` so `observeAsState()` never gets `null`.
2. In each `addSource` callback, only set the value when `lastStart != null && lastFinish != null` — i.e., after DataStore has emitted both period dates. Before that, the value stays `emptyList()`.

Applied the same fix to `periodTransactions`.

**Lesson:** `MediatorLiveData` emits on every source change. When a result depends on multiple sources, gate the emission with a "all sources ready" check to avoid emitting partial/incomplete values. Always set an initial value to prevent null from propagating.

---

## Issue 4: FinishDateSelector allows selecting past dates

**File:** `wallet/FinishDateSelector.kt:47`

**Problem:** `disableBeforeDate = null` overrides `CalendarUiState`'s default of `LocalDate.now()`, letting users select dates before today. `CalendarState.setSelectedDay` in RANGE mode hardcodes `LocalDate.now()` as the range start, so clicking a past date creates an inverted range `[today, pastDate]`.

**Fix:** Changed `disableBeforeDate = null` → `disableBeforeDate = Date()` (today). This blocks past dates from being clickable in the calendar.

**Lesson:** When a component has a sensible default, don't override it with `null` unless you intend to disable the constraint. `null` means "no constraint", not "use default".

---

## Issue 5: `Wallet.kt` `dateToValue` captures stale/null `finishPeriodDate`

**File:** `wallet/Wallet.kt:59`

**Problem:** `remember { mutableStateOf(spendsViewModel.finishPeriodDate.value) }` reads the raw LiveData value at composition time. Before DataStore emits, this is `null` — permanently disabling the Apply button and showing 0 days.

**Fix:** Use the observed state with default: `remember { mutableStateOf(finishPeriodDate) }` where `finishPeriodDate` comes from `observeAsState(Date())`. This ensures `dateToValue` is always a valid `Date`.

**Lesson:** When reading a LiveData value in `remember {}`, don't use `.value` directly — it may be null before first emission. Use the `observeAsState(default)` result instead. Same pattern applied in `BudgetConstructor.kt:94`.

---

## Issue 6: Flash of main screen before onboarding appears

**File:** `data/SpendsViewModel.kt:89,93-96`

**Problem:** `requireSetBudget` starts as `false`, main screen renders, then `runChangeDayAction()` coroutine completes and flips it to `true`, opening the onboarding. On first launch, the main screen is visible for 100-500ms before the sheet slides in.

**Fix:** Added `runBlocking` in `init` before the async launch to synchronously read `lastChangeDailyBudgetDate` from DataStore and set `requireSetBudget`. This ensures the correct value is set before any composition occurs. The blocking read is fast (<10ms for first read, immediate for cached reads) and happens before the first frame renders.

**Lesson:** When a ViewModel's `init` launches async work that determines initial UI state, there's a race with first composition. For fast I/O (DataStore reads from local files), a synchronous read in `init` is acceptable and simpler than adding loading states. Keep it minimal — only read what's needed for the initial navigation decision, not the full processing.

---

## Issue 7: DAO methods are non-suspend + `allowMainThreadQueries()`

**Files:** `data/dao/TransactionDao.kt`, `di/AppModule.kt:25`

**Problem:** `TransactionDao.insert/update/deleteById/deleteAll/getById` are non-suspend. `StorageDao.get/set/delete/deleteAll` are non-suspend. `.allowMainThreadQueries()` in `AppModule.kt` permits calling them from `viewModelScope.launch` (Dispatchers.Main), blocking the main thread during DB operations.

**Fix:**
- `TransactionDao`: made `getById`, `insert`, `update`, `deleteById`, `deleteAll` `suspend`.
- `StorageDao`: made `get`, `set`, `delete`, `deleteAll` `suspend`.
- `AppModule.kt`: removed `.allowMainThreadQueries()`.
- `FakeTransactionDao`: updated to use `suspend` + added missing `getAll` overloads.
- Room automatically runs `suspend` DAO methods on `Dispatchers.IO`, and LiveData-returning methods are already asynchronous.

**Lesson:** Never use `.allowMainThreadQueries()` in production. It's a debug helper that masks the need for proper `suspend` DAO methods. Room's `suspend` support automatically uses background dispatchers.

---

## Issue 8: JVM unit tests can't use Android's `org.json` (it's stubbed)

**Files:** `data/categories/SpendCategorizer.kt`, `test/.../SpendCategorizerTest.kt`, `app/build.gradle.kts`

**Problem:** `JSONObject(String)` / `JSONArray` calls in parser code work on a device, but local JVM unit tests fail with `org.json.JSONException: End of input at character 0` (or `Method not mocked`) because Android's `org.json` is a stub that throws in unit tests.

**Fix:** Added `testImplementation("org.json:json:20231013")` to `app/build.gradle.kts` so the real `org.json` implementation is on the unit-test classpath.

**Lesson:** Any code that parses `org.json` needs the real library for JVM tests — add the `testImplementation` dependency up front. Also note: the non-Android `org.json` does **not** preserve `LinkedHashMap` insertion order, so tests must assert against explicit-key JSON strings, not rely on map ordering.

---

## Issue 9: Test assertions must match the parser's actual contract

**File:** `test/.../SpendCategorizerTest.kt`

**Problem:** A prose-fallback test expected the parsed comment to equal `"tea"`, but the parser returns a normalized sentence (e.g. `"user spent on tea"`). The test failed even though the production behavior was correct.

**Fix:** Corrected the assertion to the parser's real output contract.

**Lesson:** When a test fails after a parser change, first check whether the *parser* or the *test* is wrong — a normalized/sanitized output is often intentional. Verify the actual contract before "fixing" production code.

---

## Issue 10: `emptyMap()` needs an explicit type parameter in generic contexts

**File:** `data/categories/SpendCategorizer.kt`

**Problem:** `categorizeSpendsWithAi` returned `emptyMap()` from a branch, and Kotlin inferred `Nothing` for the value type where a `Map<Int, SpendCategory>` return was expected → compile error.

**Fix:** `emptyMap<Int, SpendCategory>()`.

**Lesson:** `emptyMap()` / `emptyList()` rely on type inference; when the expected type isn't obvious from the context (e.g. returned from a function with a covariant/generic signature), give the explicit type parameter.

---

## Issue 11: `LaunchedEffect` keyed on the data the effect itself writes needs a no-op guard

**File:** `analytics/Analytics.kt`

**Problem:** AI categorization is triggered with `LaunchedEffect(spends)`. After an AI persist, Room re-emits `spends`, re-firing the effect — potentially looping.

**Fix:** `categorizeUncategorized` processes only rows whose `category` is null, so once persisted the row is skipped and the loop terminates.

**Lesson:** A side-effect that writes to its own data source must be idempotent (skip already-handled rows) or you get an emit → write → emit loop. Guard on the state that makes work "already done".

---

## Issue 12: Keyword matching must be whole-word to avoid false positives

**File:** `data/categories/SpendCategorizer.kt`

**Problem:** Naive `contains()` keyword matching would classify "great" as food ("eat") or "tea tree" as food ("tea").

**Fix:** Offline classification builds a regex map once with `Pattern.quote(keyword)` wrapped in `\b` word boundaries; the map is immutable for thread safety.

**Lesson:** For category/classification keyword matching, use whole-word regex (`\b` + `Pattern.quote`) and build the pattern map once rather than per-call.

---

## Issue 13: Referenced `R.drawable.ic_*` resource doesn't exist

**File:** `notifications/OverspendingNotifier.kt`

**Problem:** The overspend notification referenced `R.drawable.ic_warning`, which does not exist in the project. The build fails with an unresolved-reference error only when that line is reached — Compose resource resolution is compile-time here, but the icon name was simply wrong.

**Fix:** Swapped to the existing `ic_priority_high` drawable.

**Lesson:** Before writing any `R.drawable.ic_*`, verify it exists: `glob app/src/main/res/drawable/ic_*.xml`. Pick an existing icon instead of inventing a name. (Recorded in AGENTS.md pitfalls table.)

---

## Issue 14: `getString` format args silently ignored when placeholders don't match

**File:** `strings.xml` / `OverspendingNotifier.kt`

**Problem:** The overspend notification called `getString(R.string.overspend_notify_message, amountOver, dailyBudget)` but the string resource had no `%1$s`/`%2$s` placeholders at the time. Android does not crash on this — the extra args are silently dropped and the notification shows literal `$` markers or missing values, which is easy to ship.

**Fix:** Kept the `%1$s` / `%2$s` placeholders in the string resource in sync with the two format args.

**Lesson:** Keep the number of format args equal to the number of `%n$s`/`%n$d` placeholders in the resource. A mismatch is silent (no crash, no lint failure), so verify the string content, not just the call site.

---

## Issue 15: Import for a top-level function silently missing after an edit

**File:** `analytics/Analytics.kt`

**Problem:** After wiring the weekday card, `countDaysToToday` was referenced but its import was missing → "unresolved reference" compile error. The edit that should have added the import had silently targeted the wrong spot, so it looked like the import existed.

**Fix:** Added the import explicitly; verified with a grep on the call site.

**Lesson:** After any multi-file edit, grep the call sites to confirm imports actually landed — an `edit` can match in the wrong place without error. Kotlin requires the import even for top-level functions that share the caller's package prefix.

---

## Issue 16: Transient `.kotlin/sessions/*.salive` files staged with real work

**File:** `.gitignore` / git index

**Problem:** The compiler writes session files under `.kotlin/sessions/`; a commit prepared from `git add -A` staged dozens of them alongside the feature code. They pollute the diff and can't be committed meaningfully.

**Fix:** `git restore --staged .kotlin/` before committing; never stage the directory.

**Lesson:** Never stage `.kotlin/sessions/`, `build/`, or other generated artifacts. Check `git status --short` for these before committing and use `git restore --staged` on them.

---

## Issue 17: `spotlessCheck` reports UP-TO-DATE and misses formatting on new files

**File:** build tooling

**Problem:** After adding new Kotlin files, `spotlessCheck` returned "UP-TO-DATE" without actually checking them, so formatting issues would have passed silently. The check's up-to-date cache can lag the new files.

**Fix:** Run `.\gradlew.bat spotlessApply` after adding/changing files, then `spotlessCheck`.

**Lesson:** `spotlessCheck`'s UP-TO-DATE result is not proof of formatting. Force a pass with `spotlessApply` on new files before relying on the check. (AGENTS.md Build & Test section now includes `spotlessApply`.)

---

## Issue 18: Tracking-doc status markers must be flipped after a push

**Files:** `.track/MEMORY.md`, `.track/CHANGELOG.md`

**Problem:** Feature entries recorded as `(uncommitted)` (or listing only one of several commits) stayed stale after the work was committed and pushed, so the next session's context was wrong about what existed where.

**Fix:** After each push, flip `(uncommitted)` → `(committed <hash>, pushed)` and add the pushed commit to the top-line summary (`ALL … features committed AND pushed on master @ <hash>`).

**Lesson:** The tracking docs' `(uncommitted)`/`(committed <hash>, pushed)` markers are the source of truth for the next session. Flip them immediately after a push, and verify with `git status -sb` that `master...origin/master` are in sync. (AGENTS.md Session Protocol rule 7.)

---

## Issue 19: Overspend flag went stale when a removal or budget update bypassed it

**Files:** `di/SpendsRepository.kt` (`removeSpent`, `updateDailyBudget`)

**Problem:** The review found `overspendNotifiedStoreKey` was only maintained by `addSpent` (and reset by `setDailyBudget`). `removeSpent` dropped today's counter without touching the flag, and `updateDailyBudget` (used by the day-change fold) didn't clear it — so undo/delete or a new day's first crossing never re-notified. The bug lived in a code path that looked "locally correct" because every other path synced the flag.

**Fix:** Resync the flag to `spentFromDailyBudget > dailyBudget` inside the *same* `edit {}` block that mutates the counter/budget, for `removeSpent` (today branch) and `updateDailyBudget`.

**Lesson:** A persisted "boundary crossed" flag is a mirror of a comparison over other persisted values. Audit **every** mutation site of those values, not just the one that writes the flag — a flag that drifts from its source comparison silently disables or double-fires the side effect.

---

## Issue 20: Multi-DAO wipe+reinsert and secrets in backups need explicit design

**Files:** `di/BackupRepository.kt`

**Problem:** Two review findings: (1) `restoreBackup` wiped and reinserted across several DAOs outside any Room transaction — a mid-restore failure left a partially-destroyed DB against stale DataStore values (the earlier "intentionally not transactional, re-restore fixes it" decision underweighted how much data a crash destroys); (2) `asBackupMap()` exported the plaintext Voice-AI API key into a user-shareable JSON file.

**Fix:** (1) Inject `DatabaseModule` into `BackupRepository` and run the whole wipe + FK-safe reinsert inside `database.withTransaction {}`; on exception, log, return `false`, and skip the DataStore swaps so old data survives. (2) Skip `voiceAiApiKeyStoreKey` in `asBackupMap()` (restore of old files just leaves the current key untouched).

**Lesson:** Destructive multi-table operations deserve a transaction even when the DAO layer is per-repository; and any feature that serializes all user state must explicitly enumerate what must *never* be serialized (secrets). Encode those as regression tests (the API-key exclusion now has one).
