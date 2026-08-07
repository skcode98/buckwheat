package com.danilkinkin.buckwheat.notifications

import java.math.BigDecimal
import java.math.RoundingMode

enum class OnTrackAlertKind { NONE, WILL_OVERRUN, NO_DAILY_BUDGET }

data class OnTrackAlertMessage(
    val kind: OnTrackAlertKind,
    val projected: BigDecimal,
    val dailyBudget: BigDecimal,
)

// Do not warn too early in the day: a single early spend trivially projects over budget.
const val ON_TRACK_MIN_ELAPSED_MINUTES = 240
const val MINUTES_PER_DAY = 1440

/**
 * Proactive "on track to overspend" check: projects today's final spend from the current spend
 * pace and how much of the day has already passed. Returns [OnTrackAlertKind.WILL_OVERRUN] only
 * while the user is still within the daily budget but spending fast enough to exceed it, so the
 * instant overspend notification keeps ownership of the actual crossing.
 */
fun buildOnTrackAlertMessage(
    dailyBudget: BigDecimal,
    spent: BigDecimal,
    elapsedMinutes: Int,
): OnTrackAlertMessage {
    val projected = if (spent > BigDecimal.ZERO && elapsedMinutes > 0) {
        spent.multiply(BigDecimal(MINUTES_PER_DAY))
            .divide(BigDecimal(elapsedMinutes), 2, RoundingMode.HALF_EVEN)
    } else {
        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
    }
    val thresholdReached = elapsedMinutes >= ON_TRACK_MIN_ELAPSED_MINUTES
    return when {
        dailyBudget <= BigDecimal.ZERO -> OnTrackAlertMessage(
            kind = OnTrackAlertKind.NO_DAILY_BUDGET,
            projected = projected,
            dailyBudget = dailyBudget,
        )
        !thresholdReached || spent <= BigDecimal.ZERO -> OnTrackAlertMessage(
            kind = OnTrackAlertKind.NONE,
            projected = projected,
            dailyBudget = dailyBudget,
        )
        spent < dailyBudget && projected > dailyBudget -> OnTrackAlertMessage(
            kind = OnTrackAlertKind.WILL_OVERRUN,
            projected = projected,
            dailyBudget = dailyBudget,
        )
        else -> OnTrackAlertMessage(
            kind = OnTrackAlertKind.NONE,
            projected = projected,
            dailyBudget = dailyBudget,
        )
    }
}