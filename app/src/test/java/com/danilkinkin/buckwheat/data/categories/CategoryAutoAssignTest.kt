package com.danilkinkin.buckwheat.data.categories

import java.math.BigDecimal
import org.junit.Assert.assertEquals
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
        // No spend history -> category still receives the mean requirement share so the
        // Auto-assign button never leaves a category empty.
        assertTrue(caps.keys.contains("SHOPPING"))
        // FOOD 6000 of 10666.67 requirement -> 8437.50; SHOPPING gets the mean (2666.67) share.
        assertEquals(BigDecimal("8437.50"), caps[SpendCategory.FOOD.name])
        assertEquals(BigDecimal("3750.00"), caps["SHOPPING"])
    }

    @Test
    fun autoAssignGivesNoHistoryCategoriesTheMeanShare() {
        val caps = autoAssignCategoryCaps(
            budget = BigDecimal("15000"),
            categories = listOf("FOOD", "TRANSPORT", "SHOPPING"),
            periods = listOf(
                listOf(builtIn(SpendCategory.FOOD, "6000"), builtIn(SpendCategory.TRANSPORT, "2000")),
            ),
        )

        assertEquals(BigDecimal("15000.00"), sum(caps))
        assertEquals(BigDecimal("7500.00"), caps[SpendCategory.FOOD.name])
        // SHOPPING has no history -> gets the mean of the existing averages (4000 of 12000);
        // 1/3 at scale 8 rounds to 0.33333333 so the floored share is 4999.99 and the last
        // category absorbs the 0.01 remainder.
        assertEquals(BigDecimal("2500.01"), caps[SpendCategory.TRANSPORT.name])
        assertEquals(BigDecimal("4999.99"), caps["SHOPPING"])
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

    @Test
    fun requirementPeriodsPrefersArchivedOverPartialCurrent() {
        val current = listOf(builtIn(SpendCategory.FOOD, "800"))
        val archived = listOf(
            listOf(builtIn(SpendCategory.FOOD, "6000")),
            listOf(builtIn(SpendCategory.FOOD, "6000")),
        )

        assertEquals(archived, requirementPeriods(current, archived))
    }

    @Test
    fun requirementPeriodsFallsBackToCurrentWhenNoArchived() {
        val current = listOf(builtIn(SpendCategory.FOOD, "800"))

        assertEquals(listOf(current), requirementPeriods(current, emptyList()))
        assertTrue(requirementPeriods(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun requirementPeriodsNeverDilutesWithPartialMonth() {
        // Partial current month (10/30 days) must not halve FOOD's requirement when a full
        // archived month exists: 6000, not (6000 + 2000)/2.
        val caps = autoAssignCategoryCaps(
            budget = BigDecimal("15000"),
            categories = listOf("FOOD", "TRANSPORT"),
            periods = requirementPeriods(
                currentPeriod = listOf(builtIn(SpendCategory.FOOD, "2000")),
                archivedPeriods = listOf(
                    listOf(builtIn(SpendCategory.FOOD, "6000"), builtIn(SpendCategory.TRANSPORT, "3000")),
                ),
            ),
        )

        assertEquals(BigDecimal("15000.00"), sum(caps))
        assertEquals(BigDecimal("10000.00"), caps[SpendCategory.FOOD.name])
        assertEquals(BigDecimal("5000.00"), caps[SpendCategory.TRANSPORT.name])
    }
}
