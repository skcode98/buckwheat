package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.categoryTotals
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.toLocalDate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Date

// Everything the past-period detail screen surfaces about a finished period, computed once
// into an immutable value so the composable stays dumb. Pure so it is trivially unit-testable.
data class PeriodSummary(
    val startDate: Date,
    val endDate: Date,
    val budget: BigDecimal,
    val totalSpent: BigDecimal,
    // Whole percent of the budget already spent (may exceed 100); null when the period has no
    // budget (imported buckets), so the UI can fall back to a dash.
    val spentPercent: Int?,
    val spendsCount: Int,
    val biggestSpend: PeriodSummarySpend?,
    val lowestSpend: PeriodSummarySpend?,
    val biggestDay: PeriodSummaryDay?,
    // Days inside the period range (inclusive) with no SPENT records at all.
    val noSpendDays: Int,
    val categories: List<PeriodSummaryCategory>,
)

data class PeriodSummarySpend(
    val amount: BigDecimal,
    val date: Date,
    val comment: String,
)

data class PeriodSummaryDay(
    val date: LocalDate,
    val total: BigDecimal,
)

data class PeriodSummaryCategory(
    val key: CategoryKey,
    val total: BigDecimal,
)

fun buildPeriodSummary(
    startDate: Date,
    finishDate: Date,
    actualFinishDate: Date?,
    budget: BigDecimal,
    spends: List<Transaction>,
): PeriodSummary {
    val endDate = actualFinishDate ?: finishDate
    val totalSpent = spends.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.value }
    val spentPercent = if (budget > BigDecimal.ZERO) {
        totalSpent
            .multiply(BigDecimal(100))
            .divide(budget, 0, RoundingMode.HALF_UP)
            .toInt()
    } else {
        null
    }

    val biggestSpend = spends.maxByOrNull { it.value }?.let {
        PeriodSummarySpend(amount = it.value, date = it.date, comment = it.comment)
    }
    val lowestSpend = spends.minByOrNull { it.value }?.let {
        PeriodSummarySpend(amount = it.value, date = it.date, comment = it.comment)
    }

    val biggestDay = spends
        .groupBy { it.date.toLocalDate() }
        .mapValues { (_, txs) ->
            txs.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.value }
        }
        .maxByOrNull { it.value }
        ?.let { PeriodSummaryDay(date = it.key, total = it.value) }

    val totalDays = countDays(endDate, startDate).coerceAtLeast(0)
    val spendDays = spends.map { it.date.toLocalDate() }.distinct().size
    val noSpendDays = (totalDays - spendDays).coerceAtLeast(0)

    val categories = categoryTotals(spends).map { (key, total) ->
        PeriodSummaryCategory(key = key, total = total)
    }

    return PeriodSummary(
        startDate = startDate,
        endDate = endDate,
        budget = budget,
        totalSpent = totalSpent,
        spentPercent = spentPercent,
        spendsCount = spends.size,
        biggestSpend = biggestSpend,
        lowestSpend = lowestSpend,
        biggestDay = biggestDay,
        noSpendDays = noSpendDays,
        categories = categories,
    )
}
