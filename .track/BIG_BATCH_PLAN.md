# Big Batch Plan — 5 items (2026-08-11)

Order = low risk first. Each item gets the golden pipeline (`:app:spotlessApply :app:testDebugUnitTest :app:assembleDebug`), its own commit + push, and doc updates.

---

## Task A — On-track overspend alert: last `setRepeating` → `setWindow` (small)

Same fix already applied to the daily reminder, widget refresh, digest, and recurring-due alerts.

- `notifications/OnTrackAlertScheduler.kt`: `setRepeating` → `setWindow(RTC_WAKEUP, trigger, WINDOW_MILLIS=10min)`; one-shot so the receiver re-arms.
- `notifications/OnTrackAlertReceiver.kt`: after `postAlert`, re-arm next day from `onTrackAlertHourStoreKey`/`onTrackAlertMinuteStoreKey` (defaults `DAILY_REMINDER_DEFAULT_HOUR/MINUTE`) **only while the toggle is still enabled** (`onTrackAlertEnabledStoreKey`).
- `notifications/ReminderBootReceiver.kt`: reschedule on-track alert on boot when enabled (mirrors daily reminder block).
- No strings. No new tests (alarm scheduling is Android-side; matches existing untested pattern).

## Task B — Archived transactions: persistent category (DB v13 → v14) + backfill

Completes the categorization story for ALL historical data (out-of-period imports, archived periods).

- `data/entities/ArchivedTransaction.kt`: add `@ColumnInfo(name="category") val category: String? = null`.
- `di/DatabaseModule.kt`: manual `AutoMigration13to14` = `ALTER TABLE archived_transactions ADD COLUMN category TEXT`; bump `version = 14`; register in `MANUAL_MIGRATIONS`.
- `data/entities/ArchivedTransaction.kt` `toTransaction()`: carry `category`.
- `di/SpendsRepository.kt` `archiveImported()`: pass `category = tx.category` into the `ArchivedTransaction`.
- `backup/BackupData.kt` codec: include `category` in `toJson()`/`toArchivedTransaction()` (`optString` default null → backward compatible v1 exports).
- `data/dao/BudgetPeriodDao.kt`: new `@Query UPDATE archived_transactions SET category=:category WHERE uid=:uid` `suspend fun updateCategory(uid: Int, category: String?)`.
- `data/categories/SpendCategoriesViewModel.kt` `categorizeUncategorized()`: also scan uncategorized **archived** spends (`budgetPeriodDao.getAllArchivedNow()`), offline keywords first then AI, persist via `updateArchivedCategory`.
- Tests: `BackupDataTest` round-trip with category; `FakeBudgetPeriodDao` gains `updateCategory`. New `ImportAutoCategorizeTest`/`SpendsRepositoryTest` archive-path check (import out-of-period row keeps its offline category on the archived row).

## Task C — Interleaved Phase 5: wallet daily-allowance reservation (mitigated)

Goal: scheduled categories (FOOD monthly @5000 ≈ ₹166/day) reserve part of the daily budget so the wallet's daily counter reflects them. **Conservative design — no core-math rewrite**: a pure overlay applied only at the two "how much can I spend today" recompute points; zero change when no schedules exist.

- Pure helper `interleaved/InterleavedBudget.kt`: `dailyPace(category)` — DAILY → `amount`; MONTHLY/QUARTERLY/ANNUAL → `amount / (freqMonths × 30)` (per-day share, matching the plan's "≈ ₹166/day" example — **correction** over the draft's literal `monthlyEquivalent`, which would over-reserve ~30×). `applyCategoryAllowance(raw, allowance) = max(raw − allowance, raw×0.80, 0)` (plan's ±20% clamp).
- `di/SpendsRepository.kt`: `private suspend fun interleavedDailyAllowance(): BigDecimal` = sum of `dailyPace` over **all** `interleavedCategoriesNow()` values (DAILY schedules are per-day caps, included).
- Apply overlay in `whatBudgetForDay(...)` and `nextDayBudget(...)` final returns (the two paths that recompute the stored daily budget on day-change / "add to today"). `updateDailyBudget` (explicit manual edit) and `setDailyBudget` (commit of an explicit number) are NOT touched.
- Tests: pure `dailyPace` + `applyCategoryAllowance` cases; repository test — write schedules into the DataStore, assert `whatBudgetForDay` is reduced by the daily allowance; no schedules → identical value.

## Task D — Interleaved Phase 6 (data calibration): pattern-miner engine

Real calibration is **blocked until the user provides the 6-month transaction export**. Build the engine now so it is ready to run.

- `interleaved/AnalyzeSpendingPatterns.kt` (pure): `suggestFrequency(dates)` (2+ occurrences; monthly gaps → MONTHLY; ~quarterly → QUARTERLY; annual/rare → ANNUAL; else null), `medianAmount(values)` (HALF_EVEN), `analyzeSpendingPatterns(spends: List<Transaction>)` → `List<ScheduleSuggestion(name, amount, frequency)>` grouped by `categoryKey`.
- `di/SpendsRepository.kt`: `applyScheduleSuggestions(suggestions)` — merges into `categorySchedulesStoreKey` via the existing `setCategoryCapsAndSchedules` write path.
- Tests: `interleaved/AnalyzeSpendingPatternsTest` with synthetic monthly/quarterly/annual series.
- NOTE in docs: wiring + validation harness + real defaults await the user's export.

## Task E — Whole-history analytics: past-periods viewer (largest)

- `data/dao/BudgetPeriodDao.kt`: nothing new needed (`getSpendsForPeriod(periodId)` LiveData exists).
- New `analytics/PeriodAnalyticsViewModel.kt` (`@HiltViewModel`): `period` LiveData (from `getAll()` filtered by id), `spends` LiveData (`getSpendsForPeriod(periodId)` → `List<ArchivedTransaction>`), `periods` LiveData (`getAll()`).
- New `analytics/PastPeriodAnalytics.kt` composable (sheet content for `PERIOD_ANALYTICS_SHEET`, arg `periodId`): converts archived rows to `Transaction` (now carrying category via Task B) and reuses the data-driven cards — `WholeBudgetCard` (period budget/dates/actualFinishDate), `SpendsCountCard`, `MinMaxSpentCard` ×2, `SpendCategoriesCard` (caps empty — caps are current-period DataStore), `CategoriesChartCard`, `SpendsTrendCard` (periods = all, so vs-previous works), `SpendsWeekdayCard`. Currency from `period.currencyCode` via `ExtendCurrency.getInstance`. Skip VM-bound/current-only cards (RestAndSpent, DaysLeft, Compare, AiInsight, Interleaved).
- Entry: `ButtonRow` "Past periods" in Analytics (below share summary) → new `PERIODS_LIST_SHEET` listing archived `BudgetPeriod`s (dates + total spent) → tap opens `PERIOD_ANALYTICS_SHEET`.
- Register both sheets in `home/BottomSheets.kt`; new strings `past_periods_title`, `period_analytics_title` (+ list empty text).
- Tests: pure date/currency plumbing where feasible; sheet wiring is UI (compile-level via pipeline).

## Definition of done
- All 5 items shipped; each golden-pipeline green; committed + pushed.
- CHANGELOG / MEMORY / CACHE / session-state updated.
- Phase 6 real-data calibration explicitly recorded as still awaiting the user's export.
