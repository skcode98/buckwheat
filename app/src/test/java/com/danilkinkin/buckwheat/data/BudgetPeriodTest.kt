package com.danilkinkin.buckwheat.data

import com.danilkinkin.buckwheat.util.DAY
import com.danilkinkin.buckwheat.util.roundToDay
import com.danilkinkin.buckwheat.util.toDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetPeriodTest {

    private fun day(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).toDate()

    private val today = day(2026, 10, 19)

    @Test
    fun futureStartIsPulledBackToToday() {
        val result = normalizePeriodStart(day(2026, 10, 31), day(2026, 10, 31), today)

        assertEquals(day(2026, 10, 19), result)
    }

    @Test
    fun degenerateSingleDayRangeDoesNotStartOnFinish() {
        val result = normalizePeriodStart(day(2026, 10, 1), day(2026, 10, 1), today)

        assertTrue(result.before(day(2026, 10, 1)))
    }

    @Test
    fun normalPastStartIsPreserved() {
        val result = normalizePeriodStart(day(2026, 10, 1), day(2026, 10, 31), today)

        assertEquals(day(2026, 10, 1), result)
    }

    @Test
    fun startIsNeverAfterToday() {
        val result = normalizePeriodStart(day(2026, 10, 25), day(2026, 10, 31), today)

        assertTrue(!result.after(today))
    }

    @Test
    fun startIsAlwaysBeforeFinishDay() {
        val result = normalizePeriodStart(day(2026, 10, 20), day(2026, 10, 19), today)

        assertTrue(result.before(roundToDay(day(2026, 10, 19))))
    }

    @Test
    fun timeOfDayIsStripped() {
        val startWithTime = java.util.Date(day(2026, 10, 1).time + 13 * 60 * 60 * 1000)
        val result = normalizePeriodStart(startWithTime, day(2026, 10, 31), today)

        assertEquals(day(2026, 10, 1), result)
    }

    @Test
    fun invariantsHoldForEveryStart() {
        val finish = day(2026, 10, 31)
        for (offsetDays in -60..60) {
            val start = java.util.Date(today.time + offsetDays.toLong() * DAY)
            val result = normalizePeriodStart(start, finish, today)

            assertTrue("start must not be after today", !result.after(today))
            assertTrue("start must be before finish", result.before(roundToDay(finish)))
        }
    }
}
