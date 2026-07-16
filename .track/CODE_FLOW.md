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
    → SpendsViewModel.setBudget() or setBudgets() or changeBudget()
      → SpendsRepository.setBudget() / setBudgets() / changeBudget()
        → saveCurrentPeriod() — persists current period to Period table
        → budgetDataStore.edit { ... } — writes new budget, dates
        → transactionDao.insert(INCOME transaction)
        → setDailyBudget(whatBudgetForDay())
```

## 3. Notification Detail Screen
```
Notification tap → Intent → MainActivity.onCreate()
  → MainActivity reads intent extras (notificationType)
  → setContent { MainScreen(notificationType) }
    → MainScreen detects notificationType → opens relevant bottom sheet
      → AppViewModel.sheetStates updated
```

## 4. Recurring Transactions (Background)
```
RecurringReceiver (BroadcastReceiver)
  → System AlarmManager triggers at scheduled time
  → onReceive → goAsync() → CoroutineScope(IO).launch
    → recurringDao.getDueOnDay(dayOfMonth)
    → for each due template:
        → Check if already spent today via transactionDao (asFlow().first())
        → If not: transactionDao.insert(Transaction(...))
    → If created > 0: showNotification()
  → pendingResult.finish()
```

## 5. Sync/Export (Background)
```
SyncReceiver (BroadcastReceiver)
  → Triggered by SyncManager via ACTION_EXPORT_SPENDS
  → onReceive → goAsync() → CoroutineScope(IO).launch
    → transactionDao.getAll(SPENT).asFlow().first()
    → filter today's spends
    → generate CSV content
    → write to MediaStore Downloads
  → pendingResult.finish()
```

## 6. Settings -> Theme/Locale Sync
```
MainActivity.onCreate()
  → LaunchedEffect(Unit) {
      syncTheme(localContext)        // reads DataStore → sets appTheme
      syncOverrideLocale(localContext) // reads DataStore → sets appLocale
      isReady = true
    }
```

## 7. Tutorial System
```
AppViewModel manages tutorial stages
  → Each feature checks if its tutorial should be shown via appViewModel
  → Tutorial stages stored in settingsDataStore (TUTORS keys)
  → activateTutorial() / dismissTutorial() control flow
```

## 8. History with Undo
```
History composable
  → Shows list of transactions sorted by date
  → "Undo" button → spendsViewModel.removeSpent(transaction)
    → SpendsRepository reverses the transaction effects
    → Updates DataStore (spent, daily budget) and removes from Room
  → Race condition: ensure sequential execution via single coroutine
```

## 9. Sheet Navigation
```
AppViewModel.sheetStates: SnapshotStateList<SheetContainerState>
  → Composable bottom sheets observe this list
  → Sheets stack on top of each other
  → Dismissing a sheet pops it from the stack
  → Each sheet has args (Map<String, Any?>) for data passing
```
