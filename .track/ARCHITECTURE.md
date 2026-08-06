# Architecture

## Tech Stack
- **Language**: Kotlin 2.2.0
- **UI**: Jetpack Compose + Material3 (Compose BOM 1.8.3, Material3 1.3.2)
- **DI**: Dagger Hilt 2.57 (KSP)
- **Database**: Room 2.7.2 (version 11)
- **DataStore**: Preferences DataStore (budget/settings state)
- **Navigation**: Custom sheet-based navigation (no Jetpack Navigation)
- **Min SDK**: 29 | **Target SDK**: 36 | **Compile SDK**: 36
- **AGP**: 8.7.3 (downgraded from 8.11.1 for Android Studio Narwhal 2024.2.1)
- **Background work**: `BroadcastReceiver` (widgets); recurring payments run inside `SpendsViewModel` (no AlarmManager)

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
├── di/                     # Hilt modules, repositories (SpendsRepository, SettingsRepository), Room converters
├── editor/                 # Spend editor (Editor, EditorViewModel, date/time pickers, tagging, toolbar)
├── home/                   # MainScreen, BottomSheets (sheet navigation host)
├── keyboard/               # Custom number keyboard + voice input (VoiceInputParser, VoiceAi)
├── wallet/                 # Budget constructor, wallet display, currency editor, CSV export
├── history/                # Transaction history list (spent rows, day totals, search)
├── analytics/              # Analytics sheet (SpendsChart, SpendsCalendar, stat cards)
│   └── categoriesChart/    # SpendCategoriesCard (AI category breakdown)
├── recalcBudget/           # Budget recalculation methods (carry-forward / howMuchSaved)
├── settings/               # Settings screens (theme, locale, tags, goals, recurring, voice AI settings)
├── onboarding/             # First-launch walkthrough
├── base/                   # Reusable UI components (sheets, date picker, buttons)
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
- **Version**: 11 (entities: Transaction, Storage, SavedTag, BudgetPeriod, ArchivedTransaction, RecurringTemplate, SavingsGoal)
- **Migrations**: `MANUAL_MIGRATIONS = [AutoMigration4to5, AutoMigration5to6, AutoMigration6to7, AutoMigration8to9, AutoMigration9to10, AutoMigration10to11]`; auto-migrations 1→2, 2→3, 3→4, 7→8
- **Schemas**: tracked in `app/schemas/com.danilkinkin.buckwheat.di.DatabaseModule/{1..11}.json`
- `allowMainThreadQueries()` has been removed — DAO methods are `suspend`

## DataStore Keys
- `budgetDataStore` — `budget`, `spent`, `dailyBudget`, `spentFromDailyBudget`, `startPeriodDate`, `finishPeriodDate`, `finishPeriodActualDate`, `lastChangeDailyBudgetDate`, `lastRecurringAppliedDate`, `currency`, `restedBudgetDistributionMethod`, `hideOverspendingWarn`, `knownTags`
- `settingsDataStore` — `theme`, `locale`, `TUTOR_*` stages, `autoBackupInterval`, `debug`, `voiceAiApiKeyStoreKey`, `voiceAiProviderUrlStoreKey`, `voiceAiModelStoreKey`
