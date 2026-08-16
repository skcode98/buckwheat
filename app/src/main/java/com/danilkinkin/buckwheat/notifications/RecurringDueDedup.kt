package com.danilkinkin.buckwheat.notifications

import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.DAY
import com.danilkinkin.buckwheat.util.roundToDay
import java.util.Date

// Filters out templates whose payment is already recorded as a spend on the same day
// (same amount and comment), so the reminder doesn't double-list payments that the
// auto-apply pipeline (SILENT mode) or a manual entry already recorded. Kept separate
// from the receiver so the matching can be verified on the JVM (Robolectric).
internal fun filterAlreadyRecorded(
    templates: List<RecurringTemplate>,
    spends: List<Transaction>,
    day: Date,
): List<RecurringTemplate> {
    val start = roundToDay(day).time
    val end = start + DAY
    return templates.filterNot { template ->
        spends.any {
            it.type == TransactionType.SPENT
                && it.date.time in start until end
                && it.value.compareTo(template.amount) == 0
                && it.comment == template.comment
        }
    }
}
