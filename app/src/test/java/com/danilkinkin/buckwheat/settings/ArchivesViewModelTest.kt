package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.di.FakeBudgetPeriodDao
import com.danilkinkin.buckwheat.sync.SyncTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.math.BigDecimal
import java.util.Date

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
    fun `updating the period dates marks the row and keeps its sync columns`() = runTest(dispatcher) {
        seed("period-1", familyId = "family-1", syncSeq = 2L)

        viewModel().updatePeriodDates("period-1", Date(10_000), Date(20_000))

        assertEquals(listOf("period-1"), marker.upserted(SyncTables.BUDGET_PERIODS))
        val stored = budgetPeriodDao.getById("period-1")!!
        assertEquals(Date(10_000), stored.startDate)
        assertEquals(Date(20_000), stored.finishDate)
        assertEquals("family-1", stored.familyId)
        assertEquals(2L, stored.syncSeq)
    }

    @Test
    fun `updating the period budget marks the row and keeps its sync columns`() = runTest(dispatcher) {
        seed("period-1", familyId = "family-1", syncSeq = 3L)

        viewModel().updatePeriodBudget("period-1", BigDecimal("2500"))

        assertEquals(listOf("period-1"), marker.upserted(SyncTables.BUDGET_PERIODS))
        val stored = budgetPeriodDao.getById("period-1")!!
        assertEquals(BigDecimal("2500"), stored.budget)
        assertEquals("family-1", stored.familyId)
        assertEquals(3L, stored.syncSeq)
    }

    @Test
    fun `deleting a synced period queues a tombstone with its family metadata`() = runTest(dispatcher) {
        seed("period-1", familyId = "family-1", syncSeq = 4L)

        viewModel().deletePeriod("period-1")

        val tombstone = marker.deleted(SyncTables.BUDGET_PERIODS).single()
        assertEquals("period-1", tombstone.recordId)
        assertEquals("family-1", tombstone.familyId)
        assertEquals(4L, tombstone.syncSeq)
        assertNull(budgetPeriodDao.getById("period-1"))
    }

    @Test
    fun `deleting a never-synced period queues no tombstone`() = runTest(dispatcher) {
        seed("period-1")

        viewModel().deletePeriod("period-1")

        assertTrue(marker.deleted(SyncTables.BUDGET_PERIODS).isEmpty())
        assertNull(budgetPeriodDao.getById("period-1"))
    }

    @Test
    fun `deleting an unknown period marks nothing`() = runTest(dispatcher) {
        viewModel().deletePeriod("missing")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(marker.deletes.isEmpty())
    }
}