package com.danilkinkin.buckwheat.interleaved

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// One detected recurring spend pattern, ready to become a schedule suggestion: the category
// name (matches the stored category value), the median historical amount, and the inferred
// frequency from the gap between occurrences.
data class ScheduleSuggestion(
    val name: String,
    val amount: BigDecimal,
    val frequency: CategoryFrequency,
)

// Infers the interleaved frequency from the spacing of a category's historical occurrences.
// Needs at least 2 occurrences spread over ~2+ months. Monthly cadence (gaps roughly a month)
// wins over quarterly; anything rarer that spans roughly a year becomes ANNUAL.
fun suggestFrequency(dates: List<LocalDate>): CategoryFrequency? {
    val sorted = dates.sorted()
    if (sorted.size < 2) return null
    val spanDays = ChronoUnit.DAYS.between(sorted.first(), sorted.last())
    if (spanDays < 60) return null
    val medianGap = medianGapDays(sorted)
    return when {
        medianGap >= 20.0 && medianGap <= 45.0 -> CategoryFrequency.MONTHLY
        medianGap >= 60.0 && medianGap <= 120.0 -> CategoryFrequency.QUARTERLY
        spanDays >= 330 -> CategoryFrequency.ANNUAL
        else -> null
    }
}

// Median of the day-gaps between consecutive sorted dates (average of the middle two when even).
fun medianGapDays(sorted: List<LocalDate>): Double {
    val gaps = sorted.zipWithNext().map { (a, b) ->
        ChronoUnit.DAYS.between(a, b).toDouble()
    }
    if (gaps.isEmpty()) return 0.0
    val mid = gaps.size / 2
    return if (gaps.size % 2 == 1) {
        gaps[mid]
    } else {
        (gaps[mid - 1] + gaps[mid]) / 2.0
    }
}

// Median spend amount (2-scale HALF_EVEN, like the wallet math). Empty input -> zero.
fun medianAmount(values: List<BigDecimal>): BigDecimal {
    val sorted = values.map { it.setScale(2, RoundingMode.HALF_EVEN) }.sorted()
    if (sorted.isEmpty()) return BigDecimal.ZERO.setScale(2)
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid]
    } else {
        sorted[mid - 1].plus(sorted[mid])
            .divide(2.toBigDecimal(), 2, RoundingMode.HALF_EVEN)
    }
}

// Groups a spend history by persisted category and produces a schedule suggestion for every
// category that shows a recurring cadence. Uncategorized spends are ignored (the classifier
// keywords are not stable enough for frequency inference).
fun analyzeSpendingPatterns(spends: List<Transaction>): List<ScheduleSuggestion> =
    spends
        .filter { it.type == TransactionType.SPENT && !it.category.isNullOrBlank() }
        .groupBy { it.category!! }
        .mapNotNull { (name, txs) ->
            val dates = txs.map { tx ->
                tx.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            }
            val frequency = suggestFrequency(dates) ?: return@mapNotNull null
            ScheduleSuggestion(name, medianAmount(txs.map { it.value }), frequency)
        }
        .sortedBy { it.name }
