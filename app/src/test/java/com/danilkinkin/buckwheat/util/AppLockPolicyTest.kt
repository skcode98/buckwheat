package com.danilkinkin.buckwheat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun noAttemptsMeansNoLockout() {
        assertEquals(0L, appLockLockoutMillis(0))
        assertEquals(0L, appLockLockoutMillis(-1))
    }

    @Test
    fun lockoutEscalatesExponentially() {
        assertEquals(30_000L, appLockLockoutMillis(1))
        assertEquals(60_000L, appLockLockoutMillis(2))
        assertEquals(120_000L, appLockLockoutMillis(3))
        assertEquals(240_000L, appLockLockoutMillis(4))
    }

    @Test
    fun lockoutIsCappedAtFiveMinutes() {
        assertEquals(300_000L, appLockLockoutMillis(5))
        assertEquals(300_000L, appLockLockoutMillis(10))
        assertEquals(300_000L, appLockLockoutMillis(100))
    }

    @Test
    fun lockoutIsAlwaysPositiveForFailures() {
        (1..20).forEach { assertTrue(appLockLockoutMillis(it) > 0L) }
    }
}
