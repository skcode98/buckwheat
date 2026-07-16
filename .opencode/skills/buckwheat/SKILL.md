---
name: buckwheat
description: Buckwheat daily budget tracker — Kotlin Android app with Jetpack Compose, Room, Hilt, DataStore
license: MIT
compatibility: opencode
metadata:
  language: kotlin
  framework: android-compose
---

## Project Identity

- **App**: Buckwheat — daily budget tracker
- **Language**: Kotlin 2.2.0 | **UI**: Jetpack Compose + Material3 | **DI**: Dagger Hilt 2.57 (KSP)
- **Database**: Room 2.7.2 | **State**: Preferences DataStore + Room
- **Min SDK**: 29 | **Target/Compile SDK**: 36 | **AGP**: 8.11.1

## Repository State

- **Current branch**: `master` — clean fork of upstream @ `4b60102`
- **Saved work**: `our-fixes` branch on origin
- **Upstream**: `https://github.com/danilkinkin/buckwheat.git`

## Core Conventions

### Coding Rules (NEVER break these)
- No `runBlocking` — use `viewModelScope.launch` / `coroutineScope.launch`
- No `!!` force-unwrap — use `as?` safe cast with null check
- No `.first()` on empty lists — use `.firstOrNull()`
- No `as T` unsafe cast — use `as? T`
- No LiveData `.value` on bg threads — use `.asFlow().first()`
- No `remember {}` without observed state keys
- No split `DataStore.edit {}` — combine into one block
- No manual Room DB creation — use `@AndroidEntryPoint` + injected DAOs

### State Management
- ViewModels use `MutableLiveData` / `LiveData` (not StateFlow)
- Composables observe via `.observeAsState(default)`
- Navigation: sheet-based stack in `AppViewModel.sheetStates` (no Jetpack Navigation)
- Custom keyboard replaces system keyboard for number input

### Data Layer
- Room entities: `Transaction` (type, value, date, comment, uid), `Storage` (legacy key-value)
- DataStore keys: budget, finishDate, actualFinishDate, dailyBudget, spent, currency, etc.
- Settings DataStore: theme mode, locale, debug mode, tutorial stages, hideOverspendingWarn

## Key Architecture

```
SpendsRepository (ALL business logic) ← SpendsViewModel → Composables
AppViewModel (sheet stack, snackbars, tutors, confetti)
EditorViewModel (state machine: ADD/EDIT, IDLE/CREATING_SPENT/EDIT_SPENT/COMMITTING_SPENT)
```

## Build & Test
```powershell
.\gradlew.bat assembleDebug  # Build
.\gradlew.bat testDebug      # Unit tests
.\gradlew.bat lintDebug      # Lint
.\gradlew.bat spotlessCheck  # Format check
```

## Session Compaction Recovery

After opencode compacts a session, the `compaction-fix.ts` plugin injects preserved state (active task, recent changes, decisions) into the compaction prompt. Follow these steps to recover:

1. Read `.track/MEMORY.md` — restores full context, active section, and decisions
2. Read `.track/CHANGELOG.md` — shows what changed before compaction
3. Read `.track/CACHE.md` — build commands and references
4. Update `.track/.session-state.json` with `nextMove` and `files` before significant work
5. If `.session-state.json` is stale, manually note the current task in MEMORY.md's Active section

## When to use Context7 MCP
When you need to look up Kotlin, Android, Jetpack Compose, Room, Hilt, or Gradle documentation, use the `context7` MCP server to search official docs.
