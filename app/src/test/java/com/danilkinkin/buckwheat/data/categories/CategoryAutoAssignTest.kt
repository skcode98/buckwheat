package com.danilkinkin.buckwheat.data.categories

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryAutoAssignTest {
    private fun builtIn(category: SpendCategory, value: String): Pair<CategoryKey, BigDecimal> =
        CategoryKey.BuiltIn(category) to BigDecimal(value)

    private fun sum(map: Map<String, BigDecimal>): BigDecimal =
        map.values.fold(BigDecimal.ZERO) { acc, value -> acc + value }

    @Test
    fun averageCategorySpendAveragesAcrossPeriods() {
        val averages = averageCategorySpend(
            listOf(
                listOf(builtIn(SpendCategory.FOOD, "3000"), builtIn(SpendCategory.TRANSPORT, "1000")),
                listOf(builtIn(SpendCategory.FOOD, "5000")),
                listOf(builtIn(SpendCategory.FOOD, "4000"), builtIn(SpendCategory.HEALTH, "2000")),
            )
        )

        assertEquals(BigDecimal("4000.00"), averages[SpendCategory.FOOD.name])
        assertEquals(BigDecimal("333.33"), averages[SpendCategory.TRANSPORT.name])
        assertEquals(BigDecimal("666.67"), averages[SpendCategory.HEALTH.name])
    }

    @Test
    fun averageCategorySpendIgnoresQuietPeriods() {
        // Quiet periods with no spend must not dilute the requirement.
        val averages = averageCategorySpend(
            listOf(
                emptyList(),
                listOf(builtIn(SpendCategory.FOOD, "2000")),
                emptyList(),
            )
        )

        assertEquals(BigDecimal("2000.00"), averages[SpendCategory.FOOD.name])
        assertEquals(1, averages.size)
    }

    @Test
    fun averageCategorySpendIsEmptyWithoutPeriods() {
        assertEquals(emptyMap<String, BigDecimal>(), averageCategorySpend(emptyList()))
        assertEquals(emptyMap<String, BigDecimal>(), averageCategorySpend(listOf(emptyList())))
    }

    @Test
    fun averageCategorySpendHandlesCustomCategories() {
        val averages = averageCategorySpend(
            listOf(listOf(CategoryKey.Custom("Coffee") to BigDecimal("500")))
        )

        assertEquals(BigDecimal("500.00"), averages["Coffee"])
    }

    @Test
    fun evenlySplitBudgetSumsExactlyToBudget() {
        val caps = evenlySplitBudget(
            BigDecimal("15000"),
            listOf("FOOD", "TRANSPORT", "HEALTH", "OTHER"),
        )

        assertEquals(BigDecimal("15000.00"), sum(caps))
        // Sorted by name: FOOD, HEALTH, OTHER, TRANSPORT.
        assertEquals(listOf("FOOD", "HEALTH", "OTHER", "TRANSPORT"), caps.keys.toList())
    }

    @Test
    fun evenlySplitBudgetAbsorbsRoundingInLast() {
        val caps = evenlySplitBudget(BigDecimal("10000"), listOf("A", "B", "C"))

        assertEquals(BigDecimal("10000.00"), sum(caps))
        assertEquals(BigDecimal("3333.33"), caps["A"])
        assertEquals(BigDecimal("3333.33"), caps["B"])
        assertEquals(BigDecimal("3333.34"), caps["C"])
    }

    @Test
    fun evenlySplitBudgetIsEmptyWithoutBudgetOrCategories() {
        assertEquals(emptyMap<String, BigDecimal>(), evenlySplitBudget(BigDecimal.ZERO, listOf("A")))
        assertEquals(emptyMap<String, BigDecimal>(), evenlySplitBudget(BigDecimal("100"), emptyList()))
    }

    @Test
    fun allocateBudgetByRequirementSplitsProportionally() {
        val averages = mapOf(
            SpendCategory.FOOD.name to BigDecimal("6000"),
            SpendCategory.TRANSPORT.name to BigDecimal("2000"),
            SpendCategory.HEALTH.name to BigDecimal("2000"),
        )
        val caps = allocateBudgetByRequirement(BigDecimal("15000"), averages)

        assertEquals(BigDecimal("15000.00"), sum(caps))
        assertEquals(BigDecimal("9000.00"), caps[SpendCategory.FOOD.name])
        assertEquals(BigDecimal("3000.00"), caps[SpendCategory.TRANSPORT.name])
        assertEquals(BigDecimal("3000.00"), caps[SpendCategory.HEALTH.name])
    }

    @Test
    fun allocateBudgetByRequirementAbsorbsRoundingInLast() {
        val averages = mapOf(
            "A" to BigDecimal("333.33"),
            "B" to BigDecimal("333.33"),
            "C" to BigDecimal("333.34"),
        )
        val caps = allocateBudgetByRequirement(BigDecimal("1000"), averages)

        assertEquals(BigDecimal("1000.00"), sum(caps))
    }

    @Test
    fun allocateBudgetByRequirementIsEmptyWithoutInput() {
        assertEquals(
            emptyMap<String, BigDecimal>(),
            allocateBudgetByRequirement(BigDecimal.ZERO, mapOf("A" to BigDecimal("5"))),
        )
        assertEquals(
            emptyMap<String, BigDecimal>(),
            allocateBudgetByRequirement(BigDecimal("100"), emptyMap()),
        )
        assertEquals(
            emptyMap<String, BigDecimal>(),
            allocateBudgetByRequirement(BigDecimal("100"), mapOf("A" to BigDecimal.ZERO)),
        )
    }

    @Test
    fun autoAssignUsesHistoryWhenPresent() {
        val caps = autoAssignCategoryCaps(
            budget = BigDecimal("15000"),
            categories = listOf("FOOD", "TRANSPORT", "HEALTH", "SHOPPING"),
            periods = listOf(
                listOf(builtIn(SpendCategory.FOOD, "6000"), builtIn(SpendCategory.TRANSPORT, "2000")),
                listOf(builtIn(SpendCategory.FOOD, "6000"), builtIn(SpendCategory.HEALTH, "2000")),
            ),
        )

        assertEquals(BigDecimal("15000.00"), sum(caps))
        assertTrue(caps.keys.contains(SpendCategory.FOOD.name))
        assertTrue(caps.keys.contains(SpendCategory.HEALTH.name))
        // No spend history -> no cap from the proportional split.
        assertFalse(caps.keys.contains("SHOPPING"))
    }

    @Test
    fun autoAssignSplitsEvenlyWithoutHistory() {
        val caps = autoAssignCategoryCaps(
            budget = BigDecimal("15000"),
            categories = listOf("FOOD", "TRANSPORT", "HEALTH"),
            periods = emptyList(),
        )

        assertEquals(BigDecimal("15000.00"), sum(caps))
        assertEquals(3, caps.size)
    }

    @Test
    fun autoAssignIsEmptyWithoutBudgetOrCategories() {
        assertEquals(
            emptyMap<String, BigDecimal>(),
            autoAssignCategoryCaps(BigDecimal.ZERO, listOf("FOOD"), emptyList()),
        )
        assertEquals(
            emptyMap<String, BigDecimal>(),
            autoAssignCategoryCaps(BigDecimal("100"), emptyList(), emptyList()),
        )
    }
}
