package com.danilkinkin.buckwheat.sync

import org.junit.Assert.assertEquals
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
    fun aPartiallyBrokenPayloadKeepsTheUsableEntries() {
        val json = """
            [
              {"table":"transactions","id":"t-1","wonByMemberId":"member-1"},
              {"table":"","id":"t-2","wonByMemberId":"member-2"},
              {"table":"transactions","id":"","wonByMemberId":"member-3"},
              {"table":"transactions","id":"t-4","wonByMemberId":""}
            ]
        """.trimIndent()

        assertEquals(
            listOf(ConflictNotice(SyncTables.TRANSACTIONS, "t-1", "member-1")),
            decodeConflicts(json),
        )
    }

    @Test
    fun anEntryMissingAFieldIsSkipped() {
        val json = """[{"table":"transactions","id":"t-1"},{"table":"transactions","id":"t-2","wonByMemberId":"m"}]"""

        assertEquals(
            listOf(ConflictNotice(SyncTables.TRANSACTIONS, "t-2", "m")),
            decodeConflicts(json),
        )
    }
}
