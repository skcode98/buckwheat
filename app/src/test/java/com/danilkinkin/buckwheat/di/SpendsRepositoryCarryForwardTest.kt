package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SpendsRepositoryCarryForwardTest : SpendsRepositoryTestBase() {

    private fun assertMoney(label: String, expected: String, actual: BigDecimal) {
        assertEquals("$label: expected $expected but was $actual", 0, actual.compareTo(BigDecimal(expected)))
    }

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

        assertMoney(
            "saved after day 1",
            "90",
            spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget(),
        )

        distributeBudget()
        rewindTime(1)

        assertMoney(
            "saved after day 2 distribution",
            "110",
            spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget(),
        )

        distributeBudget()
        rewindTime(2)

        assertMoney(
            "saved after day 4 distribution",
            "247.5",
            spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget(),
        )
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
        assertTrue(
            "balance should stay positive but was ${spendsRepository.howMuchBudgetRest()}",
            spendsRepository.howMuchBudgetRest() > BigDecimal.ZERO,
        )

        // The saved carry-forward stays positive
        assertMoney("saved carry-forward", "90", spendsRepository.howMuchSaved())
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

        assertTrue(
            "balance should stay positive but was ${spendsRepository.howMuchBudgetRest()}",
            spendsRepository.howMuchBudgetRest() > BigDecimal.ZERO,
        )
        assertMoney("saved carry-forward", "5.75", spendsRepository.howMuchSaved())
    }
}
