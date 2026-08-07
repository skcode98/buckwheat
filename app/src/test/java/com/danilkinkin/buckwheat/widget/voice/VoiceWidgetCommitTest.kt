package com.danilkinkin.buckwheat.widget.voice

import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.keyboard.VoiceInputResult
import java.math.BigDecimal
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceWidgetCommitTest {

    @Test
    fun `valid results become expense transactions`() {
        val date = Date(1_700_000_000_000L)
        val transactions = voiceResultsToTransactions(
            listOf(
                VoiceInputResult(amount = "150", comment = "tea", date = date),
                VoiceInputResult(amount = "45,50", comment = "bus", date = date),
            )
        )

        assertEquals(2, transactions.size)
        assertEquals(TransactionType.SPENT, transactions[0].type)
        assertEquals(BigDecimal("150"), transactions[0].value)
        assertEquals("tea", transactions[0].comment)
        assertEquals(date, transactions[0].date)
        assertEquals(BigDecimal("45.50"), transactions[1].value)
    }

    @Test
    fun `unparseable and non-positive amounts are skipped`() {
        val date = Date()
        val transactions = voiceResultsToTransactions(
            listOf(
                VoiceInputResult(amount = "abc", comment = "junk", date = date),
                VoiceInputResult(amount = "0", comment = "zero", date = date),
                VoiceInputResult(amount = "-5", comment = "negative", date = date),
                VoiceInputResult(amount = "10", comment = "ok", date = date),
            )
        )

        assertEquals(1, transactions.size)
        assertEquals(BigDecimal("10"), transactions[0].value)
        assertEquals("ok", transactions[0].comment)
    }

    @Test
    fun `empty input yields empty transactions`() {
        assertTrue(voiceResultsToTransactions(emptyList()).isEmpty())
    }
}
