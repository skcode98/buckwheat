package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.math.BigDecimal
import java.util.Date

class FakeBudgetPeriodDao : BudgetPeriodDao {
    private val periods = mutableListOf<BudgetPeriod>()
    private val archivedTransactions = mutableListOf<ArchivedTransaction>()

    override fun getAll(): Flow<List<BudgetPeriod>> {
        return flow { emit(periods.toList()) }
    }

    override suspend fun getById(id: Int): BudgetPeriod? {
        return periods.firstOrNull { it.id == id }
    }

    override suspend fun getAllNow(): List<BudgetPeriod> {
        return periods.toList()
    }

    override suspend fun insert(period: BudgetPeriod): Long {
        if (period.id == 0) {
            period.id = periods.size
        }
        periods.add(period)
        return period.id.toLong()
    }

    override suspend fun insertAll(periods: List<BudgetPeriod>) {
        periods.forEach { insert(it) }
    }

    override suspend fun deleteAll() {
        periods.clear()
        archivedTransactions.clear()
    }

    override fun getTransactionsForPeriod(periodId: Int): Flow<List<ArchivedTransaction>> {
        return flow { emit(archivedTransactions.filter { it.periodId == periodId }) }
    }

    override fun getSpendsForPeriod(periodId: Int): Flow<List<ArchivedTransaction>> {
        return flow {
            emit(
                archivedTransactions.filter { it.periodId == periodId && it.type == TransactionType.SPENT }
            )
        }
    }

    override suspend fun getAllArchivedNow(): List<ArchivedTransaction> {
        return archivedTransactions.toList()
    }

    override fun getArchivedUncategorizedCount(): Flow<Int> {
        return flow {
            emit(
                archivedTransactions.count {
                    it.type == TransactionType.SPENT && it.category.isNullOrBlank()
                }
            )
        }
    }

    override fun getAllArchived(): Flow<List<ArchivedTransaction>> {
        return flow { emit(archivedTransactions.toList()) }
    }

    override suspend fun updateTotalSpent(periodId: Int, totalSpent: BigDecimal) {
        val index = periods.indexOfFirst { it.id == periodId }
        if (index >= 0) {
            periods[index] = periods[index].copy(totalSpent = totalSpent).also { it.id = periodId }
        }
    }

    override suspend fun updateBudget(id: Int, budget: BigDecimal) {
        val index = periods.indexOfFirst { it.id == id }
        if (index >= 0) {
            periods[index] = periods[index].copy(budget = budget).also { it.id = id }
        }
    }

    override suspend fun updateDates(id: Int, startDate: Date, finishDate: Date) {
        val index = periods.indexOfFirst { it.id == id }
        if (index >= 0) {
            periods[index] = periods[index]
                .copy(startDate = startDate, finishDate = finishDate)
                .also { it.id = id }
        }
    }

    override suspend fun deleteById(id: Int) {
        periods.removeIf { it.id == id }
    }

    override suspend fun updateCategory(uid: Int, category: String?) {
        val index = archivedTransactions.indexOfFirst { it.uid == uid }
        if (index >= 0) {
            archivedTransactions[index] = archivedTransactions[index].copy(category = category).also { it.uid = uid }
        }
    }

    override suspend fun insertArchivedTransactions(transactions: List<ArchivedTransaction>) {
        archivedTransactions.addAll(transactions)
    }
}