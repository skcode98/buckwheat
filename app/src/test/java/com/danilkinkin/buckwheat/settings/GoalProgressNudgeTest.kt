package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoalProgressNudgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val currency = ExtendCurrency.none()

    private fun goal(current: BigDecimal, target: BigDecimal) = SavingsGoal(
        name = "Trip",
        targetAmount = target,
        currentAmount = current,
    )

    private fun format(value: BigDecimal): String {
        val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
        formatter.maximumFractionDigits = 2
        formatter.minimumFractionDigits = 0
        return formatter.format(value)
    }

    @Test
    fun progressPercentIsZeroWhenTargetNotPositive() {
        assertEquals(0, goalProgressPercent(goal(BigDecimal("10"), BigDecimal.ZERO)))
        assertEquals(0, goalProgressPercent(goal(BigDecimal("10"), BigDecimal("-5"))))
    }

    @Test
    fun progressPercentFloorsAndClamps() {
        assertEquals(50, goalProgressPercent(goal(BigDecimal("50"), BigDecimal("100"))))
        assertEquals(49, goalProgressPercent(goal(BigDecimal("49.99"), BigDecimal("100"))))
        assertEquals(0, goalProgressPercent(goal(BigDecimal.ZERO, BigDecimal("100"))))
        assertEquals(100, goalProgressPercent(goal(BigDecimal("250"), BigDecimal("100"))))
    }

    @Test
    fun milestoneBucketCountsReachedMilestones() {
        assertEquals(0, goalMilestoneBucket(0))
        assertEquals(0, goalMilestoneBucket(24))
        assertEquals(1, goalMilestoneBucket(25))
        assertEquals(1, goalMilestoneBucket(49))
        assertEquals(2, goalMilestoneBucket(50))
        assertEquals(2, goalMilestoneBucket(74))
        assertEquals(3, goalMilestoneBucket(75))
        assertEquals(3, goalMilestoneBucket(99))
        assertEquals(4, goalMilestoneBucket(100))
    }

    @Test
    fun highestNewlyCrossedMilestone() {
        assertNull(highestNewlyCrossedMilestone(0, 0))
        assertNull(highestNewlyCrossedMilestone(2, 2))
        assertEquals(25, highestNewlyCrossedMilestone(0, 1))
        assertEquals(75, highestNewlyCrossedMilestone(1, 3))
        assertEquals(100, highestNewlyCrossedMilestone(2, 4))
    }

    @Test
    fun milestoneMessageListsGoalNameAndPercent() {
        val message = buildGoalNudgeMessage(
            context,
            goal(BigDecimal("50"), BigDecimal("100")),
            50,
            currency,
        )

        assertEquals("Trip — 50% saved", message.title)
        assertEquals(
            "You've saved ${format(BigDecimal("50"))} of ${format(BigDecimal("100"))} — keep going!",
            message.text,
        )
    }

    @Test
    fun reachedMessageCelebratesCompletion() {
        val message = buildGoalNudgeMessage(
            context,
            goal(BigDecimal("100"), BigDecimal("100")),
            100,
            currency,
        )

        assertEquals("Goal reached!", message.title)
        assertEquals(
            "Goal \"Trip\" reached — you saved ${format(BigDecimal("100"))} of ${format(BigDecimal("100"))}!",
            message.text,
        )
    }
}
