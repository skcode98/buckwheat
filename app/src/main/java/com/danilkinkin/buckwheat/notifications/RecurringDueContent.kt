package com.danilkinkin.buckwheat.notifications

import android.content.Context
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.util.numberFormat

// Builds the notification body listing recurring payments due today. Kept separate from the
// receiver so the formatting can be verified on the JVM (Robolectric).
internal fun buildRecurringDueText(
    context: Context,
    templates: List<RecurringTemplate>,
    currency: ExtendCurrency,
): String = templates.joinToString("\n") { template ->
    val amount = numberFormat(context, template.amount, currency).trim()
    if (template.comment.isBlank()) amount
    else "${template.comment} — $amount"
}
