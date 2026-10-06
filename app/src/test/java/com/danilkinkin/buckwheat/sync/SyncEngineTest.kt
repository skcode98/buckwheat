package com.danilkinkin.buckwheat.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
    private fun engine(
        database: FakeSyncDatabase = this.database,
        membersCache: FamilyMembersCache? = null,
        familyApiFactory: FamilyApiFactory? = null,
        periodStart: suspend () -> Long = { 0L },
    ) = SyncEngine(
        client = database.client,
        database = database,
        sessionProvider = { session },
        membersCache = membersCache,
        familyApiFactory = familyApiFactory,
        periodStart = periodStart,
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
        assertEquals(listOf(RecordKey(SyncTables.TRANSACTIONS, "rec-a")), database.applied.last().settled.map { it.key })
    }

    @Test
    fun anAcceptanceForAnotherTableNeverOverwritesThisRow() = runTest {
        // Two tables, same id. Matching an acceptance by id alone would push the transaction payload
        // onto the tag and destroy it.
        database.records = mutableListOf(
            local(id = "shared"),
            local(table = "saved_tags", id = "shared", payload = "tag"),
        )
        database.pending = mutableListOf(pending(id = "shared"))
        client.response = SyncResponse(
            cursor = 9,
            accepted = listOf(RecordKey(SyncTables.TRANSACTIONS, "shared")),
            records = listOf(remote(id = "shared")),
            conflicts = emptyList(),
        )
        engine().sync()
        val tag = database.records.single { it.table == "saved_tags" }
        assertEquals("tag", tag.payload)
        assertEquals(1, database.applied.last().settled.count { it.key.table == SyncTables.TRANSACTIONS })
        assertEquals(0, database.applied.last().settled.count { it.key.table == "saved_tags" })
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
        assertEquals(listOf(RecordKey(SyncTables.TRANSACTIONS, "rec-a")), database.applied.last().settled.map { it.key })
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
    fun aConflictWithNoWinnerReachesTheOutcome() = runTest {
        client.response = SyncResponse(
            cursor = 3,
            accepted = emptyList(),
            records = emptyList(),
            conflicts = listOf(ConflictNotice(SyncTables.TRANSACTIONS, "rec-a", wonByMemberId = null)),
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
        assertEquals(emptyList<SettledChange>(), database.applied.last().settled)
    }

    @Test
    fun noSessionMeansNothingToSync() = runTest {
        val engine = SyncEngine(client = client, database = database, sessionProvider = { null })
        assertEquals(SyncOutcome.NotEnrolled, engine.sync())
    }

    @Test
    fun aPullWritesIntoFamilyTransactionsAndNeverIntoTransactions() = runTest {
        val database = FakeSyncDatabase()
        val cache = RecordingMembersCache()
        val engine = engine(database = database, membersCache = cache, familyApiFactory = RecordingFamilyApiFactory())
        val remote = remote(table = SyncTables.TRANSACTIONS, id = "remote-1", seq = 1L, payload = spendPayload("42.00"))
        database.responses = listOf(SyncResponse(cursor = 1L, accepted = emptyList(), records = listOf(remote), conflicts = emptyList()))

        assertEquals(SyncOutcome.Synced(1L, emptyList()), engine.sync())

        val tables = database.applied.flatMap { it.records }.map { it.table }.toSet()
        assertEquals(setOf(SyncTables.TRANSACTIONS), tables)
        assertTrue(database.upsertedTables.contains("family_transactions"))
        assertFalse(database.upsertedTables.contains("transactions"))
        assertEquals(listOf("remote-1"), cache.written.single().records.map { it.id })
    }

    @Test
    fun thePullSendsTheCurrentPeriodStartAsSince() = runTest {
        val database = FakeSyncDatabase()
        val engine = engine(database = database, periodStart = { 1_700_000_000_000L })
        database.responses = listOf(SyncResponse(cursor = 0L, accepted = emptyList(), records = emptyList(), conflicts = emptyList()))
        engine.sync()
        assertEquals(1_700_000_000_000L, database.requests.single().since)
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
        table: String = SyncTables.TRANSACTIONS,
        id: String = "rec-a",
        version: Int = 4,
        seq: Long = 9,
        updatedAt: Long = 90,
        memberId: String? = "member-2",
        payload: String = spendPayload("remote"),
    ) = WireRecord(
        table = table,
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
        var responses: List<SyncResponse> = emptyList()
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
            if (responses.isNotEmpty()) {
                val next = responses.first()
                responses = responses.drop(1)
                return next
            }
            return if (pages.isNotEmpty()) pages.removeAt(0) else response
        }
    }

    /**
     * Decodes every applied payload exactly like [RoomSyncDatabase] does, so a payload the engine cannot
     * understand fails the same way here as it would against the real database.
     */
    private class FakeSyncDatabase(val client: FakeSyncClient = FakeSyncClient()) : SyncDatabase {
        var cursor = 0L
        var records = mutableListOf<LocalRecord>()
        var pending = mutableListOf<LocalRecord>()
        val applied = mutableListOf<SyncApply>()
        val upsertedTables = mutableListOf<String>()
        var conflicts = emptyList<ConflictNotice>()
        val requests: MutableList<SyncRequest> get() = client.requests
        var responses: List<SyncResponse>
            get() = client.responses
            set(value) { client.responses = value }
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
                    JSONObject(record.payload).readFamilyTransaction(record.id)
                    upsertedTables.add("family_transactions")
                } else {
                    upsertedTables.add(record.table)
                }
            }
            applied.add(apply)
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

    private class RecordingMembersCache : FamilyMembersCache {
        class Write(val records: List<FamilyMember>)
        val written = mutableListOf<Write>()
        private var members: List<FamilyMember> = emptyList()

        override fun members(): Flow<List<FamilyMember>> = flowOf(members)

        override suspend fun readMembers(): List<FamilyMember> = members

        override suspend fun replaceMembers(members: List<FamilyMember>) {
            this.members = members
            written.add(Write(members))
        }

        override suspend fun clear() {
            members = emptyList()
        }
    }

    private class RecordingFamilyApi : FamilyApi {
        val member = FamilyMember(
            id = "remote-1",
            displayName = "Remote",
            isOwner = false,
            joinedAt = "2026-01-01T00:00:00Z",
        )

        override suspend fun createFamily(displayName: String): FamilyCredentials =
            error("not under test")

        override suspend fun joinFamily(code: String, displayName: String): FamilyCredentials =
            error("not under test")

        override suspend fun whoami(token: String): WhoAmI = error("not under test")

        override suspend fun mintInvite(token: String): MintedInvite = error("not under test")

        override suspend fun members(token: String): List<FamilyMember> = listOf(member)
    }

    private class RecordingFamilyApiFactory : FamilyApiFactory {
        val api = RecordingFamilyApi()
        override fun create(baseUrl: String): FamilyApi = api
    }
}
