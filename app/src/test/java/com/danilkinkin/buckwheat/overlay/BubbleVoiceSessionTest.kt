package com.danilkinkin.buckwheat.overlay

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeHost(
    private val budget: Boolean = true,
    private val outcome: BubbleRecognitionOutcome = BubbleRecognitionOutcome("150 tea", 0),
    private val transactions: List<Transaction> = listOf(
        Transaction(type = TransactionType.SPENT, value = BigDecimal("150"), date = Date(), comment = "tea"),
    ),
    private val commitFails: Boolean = false,
) : BubbleSessionHost {
    val committed = mutableListOf<List<Transaction>>()
    val states = mutableListOf<BubbleUiState>()

    override suspend fun hasBudget() = budget
    override suspend fun recognize() = outcome
    override suspend fun toTransactions(transcript: String) = transactions
    override suspend fun commit(transactions: List<Transaction>) {
        if (commitFails) throw RuntimeException("db down")
        committed += transactions
    }

    override fun captionNoBudget() = "no budget"
    override fun captionRecognitionError(errorCode: Int) = "error $errorCode"
    override fun captionParseFailed() = "parse failed"
    override fun captionSaveFailed() = "save failed"
    override fun captionAdded(transactions: List<Transaction>) = "added ${transactions.size}"
}

class BubbleVoiceSessionTest {

    @Test
    fun `successful session goes listening to processing to added`() = runTest {
        val host = FakeHost()
        val states = mutableListOf<BubbleUiState>()
        runVoiceSession(host) { states += it }
        assertEquals(
            listOf(
                BubbleUiState(BubbleFeedbackState.LISTENING),
                BubbleUiState(BubbleFeedbackState.PROCESSING),
                BubbleUiState(BubbleFeedbackState.ADDED, "added 1"),
            ),
            states,
        )
        assertEquals(1, host.committed.size)
    }

    @Test
    fun `no budget ends in error without listening`() = runTest {
        val states = mutableListOf<BubbleUiState>()
        runVoiceSession(FakeHost(budget = false)) { states += it }
        assertEquals(
            listOf(BubbleUiState(BubbleFeedbackState.ERROR, "no budget")),
            states,
        )
    }

    @Test
    fun `recognition error maps to error state with code`() = runTest {
        val states = mutableListOf<BubbleUiState>()
        runVoiceSession(
            FakeHost(outcome = BubbleRecognitionOutcome(transcript = null, errorCode = 7)),
        ) { states += it }
        assertEquals(
            listOf(
                BubbleUiState(BubbleFeedbackState.LISTENING),
                BubbleUiState(BubbleFeedbackState.ERROR, "error 7"),
            ),
            states,
        )
    }

    @Test
    fun `unparseable transcript ends in error and commits nothing`() = runTest {
        val states = mutableListOf<BubbleUiState>()
        val host = FakeHost(transactions = emptyList())
        runVoiceSession(host) { states += it }
        assertEquals(
            listOf(
                BubbleUiState(BubbleFeedbackState.LISTENING),
                BubbleUiState(BubbleFeedbackState.PROCESSING),
                BubbleUiState(BubbleFeedbackState.ERROR, "parse failed"),
            ),
            states,
        )
        assertTrue(host.committed.isEmpty())
    }

    @Test
    fun `commit failure surfaces save failed error`() = runTest {
        val states = mutableListOf<BubbleUiState>()
        runVoiceSession(FakeHost(commitFails = true)) { states += it }
        assertEquals(
            BubbleUiState(BubbleFeedbackState.ERROR, "save failed"),
            states.last(),
        )
    }
}
