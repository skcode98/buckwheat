# Code Flow

## 1. Adding a Spend (Main Flow)
```
Keyboard composable (Keyboard.kt)
  → user presses number/dot/backspace
  → dispatch(action, value) lambda
    → editorViewModel.rawSpentValue updated
    → editorViewModel.startCreatingSpent() / modifyEditingSpent()
  → user presses CONFIRM button
    → coroutineScope.launch {
        editorViewModel.canCommitEditingSpent()
        → if EDIT mode: spendsViewModel.removeSpent(old) + addSpent(new)
        → if ADD mode: spendsViewModel.addSpent(transaction)
        → editorViewModel.resetEditingSpent()
      }
```

## 2. Budget Creation/Change
```
BudgetConstructor.kt
  → user sets budget value and finish date
  → onChange(budget, finishDate)
    → SpendsViewModel.setBudget() or changeBudget()
      → SpendsRepository.setBudget() / changeBudget()
        → saveCurrentPeriod() — inserts period record
        → budgetDataStore.edit { ... } — writes new budget, spent, dates
        → transactionDao.insert(INCOME transaction)
        → setDailyBudget(whatBudgetForDay())
```

## 3. Settings → Theme/Locale Sync
```
MainActivity.onCreate()
  → LaunchedEffect(Unit) {
      syncTheme(localContext)          // reads DataStore → sets appTheme
      syncOverrideLocale(localContext)  // reads DataStore → sets appLocale
      isReady = true
    }
```

## 4. Tutorial System
```
AppViewModel manages tutorial stages
  → Each feature checks if its tutorial should be shown via appViewModel
  → Tutorial stages stored in settingsDataStore (TUTOR_* keys)
  → activateTutorial() / dismissTutorial() control flow
```

## 5. History with Undo
```
History composable
  → Shows list of transactions sorted by date
  → "Undo" button → spendsViewModel.removeSpent(transaction)
    → SpendsRepository reverses the transaction effects
    → Updates DataStore (spent, daily budget) and removes from Room
```

## 6. Sheet Navigation
```
AppViewModel.sheetStates: SnapshotStateList<SheetContainerState>
  → Composable bottom sheets observe this list
  → Sheets stack on top of each other
  → Dismissing a sheet pops it from the stack
  → Each sheet has args (Map<String, Any?>) for data passing
```

## 7. Export CSV
```
Wallet → rememberExportCSV.kt
  → Creates file and writes CSV of current period's transactions
  → Uses ActivityResultContracts.CreateDocument to pick save location
```

## 8. Widget Display
```
GlanceAppWidget (MinimalWidget / ExtendWidget)
  → WidgetReceiver receives update requests
  → Data from DataStore + Room via Glance composables
```

## 9. Voice Input
```
Mic tap → SpeechRecognizer.isRecognitionAvailable() → startListening()
  → onResults(transcript) → Keyboard.kt (voiceSession id guard, isProcessing flag)
    → VoiceInputParser.parseVoiceInput(transcript)  // offline, deterministic
    → VoiceAi.parseVoiceInputWithAiFallback(transcript)  // AI with 10s/20s timeouts
        → JSON { amount, comment, date } from choices[0].message.content
        → VoiceAiResult.Success / Failure / NotConfigured (Failure surfaces error in red)
    → commit: EDIT mode = silent removeSpent(old) + addSpent(parsed)
              ADD mode  = addSpent(parsed)
```

## 10. AI Spend Categorization (analytics-only)
```
Analytics sheet opens
  → LaunchedEffect(spends) → SpendCategoriesViewModel.categorizeUncategorized(spends)
    → rows with null category only (persisted AI results are skipped → no loop)
    → SpendCategorizer.categorizeSpendsWithAi: batches of 60 → chat completions
        → parseCategoryResponse: map index → SpendCategory
    → spendCategoriesViewModel.saveAssignments → transactionDao.updateCategory(uid, category)
  → SpendCategoriesCard renders categoryFor(tx) = persisted category
      else offlineClassify(comment) (whole-word regex, display-time only)
  → isCategorizing=true shows "Refining with AI…" progress bar
```

## 11. Recurring Payments (day change)
```
SpendsViewModel.runChangeDayAction()  // init + 5s poll, under changeDayMutex
  → if day changed:
      processDueRecurringPayments()  // backfills every day since lastRecurringAppliedDate (max 366)
      → for each due day: due templates → transactionDao.insert (date = due day)
      → advance lastRecurringAppliedDate
  → ASK branch (one-shot/day): markDailyBudgetDistributionHandled() writes lastChangeDailyBudgetDate
  → otherwise: daily budget fold (setDailyBudget)
```

## 12. Recalc / Carry-Forward
```
RecalcBudget sheet opens
  → RecalcBudgetViewModel uses SpendsRepository.howMuchSaved()
      = (rest - daily)/(restDays + skippedDays - 1) × max(skippedDays - 1, 0) + (daily - spentToday)
      (last day: rest - spent)
  → "Congratulations! You saved …" shows a positive amount even when skippedDays == 0
```

## 13. CSV Import (in-period vs archived)
```
Wallet → rememberImportCSV.kt
  → SpendsRepository.importTransactions(file)
    → idempotency: skip rows whose (type, value, date, comment) already exist (incl. archived)
    → in-period rows → addSpent (active table)
    → out-of-period rows → archiveImported(): group by calendar month into
        BudgetPeriod(budget = 0, isImported = true) buckets or merge into covering period
    → History search (non-blank query) merges archived rows via composeHistoryRows
```

## 14. Period Archive
```
SpendsRepository.archiveCurrentPeriod()
  → filters to in-period rows [startPeriodDate, finishPeriodDate] only
  → inserts BudgetPeriod snapshot; deletes in-period transactions
  → out-of-period rows (imports/backfills) stay in the active table
```

## 15. Analytics Debug Route (UI automation)
```
Keyboard "0"×8 → "." → apply column  → sets debug=true (persisted)
Relaunch → debug icon (top-left) → DebugMenu → "Open period summary screen" → Analytics
  → SpendsCalendar renders the FULL month grid (periodEnd drives week filter, uncapped)
  → SpendCategoriesCard auto-runs AI categorization on open
```

## 16. Overspend Notification (instant)
```
Editor CONFIRM → SpendsViewModel.addSpent → SpendsRepository.addSpent
  → single budgetDataStore.edit { }
      wasOver = spentFromDailyBudget > dailyBudget (before)
      apply new spend (spent += value; spentFromDailyBudget += value in today's branch)
      nowOver = spentFromDailyBudget > dailyBudget (after)
      overspendNotifiedStoreKey = shouldNotifyOverspend(wasOver, nowOver, alreadyNotified)
                                 = nowOver && !wasOver && !alreadyNotified  (fires once per crossing)
  → after the edit block:
      if overspendNotifiedStoreKey && settings.overspendNotifyEnabledStoreKey
        → OverspendingNotifier.notifyOverspending(ctx, dailyBudget, spent, currency, numberFormat)
            → channel "overspending" (created in Application.kt), id 200, ic_stat_notification
  → flag resets in the today branch when spending returns ≤ dailyBudget, and in setDailyBudget
```
Settings row: `OverspendNotificationSetting.kt` — switch writes `overspendNotifyEnabledStoreKey`
(settingsDataStore); enabling on API 33+ requests `POST_NOTIFICATIONS` and activates only if granted.

## 17. Full JSON Backup / Restore
```
Settings → "Back up data" (TextRow) → rememberLauncherForActivityResult(CreateDocument)
  → BackupRestoreViewModel.exportBackup() → BackupRepository.exportBackup()
      → DAO getAllNow() ×7 + both DataStore map snapshots
      → BackupData → toJsonString() (v1, app-tagged) → write to picked file
Settings → "Restore backup" (TextRow) → OpenDocument → BackupRestoreViewModel.preview(json)
  → AlertDialog confirmation (destructive warning) → BackupRepository.restoreBackup(json)
      → parseBackupData() → null on foreign/malformed/older-version file
      → deleteAll() every DAO → insertAll() in FK-safe order
        (budget_periods → archived_transactions → transactions → saved_tags → saved_categories → recurring_templates → savings_goals)
      → clear() budgetDataStore + settingsDataStore → applyBackupMap() re-applies both
  → snackbar success/failure
```

## 18. Analytics Cards + Share Summary (this period)
```
Analytics opens (periodSpends, archivedTransactions, budgetPeriods from spendsViewModel)
  → SpendsTrendCard — per-day bars + vs-previous-period delta (totalByDay + SpendsTrend)
  → SpendsWeekdayCard — weekdayAverageSpend(spends, start) over elapsed part of period
      (tap selects/deselects a weekday highlight; hidden when no elapsed days)
  → CompareToLastPeriodCard — findPreviousPeriod(budgetPeriods, finish) → previous period
      → previousSpentAtSameElapsedDays(previousArchived, prevStart, elapsedDays)
        = sum of SPENT archived rows with date <= prevStart + (elapsedDays - 1)
      → delta = spent - previous; formatPercent always ±, colored colorBad/colorGood
      → early-return (hidden) when no previous finished period exists
  → "Share summary" ButtonRow → rememberShareSummary(period, budget, spent, ...)
      → buildShareSummary(...) pure string (period dates, budget, spent, remaining ≥ 0,
        daily average, transaction count, category breakdown) → ACTION_SEND chooser
```
