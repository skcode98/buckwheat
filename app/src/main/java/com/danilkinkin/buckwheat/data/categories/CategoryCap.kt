package com.danilkinkin.buckwheat.data.categories

import java.math.BigDecimal
import java.math.RoundingMode

// Cap levels for a category: nothing announced below 80%, then one notification when the
// period spend crosses 80% of the cap and one when it reaches or exceeds 100%. Progress is
// measured against the current budget period's spend totals. Pure so it is trivially
// unit-testable.
const val CATEGORY_CAP_NEAR_BUCKET = 1
const val CATEGORY_CAP_REACHED_BUCKET = 2
const val CATEGORY_CAP_NEAR_PERCENT = 80

// Whole percent of the cap already spent, floored (0 for a non-positive cap).
fun categoryCapPercent(progress: BigDecimal, cap: BigDecimal): Int {
    if (cap <= BigDecimal.ZERO) return 0
    return progress
        .multiply(BigDecimal(100))
        .divide(cap, 0, RoundingMode.FLOOR)
        .toInt()
        .coerceIn(0, 100)
}

// 0 = nothing reached, 1 = at/above 80% of the cap, 2 = at/above the full cap.
fun categoryCapBucket(progress: BigDecimal, cap: BigDecimal): Int =
    if (cap <= BigDecimal.ZERO) 0
    else if (progress >= cap) CATEGORY_CAP_REACHED_BUCKET
    else if (categoryCapPercent(progress, cap) >= CATEGORY_CAP_NEAR_PERCENT) CATEGORY_CAP_NEAR_BUCKET
    else 0

// The level newly reached when moving from `lastBucket` to `newBucket`, or 0 when the level
// did not increase (i.e. there is no new crossing to announce).
fun highestNewlyReachedCapBucket(lastBucket: Int, newBucket: Int): Int =
    if (newBucket > lastBucket) newBucket else 0
