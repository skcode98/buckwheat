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
1. **NO `runBlocking`** — use `suspend` + coroutine scope (exception: `Keyboard.kt` uses `runBlocking` for commit, keep consistent with existing pattern)
2. **NO `!!` force-unwrap** — use `.getValue(key)` for maps, `as? T` for casts
3. **NO `.first()` on potentially empty** — use `.firstOrNull()` with null check
4. **NO LiveData `.value` on background threads** — use `.asFlow().first()`
5. **NO `remember {}` without keys** — add observed state as key
6. **NO manual Room DB creation** — use Hilt `@AndroidEntryPoint` + injected DAOs
7. **NO split DataStore `edit {}` calls** — combine into single block

## Open Issues
1. **allowMainThreadQueries()** — Still enabled. Removing requires making DAO methods suspend.

## Implemented Features (on current master)
1. **Tag Management** — Persistent tags stored in Room `saved_tags` table (not lost on budget reset):
   - Settings → Tag Management opens CRUD bottom sheet
   - Tags merge transaction-derived tags with saved tags in `SpendsRepository.getAllTags()`
   - DB migration 5→6 adds `saved_tags` table
2. **Date/Time editable on fresh entry** — DateTimeEditPill now renders in ADD mode; new spends use `editorViewModel.currentDate`
3. **Past dates in finish date selector** — `disableBeforeDate` set to `null` so users can create wallets with past finish dates
4. **Voice Input** — Mic button on keyboard uses Android `SpeechRecognizer` + `VoiceInputParser`:
   - Parses natural language utterances like "tea 20 now", "lunch 150 yesterday at 8pm"
   - Extracts amount, comment, and date/time
   - Auto-commits the transaction
5. **Analytics Calendar (month heatmap)** — Rewrote `SpendsCalendar.kt`:
   - Shows one month at a time with previous/next month navigation arrows
   - Each day cell is clickable (disabled dates outside budget period are grayed out)
   - Clicking a day closes analytics sheet and opens editor with that date pre-set

## Future Considerations
- Upstream has added features (recurring, categories, periods, notifications) that we may want to re-implement
- Our `our-fixes` branch contains working implementations that can be referenced

## Decisions (Session Compaction Fix)
| Decision | Rationale |
|----------|-----------|
| Plugin-based approach for compaction fix | Hooks into `experimental.session.compacting` to inject structured state without modifying core opencode |
| `.track/.session-state.json` for structured state | Lightweight JSON file that the plugin reads/writes to preserve nextMove, files, lastTask |
| `.track/` files as canonical source of truth | MEMORY.md (context), CHANGELOG.md (changes), CACHE.md (cache) are already maintained; plugin reads them rather than duplicating state |
| `.gitignore` the auto-managed state file | `.session-state.json` is transient and varies per session; not committed |
