package com.danilkinkin.buckwheat.editor

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType

// Most recent SPENT transaction in the given list, used by the editor's "repeat last spend"
// quick action. Ignores non-SPENT rows (SET_DAILY_BUDGET, INCOME) and returns null when there
// is nothing repeatable. Pure + deterministic for unit tests.
fun lastSpendToRepeat(spends: List<Transaction>): Transaction? =
    spends
        .filter { it.type == TransactionType.SPENT }
        .maxWithOrNull(compareBy(Transaction::date, Transaction::uid))
