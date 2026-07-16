# Architecture

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material3
- **DI**: Dagger Hilt
- **Database**: Room
- **DataStore**: Preferences DataStore (for settings/budget state)
- **Navigation**: Custom sheet-based navigation (no Jetpack Navigation)
- **Notifications**: `BroadcastReceiver` + `NotificationManager`
- **Background sync**: `BroadcastReceiver` with `goAsync()`

## Module Structure
All code is in `app/src/main/java/com/danilkinkin/buckwheat/`

```
buckwheat/
├── data/
│   ├── dao/              # Room DAOs
│   ├── entities/         # Room entities
│   ├── AppViewModel.kt   # App-level state (sheets, snackbar, tutorials)
│   └── SpendsViewModel.kt # Main budget/spend state
├── di/                    # Dependency injection, repositories
├── editor/                # Spend editor (keyboard, input)
├── home/                  # Home screen, bottom sheets
├── keyboard/              # Custom number keyboard
├── notifications/         # Notification channels, scheduling
├── recurring/             # Recurring transactions
├── reminder/              # Reminder system
├── settings/              # Settings screens
├── sync/                  # CSV sync/export
├── ui/                    # Theme, locale, common UI
├── wallet/                # Budget constructor, wallet display, history
├── widget/                # Android widgets
└── MainActivity.kt        # Single activity entry point
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
    → SpendsViewModel (LiveData + StateFlow bridges)
        → Composable screens (observeAsState)
```

## Key Design Decisions
1. **Single Activity** — `MainActivity` with `setContent`
2. **Sheet Navigation** — `AppViewModel.sheetStates` drives bottom sheet stack
3. **Custom Keyboard** — Dedicated number pad composable instead of system keyboard
4. **DataStore for Budget** — Budget state (current budget, spent, dates) stored in DataStore, not Room (avoids schema migrations for frequent state changes)
5. **Room for Transactions** — Full transaction history stored in Room with migrations
6. **Hilt DI** — Singleton components for DB, repositories, ViewModels

## Database (Room)
- **DatabaseModule** — Singleton Room DB with `allowMainThreadQueries()` (legacy, should migrate)
- **Migrations** — MANUAL_MIGRATIONS array
- **Tables**: transactions, recurring_templates, periods, categories, tag_categories

## DataStore Keys
- `budgetDataStore` — budget value, spent, daily budget, dates, tags, overspending warn
- `settingsDataStore` — theme, locale, tutorial stages, notifications, currency format
