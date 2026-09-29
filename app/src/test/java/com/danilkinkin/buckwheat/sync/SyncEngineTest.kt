package com.danilkinkin.buckwheat.sync

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    private val calls = mutableListOf<String>()

    private val database = FakeSyncDatabase(calls)
    private val client = FakeSyncClient(calls)

    @Test
    fun thePushHappensBeforeThePullIsApplied() = runTest {
        database.cursor = 5
        client.response = SyncResponse(5, emptyList(), emptyList(), emptyList())

        engine().sync()

        assertEquals(
            listOf("readCursor", "dirtyRecords", "push", "loadRecords", "apply"),
            calls,
        )
    }

    @Test
    fun withoutATokenTheClientIsNeverCalled() = runTest {
        val outcome = SyncEngine(client, database) { null }.sync()

        assertTrue(outcome is SyncOutcome.NotEnrolled)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aFailedPushNeverTouchesTheCursor() = runTest {
        database.cursor = 5
        database.records = listOf(local("rec-a", 200, 3))
        client.failure = IOException("network down")

        val outcome = engine().sync()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(listOf("readCursor", "dirtyRecords", "push"), calls)
        assertEquals(0, database.applies.size)
    }

    @Test
    fun theWholeApplyRunsInASingleCall() = runTest {
        client.response = SyncResponse(
            cursor = 9,
            accepted = emptyList(),
            records = listOf(
                remote("rec-b", seq = 7, updatedAt = 400, version = 1),
                remote("rec-c", seq = 9, updatedAt = 500, version = 1),
            ),
            conflicts = emptyList(),
        )

        engine().sync()

        assertEquals(1, database.applies.size)
        assertEquals(9L, database.applies.single().cursor)
        assertEquals(2, database.applies.single().records.size)
    }

    @Test
    fun anAcceptedPushIsSettledCleanInsteadOfConflictingWithItself() = runTest {
        val pending = local("rec-a", 200, 3, dirty = true)
        database.cursor = 4
        database.records = listOf(pending)
        database.dirty = listOf(pending)
        client.response = SyncResponse(
            cursor = 9,
            accepted = listOf("rec-a"),
            records = listOf(remote("rec-a", seq = 9, updatedAt = 200, version = 4)),
            conflicts = emptyList(),
        )

        val outcome = engine().sync()

        val settled = database.applies.single().records.single()
        assertEquals(false, settled.dirty)
        assertEquals(4, settled.version)
        assertEquals(emptyList<ConflictNotice>(), (outcome as SyncOutcome.Synced).conflicts)
    }

    @Test
    fun aRejectedPushStaysDirtySoTheNextRunRetriesIt() = runTest {
        val pending = local("rec-a", 200, 3, dirty = true)
        database.cursor = 4
        database.records = listOf(pending)
        database.dirty = listOf(pending)
        client.response = SyncResponse(9, emptyList(), emptyList(), emptyList())

        engine().sync()

        assertEquals(true, database.applies.single().records.single().dirty)
    }

    @Test
    fun theCursorDoesNotAdvancePastARejectedPush() = runTest {
        val a = local("rec-a", 200, 3, dirty = true)
        val b = local("rec-b", 300, 1, dirty = true)
        database.cursor = 4
        database.records = listOf(a, b)
        database.dirty = listOf(a, b)
        client.response = SyncResponse(
            cursor = 9,
            accepted = listOf("rec-a"),
            records = listOf(remote("rec-a", seq = 9, updatedAt = 200, version = 4)),
            conflicts = emptyList(),
        )

        engine().sync()

        assertEquals(4L, database.applies.single().cursor)
    }

    @Test
    fun theCursorAdvancesWhenEveryPushIsAccepted() = runTest {
        val pending = local("rec-a", 200, 3, dirty = true)
        database.cursor = 4
        database.records = listOf(pending)
        database.dirty = listOf(pending)
        client.response = SyncResponse(
            cursor = 9,
            accepted = listOf("rec-a"),
            records = listOf(remote("rec-a", seq = 9, updatedAt = 200, version = 4)),
            conflicts = emptyList(),
        )

        engine().sync()

        assertEquals(9L, database.applies.single().cursor)
    }

    @Test
    fun aRecordTheDeviceHasNeverSeenIsAddedClean() = runTest {
        client.response = SyncResponse(
            cursor = 12,
            accepted = emptyList(),
            records = listOf(remote("rec-new", seq = 12, updatedAt = 700, version = 1)),
            conflicts = emptyList(),
        )

        engine().sync()

        val added = database.applies.single().records.single()
        assertEquals("rec-new", added.id)
        assertEquals(false, added.dirty)
        assertEquals(700L, added.updatedAt)
    }

    @Test
    fun aNewerRemoteReplacesALocalRecordAndNamesTheWinner() = runTest {
        database.records = listOf(local("rec-a", 100, 1))
        client.response = SyncResponse(
            cursor = 6,
            accepted = emptyList(),
            records = listOf(remote("rec-a", seq = 6, updatedAt = 900, version = 5)),
            conflicts = emptyList(),
        )

        val outcome = engine().sync()

        val applied = database.applies.single()
        assertEquals(900L, applied.records.single().updatedAt)
        assertEquals(listOf(ConflictNotice("transactions", "rec-a", "member-2")), applied.conflicts)
        assertEquals(applied.conflicts, (outcome as SyncOutcome.Synced).conflicts)
    }

    @Test
    fun serverSideConflictNoticesReachTheOutcome() = runTest {
        client.response = SyncResponse(
            cursor = 9,
            accepted = emptyList(),
            records = emptyList(),
            conflicts = listOf(ConflictNotice("transactions", "rec-a", "member-9")),
        )

        val outcome = engine().sync()

        assertEquals(
            listOf(ConflictNotice("transactions", "rec-a", "member-9")),
            (outcome as SyncOutcome.Synced).conflicts,
        )
    }

    @Test
    fun aTombstoneFromAnotherDeviceIsApplied() = runTest {
        database.records = listOf(local("rec-a", 900, 7))
        client.response = SyncResponse(
            cursor = 11,
            accepted = emptyList(),
            records = listOf(remote("rec-a", seq = 11, updatedAt = 100, version = 8, deletedAt = 100)),
            conflicts = emptyList(),
        )

        engine().sync()

        val applied = database.applies.single().records.single()
        assertEquals(100L, applied.deletedAt)
        assertEquals(8, applied.version)
    }

    @Test
    fun recordsTheDeviceHasNotSeenButTheServerDidNotSendAreLeftAlone() = runTest {
        database.records = listOf(local("rec-a", 100, 1), local("rec-z", 100, 1))
        client.response = SyncResponse(6, emptyList(), emptyList(), emptyList())

        engine().sync()

        val ids = database.applies.single().records.map { it.id }
        assertEquals(listOf("rec-a", "rec-z"), ids)
    }

    @Test
    fun theTokenFromTheProviderIsSentWithThePush() = runTest {
        client.response = SyncResponse(5, emptyList(), emptyList(), emptyList())

        engine("token-abc").sync()

        assertEquals("token-abc", client.lastToken)
    }

    private fun engine(token: String? = "token-abc") = SyncEngine(client, database) { token }

    private fun local(
        id: String,
        updatedAt: Long,
        version: Int,
        dirty: Boolean = false,
        payload: String = "local-payload",
        deletedAt: Long? = null,
        memberId: String? = "member-1",
    ) = LocalRecord(
        table = "transactions",
        id = id,
        updatedAt = updatedAt,
        version = version,
        deletedAt = deletedAt,
        payload = payload,
        dirty = dirty,
        memberId = memberId,
    )

    private fun remote(
        id: String,
        seq: Long,
        updatedAt: Long,
        version: Int,
        deletedAt: Long? = null,
        payload: String = "remote-payload",
        memberId: String? = "member-2",
    ) = WireRecord(
        table = "transactions",
        id = id,
        seq = seq,
        updatedAt = updatedAt,
        version = version,
        deletedAt = deletedAt,
        payload = payload,
        memberId = memberId,
    )

    private class FakeSyncClient(private val calls: MutableList<String>) : SyncClient {
        var response: SyncResponse? = null
        var failure: Exception? = null
        var lastToken: String? = null
        var lastRequest: SyncRequest? = null

        override suspend fun sync(token: String, request: SyncRequest): SyncResponse {
            calls.add("push")
            lastToken = token
            lastRequest = request
            val thrown = failure
            if (thrown != null) throw thrown
            return response ?: throw IllegalStateException("no response configured")
        }
    }

    private class FakeSyncDatabase(private val calls: MutableList<String>) : SyncDatabase {
        var cursor: Long = 0
        var records: List<LocalRecord> = emptyList()
        var dirty: List<LocalRecord> = emptyList()
        val applies: MutableList<SyncApply> = mutableListOf()

        override suspend fun readCursor(): Long {
            calls.add("readCursor")
            return cursor
        }

        override suspend fun dirtyRecords(): List<LocalRecord> {
            calls.add("dirtyRecords")
            return dirty
        }

        override suspend fun loadRecords(): List<LocalRecord> {
            calls.add("loadRecords")
            return records
        }

        override suspend fun apply(apply: SyncApply) {
            calls.add("apply")
            applies.add(apply)
        }

        override suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long) {
            calls.add("enrolAll")
        }
    }
}
