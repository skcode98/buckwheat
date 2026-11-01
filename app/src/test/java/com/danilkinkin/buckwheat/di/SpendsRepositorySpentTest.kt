package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SpendsRepositorySpentTest : SpendsRepositoryTestBase() {

    // Check spent in same day added correctly
    @Test
    fun addSpentTest() = runTest {
        setBudget()

        val spend = Transaction(TransactionType.SPENT, 10.toBigDecimal(), currentDateUseCase.value)
        spendsRepository.addSpent(spend)

        assert(spendsRepository.getAllSpends().first().contains(spend))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 10.toBigDecimal().setScale(2))
    }

    // Check spent in previous day added correctly
    @Test
    fun addSpentInPreviousDayTest() = runTest {
        setBudget()

        val spend = Transaction(TransactionType.SPENT, 10.toBigDecimal(), currentDateUseCase.value)

        rewindTime(1)
        distributeBudget()

        spendsRepository.addSpent(spend)

        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 110.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpent().first() == 10.toBigDecimal().setScale(2))
    }

    // Check today spent removed correctly
    @Test
    fun removeSpendTest() = runTest {
        setBudget()

        val spend_1 = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        val spend_2 = Transaction(
            type = TransactionType.SPENT,
            value = 20.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend_1)
        spendsRepository.addSpent(spend_2)
        spendsRepository.removeSpent(spend_1)
        val spends = spendsRepository.getAllSpends().first()

        assert(spends.isEmpty())
        assert(spendsRepository.getSpentFromDailyBudget().first() == 20.toBigDecimal().setScale(2))
    }

    // Add spent and remove in another day
    @Test
    fun removeSpendInAnotherDayTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        rewindTime(1)
        distributeBudget()

        spendsRepository.removeSpent(spend)
        val spends = spendsRepository.getAllSpends().first()

        assert(spends.isEmpty())
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 111.11.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpent().first() == 0.toBigDecimal().setScale(2))
    }

    // Cancel remove spent
    @Test
    fun removeAndReturnSpentTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)
        spendsRepository.removeSpent(spend)
        spendsRepository.addSpent(spend)
        val spends = spendsRepository.getAllSpends().first()

        assert(spends.contains(spend))
        assert(spends.size == 1)
        assert(spendsRepository.getSpentFromDailyBudget().first() == 10.toBigDecimal().setScale(2))
    }

    // Cancel remove spent in another day
    @Test
    fun removeAndReturnSpentInAnotherDayTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        rewindTime(1)
        distributeBudget()

        spendsRepository.removeSpent(spend)
        spendsRepository.addSpent(spend)
        val spends = spendsRepository.getAllSpends().first()

        assert(spends.contains(spend))
        assert(spends.size == 1)
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 110.toBigDecimal().setScale(2))
    }

    // Change day of spent
    @Test
    fun changeDayOfSpentTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        rewindTime(2)

        distributeBudget()

        spendsRepository.removeSpent(spend)

        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))

        spendsRepository.addSpent(spend.copy(date = currentDateUseCase.value))

        assert(spendsRepository.getSpentFromDailyBudget().first() == 10.toBigDecimal().setScale(2))
    }

    // Edit today's spent value atomically (old value subtracted, new added)
    @Test
    fun editSpentValueTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        val edited = spend.copy(value = 25.toBigDecimal())
        spendsRepository.editSpent(spend, edited)

        val spends = spendsRepository.getAllSpends().first()
        assert(spends.size == 1)
        assert(spends.contains(edited))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 25.toBigDecimal().setScale(2))
    }

    // Edit today's spent comment keeps counter unchanged
    @Test
    fun editSpentCommentOnlyTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        val edited = spend.copy(comment = "coffee")
        spendsRepository.editSpent(spend, edited)

        val spends = spendsRepository.getAllSpends().first()
        assert(spends.size == 1)
        assert(spendsRepository.getSpentFromDailyBudget().first() == 10.toBigDecimal().setScale(2))
    }

    // Edit today's spent does not drift the counter (regression: separate remove+add race)
    @Test
    fun editSpentDoesNotDoubleCountTest() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        spendsRepository.editSpent(spend, spend.copy(value = 15.toBigDecimal()))
        val afterFirst = spendsRepository.getSpentFromDailyBudget().first()
        assert(afterFirst == 15.toBigDecimal().setScale(2))

        val second = spendsRepository.getAllSpends().first().first()
        spendsRepository.editSpent(second, second.copy(value = 5.toBigDecimal()))

        assert(spendsRepository.getSpentFromDailyBudget().first() == 5.toBigDecimal().setScale(2))
        assert(spendsRepository.getAllSpends().first().size == 1)
    }
}