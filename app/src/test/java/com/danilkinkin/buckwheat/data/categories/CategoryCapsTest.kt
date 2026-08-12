package com.danilkinkin.buckwheat.data.categories

import com.danilkinkin.buckwheat.di.parseCategoryCapNotified
import com.danilkinkin.buckwheat.di.parseCategoryCaps
import com.danilkinkin.buckwheat.di.serializeCategoryCapNotified
import com.danilkinkin.buckwheat.di.serializeCategoryCaps
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class CategoryCapsTest {

    @Test
    fun `caps codec round-trips`() {
        val caps = mapOf(
            "FOOD" to BigDecimal("5000"),
            "SHOPPING" to BigDecimal("2500.50"),
            "rent" to BigDecimal("12000"),
        )
        assertEquals(caps, parseCategoryCaps(serializeCategoryCaps(caps)))
    }

    @Test
    fun `caps codec drops zero and negative caps`() {
        val caps = mapOf(
            "FOOD" to BigDecimal("5000"),
            "HEALTH" to BigDecimal.ZERO,
            "BILLS" to BigDecimal("-100"),
        )
        assertEquals(mapOf("FOOD" to BigDecimal("5000")), parseCategoryCaps(serializeCategoryCaps(caps)))
    }

    @Test
    fun `caps codec handles empty and null`() {
        assertEquals(emptyMap<String, BigDecimal>(), parseCategoryCaps(null))
        assertEquals(emptyMap<String, BigDecimal>(), parseCategoryCaps(""))
        assertEquals(emptyMap<String, BigDecimal>(), parseCategoryCaps(serializeCategoryCaps(emptyMap())))
    }

    @Test
    fun `caps codec skips malformed entries`() {
        assertEquals(
            mapOf("FOOD" to BigDecimal("5000")),
            parseCategoryCaps("FOOD:5000;garbage;HEALTH:not-a-number;:100;TRAVEL:"),
        )
    }

    @Test
    fun `notified codec round-trips and drops zero buckets`() {
        val notified = mapOf("FOOD" to 2, "SHOPPING" to 1)
        assertEquals(notified, parseCategoryCapNotified(serializeCategoryCapNotified(notified)))
        assertEquals(
            emptyMap<String, Int>(),
            parseCategoryCapNotified(serializeCategoryCapNotified(mapOf("FOOD" to 0))),
        )
        assertEquals(emptyMap<String, Int>(), parseCategoryCapNotified(null))
        assertEquals(
            mapOf("FOOD" to 1),
            parseCategoryCapNotified("FOOD:1;BAD;X:2:3;EMPTY:"),
        )
    }

    @Test
    fun `percent is floored and clamped`() {
        assertEquals(0, categoryCapPercent(BigDecimal.ZERO, BigDecimal("100")))
        assertEquals(0, categoryCapPercent(BigDecimal.ZERO, BigDecimal.ZERO))
        assertEquals(50, categoryCapPercent(BigDecimal("50.0"), BigDecimal("100")))
        assertEquals(49, categoryCapPercent(BigDecimal("49.99"), BigDecimal("100")))
        assertEquals(100, categoryCapPercent(BigDecimal("150"), BigDecimal("100")))
        assertEquals(100, categoryCapPercent(BigDecimal("100.5"), BigDecimal("100")))
    }

    @Test
    fun `bucket levels are 0 below 80, 1 at 80, 2 at 100`() {
        val cap = BigDecimal("100")
        assertEquals(0, categoryCapBucket(BigDecimal.ZERO, cap))
        assertEquals(0, categoryCapBucket(BigDecimal("79.99"), cap))
        assertEquals(1, categoryCapBucket(BigDecimal("80.00"), cap))
        assertEquals(1, categoryCapBucket(BigDecimal("99.99"), cap))
        assertEquals(2, categoryCapBucket(BigDecimal("100.00"), cap))
        assertEquals(2, categoryCapBucket(BigDecimal("250.00"), cap))
        assertEquals(0, categoryCapBucket(BigDecimal("50"), BigDecimal.ZERO))
    }

    @Test
    fun `battery fraction is clamped to the cap`() {
        assertEquals(0f, categoryBatteryFraction(BigDecimal.ZERO, BigDecimal("100")))
        assertEquals(0f, categoryBatteryFraction(BigDecimal.ZERO, BigDecimal.ZERO))
        assertEquals(0f, categoryBatteryFraction(BigDecimal("50"), BigDecimal.ZERO))
        assertEquals(0.5f, categoryBatteryFraction(BigDecimal("50"), BigDecimal("100")))
        assertEquals(0.25f, categoryBatteryFraction(BigDecimal("25"), BigDecimal("100")))
        assertEquals(1f, categoryBatteryFraction(BigDecimal("100"), BigDecimal("100")))
        assertEquals(1f, categoryBatteryFraction(BigDecimal("250"), BigDecimal("100")))
        assertEquals(1f, categoryBatteryFraction(BigDecimal("100.5"), BigDecimal("100")))
    }

    @Test
    fun `only a newly reached level announces`() {
        assertEquals(0, highestNewlyReachedCapBucket(0, 0))
        assertEquals(1, highestNewlyReachedCapBucket(0, 1))
        assertEquals(0, highestNewlyReachedCapBucket(1, 1))
        assertEquals(2, highestNewlyReachedCapBucket(0, 2))
        assertEquals(2, highestNewlyReachedCapBucket(1, 2))
        assertEquals(0, highestNewlyReachedCapBucket(2, 2))
    }

    @Test
    fun `a single spend crossing both levels announces only the cap`() {
        // Jumping straight from "nothing" to "reached" announces the 100% level.
        assertEquals(CATEGORY_CAP_REACHED_BUCKET, highestNewlyReachedCapBucket(0, 2))
    }
}
