package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.util.toDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SpendsRepositoryBudgetTest : SpendsRepositoryTestBase() {

    // Check budget set correctly
    @Test
    fun setBudgetTest() = runTest {
        setBudget()

        assert(spendsRepository.getBudget().first() == 1000.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 100.toBigDecimal().setScale(2))
    }

    // Check budget set correctly distribute after change day
    @Test
    fun reCalcBudgetAfterChangeDayTest() = runTest {
        setBudget()
        rewindTime(1)

        assert(spendsRepository.howMuchNotSpent() == 200.toBigDecimal().setScale(2))

        distributeBudget()

        assert(spendsRepository.getBudget().first() == 1000.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 111.11.toBigDecimal().setScale(2))
    }

    // Check budget set correctly distribute after change few days
    @Test
    fun reCalcBudgetAfterSkipFewDayTest() = runTest {
        setBudget()
        rewindTime(2)

        assert(spendsRepository.howMuchNotSpent() == 300.toBigDecimal().setScale(2))

        distributeBudget()

        assert(spendsRepository.getBudget().first() == 1000.toBigDecimal().setScale(2))
        assert(spendsRepository.nextDayBudget() == 125.toBigDecimal().setScale(2))
    }
}