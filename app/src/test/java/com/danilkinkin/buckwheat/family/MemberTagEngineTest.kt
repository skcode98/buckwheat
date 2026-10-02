package com.danilkinkin.buckwheat.family

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `previous` defaults to `spent`, meaning "no change since last period".
 *
 * The first version of this file defaulted it to a flat 1000.00, which quietly made every single case
 * a period-on-period doubling and so a rising-spend case, and then the assertions failed for reasons
 * that had nothing to do with what they claimed to be testing. Only the tests that are actually about
 * a trend pass it explicitly.
 */
private fun spending(
    count: Int = 5,
    spent: String = "1000.00",
    previous: String = spent,
) = MemberSpending(
    memberId = "m1",
    transactionCount = count,
    spent = BigDecimal(spent),
    previousSpent = BigDecimal(previous),
)

private val full = BigDecimal("10000.00")
private val half = BigDecimal("0.5000")

class MemberTagEngineTest {

    @Test
    fun tooFewTransactionsMeansNoOpinion() {
        assertEquals(
            MemberTag.NOT_ENOUGH_DATA,
            memberTag(spending(count = 2, spent = "9999.00"), full, BigDecimal.ONE),
        )
    }

    @Test
    fun spendingHalfThePaceIsNotYetASaver() {
        assertEquals(MemberTag.ON_PLAN, memberTag(spending(spent = "5000.00"), full, half))
    }

    @Test
    fun wellUnderThePaceIsASaver() {
        assertEquals(MemberTag.SUPER_SAVER, memberTag(spending(spent = "2000.00"), full, half))
    }

@Test
fun ninetyPercentOfThePaceIsStillOnPlan() {
    // Being under the pace is not being near a limit. This case is the one the old 0.90 threshold got
    // backwards, reporting the most ordinary week of a month as the dangerous one.
    assertEquals(MemberTag.ON_PLAN, memberTag(spending(spent = "4500.00"), full, half))
}

@Test
fun spendingAheadOfThePaceIsNearTheLimit() {
    // 7500 of a 5000 paced figure, so 1.5x ahead, while still inside the 10000 allocation. Ahead of
    // pace and over budget are different labels, and the boundary between them is the allocation: a
    // member who crosses it stops being "at risk" and becomes over.
    assertEquals(MemberTag.NEAR_LIMIT, memberTag(spending(spent = "7500.00"), full, half))
}

    @Test
    fun goingOverTheAllocationOutranksEverything() {
        assertEquals(
            MemberTag.OVER_PLAN,
            memberTag(spending(spent = "10001.00", previous = "0.00"), full, half),
        )
    }

@Test
fun aDoubledHabitTooSmallToMatterIsNotTrending() {
    // Doubled from 200 to 400: a real rise, but under the 500 absolute floor, so it is just being a saver.
    assertEquals(MemberTag.SUPER_SAVER, memberTag(spending(spent = "400.00", previous = "200.00"), full, half))
}

@Test
fun aRiseBiggerThanTheFloorIsTrendingEvenIfStillUnderPace() {
    // The point of the ordering: 3000 of a 5000 paced figure is well within budget, but tripling is
    // the thing worth saying. "Super saver" here would be true of the level and useless about the trend.
    assertEquals(
        MemberTag.SPENDING_UP,
        memberTag(spending(spent = "3000.00", previous = "1000.00"), full, half),
    )
}

@Test
fun aDoubledHabitThatMattersIsTrending() {
    assertEquals(
        MemberTag.SPENDING_UP,
        memberTag(spending(spent = "3000.00", previous = "1000.00"), full, half),
    )
}

@Test
fun aSmallRiseUnderTheRatioIsNotTrending() {
    // 2000 from 1800 is a rise of 200: under both the 500 floor and the 50% ratio threshold.
    assertEquals(
        MemberTag.SUPER_SAVER,
        memberTag(spending(spent = "2000.00", previous = "1800.00"), full, half),
    )
}

    @Test
    fun beingOverBudgetBeatsBeingTrending() {
        assertEquals(
            MemberTag.OVER_PLAN,
            memberTag(spending(spent = "12000.00", previous = "1000.00"), full, BigDecimal.ONE),
        )
    }

    @Test
    fun pacingMeansTheFirstDaysDoNotProduceALabel() {
        // 10% through the month having spent 10% of the allocation: exactly on pace.
        assertEquals(
            MemberTag.ON_PLAN,
            memberTag(spending(spent = "1000.00"), full, BigDecimal("0.1000")),
        )
    }

    @Test
    fun aFinishedPeriodIsJudgedOnTheAllocationNotThePace() {
        // A member who spent 55% of a finished month is a saver, not merely on plan.
        assertEquals(MemberTag.SUPER_SAVER, memberTag(spending(spent = "5500.00"), full, BigDecimal.ONE))
    }

    @Test
    fun aZeroAllocationCannotBeJudgedAsOverOrUnder() {
        assertEquals(MemberTag.ON_PLAN, memberTag(spending(spent = "50.00"), BigDecimal.ZERO, half))
    }

    @Test
    fun everyMemberGetsALineEvenWithNoSpending() {
        val tags = memberTags(
            spendings = emptyList(),
            allocations = listOf(
                MemberAllocation("m1", BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("100.00")),
            ),
            progress = half,
        )

        assertEquals(MemberTag.NOT_ENOUGH_DATA, tags["m1"])
    }

    @Test
    fun onlyPositiveTagsAreEverVisibleToTheirOwnSubject() {
        // The point of the feature: a person is never labelled by the app, whatever the family setting.
        assertEquals(true, MemberTag.SUPER_SAVER.isSelfVisible)
        assertEquals(true, MemberTag.ON_PLAN.isSelfVisible)
        assertEquals(false, MemberTag.OVER_PLAN.isSelfVisible)
        assertEquals(false, MemberTag.NEAR_LIMIT.isSelfVisible)
        assertEquals(false, MemberTag.SPENDING_UP.isSelfVisible)
    }
}