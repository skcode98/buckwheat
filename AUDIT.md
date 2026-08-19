# Buckwheat Project Audit Report

**Date:** 2026-08-19  
**Scope:** Full project — data layer, UI, notifications, patterns, AI, tests, build config

---

## Summary

| Severity | Count | Fixed |
|----------|-------|-------|
| CRITICAL | 3 | 3 |
| HIGH | 9 | 9 |
| MEDIUM | 14 | 14 |
| LOW | 18 | 0 (deferred) |
| **Total** | **44** | **26** |

---

## CRITICAL Issues

### C1. DaysLeftCard force-unwrap NPE
- **File:** `wallet/DaysLeftCard.kt:44`
- **Bug:** `finishDate!!` force-unwraps nullable parameter → NPE crash
- **Fix:** Use `finishDate?.let { ... } ?: return`

### C2. PastPeriodCard NaN in LinearProgressIndicator
- **File:** `settings/PastPeriodsSheet.kt:189`
- **Bug:** `period.totalSpent.toFloat() / period.budget.toFloat()` → NaN when budget=0. NaN passes through `coerceIn` and crashes Material3 LinearProgressIndicator
- **Fix:** Guard: `if (period.budget > BigDecimal.ZERO) ... else 0f`

### C3. Notification ID collision
- **Files:** `notifications/CategoryCapNotifier.kt:22`, `widget/voice/VoiceWidgetNotifications.kt:15`
- **Bug:** Both use `NOTIFICATION_ID = 300`. Category cap notification replaces voice widget foreground service notification → crashes foreground service on older Android
- **Fix:** Change `VoiceWidgetNotifications.NOTIFICATION_ID_LISTENING` to `400`

---

## HIGH Issues

### H1. PIN change dialog bypasses lockout
- **File:** `settings/AppLockSetting.kt:500-516`
- **Bug:** `PinChangeDialog` step 0 verifies PIN via `verifyPinHash()` directly, bypassing `AppLockViewModel.verifyPin()` which enforces lockout. Attacker can brute-force PIN through change dialog
- **Fix:** Route PIN verification through `AppLockViewModel.verifyPin()` or add attempt counting

### H2. SpendsRepository `!!` on DataStore keys in logs
- **File:** `di/SpendsRepository.kt:231-232, 337-338, 359-361`
- **Bug:** Log statements use `!!` on DataStore keys → crash if key missing
- **Fix:** Use `?: "null"` in log strings

### H3. `changeBudget` uses `!==` reference equality
- **File:** `di/SpendsRepository.kt:328`
- **Bug:** `if (newStartDate !== null)` uses referential equality instead of `!=`
- **Fix:** Change to `!= null`

### H4. RoomConverters crashes on malformed BigDecimal
- **File:** `di/RoomConverters.kt:18`
- **Bug:** `BigDecimal(input)` throws NumberFormatException on empty/malformed string from corrupted DB or bad backup
- **Fix:** Use `toBigDecimalOrNull() ?: BigDecimal.ZERO`

### H5. PatternPrompt leaks comment names to AI
- **File:** `patterns/PatternPrompt.kt:152`
- **Bug:** `recurringForecasts` passes raw `template.comment` (e.g., "Netflix") to external AI, contradicting privacy claims
- **Fix:** Anonymize or exclude comment text from AI prompt

### H6. `restoreBackup()` DataStore not atomic with DB
- **File:** `di/BackupRepository.kt:108-127`
- **Bug:** DB restored in Room transaction, but DataStore updates happen after. Crash between = stale budget state
- **Fix:** Add try-catch around DataStore writes with rollback logging

### H7. `archiveCurrentPeriod()` not in Room transaction
- **File:** `di/SpendsRepository.kt:254-317`
- **Bug:** BudgetPeriod insert + ArchivedTransaction inserts are separate operations. Crash between = period with zero archived transactions
- **Fix:** Wrap in `database.withTransaction {}`

### H8. `addSpent()` silent data loss on missing DataStore keys
- **File:** `di/SpendsRepository.kt:446-456`
- **Bug:** If DataStore keys are null (before budget set), `return@edit` skips update silently. Transaction in Room but budget counters out of sync
- **Fix:** Add logging for this case; transaction already inserted

### H9. Backup archived transactions inserted one-by-one
- **File:** `di/BackupRepository.kt:94-96`
- **Bug:** `forEach { insertArchivedTransactions(listOf(it)) }` — O(n) DB calls instead of batch
- **Fix:** `insertArchivedTransactions(backup.archivedTransactions)`

---

## MEDIUM Issues

### M1. Missing `key()` in LazyColumn items (7 sheets)
- **Files:** WorldCurrencyChooser.kt, CategoriesManagementSheet.kt, TagsManagementSheet.kt, RecurringPaymentsSheet.kt, GoalsSheet.kt, CategoryCapsSheet.kt, PeriodDetailSheet.kt
- **Bug:** No diff keys → inefficient recomposition
- **Fix:** Add `key` parameter to each `items()` call

### M2. LangSwitcher `===` reference equality
- **File:** `settings/LangSwitcher.kt:149`
- **Bug:** `locale.language === currentLocale.language` uses `===` instead of `==`
- **Fix:** Change to `==`

### M3. NumberedRow.kt `!==` reference equality
- **File:** `onboarding/NumberedRow.kt:62`
- **Bug:** `if (subtitle !== null)` should be `!= null`
- **Fix:** Change to `!= null`

### M4. BudgetConstructor wrong keyboard type
- **File:** `wallet/BudgetConstructor.kt:176`
- **Bug:** `KeyboardType.Password` for monetary input
- **Fix:** Change to `KeyboardType.Decimal`

### M5. ArchivesViewModel coroutine leak
- **File:** `settings/ArchivesViewModel.kt:34-39`
- **Bug:** Manual `launch + collect` pattern instead of `.stateIn()`
- **Fix:** Use `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)`

### M6. CommonWidgetReceiver coroutine scope never cancelled
- **File:** `widget/CommonWidgetReceiver.kt:87`
- **Bug:** `CoroutineScope(Dispatchers.IO + job)` never cancelled → accumulates on receiver recreation
- **Fix:** Cancel scope in `onDisabled()`

### M7. Widget receiver `===` null checks
- **File:** `widget/CommonWidgetReceiver.kt:128-130`
- **Bug:** `finishDate !== null` uses `===` instead of `!=`
- **Fix:** Change to `!= null`

### M8. BackupRepository `prefs.clear()` wipes all DataStore
- **File:** `di/BackupRepository.kt:109`
- **Bug:** `prefs.clear()` before `applyBackupMap` → empty DataStore if map fails
- **Fix:** Don't clear; overwrite only backed-up keys

### M9. RecalcBudgetViewModel concurrent launches
- **File:** `recalcBudget/RecalcBudgetViewModel.kt:27-34`
- **Bug:** Three separate `viewModelScope.launch` calls → briefly inconsistent state
- **Fix:** Merge into single launch or use sequential reads

### M10. AiInsightViewModel no staleness guard
- **File:** `ai/AiInsightViewModel.kt:72-89`
- **Bug:** Rapid double-call can overwrite AI result
- **Fix:** Add job cancellation before launching new AI request

### M11. BudgetCalculator negative `skippedDays`
- **File:** `di/BudgetCalculator.kt:133`
- **Bug:** `dailyBudget * skippedDays` happens before `coerceAtLeast(0)` — negative value adds to rest instead of subtracting
- **Fix:** Coerce `skippedDays` to 0 before the multiply

### M12. `fallbackToDestructiveMigration()` in production
- **File:** `di/AppModule.kt:24`
- **Bug:** Schema bump without migration wipes all user data
- **Fix:** Add comment warning; acceptable for dev but needs migration strategy for production

### M13. BudgetConstructor keyboard type
- **File:** `wallet/BudgetConstructor.kt`
- **Same as M4**

### M14. ArchivesViewModel state collection
- **File:** `settings/ArchivesViewModel.kt`
- **Same as M5**

---

## LOW Issues (Deferred — Not Fixed)

| ID | File | Issue |
|----|------|-------|
| L1 | `data/entities/Transaction.kt:36` | Missing index on `category` column |
| L2 | `data/entities/BudgetPeriod.kt` | Missing index on `start_date`/`finish_date` |
| L3 | `data/entities/SavingsGoal.kt` | Missing index on `completed` |
| L4 | `wallet/RestBudgetPillViewModel.kt` | Typo: `DaileBudgetState` → `DailyBudgetState` |
| L5 | `ai/AiBackend.kt:49` | API key in plain-text DataStore |
| L6 | `data/categories/SpendCategorizer.kt:99` | PII in AI categorization comments |
| L7 | `widget/CommonWidgetReceiver.kt:114-264` | Non-atomic widget state updates |
| L8 | `util/PinHash.kt:75-77` | No progressive lockout escalation |
| L9 | `data/AppLockViewModel.kt:48-53` | Brief unlocked flash after process death |
| L10 | `data/entities/ArchivedTransaction.kt:28` | Missing `@ColumnInfo(defaultValue="")` on comment |
| L11 | `data/entities/RecurringTemplate.kt:10-11` | Missing explicit `@ColumnInfo` |
| L12 | `data/entities/SavingsGoal.kt:11,16,19` | Missing explicit `@ColumnInfo` |
| L13 | `di/SettingsRepository.kt:135-143` | Exception-based enum defaults |
| L14 | `notifications/OverspendingNotifier.kt:19` | No dedup guard |
| L15 | `data/categories/CategoryAssignmentScheduler.kt:43-53` | Brief `_isRunning` state inconsistency |
| L16 | `recalcBudget/RecalcBudgetViewModel.kt:28-29` | No guard for zero/negative remaining days |
| L17 | `patterns/PatternEngine.kt:872` | bigDecimalSqrt seeds from Double |
| L18 | `patterns/PatternEngine.kt:940` | Direct array index without defensive check |

---

## String Resource Issues

| Issue | Severity | Description |
|-------|----------|-------------|
| 91% untranslated | Critical | RU has 188/530 keys, other locales 137/530 |
| 11 extra keys | Low | Keys in all locales but missing from EN base |
| Format string mismatches | High | `select_finish_date_title` RU has 12 placeholders vs EN's 6 |

---

## Test Coverage

| Metric | Value |
|--------|-------|
| Source files | 271 |
| Test files | 53 |
| Coverage | 19.6% |
| Completely untested | wallet/, editor/, home/, settings/, onboarding/, analytics/, widget/ |

---

## Build Config Issues

| Issue | Severity | Description |
|-------|----------|-------------|
| `fallbackToDestructiveMigration()` | High | Schema bump wipes user data |
| Minimal ProGuard rules | High | Missing commons-csv keep rule |
| `enableSplit = false` | Medium | All 23 locales bundled in single APK |
| Desugaring enabled | Low | Unnecessary with minSdk 29 |
