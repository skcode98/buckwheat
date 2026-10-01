package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.PendingMutation
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
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
 * Against a real in-memory Room database, because the bug this covers only exists in the schema:
 * `archived_transactions.period_id` is ON DELETE CASCADE off `budget_periods.id`, so an upsert that
 * deletes before it inserts silently deletes archived history on every single sync. Fake DAOs cannot
 * model that, which is why this cannot be an extension of the fake-driven cases.
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
            transactionDao = db.transactionDao(),
            budgetPeriodDao = db.budgetPeriodDao(),
            savedCategoryDao = db.savedCategoryDao(),
            savedTagDao = db.savedTagDao(),
            recurringDao = db.recurringDao(),
            savingsGoalDao = db.savingsGoalDao(),
        )
        database = RoomSyncDatabase(
            gateways = gateways,
            pendingMutationDao = pending,
            syncStateStore = state,
            runInTransaction = { block -> db.withTransaction { block() } },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun everySyncTableIsBoundInApplyOrder() {
        assertEquals(SyncTables.APPLY_ORDER, gateways.map { it.table })
    }

    @Test
    fun budgetPeriodsAreWrittenBeforeArchivedTransactions() = runTest {
        // The archived row is listed FIRST, which is what the response order used to produce. With the
        // tables grouped by first-seen order this insert hits a missing parent and the whole window fails.
        database.apply(
            SyncApply(
                records = listOf(
                    archivedRecord("a-1", periodId = "p-1"),
                    periodRecord("p-1"),
                ),
                cursor = 1,
                conflicts = emptyList(),
            )
        )

        assertNotNull(db.budgetPeriodDao().getById("p-1"))
        assertNotNull(db.budgetPeriodDao().getArchivedById("a-1"))
    }

    @Test
    fun archivedRowsSurviveARepeatedApply() = runTest {
        db.budgetPeriodDao().insert(period("p-1"))
        db.budgetPeriodDao().insertArchivedTransactions(listOf(archived("a-1", periodId = "p-1")))

        val window = SyncApply(
            records = listOf(periodRecord("p-1", budget = "200"), archivedRecord("a-1", periodId = "p-1")),
            cursor = 1,
            conflicts = emptyList(),
        )
        database.apply(window)
        database.apply(window)
        database.apply(window.copy(cursor = 2))

        val stored = db.budgetPeriodDao().getAllArchivedNow()
        assertEquals(1, stored.size)
        assertEquals("a-1", stored.single().id)
        // The period really was rewritten, so this is not passing because the write was skipped.
        assertEquals(0, BigDecimal("200").compareTo(db.budgetPeriodDao().getById("p-1")!!.budget))
    }

    @Test
    fun anUnchangedWindowLeavesExactlyOneRowPerKey() = runTest {
        db.budgetPeriodDao().insert(period("p-1"))
        db.budgetPeriodDao().insertArchivedTransactions(listOf(archived("a-1", periodId = "p-1")))
        val window = SyncApply(
            records = listOf(periodRecord("p-1"), archivedRecord("a-1", periodId = "p-1")),
            cursor = 1,
            conflicts = emptyList(),
        )

        database.apply(window)
        database.apply(window)

        assertEquals(1, db.budgetPeriodDao().getAllNow().size)
        assertEquals(1, db.budgetPeriodDao().getAllArchivedNow().size)
    }

    @Test
    fun aTombstoneRemovesTheRowAndClearsItsQueueEntry() = runTest {
        db.budgetPeriodDao().insert(period("p-1"))
        db.budgetPeriodDao().insertArchivedTransactions(listOf(archived("a-1", periodId = "p-1")))
        pending.enqueue(
            PendingMutation(SyncTables.ARCHIVED_TRANSACTIONS, "a-1", queuedAt = 10L, isDelete = true)
        )

        database.apply(
            SyncApply(
                records = listOf(archivedRecord("a-1", periodId = "p-1", deletedAt = 10L)),
                cursor = 2,
                conflicts = emptyList(),
                settled = listOf(SettledChange(RecordKey(SyncTables.ARCHIVED_TRANSACTIONS, "a-1"), 1)),
            )
        )

        assertNull(db.budgetPeriodDao().getArchivedById("a-1"))
        assertEquals(0, pending.isQueued(SyncTables.ARCHIVED_TRANSACTIONS, "a-1"))
    }

    @Test
    fun aQueuedDeleteWhoseRowIsGoneBecomesATombstone() = runTest {
        pending.enqueue(
            PendingMutation(SyncTables.SAVED_TAGS, "tag-1", queuedAt = 10L, isDelete = true)
        )

        val record = database.dirtyRecords().single()

        assertEquals(SyncTables.SAVED_TAGS, record.table)
        assertEquals("tag-1", record.id)
        assertEquals(10L, record.deletedAt)
        assertEquals(10L, record.updatedAt)
        assertEquals(1, record.version)
        assertEquals("{}", record.payload)
        assertTrue(record.dirty)
    }

    @Test
    fun aQueuedUpsertWhoseRowIsGoneDropsItsQueueEntry() = runTest {
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-1", queuedAt = 10L))

        assertEquals(emptyList<LocalRecord>(), database.dirtyRecords())
        assertEquals(0, pending.isQueued(SyncTables.SAVED_TAGS, "tag-1"))
    }

    @Test
    fun aStillQueuedRowIsPushedWithItsLiveState() = runTest {
        db.savedTagDao().insert(tag("tag-1", name = "renamed"))
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-1", queuedAt = 10L))

        val record = database.dirtyRecords().single()

        assertEquals("renamed", JSONObject(record.payload).getString("name"))
        assertTrue(record.dirty)
    }

    @Test
    fun onlySettledKeysLeaveTheQueue() = runTest {
        db.savedTagDao().insert(tag("tag-1"))
        db.savedTagDao().insert(tag("tag-2"))
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-1", queuedAt = 10L))
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-2", queuedAt = 11L))

        database.apply(
            SyncApply(
                records = listOf(tagRecord("tag-1"), tagRecord("tag-2", name = "changed")),
                cursor = 3,
                conflicts = emptyList(),
                settled = listOf(SettledChange(RecordKey(SyncTables.SAVED_TAGS, "tag-1"), tagRecord("tag-1").version)),
            )
        )

        assertEquals(0, pending.isQueued(SyncTables.SAVED_TAGS, "tag-1"))
        assertEquals(1, pending.isQueued(SyncTables.SAVED_TAGS, "tag-2"))
    }

    @Test
    fun aRowWhoseVersionMovedOnKeepsItsQueueEntry() = runTest {
        // The queue is keyed by (table, id) and a later edit re-queues onto that same key, so the
        // pushed version is the only thing that distinguishes the two. A higher version means the row
        // was edited while the sync was in flight and its entry belongs to that newer edit.
        db.savedTagDao().insert(tag("tag-1"))
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-1", queuedAt = 10L))
        val pushedVersion = database.dirtyRecords().single().version
        db.syncStampDao().stampSavedTag("tag-1", 20L)

        database.apply(
            SyncApply(
                records = emptyList(),
                cursor = 3,
                conflicts = emptyList(),
                settled = listOf(SettledChange(RecordKey(SyncTables.SAVED_TAGS, "tag-1"), pushedVersion)),
            )
        )

        assertEquals(1, pending.isQueued(SyncTables.SAVED_TAGS, "tag-1"))
    }

    @Test
    fun aRowUnchangedSinceThePushStillLeavesTheQueue() = runTest {
        db.savedTagDao().insert(tag("tag-1"))
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-1", queuedAt = 10L))
        val pushedVersion = database.dirtyRecords().single().version

        database.apply(
            SyncApply(
                records = emptyList(),
                cursor = 3,
                conflicts = emptyList(),
                settled = listOf(SettledChange(RecordKey(SyncTables.SAVED_TAGS, "tag-1"), pushedVersion)),
            )
        )

        assertEquals(0, pending.isQueued(SyncTables.SAVED_TAGS, "tag-1"))
    }

    @Test
    fun theCursorAndTheConflictsArePersisted() = runTest {
        database.apply(
            SyncApply(
                records = emptyList(),
                cursor = 42,
                conflicts = listOf(ConflictNotice(SyncTables.SAVED_TAGS, "tag-1", null)),
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
                    records = listOf(goalRecord("g-1", payload = """{"name":"x"}""")),
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
        db.transactionDao().insert(spend("t-1"))
        db.savedTagDao().insert(tag("tag-1"))

        database.enrolAll(memberId = "member-1", familyId = "family-1", enrolledAt = 800L)

        val stored = db.transactionDao().getById("t-1")!!
        assertEquals("member-1", stored.memberId)
        assertEquals("family-1", stored.familyId)
        assertEquals(800L, stored.updatedAt)
        assertEquals(1, stored.version)
        assertEquals(1, pending.isQueued(SyncTables.TRANSACTIONS, "t-1"))
        assertEquals(1, pending.isQueued(SyncTables.SAVED_TAGS, "tag-1"))
    }

    @Test
    fun enrollingTwiceDoesNotDuplicateTheQueue() = runTest {
        db.transactionDao().insert(spend("t-1"))

        database.enrolAll("member-1", "family-1", 800L)
        database.enrolAll("member-1", "family-1", 900L)

        assertEquals(1, pending.getAllNow().size)
    }

    @Test
    fun aRecordCarriesItsRealDirtyFlagRatherThanAnAssumedOne() = runTest {
        db.savedTagDao().insert(tag("tag-1"))
        db.savedTagDao().insert(tag("tag-2"))
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-2", queuedAt = 10L))

        val byId = database.loadRecords().associateBy { it.id }

        assertFalse(byId.getValue("tag-1").dirty)
        assertTrue(byId.getValue("tag-2").dirty)
    }

    @Test
    fun aLoadByIdsReturnsOnlyTheRequestedRows() = runTest {
        db.savedTagDao().insert(tag("tag-1"))
        db.savedTagDao().insert(tag("tag-2"))
        val binding = gateways.single { it.table == SyncTables.SAVED_TAGS }

        assertEquals(listOf("tag-2"), binding.loadByIds(listOf("tag-2", "missing")).map { it.id })
        assertEquals(emptyList<LocalRecord>(), binding.loadByIds(emptyList()))
    }

    private class RecordingSyncStateStore : SyncStateStore {
        var cursorValue = 0L
        var conflictValues = emptyList<ConflictNotice>()
        var lastSyncedAtValue = 0L
        var lastErrorValue: String? = null
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
    }
}

private fun period(id: String, budget: String = "100") = BudgetPeriod(
    id = id,
    budget = BigDecimal(budget),
    startDate = Date(0),
    finishDate = Date(1),
    actualFinishDate = null,
    currencyCode = "USD",
    totalSpent = BigDecimal.ZERO,
    isImported = false,
)

private fun archived(id: String, periodId: String) = ArchivedTransaction(
    id = id,
    periodId = periodId,
    type = TransactionType.SPENT,
    value = BigDecimal.TEN,
    date = Date(0),
    comment = "",
    category = null,
)

private fun spend(id: String) = Transaction(
    id = id,
    type = TransactionType.SPENT,
    value = BigDecimal.TEN,
    date = Date(0),
    comment = "",
    category = null,
)

private fun tag(id: String, name: String = "work") = SavedTag(id = id, name = name)

private fun goal(id: String, name: String = "holiday") = SavingsGoal(
    id = id,
    name = name,
    targetAmount = BigDecimal.TEN,
    currentAmount = BigDecimal.ZERO,
    deadline = null,
    createdAt = Date(0),
    completed = false,
)

private fun periodRecord(id: String, budget: String = "100") = LocalRecord(
    table = SyncTables.BUDGET_PERIODS,
    id = id,
    updatedAt = 100L,
    version = 1,
    deletedAt = null,
    payload = period(id, budget).businessPayload().toString(),
)

private fun archivedRecord(id: String, periodId: String, deletedAt: Long? = null) = LocalRecord(
    table = SyncTables.ARCHIVED_TRANSACTIONS,
    id = id,
    updatedAt = deletedAt ?: 100L,
    version = 1,
    deletedAt = deletedAt,
    payload = archived(id, periodId).businessPayload().toString(),
)

private fun tagRecord(id: String, name: String = "work") = LocalRecord(
    table = SyncTables.SAVED_TAGS,
    id = id,
    updatedAt = 100L,
    version = 1,
    deletedAt = null,
    payload = tag(id, name).businessPayload().toString(),
)

private fun goalRecord(id: String, payload: String = goal(id).businessPayload().toString()) = LocalRecord(
    table = SyncTables.SAVINGS_GOALS,
    id = id,
    updatedAt = 100L,
    version = 1,
    deletedAt = null,
    payload = payload,
)