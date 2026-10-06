package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SyncStateStoreTest {

    @Test
    fun aConflictSurvivesTheRoundTrip() {
        val conflicts = listOf(
            ConflictNotice(SyncTables.TRANSACTIONS, "t-1", "member-1"),
            ConflictNotice(SyncTables.TRANSACTIONS, "t-2", "member-2"),
        )

        assertEquals(conflicts, decodeConflicts(encodeConflicts(conflicts)))
    }

    @Test
    fun noConflictsEncodeToAnEmptyArray() {
        assertEquals(emptyList<ConflictNotice>(), decodeConflicts(encodeConflicts(emptyList())))
    }

    @Test
    fun aBlankPayloadDecodesToNoConflicts() {
        assertEquals(emptyList<ConflictNotice>(), decodeConflicts(null))
        assertEquals(emptyList<ConflictNotice>(), decodeConflicts(""))
        assertEquals(emptyList<ConflictNotice>(), decodeConflicts("   "))
    }

    @Test
    fun aMalformedPayloadDecodesToNoConflicts() {
        assertEquals(emptyList<ConflictNotice>(), decodeConflicts("not json at all"))
        assertEquals(emptyList<ConflictNotice>(), decodeConflicts("{"))
    }

    @Test
    fun aNonArrayPayloadDecodesToNoConflicts() {
        assertEquals(emptyList<ConflictNotice>(), decodeConflicts("""{"table":"t-1"}"""))
    }

    @Test
    fun anEntryWithoutAKeyIsSkippedButOneWithoutAWinnerIsKept() {
        val json = """
            [
              {"table":"transactions","id":"t-1","wonByMemberId":"member-1"},
              {"table":"","id":"t-2","wonByMemberId":"member-2"},
              {"table":"transactions","id":"","wonByMemberId":"member-3"},
              {"table":"transactions","id":"t-4","wonByMemberId":""},
              {"table":"transactions","id":"t-5"}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                ConflictNotice(SyncTables.TRANSACTIONS, "t-1", "member-1"),
                ConflictNotice(SyncTables.TRANSACTIONS, "t-4", null),
                ConflictNotice(SyncTables.TRANSACTIONS, "t-5", null),
            ),
            decodeConflicts(json),
        )
    }

    @Test
    fun aConflictFromAMemberlessTableSurvivesTheRoundTrip() {
        val conflicts = SyncTables.ALL.map {
            ConflictNotice(it, "id-1", wonByMemberId = null)
        }

        assertEquals(SyncTables.ALL.size, decodeConflicts(encodeConflicts(conflicts)).size)
        assertNull(decodeConflicts(encodeConflicts(conflicts)).first().wonByMemberId)
    }

    @Test
    fun everyReasonSurvivesTheRoundTrip() {
        val conflicts = ConflictReason.entries.mapIndexed { index, reason ->
            ConflictNotice(SyncTables.TRANSACTIONS, "t-$index", "member-1", reason)
        }

        assertEquals(conflicts, decodeConflicts(encodeConflicts(conflicts)))
    }

    @Test
    fun anUnknownReasonDegradesToStaleVersion() {
        val json = """[{"table":"transactions","id":"t-1","reason":"from_the_future"}]"""

        assertEquals(
            listOf(ConflictNotice(SyncTables.TRANSACTIONS, "t-1", null, ConflictReason.STALE_VERSION)),
            decodeConflicts(json),
        )
    }
}

/**
 * What a run leaves behind, rather than what it returned. A run that fails all five WorkManager
 * retries is only reportable afterwards, from state, which is the entire reason these keys exist.
 *
 * These run against the real store rather than a fake: the transitions under test are the store's
 * edits, and a fake would only assert that the fake behaves like the fake. The Context-scoped
 * delegate is what forces Robolectric here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataStoreSyncStateStoreTest {

    private val store =
        DataStoreSyncStateStore(ApplicationProvider.getApplicationContext<Context>())

    @Before
    fun setUp() = runBlocking {
        store.clear()
    }

    @Test
    fun aFreshDeviceReportsNeitherATimeNorAnError() = runBlocking {
        assertEquals(0L, store.lastSyncedAt().first())
        assertNull(store.lastError().first())
    }

    @Test
    fun markingSyncedKeepsTheClockTimeItWasGiven() = runBlocking {
        store.markSynced(at = 1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, store.lastSyncedAt().first())
        assertNull(store.lastError().first())
    }

    @Test
    fun markingSyncedClearsTheErrorThePreviousRunLeftBehind() = runBlocking {
        store.markSynced(at = 1_000L)
        store.markFailed("push failed")
        assertEquals("push failed", store.lastError().first())

        store.markSynced(at = 2_000L)

        // The error is not a separate fact to be dismissed: one run cannot have both worked and
        // failed, so the newer run decides and the chip must not still be reading the older one.
        assertEquals(2_000L, store.lastSyncedAt().first())
        assertNull(store.lastError().first())
    }

    @Test
    fun markingFailedLeavesTheLastGoodTimeAlone() = runBlocking {
        store.markSynced(at = 1_000L)

        store.markFailed("apply failed")

        // Kept on purpose. How long ago this device last agreed with the family is still true, and
        // erasing it would make a failure indistinguishable from a device that has never synced.
        assertEquals(1_000L, store.lastSyncedAt().first())
        assertEquals("apply failed", store.lastError().first())
    }

    @Test
    fun markingFailedWithNullClearsTheError() = runBlocking {
        store.markSynced(at = 1_000L)
        store.markFailed("push failed")

        store.markFailed(null)

        assertNull(store.lastError().first())
        assertEquals(1_000L, store.lastSyncedAt().first())
    }

    @Test
    fun aBlankReasonBehavesLikeNull() = runBlocking {
        store.markFailed("push failed")

        store.markFailed("")

        assertNull(store.lastError().first())
    }

    @Test
    fun aWhitespaceOnlyReasonNeverReadsBackAsAnError() = runBlocking {
        store.markSynced(at = 1_000L)
        store.markFailed("push failed")

        // A reason that carries no information must never turn the chip red with nothing to show:
        // both the write and the read side treat blank as no reason at all.
        store.markFailed("   ")

        assertNull(store.lastError().first())
    }

    @Test
    fun clearWipesTheLastTimeAndTheErrorTogether() = runBlocking {
        store.writeCursor(42L)
        store.markSynced(at = 1_000L)
        store.markFailed("apply failed")

        store.clear()

        assertEquals(0L, store.lastSyncedAt().first())
        assertNull(store.lastError().first())
        assertEquals(0L, store.readCursor())
    }

    @Test
    fun theReHomeFlagIsWrittenOnceSurvivesRereadAndClear() = runBlocking {
        assertFalse(store.isFamilyReHome22Done())

        store.markFamilyReHome22Done()
        assertTrue(store.isFamilyReHome22Done())

        store.clear()
        assertTrue(store.isFamilyReHome22Done())
    }
}