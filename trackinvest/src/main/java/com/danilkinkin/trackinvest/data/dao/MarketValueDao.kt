/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import com.danilkinkin.trackinvest.data.entities.MarketValue

@Dao
interface MarketValueDao {
    @Query("SELECT * FROM market_values")
    fun getAll(): LiveData<List<MarketValue>>

    @Query("SELECT * FROM market_values")
    suspend fun getAllNow(): List<MarketValue>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg marketValue: MarketValue)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(marketValues: List<MarketValue>)

    @Update(entity = MarketValue::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg marketValue: MarketValue)

    @Query("DELETE FROM market_values WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("DELETE FROM market_values")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg marketValue: MarketValue) {
        deleteAll()
        insert(*marketValue)
    }
}
