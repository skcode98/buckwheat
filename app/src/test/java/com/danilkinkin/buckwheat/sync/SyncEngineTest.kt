package com.danilkinkin.buckwheat.sync

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncEngineTest {
    private val client = FakeSyncClient()
    private val database = FakeSyncDatabase(client)
    private val session = FamilySession(
        token = "token-1",
        familyId = "family-1",
        memberId = "member-1",
        baseUrl = "https://sync.example",
    )
    private fun engine() = SyncEngine(
        client = client,
        database = database,
        sessionProvider = { session },
    )
    @Test
    fun everyRecordIsHandedToTheDatabaseWithTheServerCursor() = runTest {
        database.records = mutableListOf(local(id = "local-a"))
        client.response = SyncResponse(
            cursor = 5,
            accepted = emptyList(),
            records = listOf(remote(id = "remote-a", seq = 5)),
            conflicts = emptyList(),
        )
        val outcome = engine().sync() as SyncOutcome.Synced
        assertEquals(5, outcome.cursor)
        assertEquals(listOf("local", "remote"), database.records.map { comment(it.payload) })
        assertEquals(5, database.cursor)
    }

    @Test
    fun anAcceptedPushIsSettledCleanInsteadOfConflictingWithItself() = runTest {
        database.records = mutableListOf(local())
        database.pending = mutableListOf(pending())
        client.response = SyncResponse(
            cursor = 9,
            accepted = listOf(RecordKey(SyncTables.TRANSACTIONS, "rec-a")),
            records = listOf(remote()),
            conflicts = emptyList(),
        )
        val outcome = engine().sync() as SyncOutcome.Synced
        assertEquals(listOf("remote"), database.records.map { comment(it.payload) })
        assertEquals(emptyList<ConflictNotice>(), outcome.conflicts)
        assertEquals(listOf(RecordKey(SyncTables.TRANSACTIONS, "rec-a")), database.applied?.settled?.map { it.key })
    }

    @Test
    fun anAcceptanceForAnotherTableNeverOverwritesThisRow() = runTest {
        // Two tables, same id. Matching an acceptance by id alone would push the transaction payload
        // onto the tag and destroy it.
        database.records = mutableListOf(
            local(id = "shared"),
            local(table = SyncTables.SAVED_TAGS, id = "shared", payload = "tag"),
        )
        database.pending = mutableListOf(pending(id = "shared"))
        client.response = SyncResponse(
            cursor = 9,
            accepted = listOf(RecordKey(SyncTables.TRANSACTIONS, "shared")),
            records = listOf(remote(id = "shared")),
            conflicts = emptyList(),
        )
        engine().sync()
        val tag = database.records.single { it.table == SyncTables.SAVED_TAGS }
        assertEquals("tag", tag.payload)
        assertEquals(1, database.applied?.settled?.count { it.key.table == SyncTables.TRANSACTIONS })
        assertEquals(0, database.applied?.settled?.count { it.key.table == SyncTables.SAVED_TAGS })
    }

    @Test
    fun aRejectedPushTakesTheServersCopy() = runTest {
        database.records = mutableListOf(local())
        database.pending = mutableListOf(pending())
        client.response = SyncResponse(
            cursor = 9,
            accepted = emptyList(),
            records = listOf(remote()),
            conflicts = listOf(
                ConflictNotice(
                    table = SyncTables.TRANSACTIONS,
                    id = "rec-a",
                    wonByMemberId = "member-2",
                    reason = ConflictReason.STALE_VERSION,
                )
            ),
        )
        val outcome = engine().sync() as SyncOutcome.Synced
        assertEquals(listOf("remote"), database.records.map { comment(it.payload) })
        assertEquals(1, outcome.conflicts.size)
        assertEquals("member-2", outcome.conflicts.single().wonByMemberId)
    }

    @Test
    fun aRejectedPushWithNoServerCopyIsStillResolved() = runTest {
        database.records = mutableListOf(local())
        database.pending = mutableListOf(pending())
        // Refused, and the server's copy is outside this pull window.
        client.response = SyncResponse(
            cursor = 9,
            accepted = emptyList(),
            records = emptyList(),
            conflicts = emptyList(),
        )
        engine().sync()
        assertFalse(database.records.single().dirty)
        assertEquals(emptyList<LocalRecord>(), database.remainingQueue())
    }

    @Test
    fun theCursorAdvancesEvenWhenAChangeIsRejected() = runTest {
        database.cursor = 4
        database.records = mutableListOf(local())
        database.pending = mutableListOf(pending())
        client.response = SyncResponse(
            cursor = 9,
            accepted = emptyList(),
            records = listOf(remote()),
            conflicts = emptyList(),
        )
        val outcome = engine().sync() as SyncOutcome.Synced
        assertEquals(9, outcome.cursor)
        assertEquals(9, database.cursor)
    }

    @Test
    fun aRejectedRecordIsNoLongerDirtyAfterwards() = runTest {
        database.records = mutableListOf(local())
        database.pending = mutableListOf(pending())
        client.response = SyncResponse(
            cursor = 9,
            accepted = emptyList(),
            records = listOf(remote()),
            conflicts = emptyList(),
        )
        engine().sync()
        assertTrue(database.records.none { it.dirty })
        assertEquals(emptyList<LocalRecord>(), database.remainingQueue())
    }

    @Test
    fun aRecordThatWasNeverPushedKeepsItsQueueEntry() = runTest {
        // A local edit made while the sync was in flight: it was not in the request, so the server has
        // no decision about it and its queue entry must survive. Both rows are queued, because a row
        // that is not in the queue was never at risk of being dequeued in the first place.
        database.records = mutableListOf(
            local(id = "rec-a", version = 5, payload = spendPayload("edited")).copy(dirty = true),
            local(id = "rec-b", updatedAt = 80).copy(dirty = true),
        )
        database.pending = mutableListOf(
            local(id = "rec-a", version = 5, payload = spendPayload("edited")).copy(dirty = true),
            local(id = "rec-b", updatedAt = 80).copy(dirty = true),
        )
        client.pushesOnly = listOf(RecordKey(SyncTables.TRANSACTIONS, "rec-b"))
        engine().sync()
        assertEquals(listOf("rec-a"), database.remainingQueue().map { it.id })
        assertEquals(listOf("rec-b"), client.requests.single().changes.map { it.id })
    }

    @Test
    fun thePushedRecordsAreSentAndThenSettled() = runTest {
        database.records = mutableListOf(local())
        database.pending = mutableListOf(pending())
        engine().sync()
        assertEquals(listOf("rec-a"), client.requests.single().changes.map { it.id })
        assertEquals(listOf(RecordKey(SyncTables.TRANSACTIONS, "rec-a")), database.applied?.settled?.map { it.key })
    }

    @Test
    fun theNewerRemoteCursorIsHandedToTheDatabase() = runTest {
        client.response = SyncResponse(9, emptyList(), emptyList(), emptyList())
        val outcome = engine().sync() as SyncOutcome.Synced
        assertEquals(9, outcome.cursor)
        assertEquals(9, database.cursor)
    }

    @Test
    fun theCursorNeverMovesBackwards() = runTest {
        database.cursor = 40
        client.response = SyncResponse(9, emptyList(), emptyList(), emptyList())
        val outcome = engine().sync() as SyncOutcome.Synced
        assertEquals(40, outcome.cursor)
        assertEquals(40, database.cursor)
    }

    @Test
    fun serverSideConflictNoticesReachTheOutcome() = runTest {
        client.response = SyncResponse(
            cursor = 3,
            accepted = emptyList(),
            records = emptyList(),
            conflicts = listOf(
                ConflictNotice(
                    table = SyncTables.TRANSACTIONS,
                    id = "rec-a",
                    wonByMemberId = "member-2",
                    reason = ConflictReason.CROSS_FAMILY_WRITE,
                )
            ),
        )
        val outcome = engine().sync() as SyncOutcome.Synced
        assertEquals(1, outcome.conflicts.size)
        assertEquals(ConflictReason.CROSS_FAMILY_WRITE, outcome.conflicts.single().reason)
        assertEquals(listOf("rec-a"), database.conflicts.map { it.id })
    }

    @Test
    fun aConflictFromAMemberlessTableReachesTheOutcome() = runTest {
        client.response = SyncResponse(
            cursor = 3,
            accepted = emptyList(),
            records = emptyList(),
            conflicts = listOf(ConflictNotice(SyncTables.SAVED_TAGS, "tag-1", wonByMemberId = null)),
        )
        val outcome = engine().sync() as SyncOutcome.Synced
        assertEquals(1, outcome.conflicts.size)
        assertEquals(null, outcome.conflicts.single().wonByMemberId)
    }

    @Test
    fun anEmptyPushStillPullsTheNewRecords() = runTest {
        database.records = mutableListOf(
            local(id = "local-a", payload = spendPayload("local-a")),
            local(id = "local-b", payload = spendPayload("local-b")),
        )
        client.response = SyncResponse(
            cursor = 9,
            accepted = emptyList(),
            records = listOf(remote(id = "remote-a", version = 2, updatedAt = 120, memberId = "member-2")),
            conflicts = emptyList(),
        )
        engine().sync()
        assertEquals(
            listOf("local-a", "local-b", "remote"),
            database.records.map { comment(it.payload) },
        )
    }

    @Test
    fun pagingKeepsPullingWhileTheServerHasMore() = runTest {
        client.pages = mutableListOf(
            SyncResponse(
                cursor = 9,
                accepted = emptyList(),
                records = listOf(remote(version = 2, updatedAt = 120, memberId = "member-2")),
                conflicts = emptyList(),
                hasMore = true,
            ),
            SyncResponse(
                cursor = 14,
                accepted = emptyList(),
                records = listOf(
                    WireRecord(
                        table = SyncTables.TRANSACTIONS,
                        id = "rec-b",
                        seq = 14,
                        updatedAt = 130,
                        version = 1,
                        deletedAt = null,
                        payload = spendPayload("second page"),
                        memberId = null,
                    )
                ),
                conflicts = emptyList(),
                hasMore = false,
            ),
        )
        val outcome = engine().sync() as SyncOutcome.Synced
        assertEquals(14, outcome.cursor)
        assertEquals(listOf("rec-a", "rec-b"), database.records.map { it.id })
        // Only the first request carries changes.
        assertEquals(2, client.requests.size)
        assertTrue(client.requests[1].changes.isEmpty())
        assertEquals(9, client.requests[1].cursor)
    }

    @Test
    fun aSyncFailureReportsTheReason() = runTest {
        client.failure = IOException("offline")
        val outcome = engine().sync()
        assertEquals(SyncOutcome.Failed("offline"), outcome)
    }

    @Test
    fun anUnknownTransactionTypeIsReportedAsABadPayload() = runTest {
        client.response = SyncResponse(
            cursor = 9,
            accepted = emptyList(),
            records = listOf(remote(payload = spendPayload("remote", type = "NONSENSE"))),
            conflicts = emptyList(),
        )
        val outcome = engine().sync()
        assertTrue(outcome is SyncOutcome.Failed)
        val reason = (outcome as SyncOutcome.Failed).reason
        assertTrue(reason.startsWith("bad payload:"))
        assertTrue(reason.contains("NONSENSE"))
    }

    @Test
    fun anUnparseableAmountIsReportedAsABadPayload() = runTest {
        client.response = SyncResponse(
            cursor = 9,
            accepted = emptyList(),
            records = listOf(remote(payload = spendPayload("remote", value = "not-a-number"))),
            conflicts = emptyList(),
        )
        val outcome = engine().sync()
        assertTrue(outcome is SyncOutcome.Failed)
        assertTrue((outcome as SyncOutcome.Failed).reason.startsWith("bad payload:"))
    }

    @Test
    fun anEditMadeWhileThePushWasInFlightIsNeitherOverwrittenNorDequeued() = runTest {
        // The request carried version 4. Before it returned, a local edit moved the row to version 5.
        // The server answered version 4, so its copy is stale for this row and the queue entry now
        // belongs to the newer edit: neither may be discarded.
        database.pending = mutableListOf(pending())
        database.records = mutableListOf(local(version = 5, payload = spendPayload("edited")))
        client.response = SyncResponse(
            cursor = 9,
            accepted = listOf(RecordKey(SyncTables.TRANSACTIONS, "rec-a")),
            records = listOf(remote()),
            conflicts = emptyList(),
        )
        engine().sync()
        assertEquals(listOf("edited"), database.records.map { comment(it.payload) })
        assertTrue(database.records.single().dirty)
        assertEquals(listOf("rec-a"), database.remainingQueue().map { it.id })
        assertEquals(emptyList<SettledChange>(), database.applied?.settled)
    }

    @Test
    fun noSessionMeansNothingToSync() = runTest {
        val engine = SyncEngine(client = client, database = database, sessionProvider = { null })
        assertEquals(SyncOutcome.NotEnrolled, engine.sync())
    }

    private fun local(
        table: String = SyncTables.TRANSACTIONS,
        id: String = "rec-a",
        version: Int = 3,
        updatedAt: Long = 70,
        payload: String = spendPayload("local"),
    ) = LocalRecord(
        table = table,
        id = id,
        updatedAt = updatedAt,
        version = version,
        deletedAt = null,
        payload = payload,
        dirty = false,
        memberId = "member-1",
        familyId = "family-1",
        syncSeq = 0,
    )

    private fun pending(id: String = "rec-a") = local(id = id, version = 4, updatedAt = 80).copy(dirty = true)

    private fun remote(
        id: String = "rec-a",
        version: Int = 4,
        seq: Long = 9,
        updatedAt: Long = 90,
        memberId: String? = "member-2",
        payload: String = spendPayload("remote"),
    ) = WireRecord(
        table = SyncTables.TRANSACTIONS,
        id = id,
        seq = seq,
        updatedAt = updatedAt,
        version = version,
        deletedAt = null,
        payload = payload,
        memberId = memberId,
    )

    private fun spendPayload(
        comment: String,
        type: String = "SPENT",
        value: String = "10",
    ): String = JSONObject()
        .put("type", type)
        .put("value", value)
        .put("spentAt", 70L)
        .put("comment", comment)
        .put("category", JSONObject.NULL)
        .toString()

    private fun comment(payload: String): String = JSONObject(payload).getString("comment")

    private class FakeSyncClient : SyncClient {
        var response = SyncResponse(0, emptyList(), emptyList(), emptyList())
        var pages: MutableList<SyncResponse> = mutableListOf()
        var failure: IOException? = null
        val requests = mutableListOf<SyncRequest>()

        /**
         * When set, the fake reports having read the queue at a moment when only these keys were
         * queued, even though the database still holds more. That is what an edit landing mid-request
         * looks like to the engine, and it cannot be expressed by mutating the list afterwards.
         */
        var pushesOnly: List<RecordKey>? = null

        override suspend fun sync(token: String, request: SyncRequest): SyncResponse {
            requests.add(request)
            failure?.let { throw it }
            pushesOnly?.let { allowed ->
                check(request.changes.all { it.key in allowed }) {
                    "engine pushed ${request.changes.map { it.key }}, which was not queued at read time: $allowed"
                }
            }
            return if (pages.isNotEmpty()) pages.removeAt(0) else response
        }
    }

    /**
     * Decodes every applied payload exactly like [RoomSyncDatabase] does, so a payload the engine cannot
     * understand fails the same way here as it would against the real database.
     */
    private class FakeSyncDatabase(private val client: FakeSyncClient) : SyncDatabase {
        var cursor = 0L
        var records = mutableListOf<LocalRecord>()
        var pending = mutableListOf<LocalRecord>()
        var applied: SyncApply? = null
        var conflicts = emptyList<ConflictNotice>()
        fun remainingQueue(): List<LocalRecord> = pending
        override suspend fun readCursor(): Long = cursor
        override suspend fun dirtyRecords(): List<LocalRecord> {
            val allowed = client.pushesOnly ?: return pending
            return pending.filter { it.key in allowed }
        }
        override suspend fun loadRecords(): List<LocalRecord> = records
        override suspend fun apply(apply: SyncApply) {
            apply.records.forEach { record ->
                if (record.table == SyncTables.TRANSACTIONS) {
                    JSONObject(record.payload).readTransaction(record.id)
                }
            }
            applied = apply
            records = apply.records.toMutableList()
            conflicts = apply.conflicts
            cursor = apply.cursor
            pending = pending.filterNot { record ->
                apply.settled.any { it.key == record.key && it.version == record.version }
            }.toMutableList()
        }
        override suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long) = Unit
        override suspend fun reset() = Unit
    }
}
