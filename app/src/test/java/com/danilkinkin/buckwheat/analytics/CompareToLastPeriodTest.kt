package com.danilkinkin.buckwheat.analytics

import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CompareToLastPeriodTest {
    private fun daysAgo(days: Long): Date = LocalDate.now().minusDays(days).toDate()

    private fun period(
        finishDaysAgo: Long,
        id: Int = finishDaysAgo.toInt(),
    ): BudgetPeriod =
        BudgetPeriod(
            budget = BigDecimal("1000"),
            startDate = daysAgo(finishDaysAgo + 10),
            finishDate = daysAgo(finishDaysAgo),
            actualFinishDate = null,
            currencyCode = "USD",
            totalSpent = BigDecimal.ZERO,
        ).also { it.id = id }

    private fun archived(
        periodId: Int,
        daysAgo: Long,
        value: String,
        type: TransactionType = TransactionType.SPENT,
    ): ArchivedTransaction =
        ArchivedTransaction(
            periodId = periodId,
            type = type,
            value = BigDecimal(value),
            date = daysAgo(daysAgo),
            comment = "",
        )

    @Test
    fun findPreviousPeriodPicksLatestFinishedBeforeCurrentStart() {
        val currentStart = daysAgo(5)

        val latest = period(6, id = 2)
        val older = period(9, id = 1)

        assertSame(latest, findPreviousPeriod(listOf(older, latest), currentStart))
    }

    @Test
    fun findPreviousPeriodSkipsImportedPeriods() {
        val currentStart = daysAgo(5)

        val imported = period(6, id = 2).copy(isImported = true)
        val real = period(9, id = 1)

        assertSame(real, findPreviousPeriod(listOf(imported, real), currentStart))
    }

    @Test
    fun findPreviousPeriodReturnsNullWhenOnlyImportedPeriodsPrecede() {
        val currentStart = daysAgo(5)

        val imported = period(6, id = 2).copy(isImported = true)

        assertNull(findPreviousPeriod(listOf(imported), currentStart))
    }

    @Test
    fun findPreviousPeriodIgnoresCurrentAndFuturePeriods() {
        val currentStart = daysAgo(5)

        val current = period(4, id = 3)
        val future = period(2, id = 4)

        val result = findPreviousPeriod(listOf(current, future), currentStart)

        assertNull(result)
    }

    @Test
    fun findPreviousPeriodReturnsNullWhenNoPreviousExists() {
        assertNull(findPreviousPeriod(emptyList(), daysAgo(5)))
    }

    @Test
    fun findPreviousPeriodIncludesPeriodEndingSameDayAsCurrentStart() {
        val currentStart = daysAgo(5)

        val contiguous = period(5, id = 2)

        assertSame(contiguous, findPreviousPeriod(listOf(contiguous), currentStart))
    }

    @Test
    fun findPreviousPeriodUsesActualFinishDateForEarlyFinishedPeriod() {
        val currentStart = daysAgo(5)

        val earlyFinished = period(30, id = 1).copy(
            actualFinishDate = daysAgo(6),
        )

        assertSame(earlyFinished, findPreviousPeriod(listOf(earlyFinished), currentStart))
    }

    @Test
    fun findPreviousPeriodIgnoresEarlyFinishedAfterCurrentStart() {
        val currentStart = daysAgo(5)

        val endedLater = period(30, id = 1).copy(
            actualFinishDate = daysAgo(4),
        )

        assertNull(findPreviousPeriod(listOf(endedLater), currentStart))
    }

    @Test
    fun findPreviousPeriodIgnoresResetPeriodWhoseScheduledFinishIsLater() {
        val currentStart = daysAgo(5)

        val reset = period(1, id = 1)

        assertNull(findPreviousPeriod(listOf(reset), currentStart))
    }

    @Test
    fun findPreviousPeriodFindsManuallyFinishedPeriodBeforeEarlyRestart() {
        // Simulates the bug where a new period starts before the old
        // period's scheduled finish date. After our fix, archiveCurrentPeriod()
        // caps the archived finishDate at the new start date.
        val currentStart = daysAgo(5)

        val restartedPeriod = period(30, id = 1).copy(
            actualFinishDate = daysAgo(6), // manually finished early
        )

        assertSame(restartedPeriod, findPreviousPeriod(listOf(restartedPeriod), currentStart))
    }

    @Test
    fun effectiveFinishDateFallsBackToScheduledFinish() {
        val scheduled = daysAgo(6)
        val period = period(6, id = 1)

        assertEquals(scheduled, effectiveFinishDate(period))

        val early = period.copy(actualFinishDate = daysAgo(4))
        assertEquals(daysAgo(4), effectiveFinishDate(early))
    }

    @Test
    fun previousSpentAtSameElapsedDaysSumsOnlySpentOfGivenPeriod() {
        val previousStart = daysAgo(15)

        val result = previousSpentAtSameElapsedDays(
            archivedTransactions = listOf(
                archived(periodId = 1, daysAgo = 14, value = "100"),
                archived(periodId = 1, daysAgo = 12, value = "50"),
                archived(periodId = 1, daysAgo = 5, value = "999"),
                archived(periodId = 2, daysAgo = 13, value = "700"),
                archived(periodId = 1, daysAgo = 13, value = "200", type = TransactionType.INCOME),
            ),
            periodId = 1,
            previousStart = previousStart,
            elapsedDays = 3,
        )

        // Elapsed 3 days -> cutoff = start + 2 days, so only day-14 and day-13 (within cutoff)
        // but day-13 is INCOME, day-14 is 100, day-12 is outside cutoff.
        assertEquals(BigDecimal("100"), result)
    }

    @Test
    fun previousSpentAtSameElapsedDaysWithElapsedZeroUsesAtLeastOneDay() {
        val previousStart = daysAgo(10)

        val result = previousSpentAtSameElapsedDays(
            archivedTransactions = listOf(
                archived(periodId = 1, daysAgo = 10, value = "25"),
                archived(periodId = 1, daysAgo = 8, value = "75"),
            ),
            periodId = 1,
            previousStart = previousStart,
            elapsedDays = 0,
        )

        assertEquals(BigDecimal("25"), result)
    }

    @Test
    fun previousSpentAtSameElapsedDaysNoMatchingReturnsZero() {
        val result = previousSpentAtSameElapsedDays(
            archivedTransactions = emptyList(),
            periodId = 1,
            previousStart = daysAgo(10),
            elapsedDays = 3,
        )

        assertEquals(BigDecimal.ZERO, result)
    }

    @Test
    fun formatPercentRoundsToOneDecimalWithSign() {
        assertEquals("+12.3%", formatPercent(BigDecimal("12.34")))
        assertEquals("-5%", formatPercent(BigDecimal("-5")))
        assertEquals("+0%", formatPercent(BigDecimal("0.04")))
    }

    @Test
    fun periodComparisonDeltaAndPercent() {
        val comparison = PeriodComparison(
            currentSpent = BigDecimal("150"),
            previousSpent = BigDecimal("100"),
        )

        assertEquals(BigDecimal("50"), comparison.delta)
        assertEquals(BigDecimal("50"), comparison.percentChange)
    }

    @Test
    fun periodComparisonPercentNullWhenPreviousZero() {
        val comparison = PeriodComparison(
            currentSpent = BigDecimal("50"),
            previousSpent = BigDecimal.ZERO,
        )

        assertEquals(BigDecimal("50"), comparison.delta)
        assertNull(comparison.percentChange)
    }
}
