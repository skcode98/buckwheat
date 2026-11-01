package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.categories.CategoryAssigner
import com.danilkinkin.buckwheat.data.categories.CategoryAssignmentScheduler
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import com.danilkinkin.buckwheat.util.toLocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
abstract class SpendsRepositoryTestBase {

    lateinit var spendsRepository: SpendsRepository

    val currentDateUseCase: FakeGetCurrentDateUseCase = FakeGetCurrentDateUseCase()
    val budgetPeriodDao: FakeBudgetPeriodDao = FakeBudgetPeriodDao()

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
            CategoryAssignmentScheduler(CategoryAssigner(context, transactionDao, budgetPeriodDao)),
            CategoryCapTracker(context, SettingsRepository(context), transactionDao),
            BudgetCalculator(context, currentDateUseCase),
        )
    }

    // Set budget 1000 for 10 days
    // Start daily budget 100
    protected suspend fun setBudget(budget: Long = 1000, days: Long = 9) {
        spendsRepository.setBudget(
            budget.toBigDecimal(),
            currentDateUseCase.value.toLocalDate().plusDays(days).toDate()
        )
    }

    // Update daily budget. Should be called after change day
    protected suspend fun distributeBudget() {
        spendsRepository.setDailyBudget(spendsRepository.whatBudgetForDay(
            applyTodaySpends = true,
        ))
    }

    protected suspend fun distributeBudgetAddToday() {
        val notSpent = spendsRepository.howMuchNotSpent(
            excludeSkippedPart = true,
        )
        val dailyBudget = spendsRepository.nextDayBudget()
        val whatBudgetForDay = spendsRepository.whatBudgetForDay(
            excludeCurrentDay = false,
            applyTodaySpends = true,
            notCommittedSpent = dailyBudget,
        )

        spendsRepository.setDailyBudget(notSpent)
    }

    protected fun rewindTime(days: Long, hours: Long = 0) {
        currentDateUseCase.value = currentDateUseCase.value
            .toLocalDateTime()
            .plusDays(days)
            .plusHours(hours)
            .toDate()
    }
}