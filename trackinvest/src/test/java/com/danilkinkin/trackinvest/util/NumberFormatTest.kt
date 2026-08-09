/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.util

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberFormatTest {
    @Test
    fun `groups thousands and strips trailing zeros`() {
        assertEquals("₹1,234.5", formatAmount(BigDecimal("1234.50"), "₹"))
        assertEquals("₹100,000", formatAmount(BigDecimal("100000"), "₹"))
    }

    @Test
    fun `formats small and zero values`() {
        assertEquals("₹0", formatAmount(BigDecimal.ZERO, "₹"))
        assertEquals("₹99", formatAmount(BigDecimal("99"), "₹"))
    }

    @Test
    fun `keeps up to two decimals`() {
        assertEquals("₹0.01", formatAmount(BigDecimal("0.01"), "₹"))
        assertEquals("₹12.34", formatAmount(BigDecimal("12.34"), "₹"))
    }
}
