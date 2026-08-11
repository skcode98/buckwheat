# Interleaved Budget Categories — Implementation Plan (v2)

> **Status**: PHASE 1 SHIPPED (engine) — revised 2026-08-11 after Feature 9 (category caps) shipped.
> v2 reconciles this feature with the caps system instead of building a parallel one, which
> roughly halves the scope and removes all database-migration risk.
> Phase 6 (data calibration) remains deferred pending your 6-month transaction export.
> Open questions auto-resolved with the plan's documented defaults (calendar months; no
> untracked; Phase 5 stretch only).

## What changed from v1 (superseded plan)

| v1 (old plan) | v2 (this plan) | Why |
|---|---|---|
| New `BudgetCategory` Room entity + migration 14 → 15 | Extend the shipped caps: new DataStore key `categorySchedulesStoreKey`; **no migration** (current DB version is 13, untouched) | Caps already persist per-category amounts + notified state in DataStore; a parallel Room table would duplicate them |
| New `BudgetCategoryDao` + `getActiveInterleaved()` | Pure engine + one repository window-spend query | No new Room surface to migrate/test |
| "Kotlinx-datetime already in deps" | Use `java.time` (already the codebase standard) | kotlinx-datetime is **not** in `app/build.gradle.kts`; minSdk 29 makes `java.time` fine |
| Combined daily-allowance as the core | Stretch goal (Phase 5) | Rewriting wallet daily math is high-risk; ship window rollover + visibility first |
| Referenced `changeDayAction()` | `SpendsRepository` daily-recalc blocks (~lines 416–444, 714) | That function name doesn't exist in the codebase |

## Rationale (unchanged core)

Indian household budgets interleave: monthly groceries + quarterly medical + annual insurance
are one budget system with overlapping periods, not three separate budgets. Today the app only
understands a single daily-budget counter. This feature adds frequency-aware category budgets
that roll over on their own schedule, independent of the main budget period.

## Architecture

```
SHIPPED (Feature 9) — caps foundation:          ADDS (this feature) — scheduling:
  CategoryCap.kt        pure %/bucket logic       InterleavedBudget.kt  pure window math
  categoryCapsStoreKey  "name:amount;..."         categorySchedulesStoreKey "name:freq:anchorEpochDay;..."
  categoryCapNotified   "name:bucket;..."         notified entries carry windowStart ("name:bucket@epochDay")
  CategoryCapNotifier   channel "category_cap"    reuse as-is (progress source changes)
  CategoryCapsSheet     per-category amount       + frequency dropdown + anchor date picker
  CapProgressBar        in SpendCategoriesCard    reused by new InterleavedBudgetCard

A category WITH a schedule entry = interleaved budget (window-scoped, auto-rolls).
A category WITHOUT one = plain cap (today's behavior, backward compatible).
```

## Data model

```kotlin
enum class CategoryFrequency { DAILY, MONTHLY, QUARTERLY, ANNUAL }

data class InterleavedCategory(
    val name: String,               // matches stored category name (BuiltIn enum .name / Custom raw name)
    val amount: BigDecimal,         // per-window budget — reuses the cap amount
    val frequency: CategoryFrequency,
    val anchorEpochDay: Long,       // first window start: LocalDate.toEpochDay()
)
```

- **Serialization** — new key in `SettingsRepository.kt` (existing `categoryCapsStoreKey`
  format is untouched, so older data keeps working):
  `categorySchedulesStoreKey` = `"name:frequency:anchorEpochDay;..."`.
  `parseCategorySchedules` is defensive (drop malformed entries, like `parseCategoryCaps`).
- **Notified state** — `categoryCapNotifiedStoreKey` entries become
  `"name:bucket@windowStartEpochDay"` so a rolled window is detected even if the bucket is
  unchanged. Parse defensively: legacy `"name:bucket"` entries default to the current window.
- **Window math** — calendar arithmetic via `java.time` (handles clamping + leap years for free):
  `monthsElapsed = ChronoUnit.MONTHS.between(anchor, today).toInt() / freqMonths`
  `windowStart = anchor.plusMonths(monthsElapsed * freqMonths)`
  `windowEnd   = windowStart.plusMonths(freqMonths)`  // [start, end), tx at end excluded
  `freqMonths = 1 / 3 / 12` (DAILY = 0 → plain cap semantics, no window).

## Phase 1: Pure engine — `interleaved/InterleavedBudget.kt` (3-4h) — ✅ SHIPPED 2026-08-11

No Android/DataStore/Room imports. Unit-testable in the JVM.

```kotlin
// Current window for a scheduled category, [start, end). Returns null for DAILY/none.
fun windowFor(category: InterleavedCategory, today: LocalDate): Pair<LocalDate, LocalDate>?

// True when the window containing `today` differs from the one `recordedWindowStart` belongs to.
fun hasRolled(category: InterleavedCategory, today: LocalDate, recordedWindowStart: Long): Boolean

// Sum of SPENT transactions whose date falls inside the window, matched by stored category name.
fun windowSpent(transactions: List<WindowSpend>, category: InterleavedCategory, today: LocalDate): BigDecimal

// amount / freqMonths — the "monthly equivalent" used by the wallet stretch goal.
fun monthlyEquivalent(category: InterleavedCategory): BigDecimal

// Days (incl. today) left in the window (the count restarts at the next window start).
fun daysLeftInWindow(category: InterleavedCategory, today: LocalDate): Int

// At current pace, the day the window runs dry: spent / elapsedDays * windowDays >= amount.
fun projectedExhaustionDate(category, today, spent): LocalDate?
```

`WindowSpend` is a tiny `(date: Date, value: BigDecimal, type, category: String)` view or a
suspend function signature that the repository adapts — decide at implementation time; keep the
pure functions fed by a plain list so tests don't need Room.

Reuse `categoryCapPercent` / `categoryCapBucket` / `highestNewlyReachedCapBucket` from
`CategoryCap.kt` for progress and crossings — the window replaces "current budget period" as the
progress source.

## Phase 2: Repository rollover wiring — `SpendsRepository.kt` (3-4h) — ✅ SHIPPED 2026-08-11

- **Window spend source**: in-memory filter over the active transactions (SPENT rows only,
  window date range + category match) via the pure `windowSpent`; no new DAO query needed.
- **Rollover check on every mutation**: `resyncInterleavedNotified()` runs in `addSpent` /
  `removeSpent` (before the cap-alert evaluation so the fresh window is measured). For each
  scheduled category it recomputes `windowFor(today)` and, when the stored notified window
  start differs, resets that category's bucket (a new window can announce 80%/100% again).
- **Notified state**: `categoryCapNotifiedStoreKey` entries are now `"name:bucket@windowStartEpochDay"`
  (legacy `"name:bucket"` parses with the sentinel window so the first rollover resets). Plain
  caps keep the suffix-less format for backward compatibility.
- **setBudget independence**: `clearCategoryCapNotifiedNow()` keeps windowed (non-DAILY)
  scheduled entries across a period change; plain caps (and DAILY schedules) still clear.
- **`setCategoryCapsAndSchedules`**: caps + schedules + notified reset in one `edit {}` block.
- **Clock injection**: uses `GetCurrentDateUseCase` (already `open`, injectable).
- **Progress source switch**: cap alerts now measure scheduled categories against the current
  window (`categoryProgressTotal` → `windowSpent`) instead of the budget-period total.

## Phase 3: Settings UI — `CategoryCapsSheet.kt` (2-3h)

- Each cap row gains a frequency dropdown (`DAILY/MONTHLY/QUARTERLY/ANNUAL`) and, when not DAILY,
  an anchor date picker (reuse `base/datePicker`). DAILY stays "plain cap" (current behavior).
- "Add (+)" gets three quick templates: **Monthly Essentials** (Groceries, Fuel, Transport),
  **Quarterly Big Tickets** (Medical, Clothing, Repairs), **Annual Obligations** (Insurance,
  Maintenance) — each template sets frequency + anchor = today.
- `CategoryCapsViewModel` exposes `interleaved` LiveData derived from `SettingsRepository`
  `categorySchedulesStoreKey` flow.
- Strings EN-only in `values/strings.xml` (12 existing cap strings stay; add ~6 new: frequency
  labels, anchor picker title, template names).

## Phase 4: Analytics card — `InterleavedBudgetCard` (3-4h)

- New card after `SpendsBudgetCard` in `Analytics.kt`, rendered only when ≥1 scheduled category exists.
- Per category: name + emoji, window date range ("Sep 1 – Sep 30"), progress bar (reuse
  `CapProgressBar`: amber ≥80%, red ≥100%), velocity line
  ("at ₹45/day → 70% of window in 12 days"), and projected exhaustion date.
- Data: combine the schedules DataStore flow with window-spent totals from the DAO query in a
  repository `Flow`; observe via `.asLiveData()` (no `runBlocking`, no `remember{}` without keys).

## Phase 5 (stretch, no commitment): wallet daily-allowance integration (6-8h)

Aggregate `monthlyEquivalent(category)` across scheduled categories into the wallet's daily
counter, clamped to `dailyBudget ± 20%` (existing `spentFromDailyBudgetStoreKey` reallocation
blocks at `SpendsRepository.kt:416-444`, `714`). Do **not** start until Phases 1-4 ship and the
UX is validated — this touches the core wallet math every screen depends on.

## Phase 6 (deferred — PENDING YOUR 6-MONTH DATA): calibration

When you provide the transaction export:
1. Pattern miner (`interleaved/AnalyzeSpendingPatterns.kt`): group by category, infer frequency
   (4-6 months → MONTHLY, every 3 → QUARTERLY, once/yr → ANNUAL), median amount → suggested `amount`.
2. Backfill `categorySchedulesStoreKey` rows for detected patterns.
3. Validation harness: replay export through engine; assert projected-vs-actual within tolerance.

## Edge cases & semantics

| Case | Behavior |
|---|---|
| Anchor on Jan 31, monthly | `plusMonths` clamps (Feb 28) — correct via `java.time` |
| Feb 29 anchor, annual | Next window clamps to Feb 28 in non-leap years; `monthsElapsed` stays exact |
| Tx exactly on `windowEnd` | Excluded (half-open `[start, end)`) — belongs to the next window |
| Backfilled/future txs | Excluded from the current window by the date range; never counted twice |
| Main period finished early | Interleaved windows unaffected; card keeps showing current window |
| Custom category renamed/deleted | Schedule keyed by stored name — orphaned schedule entry is harmless; offer cleanup in sheet |
| Frequency changed mid-window | Reset notified state; anchor unchanged (user edits anchor separately) |
| Caps sheet opened with legacy data | No schedules → all plain caps; parse helpers must not crash on legacy formats |

## Risks & mitigation

| Risk | Impact | Mitigation |
|---|---|---|
| Parallel notification state (window vs period) confuses users | Med | Reuse same channel; the 80%/100% semantics are identical, only the measurement window differs |
| Window math drift (anchor vs calendar months) | Med | Single pure `windowFor` used by repo, UI, and tests; calendar-month rules documented above |
| DAILY frequency scope creep | Low | DAILY = plain cap; no window, no rollover — nothing new |
| Analytics card cost (window-spend query per category) | Low | One indexed query per scheduled category; count is tiny (<10) |
| Wallet allowance integration destabilizes core | High | Kept out of scope until Phases 1-4 validated (Phase 5 stretch) |

## Test plan

- `interleaved/InterleavedBudgetTest.kt` (~15): `windowFor` (anchor mid-month, month-end clamp,
  leap-year anchor, DAILY→null), `hasRolled` (same/rolled window), `windowSpent` (in-window,
  on-end excluded, future tx excluded, category-name match), `monthlyEquivalent`,
  `daysLeftInWindow` (incl. today), `projectedExhaustionDate`, reuse of `categoryCapBucket`.
- Serialization round-trip + backward-compat (legacy `"name:amount"` caps key, legacy
  `"name:bucket"` notified entries).
- Repository rollover: fake `GetCurrentDateUseCase`; crossing a window boundary resets notified
  bucket so a second 100% crossing notifies again; `setBudget` does NOT reset scheduled windows.

## Definition of done

- [x] Pure engine + tests green; no Android imports in `InterleavedBudget.kt` (28 tests, `InterleavedBudgetTest`)
- [x] Schedules persist/load via DataStore; legacy data unaffected (codec + window-aware notified parse, 11 `InterleavedScheduleCodecTest`)
- [x] Rollover resets notifications exactly once per window crossing (4 `InterleavedRolloverTest`; `resyncInterleavedNotified` + window start in notified entries)
- [ ] Caps sheet edits frequency + anchor; single `edit {}` write
- [ ] `InterleavedBudgetCard` renders windows, progress, velocity; hidden when no schedules
- [ ] Full pipeline green: `:app:spotlessApply :app:testDebugUnitTest :app:assembleDebug`
- [ ] CHANGELOG / MEMORY / CACHE updated; committed + pushed

## Open questions — RESOLVED 2026-08-11 (auto-decision, plan defaults)

1. Calendar quarters (`plusMonths(3)`) vs fixed 90-day windows — **calendar months** (intuitive, clamps via `java.time`).
2. Should a scheduled category also be allowed to be *untracked* (visible, no notifications)? **No** — notifications are the point of a budget.
3. Phase 5 stretch: do you want wallet integration at all, or is per-category visibility enough? **Stretch only** — build Phases 1-4 first; wallet integration deferred unless the UX warrants it.

## Dependencies

- Hilt (`@HiltViewModel` already used by `CategoryCapsViewModel`)
- Room: **no migration**, one new DAO query
- Notifications: reuse `CategoryCapNotifier`
- `java.time` (already standard) — **no** kotlinx-datetime needed
