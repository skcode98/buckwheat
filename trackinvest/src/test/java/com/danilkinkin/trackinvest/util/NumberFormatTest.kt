/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.util

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberFormatTest {
    @Test
    fun `groups thousands and strips trailing zeros`() {
        assertEquals("â‚¹1,234.5", formatAmount(BigDecimal("1234.50"), "â‚¹"))
        assertEquals("â‚¹100,000", formatAmount(BigDecimal("100000"), "â‚¹"))
    }

    @Test
    fun `formats small and zero values`() {
        assertEquals("â‚¹0", formatAmount(BigDecimal.ZERO, "â‚¹"))
        assertEquals("â‚¹99", formatAmount(BigDecimal("99"), "â‚¹"))
    }

    @Test
    fun `keeps up to two decimals`() {
        assertEquals("â‚¹0.01", formatAmount(BigDecimal("0.01"), "â‚¹"))
        assertEquals("â‚¹12.34", formatAmount(BigDecimal("12.34"), "â‚¹"))
    }
}
