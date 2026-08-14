# Issues & Enhancements

## CRITICAL

### 1. `runBlocking` in composable event handlers
**Files:** `keyboard/Keyboard.kt:319,328,617`, `editor/CurrentSpendEditor.kt:132`

`runBlocking` inside `onClick`/`onValueChange` composable lambdas blocks the main thread during every keypress. This causes jank and ANRs, especially on slower devices. The `rememberAppKeyboardDispatcher` lambda and `CurrentSpendEditor` text-field change handler both call `runBlocking` around ViewModel suspend functions (`resetEditingSpent`, `rawSpentValue.value = …`, `canCommitEditingSpent`).

**Fix:** Replace with `LaunchedEffect(key)` + `snapshotFlow` observing the input state, or make the dispatcher a `suspend` lambda and call it from a `LaunchedEffect`.

---

### 2. `bufferedReader()` without explicit charset in `VoiceAiSettingsViewModel.kt:72`

Same bug pattern as previously fixed in `AiBackend.kt`. The models-fetch endpoint reads `conn.inputStream.bufferedReader()` with the platform default charset. On devices whose default is not UTF-8, non-ASCII model names or error messages will garble.

**Fix:** `conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }`

---

## HIGH

### 3. `minOrNull()!!` / `maxOrNull()!!` in `util/Swipeable.kt:136,137,172,173,783`

If `newAnchors` or `anchors` is empty, these throw `NullPointerException`. The anchors map is usually populated by the caller, but an empty map in an edge case (e.g., rapid state change before layout) will crash.

**Fix:** Replace with `requireNotNull(newAnchors.keys.minOrNull()) { "anchors must not be empty" }` or guard with `if (newAnchors.isEmpty()) return`.

---

### 4. Unsafe `as` casts in `home/BottomSheets.kt:97,98,168,177`

```kotlin
selectDate = state.args["initialDate"] as Date?,
onlyDay = state.args["onlyDay"] as LocalDate?,
```

If any caller passes a wrong type (or a future sheet passes a different key), this throws `ClassCastException`.

**Fix:** Use `state.args["initialDate"] as? Date? ?: return` or a typed args wrapper instead of a raw `Map<String, Any?>`.

---

### 5. `systemLocale!!` in `ui/Locale.kt:30`

`LocalContext.current.systemLocale` is a custom extension property. If it reads from `DataStore` before the value is set, it returns `null` and the `!!` crashes on startup.

**Fix:** `LocalContext.current.systemLocale ?: LocalConfiguration.current.locales[0]` as a safe fallback.

---

### 6. `getSystemService` as-casts without null check (17 sites)

Every receiver/scheduler casts `getSystemService` directly (`as AlarmManager`, `as NotificationManager`). If the system service is unavailable (rare but possible on some OEMs), this crashes.

**Fix:** `context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return` or `?: error("AlarmManager unavailable")`.

---

## MEDIUM

### 7. Excessive `!!` in main code

- `history/History.kt` — `row.transaction!!` (lines 169, 181, 242, 246, 250, 253, 275)
- `wallet/Wallet.kt` — `dateToValue.value!!`, `finishPeriodDate!!`, `budgetCache > BigDecimal(...)` (lines 85, 96, 317, 326, 332, 346, 351)
- `editor/CurrentSpendEditor.kt:54,63` — `rawSpentValue.value!!`
- `keyboard/Keyboard.kt:333` — `rawSpentValue.value!!.toBigDecimal()`
- `widget/CanvasText.kt:56` — `style.fontSize!!.value`

These will throw if the observed value is `null` at any point.

**Fix:** Replace with `?: return`, `?: continue`, or safe defaults (`""`, `BigDecimal.ZERO`, `Date()`).

---

### 8. Empty `onClick = {}` handlers in production composables

- `base/DescriptionButton.kt:78`
- `editor/toolbar/restBudgetPill/RestBudgetPill.kt:226`
- `base/ButtonRow.kt:56,67`
- `base/BigIconButton.kt:66`
- `settings/VoiceAiSettingsSheet.kt:311`

These render as tappable UI but do nothing. Users tapping them get no feedback.

**Fix:** Either implement the action, disable the button (`enabled = false`), or remove the clickable wrapper.

---

### 9. Legacy `Storage` entity/DAO still in database

`data/entities/Storage.kt`, `data/dao/StorageDao.kt`, and the `storage` Room table are defined and migrated but never read or written by any production code. The app migrated to DataStore, but the dead table remains in the schema.

**Fix:** Remove the entity, DAO, and table; bump the Room version and add a migration that drops the table.

---

## LOW / ENHANCEMENTS

### 10. `CrashLogger.kt` uses `SharedPreferences` instead of DataStore

Crash logs write to `getSharedPreferences`. Since the app already uses DataStore everywhere, this is a minor inconsistency. Acceptable because `CrashLogger` must survive DataStore corruption, but worth noting.

---

### 11. `base/TextRow.kt` previews have `/*TODO*/` click handlers (lines 246, 264, 282, 301, 321)

These are only in `@Preview` composables, not shipped code, but they clutter the file.

**Fix:** Remove the previews or replace with `onClick = {}`.

---

### 12. Enhancement: `extractProviderText` is tolerant but doesn't log the raw response shape

When a new backend returns an unexpected envelope, the current code silently returns `"empty reply"`. Adding a one-time `Log.d("AiBackend", "unexpected envelope: $responseText")` (truncated to 200 chars) would make future debugging faster without leaking secrets.

---

### 13. Enhancement: `VoiceAiSettingsViewModel.loadFreeModels` returns `emptyList()` on any failure

If the models endpoint is down or the URL is misconfigured, the dropdown just shows "No models available". It would be more helpful to also show the HTTP error code (or a generic "check URL" hint) in the empty state.

---

### 14. Enhancement: `CategoryAssigner` runs AI categorization on all uncategorized spends, including already-AI-categorized ones from a previous run that failed to persist

`categorizeSpendsWithAi` skips rows with a non-null/non-empty `category`, but `assignToUncategorized` only filters `category IS NULL OR category = ''`. If a previous AI pass set `category = "AI:FOOD"` and then failed to persist, the row is skipped forever. Consider adding a `category.startsWith("AI:")` filter or a status column.
