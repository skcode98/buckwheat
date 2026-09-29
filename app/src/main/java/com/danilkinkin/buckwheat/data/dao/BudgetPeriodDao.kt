package com.danilkinkin.buckwheat.data.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import java.math.BigDecimal
import java.util.Date

@Dao
interface BudgetPeriodDao {
    @Query("SELECT * FROM budget_periods ORDER BY start_date DESC")
    fun getAll(): Flow<List<BudgetPeriod>>

    @Query("SELECT * FROM budget_periods WHERE id = :id")
    suspend fun getById(id: String): BudgetPeriod?

    @Query("SELECT * FROM budget_periods")
    suspend fun getAllNow(): List<BudgetPeriod>

    @Insert
    suspend fun insert(period: BudgetPeriod)

    @Insert
    suspend fun insertAll(periods: List<BudgetPeriod>)

    @Query("DELETE FROM budget_periods")
    suspend fun deleteAll()

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId ORDER BY date ASC")
    fun getTransactionsForPeriod(periodId: String): Flow<List<ArchivedTransaction>>

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId AND type = 'SPENT' ORDER BY date ASC")
    fun getSpendsForPeriod(periodId: String): Flow<List<ArchivedTransaction>>

    @Query("SELECT * FROM archived_transactions")
    suspend fun getAllArchivedNow(): List<ArchivedTransaction>

    @Query("SELECT COUNT(*) FROM archived_transactions WHERE type = 'SPENT' AND (category IS NULL OR category = '')")
    fun getArchivedUncategorizedCount(): Flow<Int>

    @Query("SELECT * FROM archived_transactions ORDER BY date DESC")
    fun getAllArchived(): Flow<List<ArchivedTransaction>>

    @Query("UPDATE budget_periods SET total_spent = :totalSpent WHERE id = :periodId")
    suspend fun updateTotalSpent(periodId: String, totalSpent: BigDecimal)

    @Query("UPDATE budget_periods SET budget = :budget WHERE id = :id")
    suspend fun updateBudget(id: String, budget: BigDecimal)

    @Query("UPDATE budget_periods SET start_date = :startDate, finish_date = :finishDate WHERE id = :id")
    suspend fun updateDates(id: String, startDate: Date, finishDate: Date)

    @Query("DELETE FROM budget_periods WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE archived_transactions SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: String, category: String?)

    @Insert
    suspend fun insertArchivedTransactions(transactions: List<ArchivedTransaction>)

    @Query("DELETE FROM archived_transactions WHERE id = :id")
    suspend fun deleteArchivedById(id: String)
}
