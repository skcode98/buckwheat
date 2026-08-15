package com.danilkinkin.buckwheat.analytics.categoriesChart

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class DonutChartTest {
    private fun usage(amount: String) = TagUsage(name = "x", amount = BigDecimal(amount))

    @Test
    fun emptyItemsProduceNoSlices() {
        assertEquals(emptyList<Float>(), donutItemAngles(emptyList()))
    }

    @Test
    fun zeroTotalFallsBackToEqualSlices() {
        val angles = donutItemAngles(List(4) { usage("0") })
        assertEquals(4, angles.size)
        angles.forEach { assertEquals(90f, it, 0.001f) }
    }

    @Test
    fun singleItemGetsFullCircle() {
        assertEquals(listOf(360f), donutItemAngles(listOf(usage("100"))))
    }

    @Test
    fun proportionateSlicesSumToFullCircle() {
        val angles = donutItemAngles(listOf(usage("25"), usage("75")))
        assertEquals(90f, angles[0], 0.001f)
        assertEquals(270f, angles[1], 0.001f)
    }

    @Test
    fun tinySlicesGetPaddedAndLargeOnesRedistribute() {
        val angles = donutItemAngles(listOf(usage("1"), usage("99")))
        assertEquals(28f, angles[0], 0.001f)
        assertEquals(332f, angles[1], 0.001f)
    }
}
