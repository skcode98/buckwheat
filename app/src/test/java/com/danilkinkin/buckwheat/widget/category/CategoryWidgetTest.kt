package com.danilkinkin.buckwheat.widget.category

import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class CategoryWidgetTest {

    private fun spent(
        uid: Int,
        value: String,
        category: String?,
    ): Transaction = Transaction(
        type = TransactionType.SPENT,
        value = BigDecimal(value),
        date = LocalDateTime.of(2026, 8, 5, 12, 0).toDate(),
        comment = "",
        category = category,
    ).also { it.uid = uid }

    @Test
    fun aggregatesTotalsAndAttachesCaps() {
        val rows = categoryWidgetRows(
            spends = listOf(
                spent(1, "100", "FOOD"),
                spent(2, "50", "FOOD"),
                spent(3, "40", "TRANSPORT"),
            ),
            caps = mapOf("FOOD" to BigDecimal("300"), "TRANSPORT" to BigDecimal("50")),
        )

        assertEquals(2, rows.size)
        val food = rows.single { (it.key as CategoryKey.BuiltIn).category == SpendCategory.FOOD }
        assertEquals(BigDecimal("150"), food.used)
        assertEquals(BigDecimal("300"), food.cap)
        assertEquals(50, food.percent)
        assertEquals(0.5f, food.fraction, 0.001f)
        assertTrue(food.isCapped)

        val transport = rows.single { (it.key as CategoryKey.BuiltIn).category == SpendCategory.TRANSPORT }
        assertEquals(BigDecimal("40"), transport.used)
        assertEquals(80, transport.percent)
    }

    @Test
    fun nonPositiveCapIsTreatedAsUncapped() {
        val rows = categoryWidgetRows(
            spends = listOf(spent(1, "100", "FOOD")),
            caps = mapOf("FOOD" to BigDecimal("0")),
        )

        assertNull(rows.single().cap)
        assertFalse(rows.single().isCapped)
        assertEquals(0, rows.single().percent)
        assertEquals(0f, rows.single().fraction, 0.001f)
    }

    @Test
    fun cappedCategoriesComeFirstSortedByUtilizationThenUncappedByAmount() {
        val rows = categoryWidgetRows(
            spends = listOf(
                spent(1, "30", "TRANSPORT"), // cap 100 -> 30%
                spent(2, "90", "FOOD"), // cap 100 -> 90% (top capped)
                spent(3, "80", "HEALTH"), // cap 100 -> 80%
                spent(4, "500", "BILLS"), // uncapped, largest
                spent(5, "10", "SHOPPING"), // uncapped
            ),
            caps = mapOf(
                "TRANSPORT" to BigDecimal("100"),
                "FOOD" to BigDecimal("100"),
                "HEALTH" to BigDecimal("100"),
            ),
        )

        assertEquals(
            listOf("FOOD", "HEALTH", "TRANSPORT", "BILLS", "SHOPPING"),
            rows.map { (it.key as CategoryKey.BuiltIn).category.name },
        )
    }

    @Test
    fun cappedCategoriesTieBreakByAmount() {
        val rows = categoryWidgetRows(
            spends = listOf(
                spent(1, "40", "FOOD"), // 80/200 -> 40%
                spent(2, "60", "TRANSPORT"), // 60/150 -> 40% but larger amount
            ),
            caps = mapOf("FOOD" to BigDecimal("200"), "TRANSPORT" to BigDecimal("150")),
        )

        assertEquals(
            listOf("TRANSPORT", "FOOD"),
            rows.map { (it.key as CategoryKey.BuiltIn).category.name },
        )
    }

    @Test
    fun maxRowsTruncatesKeepingTheOrder() {
        val rows = categoryWidgetRows(
            spends = listOf(
                spent(1, "90", "FOOD"),
                spent(2, "80", "HEALTH"),
                spent(3, "70", "TRANSPORT"),
                spent(4, "60", "SHOPPING"),
            ),
            caps = mapOf("FOOD" to BigDecimal("100")),
            maxRows = 2,
        )

        assertEquals(
            listOf("FOOD", "HEALTH"),
            rows.map { (it.key as CategoryKey.BuiltIn).category.name },
        )
    }

    @Test
    fun maxRowsZeroProducesEmptyList() {
        val rows = categoryWidgetRows(
            spends = listOf(spent(1, "100", "FOOD")),
            caps = emptyMap(),
            maxRows = 0,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun emptySpendsProducesEmptyList() {
        assertTrue(categoryWidgetRows(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun customCapLookupUsesRawName() {
        val rows = categoryWidgetRows(
            spends = listOf(spent(1, "100", "Groceries")),
            caps = mapOf("Groceries" to BigDecimal("250")),
        )

        val key = rows.single().key as CategoryKey.Custom
        assertEquals("Groceries", key.name)
        assertEquals(BigDecimal("250"), rows.single().cap)
        assertEquals(40, rows.single().percent)
    }

    @Test
    fun resolvesBuiltInPills() {
        val pills = categoryWidgetPills(
            rows = categoryWidgetRows(
                spends = listOf(spent(1, "100", "FOOD"), spent(2, "5", "OTHER")),
                caps = mapOf("FOOD" to BigDecimal("200")),
            ),
            displayName = { key ->
                when ((key as CategoryKey.BuiltIn).category) {
                    SpendCategory.FOOD -> "Food"
                    else -> "Other"
                }
            },
        )

        assertEquals(2, pills.size)
        val food = pills.first()
        assertEquals("Food", food.name)
        assertEquals("🍔", food.emoji)
        assertEquals(BigDecimal("100"), food.used)
        assertEquals(BigDecimal("200"), food.cap)
        assertEquals(SpendCategory.FOOD.ordinal % CATEGORY_WIDGET_PALETTE_SIZE, food.colorIndex)
        assertFalse(food.isSpecial)

        val other = pills.last()
        assertTrue(other.isSpecial)
        assertEquals(SpendCategory.OTHER.emoji, other.emoji)
    }

    @Test
    fun resolvesCustomPillsWithSavedEmojiAndDeterministicColor() {
        val pills = categoryWidgetPills(
            rows = categoryWidgetRows(
                spends = listOf(spent(1, "100", "Groceries")),
                caps = emptyMap(),
            ),
            displayName = { (it as CategoryKey.Custom).name },
            categoryEmojis = mapOf("Groceries" to "🥦"),
        )

        val pill = pills.single()
        assertEquals("Groceries", pill.name)
        assertEquals("🥦", pill.emoji)
        assertEquals(Math.floorMod("Groceries".hashCode(), CATEGORY_WIDGET_PALETTE_SIZE), pill.colorIndex)
        assertFalse(pill.isSpecial)
    }

    @Test
    fun customPillWithoutSavedEmojiFallsBackToDefault() {
        val pills = categoryWidgetPills(
            rows = categoryWidgetRows(
                spends = listOf(spent(1, "100", "Groceries")),
                caps = emptyMap(),
            ),
            displayName = { (it as CategoryKey.Custom).name },
        )

        assertEquals(SpendCategory.DEFAULT_EMOJI, pills.single().emoji)
    }

    @Test
    fun serializeParseRoundTrip() {
        val pills = listOf(
            CategoryWidgetPill("Food", "🍔", BigDecimal("100"), BigDecimal("200"), 6, false),
            CategoryWidgetPill("Groceries", "🥦", BigDecimal("50"), null, 3, false),
            CategoryWidgetPill("Other", "🗂️", BigDecimal("5"), null, 0, true),
        )

        val parsed = parseCategoryWidgetPills(serializeCategoryWidgetPills(pills))

        assertEquals(pills, parsed)
    }

    @Test
    fun parseToleratesBlankAndGarbage() {
        assertTrue(parseCategoryWidgetPills(null).isEmpty())
        assertTrue(parseCategoryWidgetPills("").isEmpty())
        assertTrue(parseCategoryWidgetPills("not json at all").isEmpty())
    }

    @Test
    fun parseToleratesJunkFields() {
        val parsed = parseCategoryWidgetPills("""[{"name":"Food","used":"abc","cap":"0"}]""")

        assertEquals(1, parsed.size)
        assertEquals("Food", parsed.single().name)
        assertEquals(BigDecimal.ZERO, parsed.single().used)
        assertNull(parsed.single().cap)
    }

    @Test
    fun effectiveDesignPrefersPerInstanceOverrideOverGlobal() {
        assertEquals(
            CategoryWidgetDesign.COMPACT.name,
            effectiveCategoryWidgetDesign(
                overrideName = CategoryWidgetDesign.COMPACT.name,
                globalName = CategoryWidgetDesign.BATTERY.name,
            ),
        )
    }

    @Test
    fun effectiveDesignFallsBackToGlobalWhenOverrideBlank() {
        assertEquals(
            CategoryWidgetDesign.BATTERY.name,
            effectiveCategoryWidgetDesign(
                overrideName = null,
                globalName = CategoryWidgetDesign.BATTERY.name,
            ),
        )
        assertEquals(
            CategoryWidgetDesign.COMPACT.name,
            effectiveCategoryWidgetDesign(
                overrideName = "",
                globalName = CategoryWidgetDesign.COMPACT.name,
            ),
        )
    }
}
