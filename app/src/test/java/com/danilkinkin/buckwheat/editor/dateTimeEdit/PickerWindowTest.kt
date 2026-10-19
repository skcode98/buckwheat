package com.danilkinkin.buckwheat.editor.dateTimeEdit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PickerWindowTest {

    private val today = LocalDate.of(2026, 10, 19)

    @Test
    fun sanePeriodKeepsPeriodStartAsLowerBound() {
        val window = pickerWindow(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), today)

        assertEquals(LocalDate.of(2026, 10, 1), window.disableBeforeDate)
        assertEquals(today, window.disableAfterDate)
        assertEquals(LocalDate.of(2026, 10, 1), window.showBeforeDate)
        assertEquals(LocalDate.of(2026, 10, 31), window.showAfterDate)
    }

    @Test
    fun futurePeriodStartIsIgnoredSoWindowIsNotEmpty() {
        val window = pickerWindow(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31), today)

        assertEquals(today, window.disableBeforeDate)
        assertEquals(today, window.disableAfterDate)
        assertFalse(window.disableBeforeDate!!.isAfter(window.disableAfterDate))
    }

    @Test
    fun nullPeriodStartDefaultsToToday() {
        val window = pickerWindow(null, LocalDate.of(2026, 10, 31), today)

        assertEquals(today, window.disableBeforeDate)
        assertEquals(today, window.disableAfterDate)
    }

    @Test
    fun nullFinishKeepsWindowAtToday() {
        val window = pickerWindow(LocalDate.of(2026, 10, 1), null, today)

        assertEquals(LocalDate.of(2026, 10, 1), window.showBeforeDate)
        assertEquals(today, window.showAfterDate)
    }

    @Test
    fun pastFinishDoesNotShrinkWindowBelowToday() {
        val window = pickerWindow(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), today)

        assertEquals(today, window.showAfterDate)
    }

    @Test
    fun windowIsNeverEmpty() {
        val starts = listOf(null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 12, 31))

        for (start in starts) {
            val window = pickerWindow(start, LocalDate.of(2026, 10, 31), today)

            assertFalse(
                "window must contain at least one selectable day",
                window.disableBeforeDate!!.isAfter(window.disableAfterDate),
            )
            assertTrue(window.showBeforeDate <= window.showAfterDate)
        }
    }
}
