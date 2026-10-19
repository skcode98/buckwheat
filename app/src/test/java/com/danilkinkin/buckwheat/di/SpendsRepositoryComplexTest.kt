package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal

class SpendsRepositoryComplexTest : SpendsRepositoryTestBase() {

    // Add to today every day
    // Init budget 330 for 10 days
    // [Day 1] dailyBudget = 330 / 10 = 33 > No spent > not spent = 33
    // [Day 2] dailyBudget = (330 - 33) / 9 + 33 = 66 > No spent > not spent = 66
    // [Day 3] dailyBudget = (330 - 66) / 8 + 66 = 99 > No spent > not spent = 99
    @Test
    fun savedWithAdd() = runTest {
        setBudget(330)

        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))

        distributeBudgetAddToday()
        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 66.toBigDecimal().setScale(2))

        distributeBudgetAddToday()
        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() - spendsRepository.nextDayBudget() == 99.toBigDecimal().setScale(2))
    }

    // Overdraft, add spent on next day and skip day
    // Init budget 1000 for 10 days
    // [Day 1] dailyBudget = 1000 / 10 = 100 > Spend 140 > not spent = -40
    // [Day 2] dailyBudget = (1000 - 140) / 9 = 95.56 > Spend 10 > not spent = 85.56
    // [Day 3] dailyBudget = (1000 - 150) / 8 = 106.25 > No spent > not spent = 106.25
    // [Day 4] dailyBudget = (1000 - 150) / 7 = 121.43 > No Spent > not spent = 121.43
    // [Day 5] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 242.86
    // [Day 6] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 364.29
    // [Day 7] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 485.72
    // [Day 8] dailyBudget = (1000 - 150) / 3 = 283.33 > Spend 300 > not spent = -16.67
    // [Day 9] dailyBudget = (1000 - 450) / 2 = 275 > Spend 100 > not spent = 175
    // [Day 10] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450
    // [Day 11] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450
    // [Day 12] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450
    @Test
    fun complexTest1() = runTest {
        setBudget()

        // [Day 1] dailyBudget = 1000 / 10 = 100 > Spend 140 > not spent = -40

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 100.toBigDecimal().setScale(2))

        spendsRepository.addSpent(Transaction(TransactionType.SPENT, 140.toBigDecimal(), currentDateUseCase.value))

        assert(spendsRepository.nextDayBudget() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 140.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == (-40).toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 2] dailyBudget = (1000 - 140) / 9 = 95.56 > Spend 10 > not spent = 85.56

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 95.56.toBigDecimal().setScale(2))

        spendsRepository.addSpent(Transaction(TransactionType.SPENT, 10.toBigDecimal(), currentDateUseCase.value))

        assert(spendsRepository.nextDayBudget() == 95.56.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 10.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 85.56.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 3] dailyBudget = (1000 - 150) / 8 = 106.25 > No spent > not spent = 106.25

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 106.25.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 106.25.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 106.25.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 4] dailyBudget = (1000 - 150) / 7 = 121.43 > No Spent > not spent = 121.43

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 121.43.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 5] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 242.86

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 141.67.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 242.86.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 6] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 364.29

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 170.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 364.29.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 7] dailyBudget = (1000 - 150) / 7 = 121.43 > Skip > not spent = 485.72

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 212.5.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 121.43.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 485.72.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 8] dailyBudget = (1000 - 150) / 3 = 283.33 > Spend 300 > not spent = -16.67

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 283.33.toBigDecimal().setScale(2))

        spendsRepository.addSpent(Transaction(TransactionType.SPENT, 300.toBigDecimal(), currentDateUseCase.value))

        assert(spendsRepository.nextDayBudget() == 283.34.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 300.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == (-16.67).toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 9] dailyBudget = (1000 - 450) / 2 = 275 > Spend 100 > not spent = 175

        distributeBudget()

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 275.toBigDecimal().setScale(2))

        spendsRepository.addSpent(Transaction(TransactionType.SPENT, 100.toBigDecimal(), currentDateUseCase.value))

        assert(spendsRepository.nextDayBudget() == 275.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 175.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 10] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 275.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 450.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 11] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 450.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 12] dailyBudget = (1000 - 550) / 1 = 450 > No Spent > not spent = 450

        assert(spendsRepository.whatBudgetForDay(applyTodaySpends = true) == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 450.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 100.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 450.toBigDecimal().setScale(2))
    }

    // Add to today every day
    // Init budget 330 for 10 days
    // [Day 1] dailyBudget = 330 / 10 = 33 > No spent > not spent = 33
    // [Day 2] dailyBudget = (330 - 33) / 9 + 33 = 66 > No spent > not spent = 66
    // [Day 3] dailyBudget = (330 - 66) / 8 + 66 = 99 > No spent > not spent = 99
    // [Day 4] dailyBudget = (330 - 99) / 7 + 99 = 132 > No spent > not spent = 132
    // [Day 5] dailyBudget = (330 - 132) / 6 + 132 = 165 > Skip > not spent = 165
    // [Day 6] dailyBudget = (330 - 165) / 5 + 165 = 198 > Skip > not spent = 198
    // [Day 7] dailyBudget = (330 - 198) / 4 + 198 = 231 > Skip > not spent = 231
    // [Day 8] dailyBudget = (330 - 231) / 3 + 231 = 264 > Spend 300 > not spent = 264
    // [Day 9] dailyBudget = (330 - 264) / 2 + 264 = 297 > Spend 100 > not spent = 297
    // [Day 10] dailyBudget = (330 - 297) / 1 + 297 = 330 > No Spent > not spent = 330
    @Test
    fun complexTest2() = runTest {
        setBudget(330)

        // [Day 1] dailyBudget = 330 / 10 = 33 > No spent > not spent = 33

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 33.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 2] dailyBudget = (330 - 33) / 9 + 33 = 66 > No spent > not spent = 66

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 66.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 3] dailyBudget = (330 - 66) / 8 + 66 = 99 > No spent > not spent = 99

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 99.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 4] dailyBudget = (330 - 99) / 7 + 99 = 132 > No spent > not spent = 132

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 132.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 5] dailyBudget = (330 - 132) / 6 + 132 = 165 > Skip > not spent = 165

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 165.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 6] dailyBudget = (330 - 165) / 5 + 165 = 198 > Skip > not spent = 198

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 198.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 7] dailyBudget = (330 - 198) / 4 + 198 = 231 > Skip > not spent = 231

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 231.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 8] dailyBudget = (330 - 231) / 3 + 231 = 264 > Spend 300 > not spent = 264

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 264.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 9] dailyBudget = (330 - 264) / 2 + 264 = 297 > Spend 100 > not spent = 297

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 33.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 297.toBigDecimal().setScale(2))

        rewindTime(1)

        // [Day 10] dailyBudget = (330 - 297) / 1 + 297 = 330 > No Spent > not spent = 330

        distributeBudgetAddToday()

        assert(spendsRepository.nextDayBudget() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.howMuchNotSpent() == 330.toBigDecimal().setScale(2))
    }
}