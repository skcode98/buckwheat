package com.danilkinkin.buckwheat.settings

import android.content.Context
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal
import java.math.RoundingMode

// Progress milestones at which a savings goal triggers a nudge. Progress is measured in
// whole percent points; a single allocation can cross several milestones at once, in which
// case only the highest newly reached milestone is announced.
val GOAL_MILESTONES: IntArray = intArrayOf(25, 50, 75, 100)

// Whole percent of the target reached, floored (0 when the target is not positive).
fun goalProgressPercent(goal: SavingsGoal): Int {
    if (goal.targetAmount <= BigDecimal.ZERO) return 0
    return goal.currentAmount
        .multiply(BigDecimal(100))
        .divide(goal.targetAmount, 0, RoundingMode.FLOOR)
        .toInt()
        .coerceIn(0, 100)
}

// Number of milestones already reached for a given progress percent (0..4).
fun goalMilestoneBucket(percent: Int): Int =
    GOAL_MILESTONES.count { percent >= it }

// The highest milestone newly reached when the bucket moves from `lastBucket` to
// `newBucket`, or null when no new milestone was crossed.
fun highestNewlyCrossedMilestone(lastBucket: Int, newBucket: Int): Int? {
    if (newBucket <= lastBucket) return null
    return GOAL_MILESTONES[newBucket - 1]
}

data class GoalNudgeMessage(
    val title: String,
    val text: String,
)

fun buildGoalNudgeMessage(
    context: Context,
    goal: SavingsGoal,
    milestone: Int,
    currency: ExtendCurrency,
): GoalNudgeMessage {
    val saved = numberFormat(context, goal.currentAmount, currency).trim()
    val target = numberFormat(context, goal.targetAmount, currency).trim()
    return if (milestone >= 100) {
        GoalNudgeMessage(
            title = context.getString(R.string.goal_nudge_reached_title),
            text = context.getString(R.string.goal_nudge_reached_text, goal.name, saved, target),
        )
    } else {
        GoalNudgeMessage(
            title = context.getString(R.string.goal_nudge_milestone_title, goal.name, milestone),
            text = context.getString(R.string.goal_nudge_milestone_text, saved, target),
        )
    }
}
