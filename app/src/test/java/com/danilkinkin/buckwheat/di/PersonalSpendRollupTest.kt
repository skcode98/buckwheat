package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.entities.SpendBucket
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one defence against a double-counted ledger, tested directly.
 *
 * While this filter lived inside a private method of `SpendsRepository`, nothing could assert it: the
 * tests could only check that a household row *has* a bucket, never that the row is actually kept out
 * of a personal total. So the single most consequential line in the feature had no test behind it, and
 * the rent was counted against the head as well as in the household total on every device.
 *
 * These call the real functions. No database, no Android context, no repository instance.
 */
class PersonalSpendRollupTest {

    private fun spend(
        value: String,
        memberId: String?,
        bucket: String = SpendBucket.MEMBER.name,
    ) = Transaction(
        type = TransactionType.SPENT,
        value = BigDecimal(value),
        date = java.util.Date(1_760_000_000_000L),
        comment = "x",
        memberId = memberId,
        bucket = bucket,
    )

    @Test
    fun aHouseholdRowIsKeptOutOfThePersonalSet() {
        val rollup = rollupPersonalSpend(
            listOf(
                spend("2500.00", "member-1", SpendBucket.HOUSEHOLD.name),
                spend("100.00", "member-1"),
            ),
            emptyMap(),
        )

        assertEquals(1, rollup.size)
        assertEquals(0, BigDecimal("100.00").compareTo(rollup.single().total))
    }

    @Test
    fun theRentIsNotCountedAgainstTheHeadWhoRecordedIt() {
        // The server stamps member_id from the caller, so a household expense arrives carrying the
        // head's id. Before the filter, this produced head=2600 for a 2500 rent and a 100 coffee.
        val rollup = rollupPersonalSpend(
            listOf(
                spend("2500.00", "member-1", SpendBucket.HOUSEHOLD.name),
                spend("100.00", "member-1"),
            ),
            mapOf("member-1" to "Head"),
        )

        assertEquals(1, rollup.size)
        assertEquals(0, BigDecimal("100.00").compareTo(rollup.single().total))
        assertEquals("Head", rollup.single().displayName)
    }

    @Test
    fun theTwoTotalsCannotOverlap() {
        val rows = listOf(
            spend("2500.00", "member-1", SpendBucket.HOUSEHOLD.name),
            spend("400.00", "member-2"),
            spend("100.00", "member-1"),
        )

        val personal = rollupPersonalSpend(rows, emptyMap())
            .fold(BigDecimal.ZERO) { acc, member -> acc.add(member.total) }
        val household = rows.filter { it.bucket == SpendBucket.HOUSEHOLD.name }
            .fold(BigDecimal.ZERO) { acc, row -> acc.add(row.value) }

        assertEquals(0, BigDecimal("500.00").compareTo(personal))
        assertEquals(0, BigDecimal("2500.00").compareTo(household))
        // 500 personal + 2500 household == 3000, the whole window, once each.
        assertEquals(0, BigDecimal("3000.00").compareTo(personal.add(household)))
    }

    @Test
    fun unattributedRowsSurviveAsTheirOwnBucket() {
        // Rows from before enrolment have no member and are not household money, so they must not be
        // dropped -- the rollup still has to add up to the personal total for the window.
        val rollup = rollupPersonalSpend(listOf(spend("70.00", null)), emptyMap())

        assertEquals(1, rollup.size)
        assertEquals(null, rollup.single().memberId)
        assertEquals(0, BigDecimal("70.00").compareTo(rollup.single().total))
    }

    @Test
    fun theUnattributedBucketSortsLast() {
        val rollup = rollupPersonalSpend(
            listOf(
                spend("10.00", null),
                spend("900.00", "member-1"),
                spend("5.00", "member-2"),
            ),
            emptyMap(),
        )

        assertEquals("member-1", rollup[0].memberId)
        assertEquals("member-2", rollup[1].memberId)
        assertEquals(null, rollup[2].memberId)
    }
}
