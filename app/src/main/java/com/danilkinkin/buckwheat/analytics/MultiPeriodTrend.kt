package com.danilkinkin.buckwheat.analytics

import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.util.toLocalDate
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

// One period's slice of the month-over-month trend.
data class MultiPeriodPoint(
    val label: String,
    val spent: BigDecimal,
    val budget: BigDecimal,
    val isCurrent: Boolean,
)

// Combines the archived budget periods (oldest first) with the in-progress current period as the
// final point. Periods that never spent anything and had no budget are skipped so the chart only
// carries real data. Labels stay terse ("Aug"); when the span covers more than one calendar year
// they include the year ("Aug '26") to keep points unambiguous.
fun multiPeriodTotals(
    periods: List<BudgetPeriod>,
    currentBudget: BigDecimal,
    currentSpent: BigDecimal,
    currentStart: Date,
): List<MultiPeriodPoint> {
    val zero = BigDecimal.ZERO
    val archived = periods
        .filter { it.totalSpent > zero || it.budget > zero }
        .sortedBy { it.startDate }
    val includeCurrent = currentSpent > zero || currentBudget > zero
    val starts = archived.map { it.startDate } + if (includeCurrent) listOf(currentStart) else emptyList()
    if (starts.isEmpty()) return emptyList()

    val years = starts.map { it.toLocalDate().year }.distinct()
    val pattern = if (years.size > 1) "MMM ''yy" else "MMM"
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)

    val result = mutableListOf<MultiPeriodPoint>()
    archived.forEach { period ->
        result += MultiPeriodPoint(
            label = period.startDate.toLocalDate().format(formatter),
            spent = period.totalSpent,
            budget = period.budget,
            isCurrent = false,
        )
    }
    if (includeCurrent) {
        result += MultiPeriodPoint(
            label = currentStart.toLocalDate().format(formatter),
            spent = currentSpent,
            budget = currentBudget,
            isCurrent = true,
        )
    }
    return result
}
