package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.categories.CategoryAssigner
import com.danilkinkin.buckwheat.data.categories.CategoryAssignmentScheduler
import com.danilkinkin.buckwheat.settings.RecurringPaymentsViewModel
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.runBlocking

// Shared fixtures for Robolectric Compose UI tests: real ViewModels wired to in-memory
// fake DAOs so interactions (confirm/skip, mode selection) exercise production logic.
data class TestUiHarness(
    val spendsViewModel: SpendsViewModel,
    val recurringPaymentsViewModel: RecurringPaymentsViewModel,
    val transactionDao: FakeTransactionDao,
    val settingsRepository: SettingsRepository,
    val spendsRepository: SpendsRepository,
)

fun buildTestUiHarness(): TestUiHarness {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // preferencesDataStore() caches one DataStore per process; clear it so a previously run UI
    // test cannot leave settings (e.g. the recurring auto-apply mode) behind.
    runBlocking {
        context.budgetDataStore.edit { it.clear() }
        context.settingsDataStore.edit { it.clear() }
    }
    val transactionDao = FakeTransactionDao()
    val budgetPeriodDao = FakeBudgetPeriodDao()
    val settingsRepository = SettingsRepository(context)
    val currentDateUseCase = FakeGetCurrentDateUseCase()
    val spendsRepository = SpendsRepository(
        context = context,
        transactionDao = transactionDao,
        savedTagDao = FakeSavedTagDao(),
        savedCategoryDao = FakeSavedCategoryDao(),
        budgetPeriodDao = budgetPeriodDao,
        getCurrentDateUseCase = currentDateUseCase,
        categoryAssignmentScheduler = CategoryAssignmentScheduler(
            CategoryAssigner(context, transactionDao, budgetPeriodDao)
        ),
        categoryCapTracker = CategoryCapTracker(context, settingsRepository, transactionDao),
        budgetCalculator = BudgetCalculator(context, currentDateUseCase),
    )
    return TestUiHarness(
        spendsViewModel = SpendsViewModel(
            context = context,
            savedStateHandle = SavedStateHandle(),
            spendsRepository = spendsRepository,
            recurringDao = FakeRecurringDao(),
            settingsRepository = settingsRepository,
        ),
        recurringPaymentsViewModel = RecurringPaymentsViewModel(
            recurringDao = FakeRecurringDao(),
            settingsRepository = settingsRepository,
        ),
        transactionDao = transactionDao,
        settingsRepository = settingsRepository,
        spendsRepository = spendsRepository,
    )
}
