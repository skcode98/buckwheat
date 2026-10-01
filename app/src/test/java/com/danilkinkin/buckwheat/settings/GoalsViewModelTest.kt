package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.di.FakeSavingsGoalDao
import com.danilkinkin.buckwheat.sync.SyncTables
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
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

// Every savings_goals write has to enqueue a pending mutation, otherwise the change never reaches
// the server and an incoming pull silently reverts it. An allocation touches two tables — the
// goal and the spend it records — so both have to be queued.
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

    // Budget 1000 for 30 days so howMuchBudgetRest() is positive and allocations go through.
    private suspend fun setBudget() {
        fixture.spendsRepository.setBudget(
            BigDecimal("1000"),
            fixture.currentDateUseCase.value.toLocalDate().plusDays(30).toDate(),
        )
    }

    @Test
    fun `adding a goal marks the inserted row`() = runTest(dispatcher) {
        viewModel().addGoal("  Trip  ", BigDecimal("500"), Date(0))

        val stored = savingsGoalDao.getAllNow().single()
        assertEquals("Trip", stored.name)
        assertEquals(listOf(stored.id), marker.upserted(SyncTables.SAVINGS_GOALS))
    }

    @Test
    fun `adding an invalid goal marks nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.addGoal("  ", BigDecimal("500"))
        viewModel.addGoal("Trip", BigDecimal.ZERO)

        assertTrue(savingsGoalDao.getAllNow().isEmpty())
        assertTrue(marker.upserted(SyncTables.SAVINGS_GOALS).isEmpty())
    }

    @Test
    fun `updating a goal marks the row and keeps its family metadata`() = runTest(dispatcher) {
        seed("goal-1", familyId = "family-1", syncSeq = 5L)

        viewModel().updateGoal("goal-1", "  Road trip  ", BigDecimal("2000"), Date(0))

        assertEquals(listOf("goal-1"), marker.upserted(SyncTables.SAVINGS_GOALS))
        val stored = savingsGoalDao.getAllNow().single()
        assertEquals("Road trip", stored.name)
        assertEquals(BigDecimal("2000"), stored.targetAmount)
        assertEquals("family-1", stored.familyId)
        assertEquals(5L, stored.syncSeq)
    }

    @Test
    fun `updating an unknown goal marks nothing`() = runTest(dispatcher) {
        viewModel().updateGoal("missing", "Trip", BigDecimal("100"), null)

        assertTrue(marker.upserted(SyncTables.SAVINGS_GOALS).isEmpty())
    }

    @Test
    fun `allocating marks the goal and the resulting spend`() = runTest(dispatcher) {
        setBudget()
        seed("goal-1", targetAmount = "1000", currentAmount = "10")
        marker.upserts.clear()
        val viewModel = viewModel()

        viewModel.allocateToGoal("goal-1", BigDecimal("20"))

        assertEquals(listOf("goal-1"), marker.upserted(SyncTables.SAVINGS_GOALS))
        assertEquals(1, marker.upserted(SyncTables.TRANSACTIONS).size)
        assertEquals(BigDecimal("30"), savingsGoalDao.getAllNow().single().currentAmount)
    }

    @Test
    fun `allocating more than the budget rest marks nothing`() = runTest(dispatcher) {
        setBudget()
        seed("goal-1", targetAmount = "100000")
        marker.upserts.clear()

        viewModel().allocateToGoal("goal-1", BigDecimal("5000"))

        assertTrue(marker.upserted(SyncTables.SAVINGS_GOALS).isEmpty())
        assertTrue(marker.upserted(SyncTables.TRANSACTIONS).isEmpty())
        assertEquals(BigDecimal.ZERO, savingsGoalDao.getAllNow().single().currentAmount)
    }

    @Test
    fun `allocating to an unknown goal marks nothing`() = runTest(dispatcher) {
        setBudget()
        marker.upserts.clear()

        viewModel().allocateToGoal("missing", BigDecimal("20"))

        assertTrue(marker.upserted(SyncTables.SAVINGS_GOALS).isEmpty())
        assertTrue(marker.upserted(SyncTables.TRANSACTIONS).isEmpty())
    }

    @Test
    fun `deleting a synced goal queues a tombstone with its family metadata`() = runTest(dispatcher) {
        seed("goal-1", familyId = "family-1", syncSeq = 12L)

        viewModel().deleteGoal("goal-1")

        val tombstone = marker.deleted(SyncTables.SAVINGS_GOALS).single()
        assertEquals("goal-1", tombstone.recordId)
        assertEquals("family-1", tombstone.familyId)
        assertEquals(12L, tombstone.syncSeq)
        assertTrue(savingsGoalDao.getAllNow().isEmpty())
    }

    @Test
    fun `deleting a never-synced goal queues no tombstone`() = runTest(dispatcher) {
        seed("goal-1")

        viewModel().deleteGoal("goal-1")

        assertTrue(marker.deleted(SyncTables.SAVINGS_GOALS).isEmpty())
    }

    @Test
    fun `deleting an unknown goal marks nothing`() = runTest(dispatcher) {
        viewModel().deleteGoal("missing")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(marker.deletes.isEmpty())
    }
}