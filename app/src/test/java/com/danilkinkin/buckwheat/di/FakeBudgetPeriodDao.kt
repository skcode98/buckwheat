package com.danilkinkin.buckwheat.di

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod

class FakeBudgetPeriodDao : BudgetPeriodDao {
    private val periods = mutableListOf<BudgetPeriod>()
    private val archivedTransactions = mutableListOf<ArchivedTransaction>()

    override fun getAll(): LiveData<List<BudgetPeriod>> {
        return MutableLiveData(periods)
    }

    override suspend fun getById(id: Int): BudgetPeriod? {
        return periods.firstOrNull { it.id == id }
    }

    override suspend fun insert(period: BudgetPeriod): Long {
        periods.add(period)
        period.id = periods.size
        return period.id.toLong()
    }

    override fun getTransactionsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>> {
        return MutableLiveData(archivedTransactions.filter { it.periodId == periodId })
    }

    override fun getSpendsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>> {
        return MutableLiveData(
            archivedTransactions.filter { it.periodId == periodId && it.type == com.danilkinkin.buckwheat.data.entities.TransactionType.SPENT }
        )
    }

    override suspend fun insertArchivedTransactions(transactions: List<ArchivedTransaction>) {
        archivedTransactions.addAll(transactions)
    }
}
