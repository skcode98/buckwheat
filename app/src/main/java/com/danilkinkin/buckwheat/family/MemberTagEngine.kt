package com.danilkinkin.buckwheat.family

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What a member's spending looks like this period.
 *
* The declaration order is severity order and is not the evaluation order. [memberTag] returns the
 * first of: NOT_ENOUGH_DATA, ON_PLAN, OVER_PLAN, TOO_EARLY, NEAR_LIMIT, SPENDING_UP, SUPER_SAVER.
 *
 * Two orderings are deliberate. OVER_PLAN is checked before TOO_EARLY, because being over budget is a
 * fact about the money rather than a judgement about the period, and reporting a member who is far
 * past their allocation as "just getting started" under-reports the one person the head most needs to
 * see. And SPENDING_UP is asked before SUPER_SAVER, because a member can be both well under pace and
 * sharply up on last period, and the rising figure is the one worth saying out loud.
 */
enum class MemberTag {
    NOT_ENOUGH_DATA,
    /**
     * Too early in the period to say anything.
     *
     * Distinct from [NOT_ENOUGH_DATA] because the two are different facts and a member should not be
     * told they have not been active when they have: there simply is not enough month yet to judge
     * anything. Flooring the pace so nobody reads "near their limit" in the first days would otherwise
     * hand them the mirror-image lie -- "super saver", two days into the month, for spending 6% of it.
     */
    TOO_EARLY,
    SUPER_SAVER,
    ON_PLAN,
    NEAR_LIMIT,
    OVER_PLAN,
    SPENDING_UP,
    ;

    /** Whether this is a judgement about someone rather than a statement about their money. */
    val isPositive: Boolean get() = this == NOT_ENOUGH_DATA || this == SUPER_SAVER || this == TOO_EARLY

    /**
     * Never shown to the member it describes, whatever the family's setting says.
     *
     * The app does not get to tell someone they have a problem with their spending. A member sees
     * their own positive label and their own plain "on plan", and nothing else.
     */
    val isSelfVisible: Boolean get() = isPositive || this == ON_PLAN

    companion object {
        fun fromStored(value: String?): MemberTag? = entries.firstOrNull { it.name == value }
    }
}

/**
 * The thresholds the ladder is cut at.
 *
 * Named rather than inlined because these are the whole definition of the feature and a reader
 * should be able to see all of them at once, and change one without hunting for it. The fractions are
 * of the *pace*, not of the allocation, so a member is not labelled in the first three days of a
 * month for spending normally.
 */
data class MemberTagThresholds(
    /** Below this many transactions a member has no pattern, only coincidence. */
    val minTransactions: Int = 3,
    /** Spent at or under this share of the pace. */
    val superSaverAt: BigDecimal = BigDecimal("0.60"),
    /**
     * Spent at or over this share of the pace.
     *
     * 1.00, not something below it, and the name says *ahead of pace* rather than *near limit*
     * because that is what it measures. A member who has spent 90% of the money the pace allows is
     * *behind*, not in danger, and calling that "near the limit" meant the common case of spending
     * exactly on plan was reported as the worrying one. A member is only ahead of pace — and so at
     * risk of overspending by the end of the period — once they pass 100% of it.
     */
    val aheadOfPaceAt: BigDecimal = BigDecimal("1.00"),
    /**
     * Absolute floor, in currency, for the rising-spend tag. Without it a member who doubled a 200
     * rupee habit is "spending up", which is technically true and useless.
     */
    val trendFloor: BigDecimal = BigDecimal("500"),
    /** And the period total has to have risen by at least this much to count. */
    val trendRatio: BigDecimal = BigDecimal("0.50"),
/**
     * How much of a period must have passed before any band is claimed at all.
     *
     * A gate, not a floor on the paced allowance. Two bands are unjustifiable in the first days:
     * measured literally against `allocation * progress`, a member who spends anything at all is "ahead
     * of pace"; and even after flooring the pace they become a "super saver" for spending a few per cent
     * of the month. Before this much of the period has passed there is nothing to say.
     */
    val minProgress: BigDecimal = BigDecimal("0.15"),
)

/** Everything the engine is allowed to know about one member for one period. */
data class MemberSpending(
    val memberId: String,
    val transactionCount: Int,
    val spent: BigDecimal,
    /** The same figure for the comparable window of the previous period. */
    val previousSpent: BigDecimal,
)

private val ONE = BigDecimal.ONE

/**
 * Tags one member, or [MemberTag.NOT_ENOUGH_DATA] when there is not enough to go on.
 *
 * Pure and total: no clock, no locale, no network, no randomness, and every input maps to exactly one
 * output. That is the whole point of the design, because a tag about a person is only defensible if
 * the person can be shown exactly why they got it. The caller supplies the clock reading and the
 * previous window, so the same inputs always produce the same tag and a test can state the expected
 * one without a fake timer.
 */
fun memberTag(
    spending: MemberSpending,
    allocation: BigDecimal,
    progress: BigDecimal,
    thresholds: MemberTagThresholds = MemberTagThresholds(),
): MemberTag {
    if (spending.transactionCount < thresholds.minTransactions) return MemberTag.NOT_ENOUGH_DATA
    if (allocation.signum() <= 0) return MemberTag.ON_PLAN

    val spent = spending.spent.setScale(2, RoundingMode.HALF_EVEN)

    // Over budget is checked BEFORE the early return. Being over is a fact about the money, not a
    // judgement about the period, and nothing makes it less true in the first days of the month.
    // Returning TOO_EARLY first would report a member spending far past their allocation as "just
    // getting started" -- under-reporting the one person the head most needs to see.
    if (spent > allocation) return MemberTag.OVER_PLAN

    // With that out of the way, no band is claimed before there is enough month to judge: both the
    // remaining alarming band and the flattering one are unjustifiable in the first days.
    if (progress < thresholds.minProgress) return MemberTag.TOO_EARLY

    // Past the end of the period there is no pace left to be ahead of, so the paced figure becomes the
    // whole allocation and every member is judged on the allocation alone.
    val pacedAllocation = allocation.multiply(progress).setScale(2, RoundingMode.HALF_EVEN)

    val denominator = if (pacedAllocation.signum() > 0) pacedAllocation else allocation
    val aheadOfPace = spent.divide(denominator, 4, RoundingMode.HALF_EVEN)

    if (aheadOfPace > thresholds.aheadOfPaceAt) return MemberTag.NEAR_LIMIT

    // Rising is checked before the compliment bands, and that ordering is deliberate rather than
    // accidental. A member who is both well under the pace and sharply up on last period has a
    // problem worth telling them about, and "super saver" is the wrong thing to say to someone whose
    // spending is accelerating. The two are different dimensions, so the actionable one wins.
    val previous = spending.previousSpent
    val rise = spent.subtract(previous)
    val rose = previous.signum() > 0 &&
        rise >= thresholds.trendFloor &&
        spent >= previous.multiply(ONE.add(thresholds.trendRatio))
    if (rose) return MemberTag.SPENDING_UP

    if (aheadOfPace <= thresholds.superSaverAt) return MemberTag.SUPER_SAVER
    return MemberTag.ON_PLAN
}

/**
 * Tags every member, keyed by member id.
 *
 * Members with an allocation but no spending at all are included, because a member who has spent
 * nothing this period still has a line on the budget screen and a blank beside it looks like a bug.
 */
fun memberTags(
    spendings: List<MemberSpending>,
    allocations: List<MemberAllocation>,
    progress: BigDecimal,
    thresholds: MemberTagThresholds = MemberTagThresholds(),
): Map<String, MemberTag> {
    val byId = spendings.associateBy { it.memberId }
    return allocations.associate { allocation ->
        allocation.memberId to memberTag(
            spending = byId[allocation.memberId] ?: MemberSpending(
                memberId = allocation.memberId,
                transactionCount = 0,
                spent = BigDecimal.ZERO,
                previousSpent = BigDecimal.ZERO,
            ),
            allocation = allocation.allocation,
            progress = progress,
            thresholds = thresholds,
        )
    }
}