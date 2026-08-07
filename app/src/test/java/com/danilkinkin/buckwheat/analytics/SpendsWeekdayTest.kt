package com.danilkinkin.buckwheat.analytics

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test

class SpendsWeekdayTest {
    // 2026-08-03 is a Monday, 2026-08-09 a Sunday.
    private fun date(day: Int, month: Int = 8, year: Int = 2026): Date =
        LocalDate.of(year, month, day).toDate()

    private fun spent(day: Int, value: String): Transaction =
        Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(value),
            date = date(day),
        )

    private fun mondayIndex() = DayOfWeek.MONDAY.value - 1
    private fun sundayIndex() = DayOfWeek.SUNDAY.value - 1

    private fun zero() = BigDecimal("0.00")

    @Test
    fun weekdayAverageSpendAggregatesByWeekday() {
        val averages = weekdayAverageSpend(
            spends = listOf(
                spent(3, "100"),
                spent(3, "50"),
                spent(5, "75"),
                spent(9, "200"),
            ),
            startDate = date(3),
            finishDate = date(9),
            today = date(9),
        )

        assertEquals(7, averages.size)
        // Mon = 150 / 1
        assertEquals(BigDecimal("150.00"), averages[mondayIndex()])
        // Tue = 0
        assertEquals(zero(), averages[DayOfWeek.TUESDAY.value - 1])
        // Wed = 75 / 1
        assertEquals(BigDecimal("75.00"), averages[DayOfWeek.WEDNESDAY.value - 1])
        // Sun = 200 / 1
        assertEquals(BigDecimal("200.00"), averages[sundayIndex()])
    }

    @Test
    fun weekdayAverageSpendAveragesAcrossMultipleOccurrences() {
        // Monday appears twice (3rd and 10th), totals 100 + 50.
        val averages = weekdayAverageSpend(
            spends = listOf(
                spent(3, "100"),
                spent(10, "50"),
            ),
            startDate = date(3),
            finishDate = date(10),
            today = date(10),
        )

        assertEquals(BigDecimal("75.00"), averages[mondayIndex()])
    }

    @Test
    fun weekdayAverageSpendIgnoresFutureWeekdays() {
        // Today is Thursday the 6th; Friday..Sunday have not occurred yet.
        val averages = weekdayAverageSpend(
            spends = listOf(
                spent(3, "100"),
                spent(9, "200"),
            ),
            startDate = date(3),
            finishDate = date(9),
            today = date(6),
        )

        // Mon spent 100 of 1 occurrence
        assertEquals(BigDecimal("100.00"), averages[mondayIndex()])
        // Sunday spend is outside the elapsed window -> ignored, but also never counted
        assertEquals(zero(), averages[sundayIndex()])
    }

    @Test
    fun weekdayAverageSpendIgnoresSpendsOutsidePeriod() {
        val averages = weekdayAverageSpend(
            spends = listOf(
                spent(2, "999"), // day before period start
                spent(10, "999"), // day after period end
                spent(3, "100"),
            ),
            startDate = date(3),
            finishDate = date(9),
            today = date(9),
        )

        assertEquals(BigDecimal("100.00"), averages[mondayIndex()])
        assertEquals(zero(), averages[DayOfWeek.SATURDAY.value - 1])
    }

    @Test
    fun weekdayAverageSpendWithNoSpendsReturnsZeros() {
        val averages = weekdayAverageSpend(
            spends = emptyList(),
            startDate = date(3),
            finishDate = date(9),
            today = date(9),
        )

        assertEquals(7, averages.size)
        assertEquals(List(7) { zero() }, averages)
    }

    @Test
    fun weekdayAverageSpendWithReversedPeriodReturnsZeros() {
        val averages = weekdayAverageSpend(
            spends = listOf(spent(3, "100")),
            startDate = date(9),
            finishDate = date(3),
            today = date(9),
        )

        assertEquals(List(7) { zero() }, averages)
    }

    @Test
    fun weekdayAverageSpendUsesTodayAsElapsedCap() {
        // Elapsed window Monday 3rd..Saturday 8th; Monday twice (3rd, 10th) but
        // only one occurrence inside the window.
        val averages = weekdayAverageSpend(
            spends = listOf(
                spent(3, "100"),
                spent(10, "50"),
            ),
            startDate = date(3),
            finishDate = date(10),
            today = date(8),
        )

        assertEquals(BigDecimal("100.00"), averages[mondayIndex()])
    }
}
