# Architecture

## Tech Stack
- **Language**: Kotlin 2.2.0
- **UI**: Jetpack Compose + Material3 (Compose BOM 1.8.3, Material3 1.3.2)
- **DI**: Dagger Hilt 2.57 (KSP)
- **Database**: Room 2.7.2 (version 13)
- **DataStore**: Preferences DataStore (budget/settings state)
- **Navigation**: Custom sheet-based navigation (no Jetpack Navigation)
- **Min SDK**: 29 | **Target SDK**: 36 | **Compile SDK**: 36
- **AGP**: 8.7.3 (downgraded from 8.11.1 for Android Studio Narwhal 2024.2.1)
- **Background work**: `BroadcastReceiver` (widgets); recurring payments run inside `SpendsViewModel` (no AlarmManager); daily budget reminder via AlarmManager + BOOT_COMPLETED reschedule; overspend notification is instant (posted from `SpendsRepository.addSpent`)

## Module Structure
All code is in `app/src/main/java/com/danilkinkin/buckwheat/`

```
buckwheat/
├── data/
│   ├── dao/                # Room DAOs (Transaction, Storage, SavedTag, BudgetPeriod, ArchivedTransaction, Recurring, SavingsGoal)
│   ├── entities/           # Room entities (Transaction, Storage, SavedTag, BudgetPeriod, ArchivedTransaction, RecurringTemplate, SavingsGoal)
│   ├── categories/         # AI spend categorization (SpendCategory, SpendCategorizer, SpendCategoriesViewModel)
│   ├── AppViewModel.kt     # App-level state (sheets, snackbar, tutorials)
│   ├── ExtendCurrency.kt   # Currency model
│   └── SpendsViewModel.kt  # Main budget/spend state, day-change, recurring, archiving
├── di/                     # Hilt modules, repositories (SpendsRepository, SettingsRepository, BackupRepository), Room converters
├── backup/                 # Full JSON backup/restore (BackupData.kt codec, BackupRestoreViewModel)
├── notifications/          # Daily budget reminder (scheduled) + instant overspend notification (OverspendingNotifier)
├── editor/                 # Spend editor (Editor, EditorViewModel, date/time pickers, tagging, toolbar)
├── home/                   # MainScreen, BottomSheets (sheet navigation host)
├── keyboard/               # Custom number keyboard + voice input (VoiceInputParser, VoiceAi)
├── wallet/                 # Budget constructor, wallet display, currency editor, CSV export
├── history/                # Transaction history list (spent rows, day totals, search)
├── analytics/              # Analytics sheet (SpendsChart, SpendsCalendar, stat cards, SpendsTrendCard, SpendsWeekdayCard, CompareToLastPeriodCard, ShareSummary)
│   └── categoriesChart/    # SpendCategoriesCard (AI category breakdown)
├── recalcBudget/           # Budget recalculation methods (carry-forward / howMuchSaved)
├── settings/               # Settings screens (theme, locale, tags, goals, recurring, voice AI, backup/restore, daily reminder, overspend notification)
├── onboarding/             # First-launch walkthrough
├── base/                   # Reusable UI components (sheets, date picker, buttons, EmojiPicker)
├── effects/                # Confetti
├── ui/                     # Theme, locale, color harmonization
├── widget/                 # Android widgets (minimal / extend)
├── util/                   # Date/time, numbers, currency formatting helpers
├── Application.kt          # @HiltAndroidApp, CrashLogger install, notification channels
├── MainActivity.kt         # Single activity entry point
└── CatchAndSendCrashReport.kt
```

## Data Flow

### Budget/Spend Flow
```
User Input → Keyboard composable
    → EditorViewModel (raw values, stage management)
    → SpendsViewModel (addSpent, removeSpent, commit)
        → SpendsRepository (DAO + DataStore operations)
            → TransactionDao (Room)
            → budgetDataStore (Preferences DataStore)
```

### State Observation
```
SpendsRepository (Flow/LiveData from Room + DataStore)
    → SpendsViewModel (LiveData + StateFlow bridges, MediatorLiveData period filters)
        → Composable screens (observeAsState)
```

### AI Spend Categorization (analytics-only)
```
Analytics opens → LaunchedEffect(spends)
    → SpendCategoriesViewModel.categorizeUncategorized(spends)
        → SpendCategorizer.categorizeSpendsWithAi (batches of 60, reuses Voice AI settings)
            → categoryFor(tx) = persisted AI category else offlineClassify(comment)
        → TransactionDao.updateCategory(uid, category)  (AI results only, never offline guesses)
    → SpendCategoriesCard renders donut + chips, "Refining with AI…" while running
```

### Voice Input
```
Mic tap → SpeechRecognizer → transcript
    → Keyboard.kt (voiceSession guard, isProcessing)
        → VoiceInputParser.parseVoiceInput (offline, deterministic)
        → VoiceAi.parseVoiceInputWithAiFallback (10s connect / 20s read timeouts)
            → AI JSON: { "amount": …, "comment": …, "date": … }
        → commit: EDIT mode = silent removeSpent + addSpent; ADD mode = addSpent
```

### Recurring Payments / Day Change
```
SpendsViewModel.runChangeDayAction() (init + 5s poll, under changeDayMutex)
    → processDueRecurringPayments() — backfills every day since lastRecurringAppliedDate (max 366)
    → ASK one-shot: markDailyBudgetDistributionHandled() writes lastChangeDailyBudgetDate
    → budget redistribution / daily budget fold
```

### CSV Import / Archiving
```
Wallet → rememberImportCSV.kt
    → SpendsRepository.importTransactions (idempotent via type/value/date/comment signature)
        → in-period rows → addSpent (active table)
        → out-of-period rows → archiveImported() → BudgetPeriod month buckets (budget=0, isImported=true)
    → archiveCurrentPeriod() at period finish → filters to in-period rows only
```

### Overspend Notification (instant)
```
Editor CONFIRM → SpendsViewModel.addSpent → SpendsRepository.addSpent
    → single budgetDataStore.edit { ... }
        → wasOver = spentFromDailyBudget > dailyBudget (before)
        → apply new spend; nowOver = spentFromDailyBudget > dailyBudget (after)
        → overspendNotifiedStoreKey = shouldNotifyOverspend(wasOver, nowOver, alreadyNotified)
          = nowOver && !wasOver && !alreadyNotified   (once per crossing)
    → after edit: if overspendNotifiedStoreKey && settings.overspendNotifyEnabledStoreKey
        → OverspendingNotifier.notifyOverspending(context, dailyBudget, spent, currency, numberFormat)
            → NotificationCompat on channel "overspending" (id 200), taps into MainActivity
    → flag resets when today's spending returns ≤ dailyBudget (today branch) and on setDailyBudget
```

### Full JSON Backup / Restore
```
Settings → "Back up data" → CreateDocument → BackupRepository.exportBackup()
    → BackupData(json): all Room entities + both DataStore preference maps
    → toJsonString() (versioned BACKUP_VERSION=1, app-tagged) → writes file
Settings → "Restore backup" → OpenDocument → confirmation AlertDialog
    → BackupRepository.restoreBackup(json): parseBackupData() → null if foreign/malformed
    → wipe DAOs → reinsert FK-safe (periods → archived → transactions → tags → categories → recurring → goals)
    → clear() both DataStores → re-apply backup preference maps
```

### Analytics Cards (this period)
```
Analytics opens → SpendsTrendCard (per-day bars, vs-previous delta)
    → SpendsWeekdayCard (weekdayAverageSpend over elapsed part of period, tap-to-highlight)
    → CompareToLastPeriodCard (findPreviousPeriod + previousSpentAtSameElapsedDays, day-aligned)
        → hidden when no previous finished period exists
    → "Share summary" ButtonRow → ShareSummary.buildShareSummary(period, budget, spent, ...)
        → ACTION_SEND text via rememberShareSummary
```

## Key Design Decisions
1. **Single Activity** — `MainActivity` with `setContent`
2. **Sheet Navigation** — `AppViewModel.sheetStates` drives bottom sheet stack
3. **Custom Keyboard** — Dedicated number pad composable instead of system keyboard
4. **DataStore for Budget** — Budget state (current budget, spent, dates) stored in DataStore, not Room (avoids schema migrations for frequent state changes)
5. **Room for Transactions** — Full transaction history stored in Room with migrations
6. **Hilt DI** — Singleton components for DB, repositories, ViewModels
7. **AI categorization is analytics-only, predefined categories** — fixed `SpendCategory` set, AI-assigned, no manual entry; persists only AI results (`transactions.category`), offline keyword guesses are display-time only
8. **AI features reuse one OpenAI-compatible settings block** — Voice AI (parser) and spend categorization share `voiceAiApiKey/ProviderUrl/Model` DataStore keys; empty key → offline fallback only
9. **Out-of-period imports become archived month buckets** — never touch the active budget; listed under Past Periods, searchable only with a non-blank query

## Database (Room)
- **Version**: 13 (entities: Transaction, Storage, SavedTag, SavedCategory, BudgetPeriod, ArchivedTransaction, RecurringTemplate, SavingsGoal)
- **Migrations**: `MANUAL_MIGRATIONS = [AutoMigration4to5, AutoMigration5to6, AutoMigration6to7, AutoMigration8to9, AutoMigration9to10, AutoMigration10to11, AutoMigration12to13]`; auto-migrations 1→2, 2→3, 3→4, 7→8
- **Schemas**: tracked in `app/schemas/com.danilkinkin.buckwheat.di.DatabaseModule/{1..13}.json`
- v12→v13 adds `emoji TEXT NOT NULL DEFAULT ''` to `saved_categories` (custom category emojis)
- `allowMainThreadQueries()` has been removed — DAO methods are `suspend`

## DataStore Keys
- `budgetDataStore` — `budget`, `spent`, `dailyBudget`, `spentFromDailyBudget`, `startPeriodDate`, `finishPeriodDate`, `finishPeriodActualDate`, `lastChangeDailyBudgetDate`, `lastRecurringAppliedDate`, `currency`, `restedBudgetDistributionMethod`, `hideOverspendingWarn`, `overspendNotifiedStoreKey`, `knownTags`
- `settingsDataStore` — `theme`, `locale`, `TUTOR_*` stages, `autoBackupInterval`, `debug`, `voiceAiApiKeyStoreKey`, `voiceAiProviderUrlStoreKey`, `voiceAiModelStoreKey`, `reminderEnabledStoreKey`, `reminderHourStoreKey`, `reminderMinuteStoreKey`, `overspendNotifyEnabledStoreKey`
