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

    override suspend fun getById(id: String): BudgetPeriod? {
        return periods.firstOrNull { it.id == id }
    }

    override suspend fun getAllNow(): List<BudgetPeriod> {
        return periods.toList()
    }

    override suspend fun insert(period: BudgetPeriod) {
        periods.add(period)
    }

    override suspend fun insertAll(periods: List<BudgetPeriod>) {
        periods.forEach { insert(it) }
    }

    override suspend fun deleteAll() {
        periods.clear()
        archivedTransactions.clear()
    }

    override fun getTransactionsForPeriod(periodId: String): Flow<List<ArchivedTransaction>> {
        return flow { emit(archivedTransactions.filter { it.periodId == periodId }) }
    }

    override fun getSpendsForPeriod(periodId: String): Flow<List<ArchivedTransaction>> {
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

    override suspend fun updateTotalSpent(periodId: String, totalSpent: BigDecimal) {
        val index = periods.indexOfFirst { it.id == periodId }
        if (index >= 0) {
            periods[index] = periods[index].copy(totalSpent = totalSpent)
        }
    }

    override suspend fun updateBudget(id: String, budget: BigDecimal) {
        val index = periods.indexOfFirst { it.id == id }
        if (index >= 0) {
            periods[index] = periods[index].copy(budget = budget)
        }
    }

    override suspend fun updateDates(id: String, startDate: Date, finishDate: Date) {
        val index = periods.indexOfFirst { it.id == id }
        if (index >= 0) {
            periods[index] = periods[index].copy(startDate = startDate, finishDate = finishDate)
        }
    }

    override suspend fun deleteById(id: String) {
        periods.removeIf { it.id == id }
    }

    override suspend fun updateCategory(id: String, category: String?) {
        val index = archivedTransactions.indexOfFirst { it.id == id }
        if (index >= 0) {
            archivedTransactions[index] = archivedTransactions[index].copy(category = category)
        }
    }

    override suspend fun insertArchivedTransactions(transactions: List<ArchivedTransaction>) {
        archivedTransactions.addAll(transactions)
    }

    override suspend fun deleteArchivedById(id: String) {
        archivedTransactions.removeIf { it.id == id }
    }
}
