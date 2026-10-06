package com.danilkinkin.buckwheat.settings

import androidx.datastore.preferences.core.edit
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.di.FakeSavingsGoalDao
import com.danilkinkin.buckwheat.di.budgetStoreKey
import com.danilkinkin.buckwheat.sync.SyncTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Date

// savings_goals edits no longer enqueue pending mutations. An allocation touches two tables —
// the goal and the spend it records — and only the spend still gets marked.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoalsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var fixture: SyncMarkingFixture
    private lateinit var savingsGoalDao: FakeSavingsGoalDao
    private lateinit var marker: FakeSyncDirtyMarker

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = SyncMarkingFixture()
        savingsGoalDao = FakeSavingsGoalDao()
        marker = fixture.syncDirtyMarker
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = GoalsViewModel(
        savingsGoalDao = savingsGoalDao,
        spendsRepository = fixture.spendsRepository,
        settingsRepository = fixture.settingsRepository,
        syncDirtyMarker = marker,
        appContext = fixture.context,
    )

    private suspend fun seed(
        id: String,
        // Keep the name free of SpendCategory keywords: addSpent writes "→ <name>" as the
        // transaction comment, so a keyword-bearing name would let the background categorizer
        // persist a category and add a second TRANSACTIONS mark from another thread.
        name: String = "House deposit",
        targetAmount: String = "1000",
        currentAmount: String = "0",
        familyId: String? = null,
        syncSeq: Long = 0L,
    ): SavingsGoal = SavingsGoal(
        id = id,
        name = name,
        targetAmount = BigDecimal(targetAmount),
        currentAmount = BigDecimal(currentAmount),
        familyId = familyId,
        syncSeq = syncSeq,
    ).also { savingsGoalDao.insert(it) }

    // Only the `budget` key is written. SpendsRepository.howMuchBudgetRest() reads
    // budget - spent - spentFromDailyBudget and defaults the two counters to zero, so one key is
    // enough to get a positive rest. Going through SpendsRepository.setBudget instead chains five
    // nested DataStore writes (archive check, setDailyBudget, hideOverspendingWarn, the
    // category-cap reset), and those re-enter the write actor from inside its own dispatch —
    // that is what left these allocation tests sitting until runTest's 60s timeout.
    private suspend fun setBudgetRest(amount: String) {
        fixture.context.budgetDataStore.edit { it[budgetStoreKey] = amount }
    }

    @Test
    fun `adding a goal inserts it without marking`() = runTest(dispatcher) {
        viewModel().addGoal("  Trip  ", BigDecimal("500"), Date(0))

        val stored = savingsGoalDao.getAllNow().single()
        assertEquals("Trip", stored.name)
        assertTrue(marker.upserts.isEmpty())
    }

    @Test
    fun `adding an invalid goal marks nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.addGoal("  ", BigDecimal("500"))
        viewModel.addGoal("Trip", BigDecimal.ZERO)

        assertTrue(savingsGoalDao.getAllNow().isEmpty())
        assertTrue(marker.upserts.isEmpty())
    }

    @Test
    fun `updating a goal keeps its family metadata without marking`() = runTest(dispatcher) {
        seed("goal-1", familyId = "family-1", syncSeq = 5L)

        viewModel().updateGoal("goal-1", "  Road trip  ", BigDecimal("2000"), Date(0))

        assertTrue(marker.upserts.isEmpty())
        val stored = savingsGoalDao.getAllNow().single()
        assertEquals("Road trip", stored.name)
        assertEquals(BigDecimal("2000"), stored.targetAmount)
        assertEquals("family-1", stored.familyId)
        assertEquals(5L, stored.syncSeq)
    }

    @Test
    fun `updating an unknown goal marks nothing`() = runTest(dispatcher) {
        viewModel().updateGoal("missing", "Trip", BigDecimal("100"), null)

        assertTrue(marker.upserts.isEmpty())
    }

    @Test
    fun `allocating marks only the resulting spend`() = runTest(dispatcher) {
        setBudgetRest("1000")
        seed("goal-1", targetAmount = "1000", currentAmount = "10")
        marker.upserts.clear()
        val viewModel = viewModel()

        // join() is the barrier: the allocation hops to real DataStore/IO work that runTest's
        // scheduler cannot advance, so without it the assertions below race the dirty mark.
        viewModel.allocateToGoal("goal-1", BigDecimal("20")).join()

        assertEquals(1, marker.upserted(SyncTables.TRANSACTIONS).size)
        assertTrue(marker.upserts.all { it.table == SyncTables.TRANSACTIONS })
        assertEquals(BigDecimal("30"), savingsGoalDao.getAllNow().single().currentAmount)
    }

    @Test
    fun `allocating more than the budget rest marks nothing`() = runTest(dispatcher) {
        seed("goal-1", targetAmount = "100000")
        marker.upserts.clear()

        // No budget is written, so howMuchBudgetRest() is zero and 5000 is over the rest.
        viewModel().allocateToGoal("goal-1", BigDecimal("5000")).join()

        assertTrue(marker.upserts.isEmpty())
        assertEquals(BigDecimal.ZERO, savingsGoalDao.getAllNow().single().currentAmount)
    }

    @Test
    fun `allocating to an unknown goal marks nothing`() = runTest(dispatcher) {
        marker.upserts.clear()

        // An unknown goal short-circuits before the budget is ever read.
        viewModel().allocateToGoal("missing", BigDecimal("20")).join()

        assertTrue(marker.upserts.isEmpty())
    }

    @Test
    fun `deleting a synced goal drops the row without a tombstone`() = runTest(dispatcher) {
        seed("goal-1", familyId = "family-1", syncSeq = 12L)

        viewModel().deleteGoal("goal-1")

        assertTrue(marker.deletes.isEmpty())
        assertTrue(savingsGoalDao.getAllNow().isEmpty())
    }

    @Test
    fun `deleting a never-synced goal queues no tombstone`() = runTest(dispatcher) {
        seed("goal-1")

        viewModel().deleteGoal("goal-1")

        assertTrue(marker.deletes.isEmpty())
    }

    @Test
    fun `deleting an unknown goal marks nothing`() = runTest(dispatcher) {
        viewModel().deleteGoal("missing")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(marker.deletes.isEmpty())
    }
}