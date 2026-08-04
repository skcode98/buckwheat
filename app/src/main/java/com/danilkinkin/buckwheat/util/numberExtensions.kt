package com.danilkinkin.buckwheat.util

import java.math.BigDecimal
import java.math.RoundingMode

fun BigDecimal.isZero(): Boolean = this.signum() == 0

fun BigDecimal.isEquals(second: BigDecimal): Boolean =
    this.setScale(2, RoundingMode.HALF_EVEN) == second.setScale(2, RoundingMode.HALF_EVEN)

// Safe parse of a spoken/AI/CSV-provided amount like "150.5 USD", "₹150", "12,50", "1,234"
// Returns null when the text contains no usable number, so callers can bail out.
fun parseAmountToBigDecimal(raw: String): BigDecimal? {
    val cleaned = raw.replace(Regex("[^0-9.,]"), "")
    if (cleaned.isEmpty()) return null

    val normalized = when {
        cleaned.contains(".") && cleaned.contains(",") -> cleaned.replace(",", "")
        cleaned.contains(",") -> {
            val afterComma = cleaned.substringAfterLast(",")
            if (afterComma.length in 1..2 && !afterComma.contains(".")) {
                cleaned.replace(",", ".")
            } else {
                cleaned.replace(",", "")
            }
        }

        else -> cleaned
    }

    return normalized.toBigDecimalOrNull()
}

// clamp(3.5f, 6.7f) > [0.0f, 1.0f]
fun Float.clamp(min: Float, max: Float): Float = (1f - ((this.coerceIn(min, max) - min) / (max - min)))