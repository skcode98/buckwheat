package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.toTransaction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportAutoCategorizeTest {

    private val currentDateUseCase = FakeGetCurrentDateUseCase()
    private val budgetPeriodDao = FakeBudgetPeriodDao()
    private val transactionDao = FakeTransactionDao()

    private lateinit var spendsRepository: SpendsRepository

    @Before
    fun init() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        spendsRepository = SpendsRepository(
            context = context,
            transactionDao,
            FakeSavedTagDao(),
            FakeSavedCategoryDao(),
            budgetPeriodDao,
            currentDateUseCase,
        )
    }

    private fun spend(comment: String, value: Long = 100L): Transaction =
        Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(value),
            date = currentDateUseCase.value,
            comment = comment,
        )

    // Set budget 1000 for 10 days so the import lands inside an active period.
    private suspend fun setBudget() {
        spendsRepository.setBudget(
            BigDecimal(1000),
            java.util.Date(currentDateUseCase.value.time + 9L * 24 * 3600 * 1000),
        )
    }

    private fun spentRows() = transactionDao.spends.filter { it.type == TransactionType.SPENT }

    @Test
    fun `import persists offline keyword categories`() = runTest {
        setBudget()
        spendsRepository.importTransactions(
            listOf(spend("lunch at the cafe"), spend("bus fare to the city"))
        )

        assertEquals("FOOD", spentRows().first { it.comment == "lunch at the cafe" }.category)
        assertEquals(
            "TRANSPORT",
            spentRows().first { it.comment == "bus fare to the city" }.category,
        )
    }

    @Test
    fun `import leaves unknown comments uncategorized when no ai key is set`() = runTest {
        setBudget()
        spendsRepository.importTransactions(listOf(spend("random expense")))

        assertNull(spentRows().single().category)
    }

    @Test
    fun `import without an active period still persists offline categories`() = runTest {
        spendsRepository.importTransactions(listOf(spend("rent payment"), spend("weird thing")))

        assertEquals("BILLS", spentRows().first { it.comment == "rent payment" }.category)
        assertNull(spentRows().first { it.comment == "weird thing" }.category)
    }

    @Test
    fun `out of period import archives rows with the offline category`() = runTest {
        setBudget()
        val future = java.util.Date(currentDateUseCase.value.time + 20L * 24 * 3600 * 1000)
        spendsRepository.importTransactions(
            listOf(
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(100),
                    date = future,
                    comment = "bus fare to the city",
                )
            )
        )

        val archived = budgetPeriodDao.getAllArchivedNow()
        assertEquals(1, archived.size)
        assertEquals("TRANSPORT", archived.single().category)
    }

    @Test
    fun `archived rows outside the active period keep the category via toTransaction`() = runTest {
        setBudget()
        val past = java.util.Date(currentDateUseCase.value.time - 20L * 24 * 3600 * 1000)
        spendsRepository.importTransactions(
            listOf(
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(100),
                    date = past,
                    comment = "electricity bill",
                )
            )
        )

        val archived = budgetPeriodDao.getAllArchivedNow()
        assertEquals(1, archived.size)
        assertEquals("BILLS", archived.single().toTransaction().category)
    }
}
