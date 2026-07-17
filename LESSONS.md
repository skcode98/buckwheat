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
