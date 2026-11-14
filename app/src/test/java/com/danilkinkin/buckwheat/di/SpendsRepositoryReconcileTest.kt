package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class SpendsRepositoryReconcileTest : SpendsRepositoryTestBase() {

    // Reconcile rebuilds today's spend counter from the DB after external drift
    @Test
    fun reconcileRebuildsSpentFromDailyBudgetFromDb() = runTest {
        setBudget()

        val today = Transaction(TransactionType.SPENT, 13.toBigDecimal(), currentDateUseCase.value)
        spendsRepository.addSpent(today)

        // Simulate drift: counter no longer matches the stored rows
        driftSpentFromDailyBudget(99.toBigDecimal())

        spendsRepository.reconcileCounters()

        assertEquals(
            0,
            spendsRepository.getSpentFromDailyBudget().first().compareTo(13.toBigDecimal()),
        )
    }

    // Reconcile rebuilds the previous-day spend total from the DB
    @Test
    fun reconcileRebuildsSpentFromDb() = runTest {
        setBudget()

        val yesterday = Transaction(TransactionType.SPENT, 7.toBigDecimal(), currentDateUseCase.value)
        spendsRepository.addSpent(yesterday)

        rewindTime(1)
        distributeBudget()

        spendsRepository.reconcileCounters()

        assertEquals(0, spendsRepository.getSpent().first().compareTo(7.toBigDecimal()))
        assertEquals(
            0,
            spendsRepository.getSpentFromDailyBudget().first().compareTo(BigDecimal.ZERO),
        )
    }

    // Reconcile is idempotent: running it twice changes nothing
    @Test
    fun reconcileIsIdempotent() = runTest {
        setBudget()

        val today = Transaction(TransactionType.SPENT, 5.toBigDecimal(), currentDateUseCase.value)
        spendsRepository.addSpent(today)

        spendsRepository.reconcileCounters()
        val firstPass = spendsRepository.getSpentFromDailyBudget().first()
        spendsRepository.reconcileCounters()

        assertEquals(
            0,
            spendsRepository.getSpentFromDailyBudget().first().compareTo(firstPass),
        )
        assertEquals(0, firstPass.compareTo(5.toBigDecimal()))
    }

    // Reconcile ignores rows outside the current period
    @Test
    fun reconcileIgnoresOutOfPeriodRows() = runTest {
        setBudget()

        val future = Transaction(
            TransactionType.SPENT,
            40.toBigDecimal(),
            currentDateUseCase.value.toLocalDate().plusDays(60).toDate(),
        )
        spendsRepository.addSpent(future)

        spendsRepository.reconcileCounters()

        assertEquals(
            0,
            spendsRepository.getSpentFromDailyBudget().first().compareTo(BigDecimal.ZERO),
        )
        assertEquals(0, spendsRepository.getSpent().first().compareTo(BigDecimal.ZERO))
    }

    // Reconcile does nothing when no budget is set
    @Test
    fun reconcileNoBudgetIsNoOp() = runTest {
        spendsRepository.reconcileCounters()

        assertEquals(
            0,
            spendsRepository.getSpentFromDailyBudget().first().compareTo(BigDecimal.ZERO),
        )
    }

    private suspend fun driftSpentFromDailyBudget(value: BigDecimal) {
        ApplicationProvider.getApplicationContext<Context>()
            .budgetDataStore.edit { it[spentFromDailyBudgetStoreKey] = value.toString() }
    }
}
