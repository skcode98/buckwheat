/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.util

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

fun formatAmount(amount: BigDecimal, symbol: String): String {
    val formatted = NumberFormat.getNumberInstance(Locale.US).apply {
        isGroupingUsed = true
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }.format(amount)
    return "$symbol$formatted"
}
