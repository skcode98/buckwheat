package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeriodSummaryTest {
    private fun date(year: Int, month: Int, day: Int): Date =
        LocalDate.of(year, month, day).toDate()

    private fun spend(
        value: String,
        year: Int,
        month: Int,
        day: Int,
        comment: String = "",
        category: String? = null,
    ) = Transaction(
        type = TransactionType.SPENT,
        value = BigDecimal(value),
        date = date(year, month, day),
        comment = comment,
        category = category,
    )

    private fun summary(spends: List<Transaction>, budget: String = "1000") = buildPeriodSummary(
        startDate = date(2026, 1, 1),
        finishDate = date(2026, 1, 10),
        actualFinishDate = null,
        budget = BigDecimal(budget),
        spends = spends,
    )

    @Test
    fun usesActualFinishDateWhenPresent() {
        val result = buildPeriodSummary(
            startDate = date(2026, 1, 1),
            finishDate = date(2026, 1, 31),
            actualFinishDate = date(2026, 1, 15),
            budget = BigDecimal("1000"),
            spends = emptyList(),
        )

        assertEquals(date(2026, 1, 15), result.endDate)
        assertEquals(15, result.noSpendDays)
    }

    @Test
    fun totalsSpendsAndComputesUtilization() {
        val result = summary(
            listOf(spend("200", 2026, 1, 2), spend("300", 2026, 1, 5)),
            budget = "1000",
        )

        assertEquals(BigDecimal("500"), result.totalSpent)
        assertEquals(50, result.spentPercent)
        assertEquals(2, result.spendsCount)
    }

    @Test
    fun utilizationIsNullWithoutBudget() {
        val result = summary(listOf(spend("200", 2026, 1, 2)), budget = "0")

        assertNull(result.spentPercent)
        assertEquals(BigDecimal("200"), result.totalSpent)
    }

    @Test
    fun utilizationCanExceedHundred() {
        val result = summary(listOf(spend("1500", 2026, 1, 2)), budget = "1000")

        assertEquals(150, result.spentPercent)
    }

    @Test
    fun findsBiggestAndLowestSpends() {
        val result = summary(
            listOf(
                spend("42", 2026, 1, 2, comment = "coffee"),
                spend("520", 2026, 1, 4, comment = "rent"),
                spend("80", 2026, 1, 6),
            ),
        )

        assertEquals(BigDecimal("520"), result.biggestSpend?.amount)
        assertEquals("rent", result.biggestSpend?.comment)
        assertEquals(BigDecimal("42"), result.lowestSpend?.amount)
        assertEquals("coffee", result.lowestSpend?.comment)
    }

    @Test
    fun biggestDayAggregatesPerDay() {
        val result = summary(
            listOf(
                spend("100", 2026, 1, 3),
                spend("200", 2026, 1, 3),
                spend("250", 2026, 1, 5),
            ),
        )

        assertEquals(LocalDate.of(2026, 1, 3), result.biggestDay?.date)
        assertEquals(BigDecimal("300"), result.biggestDay?.total)
    }

    @Test
    fun countsNoSpendDaysInsideRange() {
        val result = summary(
            listOf(
                spend("10", 2026, 1, 1),
                spend("10", 2026, 1, 1),
                spend("10", 2026, 1, 3),
            ),
        )

        // 10 days in range, spend on 2 distinct days.
        assertEquals(8, result.noSpendDays)
    }

    @Test
    fun noSpendDaysIsZeroWhenEveryDayHasSpends() {
        val spends = (1..10).map { spend("5", 2026, 1, it) }

        assertEquals(0, summary(spends).noSpendDays)
    }

    @Test
    fun noSpendDaysEqualsRangeWhenNothingSpent() {
        assertEquals(10, summary(emptyList()).noSpendDays)
    }

    @Test
    fun emptySpendsYieldNullExtremes() {
        val result = summary(emptyList())

        assertNull(result.biggestSpend)
        assertNull(result.lowestSpend)
        assertNull(result.biggestDay)
    }

    @Test
    fun aggregatesCategories() {
        val result = summary(
            listOf(
                spend("100", 2026, 1, 2, comment = "lunch", category = "FOOD"),
                spend("50", 2026, 1, 3, comment = "coffee", category = "FOOD"),
                spend("200", 2026, 1, 4, comment = "taxi", category = "TRANSPORT"),
            ),
        )

        assertEquals(
            listOf(
                CategoryKey.BuiltIn(SpendCategory.FOOD),
                CategoryKey.BuiltIn(SpendCategory.TRANSPORT),
            ),
            result.categories.map { it.key },
        )
        assertEquals(
            BigDecimal("150"),
            result.categories.first { it.key == CategoryKey.BuiltIn(SpendCategory.FOOD) }.total,
        )
    }

    @Test
    fun categoriesEmptyWithoutSpends() {
        assertEquals(emptyList<PeriodSummaryCategory>(), summary(emptyList()).categories)
    }
}
