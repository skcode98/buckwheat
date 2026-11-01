package com.danilkinkin.buckwheat.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleStateTest {

    @Test
    fun `idle and added accept taps, busy states do not`() {
        assertTrue(acceptsTap(BubbleFeedbackState.IDLE))
        assertTrue(acceptsTap(BubbleFeedbackState.ADDED))
        assertFalse(acceptsTap(BubbleFeedbackState.LISTENING))
        assertFalse(acceptsTap(BubbleFeedbackState.PROCESSING))
        assertFalse(acceptsTap(BubbleFeedbackState.ERROR))
    }

    @Test
    fun `added and error auto-reset after 4 seconds`() {
        assertEquals(4_000L, autoResetDelayMs(BubbleFeedbackState.ADDED))
        assertEquals(4_000L, autoResetDelayMs(BubbleFeedbackState.ERROR))
        assertNull(autoResetDelayMs(BubbleFeedbackState.IDLE))
        assertNull(autoResetDelayMs(BubbleFeedbackState.LISTENING))
        assertNull(autoResetDelayMs(BubbleFeedbackState.PROCESSING))
    }
}
