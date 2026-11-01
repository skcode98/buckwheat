package com.danilkinkin.buckwheat.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePositionTest {

    @Test
    fun `snaps to left edge when nearer left`() {
        assertEquals(8f, snapX(currentX = 10f, screenWidth = 1000f, bubbleWidth = 100f, marginPx = 8f))
    }

    @Test
    fun `snaps to right edge when nearer right`() {
        assertEquals(
            892f,
            snapX(currentX = 800f, screenWidth = 1000f, bubbleWidth = 100f, marginPx = 8f),
        )
    }

    @Test
    fun `clamps y inside usable screen area`() {
        assertEquals(
            100f,
            clampY(currentY = -50f, screenHeight = 2000f, bubbleHeight = 100f, topInsetPx = 100f, bottomInsetPx = 100f),
        )
        assertEquals(
            1800f,
            clampY(currentY = 5000f, screenHeight = 2000f, bubbleHeight = 100f, topInsetPx = 100f, bottomInsetPx = 100f),
        )
        assertEquals(
            500f,
            clampY(currentY = 500f, screenHeight = 2000f, bubbleHeight = 100f, topInsetPx = 100f, bottomInsetPx = 100f),
        )
    }

    @Test
    fun `fling dismiss only on fast downward fling`() {
        assertTrue(isFlingDismiss(velocityY = 5000f, thresholdPx = 3000f))
        assertFalse(isFlingDismiss(velocityY = 1000f, thresholdPx = 3000f))
        assertFalse(isFlingDismiss(velocityY = -5000f, thresholdPx = 3000f))
    }

    @Test
    fun `dismiss target hit test`() {
        assertTrue(
            isOverDismissTarget(150f, 1900f, targetLeft = 100f, targetTop = 1850f, targetRight = 200f, targetBottom = 1950f),
        )
        assertFalse(
            isOverDismissTarget(50f, 50f, targetLeft = 100f, targetTop = 1850f, targetRight = 200f, targetBottom = 1950f),
        )
    }
}
