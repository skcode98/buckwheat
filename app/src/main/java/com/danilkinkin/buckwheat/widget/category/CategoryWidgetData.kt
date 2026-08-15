package com.danilkinkin.buckwheat.widget.category

import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.categories.categoryBatteryFraction
import com.danilkinkin.buckwheat.data.categories.categoryCapPercent
import com.danilkinkin.buckwheat.data.categories.categoryTotals
import com.danilkinkin.buckwheat.data.entities.Transaction
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

// Number of distinct hues used by the widget palette. Must stay in sync with
// analytics.categoriesChart.baseColors so custom categories get the same hue as the app.
const val CATEGORY_WIDGET_PALETTE_SIZE = 7

// One category in the widget list: the aggregated period spend for a category key plus the
// cap configured for it, or null when the category has no (positive) cap. Pure so it is
// trivially unit-testable.
data class CategoryWidgetRow(
    val key: CategoryKey,
    val used: BigDecimal,
    val cap: BigDecimal?,
) {
    val isCapped: Boolean get() = cap != null

    // Whole percent of the cap already spent (0 for uncapped), floored to 0..100.
    val percent: Int get() = cap?.let { categoryCapPercent(used, it) } ?: 0

    // Fill fraction (0..1) of the battery pill (0 for uncapped).
    val fraction: Float get() = cap?.let { categoryBatteryFraction(used, it) } ?: 0f
}

// Builds the widget rows from a period's spends and the configured caps: capped categories
// first sorted by utilization (most filled first, then by amount), then uncapped categories
// sorted by amount; capped at maxRows. Pure so it is trivially unit-testable.
fun categoryWidgetRows(
    spends: List<Transaction>,
    caps: Map<String, BigDecimal>,
    maxRows: Int = Int.MAX_VALUE,
): List<CategoryWidgetRow> =
    categoryTotals(spends)
        .map { (key, total) ->
            val cap = when (key) {
                is CategoryKey.BuiltIn -> caps[key.category.name]
                is CategoryKey.Custom -> caps[key.name]
            }?.takeIf { it > BigDecimal.ZERO }
            CategoryWidgetRow(key, total, cap)
        }
        .sortedWith(
            compareByDescending<CategoryWidgetRow> { it.isCapped }
                .thenByDescending { it.percent }
                .thenByDescending { it.used },
        )
        .take(maxRows.coerceAtLeast(0))

// A widget row with the display values already resolved (label, emoji, palette slot) so it
// can be serialized to Glance state without needing a Context in the composable. Pure so it
// is trivially unit-testable.
data class CategoryWidgetPill(
    val name: String,
    val emoji: String,
    val used: BigDecimal,
    val cap: BigDecimal?,
    val colorIndex: Int,
    val isSpecial: Boolean,
) {
    val percent: Int get() = cap?.let { categoryCapPercent(used, it) } ?: 0
    val fraction: Float get() = cap?.let { categoryBatteryFraction(used, it) } ?: 0f
}

// Resolves rows into render-ready pills. Built-in categories get their localized label (via
// displayName, supplied by the receiver with a Context), their enum emoji and ordinal palette
// slot; the OTHER category is marked special so the renderer uses the neutral color. Custom
// categories keep their raw name, get the saved/fallback emoji and a deterministic palette
// slot from their name hash. Pure so it is trivially unit-testable.
fun categoryWidgetPills(
    rows: List<CategoryWidgetRow>,
    displayName: (CategoryKey) -> String,
    categoryEmojis: Map<String, String> = emptyMap(),
): List<CategoryWidgetPill> = rows.map { row ->
    when (val key = row.key) {
        is CategoryKey.BuiltIn -> CategoryWidgetPill(
            name = displayName(key),
            emoji = key.category.emoji,
            used = row.used,
            cap = row.cap,
            colorIndex = key.category.ordinal % CATEGORY_WIDGET_PALETTE_SIZE,
            isSpecial = key.category == SpendCategory.OTHER,
        )
        is CategoryKey.Custom -> CategoryWidgetPill(
            name = displayName(key),
            emoji = SpendCategory.emojiFor(key.name, categoryEmojis[key.name]),
            used = row.used,
            cap = row.cap,
            colorIndex = Math.floorMod(key.name.hashCode(), CATEGORY_WIDGET_PALETTE_SIZE),
            isSpecial = false,
        )
    }
}

// Serializes pills into a JSON array string for Glance state. Pure so it is trivially
// unit-testable.
fun serializeCategoryWidgetPills(pills: List<CategoryWidgetPill>): String =
    JSONArray().apply {
        pills.forEach { pill ->
            put(
                JSONObject()
                    .put("name", pill.name)
                    .put("emoji", pill.emoji)
                    .put("used", pill.used.toPlainString())
                    .apply { pill.cap?.let { put("cap", it.toPlainString()) } }
                    .put("color", pill.colorIndex)
                    .put("special", pill.isSpecial),
            )
        }
    }.toString()

// Inverse of serializeCategoryWidgetPills. Tolerant: missing fields, unparsable amounts and
// non-positive caps fall back to safe defaults and the whole result is empty on garbage.
fun parseCategoryWidgetPills(raw: String?): List<CategoryWidgetPill> =
    runCatching {
        val array = JSONArray(raw.orEmpty().ifBlank { "[]" })
        List(array.length()) { index ->
            val json = array.getJSONObject(index)
            CategoryWidgetPill(
                name = json.optString("name", ""),
                emoji = json.optString("emoji", ""),
                used = json.optString("used", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                cap = json.optString("cap", "")
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { BigDecimal(it) }.getOrNull() }
                    ?.takeIf { it > BigDecimal.ZERO },
                colorIndex = json.optInt("color", 0),
                isSpecial = json.optBoolean("special", false),
            )
        }
    }.getOrElse { emptyList() }
