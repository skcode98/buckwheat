package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import com.danilkinkin.buckwheat.util.toLocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SpendsRepositoryImportTest : SpendsRepositoryTestBase() {

    // Imported entries outside the active budget period are archived into a month bucket and must not consume the current budget
    @Test
    fun importOlderThanCurrentPeriodSpendShouldNotAffectBudget() = runTest {
        setBudget()

        val olderSpend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value.toLocalDateTime().minusDays(1).toDate(),
        )

        spendsRepository.importTransactions(listOf(olderSpend))

        assert(!spendsRepository.getAllSpends().first().contains(olderSpend))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpent().first() == 0.toBigDecimal().setScale(2))

        val buckets = budgetPeriodDao.getAll().first().filter { it.isImported }
        assert(buckets.size == 1)
        val bucket = buckets.single()
        assert(bucket.isImported)
        assert(bucket.totalSpent == 10.toBigDecimal().setScale(2))
        assert(!olderSpend.date.before(bucket.startDate) && !olderSpend.date.after(bucket.finishDate))
    }

    // Removing an imported spend outside the current period must not touch the budget
    @Test
    fun removeOlderThanCurrentPeriodSpendShouldNotAffectBudget() = runTest {
        setBudget()

        val olderSpend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value.toLocalDateTime().minusDays(1).toDate(),
        )

        spendsRepository.importTransactions(listOf(olderSpend))
        spendsRepository.removeSpent(olderSpend)

        assert(!spendsRepository.getAllSpends().first().contains(olderSpend))
        assert(spendsRepository.getSpentFromDailyBudget().first() == 0.toBigDecimal().setScale(2))
        assert(spendsRepository.getSpent().first() == 0.toBigDecimal().setScale(2))
    }

    // Out-of-period imports are grouped by calendar month and never count toward the current budget
    @Test
    fun importOutOfPeriodSpendsAreGroupedByMonthAndDoNotAffectBudget() = runTest {
        setBudget()

        val lastMonth = currentDateUseCase.value.toLocalDate().minusMonths(1)
        val inPeriodSpend = Transaction(TransactionType.SPENT, 5.toBigDecimal(), currentDateUseCase.value)
        val oldSpendA = Transaction(TransactionType.SPENT, 10.toBigDecimal(), lastMonth.withDayOfMonth(5).toDate())
        val oldSpendB = Transaction(TransactionType.SPENT, 20.toBigDecimal(), lastMonth.withDayOfMonth(20).toDate())

        spendsRepository.importTransactions(listOf(inPeriodSpend, oldSpendA, oldSpendB))

        assert(spendsRepository.getAllSpends().first().contains(inPeriodSpend))
        assert(!spendsRepository.getAllSpends().first().contains(oldSpendA))
        assert(!spendsRepository.getAllSpends().first().contains(oldSpendB))

        val buckets = budgetPeriodDao.getAll().first().filter { it.isImported }
        assert(buckets.size == 1)
        val bucket = buckets.single()
        assert(bucket.startDate.toLocalDate() == lastMonth.withDayOfMonth(1))
        assert(bucket.finishDate.toLocalDate() == lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()))
        assert(bucket.totalSpent == 30.toBigDecimal().setScale(2))

        val archived = budgetPeriodDao.getTransactionsForPeriod(bucket.id).first()
        assert(archived.size == 2)
        assert(archived.all { it.periodId == bucket.id })
        assert(spendsRepository.getSpentFromDailyBudget().first() == 5.toBigDecimal().setScale(2))
    }

    // Re-importing a file whose rows were already archived must not duplicate them
    @Test
    fun reimportArchivedRowsDoesNotDuplicate() = runTest {
        setBudget()

        val olderSpend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value.toLocalDateTime().minusDays(1).toDate(),
        )

        spendsRepository.importTransactions(listOf(olderSpend))
        spendsRepository.importTransactions(listOf(olderSpend))

        val buckets = budgetPeriodDao.getAll().first().filter { it.isImported }
        val archived = buckets.flatMap {
            budgetPeriodDao.getTransactionsForPeriod(it.id).first()
        }
        assert(archived.size == 1)
        assert(
            spendsRepository.getAllSpends().first()
                .none { it.type == TransactionType.SPENT }
        )
    }

    // Comments of imported out-of-period transactions must surface as tags even though
    // they are stored in the archived table (the tag picker and Tags Management read them)
    @Test
    fun importedArchivedCommentsBecomeTags() = runTest {
        setBudget()

        val oldSpend = Transaction(
            type = TransactionType.SPENT,
            value = 10.toBigDecimal(),
            date = currentDateUseCase.value.toLocalDateTime().minusDays(1).toDate(),
            comment = "groceries",
        )

        spendsRepository.importTransactions(listOf(oldSpend))

        val tags = spendsRepository.getAllTags().first()

        assert(tags.contains("groceries"))
    }
}