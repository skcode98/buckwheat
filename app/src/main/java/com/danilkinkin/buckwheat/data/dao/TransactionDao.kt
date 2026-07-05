package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date ASC")
    fun getAll(): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY date ASC")
    suspend fun getAllSuspend(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE type = :type ORDER BY date ASC")
    fun getAll(type: TransactionType): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE type = :type AND date >= :startDateMs AND date <= :endDateMs ORDER BY date ASC")
    fun getAll(type: TransactionType, startDateMs: Long, endDateMs: Long): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE uid = :uid")
    fun getById(uid: Int): Transaction?

    @Insert
    fun insert(vararg transaction: Transaction)

    @Update(entity = Transaction::class, onConflict = OnConflictStrategy.REPLACE)
    fun update(vararg transaction: Transaction)

    @Query("DELETE FROM transactions WHERE uid = :uid")
    fun deleteById(uid: Int)

    @Query("DELETE FROM transactions WHERE comment = :comment")
    fun deleteByComment(comment: String)

    @Query("UPDATE transactions SET comment = :newComment WHERE comment = :oldComment")
    fun renameByComment(oldComment: String, newComment: String)

    @Query("SELECT * FROM transactions WHERE date >= :startDateMs AND date <= :endDateMs ORDER BY date ASC")
    fun getAllByDateRange(startDateMs: Long, endDateMs: Long): LiveData<List<Transaction>>

    @Query("DELETE FROM transactions WHERE uid IN (:uids)")
    fun deleteTransactionsByIds(uids: List<Int>)

    @Query("DELETE FROM transactions")
    fun deleteAll()

    @Query("SELECT * FROM transactions WHERE category_id = :categoryId AND type = 'SPENT' AND date >= :startMs AND date <= :endMs ORDER BY date ASC")
    fun getSpendsByCategory(categoryId: Long, startMs: Long, endMs: Long): LiveData<List<Transaction>>

    @Query("SELECT category_id, SUM(CAST(value AS REAL)) as total FROM transactions WHERE type = 'SPENT' AND date >= :startMs AND date <= :endMs GROUP BY category_id")
    fun getSpentTotalsByCategory(startMs: Long, endMs: Long): LiveData<List<CategorySpentTotal>>
}

data class CategorySpentTotal(
    val category_id: Long?,
    val total: Double,
)
