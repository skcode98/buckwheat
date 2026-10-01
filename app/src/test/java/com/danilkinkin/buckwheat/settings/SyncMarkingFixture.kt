package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.categories.CategoryAssigner
import com.danilkinkin.buckwheat.data.categories.CategoryAssignmentScheduler
import com.danilkinkin.buckwheat.di.BudgetCalculator
import com.danilkinkin.buckwheat.di.CategoryCapTracker
import com.danilkinkin.buckwheat.di.FakeBudgetPeriodDao
import com.danilkinkin.buckwheat.di.FakeGetCurrentDateUseCase
import com.danilkinkin.buckwheat.di.FakeSavedCategoryDao
import com.danilkinkin.buckwheat.di.FakeSavedTagDao
import com.danilkinkin.buckwheat.di.FakeTransactionDao
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.SpendsRepository

// A real SpendsRepository over in-memory DAO fakes, so the settings ViewModels under test get the
// production constructor wiring (including SyncDirtyMarker) without a Room database.
class SyncMarkingFixture {
    val context: Context = ApplicationProvider.getApplicationContext()
    val currentDateUseCase = FakeGetCurrentDateUseCase()
    val transactionDao = FakeTransactionDao()
    val budgetPeriodDao = FakeBudgetPeriodDao()
    val settingsRepository = SettingsRepository(context)
    val syncDirtyMarker = FakeSyncDirtyMarker()

    val spendsRepository = SpendsRepository(
        context = context,
        transactionDao = transactionDao,
        savedTagDao = FakeSavedTagDao(),
        savedCategoryDao = FakeSavedCategoryDao(),
        budgetPeriodDao = budgetPeriodDao,
        getCurrentDateUseCase = currentDateUseCase,
        categoryAssignmentScheduler = CategoryAssignmentScheduler(
            CategoryAssigner(context, transactionDao, budgetPeriodDao, syncDirtyMarker)
        ),
        categoryCapTracker = CategoryCapTracker(context, settingsRepository, transactionDao),
        budgetCalculator = BudgetCalculator(context, currentDateUseCase),
        syncDirtyMarker = syncDirtyMarker,
    )
}