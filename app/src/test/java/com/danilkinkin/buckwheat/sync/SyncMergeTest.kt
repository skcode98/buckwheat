package com.danilkinkin.buckwheat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private fun local(
        updatedAt: Long,
        version: Int = 1,
        deletedAt: Long? = null,
        payload: String = "local-payload",
        dirty: Boolean = false,
        memberId: String? = "member-1",
    ) = LocalRecord(
        table = "transactions",
        id = "record-1",
        updatedAt = updatedAt,
        version = version,
        deletedAt = deletedAt,
        payload = payload,
        dirty = dirty,
        memberId = memberId,
    )

    private fun remote(
        seq: Long = 10,
        updatedAt: Long = 200,
        version: Int = 2,
        deletedAt: Long? = null,
        payload: String = "remote-payload",
        memberId: String? = "member-2",
    ) = WireRecord(
        table = "transactions",
        id = "record-1",
        seq = seq,
        updatedAt = updatedAt,
        version = version,
        deletedAt = deletedAt,
        payload = payload,
        memberId = memberId,
    )

    @Test
    fun newerRemoteReplacesLocal() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 1)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals(1, result.records.size)
        assertEquals("remote-payload", result.records.first().payload)
        assertEquals(200, result.records.first().updatedAt)
        assertEquals(2, result.records.first().version)
    }

    @Test
    fun aDirtyLocalRecordIsNotClobberedByANewerRemote() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 1, dirty = true)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals("local-payload", result.records.first().payload)
        assertEquals(100, result.records.first().updatedAt)
        assertEquals(1, result.records.first().version)
    }

    @Test
    fun aDirtyLocalRecordStaysQueuedForTheNextPush() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 1, dirty = true)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertTrue(result.records.first().dirty)
    }

    @Test
    fun aDirtyLocalRecordReportsAConflictWhenTheVersionsDiffer() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 1, dirty = true)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals(1, result.conflicts.size)
        assertEquals("record-1", result.conflicts.first().id)
        assertEquals("transactions", result.conflicts.first().table)
        assertEquals("member-1", result.conflicts.first().wonByMemberId)
    }

    @Test
    fun aDirtyLocalRecordReportsNoConflictWhenTheVersionsMatch() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 2, dirty = true)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals(0, result.conflicts.size)
    }

    @Test
    fun aRemoteTombstoneStillWinsOverADirtyLocalRecord() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 1, dirty = true)),
            remote = listOf(remote(updatedAt = 200, version = 2, deletedAt = 900L)),
            cursor = 0,
        )

        assertEquals(900L, result.records.first().deletedAt)
        assertEquals(false, result.records.first().dirty)
    }

    @Test
    fun anOlderRemoteStillLeavesADirtyLocalRecordQueued() {
        val result = mergePull(
            local = listOf(local(updatedAt = 300, version = 3, dirty = true)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals("local-payload", result.records.first().payload)
        assertTrue(result.records.first().dirty)
    }

    @Test
    fun olderRemoteLeavesLocalUntouched() {
        val result = mergePull(
            local = listOf(local(updatedAt = 300, version = 3)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals("local-payload", result.records.first().payload)
        assertEquals(300, result.records.first().updatedAt)
    }

    @Test
    fun equalUpdatedAtKeepsLocal() {
        val result = mergePull(
            local = listOf(local(updatedAt = 200, version = 1)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals("local-payload", result.records.first().payload)
    }

    @Test
    fun localWinningAVersionMismatchReportsItselfAsTheWinner() {
        val result = mergePull(
            local = listOf(local(updatedAt = 300, version = 4)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals(1, result.conflicts.size)
        assertEquals("transactions", result.conflicts.first().table)
        assertEquals("record-1", result.conflicts.first().id)
        assertEquals("member-1", result.conflicts.first().wonByMemberId)
    }

    @Test
    fun remoteWinningAVersionMismatchIsNamedAsTheWinner() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 1)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals(1, result.conflicts.size)
        assertEquals("member-2", result.conflicts.first().wonByMemberId)
    }

    @Test
    fun matchingVersionsProduceNoConflict() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 2)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun localWinningWithMatchingVersionsReportsNoConflict() {
        val result = mergePull(
            local = listOf(local(updatedAt = 300, version = 2)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertTrue(result.conflicts.isEmpty())
        assertEquals("local-payload", result.records.single().payload)
    }

    @Test
    fun localWinningWithoutAMemberIdReportsAnUnknownWinner() {
        val result = mergePull(
            local = listOf(local(updatedAt = 300, version = 4, memberId = null)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals("local-payload", result.records.first().payload)
        assertNull(result.conflicts.single().wonByMemberId)
    }

    @Test
    fun remoteWinningWithoutAMemberIdReportsAnUnknownWinner() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 4, memberId = null)),
            remote = listOf(remote(updatedAt = 200, version = 2, memberId = null)),
            cursor = 0,
        )

        assertEquals("remote-payload", result.records.first().payload)
        assertNull(result.conflicts.single().wonByMemberId)
    }

    @Test
    fun tombstoneAppliesOverANewerLocalRecord() {
        val result = mergePull(
            local = listOf(local(updatedAt = 900, version = 7)),
            remote = listOf(remote(updatedAt = 200, version = 3, deletedAt = 250)),
            cursor = 0,
        )

        assertEquals(250L, result.records.first().deletedAt)
    }

    @Test
    fun tombstoneAppliesAndWinsTheConflictNotice() {
        val result = mergePull(
            local = listOf(local(updatedAt = 900, version = 7)),
            remote = listOf(remote(updatedAt = 200, version = 3, deletedAt = 250)),
            cursor = 0,
        )

        assertEquals(1, result.conflicts.size)
        assertEquals("member-2", result.conflicts.first().wonByMemberId)
    }

    @Test
    fun tombstoneBeatsADirtyLocalRecord() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 1, dirty = true)),
            remote = listOf(remote(updatedAt = 50, version = 1, deletedAt = 60)),
            cursor = 0,
        )

        assertEquals(60L, result.records.first().deletedAt)
    }

    @Test
    fun dirtyLocalIsNotClobberedByAnOlderRemote() {
        val result = mergePull(
            local = listOf(local(updatedAt = 900, version = 7, dirty = true)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
        )

        assertEquals("local-payload", result.records.first().payload)
        assertEquals(true, result.records.first().dirty)
    }

    @Test
    fun unknownRemoteRecordIsAddedClean() {
        val result = mergePull(
            local = emptyList(),
            remote = listOf(remote()),
            cursor = 0,
        )

        assertEquals(1, result.records.size)
        assertEquals("record-1", result.records.first().id)
        assertEquals("remote-payload", result.records.first().payload)
        assertEquals(false, result.records.first().dirty)
    }

    @Test
    fun unknownRemoteRecordKeepsTheMembersWhoWroteIt() {
        val result = mergePull(
            local = emptyList(),
            remote = listOf(remote()),
            cursor = 0,
        )

        assertEquals("member-2", result.records.first().memberId)
    }

    @Test
    fun anUnknownRemoteRecordIsStampedWithTheFamilyAndItsSeq() {
        val result = mergePull(
            local = emptyList(),
            remote = listOf(remote(seq = 42)),
            cursor = 0,
            familyId = "family-1",
        )

        assertEquals("family-1", result.records.first().familyId)
        assertEquals(42L, result.records.first().syncSeq)
    }

    @Test
    fun aRemoteWinnerKeepsTheFamilyItBelongsTo() {
        val record = local(updatedAt = 100, version = 1).copy(familyId = "family-1")

        val result = mergePull(
            local = listOf(record),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
            familyId = "family-1",
        )

        assertEquals("family-1", result.records.first().familyId)
        assertEquals(10L, result.records.first().syncSeq)
    }

    @Test
    fun aRemoteWinnerInheritsTheFamilyIdOnlyWhenLocalHasNone() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100, version = 1)),
            remote = listOf(remote(updatedAt = 200, version = 2)),
            cursor = 0,
            familyId = "family-1",
        )

        assertEquals("family-1", result.records.first().familyId)
    }

    @Test
    fun recordsAbsentFromThePullAreKeptUntouched() {
        val result = mergePull(
            local = listOf(
                local(updatedAt = 100, version = 1, payload = "kept-payload", dirty = true),
            ),
            remote = emptyList(),
            cursor = 55,
        )

        assertEquals(1, result.records.size)
        assertEquals("kept-payload", result.records.first().payload)
        assertEquals(true, result.records.first().dirty)
    }

    @Test
    fun cursorAdvancesToTheHighestRemoteSeq() {
        val result = mergePull(
            local = emptyList(),
            remote = listOf(remote(seq = 12), remote(seq = 41), remote(seq = 7)),
            cursor = 5,
        )

        assertEquals(41, result.cursor)
    }

    @Test
    fun emptyRemoteLeavesTheCursorWhereItWas() {
        val result = mergePull(
            local = listOf(local(updatedAt = 100)),
            remote = emptyList(),
            cursor = 99,
        )

        assertEquals(99, result.cursor)
    }

    @Test
    fun cursorNeverMovesBackwards() {
        val result = mergePull(
            local = emptyList(),
            remote = listOf(remote(seq = 3)),
            cursor = 80,
        )

        assertEquals(80, result.cursor)
    }

    @Test
    fun everyMemberlessTableStillReportsItsConflict() {
        // These five tables carry no member id at all, so their conflicts resolve to "nobody in
        // particular". Losing the notice here is what hid every conflict on those tables.
        val tables = listOf(
            SyncTables.BUDGET_PERIODS,
            SyncTables.SAVED_CATEGORIES,
            SyncTables.SAVED_TAGS,
            SyncTables.RECURRING_TEMPLATES,
            SyncTables.SAVINGS_GOALS,
        )

        tables.forEach { table ->
            val result = mergePull(
                local = listOf(local(updatedAt = 100, version = 1, memberId = null).copy(table = table)),
                remote = listOf(remote(updatedAt = 200, version = 2, memberId = null).copy(table = table)),
                cursor = 0,
            )

            assertEquals("no conflict reported for $table", 1, result.conflicts.size)
            assertNull("winner for $table", result.conflicts.single().wonByMemberId)
        }
    }

    @Test
    fun aCursorOlderThanTheLocalOneNeverRewinds() {
        val result = mergePull(
            local = emptyList(),
            remote = emptyList(),
            cursor = 0,
        )

        assertEquals(0, result.cursor)
        val ahead = mergePull(
            local = listOf(local(updatedAt = 100)),
            remote = listOf(remote(seq = 4, updatedAt = 200)),
            cursor = 90,
        )

        assertEquals(90, ahead.cursor)
    }
}
