package com.danilkinkin.buckwheat.util

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothPathTest {
    @Test
    fun emptyAndSinglePointProduceNoSegments() {
        assertEquals(emptyList<SmoothSegment>(), smoothSegments(emptyList()))
        assertEquals(emptyList<SmoothSegment>(), smoothSegments(listOf(Offset(10f, 10f))))
    }

    @Test
    fun producesOneSegmentPerIntervalEndingAtEachPoint() {
        val points = listOf(
            Offset(0f, 0f),
            Offset(10f, 10f),
            Offset(20f, 20f),
            Offset(30f, 30f),
        )

        val segments = smoothSegments(points)

        assertEquals(3, segments.size)
        assertEquals(listOf(Offset(10f, 10f), Offset(20f, 20f), Offset(30f, 30f)), segments.map { it.end })
    }

    @Test
    fun plateauOvershootIsClampedToTheDataExtent() {
        // High plateau in the middle: bottom, top, top, bottom (smaller y = higher).
        val points = listOf(
            Offset(10f, 100f),
            Offset(40f, 0f),
            Offset(70f, 0f),
            Offset(100f, 100f),
        )

        // Guard: the raw Catmull-Rom control point would overshoot above the data's top.
        val rawControlY = 0f - (100f - 0f) / 6f
        assertTrue("raw control y $rawControlY should overshoot", rawControlY < 0f)

        val segments = smoothSegments(points)

        assertTrue(segments.all { it.control1.y in 0f..100f })
        assertTrue(segments.all { it.control2.y in 0f..100f })

        val plateauSegment = segments.first { it.end == Offset(70f, 0f) }
        assertEquals(0f, plateauSegment.control1.y, 0.001f)
        assertEquals(0f, plateauSegment.control2.y, 0.001f)
    }

    @Test
    fun monotoneRiseKeepsControlPointsInsideTheDataExtent() {
        val points = listOf(
            Offset(0f, 80f),
            Offset(10f, 60f),
            Offset(20f, 30f),
            Offset(30f, 10f),
        )

        val segments = smoothSegments(points)

        assertTrue(segments.all { it.control1.y in 10f..80f })
        assertTrue(segments.all { it.control2.y in 10f..80f })
    }
}
