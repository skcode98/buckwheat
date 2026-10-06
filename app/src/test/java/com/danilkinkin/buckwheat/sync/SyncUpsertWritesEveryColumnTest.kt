package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.dao.SavingsGoalDao

import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.DatabaseModule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Locks in the two ways a DAO insert can be a no-op on conflict, which is what made sync silently
 * drop writes:
 *
 * 1. `@Upsert` emits `INSERT OR ABORT` plus a PRIMARY-KEY-ONLY UPDATE, so on conflict nothing but
 *    the key is touched and every bookkeeping column stays frozen at its old value.
 * 2. `@Insert(onConflict = REPLACE)` emits a delete-then-insert, and `archived_transactions.period_id`
 *    is ON DELETE CASCADE off `budget_periods.id`, so re-applying a period destroys archived history.
 *
 * A correct upsert must therefore both write EVERY column on conflict and leave children alone.
 * Each test below upserts the same id twice with different sync columns and asserts the SECOND write
 * took effect, so a regression to either annotation fails here rather than in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncUpsertWritesEveryColumnTest {

    private lateinit var db: DatabaseModule
    private lateinit var transactions: TransactionDao
    private lateinit var periods: BudgetPeriodDao
    private lateinit var categories: SavedCategoryDao
    private lateinit var tags: SavedTagDao
    private lateinit var recurring: RecurringDao
    private lateinit var goals: SavingsGoalDao


    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            DatabaseModule::class.java,
        ).allowMainThreadQueries().build()
        transactions = db.transactionDao()
        periods = db.budgetPeriodDao()
        categories = db.savedCategoryDao()
        tags = db.savedTagDao()
        recurring = db.recurringDao()
        goals = db.savingsGoalDao()

    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun transactionUpsertRewritesMemberFamilyVersionAndTimestamp() = runTest {
        transactions.insert(spend("t-1"))
        transactions.insert(
            spend("t-1").copy(
                memberId = "member-2",
                familyId = "family-2",
                syncSeq = 77L,
                updatedAt = 9_000L,
                version = 42,
                comment = "second write",
            )
        )

        val stored = transactions.getById("t-1")!!
        assertEquals("member-2", stored.memberId)
        assertEquals("family-2", stored.familyId)
        assertEquals(77L, stored.syncSeq)
        assertEquals(9_000L, stored.updatedAt)
        assertEquals(42, stored.version)
        assertEquals("second write", stored.comment)
        assertEquals(1, transactions.getAllNow().size)
    }

    @Test
    fun transactionUpsertClearsATombstoneAndStampsThePulledRow() = runTest {
        transactions.insert(
            spend("t-1").copy(
                memberId = "member-1", familyId = "family-1", syncSeq = 5L,
                updatedAt = 1_000L, version = 7, deletedAt = 2_000L,
            )
        )
        transactions.insert(
            spend("t-1").copy(familyId = "family-1", syncSeq = 6L, updatedAt = 3_000L, version = 8)
        )

        val stored = transactions.getById("t-1")!!
        assertNull("a pulled row must not keep the old tombstone", stored.deletedAt)
        assertEquals(8, stored.version)
        assertEquals(6L, stored.syncSeq)
    }

    @Test
    fun budgetPeriodUpsertRewritesEveryColumn() = runTest {
        periods.insert(period("p-1"))
        periods.insert(
            period("p-1", budget = "222.50", currencyCode = "EUR", isImported = true).copy(
                familyId = "family-9", syncSeq = 31L, updatedAt = 4_000L, version = 12,
            )
        )

        val stored = periods.getById("p-1")!!
        assertEquals(0, BigDecimal("222.50").compareTo(stored.budget))
        assertEquals("EUR", stored.currencyCode)
        assertTrue(stored.isImported)
        assertEquals("family-9", stored.familyId)
        assertEquals(31L, stored.syncSeq)
        assertEquals(4_000L, stored.updatedAt)
        assertEquals(12, stored.version)
        assertEquals(1, periods.getAllNow().size)
    }

    @Test
    fun archivedTransactionUpsertRewritesEveryColumn() = runTest {
        periods.insert(period("p-1"))
        periods.insertArchivedTransactions(listOf(archived("a-1", "p-1")))
        periods.insertArchivedTransactions(
            listOf(
                archived("a-1", "p-1").copy(
                    category = "SHOPPING",
                    memberId = "member-3",
                    familyId = "family-3",
                    syncSeq = 21L,
                    updatedAt = 5_000L,
                    version = 5,
                )
            )
        )

        val stored = periods.getArchivedById("a-1")!!
        assertEquals("SHOPPING", stored.category)
        assertEquals("member-3", stored.memberId)
        assertEquals("family-3", stored.familyId)
        assertEquals(21L, stored.syncSeq)
        assertEquals(5_000L, stored.updatedAt)
        assertEquals(5, stored.version)
        assertEquals(1, periods.getAllArchivedNow().size)
    }

    /**
     * The reason REPLACE cannot come back: rewriting a period in place must leave its archived rows
     * alone. A delete-then-insert upsert would cascade them away and there would be nothing to assert.
     */
    @Test
    fun rewritingAPeriodDoesNotCascadeAwayItsArchivedRows() = runTest {
        periods.insert(period("p-1"))
        periods.insertArchivedTransactions(
            listOf(
                archived("a-1", "p-1"),
                archived("a-2", "p-1").copy(memberId = "member-2", familyId = "family-2", version = 3),
            )
        )

        periods.insert(
            period("p-1", budget = "999").copy(familyId = "family-1", syncSeq = 2L, updatedAt = 8L, version = 2)
        )

        assertEquals(listOf("a-1", "a-2"), periods.getAllArchivedNow().map { it.id }.sorted())
        assertEquals("member-2", periods.getArchivedById("a-2")!!.memberId)
    }

    @Test
    fun savedTagUpsertRewritesEveryColumn() = runTest {
        tags.insert(SavedTag(id = "tag-1", name = "work"))
        tags.insert(
            SavedTag(
                id = "tag-1", name = "renamed", familyId = "family-4", syncSeq = 13L,
                updatedAt = 6_000L, version = 4,
            )
        )

        val stored = tags.getById("tag-1")!!
        assertEquals("renamed", stored.name)
        assertEquals("family-4", stored.familyId)
        assertEquals(13L, stored.syncSeq)
        assertEquals(6_000L, stored.updatedAt)
        assertEquals(4, stored.version)
        assertEquals(1, tags.getAllNow().size)
    }

    @Test
    fun savedCategoryUpsertRewritesEveryColumn() = runTest {
        categories.insert(SavedCategory(id = "c-1", name = "coffee"))
        categories.insert(
            SavedCategory(
                id = "c-1", name = "tea", emoji = "🍵", familyId = "family-5", syncSeq = 17L,
                updatedAt = 7_000L, version = 6,
            )
        )

        val stored = categories.getById("c-1")!!
        assertEquals("tea", stored.name)
        assertEquals("🍵", stored.emoji)
        assertEquals("family-5", stored.familyId)
        assertEquals(17L, stored.syncSeq)
        assertEquals(7_000L, stored.updatedAt)
        assertEquals(6, stored.version)
        assertEquals(1, categories.getAllNow().size)
    }

    @Test
    fun recurringTemplateUpsertRewritesEveryColumn() = runTest {
        recurring.insert(RecurringTemplate(id = "r-1", amount = BigDecimal("9.99"), comment = "rent", dayOfMonth = 1))
        recurring.insert(
            RecurringTemplate(
                id = "r-1", amount = BigDecimal("19.99"), comment = "rent raised", dayOfMonth = 3,
                enabled = false, familyId = "family-6", syncSeq = 19L, updatedAt = 8_000L, version = 9,
            )
        )

        val stored = recurring.getById("r-1")!!
        assertEquals(0, BigDecimal("19.99").compareTo(stored.amount))
        assertEquals("rent raised", stored.comment)
        assertEquals(3, stored.dayOfMonth)
        assertEquals(false, stored.enabled)
        assertEquals("family-6", stored.familyId)
        assertEquals(19L, stored.syncSeq)
        assertEquals(8_000L, stored.updatedAt)
        assertEquals(9, stored.version)
        assertEquals(1, recurring.getAllNow().size)
    }

    @Test
    fun savingsGoalUpsertRewritesEveryColumn() = runTest {
        goals.insert(SavingsGoal(id = "g-1", name = "holiday", targetAmount = BigDecimal.TEN))
        goals.insert(
            SavingsGoal(
                id = "g-1", name = "holiday 2027", targetAmount = BigDecimal("1000.00"),
                currentAmount = BigDecimal("250.00"), deadline = Date(2_000), createdAt = Date(500),
                completed = true, familyId = "family-7", syncSeq = 23L, updatedAt = 9_000L, version = 11,
            )
        )

        val stored = goals.getById("g-1")!!
        assertEquals("holiday 2027", stored.name)
        assertEquals(0, BigDecimal("1000.00").compareTo(stored.targetAmount))
        assertEquals(0, BigDecimal("250.00").compareTo(stored.currentAmount))
        assertEquals(Date(2_000), stored.deadline)
        assertEquals(Date(500), stored.createdAt)
        assertEquals(true, stored.completed)
        assertEquals("family-7", stored.familyId)
        assertEquals(23L, stored.syncSeq)
        assertEquals(9_000L, stored.updatedAt)
        assertEquals(11, stored.version)
        assertEquals(1, goals.getAllNow().size)
    }

    /**
     * The whole point of the DAO method in production terms: `SyncTableBinding.upsert` only ever
     * inserts, so if the insert does not overwrite, `RoomSyncDatabase.apply` cannot update any row it
     * has already pulled and `enrolAll` cannot stamp an existing row.
     */
    @Test
    fun aSyncGatewayUpsertOverwritesAPreviouslyPulledRow() = runTest {
        val gateway = SyncBindings(db.pendingMutationDao()).gateways(
            transactionDao = transactions,
            budgetPeriodDao = periods,
            savedCategoryDao = categories,
            savedTagDao = tags,
            recurringDao = recurring,
            savingsGoalDao = goals,
        ).single { it.table == SyncTables.TRANSACTIONS }

        gateway.upsert(pulledTransaction("t-9", memberId = "member-1", familyId = "family-1", version = 2))
        gateway.upsert(pulledTransaction("t-9", memberId = "member-7", familyId = "family-7", version = 3))

        val stored = transactions.getById("t-9")!!
        assertEquals("member-7", stored.memberId)
        assertEquals("family-7", stored.familyId)
        assertEquals(3, stored.version)
        assertEquals(2_000L, stored.updatedAt)
        assertEquals(listOf("t-9"), transactions.getAllNow().map { it.id })
    }

    /**
     * `enrolAll` stamps the columns it read back out. With a primary-key-only upsert the row came
     * back unstamped, so enrolment was a silent no-op.
     */
    @Test
    fun enrolmentStampsAnAlreadyStoredRow() = runTest {
        tags.insert(SavedTag(id = "tag-1", name = "work"))
        transactions.insert(spend("t-1"))

        val database = RoomSyncDatabase(
            gateways = SyncBindings(db.pendingMutationDao()).gateways(
                transactionDao = transactions,
                budgetPeriodDao = periods,
                savedCategoryDao = categories,
                savedTagDao = tags,
                recurringDao = recurring,
                savingsGoalDao = goals,
                familyStateDao = familyState,
                periodLimitDao = limits,
                spendAssignmentDao = assignments,
            ),
            pendingMutationDao = db.pendingMutationDao(),
            syncStateStore = NoOpTestSyncStateStore,
            runInTransaction = { block -> block() },
        )

        database.enrolAll(memberId = "member-5", familyId = "family-5", enrolledAt = 12_345L)

        val stamped = transactions.getById("t-1")!!
        assertEquals("member-5", stamped.memberId)
        assertEquals("family-5", stamped.familyId)
        assertEquals(12_345L, stamped.updatedAt)
        assertEquals("family-5", tags.getById("tag-1")!!.familyId)
    }
}

// Deliberately NOT named `NoOpSyncStateStore`: production already ships `internal object
// NoopSyncStateStore` in the same package, and the two class files differ only by the case of one
// letter. On a case-insensitive filesystem they land on the same output path, one overwrites the
// other, and the survivor loads under the wrong name, which surfaces as
// NoClassDefFoundError: com/danilkinkin/buckwheat/sync/NoopSyncStateStore (wrong name: ...NoOp...).
private object NoOpTestSyncStateStore : SyncStateStore {
    override fun cursor() = kotlinx.coroutines.flow.flowOf(0L)
    override suspend fun readCursor() = 0L
    override suspend fun writeCursor(cursor: Long) = Unit
    override fun conflicts() = kotlinx.coroutines.flow.flowOf(emptyList<ConflictNotice>())
    override suspend fun readConflicts() = emptyList<ConflictNotice>()
    override suspend fun replaceConflicts(conflicts: List<ConflictNotice>) = Unit
    override fun lastSyncedAt() = kotlinx.coroutines.flow.flowOf(0L)
    override fun lastError() = kotlinx.coroutines.flow.flowOf(null)
    override suspend fun markSynced(at: Long) = Unit
    override suspend fun markFailed(reason: String?) = Unit
    override suspend fun clear() = Unit
}

private fun spend(id: String) = Transaction(
    id = id,
    type = TransactionType.SPENT,
    value = BigDecimal.TEN,
    date = Date(0),
    comment = "",
    category = null,
)

private fun period(
    id: String,
    budget: String = "100",
    currencyCode: String = "USD",
    isImported: Boolean = false,
) = BudgetPeriod(
    id = id,
    budget = BigDecimal(budget),
    startDate = Date(0),
    finishDate = Date(1),
    actualFinishDate = null,
    currencyCode = currencyCode,
    totalSpent = BigDecimal.ZERO,
    isImported = isImported,
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

private fun pulledTransaction(
    id: String,
    memberId: String?,
    familyId: String?,
    version: Int,
) = LocalRecord(
    table = SyncTables.TRANSACTIONS,
    id = id,
    updatedAt = 2_000L,
    version = version,
    deletedAt = null,
    payload = Transaction(
        id = id,
        type = TransactionType.SPENT,
        value = BigDecimal.TEN,
        date = Date(1_000),
        comment = "pulled",
    ).businessPayload().toString(),
    memberId = memberId,
    familyId = familyId,
    syncSeq = 55L,
)