package com.danilkinkin.buckwheat.overlay

enum class BubbleFeedbackState { IDLE, LISTENING, PROCESSING, ADDED, ERROR }

data class BubbleUiState(val state: BubbleFeedbackState, val caption: String? = null)

// Taps start a new voice session only from a settled state. ADDED re-taps begin a fresh
// session immediately (widget behavior); busy and error states ignore taps.
fun acceptsTap(state: BubbleFeedbackState): Boolean =
    state == BubbleFeedbackState.IDLE || state == BubbleFeedbackState.ADDED

// Transient feedback states return to IDLE on their own; session states never auto-reset.
fun autoResetDelayMs(state: BubbleFeedbackState): Long? = when (state) {
    BubbleFeedbackState.ADDED, BubbleFeedbackState.ERROR -> 4_000L
    else -> null
}
