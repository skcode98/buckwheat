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
