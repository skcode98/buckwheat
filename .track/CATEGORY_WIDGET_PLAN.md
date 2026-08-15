# Category Utilization Widget — Plan (2026-08-15)

> **Goal**: a 4th home-screen widget showing per-category spend/utilization for the current budget period as **pills** — the same `CategoryBatteryChip` style used in the analytics spend-categories card (emoji + name + amount + fill/percent vs cap), rendered in Glance.
> Status: **PLANNED** (backlog item 6 "Per-category allowance widget" from `FEATURES_AND_IMPROVEMENTS.md`, upgraded from single-category pin to a full category-utilization overview).

---

## Why / what the user asked

"1 more widget based on categories information utilization and informative — I like the pills we use in all designs, something like that."

- Show the current period's categories as **battery pills** (capped) and **pill chips** (uncapped), exactly like `analytics/categoriesChart/CategoryBatteryChip.kt` + `TagAmount.kt`.
- Utilization = period category total vs the category cap (`categoryCapPercent`/`categoryBatteryFraction`), plus the informative extra: total spent of period, % of budget, days left.
- Glance 1.1.1 constraint: no `Canvas`/`LazyColumn`/`FlowRow`/`clipRect` composables — text goes through the existing `CanvasText` bitmap engine and fills through pre-rendered bitmaps (the `drawProgressBarBitmap` pattern) or stacked fill-layer widths.

---

## Design — "Buckwheat categories" widget (`CategoryWidget`)

### Sizes (`SizeMode.Responsive`, mirror the Extend widget's fixed-width column stack)
| Mode | Size | Shows |
|---|---|---|
| `small` | 220×110 | Header (Total · % budget) + up to 2 pills |
| `medium` | 220×160 | Header + up to 3 pills |
| `large` | 220×240 | Header + up to 5 pills |
| `huge` | 220×400 | Header + all capped categories (scroll if needed via bitmap) |

Column layout (no wrapping possible — fixed width, one pill per row):
1. **Header row**: "Categories" caption + period total + "X% of budget" (reuse the budget % math already in `WidgetReceiver.observeData`).
2. **Pill rows** (top-N by spent, capped categories first): each pill = rounded 32dp full-width `Box` —
   - fill layer: `baseColor` with fill fraction = `categoryBatteryFraction(total, cap)` (bitmap-drawn like `drawProgressBarBitmap`, or two stacked `Box`es with `fillMaxWidth(fraction)` since Glance supports fractional fill widths),
   - pill contents: `emoji` + category name (ellipsized) + "₹used" + "NN%" (or no % for uncapped → plain `TagAmount`-style pill).
3. Footer hint: "Tap to open categories" → `MainActivity` (same invisible clickable overlay as the other widgets).

### Color mapping (must match analytics)
- BuiltIn → `baseColors[ordinal % baseColors.size]`; OTHER → neutral `restColor`; Custom → `baseColors[Math.floorMod(name.hashCode(), size)]`.
- `harmonizeWithColor(designColor, primary)` + `toPalette` — **problem**: `MaterialTheme.colorScheme.primary` is not available in Glance. Use `GlanceTheme.colors.primary` (from the widget's resolved theme) as the harmonization source. Same decision as the Extend/Voice widgets using `GlanceTheme.colors.primary`.
- Fill thresholds identical to `CategoryBatteryChip`: `>=100%` → theme error red, `>=80%` → amber `E6A23C`, else base; track = `baseColor` @ 15%.

### Data pipeline (widget receiver)
Reuses the base `WidgetReceiver.observeData` additions:
1. Read `startPeriodDate`/`finishPeriodDate` (already there) → `getSpendsInRange(start, finish).asFlow().first()`.
2. `categoryTotals(spends)` (`data/categories/SpendCategorizer.kt:71`) → ordered `Pair<CategoryKey, BigDecimal>`.
3. `settingsRepository.getCategoryCaps().first()` (`parseCategoryCaps`).
4. For each key: cap lookup (BuiltIn `caps[key.category.name]`, Custom `caps[key.name]`), `categoryCapPercent`/`categoryBatteryFraction`, emoji via `SpendCategory.emojiFor` (custom) / `category.emoji` (built-in), label via `labelRes` (built-in) / `key.name` (custom).
5. Serialize rows into Glance state: `name|emoji|used|cap` joined with `;` under a new key (e.g. `categoryRowsPreferenceKey = "category-rows-key"`), plus `categoryHeaderKey` with total/percent. Cap rows at the max pill count so tiny widgets don't get over-long state.
6. `glanceAppWidget.update(context, glanceId)`.

### Files (new)
- `app/src/main/java/com/danilkinkin/buckwheat/widget/category/CategoryWidget.kt` — `GlanceAppWidget` + `CategoryWidgetContent` (pill renderer, bitmap helpers `drawCategoryPillBitmap`, header).
- `app/src/main/java/com/danilkinkin/buckwheat/widget/category/CategoryWidgetReceiver.kt` — `@AndroidEntryPoint : WidgetReceiver`, `glanceAppWidget = CategoryWidget()`, companion `requestUpdateData`.
- `app/src/main/res/xml/category_app_widget_provider.xml` — 220×110 min, resizeMode vertical, preview image/layout.
- `app/src/main/res/layout/category_app_widget_preview.xml` + `res/drawable/category_app_widget_preview.xml`.
- Manifest `<receiver>` (label `@string/app_widget_category_name` = "Buckwheat categories").

### Files (modified)
- `widget/CommonWidgetReceiver.kt` — new state keys + populate in `observeData` (normal branch).
- `Application.kt:66-70` (onActivityPaused), `WidgetRefreshReceiver.kt:16-18`, `TryWidget.kt` — add the category receiver to the refresh/pin lists (1 line each).
- `strings.xml` — `app_widget_category_name` + any pill/header strings.
- `settings/TryWidget.kt` — widget row + preview.

### Tests
- Pure helper `categoryWidgetRows(spends, caps)` (build + sort rows, cap-N, serialize) → new `CategoryWidgetTest.kt` (row building, sorting, cap/uncap classification, percent, N-limit). Mirrors the existing pure-function test pattern (`ListAnimationTest`, `HistoryRowsTest`).
- No Robolectric render test (matches existing widget-test limitation: Glance `update()` never runs `provideGlance`).

---

## Implementation order (each step keeps the golden pipeline green)

1. **Pure model + test**: `categoryWidgetRows` + `CategoryWidgetTest` (no UI, no manifest).
2. **Widget skeleton**: `CategoryWidget.kt` content composable rendering pills from state (static for now), provider xml, preview, manifest receiver.
3. **Receiver + data**: keys in `CommonWidgetReceiver`, populate in `observeData`, `requestUpdateData` wiring (Application, WidgetRefreshReceiver, TryWidget).
4. **Settings row**: add to `TryWidget` pin list.
5. **Golden pipeline** (detached): `spotlessApply` + `testDebugUnitTest` + `assembleDebug`; commit + push; docs update (LESSONS/CHANGELOG/MEMORY/CACHE/DECISIONS/AGENTS/session-state).

---

## Open questions (confirmed 2026-08-15)
1. **Name** — **"Buckwheat categories"** (`app_widget_category_name`).
2. **Which categories to show** — **capped first, then uncapped top-N by spend**, limited by widget height.
3. **Per-instance config** — **YES**: follow the voice widget's `android:configure` pattern. A `CategoryWidgetDesign` enum (`BATTERY` pills = default / `COMPACT` = more pills, no fill) with a global settings row + a per-instance override stored in Glance state under `categoryDesignOverridePreferenceKey`, resolved by a pure `effectiveCategoryWidgetDesign(overrideName, globalName)`. Configure activity `CategoryWidgetConfigureActivity` (same shape as `VoiceWidgetConfigureActivity`).
