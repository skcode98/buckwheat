# Memory & Decisions

## Active Context
- **Base branch**: `master` — clean fork of `danilkinkin/buckwheat` upstream/master @ `4b60102`
- **Our saved work**: `our-fixes` branch on origin (all prior changes preserved there)
- **Upstream**: `https://github.com/danilkinkin/buckwheat.git`
- **Origin**: `https://github.com/skcode98/buckwheat`

## Current State
- `master` is **our fork** (`skcode98/buckwheat`, based on upstream master @ `4b60102`) with the implemented feature set below
- **2026-08-05 bug-fix wave (uncommitted until verified)**: 15 fixes shipped across voice AI, budget/period arithmetic, goals, tags, recurring payments, CSV import, and startup I/O — details in `CHANGELOG.md`; verified with `compileDebugKotlin` (BUILD SUCCESSFUL) + `SpendsRepositoryTest` (18/18)
- **Voice input hardening wave (uncommitted)**: AI call moved to `keyboard/VoiceAi.kt` (timeouts, JSON-safe body, markdown extraction, date fallback), `SpeechRecognizer` lifecycle hardened (`isRecognitionAvailable` + null-safe create/destroy), `voiceSession` stale-result guard in `Keyboard.kt`, voice messages i18n'd to `strings.xml` with permission/unavailable feedback, `VoiceInputParser` amount heuristic rewritten (currency-anchored, else last number; time stripped before amount), and 17 new `VoiceInputParserTest` cases — `testDebugUnitTest` fully green
- Last pushed commit `a309c29`: budget scope guards, voice AI off-main-thread, UI freeze fix, AGP 8.7.3 downgrade (Android Studio Narwhal 2024.2.1 supports max AGP 8.7.3)

## 2026-08-05 Bug-Fix Wave — Decisions
| Decision | Rationale |
|----------|-----------|
| Recurring backfill uses its own DataStore key `lastRecurringAppliedDate`, NOT `lastChangeDailyBudgetDate` | The ASK-branch no longer advances the budget date, so recurring needs an independent "already applied" marker |
| Recurring first run seeds the key **without** charging retroactively | A brand-new install must not suddenly insert a year of recurring payments |
| Backfill walks day-by-day (max 366 days guard) instead of only today's day-of-month | Payments are no longer skipped when the app is closed on the due day |
| Day-of-month 29/30/31 templates simply don't fire in shorter months (deliberate, not fixed) | Clamping to the last day would silently move the charge; day-of-month is the declared contract |
| ASK redistribution is a one-shot per day (`markDailyBudgetDistributionHandled()` writes `lastChangeDailyBudgetDate`) | Dismissing the sheet must not re-trigger every 5s poll and double-charge recurring |
| `removeSpent` picks the counter by `foldRanToday` (`lastChangeDailyBudgetDate` is today), not just `isSameDay(tx.date, today)` | The value's home (`spent` vs `spentFromDailyBudget`) depends on whether the daily fold ran, not the tx date |
| `archiveCurrentPeriod` filters to in-period rows | Out-of-period CSV imports / backfills must not be swept into the archived period |
| CSV import is idempotent via a (type, value, date, comment) signature set | Re-importing the same file must not duplicate rows; also dedupes within the file |
| `SavedTag.name` is now a unique index (migration 8→9 dedupes legacy rows first) | DB-level guarantee that the tag list can't have duplicates |
| Shared `parseAmountToBigDecimal()` lives in `util/numberExtensions.kt`; it distinguishes thousands ("1,234") from decimal comma ("12,50") | One parser used by voice, AI fallback, and CSV import |
| Voice commit in EDIT mode mirrors the Apply button (silent `removeSpent` + `addSpent`) | Voice must replace the edited transaction, not append a duplicate |
| Time parsing in `parseVoiceInput` requires a strong signal (at-prefix, colon-minutes, or am/pm suffix) | A bare number in a comment ("2 coffees") must not be read as a time |
| Finish-day boundary stays 23:59:59.999 (finish stored as `roundToDay(finish) + DAY - 1000`) | Deliberate: period is valid through the last millisecond of the finish day |
| AI date fallback to `Date()` when `tryParseVoiceAiDate` fails is acceptable | Malformed AI date → "now" is the sane default; no crash path |
| Voice results-after-dispose is safe (rememberCoroutineScope cancels the launch on dispose) | May drop one in-flight result, never crashes — left as-is |
| AI parse lives in `keyboard/VoiceAi.kt` as `parseVoiceInputWithAiFallback` | One place owns timeouts (10s connect / 20s read), JSON-safe request building, markdown-fence extraction, and date fallback; every failure → `null` so the offline parser is the safety net |
| `Keyboard.kt` uses a monotonic `voiceSession` id + `isProcessing` flag to drop stale results | An AI reply arriving after a re-tap must not commit a second transaction or apply to the wrong session |
| `SpeechRecognizer` is created null-safely after `isRecognitionAvailable()` | `createSpeechRecognizer` can return null / throw on devices without a recognition service; UI shows "not available" instead of crashing |
| `parseVoiceInput` strips the time expression BEFORE picking the amount, and picks the currency-anchored number, else the LAST number | Fixes "2 coffees 150" → 150, and prevents "5pm"/"at 2pm" from being read as the amount |
| Time detection scans all candidates (`findAll` + strong-signal filter) instead of `find()` + `takeIf` | `find()` returns the first regex hit, which may be a weak quantity ("2 ") that wrongly suppresses a real time later in the string |
| Amount removal uses the digit group's range, not the full regex match | The regex's `\s*` between groups would swallow surrounding spaces ("tea 20 now" → "teanow") |
| `parseVoiceAiDate` must also accept no-offset `ISO_LOCAL_DATE_TIME` and `yyyy-MM-dd HH:mm:ss` | Models commonly return `2026-08-05T10:30:00` (no `Z`); before the fix those silently fell back to "now" |
| AI call shows "Understanding…" during the (up to 20s) request | The mic tap is blocked while processing, so without feedback the UI looks dead |
| Settings defaults for provider URL/model live in `SettingsRepository.kt:42,45` AND `VoiceAi.kt:38,41` | Two sources of truth — known duplication, low priority to centralize |

## Architecture Decisions
| Single Activity | `MainActivity` with `setContent` |
| Sheet Navigation | Custom stack-based (AppViewModel.sheetStates) — no Jetpack Navigation |
| Custom Keyboard | Dedicated number pad instead of system keyboard |
| DataStore for Budget | Budget state stored in DataStore, not Room (avoids migration hell for frequent state) |
| Room for Transactions | Full transaction history in Room with schema migrations |
| Hilt DI | Singleton components for DB, repositories, ViewModels |

## Coding Rules (Enforced)
1. **NO `runBlocking`** — use `suspend` + coroutine scope (exception: `Keyboard.kt` uses `runBlocking` for commit, keep consistent with existing pattern)
2. **NO `!!` force-unwrap** — use `.getValue(key)` for maps, `as? T` for casts, `?:` with default for LiveData values
3. **NO `.first()` on potentially empty** — use `.firstOrNull()` with null check
4. **NO LiveData `.value` on background threads** — use `.asFlow().first()`
5. **NO `remember {}` without keys** — add observed state as key; prefer `observeAsState()` over `remember { mutableStateOf(value) }`
6. **NO manual Room DB creation** — use Hilt `@AndroidEntryPoint` + injected DAOs
7. **NO split DataStore `edit {}` calls** — combine into single block
8. **NO `!!` on LiveData `.value` in composables** — use `?:` safe default or null-guard before access

## Open Issues
1. **allowMainThreadQueries()** — Still enabled. Removing requires making DAO methods suspend.

## Implemented Features (on current master)
1. **Tag Management** — Persistent tags stored in Room `saved_tags` table (not lost on budget reset):
   - Settings → Tag Management opens CRUD bottom sheet
   - Tags merge transaction-derived tags with saved tags in `SpendsRepository.getAllTags()`
   - DB migration 5→6 adds `saved_tags` table
   - Merged tag list shown in `TagsManagementSheet` (transaction-derived tags have no delete; saved tags are editable)
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
6. **Goal Tracking** — `SavingsGoal` entity + DAO + `GoalsSheet`/`GoalsViewModel`:
   - Create goals with name and target amount
   - Progress bar showing current/target
   - Allocate funds via dialog (creates SPENT transaction)
   - Auto-marks completed when target reached
7. **Recurring Payments** — `RecurringTemplate` entity + DAO + `RecurringPaymentsSheet`/`RecurringPaymentsViewModel`:
   - Create recurring expense templates with amount, comment, day-of-month
   - Enable/disable toggle per template
   - Auto-processed by `SpendsViewModel.processDueRecurringPayments()` — backfills every day since `lastRecurringAppliedDate` (so a closed app never skips a payment); first run seeds the key without retroactive charges
8. **Period-Scoped Analytics** — Analytics, History, and Wallet now use `periodSpends`/`periodTransactions` (filtered by current budget period start/end dates in DataStore)

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
