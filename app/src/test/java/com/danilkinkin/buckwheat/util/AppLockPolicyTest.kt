package com.danilkinkin.buckwheat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun noAttemptsMeansNoLockout() {
        assertEquals(0L, appLockLockoutMillis(0))
        assertEquals(0L, appLockLockoutMillis(-1))
    }

    @Test
    fun firstTwoAttemptsNoLockout() {
        assertEquals(0L, appLockLockoutMillis(1))
        assertEquals(0L, appLockLockoutMillis(2))
    }

    @Test
    fun threeAttemptsTriggersFiveMinuteLockout() {
        assertEquals(300_000L, appLockLockoutMillis(3))
        assertEquals(300_000L, appLockLockoutMillis(4))
        assertEquals(300_000L, appLockLockoutMillis(5))
        assertEquals(300_000L, appLockLockoutMillis(10))
        assertEquals(300_000L, appLockLockoutMillis(100))
    }
}
