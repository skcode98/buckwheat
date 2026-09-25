package com.danilkinkin.buckwheat.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoExportDecisionTest {
    private val nowMillis = 1_000_000L

    @Test
    fun toggleOffSkipsExport() {
        assertFalse(
            shouldAutoExportPeriod(
                toggleEnabled = false,
                finishPeriodActualDateMillis = null,
                startPeriodMillis = 100L,
                finishPeriodMillis = 500L,
                lastAutoExportedPeriodStart = null,
                nowMillis = nowMillis,
            )
        )
    }

    @Test
    fun manualFinishSkipsExport() {
        assertFalse(
            shouldAutoExportPeriod(
                toggleEnabled = true,
                finishPeriodActualDateMillis = 900L,
                startPeriodMillis = 100L,
                finishPeriodMillis = 500L,
                lastAutoExportedPeriodStart = null,
                nowMillis = nowMillis,
            )
        )
    }

    @Test
    fun missingPeriodSkipsExport() {
        assertFalse(
            shouldAutoExportPeriod(
                toggleEnabled = true,
                finishPeriodActualDateMillis = null,
                startPeriodMillis = null,
                finishPeriodMillis = null,
                lastAutoExportedPeriodStart = null,
                nowMillis = nowMillis,
            )
        )
    }

    @Test
    fun staleAlarmSkipsExport() {
        assertFalse(
            shouldAutoExportPeriod(
                toggleEnabled = true,
                finishPeriodActualDateMillis = null,
                startPeriodMillis = 100L,
                finishPeriodMillis = nowMillis + 1,
                lastAutoExportedPeriodStart = null,
                nowMillis = nowMillis,
            )
        )
    }

    @Test
    fun alreadyExportedPeriodSkipsExport() {
        assertFalse(
            shouldAutoExportPeriod(
                toggleEnabled = true,
                finishPeriodActualDateMillis = null,
                startPeriodMillis = 100L,
                finishPeriodMillis = 500L,
                lastAutoExportedPeriodStart = 100L,
                nowMillis = nowMillis,
            )
        )
    }

    @Test
    fun validPeriodExports() {
        assertTrue(
            shouldAutoExportPeriod(
                toggleEnabled = true,
                finishPeriodActualDateMillis = null,
                startPeriodMillis = 100L,
                finishPeriodMillis = 500L,
                lastAutoExportedPeriodStart = null,
                nowMillis = nowMillis,
            )
        )
    }
}