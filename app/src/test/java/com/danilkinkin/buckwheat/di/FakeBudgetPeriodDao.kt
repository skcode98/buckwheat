package com.danilkinkin.buckwheat.di

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import java.math.BigDecimal

class FakeBudgetPeriodDao : BudgetPeriodDao {
    private val periods = mutableListOf<BudgetPeriod>()
    private val archivedTransactions = mutableListOf<ArchivedTransaction>()

    override fun getAll(): LiveData<List<BudgetPeriod>> {
        return MutableLiveData(periods)
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

    override fun getTransactionsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>> {
        return MutableLiveData(archivedTransactions.filter { it.periodId == periodId })
    }

    override fun getSpendsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>> {
        return MutableLiveData(
            archivedTransactions.filter { it.periodId == periodId && it.type == com.danilkinkin.buckwheat.data.entities.TransactionType.SPENT }
        )
    }

    override suspend fun getAllArchivedNow(): List<ArchivedTransaction> {
        return archivedTransactions.toList()
    }

    override fun getAllArchived(): LiveData<List<ArchivedTransaction>> {
        return MutableLiveData(archivedTransactions.toList())
    }

    override suspend fun updateTotalSpent(periodId: Int, totalSpent: BigDecimal) {
        val index = periods.indexOfFirst { it.id == periodId }
        if (index >= 0) {
            periods[index] = periods[index].copy(totalSpent = totalSpent).also { it.id = periodId }
        }
    }

    override suspend fun insertArchivedTransactions(transactions: List<ArchivedTransaction>) {
        archivedTransactions.addAll(transactions)
    }

    override suspend fun updateCategory(uid: Int, category: String?) {
        val index = archivedTransactions.indexOfFirst { it.uid == uid }
        if (index >= 0) {
            archivedTransactions[index] = archivedTransactions[index].copy(category = category).also { it.uid = uid }
        }
    }
}
