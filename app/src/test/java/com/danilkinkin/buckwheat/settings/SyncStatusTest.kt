package com.danilkinkin.buckwheat.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chip is presentation, so everything it claims is decided by [syncStatus] and can be checked
 * without a device. That matters most at the boundaries, where an off-by-one is the difference
 * between a user being told the truth and being told something reassuring.
 */
class SyncStatusTest {

    private val now = 1_700_000_000_000L
    private val staleAfter = 6 * 60 * 60 * 1000L

    @Test
    fun anErrorWinsOverEverythingElse() {
        val status = syncStatus(
            lastSyncedAt = now,
            lastError = "push failed",
            now = now,
            staleAfterMs = staleAfter,
        )

        assertEquals(SyncStatusKind.FAILED, status.kind)
        assertEquals("push failed", status.lastError)
    }

    @Test
    fun anErrorOutranksAFreshTickAndAnAbsentOne() {
        // The whole point of the state: a device that is demonstrably failing must never be chip
        // green, however recently it worked.
        assertEquals(
            SyncStatusKind.FAILED,
            syncStatus(now, "apply failed", now, staleAfter).kind,
        )
        assertEquals(
            SyncStatusKind.FAILED,
            syncStatus(0L, "apply failed", now, staleAfter).kind,
        )
    }

    @Test
    fun aDeviceThatHasNeverSyncedIsNeverOutOfDate() {
        // Not STALE. There is no earlier run to have gone cold, so claiming staleness would describe
        // a comparison that cannot be made.
        val status = syncStatus(
            lastSyncedAt = 0L,
            lastError = null,
            now = now,
            staleAfterMs = staleAfter,
        )

        assertEquals(SyncStatusKind.NEVER, status.kind)
        assertEquals(0L, status.lastSyncedAt)
    }

    @Test
    fun aRecentRunIsSynced() {
        val status = syncStatus(
            lastSyncedAt = now - 1_000L,
            lastError = null,
            now = now,
            staleAfterMs = staleAfter,
        )

        assertEquals(SyncStatusKind.SYNCED, status.kind)
        assertEquals(now - 1_000L, status.lastSyncedAt)
    }

    @Test
    fun exactlyAtTheBoundaryIsStillSyncedAndOneMillisecondPastIsStale() {
        assertEquals(
            SyncStatusKind.SYNCED,
            syncStatus(now - staleAfter, null, now, staleAfter).kind,
        )
        assertEquals(
            SyncStatusKind.STALE,
            syncStatus(now - staleAfter - 1L, null, now, staleAfter).kind,
        )
    }

    @Test
    fun aLongDeadRunIsStale() {
        assertEquals(
            SyncStatusKind.STALE,
            syncStatus(lastSyncedAt = now - 7L * 24 * 60 * 60 * 1000, lastError = null, now = now, staleAfterMs = staleAfter).kind,
        )
    }

    @Test
    fun aBlankReasonIsNotAnError() {
        assertEquals(
            SyncStatusKind.SYNCED,
            syncStatus(now - 1_000L, "", now, staleAfter).kind,
        )
        assertEquals(
            SyncStatusKind.SYNCED,
            syncStatus(now - 1_000L, "   ", now, staleAfter).kind,
        )
        assertEquals(
            SyncStatusKind.NEVER,
            syncStatus(0L, "", now, staleAfter).kind,
        )
    }

    @Test
    fun theStatusCarriesTheInputsItWasResolvedFrom() {
        val status = syncStatus(lastSyncedAt = 12_345L, lastError = "push failed", now = now, staleAfterMs = staleAfter)

        assertEquals(
            SyncStatus(SyncStatusKind.FAILED, lastSyncedAt = 12_345L, lastError = "push failed"),
            status,
        )
    }
}
