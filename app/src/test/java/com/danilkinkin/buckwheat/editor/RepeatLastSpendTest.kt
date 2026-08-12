package com.danilkinkin.buckwheat.editor

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatLastSpendTest {
    private fun date(day: Int, month: Int = 8, year: Int = 2026): Date =
        LocalDate.of(year, month, day).toDate()

    private fun tx(type: TransactionType, day: Int, value: String, uid: Int = 0): Transaction =
        Transaction(
            type = type,
            value = BigDecimal(value),
            date = date(day),
        ).also { it.uid = uid }

    @Test
    fun returnsNullForEmptyList() {
        assertNull(lastSpendToRepeat(emptyList()))
    }

    @Test
    fun ignoresNonSpentRows() {
        val result = lastSpendToRepeat(
            listOf(
                tx(TransactionType.SET_DAILY_BUDGET, 5, "500"),
                tx(TransactionType.INCOME, 6, "1000"),
            )
        )
        assertNull(result)
    }

    @Test
    fun returnsTheMostRecentSpent() {
        val result = lastSpendToRepeat(
            listOf(
                tx(TransactionType.SPENT, 3, "50", uid = 1),
                tx(TransactionType.SPENT, 9, "200", uid = 2),
                tx(TransactionType.SPENT, 5, "75", uid = 3),
                tx(TransactionType.SET_DAILY_BUDGET, 10, "500", uid = 4),
            )
        )
        assertEquals(BigDecimal("200"), result?.value)
    }

    @Test
    fun returnsLaterRowOnTieUsingUid() {
        val result = lastSpendToRepeat(
            listOf(
                tx(TransactionType.SPENT, 5, "75", uid = 1),
                tx(TransactionType.SPENT, 5, "100", uid = 5),
            )
        )
        assertEquals(BigDecimal("100"), result?.value)
    }

    @Test
    fun preservesCommentAndCategory() {
        val result = lastSpendToRepeat(
            listOf(
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal("120.00"),
                    date = date(4),
                    comment = "lunch",
                    category = "FOOD",
                )
            )
        )
        assertEquals("lunch", result?.comment)
        assertEquals("FOOD", result?.category)
        assertTrue(result!!.value.compareTo(BigDecimal("120.00")) == 0)
    }
}
