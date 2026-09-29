package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.entities.PendingMutation
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.FakeBudgetPeriodDao
import com.danilkinkin.buckwheat.di.FakePendingMutationDao
import com.danilkinkin.buckwheat.di.FakeRecurringDao
import com.danilkinkin.buckwheat.di.FakeSavedCategoryDao
import com.danilkinkin.buckwheat.di.FakeSavedTagDao
import com.danilkinkin.buckwheat.di.FakeSavingsGoalDao
import com.danilkinkin.buckwheat.di.FakeTransactionDao
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSyncDatabaseTest {

    private val pending = FakePendingMutationDao()
    private val transactions = FakeTransactionDao()
    private val periods = FakeBudgetPeriodDao()
    private val categories = FakeSavedCategoryDao()
    private val tags = FakeSavedTagDao()
    private val recurring = FakeRecurringDao()
    private val goals = FakeSavingsGoalDao()
    private val state = FakeSyncStateStore()

    private fun database() = RoomSyncDatabase(
        gateways = SyncBindings(pending).gateways(
            transactionDao = transactions,
            budgetPeriodDao = periods,
            savedCategoryDao = categories,
            savedTagDao = tags,
            recurringDao = recurring,
            savingsGoalDao = goals,
        ),
        pendingMutationDao = pending,
        syncStateStore = state,
    )

    @Test
    fun everySyncTableIsBound() {
        assertEquals(
            listOf(
                SyncTables.TRANSACTIONS,
                SyncTables.ARCHIVED_TRANSACTIONS,
                SyncTables.BUDGET_PERIODS,
                SyncTables.SAVED_CATEGORIES,
                SyncTables.SAVED_TAGS,
                SyncTables.RECURRING_TEMPLATES,
                SyncTables.SAVINGS_GOALS,
            ),
            SyncBindings(pending).gateways(
                transactionDao = transactions,
                budgetPeriodDao = periods,
                savedCategoryDao = categories,
                savedTagDao = tags,
                recurringDao = recurring,
                savingsGoalDao = goals,
            ).map { it.table },
        )
    }

    @Test
    fun theCursorComesFromTheStateStore() = runTest {
        state.cursor = 12L

        assertEquals(12L, database().readCursor())
    }

    @Test
    fun aStoredRowIsExposedWithItsSyncMetadata() = runTest {
        categories.insert(SavedCategory(id = "c-1", name = "Food", familyId = "f-1", syncSeq = 3L, updatedAt = 50L, version = 2))

        val record = database().loadRecords().single { it.id == "c-1" }

        assertEquals(SyncTables.SAVED_CATEGORIES, record.table)
        assertEquals(50L, record.updatedAt)
        assertEquals(2, record.version)
        assertEquals(3L, record.syncSeq)
        assertEquals("f-1", record.familyId)
        assertTrue(record.payload.contains("Food"))
    }

    @Test
    fun aQueuedRowIsReportedAsDirty() = runTest {
        tags.insert(SavedTag(id = "tag-1", name = "work"))
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-1", 100L))

        val record = database().loadRecords().single { it.id == "tag-1" }

        assertTrue(record.dirty)
    }

    @Test
    fun anUnqueuedRowIsNotDirty() = runTest {
        tags.insert(SavedTag(id = "tag-1", name = "work"))

        assertTrue(!database().loadRecords().single { it.id == "tag-1" }.dirty)
    }

    @Test
    fun aQueuedRowIsPushedWithItsLiveState() = runTest {
        transactions.spends.add(
            transaction("t-1", familyId = "f-1", syncSeq = 4L, updatedAt = 70L, version = 3),
        )
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-1", 100L))

        val record = database().dirtyRecords().single()

        assertEquals(SyncTables.TRANSACTIONS, record.table)
        assertEquals("t-1", record.id)
        assertEquals(4L, record.syncSeq)
        assertEquals(3, record.version)
        assertTrue(record.payload.contains("coffee"))
    }

    @Test
    fun aDeletedEnrolledRowBecomesATombstone() = runTest {
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-1", 555L, isDelete = true))

        val record = database().dirtyRecords().single()

        assertEquals("t-1", record.id)
        assertEquals(555L, record.deletedAt)
        assertEquals("{}", record.payload)
        assertTrue(record.dirty)
    }

    @Test
    fun aDeletedNeverSyncedRowIsNotPushed() = runTest {
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-1", 555L, isDelete = true))

        assertEquals(555L, database().dirtyRecords().single().deletedAt)
        pending.deleteQueued(SyncTables.TRANSACTIONS, listOf("t-1"))
    }

    @Test
    fun aRemoteCleanRowIsWrittenAndDequeued() = runTest {
        transactions.spends.add(transaction("t-1", familyId = "f-1", syncSeq = 4L, updatedAt = 70L, version = 3))
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-1", 100L))

        database().apply(
            SyncApply(
                records = listOf(
                    LocalRecord(
                        table = SyncTables.TRANSACTIONS,
                        id = "t-1",
                        updatedAt = 90L,
                        version = 4,
                        deletedAt = null,
                        payload = transaction("t-1", comment = "tea", updatedAt = 90L, version = 4).businessPayload().toString(),
                        dirty = false,
                        memberId = null,
                        familyId = "f-1",
                        syncSeq = 11L,
                    ),
                ),
                cursor = 11L,
                conflicts = emptyList(),
            ),
        )

        val stored = transactions.getById("t-1")
        assertNotNull(stored)
        assertEquals("tea", stored?.comment)
        assertEquals(11L, stored?.syncSeq)
        assertEquals(4, stored?.version)
        assertTrue(pending.forTable(SyncTables.TRANSACTIONS).isEmpty())
    }

    @Test
    fun aRemoteTombstoneRemovesTheLocalRow() = runTest {
        tags.insert(SavedTag(id = "tag-1", name = "work"))

        database().apply(
            SyncApply(
                records = listOf(
                    LocalRecord(
                        table = SyncTables.SAVED_TAGS,
                        id = "tag-1",
                        updatedAt = 90L,
                        version = 2,
                        deletedAt = 90L,
                        payload = "{}",
                        dirty = false,
                        memberId = null,
                        familyId = "f-1",
                        syncSeq = 11L,
                    ),
                ),
                cursor = 11L,
                conflicts = emptyList(),
            ),
        )

        assertNull(tags.getById("tag-1"))
        assertTrue(pending.forTable(SyncTables.SAVED_TAGS).isEmpty())
    }

    @Test
    fun aDirtyLocalRowIsNeverOverwrittenByTheServer() = runTest {
        categories.insert(SavedCategory(id = "c-1", name = "local", emoji = "x"))
        pending.enqueue(PendingMutation(SyncTables.SAVED_CATEGORIES, "c-1", 100L))

        database().apply(
            SyncApply(
                records = listOf(
                    LocalRecord(
                        table = SyncTables.SAVED_CATEGORIES,
                        id = "c-1",
                        updatedAt = 5L,
                        version = 1,
                        deletedAt = null,
                        payload = SavedCategory(id = "c-1", name = "local", emoji = "x").businessPayload().toString(),
                        dirty = true,
                        memberId = null,
                        familyId = "f-1",
                        syncSeq = 11L,
                    ),
                ),
                cursor = 11L,
                conflicts = emptyList(),
            ),
        )

        assertEquals("local", categories.getById("c-1")?.name)
        assertEquals(setOf("c-1"), pending.idsFor(SyncTables.SAVED_CATEGORIES))
    }

    @Test
    fun aStillDirtyRowIsNotDequeued() = runTest {
        tags.insert(SavedTag(id = "tag-1", name = "work"))
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-1", 100L))

        database().apply(
            SyncApply(
                records = listOf(
                    LocalRecord(
                        table = SyncTables.SAVED_TAGS,
                        id = "tag-1",
                        updatedAt = 90L,
                        version = 2,
                        deletedAt = null,
                        payload = "{\"name\":\"work\"}",
                        dirty = true,
                        memberId = null,
                        familyId = "f-1",
                        syncSeq = 11L,
                    ),
                ),
                cursor = 11L,
                conflicts = emptyList(),
            ),
        )

        assertEquals(setOf("tag-1"), pending.idsFor(SyncTables.SAVED_TAGS))
    }

    @Test
    fun applyPersistsTheCursorAndTheConflicts() = runTest {
        val conflicts = listOf(ConflictNotice(SyncTables.SAVED_TAGS, "tag-1", "member-2"))

        database().apply(SyncApply(records = emptyList(), cursor = 77L, conflicts = conflicts))

        assertEquals(77L, state.cursor)
        assertEquals(conflicts, state.conflicts)
    }

    @Test
    fun applyOnlyClearsTheTablesItTouched() = runTest {
        transactions.spends.add(transaction("t-1"))
        pending.enqueue(PendingMutation(SyncTables.TRANSACTIONS, "t-1", 100L))
        pending.enqueue(PendingMutation(SyncTables.SAVED_TAGS, "tag-9", 100L))

        database().apply(
            SyncApply(
                records = listOf(
                    LocalRecord(
                        table = SyncTables.TRANSACTIONS,
                        id = "t-1",
                        updatedAt = 90L,
                        version = 2,
                        deletedAt = null,
                        payload = transaction("t-1").businessPayload().toString(),
                        dirty = false,
                        memberId = null,
                        familyId = "f-1",
                        syncSeq = 5L,
                    ),
                ),
                cursor = 5L,
                conflicts = emptyList(),
            ),
        )

        assertTrue(pending.forTable(SyncTables.TRANSACTIONS).isEmpty())
        assertEquals(setOf("tag-9"), pending.idsFor(SyncTables.SAVED_TAGS))
    }

    @Test
    fun aRenamedCategoryDoesNotTripTheUniqueNameIndex() = runTest {
        categories.insert(SavedCategory(id = "c-1", name = "old"))

        database().apply(
            SyncApply(
                records = listOf(
                    LocalRecord(
                        table = SyncTables.SAVED_CATEGORIES,
                        id = "c-1",
                        updatedAt = 90L,
                        version = 2,
                        deletedAt = null,
                        payload = SavedCategory(id = "c-1", name = "new", emoji = "🍔").businessPayload().toString(),
                        dirty = false,
                        memberId = null,
                        familyId = "f-1",
                        syncSeq = 5L,
                    ),
                ),
                cursor = 5L,
                conflicts = emptyList(),
            ),
        )

        assertEquals("new", categories.getById("c-1")?.name)
    }

    private fun transaction(
        id: String,
        comment: String = "coffee",
        familyId: String? = null,
        syncSeq: Long = 0L,
        updatedAt: Long = 0L,
        version: Int = 1,
    ) = Transaction(
        id = id,
        type = TransactionType.SPENT,
        value = BigDecimal("4.50"),
        date = Date(1_700_000_000_000L),
        comment = comment,
        familyId = familyId,
        syncSeq = syncSeq,
        updatedAt = updatedAt,
        version = version,
    )

    @Test
    fun enrollingStampsEveryRowWithTheFamilyAndMember() = runTest {
        transactions.insert(transaction(id = "t-1"))
        tags.insert(SavedTag(id = "tag-1", name = "home"))

        database().enrolAll(memberId = "member-1", familyId = "family-1", enrolledAt = 700L)

        val enrolledTransaction = transactions.getById("t-1")
        assertNotNull(enrolledTransaction)
        assertEquals("family-1", enrolledTransaction?.familyId)
        assertEquals("member-1", enrolledTransaction?.memberId)
        assertEquals(700L, enrolledTransaction?.updatedAt)
        assertEquals(1, enrolledTransaction?.version)
        assertNull(enrolledTransaction?.deletedAt)

        val enrolledTag = tags.getById("tag-1")
        assertNotNull(enrolledTag)
        assertEquals("family-1", enrolledTag?.familyId)
        assertEquals(700L, enrolledTag?.updatedAt)
    }

    @Test
    fun enrollingQueuesEveryRowForTheNextPush() = runTest {
        transactions.insert(transaction(id = "t-1"))
        categories.insert(SavedCategory(id = "c-1", name = "food"))

        database().enrolAll(memberId = "member-1", familyId = "family-1", enrolledAt = 700L)

        assertEquals(setOf("t-1"), pending.idsFor(SyncTables.TRANSACTIONS))
        assertEquals(setOf("c-1"), pending.idsFor(SyncTables.SAVED_CATEGORIES))
    }

    @Test
    fun enrolledRowsAreReportedAsDirty() = runTest {
        transactions.insert(transaction(id = "t-1"))

        database().enrolAll(memberId = "member-1", familyId = "family-1", enrolledAt = 700L)

        val dirty = database().dirtyRecords()
        assertEquals(listOf("t-1"), dirty.map { it.id })
    }

    @Test
    fun enrollingTwiceDoesNotDuplicateTheQueue() = runTest {
        transactions.insert(transaction(id = "t-1"))

        database().enrolAll(memberId = "member-1", familyId = "family-1", enrolledAt = 700L)
        database().enrolAll(memberId = "member-1", familyId = "family-1", enrolledAt = 800L)

        assertEquals(1, pending.idsFor(SyncTables.TRANSACTIONS).size)
        assertEquals(1, pending.count())
    }

    private class FakeSyncStateStore : SyncStateStore {
        var cursor: Long = 0L
        var conflicts: List<ConflictNotice> = emptyList()

        override fun cursor(): kotlinx.coroutines.flow.Flow<Long> =
            kotlinx.coroutines.flow.flowOf(cursor)

        override suspend fun readCursor(): Long = cursor

        override suspend fun writeCursor(value: Long) {
            cursor = value
        }

        override fun conflicts(): kotlinx.coroutines.flow.Flow<List<ConflictNotice>> =
            kotlinx.coroutines.flow.flowOf(conflicts)

        override suspend fun readConflicts(): List<ConflictNotice> = conflicts

        override suspend fun replaceConflicts(value: List<ConflictNotice>) {
            conflicts = value
        }

        override suspend fun clear() {
            cursor = 0L
            conflicts = emptyList()
        }
    }
}
