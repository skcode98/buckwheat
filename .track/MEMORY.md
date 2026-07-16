# Memory & Decisions

## Active Context
- **Base branch**: `master` (fork of `danilkinkin/buckwheat` upstream/master @ `4b60102`)
- **Our fixes branch**: `our-fixes` on origin (all previous work saved here)
- **Upstream**: `https://github.com/danilkinkin/buckwheat.git`
- **Origin**: `https://github.com/skcode98/buckwheat`

## Recent Decisions
1. **Hilt for Receivers** — Both `RecurringReceiver` and `SyncReceiver` switched from manual Room DB creation to Hilt `@AndroidEntryPoint` with injected DAOs. This avoids duplicate DB instances and migration inconsistency.
2. **Suspend over runBlocking** — Any function doing I/O (DataStore reads, Room calls) called from `LaunchedEffect` or coroutine scope should be `suspend`, never use `runBlocking`.
3. **Safe casts** — Always use `as? T` instead of `as T` when casting from `Map<String, Any?>` or similar untyped sources.
4. **Room DAO convention** — Non-suspend DAO methods rely on `allowMainThreadQueries()`. For background thread safety, prefer suspend DAO methods or `asFlow().first()`.

## Open Issues
1. **allowMainThreadQueries()** — Still enabled in `AppModule.kt`. Removing it requires converting all non-suspend DAO methods to suspend (RecurringDao, PeriodDao, TransactionDao non-suspend methods). Large refactor — defer to future.
2. **computeStreak()** — Dead code (defined but never called). Converted to `suspend` anyway.
3. **Deprecated Java Locale API** — `Locale(String)` constructor deprecated in Java 19+. Should migrate to `Locale.Builder` or `Locale.of()`.

## Patterns to Follow
- ViewModels: use `viewModelScope.launch` for coroutines
- Composables: use `rememberCoroutineScope()` for event-driven coroutines
- BroadcastReceivers: use `@AndroidEntryPoint` + injected fields, never manual DB creation
- DataStore: prefer single `edit {}` block when writing multiple keys atomically
- LiveData in coroutines: use `.asFlow().first()` instead of `.value`
