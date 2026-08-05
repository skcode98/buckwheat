package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import java.math.BigDecimal

@Dao
interface BudgetPeriodDao {
    @Query("SELECT * FROM budget_periods ORDER BY start_date DESC")
    fun getAll(): LiveData<List<BudgetPeriod>>

    @Query("SELECT * FROM budget_periods WHERE id = :id")
    suspend fun getById(id: Int): BudgetPeriod?

    @Query("SELECT * FROM budget_periods")
    suspend fun getAllNow(): List<BudgetPeriod>

    @Insert
    suspend fun insert(period: BudgetPeriod): Long

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId ORDER BY date ASC")
    fun getTransactionsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>>

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId AND type = 'SPENT' ORDER BY date ASC")
    fun getSpendsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>>

    @Query("SELECT * FROM archived_transactions")
    suspend fun getAllArchivedNow(): List<ArchivedTransaction>

    @Query("SELECT * FROM archived_transactions ORDER BY date DESC")
    fun getAllArchived(): LiveData<List<ArchivedTransaction>>

    @Query("UPDATE budget_periods SET total_spent = :totalSpent WHERE id = :periodId")
    suspend fun updateTotalSpent(periodId: Int, totalSpent: BigDecimal)

    @Insert
    suspend fun insertArchivedTransactions(transactions: List<ArchivedTransaction>)
}
