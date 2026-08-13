package com.danilkinkin.buckwheat.analytics

import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiPeriodTrendTest {
    private fun date(year: Int, month: Int, day: Int): Date =
        LocalDate.of(year, month, day).toDate()

    private fun period(
        year: Int,
        month: Int,
        budget: String,
        spent: String,
        isImported: Boolean = false,
    ) = BudgetPeriod(
        budget = BigDecimal(budget),
        startDate = date(year, month, 1),
        finishDate = date(year, month, 28),
        actualFinishDate = null,
        currencyCode = "USD",
        totalSpent = BigDecimal(spent),
        isImported = isImported,
    )

    private fun point(
        label: String,
        spent: String,
        budget: String,
        isCurrent: Boolean = false,
    ) = MultiPeriodPoint(
        label = label,
        spent = BigDecimal(spent),
        budget = BigDecimal(budget),
        isCurrent = isCurrent,
    )

    @Test
    fun combinesArchivedAndCurrentInChronologicalOrder() {
        val points = multiPeriodTotals(
            periods = listOf(
                period(2026, 6, "1000", "1100"),
                period(2026, 7, "1000", "800"),
            ),
            currentBudget = BigDecimal("1000"),
            currentSpent = BigDecimal("500"),
            currentStart = date(2026, 8, 1),
        )

        assertEquals(
            listOf(
                point("Jun", "1100", "1000"),
                point("Jul", "800", "1000"),
                point("Aug", "500", "1000", isCurrent = true),
            ),
            points,
        )
    }

    @Test
    fun sortsArchivedPeriodsOldestFirst() {
        val points = multiPeriodTotals(
            periods = listOf(
                period(2026, 7, "1000", "800"),
                period(2026, 6, "1000", "1100"),
                period(2026, 5, "1000", "900"),
            ),
            currentBudget = BigDecimal("1000"),
            currentSpent = BigDecimal("500"),
            currentStart = date(2026, 8, 1),
        )

        assertEquals(
            listOf("May", "Jun", "Jul", "Aug"),
            points.map { it.label },
        )
        assertEquals(listOf(false, false, false, true), points.map { it.isCurrent })
    }

    @Test
    fun skipsArchivedPeriodsWithoutData() {
        val points = multiPeriodTotals(
            periods = listOf(
                period(2026, 5, "0", "0"),
                period(2026, 6, "1000", "1100"),
                period(2026, 7, "0", "0"),
            ),
            currentBudget = BigDecimal("1000"),
            currentSpent = BigDecimal("500"),
            currentStart = date(2026, 8, 1),
        )

        assertEquals(
            listOf(point("Jun", "1100", "1000"), point("Aug", "500", "1000", isCurrent = true)),
            points,
        )
    }

    @Test
    fun keepsArchivedPeriodWithOnlyBudget() {
        val points = multiPeriodTotals(
            periods = listOf(period(2026, 6, "1000", "0")),
            currentBudget = BigDecimal("1000"),
            currentSpent = BigDecimal("500"),
            currentStart = date(2026, 8, 1),
        )

        assertEquals(
            listOf(point("Jun", "0", "1000"), point("Aug", "500", "1000", isCurrent = true)),
            points,
        )
    }

    @Test
    fun omitsCurrentPeriodWhenItHasNoData() {
        val points = multiPeriodTotals(
            periods = listOf(period(2026, 6, "1000", "1100")),
            currentBudget = BigDecimal.ZERO,
            currentSpent = BigDecimal.ZERO,
            currentStart = date(2026, 8, 1),
        )

        assertEquals(listOf(point("Jun", "1100", "1000")), points)
    }

    @Test
    fun labelsIncludeYearAcrossMultipleCalendarYears() {
        val points = multiPeriodTotals(
            periods = listOf(period(2025, 12, "1000", "900")),
            currentBudget = BigDecimal("1000"),
            currentSpent = BigDecimal("500"),
            currentStart = date(2026, 1, 1),
        )

        assertEquals(listOf("Dec '25", "Jan '26"), points.map { it.label })
    }

    @Test
    fun labelsStayMonthOnlyWithinOneYear() {
        val points = multiPeriodTotals(
            periods = listOf(
                period(2026, 1, "1000", "900"),
                period(2026, 2, "1000", "800"),
            ),
            currentBudget = BigDecimal("1000"),
            currentSpent = BigDecimal("500"),
            currentStart = date(2026, 3, 1),
        )

        assertEquals(listOf("Jan", "Feb", "Mar"), points.map { it.label })
    }

    @Test
    fun returnsEmptyListWhenNothingToShow() {
        assertEquals(
            emptyList<MultiPeriodPoint>(),
            multiPeriodTotals(
                periods = emptyList(),
                currentBudget = BigDecimal.ZERO,
                currentSpent = BigDecimal.ZERO,
                currentStart = date(2026, 8, 1),
            ),
        )
    }

    @Test
    fun includesImportedPeriodBuckets() {
        val points = multiPeriodTotals(
            periods = listOf(period(2026, 6, "0", "700", isImported = true)),
            currentBudget = BigDecimal("1000"),
            currentSpent = BigDecimal("500"),
            currentStart = date(2026, 8, 1),
        )

        assertEquals(
            listOf(point("Jun", "700", "0"), point("Aug", "500", "1000", isCurrent = true)),
            points,
        )
    }
}
