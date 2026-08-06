package com.danilkinkin.buckwheat.notifications

import java.math.BigDecimal

enum class DailyReminderMessageKind { LEFT, OVER, NO_BUDGET }

data class DailyReminderMessage(
    val kind: DailyReminderMessageKind,
    val amount: BigDecimal,
)

fun buildDailyReminderMessage(
    dailyBudget: BigDecimal,
    spentFromDailyBudget: BigDecimal,
): DailyReminderMessage {
    val remaining = dailyBudget - spentFromDailyBudget
    return when {
        dailyBudget <= BigDecimal.ZERO -> DailyReminderMessage(
            kind = DailyReminderMessageKind.NO_BUDGET,
            amount = BigDecimal.ZERO,
        )
        remaining >= BigDecimal.ZERO -> DailyReminderMessage(
            kind = DailyReminderMessageKind.LEFT,
            amount = remaining,
        )
        else -> DailyReminderMessage(
            kind = DailyReminderMessageKind.OVER,
            amount = remaining.abs(),
        )
    }
}
