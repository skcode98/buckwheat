package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.toTransaction
import com.danilkinkin.buckwheat.history.categoryLabelFor
import java.math.BigDecimal
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeriodDetailLabelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun labelOf(transaction: ArchivedTransaction): String =
        archivedSpendLabel(
            transaction.comment,
            categoryLabelFor(context, transaction.toTransaction(), emptyMap())?.second,
        )

    @Test
    fun blankCommentSpend_fallsBackToCategoryName() {
        val transaction = ArchivedTransaction(1, TransactionType.SPENT, BigDecimal("20"), Date(), "")
        assertTrue(labelOf(transaction).isNotBlank())
    }

    @Test
    fun commentedSpend_keepsItsComment() {
        val transaction = ArchivedTransaction(1, TransactionType.SPENT, BigDecimal("20"), Date(), "lunch")
        assertEquals("lunch", labelOf(transaction))
    }
}
