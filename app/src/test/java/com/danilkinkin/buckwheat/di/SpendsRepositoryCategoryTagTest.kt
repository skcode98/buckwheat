package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SpendsRepositoryCategoryTagTest : SpendsRepositoryTestBase() {

    // Distinct category values assigned to transactions surface via getAllCategories so the
    // Categories Management sheet can show (and offer to re-save) them even before the user
    // has added any custom categories.
    @Test
    fun getAllCategoriesMergesTransactionCategories() = runTest {
        setBudget()

        spendsRepository.addSpent(
            Transaction(
                type = TransactionType.SPENT,
                value = 10.toBigDecimal(),
                date = currentDateUseCase.value,
                comment = "groceries",
                category = "FOOD",
            )
        )
        spendsRepository.addSpent(
            Transaction(
                type = TransactionType.SPENT,
                value = 20.toBigDecimal(),
                date = currentDateUseCase.value,
                comment = "gifts",
                category = "MyCategory",
            )
        )
        spendsRepository.addSpent(
            Transaction(
                type = TransactionType.SPENT,
                value = 30.toBigDecimal(),
                date = currentDateUseCase.value,
                comment = "no category",
            )
        )

        val categories = spendsRepository.getAllCategories().first()

        assert(categories.contains("FOOD"))
        assert(categories.contains("MyCategory"))
        assert(categories.size == 2)
    }
}