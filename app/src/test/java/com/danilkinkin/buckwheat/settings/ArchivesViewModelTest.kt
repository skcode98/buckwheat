package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.FakeBudgetPeriodDao
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchivesViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var dao: FakeBudgetPeriodDao
    private lateinit var viewModel: ArchivesViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dao = FakeBudgetPeriodDao()
        viewModel = ArchivesViewModel(dao)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun selectedPeriodSpends_emitsOnlySpentRecords() = runTest {
        dao.insertArchivedTransactions(
            listOf(
                ArchivedTransaction(1, TransactionType.INCOME, BigDecimal("1000"), Date(0), ""),
                ArchivedTransaction(1, TransactionType.SET_DAILY_BUDGET, BigDecimal("50"), Date(1), ""),
                ArchivedTransaction(1, TransactionType.SPENT, BigDecimal("20"), Date(2), "lunch"),
                ArchivedTransaction(2, TransactionType.SPENT, BigDecimal("5"), Date(3), "other period"),
            )
        )
        viewModel.selectPeriod(1)

        val spends = viewModel.selectedPeriodSpends.first { it.isNotEmpty() }

        assertEquals(1, spends.size)
        assertTrue(spends.all { it.type == TransactionType.SPENT })
        assertEquals("lunch", spends.first().comment)
    }
}
