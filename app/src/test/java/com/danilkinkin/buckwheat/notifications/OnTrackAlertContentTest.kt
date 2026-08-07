package com.danilkinkin.buckwheat.notifications

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class OnTrackAlertContentTest {
    private val dailyBudget = BigDecimal("500.00")

    @Test
    fun paceOverBudgetWarns() {
        // Half the day gone, 300 spent -> projected 600 > 500.
        val message = buildOnTrackAlertMessage(dailyBudget, BigDecimal("300.00"), 720)

        assertEquals(OnTrackAlertKind.WILL_OVERRUN, message.kind)
        assertEquals(BigDecimal("600.00").setScale(2), message.projected)
    }

    @Test
    fun paceWithinBudgetDoesNotWarn() {
        // Half the day gone, 200 spent -> projected 400 < 500.
        val message = buildOnTrackAlertMessage(dailyBudget, BigDecimal("200.00"), 720)

        assertEquals(OnTrackAlertKind.NONE, message.kind)
    }

    @Test
    fun zeroSpentDoesNotWarn() {
        val message = buildOnTrackAlertMessage(dailyBudget, BigDecimal.ZERO, 720)

        assertEquals(OnTrackAlertKind.NONE, message.kind)
    }

    @Test
    fun tooEarlyDoesNotWarn() {
        val message = buildOnTrackAlertMessage(dailyBudget, BigDecimal("300.00"), 120)

        assertEquals(OnTrackAlertKind.NONE, message.kind)
    }

    @Test
    fun alreadyAtBudgetDoesNotTriggerOnTrackWarning() {
        val message = buildOnTrackAlertMessage(dailyBudget, dailyBudget, 720)

        assertEquals(OnTrackAlertKind.NONE, message.kind)
    }

    @Test
    fun noBudgetSetYieldsNoBudgetKind() {
        val message = buildOnTrackAlertMessage(BigDecimal.ZERO, BigDecimal("300.00"), 720)

        assertEquals(OnTrackAlertKind.NO_DAILY_BUDGET, message.kind)
    }
}