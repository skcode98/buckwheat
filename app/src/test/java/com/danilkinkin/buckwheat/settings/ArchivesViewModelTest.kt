package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.FakeBudgetPeriodDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Archives edits the period rows with targeted SQL UPDATEs that leave the sync columns alone, so
// a bump applied after them is what makes the change pushable.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchivesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var budgetPeriodDao: FakeBudgetPeriodDao
    private lateinit var marker: FakeSyncDirtyMarker

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        budgetPeriodDao = FakeBudgetPeriodDao()
        marker = FakeSyncDirtyMarker()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ArchivesViewModel(
        budgetPeriodDao = budgetPeriodDao,
        syncDirtyMarker = marker,
    )

    private suspend fun seed(
        id: String,
        budget: String = "1000",
        startDate: Date = Date(0),
        finishDate: Date = Date(1000),
        familyId: String? = null,
        syncSeq: Long = 0L,
    ): BudgetPeriod = BudgetPeriod(
        id = id,
        budget = BigDecimal(budget),
        startDate = startDate,
        finishDate = finishDate,
        actualFinishDate = null,
        currencyCode = "INR",
        totalSpent = BigDecimal.ZERO,
        familyId = familyId,
        syncSeq = syncSeq,
    ).also { budgetPeriodDao.insert(it) }

    @Test
    fun `updating the period dates keeps its sync columns`() = runTest(dispatcher) {
        seed("period-1", familyId = "family-1", syncSeq = 2L)

        viewModel().updatePeriodDates("period-1", Date(10_000), Date(20_000))

        assertTrue(marker.upserts.isEmpty())
        val stored = budgetPeriodDao.getById("period-1")!!
        assertEquals(Date(10_000), stored.startDate)
        assertEquals(Date(20_000), stored.finishDate)
        assertEquals("family-1", stored.familyId)
        assertEquals(2L, stored.syncSeq)
    }

    @Test
    fun `updating the period budget keeps its sync columns`() = runTest(dispatcher) {
        seed("period-1", familyId = "family-1", syncSeq = 3L)

        viewModel().updatePeriodBudget("period-1", BigDecimal("2500"))

        assertTrue(marker.upserts.isEmpty())
        val stored = budgetPeriodDao.getById("period-1")!!
        assertEquals(BigDecimal("2500"), stored.budget)
        assertEquals("family-1", stored.familyId)
        assertEquals(3L, stored.syncSeq)
    }

    @Test
    fun `deleting a synced period queues no tombstone and drops the row`() = runTest(dispatcher) {
        seed("period-1", familyId = "family-1", syncSeq = 4L)

        viewModel().deletePeriod("period-1")

        assertTrue(marker.deletes.isEmpty())
        assertNull(budgetPeriodDao.getById("period-1"))
    }

    @Test
    fun `deleting a never-synced period queues no tombstone`() = runTest(dispatcher) {
        seed("period-1")

        viewModel().deletePeriod("period-1")

        assertTrue(marker.deletes.isEmpty())
        assertNull(budgetPeriodDao.getById("period-1"))
    }

    @Test
    fun `deleting an unknown period marks nothing`() = runTest(dispatcher) {
        viewModel().deletePeriod("missing")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(marker.deletes.isEmpty())
    }

    @Test
    fun selectedPeriodSpendsEmitsOnlySpentRecords() = runTest(dispatcher) {
        budgetPeriodDao.insertArchivedTransactions(
            listOf(
                ArchivedTransaction(
                    periodId = "period-1",
                    type = TransactionType.INCOME,
                    value = BigDecimal("1000"),
                    date = Date(0),
                    comment = "income",
                ),
                ArchivedTransaction(
                    periodId = "period-1",
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal("50"),
                    date = Date(1),
                    comment = "budget",
                ),
                ArchivedTransaction(
                    periodId = "period-1",
                    type = TransactionType.SPENT,
                    value = BigDecimal("20"),
                    date = Date(2),
                    comment = "lunch",
                ),
                ArchivedTransaction(
                    periodId = "period-2",
                    type = TransactionType.SPENT,
                    value = BigDecimal("5"),
                    date = Date(3),
                    comment = "other period",
                ),
            )
        )

        val selectedViewModel = viewModel()
        selectedViewModel.selectPeriod("period-1")
        val spends = selectedViewModel.selectedPeriodSpends.first { it.isNotEmpty() }

        assertEquals(1, spends.size)
        assertTrue(spends.all { it.type == TransactionType.SPENT })
        assertEquals("lunch", spends.single().comment)
    }
}
