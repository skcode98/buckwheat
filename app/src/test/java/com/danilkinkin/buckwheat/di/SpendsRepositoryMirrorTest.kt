package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.categories.CategoryAssigner
import com.danilkinkin.buckwheat.data.categories.CategoryAssignmentScheduler
import com.danilkinkin.buckwheat.data.dao.FamilyTransactionDao
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.settings.FakeSyncDirtyMarker
import com.danilkinkin.buckwheat.sync.SyncTables
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import com.danilkinkin.buckwheat.util.toLocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpendsRepositoryMirrorTest {

    lateinit var spendsRepository: SpendsRepository
    lateinit var transactionDao: FakeTransactionDao
    lateinit var syncDirtyMarker: FakeSyncDirtyMarker

    val pendingMutationDao: FakePendingMutationDao
        get() = syncDirtyMarker.pendingMutationDao
    val currentDateUseCase: FakeGetCurrentDateUseCase = FakeGetCurrentDateUseCase()
    val budgetPeriodDao: FakeBudgetPeriodDao = FakeBudgetPeriodDao()
    val familySessionStore = FakeSessionStore()
    val familyTransactionDao = FakeFamilyTransactionDao()

    @Before
    fun init() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        transactionDao = FakeTransactionDao()
        syncDirtyMarker = FakeSyncDirtyMarker(transactionDao = transactionDao)
        spendsRepository = SpendsRepository(
            context = context,
            transactionDao,
            FakeSavedTagDao(),
            FakeSavedCategoryDao(),
            budgetPeriodDao,
            currentDateUseCase,
            CategoryAssignmentScheduler(
                CategoryAssigner(context, transactionDao, budgetPeriodDao, syncDirtyMarker)
            ),
            CategoryCapTracker(context, SettingsRepository(context), transactionDao),
            BudgetCalculator(context, currentDateUseCase),
            syncDirtyMarker,
            familySessionStore,
            familyTransactionDao,
        )
    }

    private suspend fun activateSession() {
        familySessionStore.save("https://server", "token", "family-1", "member-1")
    }

    private suspend fun setBudget(budget: Long = 1000, days: Long = 9) {
        spendsRepository.setBudget(
            budget.toBigDecimal(),
            currentDateUseCase.value.toLocalDate().plusDays(days).toDate()
        )
    }

    @Test
    fun withoutSessionAddSpentQueuesButDoesNotMirror() = runTest {
        setBudget()
        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        pendingMutationDao.deleteAll()

        spendsRepository.addSpent(spend)

        assertEquals("spend-1", pendingMutationDao.forTable(SyncTables.TRANSACTIONS).single().recordId)
        assertTrue(familyTransactionDao.rows.isEmpty())
    }

    @Test
    fun addSpentMirrorsWithSessionMemberId() = runTest {
        activateSession()
        setBudget()
        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
            comment = "groceries",
            category = "FOOD",
            memberId = "other-member",
        )
        pendingMutationDao.deleteAll()

        spendsRepository.addSpent(spend)

        val mirror = familyTransactionDao.getById("spend-1")
        assertEquals("spend-1", pendingMutationDao.forTable(SyncTables.TRANSACTIONS).single().recordId)
        requireNotNull(mirror)
        assertEquals("spend-1", mirror.id)
        assertEquals("member-1", mirror.memberId)
        assertEquals(TransactionType.SPENT, mirror.type)
        assertEquals(10.toBigDecimal(), mirror.value)
        assertEquals("groceries", mirror.comment)
        assertEquals("FOOD", mirror.category)
        // The mirror is read back AFTER markUpsert, so it carries the stamped values: the stamp
        // advanced the personal row and those are exactly what travel in the push payload.
        val stamped = transactionDao.getById("spend-1")
        requireNotNull(stamped)
        assertEquals(spend.version + 1, stamped.version)
        assertEquals(stamped.syncSeq, mirror.syncSeq)
        assertEquals(stamped.updatedAt, mirror.updatedAt)
        assertEquals(stamped.deletedAt, mirror.deletedAt)
        assertEquals(stamped.version, mirror.version)
    }

    @Test
    fun stampExposedMirrorCarriesTheStampedValues() = runTest {
        activateSession()
        setBudget()
        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )

        spendsRepository.addSpent(spend)

        val personal = transactionDao.getById("spend-1")
        val mirror = familyTransactionDao.getById("spend-1")
        requireNotNull(personal)
        requireNotNull(mirror)
        // mirrorWrite copies the freshly stamped version/updated_at onto the mirror, so a push
        // carries an accepted stamp instead of a frozen one (which the server would reject).
        assertEquals(spend.version + 1, personal.version)
        assertEquals(personal.version, mirror.version)
        assertEquals(personal.updatedAt, mirror.updatedAt)
        assertTrue(personal.updatedAt > 0L)
    }

    @Test
    fun removeSpentQueuesATombstoneFromThePushedMirrorRow() = runTest {
        activateSession()
        setBudget()
        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        pendingMutationDao.deleteAll()

        spendsRepository.addSpent(spend)
        val mirrorIndex = familyTransactionDao.rows.indexOfFirst { it.id == "spend-1" }
        require(mirrorIndex >= 0)
        // Production shape: the fresh personal row carries familyId=null / syncSeq=0 and only the
        // mirror row acknowledges the server once a push has stamped its sync_seq.
        familyTransactionDao.rows[mirrorIndex] = familyTransactionDao.rows[mirrorIndex].copy(syncSeq = 7L)

        spendsRepository.removeSpent(spend)

        assertNull(familyTransactionDao.getById("spend-1"))
        assertEquals(true, pendingMutationDao.forTable(SyncTables.TRANSACTIONS).single().isDelete)
    }

    @Test
    fun removeSpentBeforeFirstPushQueuesNoTombstone() = runTest {
        activateSession()
        setBudget()
        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        pendingMutationDao.deleteAll()

        spendsRepository.addSpent(spend)
        spendsRepository.removeSpent(spend)

        // Nothing has ever reached the server, so the queued upsert is dropped and no tombstone
        // takes its place.
        assertEquals(0, pendingMutationDao.count())
        assertNull(familyTransactionDao.getById("spend-1"))
    }

    @Test
    fun removeSpentWithoutSessionLeavesPersonalBehaviourUntouched() = runTest {
        activateSession()
        setBudget()
        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        pendingMutationDao.deleteAll()
        spendsRepository.addSpent(spend)
        pendingMutationDao.deleteAll()

        familySessionStore.clear()
        spendsRepository.removeSpent(spend)

        // No session, no mirror: the personal row is gone, the queue entry is gone, and the
        // mirror row from before the session ended is left in place untouched.
        assertEquals(0, pendingMutationDao.count())
        assertEquals("spend-1", requireNotNull(familyTransactionDao.getById("spend-1")).id)
    }

    @Test
    fun importMirrorsInPeriodRowsOnly() = runTest {
        activateSession()
        setBudget()
        val inPeriod = Transaction(
            id = "in-1",
            type = TransactionType.SPENT,
            value = 5.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        val outOfPeriod = Transaction(
            id = "out-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value.toLocalDateTime().minusDays(1).toDate(),
        )

        spendsRepository.importTransactions(listOf(inPeriod, outOfPeriod))

        assertEquals("in-1", requireNotNull(familyTransactionDao.getById("in-1")).id)
        assertNull(familyTransactionDao.getById("out-1"))
    }

    @Test
    fun changeBudgetMirrorsTheUpdatedIncomeMarker() = runTest {
        activateSession()
        setBudget()
        val incomeId = spendsRepository.getAllTransactions().first()
            .first { it.type == TransactionType.INCOME }.id
        pendingMutationDao.deleteAll()

        spendsRepository.changeBudget(
            2000.toBigDecimal(),
            currentDateUseCase.value.toLocalDate().plusDays(9).toDate(),
        )

        val mirror = familyTransactionDao.getById(incomeId)
        requireNotNull(mirror)
        assertEquals(TransactionType.INCOME, mirror.type)
        assertEquals(2000.toBigDecimal(), mirror.value)
        assertEquals("member-1", mirror.memberId)
    }

    @Test
    fun setDailyBudgetMirrorsTheNewMarker() = runTest {
        activateSession()
        setBudget()
        familyTransactionDao.rows.clear()

        spendsRepository.setDailyBudget(50.toBigDecimal())

        val markers = familyTransactionDao.rows.filter { it.type == TransactionType.SET_DAILY_BUDGET }
        assertEquals(1, markers.size)
        assertEquals(50.toBigDecimal(), markers.single().value)
    }

    @Test
    fun updateDailyBudgetMirrorsTheUpdatedMarker() = runTest {
        activateSession()
        setBudget()
        familyTransactionDao.rows.clear()
        spendsRepository.setDailyBudget(50.toBigDecimal())

        spendsRepository.updateDailyBudget(150.toBigDecimal())

        val markers = familyTransactionDao.rows.filter { it.type == TransactionType.SET_DAILY_BUDGET }
        assertEquals(1, markers.size)
        assertEquals(150.toBigDecimal(), markers.single().value)
    }

    @Test
    fun setBudgetMirrorsTheNewIncomeMarkerAndRemovesTheOld() = runTest {
        activateSession()
        setBudget()
        val oldIncomeId = spendsRepository.getAllTransactions().first()
            .first { it.type == TransactionType.INCOME }.id

        setBudget(budget = 2000, days = 9)

        assertNull(familyTransactionDao.getById(oldIncomeId))
        val markers = familyTransactionDao.rows.filter { it.type == TransactionType.INCOME }
        assertEquals(1, markers.size)
        assertEquals(2000.toBigDecimal(), markers.single().value)
    }
}

class FakeFamilyTransactionDao : FamilyTransactionDao {
    val rows = mutableListOf<FamilyTransaction>()

    override fun getAllInPeriod(startDate: Date, endDate: Date): List<FamilyTransaction> =
        rows.filter { it.date.time in startDate.time..endDate.time }

    override fun getAllNow(): List<FamilyTransaction> = rows.toList()

    override fun getById(id: String): FamilyTransaction? = rows.firstOrNull { it.id == id }

    override suspend fun upsertOne(
        id: String,
        type: TransactionType,
        value: BigDecimal,
        date: Date,
        comment: String,
        category: String?,
        memberId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    ) {
        val row = FamilyTransaction(
            id = id,
            type = type,
            value = value,
            date = date,
            comment = comment,
            category = category,
            memberId = memberId,
            syncSeq = syncSeq,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            version = version,
        )
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = row else rows.add(row)
    }

    override suspend fun deleteById(id: String): Int {
        val before = rows.size
        rows.removeAll { it.id == id }
        return before - rows.size
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun updateMemberId(id: String, memberId: String) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(memberId = memberId)
    }

    override suspend fun updateCategory(id: String, category: String?, version: Int, updatedAt: Long) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(category = category, version = version, updatedAt = updatedAt)
    }

    override suspend fun deleteRowsWhereMemberDiffersFrom(memberId: String): Int {
        val before = rows.size
        rows.removeAll { it.memberId != null && it.memberId != memberId }
        return before - rows.size
    }

    override suspend fun attributeNullMembersTo(memberId: String): Int {
        val before = rows.size
        rows.replaceAll { if (it.memberId == null) it.copy(memberId = memberId) else it }
        return before - rows.size
    }
}
