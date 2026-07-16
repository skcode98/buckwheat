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
