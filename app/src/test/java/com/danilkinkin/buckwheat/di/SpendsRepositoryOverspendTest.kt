package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.overspendNotifiedStoreKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendsRepositoryOverspendTest : SpendsRepositoryTestBase() {

    // Crossing the daily budget flags overspend; removing the crossing spend drops the
    // flag so a later crossing of the same day can notify again (e.g. after undo/edit).
    @Test
    fun overspendFlagResetsWhenRemovalDropsBelowDailyBudget() = runTest {
        setBudget()

        val crossing = Transaction(
            type = TransactionType.SPENT,
            value = 120.toBigDecimal(),
            date = currentDateUseCase.value,
        ).also { it.uid = 1 }
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
            type = TransactionType.SPENT,
            value = 120.toBigDecimal(),
            date = currentDateUseCase.value,
        ).also { it.uid = 1 }
        spendsRepository.addSpent(crossing)

        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.budgetDataStore.data.first()[overspendNotifiedStoreKey] == true)

        spendsRepository.updateDailyBudget(150.toBigDecimal())

        assertTrue(context.budgetDataStore.data.first()[overspendNotifiedStoreKey] == false)
    }
}