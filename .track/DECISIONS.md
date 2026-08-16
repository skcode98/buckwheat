# Decisions Log

> Every decision taken during feature work, with the reasoning behind it. Append newest at the top.
> Format: **Date — Decision** — why.

---

## 2026-08-16 — Decision: revert the History day-card redesign back to the earlier flat-list design, keeping only the extra row spacing

**Decision**: Surgically reverted the History pieces of the `a0883e2` redesign (committed `b581ab1`): restored `history/SpentItem.kt`, `history/HistoryDateDivider.kt`, `history/TotalPerDay.kt` and rewound `History.kt`, `ListAnimation.kt`, `SpentItemActions.kt`, `ListAnimationTest.kt` to their `a0883e2^` state; deleted the redesign-only `DayCard.kt`, `TimelineRow.kt`, `HistoryRowsTest.kt`. Re-applied the user's actual request on the restored design: `SpentItem` bottom padding `14.dp` → `18.dp`. Every non-History fix bundled in `a0883e2` (chart padding, donut empty-crash, editor back-dating) was left untouched.

**Why**: User: "you have simplified the history tab too much, i like earlier design i just wanted bit space there only". The redesign overshot a small "add some space in record" request. `a0883e2` was a single commit bundling the redesign with unrelated fixes, so `git revert a0883e2` was NOT an option — a file-level revert (`git checkout a0883e2^ -- <history paths>` + `git rm` of the redesign-only files) isolated the visual change without losing the bundled fixes. Spacing was moved to the single row source (`SpentItem`) so both the swipe and read-only render paths breathe equally. The restored layout also structurally removes the Issue 34 crash trigger (`SwipeActions` back as the direct LazyColumn item root, no `IntrinsicSize.Min`).

**Outcome**: Golden pipeline green — `spotlessApply` + `testDebugUnitTest` + `assembleDebug` = **315 tests, 0 failures, 32 suites** (321 − 6 `HistoryRowsTest`). Committed `b581ab1`, pushed. See LESSONS.md Issue 36.

---

## 2026-08-16 — Decision: re-add the editor date-picker period floor per the user's exact spec, decoupling "shown months" from "enabled days"

**Decision**: The editor's spend date picker (`DateTimeEditPill` → `DatePickerDialog` → `CalendarState`) now passes `disableBeforeDate = startPeriodDate`, `disableAfterDate = LocalDate.now()`, `showBeforeDate = startPeriodDate`, `showAfterDate = finishPeriodDate`. `CalendarState` gained `showBeforeDate`/`showAfterDate` (defaulting to the disable bounds) so the rendered month range (`listMonths`) and the enabled-day bounds (`disabledBefore`/`disabledAfter`) can differ.

**Why**: User: "the date selector display for year in editor when adding the new spend its should only show the spend period and only enable the spend start till today". This deliberately re-adds the period floor that was removed on 2026-08-15 (LESSONS Issue 28) — but the 2026-08-15 revert removed the floor because the old version (a) used `disableAfterDate = period end` (enabling dates after today) and (b) reverted `calendarStartDate` to the current month, so with no period the picker rendered no past months. This implementation avoids both regressions: enable range is explicitly `start → today` (not period end), the shown range is exactly the period, and `calendarStartDate` (one year back) is untouched, so a null period keeps the "no constraint" behavior. The two paths are kept in sync (shown range ⊇ enabled range) so there is no "rendered but unreachable" or "reachable but not rendered" state.

**Outcome**: Golden pipeline green — `spotlessApply` + `testDebugUnitTest` + `assembleDebug` = **321 tests, 0 failures, 33 suites**. Committed `fb6b619`, pushed. See LESSONS Issue 28 (amendment) + Issue 35.

---

## 2026-08-15 — Decision: fix the History swipe crash at the `IntrinsicSize.Min` wrapper rather than changing the swipe composable

**Decision**: Removed `Modifier.height(IntrinsicSize.Min)` from the per-transaction `Row` in `history/DayCard.kt`. The row's weighted `Box` contains `SwipeActions`, which is built on `BoxWithConstraints` (a `SubcomposeLayout`) — an `IntrinsicSize.Min` row forces an intrinsic measurement pass, and SubcomposeLayouts throw `IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout layouts is not supported` (crash log `Downloads/buckwheat-crash-20260815-181642.txt`). The `IntrinsicSize.Min` inside `SwipeRowSheet` (wraps only a `Surface` + plain `SpentItemActions`) was kept.

**Why**: The crash is a property of asking a SubcomposeLayout for intrinsic sizes — the correct fix is to stop asking, not to rework `SwipeActions` (which is old, proven code). The wrapper is also a no-op visually: `TimelineRail` and the weighted `Box` are both wrap-content, so the row sizes identically without the intrinsic hint. This matches the codebase's "fix in the right layer, keep the shared primitive intact" pattern.

**Outcome**: Golden pipeline green — `spotlessApply` + `testDebugUnitTest` + `assembleDebug` = **321 tests, 0 failures, 33 suites**. On-device swipe E2E was skipped at the user's request (no emulator this run); verification = crash-stack analysis + old-vs-new structure comparison + green unit tests. See LESSONS.md Issue 34.

---

## 2026-08-15 — Decision: History redesigned as rounded per-day cards whose rows use the category-timeline layout (merged design B + C); animate by DAY, not by row

**Decision**: Rebuilt the History list as one **rounded card per day** (design B) whose rows use the **category-timeline** look (design C): `RowEntity` in `history/ListAnimation.kt` went from one entity per transaction/divider to **one per day** — `RowEntity(key, contentHash?, day, transactions, firstTransactionIndex, dayTotal?)` keyed by the day's date (`"day-$day"`). `composeHistoryRows` groups entries by `entry.date.toLocalDate()` ascending then `reversed()` (newest day first; transactions inside a day stay oldest-first), computes `firstTransactionIndex` in ascending-date order, `dayTotal`, and a `contentHash` covering every transaction in the day. New `DayCard.kt` (22dp rounded card in `DayCardContainerColor` = `combineColors(surface, surfaceVariant, 0.3f)`, header + per-row `TimelineRail` and `SwipeRowSheet`) and `TimelineRow.kt` (`categoryLabelFor`, `timelinePaletteFor` mirroring the analytics `baseColors`→`harmonizeWithColor`→`toPalette` mapping). Deleted `SpentItem.kt`/`HistoryDateDivider.kt`/`TotalPerDay.kt`; `SpentItemActions.kt` preserved tap-edit + long-press copy/edit/delete, now rendering `TimelineRowContent`.

**Why**: The user explicitly chose the merged B+C concept, and day-card keying is the structurally right animation unit — a whole day animates in/out as one item while a single edit keeps its card in place via the existing content-hash check (no per-row reordering churn, no regression risk to the `ListAnimation` crash fixes). Keeping `firstTransactionIndex` ascending makes tutorial/scroll math independent of the reversed display order. Colors reuses the analytics category mapping so the same category is drawn identically everywhere.

**Outcome**: Rewritten `ListAnimationTest` (4) + new `HistoryRowsTest` (6; grouping/filter/hash/archived-in-search). Golden pipeline green: **305 tests, 0 failures** (299 → 305). See LESSONS.md Issue 32.

---

## 2026-08-15 — Decision: fix chart clipping at the shared `smoothPath` primitive + add a plotting band to the trend charts

**Decision**: Fixed "the graph look like start from lower ... the bottom or up part is cut" across the three smooth area-line charts (`SpendsTrendCard`, `MultiPeriodTrendCard`, `PeriodSummaryCard.ExpenditureAreaChart`) with two coordinated changes:
1. **`smoothPath` clamps control-point y into the data's `[minY, maxY]`** (curve math extracted to pure `internal smoothSegments`). Catmull-Rom control points overshoot the data extent by up to `neighborDelta/6` (a two-equal-high-days plateau pushed the curve ~16dp above the top on the 96dp charts) — because a cubic Bézier lies inside the convex hull of its control points, clamping c1.y/c2.y guarantees the entire curve stays in-band. This fixed `ExpenditureAreaChart`, which already had 8dp insets but still poked above the card.
2. **`SpendsTrendAreaChart` + `MultiPeriodTrendChart` plot inside a band** (`topInset = 12.dp`, `bottomInset = 6.dp`). Previously they mapped the max to `y = 0` (top edge) and zero to `y = size.height` (bottom edge), so the peak marker ring (6–8dp) and the line were half-clipped and zero-dots clipped at the bottom.

**Why**: Fixing the shared `smoothPath` protects every caller (all three charts use it, and the clamping is lossless for data fidelity), while the inset band gives the markers real headroom — the clamp alone can't save a marker whose center sits on the edge. This mirrors the codebase's "fix in the shared primitive + pure function + regression test" pattern (`SmoothPathTest`, 4 cases).

---

## 2026-08-15 — Decision: guard the shared donut chart against empty items instead of patching each caller

**Decision**: Fixed the "Monthly report crashes on a period with no spend records" bug at the shared primitive: `DonutChart` now early-returns on empty `items`, and the per-item angle math was extracted into a pure `internal donutItemAngles(items)` (empty → `emptyList()`, zero-total → equal `360/n` slices, tiny slices padded to the 28° minimum with redistribution). The two empty-data callers were then made visually correct: `CategoriesChartCard` routes empty `tags` to its existing "We can't split your spends by categories" branch, and `SpendCategoriesCard` renders the donut only when categories exist (its "not enough data" text is now reachable).

**Why**: The crash came from `reduce` on an empty list inside `DonutChart`, which is fed by several data-driven callers. Guarding the primitive protects every current and future caller in one place, while extracting the math keeps the empty/zero-total edge cases unit-testable (5 new `DonutChartTest` cases) — matching the codebase's "pure function + regression test" pattern.

---

## 2026-08-15 — Decision: AI model is optional (blank = provider default); model dropdown removed from settings sheet

**Decision**: `6e9a23a` made the model field optional in `AiBackendConfig`/`resolveAiBackendConfig` (blank model is simply omitted from the chat-completions body so the service provider picks its default). `10aa493` then removed the model state, dropdown, and `VoiceAiSettingsViewModel` dependency from `VoiceAiSettingsSheet.kt` — the settings sheet now manages only provider URL + API key. `loadFreeModels`/`DEFAULT_MODEL_PRESETS`/`VoiceAiSettingsViewModel` remain in the codebase but are no longer called from the sheet.

**Why**: The single-backend migration (`7e0d0f8`) made the backend fully user-supplied and routing/deduping is the backend's job. A model dropdown that fetched `/v1/models` and could break the whole sheet for a misconfigured URL added friction; the backend defaults to a sensible model when none is sent, so the UI no longer needs to guess model ids. The test connection uses `model = ""` to validate URL + key only.

---

## 2026-08-13 — Decision: Interleaved budgets removed entirely (engine, UI, tests)

**Decision**: Deleted the interleaved-budgets feature — `interleaved/InterleavedBudget.kt`, `interleaved/AnalyzeSpendingPatterns.kt`, `analytics/InterleavedBudgetCard.kt`, `settings/InterleavedAnchorSheet.kt`, and all 5 interleaved test suites. `CategoryCapsSheet` went back to a plain cap editor (+ Remove cap + **Auto** = seed the cap with the current budget); `whatBudgetForDay`/`nextDayBudget` return plain recomputed values again (no daily-allowance overlay).

**Why**: The feature added window-rollover math, schedules, and a second progress source on top of plain per-period caps, and its Settings/analytics surfaces kept fighting the app's standard UI. With the feature gone, the caps flow is simpler and the codebase loses ~2k lines of schema/codec/rollover complexity. **Backward-compat detail**: `parseCategoryCapNotified` still strips a legacy `@windowStartEpochDay` suffix (`substringBefore('@')`) so pre-removal persisted notified strings keep parsing; the schedules DataStore key is simply never written again.

---

## 2026-08-13 — Decision: Auto-categorization moved off the ViewModel onto an application-scoped background scheduler

**Decision**: New `data/categories/CategoryAssigner.kt` (offline-first then AI, over `transactions` + `archived_transactions`, persisted) + `data/categories/CategoryAssignmentScheduler.kt` (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`, `AtomicBoolean`-coalesced `schedule()`, dirty-flag rescan, `StateFlow isRunning`, re-arm in `finally`). `SpendCategoriesViewModel` now just delegates; `SpendsRepository.addSpent`/`importTransactions` trigger scheduling.

**Why**: The old inline path blocked the import/add caller on the AI provider and died with the screen (ViewModel scope). An application-scoped scheduler means categorization survives navigation, coalesces concurrent triggers, and never blocks the wallet/editor. Coalescing via `AtomicBoolean` keeps it single-runner; the dirty flag + `finally` re-arm guarantee no uncategorized row is stranded when work lands mid-run.

---

## 2026-08-12 — Decision: Category budget utilization rendered as battery indicators

**Decision**: Replaced the "category chip + thin progress bar underneath" treatment in `SpendCategoriesCard` with a full-width **battery pill** for capped categories — the pill itself is the progress indicator (filled portion = budget used up to the cap, unfilled = remaining), with the category name, amount used, and used percentage overlaid directly on it.

**Why**: User asked for exactly this — "like battery indicator, used budget as filled and remaining on the categories pill directly, show the category name and amount used and the battery indicator and the used percentage". A battery reads the fill/remaining state at a glance, keeps the drill-down tap, and removes the separate percent label below the chip (the percentage now lives on the pill).

**Implementation notes**:
- Fill geometry is a pure helper `categoryBatteryFraction(progress, cap)` in `CategoryCap.kt` (0..1, clamped). **Kotlin's `BigDecimal.div` rounds to scale 0 (HALF_EVEN), so 50/100 → 0** — the helper must use `divide(cap, 4, HALF_UP)`; a regression test locks this in.
- The label over a partially-filled pill needs two text colors. The fill layer is a `Box` with `drawWithContent { clipRect(right = size.width * fraction) { scope.drawContent() } }` that clips the fill rect + a *second* full-width text row to the fill region; both text rows are laid out full-width with identical padding so glyph positions match exactly.
- Colors: fill = category palette `main`; ≥80% → amber `E6A23C` (dark text); ≥100% → theme `error` (onError text). Track = category color @ 15% alpha. A 3dp × 12dp rounded nub on the right (body inset 5dp) sells the battery metaphor.
- Only **capped** categories get the battery (a cap defines "remaining"); uncapped categories keep the plain `TagAmount` pill. `CapProgressBar` is retained for `InterleavedBudgetCard`, which has its own richer row layout (window range + status line).

**Rejected**: drawing the battery as a separate small icon next to an unchanged chip (the user explicitly wanted the fill/remaining on the pill itself); converting uncapped categories to batteries (no budget → no remaining to show); retrofitting `InterleavedBudgetCard` rows in the same pass (different layout, kept for a future consistency pass).

**Outcome**: `analytics/categoriesChart/CategoryBatteryChip.kt` (new), `SpendCategoriesCard.kt` uses it for capped categories, `CategoryCap.kt` `categoryBatteryFraction`, EN string `category_battery_percent` `%1$d%%`, 1 new `CategoryCapsTest` case. Golden pipeline green: **290 tests, 0 failures** (289 + 1 new).

## 2026-08-12 — Decision: Tap-to-edit + long-press actions in History (backlog #1)

**Decision**: Implemented the next Tier-1 backlog item. History rows now respond to tap (edit) and long-press (Edit / Delete / Copy menu) in addition to the existing swipe gestures.

**Why**: The wallet's history is the second-most-touched screen after the editor, but rows were gesture-only (swipe to edit/delete) with no discoverable tap affordance. Tap-to-edit matches the editor's primary action; the long-press menu adds delete (previously only via swipe) and copy-to-clipboard. Wired only in the non-readOnly branch so read-only viewers (`ViewerHistory`, `SearchHistorySheet`) stay inert — archived spends can't be edited through the editor.

**Gesture-conflict note**: `combinedClickable` (tap/long-press) coexists with `SwipeToDismiss`'s drag — taps fire only when the gesture never exceeds touch slop, drags are claimed by the dismiss. Menu delete is instant (`removeSpent`), consistent with swipe-delete (no confirmation dialog in either path).

**Rejected**: a full-blown delete confirmation dialog (adds friction; swipe delete has none); copy as "duplicate transaction" instead of clipboard (the repeat-last-spend chip already covers re-adding).

**Outcome**: `history/SpentItemActions.kt` (combinedClickable + DropdownMenu), `History.kt` wiring (tap→`startEditingSpent`+`onClose()`, delete→`removeSpent`, copy→clipboard via pure `buildSpendCopyText` + snackbar), new `ic_content_copy.xml`, 4 EN strings, 3 `SpentItemActionsTest` cases. Golden pipeline green: **289 tests, 0 failures** (286 + 3 new).

## 2026-08-12 — Decision: AI report belongs in Settings (not Analytics); interleaved + category-caps UIs simplified

**Decision**: User feedback ("AI report it's just normal text and placement is also wrong, add it in setting page not in analytics; interleaved budget looks messy in analytics; category caps settings UI is a mess"). Moved the AI report out of the analytics column into a dedicated Settings sheet (`AI_INSIGHT_SHEET` under a new "AI Insight" row), rendered the report's `• ` bullets as real bullet rows, replaced the interleaved card's wordy velocity sentence with one compact status caption, and swapped the per-row frequency dropdown in the caps sheet for four FilterChips.

**Why**: Analytics is for period stats — an on-demand AI card buried there was hard to find and visually competed with the numbers; Settings is where the rest of the AI controls already live ("Voice AI" row directly above). Bullet rows read better than a plain paragraph. Chips are one-tap and match the app's chip language; the dropdown OutlinedTextField was heavy for a 4-value choice. Shorter status text removes wrapping on small screens. **Outcome**: golden pipeline green, 286 tests, 0 failures. Kept-in-string-but-unused: `category_caps_frequency`, `category_caps_anchor_hint`, `category_caps_templates_title` (UI labels no longer rendered).

## 2026-08-12 — Decision: Ship a "Repeat last spend" editor quick action as the next feature

**Decision**: From the 20-candidate feature list in `.track/FEATURES_AND_IMPROVEMENTS.md`, implement **Repeat-last-spend quick action** (`editor/RepeatLastSpend.kt` pure helper + `EditorViewModel.startRepeatSpend()` + an `AssistChip` in the Editor).

**Why**:
- Tier-1 rating: Small complexity, High daily value. The app's fastest daily loop is "add a spend" — a repeat chip cuts the most common re-entry (coffee/transport/everyday small spends) to one tap.
- Pure logic (`lastSpendToRepeat`) is trivially unit-testable without Android dependencies, matching the repo's Robolectric/JVM test style.
- Self-contained: touches Editor + EditorViewModel + one string; no DB migration, no DataStore schema change, no notification wiring. Lowest regression risk of the high-value candidates.
- Fit with existing architecture: the editor already routes commits through `SpendsViewModel.addSpent`; prefilling `currentSpent/currentComment/currentCategory/rawSpentValue` and setting stage `EDIT_SPENT` reuses the whole commit path unchanged.

**Rejected alternatives (with reasons)**:
- Tap-to-edit + long-press in History (Tier 1, High) — higher value but touches the `SwipeActions` gesture stack (risk of gesture conflicts) and is UI-only (hard to unit test). ✅ **SHIPPED as the next item (2026-08-12) — see the decision above; `combinedClickable` coexists cleanly with the swipe gestures.**
- Category chips on history rows — lower daily frequency; display-only change.
- App lock (PIN/biometric) — Large complexity, security surface, needs `BiometricPrompt` + new settings; better as its own focused session.
- Per-category widget / savings widget — medium complexity; widget E2E can't be verified without an emulator.
- Multi-period trend — medium; needs new Room aggregate + new card; more surface.
- `whatBudgetForDay` widget/repository unification — a correctness win but silently touches widget rendering; no emulator to verify.

**Outcome**: Implemented — pure `editor/RepeatLastSpend.kt` (`lastSpendToRepeat` filters SPENT, most recent by date then uid), `EditorViewModel.startRepeatSpend`, an `AssistChip` in `Editor.kt` fed from `periodSpends`, string `repeat_last_spend`. Confirming commits a NEW row (`editedTransaction = null`, `mode = ADD`, `stage = EDIT_SPENT`) — the old transaction is never overwritten. Golden pipeline green: **286 tests, 0 failures** (5 new `RepeatLastSpendTest`). NOTE: still uncommitted pending user sign-off (user's "not good" feedback addressed the AI report / interleaved / caps UI, not this feature — see the UI-cleanup decision above).

---

## 2026-08-12 — Decision: Two new tracking docs, `DECISIONS.md` + `FEATURES_AND_IMPROVEMENTS.md`

**Decision**: Add `.track/DECISIONS.md` (this file) recording decisions + rationale, and `.track/FEATURES_AND_IMPROVEMENTS.md` listing candidate work with complexity/value notes.

**Why**: The user asked for a durable record of (a) decisions and why they were taken, and (b) any potential feature/enhancement/improvement across any feature, functionality, or file. Keeping these as living `.track/` docs (same convention as `CHANGELOG`/`MEMORY`/`CACHE`) means a compacted future session can recover the full context. The candidates were verified as genuinely missing by a codebase-wide exploration agent (`2026-08-12`).

---

## 2026-08-11 — Decision: Committed the big batch as one commit per item, then pushed (A `b23b12d`, B `c39e21b`, C `124491e`, D `d61edc4`, E `84f5aaf`)

**Why**: The plan's definition of done was "each item golden-pipeline green; committed + pushed". Splitting `SpendsRepository.kt` hunks (Task B archive-category vs Task C interleaved allowance) was done via `git add -p` interactive staging so each commit stays logically atomic and bisectable.

---

## 2026-08-11 — Decision: Phase 5 allowance formula = per-day `dailyPace`, NOT the draft's literal `monthlyEquivalent`

**Why**: `monthlyEquivalent` would reserve ~30× the intended amount (the plan's own example: FOOD monthly @5000 ≈ ₹166/day requires `amount/(freqMonths×30)`). `applyCategoryAllowance(raw, allowance) = max(raw − allowance, raw×0.80, 0)` normalized to scale 2. Applied only at `whatBudgetForDay`/`nextDayBudget` returns so `updateDailyBudget`/`setDailyBudget` and no-schedule users are untouched.

---

## 2026-08-11 — Decision: Phase 6 miner seeds schedules only for categories with NO existing schedule

**Why**: User-configured schedules must always win over machine-mined suggestions. `SettingsRepository.applyScheduleSuggestions` skips any category that already has a schedule, keeping user choices authoritative. Real-data calibration remains blocked on the user's 6-month export.

---

## 2026-08-11 — Decision: Archived categories live in the `archived_transactions` table (DB v14), not a new join table

**Why**: Mirrors the existing `transactions.category` column; single ALTER + migration is the smallest, safest schema change and keeps backup codec + `toTransaction()` trivial. Restore stays backward compatible (v1 exports round-trip via `optString` null-default).

---

## 2026-08-11 — Decision: `OnTrackAlertScheduler` moved from `setRepeating` to one-shot `setWindow` + receiver re-arm

**Why**: `setRepeating` is inexact on modern Android (~18h drift observed on the widget-refresh fix). One-shot `setWindow` bounds the delay to ≤10 min; the receiver re-arms next day only while the toggle is enabled. Same fix already applied to daily reminder, widget refresh, digest, and recurring-due alerts.

---

## 2026-08-10 — Decision: De-linked the app (removed About links, BugReporter, crash-email)

**Why**: Security audit rules (`.track/SECURITY.md`). Zero external contacts/analytics/ads SDKs; only outbound endpoint is the user-chosen Voice AI provider. Crash logs are local-only (Downloads). API key excluded from all backups + cloud transfer.

---

## 2026-08-09 — Decision: Voice AI default model → `openai/gpt-oss-20b:free`, defaults centralized

**Why**: Live OpenRouter listing showed the old `nvidia/nemotron-3-ultra-550b-a55b:free` is rate-limited (HTTP 429) → surfaced as "wrong parse / no AI". Centralizing defaults + `normalizeVoiceAiModel` auto-upgrades already-saved settings on-device without a sheet visit.

> **SUPERSEDED 2026-08-14**: the multi-provider engine + `normalizeVoiceAiModel`/`DEFAULT_VOICE_AI_*` were removed by the single-backend migration (`7e0d0f8`); since `6e9a23a` the model is **optional** (blank = service-provider default, no app-side default), and the settings sheet no longer edits it (`10aa493`).

---

## 2026-08-08 — Decision: Midnight widget refresh uses `setWindow` one-shot, not `setRepeating`

**Why**: `setRepeating` was observed (dumpsys) to drift ~18h; `setWindow(RTC_WAKEUP, trigger, 10min)` bounds it without needing `SCHEDULE_EXACT_ALARM`. Receiver re-arms after firing.

---

## 2026-08-08 — Decision: Analytics "vs previous" compares day-granularity via `effectiveFinishDate = actualFinishDate ?: finishDate`

**Why**: `finishDate` is stored end-of-day; a previous period finished early (or restarted mid-period) never qualified → card vanished. Day-granular comparison fixes it without touching stored data.

---

## 2026-08-07 — Decision: Restore is transactional (`BackupRepository` gets `DatabaseModule` param); API key excluded from exports

**Why**: Review-wave fixes — restore must be all-or-nothing to avoid a half-restored DB; the Voice AI key is the only credential and must never leave the device.

---

## 2026-08-07 — Decision: `overspendNotifiedStoreKey` is a "currently over" mirror kept in sync inside every `edit {}` that mutates today's counter/budget

**Why**: The instant overspend notification must fire exactly once when crossing, and re-fire when crossing again after recovering. Keeping the mirror inside the same `edit {}` block prevents a stray reader from firing duplicates.

---

## 2026-08-06 — Decision: Currency = curated 12-code list, INR default

**Why**: User request ("only a few important currencies, Rupee default"). Fresh installs get ₹; existing picks outside the shortlist are preserved via `remember(defaultCurrency)` prepend. Explicit "No currency" still stores `""` → NONE.

---

## 2026-08-06 — Decision: Categories management as a Room entity (`SavedCategory`, migration 11→12) rather than comment-derived only

**Why**: User asked for management "like tags". Persistent entity gives stable ids for edit/delete and later analytics; mirrors the `saved_tags` migration pattern.

---

## 2026-08-06 — Decision: Period picker limited to current + next month

**Why**: The onboarding calendar showed ~2 years (default listMonths bounds). Restricting to `now.withDayOfMonth(1)`..`last day of next month` makes it usable. Overrides the earlier "start up to one month back" decision.

---

## 2026-08-06 — Decision: Full JSON backup/restore covers every Room entity + both DataStores, ids preserved for FK integrity

**Why**: FK-safe reinsert order (periods → archived → transactions → tags → categories → recurring → goals) and id preservation keep `archived_transactions.period_id` links alive. Restore replaces all current data (with confirmation) — partial failure recoverable by re-restore.

---

## 2026-08-06 — Decision: AI spend categories persisted to the DB via `transactions.category`, not re-classified every render

**Why**: Classification is expensive (network) and non-deterministic per run; persisting makes analytics stable and offline-correct. Fallback: offline keyword classifier when no category persisted.

---

## 2026-08-05 — Decision: Voice widget renders chart/drawings into `android.graphics.Bitmap` at composition time

**Why**: Glance 1.1.1 has no canvas/path composables (`androidx.glance.canvas` absent) and `ContentScale` is only `{ Fit, Crop, FillBounds }`. Bitmap drawing with `ContentScale.FillBounds` is the only way to draw custom charts/rings/bars.

---

## 2026-08-04 — Decision: `howMuchBudgetRest()` returns a cached `MediatorLiveData`, never constructs a LiveData per call

**Why**: The previous version created a new LiveData (+ coroutine) on every call → infinite recomposition loop / freezes on the wallet screen. Rule: hoist LiveData/Flow to the ViewModel and cache.

---

## 2026-08-03 — Decision: No `runBlocking` in composables/ViewModels; no `!!`, no `.first()` on empty lists, no split `edit {}` blocks

**Why**: These are the repo's hard rules (`AGENTS.md`/`SECURITY.md`) — they prevent freezes, crashes, and lost DataStore writes. Enforced in code review.
