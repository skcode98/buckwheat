package com.danilkinkin.buckwheat.notifications

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyReminderContentTest {
    private val dailyBudget = BigDecimal("500.00")

    @Test
    fun positiveRemainingShowsLeftAmount() {
        val message = buildDailyReminderMessage(dailyBudget, BigDecimal("150.00"))

        assertEquals(DailyReminderMessageKind.LEFT, message.kind)
        assertEquals(BigDecimal("350.00"), message.amount)
    }

    @Test
    fun zeroRemainingCountsAsLeft() {
        val message = buildDailyReminderMessage(dailyBudget, dailyBudget)

        assertEquals(DailyReminderMessageKind.LEFT, message.kind)
        assertEquals(BigDecimal.ZERO.setScale(2), message.amount)
    }

    @Test
    fun overspentShowsAbsoluteAmount() {
        val message = buildDailyReminderMessage(dailyBudget, BigDecimal("600.00"))

        assertEquals(DailyReminderMessageKind.OVER, message.kind)
        assertEquals(BigDecimal("100.00"), message.amount)
    }

    @Test
    fun noBudgetSetYieldsNoBudgetKind() {
        val message = buildDailyReminderMessage(BigDecimal.ZERO, BigDecimal("50.00"))

        assertEquals(DailyReminderMessageKind.NO_BUDGET, message.kind)
    }
}
