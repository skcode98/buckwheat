# AGENTS.md — AI Agent Guide for Buckwheat

> **Read this file at the start of every request before doing any work.**
> After completing work, update `.track/CHANGELOG.md`, `.track/MEMORY.md`, and `.track/CACHE.md` if anything changed.

---

## 1. Project Identity

- **App**: Buckwheat — a daily budget tracker (like a "no-zero day" app for finances)
- **Language**: Kotlin 2.2.0
- **UI**: Jetpack Compose + Material3
- **DI**: Dagger Hilt 2.57 (KSP)
- **Database**: Room 2.7.2
- **State**: Preferences DataStore (budget state) + Room (transaction history)
- **Min SDK**: 29 | **Target SDK**: 36 | **Compile SDK**: 36
- **AGP**: 8.11.1 | **Gradle**: uses wrapper

---

## 2. Repository State

- **origin**: `https://github.com/skcode98/buckwheat` (our fork)
- **upstream**: `https://github.com/danilkinkin/buckwheat.git` (original)
- **Current branch**: `master` (clean fork of upstream @ `4b60102`)
- **Our saved work**: `our-fixes` branch on origin
- **Workflow**: Build APK via GitHub Actions (`.github/workflows/build.yml`)

---

## 3. Code Conventions

### Naming
- Files: PascalCase matching the class name (e.g., `SpendsRepository.kt`)
- Functions: camelCase (e.g., `addSpent`, `computeStreak`)
- Composables: PascalCase (e.g., `Wallet`, `BudgetConstructor`)
- Constants: UPPER_SNAKE_CASE or camelCase in companion objects
- ViewModels: suffix `ViewModel` (e.g., `SpendsViewModel`)
- Repositories: suffix `Repository`

### Imports
```kotlin
// LiveData → Flow (for background threads)
import androidx.lifecycle.asFlow
// Flow → LiveData (for compose observation)
import androidx.lifecycle.asLiveData
// Observe in compose
import androidx.compose.runtime.livedata.observeAsState
```

### State Management
- ViewModels use `MutableLiveData` / `LiveData` (not `StateFlow` / `MutableStateFlow`)
  - Exception: `AppViewModel.sheetStates` uses `SnapshotStateList` from Compose
- Composables observe via `.observeAsState(default)`
- `rememberCoroutineScope()` for event-driven coroutines in composables
- `viewModelScope.launch` for coroutines in ViewModels
- NEVER use `runBlocking` — use `suspend` functions instead

### NULL Safety
```kotlin
// SAFE — use safe cast + Elvis
val value = map["key"] as? String ?: return

// UNSAFE — AVOID
val value = map["key"] as String
val value = map["key"]!!

// PREFERRED for Map access with known keys
val value = map.getValue("key")  // throws NoSuchElementException with key name
```

### DataStore Access
```kotlin
// READ (suspend)
val value = dataStore.data.first()[someKey]

// WRITE (suspend)
dataStore.edit { it[someKey] = value }

// OBSERVE (Flow)
dataStore.data.map { it[someKey] }
```

### Room Access
```kotlin
// Background thread (coroutine)
dao.getAllSuspend()  // suspend method
dao.getAll().asFlow().first()  // LiveData → Flow

// Composition (observation)
dao.getAll().observeAsState(emptyList())
```

### Compose Keys
```kotlin
// CORRECT — key on observed state so it updates when data loads
val observedValue by viewModel.someLiveData.observeAsState()
val localState = remember(observedValue) { mutableStateOf(observedValue) }

// WRONG — captures stale reference, never updates
val localState = remember { mutableStateOf(viewModel.someLiveData.value) }
```

---

## 4. Package Map

```
com.danilkinkin.buckwheat/
├── Application.kt              # @HiltAndroidApp, NotificationChannels
├── MainActivity.kt             # Single Activity, LaunchedEffect setup
├── CatchAndSendCrashReport.kt  # Crash reporting
│
├── data/
│   ├── dao/
│   │   ├── TransactionDao.kt   # CRUD for transactions
│   │   └── StorageDao.kt       # Key-value storage (legacy)
│   ├── entities/
│   │   ├── Transaction.kt      # Room entity: type, value, date, comment
│   │   └── Storage.kt          # Legacy key-value entity
│   ├── AppViewModel.kt         # Sheet stack, snackbars, tutorials
│   ├── ExtendCurrency.kt       # Currency model
│   └── SpendsViewModel.kt      # Budget/spend state, streak compute
│
├── di/
│   ├── AppModule.kt            # Hilt module: Room DB, DAOs
│   ├── DatabaseModule.kt       # Room DB class with migrations
│   ├── SettingsRepository.kt   # DataStore for settings (theme, locale, etc.)
│   ├── SpendsRepository.kt     # Budget + DataStore + Room operations
│   ├── GetCurrentDateUseCase.kt
│   ├── RoomConverters.kt       # TypeConverters for Room
│   └── migrateToDataStore.kt   # Legacy migration
│
├── editor/
│   ├── EditorViewModel.kt      # Spend editor state machine
│   ├── Editor.kt               # Main editor composable
│   ├── CurrentSpendEditor.kt   # Text input for spend value
│   ├── EditableTextWithLabel.kt
│   ├── calcMaxFontSize.kt
│   ├── dateTimeEdit/           # Date & time pickers
│   ├── tagging/                # Comment tags
│   └── toolbar/                # Editor toolbar, rest budget pill
│
├── keyboard/
│   ├── Keyboard.kt             # Number pad composable
│   ├── KeyboardButton.kt
│   ├── KeyboardViewModel.kt
│   └── rememberAppKeyboard.kt  # Keyboard action dispatcher
│
├── home/
│   ├── MainScreen.kt           # Main layout wrapper
│   └── BottomSheets.kt         # Sheet navigation host
│
├── wallet/
│   ├── Wallet.kt               # Main wallet display
│   ├── BudgetConstructor.kt    # Budget creation/edit form
│   ├── BudgetSummary.kt        # Budget overview card
│   ├── Total.kt                # Total spend display
│   ├── DaysLeftCard.kt
│   ├── EditButton.kt
│   ├── FinishDateSelector.kt
│   ├── FinishEarlyConfirm.kt
│   ├── DefaultRecalcBudgetChooser.kt
│   ├── CurrencyEditor.kt
│   ├── CustomCurrencyEditor.kt
│   ├── WorldCurrencyChooser.kt
│   └── rememberExportCSV.kt    # CSV export launcher
│
├── history/
│   ├── History.kt              # Transaction history list
│   ├── Spent.kt                # Spend item row
│   ├── SpentItem.kt            # Animated spend item
│   ├── SwipeActions.kt         # Swipe-to-delete/undo
│   ├── TotalPerDay.kt
│   ├── HistoryDateDivider.kt
│   ├── NoSpends.kt
│   └── ListAnimation.kt
│
├── analytics/
│   ├── SpendsChart.kt          # Spending chart
│   ├── SpendsCalendar.kt       # Calendar heatmap
│   ├── SpendsBudgetCard.kt
│   ├── RestAndSpentBudgetCard.kt
│   ├── SpendsCountCard.kt
│   ├── MinMaxSpentCard.kt
│   ├── WholeBudgetCard.kt
│   ├── StatCard.kt
│   ├── ViewerHistory.kt
│   ├── MiddlePeriodAnalyticsHeader.kt
│   ├── FinishedPeriodHeader.kt
│   └── FillCircleStub.kt
│
├── recalcBudget/               # Budget recalculation methods
│   ├── RecalcBudget.kt
│   ├── RecalcBudgetViewModel.kt
│   ├── AddToTodayButton.kt
│   ├── SplitToRestDaysButton.kt
│   └── StartLastDayButton.kt
│
├── settings/
│   ├── Settings.kt             # Settings screen
│   ├── ThemeSwitcher.kt
│   ├── LangSwitcher.kt
│   ├── About.kt
│   ├── BugReporter.kt
│   └── TryWidget.kt
│
├── onboarding/
│   ├── Onboarding.kt           # First-launch walkthrough
│   └── NumberedRow.kt
│
├── base/                       # Reusable UI components
│   ├── BottomSheetWrapper.kt   # Sheet wrapper with ActivityResultRegistry
│   ├── ModalBottomSheet.kt     # Custom modal sheet
│   ├── TopSheet.kt
│   ├── datePicker/             # Date picker component
│   ├── BigIconButton.kt
│   ├── ButtonRow.kt
│   ├── CheckedRow.kt
│   ├── ClickableText.kt
│   ├── DescriptionButton.kt
│   ├── Divider.kt
│   ├── RenderAdaptivePane.kt
│   ├── SwipeableSnackbarHost.kt
│   ├── TextFieldWithPaddings.kt
│   ├── TextRow.kt
│   └── WavyShape.kt
│
├── effects/
│   ├── Confetti.kt             # Celebration particle effect
│   ├── ConfettiParticle.kt
│   └── vector.kt
│
├── ui/                         # Theme & localization
│   ├── Color.kt                # Color definitions
│   ├── Theme.kt                # Material theme + syncTheme()
│   ├── Typography.kt
│   ├── Shape.kt
│   └── Locale.kt               # Locale override + syncOverrideLocale()
│
├── widget/                     # Android home screen widgets
│   ├── CommonWidgetReceiver.kt
│   ├── CanvasText.kt
│   ├── minimal/
│   └── extend/
│
└── util/                       # Utility functions
    ├── time.kt                 # Date/time helpers (isSameDay, roundToDay, etc.)
    ├── numberExtensions.kt     # BigDecimal extensions
    ├── numberFormat.kt         # Currency formatting
    ├── string.kt               # String manipulation
    ├── colors.kt
    ├── observeLiveData.kt      # Composable helper for LiveData observation
    ├── openIn.kt
    ├── lockOrientation.kt
    ├── Swipeable.kt
    ├── setSystemBarStyle.kt
    ├── toSP.kt
    ├── visualTransformationAsCurrency.kt
    ├── animationTimeMillis.kt
    ├── collectEnvInfo.kt
    └── YearMonthExt.kt
```

---

## 5. Common Pitfalls (DO NOT REPEAT)

| Pitfall | Fix |
|---------|-----|
| `runBlocking` in composable/ViewModel | Use `coroutineScope.launch` or `viewModelScope.launch` |
| `!!` force-unwrap on map access | Use `.getValue(key)` (throws with key name) |
| `as T` unsafe cast | Use `as? T` with null check |
| `.first()` on potentially empty list | Use `.firstOrNull()` and handle null |
| LiveData `.value` on background thread | Use `.asFlow().first()` |
| `remember {}` without keys (stale data) | Add observed state as key |
| Multiple separate `DataStore.edit {}` blocks | Combine into one `edit {}` block |
| Manual `Room.databaseBuilder()` in receivers | Use `@AndroidEntryPoint` + injected DAOs |
| Split `goAsync()`/`finish()` in receivers | Already correct — keep this pattern |

---

## 6. Key Architectural Patterns

### State Flow (LiveData-based)
```
Room/LiveData → SpendsViewModel (LiveData bridge) → Composable (.observeAsState)
DataStore/Flow → SettingsRepository (map + asLiveData) → Composable
```

### Navigation (Sheet-based, no Jetpack Navigation)
```
AppViewModel.sheetStates: SnapshotStateList<SheetContainerState>
  → BottomSheets composable renders sheets from top of stack
  → Each sheet has: id, content composable, args Map
  → Dismiss pops from stack
```

### Custom Keyboard (replaces system keyboard)
```
Keyboard composable → dispatch(action, value) → EditorViewModel
  → KeyboardAction: PUT_NUMBER, SET_DOT, REMOVE_LAST
  → EditorViewModel: rawSpentValue, startCreatingSpent, modifyEditingSpent
  → CONFIRM button: coroutineScope.launch { canCommitEditingSpent → addSpent }
```

### Tutorial System
```
AppViewModel.activateTutorial(TUTORS.X) / dismissTutorial()
  → Tutorial stages stored in settingsDataStore
  → Each stage is a boolean key (e.g., "TUTOR_SHOW_SPENDS_IS_ZERO")
```

---

## 7. Build & Test

### Build
```powershell
.\gradlew.bat assembleDebug
```

### Test
```powershell
.\gradlew.bat testDebug
```

### Lint
```powershell
.\gradlew.bat lintDebug
```

### Spotless (format check)
```powershell
.\gradlew.bat spotlessCheck
```

---

## 8. Session Protocol

1. **START**: Read `.track/AGENTS.md` (this file), then `.track/MEMORY.md`, `.track/CACHE.md`
2. **DURING**: Understand the codebase before writing code. Use grep/glob/read tools.
3. **AFTER**: Update `.track/CHANGELOG.md` with changes, `.track/MEMORY.md` with decisions, `.track/CACHE.md` with new info.
4. **BUILD**: Always run `.\gradlew.bat assembleDebug` after changes to verify compilation.
5. **COMMIT**: Only when asked. Use concise messages matching repo style.
6. **TRACKING FILES** are in `.track/` directory — always maintain them.

### Compaction Recovery

When a new session starts (after session compaction), follow this recovery protocol:

1. The `.opencode/plugins/compaction-fix.ts` plugin injects preserved state (active task, recent changes, decisions) into the compaction prompt
2. Read `.track/MEMORY.md` to restore full context and decisions
3. Read `.track/CHANGELOG.md` to see what changed before compaction
4. Read `.track/CACHE.md` for build commands and references
5. **Update `.track/.session-state.json`** with the current `nextMove` and `files` before significant work, so compaction captures accurate state
6. If `.track/.session-state.json` is empty or stale, manually record the current task and files being worked on
