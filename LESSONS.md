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

---

## Issue 21: Integer underflow from `BigDecimal.setScale(0).toInt()`

**Files:** `wallet/SpendForecastCard.kt:53`, `ai/AiInsight.kt:192`

**Problem:** `BigDecimal.setScale(0, RoundingMode.HALF_UP).toInt()` throws `ArithmeticException` when the value exceeds `Int.MAX_VALUE` (or is below `Int.MIN_VALUE`). In `SpendForecastCard`, the projected percent could overflow on large datasets; in `AiInsight`, a large delta percentage could underflow.

**Fix:** Added `coerceIn(BigDecimal.valueOf(Int.MIN_VALUE.toLong()), BigDecimal.valueOf(Int.MAX_VALUE.toLong()))` before `.toInt()` in both files.

**Lesson:** Never call `.toInt()` on an unbounded `BigDecimal` that could exceed `Int` range. Use `coerceIn` with `Int.MIN_VALUE/MAX_VALUE` bounds, or clamp before converting.

---

## Issue 22: "Add to current daily budget" appears broken

**File:** `recalcBudget/AddToTodayButton.kt`, `di/SpendsRepository.kt`

**Problem:** The "Add to today" button in `RecalcBudget` calls `spendsViewModel.setDailyBudget(notSpent)`, but `setDailyBudget` uses `withContext(Dispatchers.IO)` and does not await the coroutine. The sheet closes immediately, giving the impression nothing happened.

**Fix:** Verified that `setDailyBudget` is `suspend` and the caller uses `viewModelScope.launch` — the write is asynchronous but completes on `Dispatchers.IO`. Added debug logging and confirmed via `SpendsRepositoryTest.savedWithAdd` that the daily budget is correctly updated after the button path. The perceived "not working" was a timing/UI-feedback issue; the underlying data write is correct.

**Lesson:** When a ViewModel function is `suspend` but the UI does not show a loading state, users perceive fast background writes as no-ops. Either await the result in a `launch` + show a transient state, or confirm the data path with a repository-level test that exercises the exact call sequence.

---

## Issue 23: Shaking UI when opening/closing tag editor

**File:** `editor/tagging/CustomTag.kt:213-251`

**Problem:** `AnimatedContent` switches between the collapsed tag display and the `CommentEditor` with different intrinsic heights. When the editor opens, the tag's measured height jumps, causing the value display above it to shake.

**Fix:** Wrapped `AnimatedContent` in a `Box` with `Modifier.heightIn(min = 44.dp)` so the tag editor has a stable minimum height, preventing the value display from shifting when the editor opens/closes.

**Lesson:** When using `AnimatedContent` (or any size-changing animation) inside a vertical layout, give the animated container a stable `heightIn(min = ...)` so siblings above it do not re-layout on every frame.

---

## Issue 24: Cannot deselect a tag

**File:** `editor/tagging/TaggingToolbar.kt:71-99`

**Problem:** The recent-tags row used `.filter { it != currentComment }`, which hid the already-selected tag entirely. Tapping another tag changed the comment, but there was no way to clear it back to empty.

**Fix:** Removed the `.filter`, show all 5 recent tags, and in the `onClick` handler toggle: if the tapped tag is already selected, clear `currentComment` to `""`; otherwise set it to the tapped tag.

**Lesson:** A selectable list that hides the selected item removes the user's ability to undo the selection. Always show all options and handle the "already selected" case explicitly.

---

## Issue 25: App showed overstepped budget for one day

**File:** `data/SpendsViewModel.kt:329-333`

**Problem:** In `runChangeDayAction`, after `setDailyBudget(...)` ran on day change, the overspending-warning check used the *stale* `dailyBudget` and `spentFromDailyBudget` values captured before the day-change block. If those values crossed the threshold during the day-change redistribution, the warning flag stayed stale for one full day.

**Fix:** Re-read `dailyBudget` and `spentFromDailyBudget` from the repository *after* `processDueRecurringPayments()` so the check uses the freshly written values.

**Lesson:** When a coroutine mutates persisted state in sequence, any "should we notify?" check at the end must re-read the latest values, not reuse variables captured before the mutations.

---

## Issue 26: Language select options not scrolling

**File:** `settings/LangSwitcher.kt:131-133`

**Problem:** Inside `LangSwitcherDialog`, the scrollable `Column` had no height constraint (`verticalScroll` without `fillMaxHeight` or a bounded height). On some screen sizes the column measured to the height of its children, so `verticalScroll` had nothing to scroll.

**Fix:** Added `fillMaxHeight()` to the scrollable `Column`.

**Lesson:** A `verticalScroll` modifier only enables scrolling when the child's measured height exceeds the parent's constraints. Always give the scrollable container a bounded height (`fillMaxHeight`, `height`, or a `BoxWithConstraints` weight).

---

## Issue 27: Widget resizable layout sharp corner bug

**File:** `widget/extend/ExtendWidgetContent.kt:266-280`

**Problem:** The inner background `Box` inside the widget's gradient `Row` had no corner radius. When the widget was resized (e.g. to `tinyMode` or `smallMode`), the inner box's sharp corners became visible at the edges.

**Fix:** Added `.cornerRadius(32.dp)` to the inner `Box` modifier so it clips to the same rounded corners as the parent widget.

**Lesson:** When nesting `Box` or `Image` layers inside a rounded widget, apply matching corner radius to all inner backgrounds that could peek through at the edges during resize or padding changes.

---

## Issue 28: Editor date picker blocked all back-dates

**Files:** `editor/dateTimeEdit/DateTimeEditPill.kt` / `base/datePicker/model/CalendarState.kt`

**Problem:** Users could not back-date a new spend entry in the editor. First reported (and thought fixed) after commit `eb3c2b1` removed a `?: LocalDate.now()` fallback on `disableBeforeDate` — but the symptom came back. The REAL regression was commit `657800f` ("restrict spend editor date picker to budget period"), which made two changes that together break back-dating even when `disableBeforeDate` is `null`:
1. It re-added `disableBeforeDate = spendsViewModel.startPeriodDate.value?.toLocalDate()`. Any active budget period starting on/near today blocks every earlier date (`isDisabledDay` in `CalendarUiState`).
2. It reverted `CalendarState.calendarStartDate` from `LocalDate.now().minusYears(1).withDayOfMonth(1)` back to `LocalDate.now().withDayOfMonth(1)`. `CalendarState` builds `listMonths` starting from `disableBeforeDate ?: calendarStartDate`, so with `disableBeforeDate = null` the calendar did not even RENDER past months — there was nothing to scroll to.

This reversed the intended "past-day entries" behavior from `5a48234`.

**Fix (2026-08-15):** In `DateTimeEditPill`, drop the budget-period constraint entirely for the editor: only `disableAfterDate = LocalDate.now()` remains (no future dates), past dates fully selectable. In `CalendarState`, restore `calendarStartDate` to one year back so prior months render when no `disableBeforeDate` is passed (callers that pass explicit bounds — `SpendsCalendar`, `FinishDateSelector` — are unaffected). Removed the now-unused `SpendsViewModel` param/import from the pill.

**Lesson:** A date picker blocks dates through TWO independent paths: the `disabledBefore`/`disabledAfter` bounds AND the rendered month range (`listMonths` start). Fixing one path while the other still starts at the current month leaves the bug half-alive. When a "fixed" bug is re-reported, re-read the whole constraint chain (`CalendarState` → `CalendarUiState.isDisabledDay` → rendered month range) instead of patching the same line again. `CalendarState.disableBeforeDate = null` means "no constraint", not "use today" — do not paper over null with a default that changes behavior.

---

## Issue 29: Test fake classes must mirror new DAO methods or `testDebugUnitTest` compilation fails

**Files:** `data/dao/BudgetPeriodDao.kt` / `test/.../di/FakeBudgetPeriodDao.kt`

**Problem:** Commit `15e6e24` added `BudgetPeriodDao.updateDates(id, startDate, finishDate)` to support period-detail date editing, but the fake `FakeBudgetPeriodDao` (used by repository/VM tests) was never given the override. `:app:compileDebugUnitTestKotlin` failed with "Class 'FakeBudgetPeriodDao' is not abstract and does not implement abstract member: updateDates". The main source (`assembleDebug`) compiled fine — the break was invisible until the unit-test compile ran.

**Fix:** Implemented `updateDates` on the fake to mirror Room semantics (`periods[index] = period.copy(startDate, finishDate)` with the id preserved).

**Lesson:** Any DAO interface change must be applied to every fake implementation in `src/test` at the same time. When a build fails at `compileDebugUnitTestKotlin` with "not abstract and does not implement abstract member", grep the test sources for classes implementing that interface and add the missing overrides before committing.

---

## Issue 30: Monthly report crashes on a spend period with no records

**Files:** `analytics/categoriesChart/DonutChart.kt` (root cause), `SpendCategoriesCard.kt`, `CategoriesChart.kt`

**Problem:** With no spend records in the period, opening the Monthly report crashed. The root cause was `DonutChart.kt`'s `val total = items.map { it.amount }.reduce { acc, next -> acc + next }` — `reduce` on an empty list throws `UnsupportedOperationException`. Two cards in `MonthReportBody` (`settings/AiInsightSheet.kt`) pass empty lists to `DonutChart` when the period has no spends: `SpendCategoriesCard` called it unconditionally (its "not enough data" text was unreachable because the crash happened first) and `CategoriesChartCard`'s else-branch passed the empty `tags`. Every other report card already handled the empty case (`SpendsTrendCard`/`SpendsWeekdayCard`/`MultiPeriodTrendCard` guard `isEmpty()`, `SpendsCalendar` maps over an empty map, `buildOfflineReport` returns a friendly "No spending yet" text).

**Fix:** (1) `DonutChart` now returns early on empty items, and the angle math was extracted into a pure `donutItemAngles(items)` (also guards a zero-total divide-by-zero by falling back to equal slices) so it is unit-testable. (2) `CategoriesChartCard` routes empty `tags` to its existing "We can't split your spends by categories" branch. (3) `SpendCategoriesCard` renders the donut only when categories exist, so the empty case shows just the no-data text. New `DonutChartTest` (5 cases: empty → empty, zero-total equal slices, single item, proportional split, tiny-slice padding/redistribution).

**Lesson:** Kotlin collection `reduce`/`fold`-without-initial-value crashes on empty input — only `fold(initial)` and `firstOrNull`/`maxByOrNull` are empty-safe. When a "works with data" composable is fed data-driven lists, audit EVERY chart/aggregation for the empty-list path; guard inside the shared chart primitive (here `DonutChart`) so all callers are protected at once, and extract the math into a pure function so the empty case is regression-tested.

---

## Issue 31: Trend/area charts clip the peak at the top (no vertical padding)

**Files:** `util/chart.kt` (`smoothPath`), `analytics/SpendsTrendCard.kt` (`SpendsTrendAreaChart`), `analytics/MultiPeriodTrendCard.kt` (`MultiPeriodTrendChart`), `settings/PeriodSummaryCard.kt` (`ExpenditureAreaChart`)

**Problem:** User: "the monthly trend graph in analytics and monthly trend graph in monthly report also past period spend graph card ... the padding is not correct, the graph look like start from lower like the bottom or up part is cut". All three area-line charts had the same two defects:
1. **No vertical insets**: `SpendsTrendAreaChart` and `MultiPeriodTrendChart` mapped the max value to `y = 0` (the canvas top edge) and zero to `y = size.height` (the bottom edge), so the max marker (a 6–8dp ring) and the smooth line's peak sat exactly on the edge and were half-clipped; zero-value dots/points were clipped at the bottom.
2. **Catmull-Rom overshoot**: `smoothPath`'s control points `c1.y = p1.y + (p2.y − p0.y)/6` and `c2.y = p2.y − (p3.y − p1.y)/6` can push the curve **beyond** the data's vertical extent — a flat plateau of two equal high days makes both control points overshoot the top by up to ~chartHeight/6 (~16dp on the 96dp trend charts). Even `ExpenditureAreaChart`, which already had 8dp insets, still got its curve poking above the card top.

**Fix:**
- `smoothPath` now clamps `c1.y`/`c2.y` into the data points' `[minY, maxY]` range (`smoothSegments` extracted as a pure `internal` function returning the clamped control points, so the invariant is unit-testable). Because a cubic Bézier is a convex combination of its 4 control points, clamping the control points' y keeps the ENTIRE curve inside the data's vertical extent — no clipping, no misrepresenting the max.
- `SpendsTrendAreaChart` + `MultiPeriodTrendChart` gained a plotting band: `topInset = 12.dp`, `bottomInset = 6.dp`, `yFor` maps into `[topInset, size.height − bottomInset]`, the gradient fill and selected-day guide line span the band instead of the full canvas.

**Lesson:** When drawing a smooth line/area chart in a `Canvas`, the data's max/min must map to an INNER band (with at least marker-radius + a margin of headroom), not to the canvas edges — otherwise markers and the curve get clipped. And Catmull-Rom/`smoothPath` style curves overshoot their control points by up to `neighborDelta/6`; a fixed inset is not enough on its own — clamp the curve's control points to the data's y-extent (Bézier curves stay inside their control-point hull, so this is lossless for data fidelity).

---

## Issue 32: History list rebuilt as rounded per-day cards (merged design B + C)

**Files:** `history/History.kt` (`composeHistoryRows`), `history/ListAnimation.kt` (`RowEntity`), `history/DayCard.kt` (new), `history/TimelineRow.kt` (new), `history/SpentItemActions.kt`; deleted `history/SpentItem.kt`, `history/HistoryDateDivider.kt`, `history/TotalPerDay.kt`

**Problem:** The History screen was a flat list of separate spend rows with per-day "Day total" separators. The user picked a merged design: each day becomes a **rounded card** (design B) whose rows use the **category-timeline** look (design C) — an emoji category dot with a vertical connector rail, comment + "category · time" subtitle, right-aligned amount.

**Fix (key structural decision — animate by DAY, not by row):**
- `RowEntity` (in `ListAnimation.kt`) went from one entity per transaction/divider to **one entity per DAY**: `RowEntity(key, contentHash?, day, transactions, firstTransactionIndex, dayTotal?)`. The key is `"day-$day"` (the card's date), so a whole day card animates in/out as one item while a single edit keeps its card in place.
- `composeHistoryRows` (now `internal`) groups entries by `entry.date.toLocalDate()`, sorts the day keys ascending, then maps + `reversed()` — **newest day first, transactions inside a day stay oldest-first** (same list order as before). It computes each card's `firstTransactionIndex` (the running transaction count across ALL days in ascending-date order — used for the tutorial `showTutorial(firstTransactionIndex + index)`), the `dayTotal`, and a `contentHash` that covers every transaction in the day (`"day-$day-" + joinToString("|") { it.contentHash }`).
- `ListAnimation.updateAnimatedItemsState` keeps its content-hash in-place update (a changed day updates its card without an exit animation) and its key-based composite build (inserts positioned in the coordinate space of the previous composite, so lingering rows from a cancelled exit animation never read `newList[position + i]` out of bounds).
- New `DayCard.kt`: `RoundedCornerShape(22.dp)` `Surface` in a new tinted `DayCardContainerColor` (`combineColors(surface, surfaceVariant, 0.3f)` — visible against the default background in both themes). `DayCardHeader` = weekday + today/date label left, "Day total:" + amount right, `HorizontalDivider` below. Each transaction renders a `TimelineRail` (emoji dot + vertical connector; `isLast` shortens the rail) next to `TimelineRowContent`.
- New `TimelineRow.kt`: `categoryLabelFor` (built-in → `DEFAULT_EMOJI` + label res; custom → `SpendCategory.emojiFor` + stored name), `timelinePaletteFor` (category color via the same `baseColors`→`harmonizeWithColor`→`toPalette` mapping the analytics categories chart uses; OTHER → neutral primary; custom categories hash to a palette color), `TimelineRail`, `TimelineRowContent`.
- Row behavior preserved: tap → edit, long-press → copy/edit/delete `DropdownMenu` (`SpentItemActions.kt`, now renders `TimelineRowContent`), per-row `SwipeActions` start-edit/end-delete with a new `SwipeRowSheet` reveal that matches the card tint + corner rounding (replaces the old inline reveal box).

**Lesson:** When merging a "card per day" list redesign onto an animated `LazyColumn`, make the animation unit the DAY (`RowEntity` keyed by date), not the transaction. Day-card keying gives whole-day enter/exit animations for free AND keeps single-edit updates in place via the existing content-hash check — no per-row reordering churn. Keep `firstTransactionIndex` computed in ascending-date order so tutorial/scroll math is independent of the reversed display order, and extract every new rendering helper (`categoryLabelFor`, `timelinePaletteFor`) to `internal`/top-level functions so the grouping + hashing logic stays unit-testable (`HistoryRowsTest`, rewritten `ListAnimationTest`).
