package com.danilkinkin.buckwheat.di

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.categories.CategoryAssigner
import com.danilkinkin.buckwheat.data.categories.CategoryAssignmentScheduler
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpendsRepositoryTest {

    lateinit var spendsRepository: SpendsRepository

    val currentDateUseCase: FakeGetCurrentDateUseCase = FakeGetCurrentDateUseCase()
    val budgetPeriodDao: FakeBudgetPeriodDao = FakeBudgetPeriodDao()
    val syncDirtyMarker = FakeSyncDirtyMarker()
    val pendingMutationDao: FakePendingMutationDao = syncDirtyMarker.pendingMutationDao

    @Before
    fun init() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transactionDao = FakeTransactionDao()
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
        )
    }

    // Set budget 1000 for 10 days
    // Start daily budget 100
    private suspend fun setBudget(budget: Long = 1000, days: Long = 9) {
        spendsRepository.setBudget(
            budget.toBigDecimal(),
            currentDateUseCase.value.toLocalDate().plusDays(days).toDate()
        )
    }

    // Update daily budget. Should be called after change day
    private suspend fun distributeBudget() {
        spendsRepository.setDailyBudget(spendsRepository.whatBudgetForDay(
            applyTodaySpends = true,
        ))
    }

    private suspend fun distributeBudgetAddToday() {
        val notSpent = spendsRepository.howMuchNotSpent(
            excludeSkippedPart = true,
        )
        val dailyBudget = spendsRepository.nextDayBudget()
        val whatBudgetForDay = spendsRepository.whatBudgetForDay(
            excludeCurrentDay = false,
            applyTodaySpends = true,
            notCommittedSpent = dailyBudget,
        )

        Log.d("SpendsRepositoryTest", "notSpent = $notSpent dailyBudget = $dailyBudget whatBudgetForDay = $whatBudgetForDay")

        spendsRepository.setDailyBudget(notSpent)
    }

    private fun rewindTime(days: Long, hours: Long = 0) {
        Log.d("SpendsRepositoryTest", "rewind time for days = $days hours = $hours")
        currentDateUseCase.value = currentDateUseCase.value
            .toLocalDateTime()
            .plusDays(days)
            .plusHours(hours)
            .toDate()
    }

    // Check budget set correctly
    @Test
    fun setBudgetTest() = runTest {
        setBudget()

        assert(spendsRepository.getBudget().first() == 1000.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 100.toBigDecimal().setScale(2))
    }

    // Check budget set correctly distribute after change day
    @Test
    fun reCalcBudgetAfterChangeDayTest() = runTest {
        setBudget()
        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() == 200.toBigDecimal().setScale(2))

        distributeBudget()

        assert(spendsRepository.getBudget().first() == 1000.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 111.11.toBigDecimal().setScale(2))
    }

    // Check budget set correctly distribute after change few days
    @Test
    fun reCalcBudgetAfterSkipFewDayTest() = runTest {
        setBudget()
        rewindTime(2)

        assert(spendsRepository.howMuchNotSpent() == 300.toBigDecimal().setScale(2))

        distributeBudget()

        assert(spendsRepository.getBudget().first() == 1000.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 125.toBigDecimal().setScale(2))
    }

    // Check budget set correctly distribute after change few days
    // Init budget 1000 for 10 days
    // [Day 1] Spend 10 | dailyBudget = 1000 / 10 = 100 | not spent = 90
    // [Day 2] Skip | dailyBudget = (1000 - 10) / 9 = 110 | not spent = 190
    // [Day 3] Skip | dailyBudget = (1000 - 10) / 8 = 125 | not spent = 290
    // [Day 4] Skip | dailyBudget = (1000 - 10) / 7 = 142.86 | not spent = 390
    // [Day 5] Skip | dailyBudget = (1000 - 10) / 6 = 166.67 | not spent = 490
    // [Day 6] Skip | dailyBudget = (1000 - 10) / 5 = 200 | not spent = 590
    // [Day 7] Skip | dailyBudget = (1000 - 10) / 4 = 250 | not spent = 690
    // [Day 8] Skip | dailyBudget = (1000 - 10) / 3 = 333.33 | not spent = 790
    // [Day 9] Skip | dailyBudget = (1000 - 10) / 2 = 500 | not spent = 890
    // [Day 10] Skip | dailyBudget = (1000 - 10) / 1 = 990 | not spent = 990
    @Test
    fun reCalcBudgetAfterSkipFewDayWithSpentTest() = runTest {
        setBudget()

        spendsRepository.addSpent(Transaction(type = TransactionType.SPENT, value = 10.toBigDecimal(), date = currentDateUseCase.value))

        assert(spendsRepository.howMuchNotSpent() == 90.toBigDecimal().setScale(2))
        rewindTime(1)
        assert(spendsRepository.howMuchNotSpent() == 190.toBigDecimal().setScale(2))
        rewindTime(1)
        assert(spendsRepository.howMuchNotSpent() == 290.toBigDecimal().setScale(2))
        rewindTime(1)
        assert(spendsRepository.howMuchNotSpent() == 390.toBigDecimal().setScale(2))
        rewindTime(1)
        assert(spendsRepository.howMuchNotSpent() == 490.toBigDecimal().setScale(2))
        rewindTime(1)
        assert(spendsRepository.howMuchNotSpent() == 590.toBigDecimal().setScale(2))
        rewindTime(1)
        assert(spendsRepository.howMuchNotSpent() == 690.toBigDecimal().setScale(2))
        rewindTime(1)
        assert(spendsRepository.howMuchNotSpent() == 790.toBigDecimal().setScale(2))
        rewindTime(1)
        assert(spendsRepository.howMuchNotSpent() == 890.toBigDecimal().setScale(2))
        rewindTime(1)
        assert(spendsRepository.howMuchNotSpent() == 990.toBigDecimal().setScale(2))

        distributeBudget()

        assert(spendsRepository.getBudget().first() == 1000.toBigDecimal().setScale(2))
        assert(spendsRepository.getDailyBudget().first() == 990.toBigDecimal().setScale(2))
    }

    // Imported entries outside the active budget period are archived into a month bucket and must not consume the current budget
    @Test
    fun importOlderThanCurrentPeriodSpendShouldNotAffectBudget() = runTest {
        setBudget()

        val olderSpend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value.toLocalDateTime().minusDays(1).toDate(),
        )

        spendsRepository.importTransactions(listOf(olderSpend))

        assert(!spendsRepository.getAllSpends().first().contains(olderSpend))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpent().first() == 0.toBigDecimal().setScale(2))

        val buckets = budgetPeriodDao.getAll().first().filter { it.isImported }
        assert(buckets.size == 1)
        val bucket = buckets.single()
        assert(bucket.isImported)
        assert(bucket.totalSpent == 10.toBigDecimal().setScale(2))
        assert(!olderSpend.date.before(bucket.startDate) && !olderSpend.date.after(bucket.finishDate))
    }

    // Removing an imported spend outside the current period must not touch the budget
    @Test
    fun removeOlderThanCurrentPeriodSpendShouldNotAffectBudget() = runTest {
        setBudget()

        val olderSpend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value.toLocalDateTime().minusDays(1).toDate(),
        )

        spendsRepository.importTransactions(listOf(olderSpend))
        spendsRepository.removeSpent(olderSpend)

        assert(!spendsRepository.getAllSpends().first().contains(olderSpend))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpent().first() == 0.toBigDecimal().setScale(2))
    }

    // Out-of-period imports are grouped by calendar month and never count toward the current budget
    @Test
    fun importOutOfPeriodSpendsAreGroupedByMonthAndDoNotAffectBudget() = runTest {
        setBudget()

        val lastMonth = currentDateUseCase.value.toLocalDate().minusMonths(1)
        val inPeriodSpend = Transaction(type = TransactionType.SPENT, value = 5.toBigDecimal(), date = currentDateUseCase.value)
        val oldSpendA = Transaction(type = TransactionType.SPENT, value = 10.toBigDecimal(), date = lastMonth.withDayOfMonth(5).toDate())
        val oldSpendB = Transaction(type = TransactionType.SPENT, value = 20.toBigDecimal(), date = lastMonth.withDayOfMonth(20).toDate())

        spendsRepository.importTransactions(listOf(inPeriodSpend, oldSpendA, oldSpendB))

        assert(spendsRepository.getAllSpends().first().contains(inPeriodSpend))
        assert(!spendsRepository.getAllSpends().first().contains(oldSpendA))
        assert(!spendsRepository.getAllSpends().first().contains(oldSpendB))

        val buckets = budgetPeriodDao.getAll().first().filter { it.isImported }
        assert(buckets.size == 1)
        val bucket = buckets.single()
        assert(bucket.startDate.toLocalDate() == lastMonth.withDayOfMonth(1))
        assert(bucket.finishDate.toLocalDate() == lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()))
        assert(bucket.totalSpent == 30.toBigDecimal().setScale(2))

        val archived = budgetPeriodDao.getTransactionsForPeriod(bucket.id).first()
        assert(archived.size == 2)
        assert(archived.all { it.periodId == bucket.id })
        assert(spendsRepository.getSpentFromDailyBudget().first() == 5.toBigDecimal().setScale(2))
    }

    // Re-importing a file whose rows were already archived must not duplicate them
    @Test
    fun reimportArchivedRowsDoesNotDuplicate() = runTest {
        setBudget()

        val olderSpend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value.toLocalDateTime().minusDays(1).toDate(),
        )

        spendsRepository.importTransactions(listOf(olderSpend))
        spendsRepository.importTransactions(listOf(olderSpend))

        val buckets = budgetPeriodDao.getAll().first().filter { it.isImported }
        val archived = buckets.flatMap {
            budgetPeriodDao.getTransactionsForPeriod(it.id).first()
        }
        assert(archived.size == 1)
        assert(
            spendsRepository.getAllSpends().first()
                .none { it.type == TransactionType.SPENT }
        )
    }

    // Comments of imported out-of-period transactions must surface as tags even though
    // they are stored in the archived table (the tag picker and Tags Management read them)
    @Test
    fun importedArchivedCommentsBecomeTags() = runTest {
        setBudget()

        val oldSpend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value.toLocalDateTime().minusDays(1).toDate(),
            comment = "groceries",
        )

        spendsRepository.importTransactions(listOf(oldSpend))

        val tags = spendsRepository.getAllTags().first()

        assert(tags.contains("groceries"))
    }

    // Distinct category values assigned to transactions surface via getAllCategories so the
    // Categories Management sheet can show (and offer to re-save) them even before the user
    // has added any custom categories.
    @Test
    fun getAllCategoriesMergesTransactionCategories() = runTest {
        setBudget()

        spendsRepository.addSpent(
            Transaction(
                type = TransactionType.SPENT,
                value = 10.toBigDecimal(),
                date = currentDateUseCase.value,
                comment = "groceries",
                category = "FOOD",
            )
        )
        spendsRepository.addSpent(
            Transaction(
                type = TransactionType.SPENT,
                value = 20.toBigDecimal(),
                date = currentDateUseCase.value,
                comment = "gifts",
                category = "MyCategory",
            )
        )
        spendsRepository.addSpent(
            Transaction(
                type = TransactionType.SPENT,
                value = 30.toBigDecimal(),
                date = currentDateUseCase.value,
                comment = "no category",
            )
        )

        val categories = spendsRepository.getAllCategories().first()

        assert(categories.contains("FOOD"))
        assert(categories.contains("MyCategory"))
        assert(categories.size == 2)
    }

    // Check spent in same day added correctly
    @Test
    fun addSpentTest() = runTest {
        setBudget()

        val spend = Transaction(type = TransactionType.SPENT, value = 10.toBigDecimal(), date = currentDateUseCase.value)
        spendsRepository.addSpent(spend)

        assert(spendsRepository.getAllSpends().first().contains(spend))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 10.toBigDecimal().setScale(2))
    }

    // Crossing the daily budget flags overspend; removing the crossing spend drops the
    // flag so a later crossing of the same day can notify again (e.g. after undo/edit).
    @Test
    fun overspendFlagResetsWhenRemovalDropsBelowDailyBudget() = runTest {
        setBudget()

        val crossing = Transaction(
            id = "1",
            type = TransactionType.SPENT,
            value = 120.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(crossing)

        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.budgetDataStore.data.first()[overspendNotifiedStoreKey] == true)

        spendsRepository.removeSpent(crossing)

        assertTrue(context.budgetDataStore.data.first()[overspendNotifiedStoreKey] == false)
    }

    // Raising the daily budget above the current spend clears a stale overspend flag,
    // so the first crossing of the new budget notifies again.
    @Test
    fun overspendFlagClearsWhenDailyBudgetRaisedAboveSpend() = runTest {
        setBudget()

        val crossing = Transaction(
            id = "1",
            type = TransactionType.SPENT,
            value = 120.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(crossing)

        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.budgetDataStore.data.first()[overspendNotifiedStoreKey] == true)

        spendsRepository.updateDailyBudget(150.toBigDecimal())

        assertTrue(context.budgetDataStore.data.first()[overspendNotifiedStoreKey] == false)
    }

    // Check spent in previous day added correctly
    // Init budget 1000 for 10 days
    // [Day 1] dailyBudget = 1000 / 10 = 100 > No spent > not spent = 100
    // [Day 2] dailyBudget = 1000 / 9 = 111.11 > Spend 10 (to yesterday) > 111.11 - (10 / 9) = 110
    @Test
    fun addSpentInPreviousDayTest() = runTest {
        setBudget()

        val spend = Transaction(type = TransactionType.SPENT, value = 10.toBigDecimal(), date = currentDateUseCase.value)

        Log.d("SpendsRepositoryTest", "whatBudgetForDay: ${spendsRepository.whatBudgetForDay()}")

        rewindTime(1)

        Log.d("SpendsRepositoryTest", "whatBudgetForDay: ${spendsRepository.whatBudgetForDay()}")

        distributeBudget()

        spendsRepository.addSpent(spend)

        Log.d("SpendsRepositoryTest", "whatBudgetForDay: ${spendsRepository.whatBudgetForDay()}")
        Log.d("SpendsRepositoryTest", "spentFromDailyBudget: ${spendsRepository.getSpentFromDailyBudget().first()}")
        Log.d("SpendsRepositoryTest", "dailyBudget: ${spendsRepository.nextDayBudget()}")
        Log.d("SpendsRepositoryTest", "spent: ${spendsRepository.getSpent().first()}")

        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 110.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpent().first() == 10.toBigDecimal().setScale(2))
    }

    @Test
    fun addSpentQueuesTheTransactionForPush() = runTest {
        setBudget()

        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        pendingMutationDao.deleteAll()

        spendsRepository.addSpent(spend)

        val queued = pendingMutationDao.forTable(SyncTables.TRANSACTIONS).single()
        assertEquals("spend-1", queued.recordId)
        assertEquals(false, queued.isDelete)
        assertEquals(FakeSyncDirtyMarker.QUEUED_AT, queued.queuedAt)
    }

    @Test
    fun setDailyBudgetQueuesTheNewMarker() = runTest {
        setBudget()
        pendingMutationDao.deleteAll()

        spendsRepository.setDailyBudget(50.toBigDecimal())

        val queued = pendingMutationDao.forTable(SyncTables.TRANSACTIONS).single()
        assertEquals(false, queued.isDelete)
    }

    @Test
    fun changeBudgetQueuesTheUpdatedIncomeMarker() = runTest {
        setBudget()
        pendingMutationDao.deleteAll()

        spendsRepository.changeBudget(
            2000.toBigDecimal(),
            currentDateUseCase.value.toLocalDate().plusDays(9).toDate(),
        )

        val queued = pendingMutationDao.forTable(SyncTables.TRANSACTIONS)
        val queuedIds = queued.map { it.recordId }.toSet()
        val stored = spendsRepository.getAllTransactions().first()
            .filter { it.type == TransactionType.INCOME }
            .map { it.id }
            .toSet()
        assertTrue(stored.isNotEmpty())
        assertTrue(
            "missing=" + (stored - queuedIds),
            stored.all { it in queuedIds },
        )
        assertTrue(queued.none { it.isDelete })
    }

    @Test
    fun setBudgetQueuesTheNewIncomeMarkerAndArchivesTheOldPeriod() = runTest {
        setBudget()
        spendsRepository.addSpent(
            Transaction(
                id = "spend-1",
                type = TransactionType.SPENT,
                value = 10.toBigDecimal(),
                date = currentDateUseCase.value,
            )
        )
        pendingMutationDao.deleteAll()

        rewindTime(1)
        setBudget(budget = 2000, days = 9)

        assertTrue(pendingMutationDao.forTable(SyncTables.BUDGET_PERIODS).isNotEmpty())
        assertTrue(pendingMutationDao.forTable(SyncTables.ARCHIVED_TRANSACTIONS).isNotEmpty())
        assertTrue(pendingMutationDao.forTable(SyncTables.TRANSACTIONS).isNotEmpty())
        assertTrue(pendingMutationDao.tombstones().isEmpty())
    }

    @Test
    fun csvImportQueuesInPeriodSpendsAndArchivedOutOfPeriodRows() = runTest {
        setBudget()
        pendingMutationDao.deleteAll()

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

        assertTrue(pendingMutationDao.idsFor(SyncTables.TRANSACTIONS).contains("in-1"))
        assertTrue(pendingMutationDao.forTable(SyncTables.ARCHIVED_TRANSACTIONS).isNotEmpty())
        assertTrue(pendingMutationDao.idsFor(SyncTables.TRANSACTIONS).contains("in-1"))
    }

    @Test
    fun removingANeverSyncedTransactionClearsItsQueueEntry() = runTest {
        setBudget()
        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        pendingMutationDao.deleteAll()

        spendsRepository.addSpent(spend)
        assertEquals(1, pendingMutationDao.count())

        spendsRepository.removeSpent(spend)

        assertEquals(0, pendingMutationDao.count())
    }

    @Test
    fun removingASyncedTransactionQueuesATombstone() = runTest {
        setBudget()
        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
            familyId = "family-1",
            syncSeq = 7L,
        )
        pendingMutationDao.deleteAll()

        spendsRepository.addSpent(spend)

        spendsRepository.removeSpent(spend)

        val queued = pendingMutationDao.forTable(SyncTables.TRANSACTIONS).single()
        assertEquals("spend-1", queued.recordId)
        assertEquals(true, queued.isDelete)
    }

    @Test
    fun removingASyncedTransactionAndReAddingItClearsTheTombstone() = runTest {
        setBudget()
        val spend = Transaction(
            id = "spend-1",
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
            familyId = "family-1",
            syncSeq = 7L,
        )
        pendingMutationDao.deleteAll()

        spendsRepository.addSpent(spend)
        spendsRepository.removeSpent(spend)
        assertEquals(true, pendingMutationDao.forTable(SyncTables.TRANSACTIONS).single().isDelete)

        spendsRepository.addSpent(spend)

        val queued = pendingMutationDao.forTable(SyncTables.TRANSACTIONS).single()
        assertEquals(false, queued.isDelete)
    }



    // Check today spent removed correctly
    @Test
    fun removeSpendTest() = runTest {
        setBudget()

        val spend_1 = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        val spend_2 = Transaction(
            type = TransactionType.SPENT,
            value = 20.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend_1)
        spendsRepository.addSpent(spend_2)
        spendsRepository.removeSpent(spend_1)
        val spends = spendsRepository.getAllSpends().first()

        assertEquals(1, spends.size)
        assertEquals(spend_2.id, spends.single().id)
        assert(spendsRepository.getSpentFromDailyBudget().first() == 20.toBigDecimal().setScale(2))
    }

    // Add spent and remove in another day
    @Test
    fun removeSpendInAnotherDayTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        rewindTime(1)
        distributeBudget()

        spendsRepository.removeSpent(spend)
        val spends = spendsRepository.getAllSpends().first()

        Log.d("SpendsRepositoryTest", "spentFromDailyBudget: ${spendsRepository.getSpentFromDailyBudget().first()}")
        Log.d("SpendsRepositoryTest", "dailyBudget: ${spendsRepository.nextDayBudget()}")
        Log.d("SpendsRepositoryTest", "spent: ${spendsRepository.getSpent().first()}")

        assert(spends.isEmpty())
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 111.11.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpent().first() == 0.toBigDecimal().setScale(2))
    }

    // Cancel remove spent
    @Test
    fun removeAndReturnSpentTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)
        spendsRepository.removeSpent(spend)
        spendsRepository.addSpent(spend)
        val spends = spendsRepository.getAllSpends().first()

        assert(spends.contains(spend))
        assert(spends.size == 1)
        assert(spendsRepository.getSpentFromDailyBudget().first() == 10.toBigDecimal().setScale(2))
    }

    // Cancel remove spent in another day
    // Init budget 1000 for 10 days
    // [Day 1] Spend 10 > dailyBudget = 1000 / 10 = 100 > not spent = 90
    // [Day 2] No spent > dailyBudget = (1000 - 10) / 9 = 110 > Remove Spend 10 > dailyBudget = 1000 / 9 = 111.11 > not spent = 101.11
    @Test
    fun removeAndReturnSpentInAnotherDayTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        rewindTime(1)
        distributeBudget()

        spendsRepository.removeSpent(spend)
        spendsRepository.addSpent(spend)
        val spends = spendsRepository.getAllSpends().first()

        assert(spends.contains(spend))
        assert(spends.size == 1)
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 110.toBigDecimal().setScale(2))
    }

    // Change day of spent
    @Test
    fun changeDayOfSpentTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        rewindTime(2)

        distributeBudget()

        spendsRepository.removeSpent(spend)

        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))

        spendsRepository.addSpent(spend.copy(date = currentDateUseCase.value))

        assert(spendsRepository.getSpentFromDailyBudget().first() == 10.toBigDecimal().setScale(2))
    }

    // Check overdraft
    // Init budget 1000 for 10 days
    // [Day 1] Spend 120 | dailyBudget = 1000 / 10 = 100 | not spent = -20
    // [Day 2] Skip | dailyBudget = (1000 - 120) / 9 = 97.78 | not spent = 80
    // [Day 3] Skip | dailyBudget = (1000 - 120) / 8 = 98.75 | not spent = 180
    @Test
    fun overdraft() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 120.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)


        assert(spendsRepository.getSpentFromDailyBudget().first() == 120.toBigDecimal().setScale(2))

        Log.d("SpendsRepositoryTest", "whatBudgetForDay: ${spendsRepository.whatBudgetForDay(excludeCurrentDay = true, applyTodaySpends = true)}")
        // (1000 - 120) / 9 = 97.78
        assert(spendsRepository.whatBudgetForDay(excludeCurrentDay = true, applyTodaySpends = true) == 97.78.toBigDecimal().setScale(2))

        rewindTime(1)
        distributeBudget()

        Log.d("SpendsRepositoryTest", "whatBudgetForDay: ${spendsRepository.whatBudgetForDay()}")
        // (1000 - 120) / 9 = 97.78
        assert(spendsRepository.whatBudgetForDay() == 97.78.toBigDecimal().setScale(2))
    }

    // How much saved
    @Test
    fun saved() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 90.toBigDecimal().setScale(2))

        distributeBudget()
        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 110.toBigDecimal().setScale(2))

        distributeBudget()
        rewindTime(2)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 247.5.toBigDecimal().setScale(2))
    }

    // The carry-forward ("You saved") must not go negative while the balance is positive,
    // even after the ASK one-shot marked the daily-budget distribution as handled for
    // today. Marking it handled sets lastChangeDailyBudgetDate = today, which makes
    // skippedDays == 0 in the RecalcBudget sheet; the old `howMuchNotSpent() -
    // nextDayBudget()` formula then produced a spurious negative (90 - 112.50 = -22.50)
    // despite a positive balance.
    @Test
    fun carryForwardStaysPositiveAfterDistributionHandledToday() = runTest {
        setBudget() // 1000 for 10 days, daily = 100

        // [Day 1] Spend 10 of 100 -> saved 90
        spendsRepository.addSpent(
            Transaction(
                type = TransactionType.SPENT,
                value = 10.toBigDecimal(),
                date = currentDateUseCase.value,
            )
        )

        rewindTime(1) // [Day 2]

        // Simulate the ASK redistribution one-shot that runs before the sheet computes
        spendsRepository.markDailyBudgetDistributionHandled()

        // Balance is still positive
        assert(spendsRepository.howMuchBudgetRest() > BigDecimal.ZERO)

        // The saved carry-forward stays positive
        assert(spendsRepository.howMuchSaved() == 90.toBigDecimal().setScale(2))
    }

    // Same guard as carryForwardStaysPositiveAfterDistributionHandledToday but with the
    // real emulator-like numbers: a large budget with a small leftover keeps a positive
    // carry-forward after the distribution was marked handled for today.
    @Test
    fun carryForwardStaysPositiveWithLargeBudget() = runTest {
        spendsRepository.setBudget(
            5000.toBigDecimal(),
            currentDateUseCase.value.toLocalDate().plusDays(31).toDate()
        )

        spendsRepository.addSpent(
            Transaction(
                type = TransactionType.SPENT,
                value = 150.50.toBigDecimal(),
                date = currentDateUseCase.value,
            )
        )

        rewindTime(1)

        spendsRepository.markDailyBudgetDistributionHandled()

        assert(spendsRepository.howMuchBudgetRest() > BigDecimal.ZERO)
        assert(spendsRepository.howMuchSaved() == 5.75.toBigDecimal().setScale(2))
    }

    // Add to today every day
    // Init budget 330 for 10 days
    // [Day 1] dailyBudget = 330 / 10 = 33 > No spent > not spent = 33
    // [Day 2] dailyBudget = (330 - 33) / 9 + 33 = 66 > No spent > not spent = 66
    // [Day 3] dailyBudget = (330 - 66) / 8 + 66 = 99 > No spent > not spent = 99
    @Test
    fun savedWithAdd() = runTest {
        setBudget(330)

        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))

        distributeBudgetAddToday()
        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 66.toBigDecimal().setScale(2))

        distributeBudgetAddToday()
        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 99.toBigDecimal().setScale(2))
    }

    // Overdraft, add spent on next day and skip day
    // Init budget 1000 for 10 days
    // [Day 1] dailyBudget = 1000 / 10 = 100 > Spend 140 > not spent = -40
    // [Day 2] dailyBudget = (1000 - 140) / 9 = 95.56 > Spend 10 > not spent = 85.56
    // [Day 3] dailyBudget = (1000 - 150) / 8 = 106.25 > No spent > not spent = 106.25
    // [Day 4] dailyBudget = (1000 - 150) / 7 = 121.43 > No Spent > not spent = 121.43
    // [Day 5] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 242.86
    // [Day 6] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 364.29
    // [Day 7] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 485.72
    // [Day 8] dailyBudget = (1000 - 150) / 3 = 283.33 > Spend 300 > not spent = -16.67
    // [Day 9] dailyBudget = (1000 - 450) / 2 = 275 > Spend 100 > not spent = 175
    // [Day 10] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450
    // [Day 11] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450
    // [Day 12] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450
    @Test
    fun complexTest1() = runTest {
        setBudget()

        // [Day 1] dailyBudget = 1000 / 10 = 100 > Spend 140 > not spent = -40

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 100.toBigDecimal().setScale(2))

        spendsRepository.addSpent(Transaction(type = TransactionType.SPENT, value = 140.toBigDecimal(), date = currentDateUseCase.value))

        assert(spendsRepository.nextDayBudget() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 140.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == (-40).toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 2] dailyBudget = (1000 - 140) / 9 = 95.56 > Spend 10 > not spent = 85.56

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 95.56.toBigDecimal().setScale(2))

        spendsRepository.addSpent(Transaction(type = TransactionType.SPENT, value = 10.toBigDecimal(), date = currentDateUseCase.value))

        assert(spendsRepository.nextDayBudget() == 95.56.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 10.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 85.56.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 3] dailyBudget = (1000 - 150) / 8 = 106.25 > No spent > not spent = 106.25

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 106.25.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 106.25.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 106.25.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 4] dailyBudget = (1000 - 150) / 7 = 121.43 > No Spent > not spent = 121.43

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 121.43.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 5] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 242.86

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 141.67.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 242.86.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 6] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 364.29

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 170.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 364.29.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 7] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 485.72

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 212.5.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 485.72.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 8] dailyBudget = (1000 - 150) / 3 = 283.33 > Spend 300 > not spent = -16.67

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 283.33.toBigDecimal().setScale(2))

        spendsRepository.addSpent(Transaction(type = TransactionType.SPENT, value = 300.toBigDecimal(), date = currentDateUseCase.value))

        assert(spendsRepository.nextDayBudget() == 283.34.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 300.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == (-16.67).toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 9] dailyBudget = (1000 - 450) / 2 = 275 > Spend 100 > not spent = 175

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 275.toBigDecimal().setScale(2))

        spendsRepository.addSpent(Transaction(type = TransactionType.SPENT, value = 100.toBigDecimal(), date = currentDateUseCase.value))

        assert(spendsRepository.nextDayBudget() == 275.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 175.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 10] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 275.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 450.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 11] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 450.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 12] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 450.toBigDecimal().setScale(2))
    }

    // Add to today every day
    // Init budget 330 for 10 days
    // [Day 1] dailyBudget = 330 / 10 = 33 > No spent > not spent = 33
    // [Day 2] dailyBudget = (330 - 33) / 9 + 33 = 66 > No spent > not spent = 66
    // [Day 3] dailyBudget = (330 - 66) / 8 + 66 = 99 > No spent > not spent = 99
    // [Day 4] dailyBudget = (330 - 99) / 7 + 99 = 132 > No spent > not spent = 132
    // [Day 5] dailyBudget = (330 - 132) / 6 + 132 = 165 > Skip > not spent = 165
    // [Day 6] dailyBudget = (330 - 165) / 5 + 165 = 198 > Skip > not spent = 198
    // [Day 7] dailyBudget = (330 - 198) / 4 + 198 = 231 > Skip > not spent = 231
    // [Day 8] dailyBudget = (330 - 231) / 3 + 231 = 264 > Spend 300 > not spent = 264
    // [Day 9] dailyBudget = (330 - 264) / 2 + 264 = 297 > Spend 100 > not spent = 297
    // [Day 10] dailyBudget = (330 - 297) / 1 + 297 = 330 > No Spent > not spent = 330
    @Test
    fun complexTest2() = runTest {
        setBudget(330)

        // [Day 1] dailyBudget = 330 / 10 = 33 > No spent > not spent = 33

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 33.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 2] dailyBudget = (330 - 33) / 9 + 33 = 66 > No spent > not spent = 66

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 66.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 3] dailyBudget = (330 - 66) / 8 + 66 = 99 > No spent > not spent = 99

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 99.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 4] dailyBudget = (330 - 99) / 7 + 99 = 132 > No spent > not spent = 132

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 132.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 5] dailyBudget = (330 - 132) / 6 + 132 = 165 > Skip > not spent = 165

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 165.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 6] dailyBudget = (330 - 165) / 5 + 165 = 198 > Skip > not spent = 198

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 198.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 7] dailyBudget = (330 - 198) / 4 + 198 = 231 > Skip > not spent = 231

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 231.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 8] dailyBudget = (330 - 231) / 3 + 231 = 264 > Spend 300 > not spent = 264

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 264.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 9] dailyBudget = (330 - 264) / 2 + 264 = 297 > Spend 100 > not spent = 297

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 297.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 10] dailyBudget = (330 - 297) / 1 + 297 = 330 > No Spent > not spent = 330

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 330.toBigDecimal().setScale(2))
    }
}
