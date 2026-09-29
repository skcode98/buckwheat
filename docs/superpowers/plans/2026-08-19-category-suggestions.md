# Smart Category Suggestions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show smart category suggestions on the main screen based on time-of-day and purchase frequency patterns from all historical data.

**Architecture:** New engine function `buildCategorySuggestions()` analyzes all historical transactions (current + archived) to detect category patterns by time-of-day, weekday, and frequency. Results are exposed via `SpendsViewModel` and rendered as tappable chips in the editor between the tag toolbar and category selector.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, StateFlow

**Spec:** This plan (no separate spec file)

## Global Constraints

- Kotlin 2.2.0, Compose, Hilt, Room
- Min SDK 29 (Android 10)
- Follow existing code patterns in PatternEngine.kt
- Use existing `PatternDataset` as input (no new data sources)
- No schema changes required
- All historical data (current + archived periods) must be used

---

## File Structure

| File | Action | Purpose |
|------|--------|---------|
| `PatternData.kt` | Modify | Add `CategorySuggestion` data class |
| `PatternEngine.kt` | Modify | Add `buildCategorySuggestions()` function |
| `SpendsViewModel.kt` | Modify | Expose `categorySuggestions` StateFlow |
| `TaggingToolbar.kt` | Modify | Render suggestion chips alongside tags |
| `strings.xml` (EN) | Modify | Add suggestion reason strings |
| `strings.xml` (RU) | Modify | Add suggestion reason strings |

---

## Task 1: Add CategorySuggestion data class

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/patterns/PatternData.kt`

**Interfaces:**
- Consumes: `SpendCategory` enum (for category reference)
- Produces: `CategorySuggestion` data class used by engine and UI

- [ ] **Step 1: Add data class to PatternData.kt**

Add after the `CommentPattern` data class (around line 30):

```kotlin
data class CategorySuggestion(
    val category: String,           // SpendCategory enum name or custom name
    val emoji: String,              // display emoji
    val reasonRes: Int,             // @StringRes for reason text
    val reasonArgs: List<Any>,      // format args for reason string
    val strength: Float,            // 0.0-1.0, used for ranking
    val matchCount: Int,            // how many times this pattern was observed
)
```

- [ ] **Step 2: Verify compilation**

Run: `.\gradlew.bat compileDebugKotlin --exclude-task compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/patterns/PatternData.kt
git commit -m "feat: add CategorySuggestion data class"
```

---

## Task 2: Add buildCategorySuggestions() engine function

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/patterns/PatternEngine.kt`

**Interfaces:**
- Consumes: `PatternDataset` (from SpendsViewModel)
- Produces: `List<CategorySuggestion>` (sorted by strength desc, max 4)

- [ ] **Step 1: Add time window helper and suggestion function**

Add at the end of PatternEngine.kt (before `analyzePatterns()`):

```kotlin
enum class TimeWindow(val label: String, val startHour: Int, val endHour: Int) {
    MORNING("morning", 6, 10),
    LUNCH("lunch", 11, 14),
    AFTERNOON("afternoon", 14, 17),
    EVENING("evening", 17, 21),
    NIGHT("night", 21, 6),
}

private fun hourToWindow(hour: Int): TimeWindow = when (hour) {
    in 6..10 -> TimeWindow.MORNING
    in 11..14 -> TimeWindow.LUNCH
    in 15..17 -> TimeWindow.AFTERNOON
    in 18..20 -> TimeWindow.EVENING
    else -> TimeWindow.NIGHT
}

private fun dayOfWeekToLocalDay(date: Date): DayOfWeek =
    date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().dayOfWeek

fun buildCategorySuggestions(
    dataset: PatternDataset,
    maxSuggestions: Int = 4,
): List<CategorySuggestion> {
    if (dataset.spends.isEmpty()) return emptyList()

    // Group by canonical category
    val names = dataset.spends.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }.distinct()
    val index = categoryNameIndex(names)

    val byCategory = dataset.spends
        .filter { !it.category.isNullOrBlank() }
        .groupBy { canonicalCategory(it.category, index) }

    if (byCategory.isEmpty()) return emptyList()

    // Total months span for frequency calculation
    val allMonths = dataset.spends.map { YearMonth.from(it.date.toLocalDate()) }.distinct().size.coerceAtLeast(1)

    val suggestions = byCategory.map { (categoryKey, spends) ->
        if (spends.size < 3) return@map null

        val categoryName = spends.first().category ?: return@map null
        val emoji = emojiForCategory(categoryKey)

        // Signal 1: Frequency score
        val freqScore = (spends.size.toFloat() / allMonths).coerceIn(0f, 1f)

        // Signal 2: Time-of-day affinity
        val byWindow = spends.groupBy { hourToWindow(it.date.toInstant().atZone(ZoneId.systemDefault()).hour) }
        val topWindow = byWindow.maxByOrNull { it.value.size }
        val windowConcentration = if (topWindow != null) {
            topWindow.value.size.toFloat() / spends.size
        } else 0f
        val timeScore = if (windowConcentration >= 0.5f && topWindow!!.value.size >= 3) {
            windowConcentration
        } else 0f

        // Signal 3: Day-of-week affinity
        val byDay = spends.groupBy { dayOfWeekToLocalDay(it.date) }
        val topDay = byDay.maxByOrNull { it.value.size }
        val dayConcentration = if (topDay != null) {
            topDay.value.size.toFloat() / spends.size
        } else 0f
        val dayScore = if (dayConcentration >= 0.4f && topDay!!.value.size >= 2) {
            dayConcentration * 0.9f // slightly lower weight than time
        } else 0f

        // Signal 4: Combined time+day
        val comboScore = if (topWindow != null && topDay != null && timeScore > 0f && dayScore > 0f) {
            val comboCount = spends.count {
                hourToWindow(it.date.toInstant().atZone(ZoneId.systemDefault()).hour) == topWindow.key &&
                dayOfWeekToLocalDay(it.date) == topDay.key
            }
            if (comboCount >= 2) {
                (comboCount.toFloat() / spends.size) * 1.1f // boost for combined signal
            } else 0f
        } else 0f

        // Pick strongest signal
        val bestScore = maxOf(freqScore, timeScore, dayScore, comboScore)
        if (bestScore < 0.15f) return@map null

        // Build reason based on which signal won
        val reasonRes: Int
        val reasonArgs: List<Any>
        when {
            comboScore == bestScore -> {
                reasonRes = R.string.suggestion_reason_day_time
                reasonArgs = listOf(
                    topDay!!.key.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    topWindow!!.label
                )
            }
            timeScore == bestScore -> {
                reasonRes = R.string.suggestion_reason_time
                reasonArgs = listOf(topWindow!!.label)
            }
            dayScore == bestScore -> {
                reasonRes = R.string.suggestion_reason_day
                reasonArgs = listOf(topDay!!.key.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            }
            else -> {
                reasonRes = R.string.suggestion_reason_frequency
                reasonArgs = listOf(spends.size)
            }
        }

        CategorySuggestion(
            category = categoryKey,
            emoji = emoji,
            reasonRes = reasonRes,
            reasonArgs = reasonArgs,
            strength = bestScore,
            matchCount = spends.size,
        )
    }

    return suggestions
        .filterNotNull()
        .sortedByDescending { it.strength }
        .take(maxSuggestions)
}

private fun emojiForCategory(categoryKey: String): String {
    val builtIn = try {
        SpendCategory.valueOf(categoryKey)
    } catch (_: IllegalArgumentException) {
        null
    }
    return builtIn?.emoji ?: DEFAULT_EMOJI
}
```

- [ ] **Step 2: Add required imports to PatternEngine.kt**

```kotlin
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
```

- [ ] **Step 3: Verify compilation**

Run: `.\gradlew.bat compileDebugKotlin --exclude-task compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/patterns/PatternEngine.kt
git commit -m "feat: add buildCategorySuggestions() engine function"
```

---

## Task 3: Add string resources for suggestion reasons

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`

**Interfaces:**
- Consumes: nothing
- Produces: string resources used by CategorySuggestion.reasonRes

- [ ] **Step 1: Add English strings**

Add to `values/strings.xml`:

```xml
<string name="suggestion_reason_time">Usually in the %1$s</string>
<string name="suggestion_reason_day">Often on %1$s</string>
<string name="suggestion_reason_day_time">Habit on %1$s %2$ss</string>
<string name="suggestion_reason_frequency">Bought %1$d times</string>
```

- [ ] **Step 2: Add Russian strings**

Add to `values-ru/strings.xml`:

```xml
<string name="suggestion_reason_time">Обычно утром/днём/вечером — %1$s</string>
<string name="suggestion_reason_day">Часто по %1$s</string>
<string name="suggestion_reason_day_time">Привычка по %1$s в %2$ss</string>
<string name="suggestion_reason_frequency">Куплено %1$d раз</string>
```

(Exact Russian translations should be reviewed — the above are placeholders for structure.)

- [ ] **Step 3: Verify compilation**

Run: `.\gradlew.bat compileDebugKotlin --exclude-task compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml
git commit -m "feat: add category suggestion reason strings"
```

---

## Task 4: Expose category suggestions from SpendsViewModel

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/data/SpendsViewModel.kt`

**Interfaces:**
- Consumes: `PatternDataset` (already constructed internally)
- Produces: `categorySuggestions: StateFlow<List<CategorySuggestion>>` consumed by TaggingToolbar

- [ ] **Step 1: Add categorySuggestions StateFlow**

In SpendsViewModel.kt, add after the `suggestedBudget` property (around line 166):

```kotlin
val categorySuggestions: StateFlow<List<CategorySuggestion>> = combine(
    spends,
    archivedTransactions,
    budgetPeriods,
) { spends, archived, periods ->
    val allSpends = spends.map { PatternSpend(it.date, it.value, it.category, it.comment) } +
        archived.map { PatternSpend(it.date, it.value, it.category, it.comment) }

    val allPeriods = periods.map {
        PatternPeriod(it.startDate, it.finishDate, it.budget, it.totalSpent, it.isImported)
    }

    val today = LocalDate.now()
    val dataset = PatternDataset(
        spends = allSpends,
        periods = allPeriods,
        currencyCode = currency.value.currency,
        today = today,
    )

    buildCategorySuggestions(dataset)
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = emptyList(),
)
```

- [ ] **Step 2: Add required imports**

```kotlin
import com.danilkinkin.buckwheat.patterns.CategorySuggestion
import com.danilkinkin.buckwheat.patterns.buildCategorySuggestions
```

- [ ] **Step 3: Verify compilation**

Run: `.\gradlew.bat compileDebugKotlin --exclude-task compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/data/SpendsViewModel.kt
git commit -m "feat: expose categorySuggestions from SpendsViewModel"
```

---

## Task 5: Create SuggestionChips composable

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/editor/TaggingToolbar.kt`

**Interfaces:**
- Consumes: `List<CategorySuggestion>` from SpendsViewModel, `EditorViewModel.currentCategory`
- Produces: Renders suggestion chips, sets category on tap

- [ ] **Step 1: Add SuggestionChip composable and integrate into TaggingToolbar**

At the top of `TaggingToolbar.kt`, add a new composable:

```kotlin
@Composable
private fun CategorySuggestionChip(
    suggestion: CategorySuggestion,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = suggestion.emoji,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stringResource(suggestion.reasonRes, *suggestion.reasonArgs.toTypedArray()),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
```

- [ ] **Step 2: Modify TaggingToolbar to show suggestions**

In the `TaggingToolbar` composable, add suggestion chips before the existing tag chips. Modify the `Row` to include:

```kotlin
// Add inside the existing Row, before tags.take(5).reversed()
val suggestions by spendsViewModel.categorySuggestions.collectAsState()
val currentCategory by editorViewModel.currentCategory.collectAsState()

if (suggestions.isNotEmpty()) {
    suggestions.take(3).forEach { suggestion ->
        CategorySuggestionChip(
            suggestion = suggestion,
            isSelected = currentCategory == suggestion.category,
            onClick = {
                if (currentCategory == suggestion.category) {
                    editorViewModel.currentCategory.value = null
                } else {
                    editorViewModel.currentCategory.value = suggestion.category
                }
            },
        )
    }
    Spacer(Modifier.width(8.dp))
}
```

- [ ] **Step 3: Add required imports**

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.danilkinkin.buckwheat.patterns.CategorySuggestion
```

- [ ] **Step 4: Verify compilation**

Run: `.\gradlew.bat compileDebugKotlin --exclude-task compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/editor/TaggingToolbar.kt
git commit -m "feat: show category suggestions as tappable chips in editor"
```

---

## Task 6: End-to-end verification

- [ ] **Step 1: Full build**

Run: `.\gradlew.bat compileDebugDebugKotlin --exclude-task compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL with no errors

- [ ] **Step 2: Manual test scenarios**

Verify these scenarios work:
1. Fresh install with no data → no suggestions shown
2. Add 3+ spends in same category with same time-of-day → suggestion appears
3. Tap suggestion chip → category selector updates to that category
4. Tap same chip again → category clears (toggle behavior)
5. Add spends across different categories → top 3-4 shown by strength
6. Suggestion chips don't interfere with existing tag chips

- [ ] **Step 3: Final commit if any fixes needed**

```bash
git add -A
git commit -m "fix: category suggestion UI polish"
git push
```

---

## Algorithm Summary

```
For each category with ≥3 transactions:
  1. Compute frequency score: txns_per_month (capped at 1.0)
  2. Compute time-of-day affinity: group by 6h window, check if ≥50% in one window with ≥3 txns
  3. Compute day-of-week affinity: group by day, check if ≥40% on one day with ≥2 txns
  4. Compute combined time+day: check if ≥2 txns in same window+day combo
  5. Pick strongest signal → build reason string
  6. Return top 4 by strength
```

## Edge Cases

- **No categories assigned yet** → returns empty list, no chips shown
- **Only 1-2 txns per category** → threshold not met, no suggestions
- **All categories below threshold** → returns empty list
- **Custom categories** → uses `DEFAULT_EMOJI` (🏷️) since custom categories have no enum
- **Archived data** → included in analysis, gives stronger signals for long-term users
