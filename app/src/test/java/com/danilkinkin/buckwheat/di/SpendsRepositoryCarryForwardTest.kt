package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal

class SpendsRepositoryCarryForwardTest : SpendsRepositoryTestBase() {

    // How much saved
    @Test
    fun saved() = runTest {
        setBudget()

        val spend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value,
        )
        spendsRepository.addSpent(spend)

        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 90.toBigDecimal().setScale(2))

        distributeBudget()
        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 110.toBigDecimal().setScale(2))

        distributeBudget()
        rewindTime(2)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 247.5.toBigDecimal().setScale(2))
    }

    // The carry-forward ("You saved") must not go negative while the balance is positive,
    // even after the ASK one-shot marked the daily-budget distribution as handled for
    // today. Marking it handled sets lastChangeDailyBudgetDate = today, which makes
    // skippedDays == 0 in the RecalcBudget sheet; the old `howMuchNotSpent() -
    // nextDayBudget()` formula then produced a spurious negative (90 - 112.50 = -22.50)
    // despite a positive balance.
    @Test
    fun carryForwardStaysPositiveAfterDistributionHandledToday() = runTest {
        setBudget() // 1000 for 10 days, daily = 100

        // [Day 1] Spend 10 of 100 -> saved 90
        spendsRepository.addSpent(
            Transaction(
                TransactionType.SPENT,
                10.toBigDecimal(),
                currentDateUseCase.value,
            )
        )

        rewindTime(1) // [Day 2]

        // Simulate the ASK redistribution one-shot that runs before the sheet computes
        spendsRepository.markDailyBudgetDistributionHandled()

        // Balance is still positive
        assert(spendsRepository.howMuchBudgetRest() > BigDecimal.ZERO)

        // The saved carry-forward stays positive
        assert(spendsRepository.howMuchSaved() == 90.toBigDecimal().setScale(2))
    }

    // Same guard as carryForwardStaysPositiveAfterDistributionHandledToday but with the
    // real emulator-like numbers: a large budget with a small leftover keeps a positive
    // carry-forward after the distribution was marked handled for today.
    @Test
    fun carryForwardStaysPositiveWithLargeBudget() = runTest {
        spendsRepository.setBudget(
            5000.toBigDecimal(),
            currentDateUseCase.value.toLocalDate().plusDays(31).toDate()
        )

        spendsRepository.addSpent(
            Transaction(
                TransactionType.SPENT,
                150.50.toBigDecimal(),
                currentDateUseCase.value,
            )
        )

        rewindTime(1)

        spendsRepository.markDailyBudgetDistributionHandled()

        assert(spendsRepository.howMuchBudgetRest() > BigDecimal.ZERO)
        assert(spendsRepository.howMuchSaved() == 5.75.toBigDecimal().setScale(2))
    }
}