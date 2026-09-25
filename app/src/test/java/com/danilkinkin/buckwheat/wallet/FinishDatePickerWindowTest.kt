package com.danilkinkin.buckwheat.wallet

import com.danilkinkin.buckwheat.util.toLocalDate
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class FinishDatePickerWindowTest {

    @Test
    fun defaultWindowAllowsPastStartAndFutureFinish() {
        val now = LocalDate.of(2026, 9, 25)
        val (before, after) = defaultPickerWindow(now)

        val start = now.minusMonths(1).withDayOfMonth(15)
        val finish = now.plusMonths(1).withDayOfMonth(20)

        assertFalse(start.isBefore(before.toLocalDate()))
        assertFalse(finish.isAfter(after.toLocalDate()))
    }

    @Test
    fun defaultWindowSpansTwoMonthsBackAndTwoMonthsAhead() {
        val now = LocalDate.of(2026, 9, 25)
        val (before, after) = defaultPickerWindow(now)

        val twoMonthsBack = now.minusMonths(2).withDayOfMonth(1)
        val twoMonthsAhead = now.plusMonths(2).withDayOfMonth(1).minusDays(1)

        assertFalse(before.toLocalDate().isAfter(twoMonthsBack))
        assertFalse(after.toLocalDate().isBefore(twoMonthsAhead))
    }

    @Test
    fun monthStartClampedToOneMonthEarlierThanNow() {
        val now = LocalDate.of(2026, 1, 5)
        val (before, _) = defaultPickerWindow(now)

        assertFalse(before.toLocalDate().isAfter(LocalDate.of(2025, 11, 1)))
    }
}