# Memory & Decisions

## Active Context
- **Base branch**: `master` — clean fork of `danilkinkin/buckwheat` upstream/master @ `4b60102`
- **Our saved work**: `our-fixes` branch on origin (all prior changes preserved there)
- **Upstream**: `https://github.com/danilkinkin/buckwheat.git`
- **Origin**: `https://github.com/skcode98/buckwheat`

## Current State
- The upstream master is a **simpler codebase** than our-fixes:
  - Only 2 Room tables: `transactions` and `storage`
  - No recurring transactions, no periods, no categories, no sync, no reminder, no notifications
  - These features were all added in our saved `our-fixes` branch
- This is a clean slate to start fresh development from upstream

## Architecture Decisions
| Decision | Rationale |
|----------|-----------|
| Single Activity | `MainActivity` with `setContent` |
| Sheet Navigation | Custom stack-based (AppViewModel.sheetStates) — no Jetpack Navigation |
| Custom Keyboard | Dedicated number pad instead of system keyboard |
| DataStore for Budget | Budget state stored in DataStore, not Room (avoids migration hell for frequent state) |
| Room for Transactions | Full transaction history in Room with schema migrations |
| Hilt DI | Singleton components for DB, repositories, ViewModels |

## Coding Rules (Enforced)
1. **NO `runBlocking`** — use `suspend` + coroutine scope
2. **NO `!!` force-unwrap** — use `.getValue(key)` for maps, `as? T` for casts
3. **NO `.first()` on potentially empty** — use `.firstOrNull()` with null check
4. **NO LiveData `.value` on background threads** — use `.asFlow().first()`
5. **NO `remember {}` without keys** — add observed state as key
6. **NO manual Room DB creation** — use Hilt `@AndroidEntryPoint` + injected DAOs
7. **NO split DataStore `edit {}` calls** — combine into single block

## Open Issues
1. **allowMainThreadQueries()** — Still enabled. Removing requires making DAO methods suspend.

## Future Considerations
- Upstream has added features (recurring, categories, periods, notifications) that we may want to re-implement
- Our `our-fixes` branch contains working implementations that can be referenced
