# Features & Improvements Backlog

> Living list of potential features, enhancements, and improvements across any feature, functionality, or file.
> Verified as genuinely missing by a codebase-wide exploration (`2026-08-12`). Each item lists files involved, complexity, value, and status. Newest first; check off / annotate when shipped.

---

## Tier 1 — High daily value

### 1. Tap-to-edit + long-press actions in History ✅ **SHIPPED 2026-08-12**
- **Files**: `history/History.kt`, `history/SpentItemActions.kt` (new, `combinedClickable` + `DropdownMenu`)
- **What**: Today only swipe-gestures edit/delete; tap does nothing. Add tap → `startEditingSpent`, long-press → edit/delete/copy menu.
- **Complexity**: Small · **Value**: High (discoverability)
- **Status**: ✅ Implemented — golden pipeline green, **289 tests, 0 failures**. Tap = edit, long-press = Edit/Delete/Copy menu (copy → clipboard + snackbar). Read-only viewers unchanged. Pending commit + push.

### 2. Search sheet filters: category, amount range, date range
- **Files**: `settings/SearchHistorySheet.kt`, `history/History.kt`
- **What**: Current search is comment-text only. Add SavedCategory chips (reuse `CategoriesManagementSheet` list), min/max amount, date-range; filter the LazyColumn.
- **Complexity**: Medium · **Value**: High
- **Status**: Backlog

### 3. Repeat-last-spend quick action in the Editor ❌ **REMOVED 2026-08-13**
- **Files**: `editor/RepeatLastSpend.kt`, `EditorViewModel.kt`, `Editor.kt`, `strings.xml`
- **What**: One-tap re-add of the most recent transaction (amount + comment + category) for frequent small spends (coffee/transport). Prefills the editor's existing commit path; user just confirms.
- **Complexity**: Small · **Value**: High (fastest daily interaction)
- **Status**: ❌ REMOVED 2026-08-13 (committed `9f5383f`, pushed) — user asked to drop it from the editor. All 5 test cases + the string deleted; suite back to 252 green.

### 4. Multi-period month-over-month trend ✅ **SHIPPED 2026-08-13**
- **Files**: `analytics/MultiPeriodTrend.kt` (pure `multiPeriodTotals`), `analytics/MultiPeriodTrendCard.kt` (budget-vs-spent bars, tap caption), `analytics/Analytics.kt` (wired after `CompareToLastPeriodCard`)
- **What**: All analytics are current-period (plus one previous-period compare). Archived data has no cross-period chart. Add a bar/line across all past periods.
- **Complexity**: Medium · **Value**: High
- **Status**: ✅ Implemented — golden pipeline green, **244 tests, 0 failures**. Note: no `BudgetPeriodDao` aggregate was needed — `BudgetPeriod.totalSpent` already stores each month's total; the card reads already-observed analytics state.

### 5. App lock (PIN and/or biometric)
- **Files**: new `lock/` package, `MainActivity.kt`, `AppViewModel.kt`, `settings/Settings.kt`
- **What**: Optional 4–6 digit PIN / `BiometricPrompt` before wallet content; PIN hash in DataStore; on/off row in Settings.
- **Complexity**: Medium–Large · **Value**: High (money app)
- **Status**: Backlog

### 6. Per-category allowance widget
- **Files**: new `widget/` provider + `CategoryWidget.kt`, `res/xml/category_app_widget_provider.xml`, manifest
- **What**: Pin one spend category to the home screen: cap, spent, window days-left, using the shipped cap engine.
- **Complexity**: Medium · **Value**: Medium–High
- **Status**: Backlog

### 7. Spend streak card ("days on track")
- **Files**: `wallet/BudgetSummary.kt` or `analytics/`, `data/SpendsViewModel.kt`
- **What**: Consecutive days under the daily rest budget, computed from per-day transaction sums vs `whatBudgetForDay`.
- **Complexity**: Medium · **Value**: Medium–High (motivation loop)
- **Status**: Backlog

---

## Tier 2 — Strong completeness wins

### 8. Category chips on history rows
- **Files**: `history/History.kt`
- **What**: Transaction has `category` (archived too, DB v14); list shows only the comment. Render a small chip with offline `SpendCategorizer` fallback.
- **Complexity**: Small · **Value**: Medium
- **Status**: Backlog

### 9. Edit archived transactions
- **Files**: `settings/PeriodDetailSheet.kt`, `data/dao/BudgetPeriodDao.kt` (add `update`), `data/entities/ArchivedTransaction.kt`
- **What**: Swipe/tap archived rows to change value/comment/category, then recompute that period's `totalSpent` (fix imported CSV errors).
- **Complexity**: Medium · **Value**: Medium
- **Status**: Backlog — note 2026-08-13: the detail screen now renders a single `PeriodSummaryCard` (`settings/PeriodSummaryCard.kt`) above the records, so edits must also refresh the card (or recompute from the row list).–High
- **Status**: Backlog

### 10. Upcoming recurring payments widget / wallet preview
- **Files**: `widget/`, `wallet/Wallet.kt`, `data/dao/RecurringDao.kt` ("upcoming" query)
- **What**: Show next N due recurring templates (day-of-month, amount, comment). Auto-post already exists — this adds visibility *before* they post.
- **Complexity**: Small–Medium · **Value**: Medium
- **Status**: Backlog

### 11. Savings goals widget
- **Files**: new `widget/` provider + `GoalsWidget.kt`, `data/dao/SavingsGoalDao.kt`
- **What**: Show nearest-goal progress; tap opens GoalsSheet.
- **Complexity**: Medium · **Value**: Medium
- **Status**: Backlog

### 12. Notification: period finish / new-period rest summary
- **Files**: `notifications/` (new content builder + scheduler), finish-early path
- **What**: Notify with final rest amount when a period finishes early or naturally ends, and next-period start — mirrors the daily-reminder pattern.
- **Complexity**: Medium · **Value**: Medium
- **Status**: Backlog

### 13. Notification deep links to specific sheets
- **Files**: `notifications/*Content.kt`, `home/MainScreen.kt` (PathState), `MainActivity.kt`
- **What**: Tap on digest/recurring/cap notifications opens the matching sheet (history, editor, recurring, caps).
- **Complexity**: Medium · **Value**: Medium
- **Status**: Backlog

### 14. Recurring templates "due soon" review UI in wallet
- **Files**: `wallet/Wallet.kt`, `settings/RecurringSheet` (reuse)
- **What**: "3 recurring payments due this week — confirm" row before auto-post, with edit/skip.
- **Complexity**: Medium · **Value**: Medium
- **Status**: Backlog

### 15. CSV export of filtered history
- **Files**: `history/History.kt`, reuse `wallet/rememberExportCSV` codec
- **What**: Export the current date-range/filtered view to CSV (wallet already exports whole-period).
- **Complexity**: Small · **Value**: Medium
- **Status**: Backlog

### 16. Import CSV button in the wallet
- **Files**: `wallet/Wallet.kt`, reuse `rememberImportCSV`
- **What**: Import currently lives only in Settings; add to wallet overflow menu.
- **Complexity**: Small · **Value**: Medium
- **Status**: Backlog

---

## Tier 3 — Polish / correctness

### 17. Unify `whatBudgetForDay` (widget vs repository drift)
- **Files**: `widget/CommonWidgetReceiver.kt:107` (private copy), `di/SpendsRepository.kt:479` (canonical)
- **What**: The widget receiver holds a simplified inline copy of daily-budget math that can drift. Route widgets through the repository so widget rest always matches wallet rest.
- **Complexity**: Small–Medium · **Value**: Medium (correctness)
- **Status**: Backlog

### 18. Archived-period weekday comparison
- **Files**: `analytics/SpendsWeekdayCard.kt`, `CompareToLastPeriodCard.kt`
- **What**: Overlay this period's per-weekday spend vs previous period on the weekday card.
- **Complexity**: Medium · **Value**: Low–Medium
- **Status**: ❌ CANCELLED 2026-08-13 — the past-period detail screen (`settings/PeriodDetailSheet.kt`) no longer renders the weekday/trend/categories charts; it shows only a single `PeriodSummaryCard` + the spend records. Any cross-period comparison now belongs on the current-period Analytics sheet (`analytics/Analytics.kt`).

### 19. Import feedback: amount-range validation + skipped-row report
- **Files**: `di/SpendsRepository.kt` (import path, near the in-file dedupe)
- **What**: Pre-validate rows (zero/negative/absurd amounts), report skipped-row count in the snackbar alongside the existing dedupe logic.
- **Complexity**: Small–Medium · **Value**: Medium
- **Status**: Backlog

### 20. Structured tags on transactions (strategic, large)
- **Files**: `data/entities/Transaction.kt`, `data/dao/TransactionDao.kt`, `di/DatabaseModule.kt` (v15 migration), editor `CustomTag.kt`
- **What**: Tags today are comment-derived only. A real `tags` column enables tag-based search/analytics/filters. Largest item — deliberate DB + UI rework.
- **Complexity**: Large · **Value**: Medium–High (long-term)
- **Status**: Backlog

---

## Interleaved-budget follow-ups (from `.track/INTERLEAVED_BUDGETS.md`)
- **Phase 6 real-data calibration** — engine shipped (`d61edc4`), needs the user's 6-month transaction export to tune defaults + validation harness.
- **Phase 7 (stretch): wallet integration** — surface per-category windows on the wallet screen (currently only analytics card + caps sheet).
