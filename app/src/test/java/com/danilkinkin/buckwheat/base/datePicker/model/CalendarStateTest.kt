package com.danilkinkin.buckwheat.base.datePicker.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.util.toDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun rangeState(
        disableBefore: Date? = null,
        disableAfter: Date? = null,
    ): CalendarState = CalendarState(
        context,
        selectionMode = CalendarSelectionMode.RANGE,
        disableBeforeDate = disableBefore,
        disableAfterDate = disableAfter,
    )

    @Test
    fun rangeTwoTapsPickStartThenFinish() {
        val state = rangeState()
        val start = LocalDate.now().plusDays(2)

        state.setSelectedDay(start)

        assertEquals(start, state.calendarUiState.value.selectedStartDate)
        assertEquals(null, state.calendarUiState.value.selectedEndDate)
        assertFalse(state.calendarUiState.value.hasSelectedDates)

        val finish = start.plusDays(5)
        state.setSelectedDay(finish)

        assertEquals(start, state.calendarUiState.value.selectedStartDate)
        assertEquals(finish, state.calendarUiState.value.selectedEndDate)
        assertTrue(state.calendarUiState.value.hasSelectedDates)
    }

    @Test
    fun rangeNormalizesReversedSecondTap() {
        val state = rangeState()
        val firstTap = LocalDate.now().plusDays(10)
        val secondTap = firstTap.minusDays(3)

        state.setSelectedDay(firstTap)
        state.setSelectedDay(secondTap)

        assertEquals(secondTap, state.calendarUiState.value.selectedStartDate)
        assertEquals(firstTap, state.calendarUiState.value.selectedEndDate)
        assertTrue(state.calendarUiState.value.hasSelectedDates)
    }

    @Test
    fun tappingPreloadedRangeRestartsThenCompletesOnSecondTap() {
        val state = rangeState()
        val preloadedStart = LocalDate.now().minusDays(10)
        val preloadedFinish = LocalDate.now().minusDays(3)
        state.setSelectedRange(preloadedStart, preloadedFinish)

        assertTrue(state.calendarUiState.value.hasSelectedDates)

        val newStart = LocalDate.now().plusDays(1)
        state.setSelectedDay(newStart)

        assertEquals(newStart, state.calendarUiState.value.selectedStartDate)
        assertEquals(null, state.calendarUiState.value.selectedEndDate)
        assertFalse(state.calendarUiState.value.hasSelectedDates)

        val newFinish = newStart.plusDays(7)
        state.setSelectedDay(newFinish)

        assertEquals(newStart, state.calendarUiState.value.selectedStartDate)
        assertEquals(newFinish, state.calendarUiState.value.selectedEndDate)
        assertTrue(state.calendarUiState.value.hasSelectedDates)
    }

    @Test
    fun pastStartIsPickableInsideDefaultWindow() {
        val now = LocalDate.now()
        val state = rangeState(
            disableBefore = now.minusMonths(2).withDayOfMonth(1).toDate(),
            disableAfter = now.plusMonths(2).withDayOfMonth(1).minusDays(1).toDate(),
        )

        val pastStart = now.minusMonths(1).withDayOfMonth(15)
        assertFalse(state.calendarUiState.value.isDisabledDay(pastStart))

        state.setSelectedDay(pastStart)
        assertEquals(pastStart, state.calendarUiState.value.selectedStartDate)
    }

    @Test
    fun daysOutsideWindowAreDisabled() {
        val state = rangeState(
            disableBefore = LocalDate.of(2026, 8, 1).toDate(),
            disableAfter = LocalDate.of(2026, 11, 30).toDate(),
        )

        assertTrue(state.calendarUiState.value.isDisabledDay(LocalDate.of(2026, 7, 31)))
        assertTrue(state.calendarUiState.value.isDisabledDay(LocalDate.of(2026, 12, 1)))
        assertFalse(state.calendarUiState.value.isDisabledDay(LocalDate.of(2026, 8, 1)))
        assertFalse(state.calendarUiState.value.isDisabledDay(LocalDate.of(2026, 11, 30)))
    }
}