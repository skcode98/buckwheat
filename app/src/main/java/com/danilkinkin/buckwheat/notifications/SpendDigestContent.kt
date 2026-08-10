package com.danilkinkin.buckwheat.notifications

import android.content.Context
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.roundToDay
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class SpendDigestFrequency {
    WEEKLY,
    MONTHLY,
}

data class SpendDigest(
    val total: BigDecimal,
    val transactionCount: Int,
    val dailyAverage: BigDecimal,
)

data class SpendDigestMessage(
    val title: String,
    val text: String,
)

// Inclusive [from, to] window the digest covers, ending today.
fun digestRange(
    frequency: SpendDigestFrequency,
    today: LocalDate = LocalDate.now(),
): Pair<LocalDate, LocalDate> = when (frequency) {
    SpendDigestFrequency.WEEKLY -> today.minusDays(6) to today
    SpendDigestFrequency.MONTHLY -> today.minusDays(29) to today
}

// Reduces the period's SPENT transactions into a digest. Spends outside the window are ignored
// so the DAO can be queried with a slightly wider range without double counting.
fun buildSpendDigest(
    spends: List<Transaction>,
    from: LocalDate,
    to: LocalDate,
): SpendDigest {
    var total = BigDecimal.ZERO
    var count = 0
    for (transaction in spends) {
        val day = roundToDay(transaction.date)
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        if (day.isBefore(from) || day.isAfter(to)) continue
        total += transaction.value
        count++
    }
    val days = ChronoUnit.DAYS.between(from, to) + 1
    val dailyAverage = if (days > 0 && count > 0) {
        total.divide(BigDecimal(days), 2, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ZERO
    }
    return SpendDigest(total, count, dailyAverage)
}

fun buildSpendDigestMessage(
    context: Context,
    digest: SpendDigest,
    frequency: SpendDigestFrequency,
    currency: ExtendCurrency,
): SpendDigestMessage {
    val total = numberFormat(context, digest.total, currency).trim()
    val average = numberFormat(context, digest.dailyAverage, currency).trim()
    val title = when (frequency) {
        SpendDigestFrequency.WEEKLY ->
            context.getString(R.string.spend_digest_weekly_title, total)
        SpendDigestFrequency.MONTHLY ->
            context.getString(R.string.spend_digest_monthly_title, total)
    }
    val text = context.getString(
        R.string.spend_digest_text,
        digest.transactionCount,
        average,
    )
    return SpendDigestMessage(title, text)
}
