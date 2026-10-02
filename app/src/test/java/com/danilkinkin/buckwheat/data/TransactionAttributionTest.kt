package com.danilkinkin.buckwheat.data

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.attributedTo
import java.math.BigDecimal
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun spend(memberId: String? = null) = Transaction(
    type = TransactionType.SPENT,
    value = BigDecimal("12.50"),
    date = Date(),
    comment = "coffee",
    memberId = memberId,
)

class TransactionAttributionTest {

    @Test
    fun anUnattributedRowTakesTheMemberItIsGiven() {
        assertEquals("member-1", spend().attributedTo("member-1").memberId)
    }

    @Test
    fun aNullMemberLeavesTheRowAlone() {
        assertNull(spend().attributedTo(null).memberId)
    }

    @Test
    fun anAlreadyAttributedRowKeepsItsMember() {
        assertEquals("member-2", spend("member-2").attributedTo("member-1").memberId)
    }

    @Test
    fun attributionIsTheOnlyFieldItTouches() {
        val original = spend()

        assertEquals(original.copy(memberId = "member-1"), original.attributedTo("member-1"))
    }
}