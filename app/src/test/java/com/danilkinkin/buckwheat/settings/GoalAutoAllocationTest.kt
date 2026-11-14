package com.danilkinkin.buckwheat.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class GoalAutoAllocationTest {
    @Test
    fun leftoverIsUnspentPartOfTodayAllowance() {
        assertEquals(
            0,
            dailyLeftover(BigDecimal("100.00"), BigDecimal("40.00")).compareTo(BigDecimal("60.00")),
        )
    }

    @Test
    fun leftoverIsZeroWhenAllowanceFullySpent() {
        assertEquals(
            0,
            dailyLeftover(BigDecimal("100.00"), BigDecimal("100.00")).compareTo(BigDecimal.ZERO),
        )
    }

    @Test
    fun leftoverIsZeroWhenOverspent() {
        assertEquals(
            0,
            dailyLeftover(BigDecimal("100.00"), BigDecimal("130.00")).compareTo(BigDecimal.ZERO),
        )
    }

    @Test
    fun leftoverIsZeroWhenNothingWasBudgeted() {
        assertEquals(
            0,
            dailyLeftover(BigDecimal.ZERO, BigDecimal.ZERO).compareTo(BigDecimal.ZERO),
        )
    }
}
