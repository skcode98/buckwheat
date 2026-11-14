# Budget Counter Self-Heal & Goal Auto-Allocation — Implementation Plan

**Goal:** Two related budget-integrity features:

1. **Startup counter self-heal** — recompute the period `spent` / `spentFromDailyBudget` counters from the stored transaction rows on app start, so an interrupted write (process kill mid-edit, crash between `removeSpent` and `addSpent`) can never leave the daily-budget counters permanently drifted.
2. **Auto-allocate leftover daily budget into a savings goal** — a "Use leftover" action on a goal card that moves the unspent part of today's daily allowance (`dailyBudget - spentFromDailyBudget`, never negative, capped at the goal's remaining target) into the goal.

**Architecture:**
- `SpendsRepository.reconcileCounters()` owns the self-heal. It reads the period dates from `budgetDataStore`, loads SPENT rows via `transactionDao.getAllNow()`, keeps only rows inside `[startPeriodDate, finishPeriodDate]`, splits them into today vs earlier days, and writes both counters in one `DataStore.edit`. Idempotent; no-op when the period dates are absent (fresh install).
- `Application.onCreate()` calls it once per process start inside the existing `configScope` coroutine, after notification channels exist and before the widget scheduler.
- Leftover math lives in one pure function `dailyLeftover(dailyBudget, spentFromDailyBudget)` in `settings/GoalAutoAllocation.kt`.
- `GoalsViewModel.autoAllocateLeftoverToGoal(goalId)` reads the counters, clamps to `min(leftover, targetAmount - currentAmount)`, and reuses the extracted `allocateLocked()` body that `allocateToGoal()` already used, so manual and automatic allocations share one mutex-guarded path (no double-spend race).
- `GoalsSheet` renders a second button, "Use leftover", next to "Allocate" on incomplete goals.

**Tech Stack:** Kotlin, DataStore Preferences, Hilt, Room DAOs (via fakes in tests), Jetpack Compose, JUnit4 + Robolectric (`@Config(sdk = [35])`).

---

### Task 1: Counter self-heal (repository + startup wiring)

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/di/SpendsRepository.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/Application.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/di/SpendsRepositoryReconcileTest.kt`

**Interfaces:**
- `suspend fun SpendsRepository.reconcileCounters()` — rebuilds `spentStoreKey` (in-period SPENT rows dated before today) and `spentFromDailyBudgetStoreKey` (in-period SPENT rows dated today).
- `Application` gains `@Inject lateinit var spendsRepository: SpendsRepository` and launches `reconcileCounters()` from `configScope`.

**Why it is safe on every start:** the values are a pure function of the transaction table and the period dates. Running it twice produces the same result, and a fresh install (no period dates yet) returns immediately.

- [x] **Step 1: Failing tests** — 5 tests: drift in today's counter is repaired from rows; drift in the previous-day total is repaired; running twice is idempotent; rows outside the period are ignored; no period dates ⇒ no-op (`Unresolved reference: reconcileCounters` = RED).
- [x] **Step 2: Implement `reconcileCounters()`** — single `budgetDataStore.edit` writing both counters.
- [x] **Step 3: Wire into `Application.onCreate()`** — `configScope.launch { spendsRepository.reconcileCounters() }`.
- [x] **Step 4: Green** — `SpendsRepositoryReconcileTest` 5/5.

### Task 2: Leftover calculation (pure helper)

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/settings/GoalAutoAllocation.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/settings/GoalAutoAllocationTest.kt`

**Interfaces:**
- `internal fun dailyLeftover(dailyBudget: BigDecimal, spentFromDailyBudget: BigDecimal): BigDecimal`

```kotlin
fun dailyLeftover(dailyBudget: BigDecimal, spentFromDailyBudget: BigDecimal): BigDecimal =
    (dailyBudget - spentFromDailyBudget).coerceAtLeast(BigDecimal.ZERO)
```

- [x] **Step 1: Failing tests** — unspent part returned (100 − 40 = 60); fully spent ⇒ 0; overspent ⇒ 0 (never negative); nothing budgeted ⇒ 0. (RED: unresolved reference.)
- [x] **Step 2: Implement helper.** GREEN 4/4. Comparisons use `compareTo` because `BigDecimal.equals` also compares scale.

### Task 3: Allocate leftover into a goal (ViewModel + UI)

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/settings/GoalsViewModel.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/settings/GoalsSheet.kt`
- Modify: `app/src/main/res/values/strings.xml` (`goal_allocate_leftover` = "Use leftover")
- Test: `app/src/test/java/com/danilkinkin/buckwheat/settings/GoalsViewModelAutoAllocationTest.kt`

**Interfaces:**
- `fun GoalsViewModel.autoAllocateLeftoverToGoal(goalId: Long): Job`
- `private suspend fun GoalsViewModel.allocateLocked(goalId: Long, amount: BigDecimal)` — the original `allocateToGoal` body: budget-rest guard, goal update, completion event, SPENT row (`comment = "→ <goal name>"`), milestone nudge.

**Behaviour:**
- Reads `getDailyBudget().first()` and `getSpentFromDailyBudget().first()`.
- `amount = min(dailyLeftover(...), targetAmount - currentAmount)`; `<= 0` ⇒ silent no-op.
- Runs inside the same `allocationMutex` as manual allocation.
- Never exceeds the target: an allocation can complete a goal, and the spend row records only the clamped amount.

- [x] **Step 1: Failing tests** — end-to-end against the real `SpendsRepository` with fakes: full leftover 100 moves into the goal and writes one SPENT row; a 30 spend earlier today leaves 70 to move; spending the whole allowance is a no-op; a 50 target receives only 50 and the goal is marked completed.
- [x] **Step 2: Refactor `allocateToGoal` into `allocateLocked` (pure extraction, no behaviour change).**
- [x] **Step 3: Implement `autoAllocateLeftoverToGoal` + button + string.**
- [x] **Step 4: Green** — `GoalsViewModelAutoAllocationTest` 4/4, `GoalAutoAllocationTest` 4/4.

---

## Notes / decisions

- **Leftover definition:** unspent part of *today's* daily allowance, not the whole remaining period budget. Moving the period remainder would starve the rest of the period, so auto-allocation only harvests what today's allowance did not consume.
- **Counters vs rows:** the self-heal treats the transaction table as the source of truth, matching how `spent` (before today) and `spentFromDailyBudget` (today) are defined and how `setDailyBudget()` folds today's rows into `spent` at day rollover.
- **Translations:** only the default `values/strings.xml` gets `goal_allocate_leftover`; other locales fall back to English until translated.
