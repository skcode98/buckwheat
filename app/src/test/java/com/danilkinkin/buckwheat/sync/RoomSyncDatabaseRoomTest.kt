package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.data.entities.PendingMutation
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.DatabaseModule
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Date

/**
 * Against a real in-memory Room database, because the schema is what this covers: the wire table
 * `transactions` mirrors into `family_transactions`, the v22 re-home moves rows between the two, and
 * the queue/cursor machinery has to hold against the real DAOs. Fake DAOs agree with whatever the
 * code does, which is why this cannot be an extension of the fake-driven cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSyncDatabaseRoomTest {

    private lateinit var db: DatabaseModule
    private lateinit var pending: PendingMutationDao
    private lateinit var gateways: List<SyncTableGateway>
    private lateinit var database: RoomSyncDatabase
    private lateinit var state: RecordingSyncStateStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            DatabaseModule::class.java,
        ).allowMainThreadQueries().build()
        pending = db.pendingMutationDao()
        state = RecordingSyncStateStore()
        gateways = SyncBindings(pending).gateways(
            familyTransactionDao = db.familyTransactionDao(),
        )
        database = RoomSyncDatabase(
            gateways = gateways,
            pendingMutationDao = pending,
            syncStateStore = state,
            transactionDao = db.transactionDao(),
            familyTransactionDao = db.familyTransactionDao(),
            runInTransaction = { block -> db.withTransaction { block() } },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun everySyncTableIsBoundInApplyOrder() {
        assertEquals(listOf(SyncTables.TRANSACTIONS), SyncTables.APPLY_ORDER)
        assertEquals(SyncTables.APPLY_ORDER, gateways.map { it.table })
    }

    @Test
    fun aPulledTransactionIsMirroredIntoFamilyTransactionsAndNeverIntoTransactions() = runTest {
        database.apply(
            SyncApply(
                records = listOf(spendRecord("r-1", comment = "remote")),
                cursor = 5,
                conflicts = emptyList(),
            )
        )

        val mirrored = db.familyTransactionDao().getById("r-1")!!
        assertEquals("remote", mirrored.comment)
        assertNull(db.transactionDao().getById("r-1"))
        assertEquals(5L, state.cursorValue)
    }

    @Test
    fun aQueuedDeleteWhoseRowIsGoneBecomesATombstone() = runTest {
        pending.enqueue(
            PendingMutation(SyncTables.TRANSACTIONS, "gone-1", queuedAt = 10L, isDelete = true)
        )

        val record = database.dirtyRecords().single()

        assertEquals(SyncTables.TRANSACTIONS, record.table)
        assertEquals("gone-1", record.id)
        assertEquals(10L, record.deletedAt)
        assertEquals(10L, record.updatedAt)
        assertEquals(1, record.version)
        assertEquals("{}", record.payload)
        assertTrue(record.dirty)
    }

    @Test
    fun aQueuedUpsertWhoseRowIsGoneDropsItsQueueEntry() = runTest {
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "gone-1", queuedAt = 10L))

        assertEquals(emptyList<LocalRecord>(), database.dirtyRecords())
        assertEquals(0, pending.isQueued(SyncTables.TRANSACTIONS, "gone-1"))
    }

    @Test
    fun aStillQueuedRowIsPushedWithItsLiveState() = runTest {
        db.familyTransactionDao().insert(familySpend("t-1", comment = "renamed"))
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-1", queuedAt = 10L))

        val record = database.dirtyRecords().single()

        assertEquals("renamed", JSONObject(record.payload).getString("comment"))
        assertTrue(record.dirty)
    }

    @Test
    fun onlySettledKeysLeaveTheQueue() = runTest {
        db.familyTransactionDao().insert(familySpend("t-1"))
        db.familyTransactionDao().insert(familySpend("t-2"))
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-1", queuedAt = 10L))
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-2", queuedAt = 11L))

        database.apply(
            SyncApply(
                records = listOf(spendRecord("t-1"), spendRecord("t-2")),
                cursor = 3,
                conflicts = emptyList(),
                settled = listOf(SettledChange(RecordKey(SyncTables.TRANSACTIONS, "t-1"), spendRecord("t-1").version)),
            )
        )

        assertEquals(0, pending.isQueued(SyncTables.TRANSACTIONS, "t-1"))
        assertEquals(1, pending.isQueued(SyncTables.TRANSACTIONS, "t-2"))
    }

    @Test
    fun aRowWhoseVersionMovedOnKeepsItsQueueEntry() = runTest {
        // The queue is keyed by (table, id) and a later edit re-queues onto that same key, so the
        // pushed version is the only thing that distinguishes the two. A higher version means the row
        // was edited while the sync was in flight and its entry belongs to that newer edit.
        db.familyTransactionDao().insert(familySpend("t-1"))
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-1", queuedAt = 10L))
        val pushedVersion = database.dirtyRecords().single().version
        db.familyTransactionDao().insert(familySpend("t-1", updatedAt = 20L, version = pushedVersion + 1))

        database.apply(
            SyncApply(
                records = emptyList(),
                cursor = 3,
                conflicts = emptyList(),
                settled = listOf(SettledChange(RecordKey(SyncTables.TRANSACTIONS, "t-1"), pushedVersion)),
            )
        )

        assertEquals(1, pending.isQueued(SyncTables.TRANSACTIONS, "t-1"))
    }

    @Test
    fun aRowUnchangedSinceThePushStillLeavesTheQueue() = runTest {
        db.familyTransactionDao().insert(familySpend("t-1"))
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-1", queuedAt = 10L))
        val pushedVersion = database.dirtyRecords().single().version

        database.apply(
            SyncApply(
                records = emptyList(),
                cursor = 3,
                conflicts = emptyList(),
                settled = listOf(SettledChange(RecordKey(SyncTables.TRANSACTIONS, "t-1"), pushedVersion)),
            )
        )

        assertEquals(0, pending.isQueued(SyncTables.TRANSACTIONS, "t-1"))
    }

    @Test
    fun theCursorAndTheConflictsArePersisted() = runTest {
        database.apply(
            SyncApply(
                records = emptyList(),
                cursor = 42,
                conflicts = listOf(ConflictNotice(SyncTables.TRANSACTIONS, "rec-a", null)),
            )
        )

        assertEquals(42L, state.cursorValue)
        assertEquals(1, state.conflictValues.size)
        assertNull(state.conflictValues.single().wonByMemberId)
    }

    @Test
    fun theConflictsAreWrittenBeforeTheCursor() = runTest {
        database.apply(SyncApply(emptyList(), 42, emptyList()))

        assertEquals(listOf("conflicts", "cursor"), state.writes)
    }

    @Test
    fun aFailingWriteLeavesTheCursorUntouched() = runTest {
        state.cursorValue = 7

        val failure = try {
            database.apply(
                SyncApply(
                    records = listOf(spendRecord("bad-1", payload = """{"type":"NONSENSE"}""")),
                    cursor = 99,
                    conflicts = emptyList(),
                )
            )
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull(failure)
        assertEquals(7L, state.cursorValue)
        assertEquals(emptyList<String>(), state.writes)
    }

    @Test
    fun enrolAllStampsEveryRowAndQueuesIt() = runTest {
        db.transactionDao().insert(spend("t-1").copy(familyId = "family-0", memberId = "member-9"))

        database.enrolAll(memberId = "member-1", familyId = "family-1", enrolledAt = 800L)

        val stored = db.familyTransactionDao().getById("t-1")!!
        assertEquals("member-1", stored.memberId)
        assertEquals(800L, stored.updatedAt)
        assertEquals(1, stored.version)
        assertEquals(1, pending.isQueued(SyncTables.TRANSACTIONS, "t-1"))
    }

    @Test
    fun enrollingTwiceDoesNotDuplicateTheQueue() = runTest {
        db.transactionDao().insert(spend("t-1").copy(familyId = "family-0", memberId = "member-9"))

        database.enrolAll("member-1", "family-1", 800L)
        database.enrolAll("member-1", "family-1", 900L)

        assertEquals(1, pending.getAllNow().size)
    }

    @Test
    fun aRecordCarriesItsRealDirtyFlagRatherThanAnAssumedOne() = runTest {
        db.familyTransactionDao().insert(familySpend("t-1"))
        db.familyTransactionDao().insert(familySpend("t-2"))
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-2", queuedAt = 10L))

        val byId = database.loadRecords().associateBy { it.id }

        assertFalse(byId.getValue("t-1").dirty)
        assertTrue(byId.getValue("t-2").dirty)
    }

    @Test
    fun aLoadByIdsReturnsOnlyTheRequestedRows() = runTest {
        db.familyTransactionDao().insert(familySpend("t-1"))
        db.familyTransactionDao().insert(familySpend("t-2"))
        val binding = gateways.single { it.table == SyncTables.TRANSACTIONS }

        assertEquals(listOf("t-2"), binding.loadByIds(listOf("t-2", "missing")).map { it.id })
        assertEquals(emptyList<LocalRecord>(), binding.loadByIds(emptyList()))
    }

    private class RecordingSyncStateStore : SyncStateStore {
        var cursorValue = 0L
        var conflictValues = emptyList<ConflictNotice>()
        var lastSyncedAtValue = 0L
        var lastErrorValue: String? = null
        var reHomeDone = false
        val writes = mutableListOf<String>()

        override fun cursor() = flowOf(cursorValue)

        override suspend fun readCursor() = cursorValue

        override suspend fun writeCursor(cursor: Long) {
            writes.add("cursor")
            cursorValue = cursor
        }

        override fun conflicts() = flowOf(conflictValues)

        override suspend fun readConflicts() = conflictValues

        override suspend fun replaceConflicts(conflicts: List<ConflictNotice>) {
            writes.add("conflicts")
            conflictValues = conflicts
        }

        override fun lastSyncedAt() = flowOf(lastSyncedAtValue)

        override fun lastError() = flowOf(lastErrorValue)

        override suspend fun markSynced(at: Long) {
            writes.add("synced")
            lastSyncedAtValue = at
        }

        override suspend fun markFailed(reason: String?) {
            writes.add("failed")
            lastErrorValue = reason
        }

        override suspend fun clear() {
            cursorValue = 0
            conflictValues = emptyList()
            lastSyncedAtValue = 0
            lastErrorValue = null
        }

        override suspend fun isFamilyReHome22Done(): Boolean = reHomeDone

        override suspend fun markFamilyReHome22Done() {
            reHomeDone = true
        }
    }
}

private fun spend(id: String, comment: String = "") = Transaction(
    id = id,
    type = TransactionType.SPENT,
    value = BigDecimal.TEN,
    date = Date(0),
    comment = comment,
    category = null,
)

private fun familySpend(
    id: String,
    comment: String = "",
    updatedAt: Long = 100L,
    version: Int = 1,
) = FamilyTransaction(
    id = id,
    type = TransactionType.SPENT,
    value = BigDecimal.TEN,
    date = Date(0),
    comment = comment,
    updatedAt = updatedAt,
    version = version,
)

private fun spendRecord(
    id: String,
    comment: String = "",
    payload: String? = null,
    updatedAt: Long = 100L,
    version: Int = 1,
    deletedAt: Long? = null,
) = LocalRecord(
    table = SyncTables.TRANSACTIONS,
    id = id,
    updatedAt = updatedAt,
    version = version,
    deletedAt = deletedAt,
    payload = payload ?: spend(id, comment).businessPayload().toString(),
)
