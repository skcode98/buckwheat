package com.danilkinkin.buckwheat.analytics

import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SpendsTrendTest {
    private fun daysAgo(days: Long): Date = LocalDate.now().minusDays(days).toDate()

    private fun spent(daysAgo: Long, value: String): Transaction =
        Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(value),
            date = daysAgo(daysAgo),
        )

    private fun period(finishDaysAgo: Long, totalSpent: String, imported: Boolean = false): BudgetPeriod =
        BudgetPeriod(
            budget = BigDecimal("1000"),
            startDate = daysAgo(finishDaysAgo + 10),
            finishDate = daysAgo(finishDaysAgo),
            actualFinishDate = null,
            currencyCode = "USD",
            totalSpent = BigDecimal(totalSpent),
            isImported = imported,
        )

    @Test
    fun dailySpendTotalsAggregatesByDayOfPeriod() {
        val start = daysAgo(5)
        val finish = daysAgo(1)

        val totals = dailySpendTotals(
            spends = listOf(
                spent(5, "100"),
                spent(5, "50"),
                spent(3, "75"),
            ),
            startDate = start,
            finishDate = finish,
        )

        assertEquals(listOf(BigDecimal("150"), BigDecimal.ZERO, BigDecimal("75"), BigDecimal.ZERO, BigDecimal.ZERO), totals)
    }

    @Test
    fun dailySpendTotalsIgnoresSpendsOutsidePeriod() {
        val start = daysAgo(5)
        val finish = daysAgo(1)

        val totals = dailySpendTotals(
            spends = listOf(
                spent(6, "10"),
                spent(0, "20"),
                spent(3, "30"),
            ),
            startDate = start,
            finishDate = finish,
        )

        assertEquals(listOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("30"), BigDecimal.ZERO, BigDecimal.ZERO), totals)
    }

    @Test
    fun dailySpendTotalsWithNoSpendsReturnsAllZeros() {
        val totals = dailySpendTotals(
            spends = emptyList(),
            startDate = daysAgo(5),
            finishDate = daysAgo(1),
        )

        assertEquals(5, totals.size)
        assertEquals(listOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), totals)
    }

    @Test
    fun dailySpendTotalsWithReversedPeriodReturnsEmpty() {
        val totals = dailySpendTotals(
            spends = listOf(spent(3, "30")),
            startDate = daysAgo(5),
            finishDate = daysAgo(10),
        )

        assertEquals(emptyList<BigDecimal>(), totals)
    }

    @Test
    fun monthDayCountsSingleMonth() {
        val result = monthDayCounts(
            LocalDate.of(2026, 8, 1).toDate(),
            LocalDate.of(2026, 8, 31).toDate(),
        )

        assertEquals(listOf(YearMonth.of(2026, 8) to 31), result)
    }

    @Test
    fun monthDayCountsPartialMonthsCountOnlyInPeriodDays() {
        val result = monthDayCounts(
            LocalDate.of(2026, 1, 20).toDate(),
            LocalDate.of(2026, 2, 10).toDate(),
        )

        assertEquals(listOf(YearMonth.of(2026, 1) to 12, YearMonth.of(2026, 2) to 10), result)
    }

    @Test
    fun monthDayCountsAcrossYearBoundary() {
        val result = monthDayCounts(
            LocalDate.of(2026, 12, 20).toDate(),
            LocalDate.of(2027, 1, 10).toDate(),
        )

        assertEquals(listOf(YearMonth.of(2026, 12) to 12, YearMonth.of(2027, 1) to 10), result)
    }

    @Test
    fun monthDayCountsReversedPeriodReturnsEmpty() {
        val result = monthDayCounts(
            LocalDate.of(2026, 8, 10).toDate(),
            LocalDate.of(2026, 8, 1).toDate(),
        )

        assertEquals(emptyList<Pair<YearMonth, Int>>(), result)
    }

    @Test
    fun previousPeriodPicksLatestNonImportedFinishedBeforeCurrentStart() {
        val start = daysAgo(5)

        val latest = period(6, "200")
        val older = period(9, "100")
        val imported = period(2, "900", imported = true)
        val future = period(1, "50")

        val result = previousPeriodBefore(listOf(imported, older, future, latest), start)

        assertSame(latest, result)
    }

    @Test
    fun previousPeriodReturnsNullWhenNothingQualifies() {
        val start = daysAgo(5)

        assertNull(previousPeriodBefore(emptyList(), start))
        assertNull(previousPeriodBefore(listOf(period(2, "900", imported = true)), start))
        assertNull(previousPeriodBefore(listOf(period(1, "50")), start))
    }
}
