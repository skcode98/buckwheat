package com.danilkinkin.buckwheat.family

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of these is not that the summary is well written. It is that no member's name can reach
 * the AI provider, which is the one place in this feature where data leaves a device the user is
 * holding.
 */
class FamilySnapshotPrivacyTest {

    private val realNames = listOf("Ananya", "Bilal", "Chidi", "Divya")

    private fun budget() = FamilyBudget(
        total = BigDecimal("30000.00"),
        householdTier = BigDecimal("9000.00"),
        memberTier = BigDecimal("21000.00"),
        householdSpent = BigDecimal("9000.00"),
        householdRemaining = BigDecimal("0.00"),
        allocations = listOf(
            MemberAllocation("m1", BigDecimal("13000.00"), BigDecimal("9000.00"), BigDecimal("2250.00"), BigDecimal("1750.00")),
            MemberAllocation("m2", BigDecimal("8000.00"), BigDecimal("8000.00"), BigDecimal("2250.00"), BigDecimal("-2250.00")),
        ),
    )

    private fun snapshot() = buildFamilySnapshot(
        budget = budget(),
        memberIdsInRosterOrder = listOf("m1", "m2"),
        tags = mapOf("m1" to MemberTag.SUPER_SAVER, "m2" to MemberTag.OVER_PLAN),
    )

    @Test
    fun noDisplayNameAppearsInTheSnapshot() {
        val text = snapshot().lines.joinToString("\n")

        realNames.forEach { name ->
            assertFalse("\"$name\" reached the snapshot", text.contains(name, ignoreCase = true))
        }
    }

    @Test
    fun noDisplayNameAppearsInThePrompt() {
        val prompt = familySummaryPrompt(snapshot())

        realNames.forEach { name ->
            assertFalse("\"$name\" reached the prompt", prompt.contains(name, ignoreCase = true))
        }
    }

    @Test
    fun noMemberIdReachesThePromptEither() {
        val prompt = familySummaryPrompt(snapshot())

        assertFalse(prompt.contains("m1"))
        assertFalse(prompt.contains("m2"))
    }

    @Test
    fun theOfflineSummaryIsJustAsAnonymous() {
        val offline = offlineFamilySummary(snapshot())

        realNames.forEach { name ->
            assertFalse("\"$name\" reached the offline summary", offline.contains(name, ignoreCase = true))
        }
        assertTrue(offline.isNotBlank())
    }

    @Test
    fun peopleAppearAsLettersInstead() {
        val text = snapshot().lines.joinToString("\n")

        assertTrue(text.contains("Member A"))
        assertTrue(text.contains("Member B"))
    }

    /**
 * Pins the mapping itself, which is the part that can actually break.
 *
 * An earlier version of this test called the builder twice with identical arguments and asserted the
 * two results were equal. It passed for any implementation whatsoever, including one that returned a
 * random label every time: the function is pure, so identical inputs are identical outputs no matter
 * what the labelling logic says. Asserting the specific id-to-letter mapping is what can fail when the
 * logic changes.
 */
@Test
    fun aMemberIsMappedByTheirPositionInTheRoster() {
        val text = snapshot().lines.joinToString("\n")

        // m1 is first in the roster, m2 second.
        assertTrue(text.contains("Member A was given 13000.00"))
        assertTrue(text.contains("Member B was given 8000.00"))
    }

    /**
     * A roster change CAN relabel somebody, and that is a real cost of deriving the label from
     * position rather than storing it.
     *
     * Recorded here deliberately rather than left for someone to discover from a report that suddenly
     * describes last month's figures differently. A label that never moved would need persisting, and
     * persisting it means the server holding one more thing per member per family forever.
     */
@Test
    fun removingAMemberEarlierInTheRosterRelabelsTheOnesAfterThem() {
        val before = buildFamilySnapshot(budget(), listOf("m1", "m2"))
        val after = buildFamilySnapshot(budget(), listOf("m2"))

        assertTrue(before.lines.any { it.startsWith("Member B was given 8000.00") })
        assertTrue(after.lines.any { it.startsWith("Member A was given 8000.00") })
    }

    @Test
    fun thePromptTellsTheModelNotToGuess() {
        val prompt = familySummaryPrompt(snapshot())

        assertTrue(prompt.contains("Do not guess"))
        assertTrue(prompt.contains("Do not comment on anyone's character"))
    }
}

/** The deterministic fallback has to be real content, not a placeholder. */
class OfflineFamilySummaryTest {

    private fun budget(spentByMember: Map<String, String>) = FamilyBudget(
        total = BigDecimal("30000.00"),
        householdTier = BigDecimal("9000.00"),
        memberTier = BigDecimal("21000.00"),
        householdSpent = BigDecimal("9000.00"),
        householdRemaining = BigDecimal("0.00"),
        allocations = spentByMember.map { (id, spent) ->
            MemberAllocation(id, BigDecimal("7000.00"), BigDecimal(spent), BigDecimal("2250.00"), BigDecimal("4750.00"))
        },
    )

    @Test
    fun itReportsTheSharedSpendAndWhatIsLeft() {
        val text = offlineFamilySummary(
            buildFamilySnapshot(budget(mapOf("m1" to "1000.00")), listOf("m1"))
        )

        assertTrue(text.contains("9000.00"))
        // 30000 pool - 9000 shared - 1000 spent by the member. Both spend buckets come off, which is
        // the part worth asserting: a remaining figure that ignored the members would read as though
        // nothing had been spent at all.
        assertTrue(text.contains("20000.00"))
    }

    @Test
    fun itWorksWithNobodyInTheFamily() {
        val text = offlineFamilySummary(
            buildFamilySnapshot(budget(emptyMap()), emptyList())
        )

        assertTrue(text.isNotBlank())
        assertTrue(text.contains("0 members"))
    }

    @Test
    fun aRosterLongerThanTheAlphabetStillLabelsEveryoneDistinctly() {
        val ids = (1..30).map { "member-$it" }
        val pseudonyms = familyPseudonyms(ids)

        assertEquals(30, pseudonyms.size)
        assertEquals(30, pseudonyms.values.toSet().size)
    }
}
