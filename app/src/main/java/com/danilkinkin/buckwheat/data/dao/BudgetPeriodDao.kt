package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import java.math.BigDecimal
import java.util.Date

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

    @Insert
    suspend fun insertAll(periods: List<BudgetPeriod>)

    @Query("DELETE FROM budget_periods")
    suspend fun deleteAll()

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId ORDER BY date ASC")
    fun getTransactionsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>>

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId AND type = 'SPENT' ORDER BY date ASC")
    fun getSpendsForPeriod(periodId: Int): LiveData<List<ArchivedTransaction>>

    @Query("SELECT * FROM archived_transactions")
    suspend fun getAllArchivedNow(): List<ArchivedTransaction>

    @Query("SELECT COUNT(*) FROM archived_transactions WHERE type = 'SPENT' AND (category IS NULL OR category = '')")
    fun getArchivedUncategorizedCount(): LiveData<Int>

    @Query("SELECT * FROM archived_transactions ORDER BY date DESC")
    fun getAllArchived(): LiveData<List<ArchivedTransaction>>

    @Query("UPDATE budget_periods SET total_spent = :totalSpent WHERE id = :periodId")
    suspend fun updateTotalSpent(periodId: Int, totalSpent: BigDecimal)

    @Query("UPDATE budget_periods SET start_date = :startDate, finish_date = :finishDate WHERE id = :id")
    suspend fun updateDates(id: Int, startDate: Date, finishDate: Date)

    @Query("UPDATE archived_transactions SET category = :category WHERE uid = :uid")
    suspend fun updateCategory(uid: Int, category: String?)

    @Insert
    suspend fun insertArchivedTransactions(transactions: List<ArchivedTransaction>)
}
