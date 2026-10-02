package com.danilkinkin.buckwheat.family

import com.danilkinkin.buckwheat.data.entities.CommonSplitRule
import com.danilkinkin.buckwheat.data.entities.FamilyState
import com.danilkinkin.buckwheat.data.entities.PeriodLimit
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun state(
    budget: String = "30000.00",
    householdTier: String = "0.00",
    rule: CommonSplitRule = CommonSplitRule.EQUAL,
) = FamilyState(
    familyId = "family-1",
    budget = BigDecimal(budget),
    householdTier = BigDecimal(householdTier),
    startDate = 0L,
    finishDate = 30_000L,
    currency = "INR",
    commonSplitRule = rule.name,
)

private fun limit(memberId: String, value: String) =
    PeriodLimit(periodId = "pool_0", memberId = memberId, limitValue = BigDecimal(value))

private fun spent(memberId: String, value: String) = MemberAmount(memberId, BigDecimal(value))

class FamilyBudgetMathTest {

    @Test
    fun thePoolSplitsIntoAHouseholdTierAndTheRest() {
        val budget = familyBudget(
            state = state(budget = "30000.00", householdTier = "10000.00"),
            limits = listOf(limit("m1", "13000.00"), limit("m2", "7000.00")),
            spentByMember = emptyList(),
            householdSpent = BigDecimal.ZERO,
        )

        assertEquals(BigDecimal("30000.00"), budget.total)
        assertEquals(BigDecimal("10000.00"), budget.householdTier)
        assertEquals(BigDecimal("20000.00"), budget.memberTier)
        assertEquals(BigDecimal("20000.00"), budget.allocated)
    }

    @Test
    fun theWorkedExampleSplitsThirtyThousandThirteenTenAndSeven() {
        val budget = familyBudget(
            state = state(budget = "30000.00"),
            limits = listOf(limit("m1", "13000.00"), limit("m2", "10000.00"), limit("m3", "7000.00")),
            // 13k + 4k + 7k = 24k spent of 30k.
            spentByMember = listOf(spent("m1", "13000.00"), spent("m2", "4000.00"), spent("m3", "7000.00")),
            householdSpent = BigDecimal.ZERO,
        )

        assertEquals(BigDecimal("30000.00"), budget.allocated)
        assertEquals(0, BigDecimal("6000.00").compareTo(budget.remaining))
        assertEquals(0, BigDecimal("0.00").compareTo(budget.allocations[0].remaining))
        assertEquals(0, BigDecimal("6000.00").compareTo(budget.allocations[1].remaining))
        assertEquals(0, BigDecimal("0.00").compareTo(budget.allocations[2].remaining))
    }

    @Test
    fun householdSpendIsDividedEquallyByDefault() {
        val budget = familyBudget(
            state = state(budget = "30000.00", householdTier = "9000.00"),
            limits = listOf(limit("m1", "13000.00"), limit("m2", "10000.00"), limit("m3", "7000.00")),
            spentByMember = emptyList(),
            householdSpent = BigDecimal("9000.00"),
        )

        // 9000 of household spend over three members, including the head.
        assertEquals(BigDecimal("3000.00"), budget.allocations[0].shareOfHousehold)
        assertEquals(BigDecimal("3000.00"), budget.allocations[1].shareOfHousehold)
        assertEquals(BigDecimal("3000.00"), budget.allocations[2].shareOfHousehold)
        // 13k of allocation, 3k of the household's rent.
        assertEquals(0, BigDecimal("10000.00").compareTo(budget.allocations[0].remaining))
        // The household share is spent once, not double-counted.
        assertEquals(0, BigDecimal.ZERO.compareTo(budget.householdRemaining))
    }

    @Test
    fun anUnevenSplitLosesNoCentToRounding() {
        val budget = familyBudget(
            state = state(budget = "30.00", householdTier = "10.00"),
            limits = listOf(limit("m1", "6.66"), limit("m2", "6.67"), limit("m3", "6.67")),
            spentByMember = emptyList(),
            householdSpent = BigDecimal("10.00"),
        )

        val shares = budget.allocations.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.shareOfHousehold) }
        assertEquals(0, BigDecimal("10.00").compareTo(shares))
    }

    @Test
    fun proportionalSplitShieldsAMemberWhoAllocatedNothing() {
        val budget = familyBudget(
            state = state(budget = "20000.00", householdTier = "10000.00", rule = CommonSplitRule.PROPORTIONAL),
            limits = listOf(limit("m1", "10000.00"), limit("m2", "10000.00"), limit("m3", "0.00")),
            spentByMember = emptyList(),
            householdSpent = BigDecimal("10000.00"),
        )

        assertEquals(BigDecimal("0.00"), budget.allocations[2].shareOfHousehold)
        assertEquals(BigDecimal("5000.00"), budget.allocations[0].shareOfHousehold)
    }

    @Test
    fun anUnknownSplitRuleFallsBackToEqualRatherThanThrowing() {
        val stored = state().copy(commonSplitRule = "PROPORTIONAL_BY_FAMILY_TREE")

        assertEquals(CommonSplitRule.EQUAL, stored.splitRule)
    }

    @Test
    fun aHouseholdMayOverspendAndTheRemainingFigureSaysSo() {
        val budget = familyBudget(
            state = state(budget = "30000.00"),
            limits = listOf(limit("m1", "30000.00")),
            spentByMember = listOf(spent("m1", "31000.00")),
            householdSpent = BigDecimal.ZERO,
        )

        assertEquals(0, BigDecimal("-1000.00").compareTo(budget.allocations[0].remaining))
        assertEquals(0, BigDecimal("-1000.00").compareTo(budget.remaining))
    }

    @Test
    fun noAllocationsMeansNoMembersAndNoShareOfAnything() {
        val budget = familyBudget(
            state = state(budget = "30000.00", householdTier = "10000.00"),
            limits = emptyList(),
            spentByMember = emptyList(),
            householdSpent = BigDecimal("10000.00"),
        )

        assertEquals(0, BigDecimal.ZERO.compareTo(budget.allocated))
        assertEquals(0, BigDecimal.ZERO.compareTo(budget.memberSpent))
        assertNull(budget.allocations.firstOrNull())
    }

    @Test
    fun progressIsClampedAndSurvivesAZeroLengthPeriod() {
        assertEquals(0, BigDecimal.ONE.compareTo(periodProgress(0L, 0L, 0L)))
        assertEquals(0, BigDecimal.ZERO.compareTo(periodProgress(0L, 100L, -5L)))
        assertEquals(0, BigDecimal.ONE.compareTo(periodProgress(0L, 100L, 500L)))
        assertEquals(0, BigDecimal("0.5000").compareTo(periodProgress(0L, 100L, 50L)))
    }

    @Test
    fun thePoolKeyIsDerivedFromTheStartSoBothPeriodsAgree() {
        assertEquals("pool_1760000000000", poolPeriodId(1_760_000_000_000L))
    }
}

class AllocationValidationTest {

    private val limits = listOf(limit("m1", "13000.00"), limit("m2", "10000.00"), limit("m3", "7000.00"))

    @Test
    fun anExactSplitIsAccepted() {
        assertNull(validateAllocations(limits, BigDecimal("30000.00"), 3))
    }

    @Test
    fun overAllocatingIsRefused() {
        assertEquals(
            AllocationProblem.OVER_ALLOCATED,
            validateAllocations(limits, BigDecimal("29000.00"), 3),
        )
    }

    @Test
    fun underAllocatingIsRefusedRatherThanLeftPartial() {
        assertEquals(
            AllocationProblem.UNDER_ALLOCATED,
            validateAllocations(limits, BigDecimal("31000.00"), 3),
        )
    }

    @Test
    fun aNegativeSliceIsRefused() {
        assertEquals(
            AllocationProblem.NEGATIVE_ALLOCATION,
            validateAllocations(listOf(limit("m1", "-1.00"), limit("m2", "31000.00")), BigDecimal("31000.00"), 2),
        )
    }

    @Test
    fun aFamilyWithNoMembersIsRefused() {
        assertEquals(AllocationProblem.NO_ALLOCATION, validateAllocations(emptyList(), BigDecimal.ZERO, 0))
    }

    /**
     * A shortfall of less than a paisa is accepted, on purpose.
     *
     * The alternative is refusing to save because two people typed 9999.999 and 10000.00 into a
     * 20000.00 pool. A fraction of a paisa is not a real discrepancy, and blocking it would teach the
     * head that the editor is fussy rather than that it is careful. A genuine shortfall — even one of a
     * single paisa — still fails, which `aWholePaisaShortIsStillShort` covers.
     */
    @Test
    fun aSubPaisaShortfallIsRoundedAway() {
        assertNull(
            validateAllocations(
                listOf(limit("m1", "10000.00"), limit("m2", "9999.999")),
                BigDecimal("20000.00"),
                2,
            )
        )
    }

    @Test
    fun aWholePaisaShortIsStillShort() {
        assertEquals(
            AllocationProblem.UNDER_ALLOCATED,
            validateAllocations(
                listOf(limit("m1", "10000.00"), limit("m2", "9999.99")),
                BigDecimal("20000.00"),
                2,
            )
        )
    }
}