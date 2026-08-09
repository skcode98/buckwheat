/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import com.danilkinkin.trackinvest.data.entities.Investment

@Dao
interface InvestmentDao {
    @Query("SELECT * FROM investments ORDER BY date DESC")
    fun getAll(): LiveData<List<Investment>>

    @Query("SELECT * FROM investments ORDER BY date DESC")
    suspend fun getAllNow(): List<Investment>

    @Query("SELECT * FROM investments WHERE uid = :uid")
    suspend fun getById(uid: Int): Investment?

    @Insert
    suspend fun insert(vararg investment: Investment)

    @Insert
    suspend fun insertAll(investments: List<Investment>)

    @Update(entity = Investment::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg investment: Investment)

    @Query("DELETE FROM investments WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("DELETE FROM investments")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg investment: Investment) {
        deleteAll()
        insert(*investment)
    }
}
