package com.danilkinkin.buckwheat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncStateStoreTest {

    @Test
    fun aConflictSurvivesTheRoundTrip() {
        val conflicts = listOf(
            ConflictNotice(SyncTables.TRANSACTIONS, "t-1", "member-1"),
            ConflictNotice(SyncTables.SAVED_TAGS, "tag-1", "member-2"),
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
              {"table":"budget_periods","id":"p-1","wonByMemberId":""},
              {"table":"saved_tags","id":"tag-1"}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                ConflictNotice(SyncTables.TRANSACTIONS, "t-1", "member-1"),
                ConflictNotice(SyncTables.BUDGET_PERIODS, "p-1", null),
                ConflictNotice(SyncTables.SAVED_TAGS, "tag-1", null),
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