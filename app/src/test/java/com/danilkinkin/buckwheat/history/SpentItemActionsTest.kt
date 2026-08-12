package com.danilkinkin.buckwheat.history

import org.junit.Assert.assertEquals
import org.junit.Test

class SpentItemActionsTest {
    @Test
    fun buildSpendCopyTextAmountOnly() {
        assertEquals("₹1,234.00", buildSpendCopyText("₹1,234.00", ""))
    }

    @Test
    fun buildSpendCopyTextAmountWithBlankComment() {
        assertEquals("₹1,234.00", buildSpendCopyText("₹1,234.00", "   "))
    }

    @Test
    fun buildSpendCopyTextAmountAndComment() {
        assertEquals("₹1,234.00 — Coffee", buildSpendCopyText("₹1,234.00", "Coffee"))
    }
}
