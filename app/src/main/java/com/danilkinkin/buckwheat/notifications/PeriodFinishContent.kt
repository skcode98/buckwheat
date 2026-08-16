package com.danilkinkin.buckwheat.notifications

import android.content.Context
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal

data class PeriodFinishMessage(
    val title: String,
    val text: String,
)

// Message for the natural end of a budget period. `rest` is the amount left over
// (negative when the period finished over budget).
fun buildPeriodFinishMessage(
    context: Context,
    rest: BigDecimal,
    currency: ExtendCurrency,
): PeriodFinishMessage {
    val amount = numberFormat(context, rest.abs(), currency).trim()
    val title = context.getString(R.string.period_finish_notify_title)
    val text = if (rest >= BigDecimal.ZERO) {
        context.getString(R.string.period_finish_notify_text, amount)
    } else {
        context.getString(R.string.period_finish_overspent_text, amount)
    }
    return PeriodFinishMessage(title, text)
}
