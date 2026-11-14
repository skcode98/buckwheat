package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.categories.CategoryAssigner
import com.danilkinkin.buckwheat.data.categories.CategoryAssignmentScheduler
import com.danilkinkin.buckwheat.di.BudgetCalculator
import com.danilkinkin.buckwheat.di.CategoryCapTracker
import com.danilkinkin.buckwheat.di.FakeBudgetPeriodDao
import com.danilkinkin.buckwheat.di.FakeGetCurrentDateUseCase
import com.danilkinkin.buckwheat.di.FakeSavedCategoryDao
import com.danilkinkin.buckwheat.di.FakeSavedTagDao
import com.danilkinkin.buckwheat.di.FakeSavingsGoalDao
import com.danilkinkin.buckwheat.di.FakeTransactionDao
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.util.toDate
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

// Auto-allocation moves the unspent part of today's daily allowance into the chosen goal.
// These tests drive the GoalsViewModel path end to end against the real SpendsRepository so
// the state written by both features (counters + goal row + spend row) stays consistent.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoalsViewModelAutoAllocationTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var goalDao: FakeSavingsGoalDao
    private lateinit var spendsRepository: SpendsRepository
    private lateinit var currentDateUseCase: FakeGetCurrentDateUseCase
    private lateinit var viewModel: GoalsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        transactionDao = FakeTransactionDao()
        goalDao = FakeSavingsGoalDao()
        currentDateUseCase = FakeGetCurrentDateUseCase()
        val budgetPeriodDao = FakeBudgetPeriodDao()
        spendsRepository = SpendsRepository(
            context = context,
            transactionDao,
            FakeSavedTagDao(),
            FakeSavedCategoryDao(),
            budgetPeriodDao,
            currentDateUseCase,
            CategoryAssignmentScheduler(CategoryAssigner(context, transactionDao, budgetPeriodDao)),
            CategoryCapTracker(context, SettingsRepository(context), transactionDao),
            BudgetCalculator(context, currentDateUseCase),
        )
        viewModel = GoalsViewModel(
            savingsGoalDao = goalDao,
            spendsRepository = spendsRepository,
            settingsRepository = SettingsRepository(context),
            appContext = context,
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    // Set budget 1000 for 10 days → start daily budget 100, nothing spent today yet.
    private suspend fun seedGoalAndBudget(): Long {
        spendsRepository.setBudget(
            BigDecimal("1000"),
            java.time.LocalDate.now().plusDays(9).toDate(),
        )
        return goalDao.insert(SavingsGoal(name = "Trip", targetAmount = BigDecimal("500")))
    }

    @Test
    fun autoAllocateMovesUnspentDailyLeftoverIntoGoal() = runTest {
        val goalId = seedGoalAndBudget()

        viewModel.autoAllocateLeftoverToGoal(goalId).join()

        val goal = checkNotNull(goalDao.getById(goalId))
        assertEquals(0, goal.currentAmount.compareTo(BigDecimal("100")))
        val spentRows = transactionDao.spends.filter { it.type == TransactionType.SPENT }
        assertEquals(1, spentRows.size)
        assertEquals(0, spentRows.first().value.compareTo(BigDecimal("100")))
    }

    @Test
    fun autoAllocateAddsOnlyTheRemainderAfterTodaySpend() = runTest {
        val goalId = seedGoalAndBudget()
        spendsRepository.addSpent(
            com.danilkinkin.buckwheat.data.entities.Transaction(
                type = TransactionType.SPENT,
                value = BigDecimal("30"),
                date = currentDateUseCase.value,
                comment = "coffee",
            )
        )

        viewModel.autoAllocateLeftoverToGoal(goalId).join()

        val goal = checkNotNull(goalDao.getById(goalId))
        assertEquals(0, goal.currentAmount.compareTo(BigDecimal("70")))
    }

    @Test
    fun autoAllocateIsNoOpWhenNothingIsLeft() = runTest {
        val goalId = seedGoalAndBudget()
        spendsRepository.addSpent(
            com.danilkinkin.buckwheat.data.entities.Transaction(
                type = TransactionType.SPENT,
                value = BigDecimal("100"),
                date = currentDateUseCase.value,
                comment = "full day",
            )
        )

        viewModel.autoAllocateLeftoverToGoal(goalId).join()

        val goal = checkNotNull(goalDao.getById(goalId))
        assertEquals(0, goal.currentAmount.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun autoAllocateDoesNotExceedGoalTarget() = runTest {
        spendsRepository.setBudget(
            BigDecimal("1000"),
            java.time.LocalDate.now().plusDays(9).toDate(),
        )
        val goalId = goalDao.insert(
            SavingsGoal(name = "Trip", targetAmount = BigDecimal("50"))
        )

        viewModel.autoAllocateLeftoverToGoal(goalId).join()

        val goal = checkNotNull(goalDao.getById(goalId))
        assertEquals(0, goal.currentAmount.compareTo(BigDecimal("50")))
        assertTrue(goal.completed)
        val spentRows = transactionDao.spends.filter { it.type == TransactionType.SPENT }
        assertEquals(1, spentRows.size)
        assertEquals(0, spentRows.single().value.compareTo(BigDecimal("50")))
    }
}
