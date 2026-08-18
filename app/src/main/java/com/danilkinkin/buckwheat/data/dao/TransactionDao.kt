package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date ASC")
    fun getAll(): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE type = :type ORDER BY date ASC")
    fun getAll(type: TransactionType): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE type = :type AND date >= :startDate AND date <= :endDate ORDER BY date ASC")
    fun getAll(type: TransactionType, startDate: Long, endDate: Long): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC")
    fun getAll(startDate: Long, endDate: Long): LiveData<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE uid = :uid")
    suspend fun getById(uid: Int): Transaction?

    @Query("SELECT * FROM transactions")
    suspend fun getAllNow(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE type = :type AND date >= :startDate AND date <= :endDate ORDER BY date ASC")
    suspend fun getAllNow(type: TransactionType, startDate: Long, endDate: Long): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE type = 'SPENT' AND (category IS NULL OR category = '')")
    fun getUncategorizedCount(): LiveData<Int>

    @Insert
    suspend fun insert(vararg transaction: Transaction)

    @Insert
    suspend fun insertAll(transactions: List<Transaction>)

    @Update(entity = Transaction::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg transaction: Transaction)

    @Query("DELETE FROM transactions WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("UPDATE transactions SET category = :category WHERE uid = :uid")
    suspend fun updateCategory(uid: Int, category: String?)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg transaction: Transaction) {
        deleteAll()
        insert(*transaction)
    }
}
