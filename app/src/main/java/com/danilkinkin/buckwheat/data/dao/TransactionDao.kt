package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
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

    @Insert
    suspend fun insert(vararg transaction: Transaction)

    @Update(entity = Transaction::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg transaction: Transaction)

    @Query("DELETE FROM transactions WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
