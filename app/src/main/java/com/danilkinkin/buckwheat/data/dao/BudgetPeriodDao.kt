package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod

@Dao
interface BudgetPeriodDao {
    @Query("SELECT * FROM budget_periods ORDER BY start_date DESC")
    fun getAll(): LiveData<List<BudgetPeriod>>

    @Query("SELECT * FROM budget_periods WHERE id = :id")
    suspend fun getById(id: Int): BudgetPeriod?

    @Insert
    suspend fun insert(period: BudgetPeriod): Long

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId ORDER BY date ASC")
    fun getTransactionsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>>

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId AND type = 'SPENT' ORDER BY date ASC")
    fun getSpendsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>>

    @Insert
    suspend fun insertArchivedTransactions(transactions: List<ArchivedTransaction>)
}
