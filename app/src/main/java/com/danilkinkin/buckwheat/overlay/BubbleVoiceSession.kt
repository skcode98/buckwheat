package com.danilkinkin.buckwheat.overlay

import android.speech.SpeechRecognizer
import com.danilkinkin.buckwheat.data.entities.Transaction

data class BubbleRecognitionOutcome(val transcript: String?, val errorCode: Int)

// Everything the session needs from the Android world, so the pipeline is unit-testable
// with fakes. The service implements this with the real SpeechRecognizer / repository /
// parser stack (reusing the widget's helpers, never modifying them).
interface BubbleSessionHost {
    suspend fun hasBudget(): Boolean
    suspend fun recognize(): BubbleRecognitionOutcome
    suspend fun toTransactions(transcript: String): List<Transaction>
    suspend fun commit(transactions: List<Transaction>)
    fun captionNoBudget(): String
    fun captionRecognitionError(errorCode: Int): String
    fun captionParseFailed(): String
    fun captionSaveFailed(): String
    fun captionAdded(transactions: List<Transaction>): String
}

class BubbleSessionBusyException : Exception("voice session already running")

// One full "listen -> parse -> commit" pass. Emits BubbleUiState values through onState:
// LISTENING -> (PROCESSING -> ADDED | ERROR) or LISTENING -> ERROR. The caller (service)
// owns timing, the recognizer lifecycle, and the reset-to-IDLE timer.
suspend fun runVoiceSession(host: BubbleSessionHost, onState: (BubbleUiState) -> Unit) {
    if (!host.hasBudget()) {
        onState(BubbleUiState(BubbleFeedbackState.ERROR, host.captionNoBudget()))
        return
    }

    onState(BubbleUiState(BubbleFeedbackState.LISTENING))
    val outcome = host.recognize()
    val transcript = outcome.transcript
    if (transcript == null) {
        onState(BubbleUiState(BubbleFeedbackState.ERROR, host.captionRecognitionError(outcome.errorCode)))
        return
    }

    onState(BubbleUiState(BubbleFeedbackState.PROCESSING))
    val transactions = host.toTransactions(transcript)
    if (transactions.isEmpty()) {
        onState(BubbleUiState(BubbleFeedbackState.ERROR, host.captionParseFailed()))
        return
    }

    try {
        host.commit(transactions)
    } catch (e: Exception) {
        onState(BubbleUiState(BubbleFeedbackState.ERROR, host.captionSaveFailed()))
        return
    }
    onState(BubbleUiState(BubbleFeedbackState.ADDED, host.captionAdded(transactions)))
}
