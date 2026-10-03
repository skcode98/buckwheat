package com.danilkinkin.buckwheat.family

import com.danilkinkin.buckwheat.data.entities.CommonSplitRule
import com.danilkinkin.buckwheat.data.entities.FamilyState
import com.danilkinkin.buckwheat.data.entities.PeriodLimit
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** What one member has spent, already grouped. Mirrors `MemberSpend` in the repository read path. */
data class MemberAmount(
    val memberId: String,
    val amount: BigDecimal,
    /** Personal transactions behind [amount]. The tag engine refuses to judge one or two purchases. */
    val transactionCount: Int = 0,
)

/** One member's line in the family budget: what they were given, what they used, what is left. */
data class MemberAllocation(
    val memberId: String,
    val allocation: BigDecimal,
    val spent: BigDecimal,
    val shareOfHousehold: BigDecimal,
    val remaining: BigDecimal,
    val transactionCount: Int = 0,
)

/** Everything the family budget screen renders, derived from stored rows and nothing else. */
data class FamilyBudget(
    val total: BigDecimal,
    val householdTier: BigDecimal,
    val memberTier: BigDecimal,
    val householdSpent: BigDecimal,
    val householdRemaining: BigDecimal,
    val allocations: List<MemberAllocation>,
) {
    val memberSpent: BigDecimal get() = allocations.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.spent) }

    val allocated: BigDecimal get() = allocations.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.allocation) }

    val totalSpent: BigDecimal get() = householdSpent.add(memberSpent)

    val remaining: BigDecimal get() = total.subtract(totalSpent)
}

/**
 * The key an allocation set is stored under.
 *
 * `budget_periods` rows only exist once a period has been *closed*, so the active period has no row
 * to point `period_limits.period_id` at. Deriving the key from the period's start date rather than
 * generating one fixes both cases with the same value: a period that is later closed keeps the
 * allocations it had while it was open, without `finishBudget` having to know this exists, and two
 * devices agree on the key without talking to each other.
 *
 * It has to be a UUID, not a readable string. The sync server validates `period_id` as a UUID and
 * rejects anything else with `payload_invalid`, which would mean every allocation and every request
 * the app pushed was silently refused and the pool never synced between devices. A name-based UUID
 * keeps the determinism while satisfying that, and `UUID.fromString` is guaranteed to accept it
 * because `nameUUIDFromBytes` produced it.
 */
fun poolPeriodId(startDate: Long): String =
    UUID.nameUUIDFromBytes("pool:$startDate".toByteArray(Charsets.UTF_8)).toString()

/**
 * How far through the period is, as a fraction, from the stored bounds.
 *
 * Returns 1 for a period with no length rather than dividing by zero, and clamps to 0..1: a
 * household reading "118% of the way through its money" is already confusing enough without the
 * rate also running backwards because a clock moved.
 */
fun periodProgress(startDate: Long, finishDate: Long, now: Long): BigDecimal {
    val span = finishDate - startDate
    if (span <= 0L) return BigDecimal.ONE
    val elapsed = now - startDate
    if (elapsed <= 0L) return BigDecimal.ZERO
    if (elapsed >= span) return BigDecimal.ONE
    return BigDecimal(elapsed).divide(BigDecimal(span), 4, RoundingMode.HALF_EVEN)
}

/**
 * One member's weight when a household expense is divided up.
 *
 * [EQUAL] divides by the member count including the head, which is the defensible default: the head
 * is a member of the household and takes the same share as anyone else unless the family says
 * otherwise. [PROPORTIONAL] weights by allocation and hands a zero allocation a zero share, so a
 * member who set aside nothing is shielded from the rent.
 */
fun householdWeight(
    rule: CommonSplitRule,
    allocation: BigDecimal,
    totalAllocation: BigDecimal,
    memberCount: Int,
): BigDecimal = when (rule) {
    CommonSplitRule.EQUAL ->
        if (memberCount <= 0) BigDecimal.ZERO else BigDecimal.ONE.divide(BigDecimal(memberCount), 8, RoundingMode.HALF_EVEN)

    CommonSplitRule.PROPORTIONAL ->
        if (totalAllocation.signum() <= 0) BigDecimal.ZERO
        else allocation.divide(totalAllocation, 8, RoundingMode.HALF_EVEN)
}

/**
 * The household's share of one member, rounded to the currency scale.
 *
 * Rounding each member independently and then checking the total is the whole reason this returns
 * money rather than a fraction: eight equal shares of 1000.00 at scale 2 are exact, but three
 * shares of 1000.00 are not, and a leftover cent that appears in one place and not another reads as
 * a bug in a ledger. [familyBudget] pushes any rounding remainder into the last member instead.
 */
fun householdShare(householdSpent: BigDecimal, weight: BigDecimal): BigDecimal =
    householdSpent.multiply(weight).setScale(2, RoundingMode.HALF_EVEN)

/**
 * Builds the whole family budget view.
 *
 * Every figure is derived, never stored, so the pool can be recomputed from the transactions at any
 * time and cannot drift from them. Negative values are allowed and shown: a household that has
 * overspent has overspent, and clamping to zero would hide the size of the hole while still letting
 * the next spend look affordable.
 *
 * The rounding remainder from dividing household spend lands on the last member rather than being
 * dropped, so the per-member remaining figures always sum to the household remaining figure.
 */
fun familyBudget(
    state: FamilyState,
    limits: List<PeriodLimit>,
    spentByMember: List<MemberAmount>,
    householdSpent: BigDecimal,
): FamilyBudget {
    val memberCount = limits.size
    val totalAllocation = limits.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.limitValue) }
    val spentById = spentByMember.associate { it.memberId to it.amount }
    val spentCounts = spentByMember.associate { it.memberId to it.transactionCount }
    val household = householdSpent.setScale(2, RoundingMode.HALF_EVEN)

    val shares = limits.map { limit ->
        val weight = householdWeight(state.splitRule, limit.limitValue, totalAllocation, memberCount)
        limit.memberId to householdShare(household, weight)
    }

    val remainder = household.subtract(shares.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.second) })

    val allocations = limits.mapIndexed { index, limit ->
        val share = shares[index].second.let { if (index == shares.lastIndex) it.add(remainder) else it }
        val spent = (spentById[limit.memberId] ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN)
        val count = spentCounts[limit.memberId] ?: 0
        val allocation = limit.limitValue.setScale(2, RoundingMode.HALF_EVEN)
        MemberAllocation(
            memberId = limit.memberId,
            allocation = allocation,
            spent = spent,
            shareOfHousehold = share,
            // Only the member's own overspend can push this negative. The household share is added
            // here, never subtracted, so an under-allocated split cannot make a member's own number
            // look worse than their own spending.
            remaining = allocation.subtract(spent).subtract(share),
            transactionCount = count,
        )
    }

    return FamilyBudget(
        total = state.budget.setScale(2, RoundingMode.HALF_EVEN),
        householdTier = state.householdTier.setScale(2, RoundingMode.HALF_EVEN),
        memberTier = state.memberTier.setScale(2, RoundingMode.HALF_EVEN),
        householdSpent = household,
        householdRemaining = state.householdTier.setScale(2, RoundingMode.HALF_EVEN).subtract(household),
        allocations = allocations,
    )
}

/**
 * Why an allocation edit was refused, or null when it is fine.
 *
 * A partially allocated pool is not a storable state. Leaving it storable means the pool and the
 * member slices disagree and one of the two numbers on screen is a lie, so the editor refuses and
 * says which of the two the person meant.
 */
enum class AllocationProblem {
    POOL_EXCEEDS_TOTAL,
    OVER_ALLOCATED,
    UNDER_ALLOCATED,
    NEGATIVE_ALLOCATION,
    NO_ALLOCATION,
    /** A pool of nothing is storable but meaningless, and would sync to every device. */
    POOL_NOT_POSITIVE,
}

fun validateAllocations(
    limits: List<PeriodLimit>,
    total: BigDecimal,
    memberCount: Int,
): AllocationProblem? {
    if (memberCount <= 0) return AllocationProblem.NO_ALLOCATION
    if (limits.any { it.limitValue.signum() < 0 }) return AllocationProblem.NEGATIVE_ALLOCATION
    val sum = limits.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.limitValue) }
        .setScale(2, RoundingMode.HALF_EVEN)
    val target = total.setScale(2, RoundingMode.HALF_EVEN)
    return when {
        sum > target -> AllocationProblem.OVER_ALLOCATED
        sum < target -> AllocationProblem.UNDER_ALLOCATED
        else -> null
    }
}