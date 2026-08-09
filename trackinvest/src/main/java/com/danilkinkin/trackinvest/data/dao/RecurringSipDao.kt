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
import com.danilkinkin.trackinvest.data.entities.RecurringSip

@Dao
interface RecurringSipDao {
    @Query("SELECT * FROM recurring_sips ORDER BY nextRun ASC")
    fun getAll(): LiveData<List<RecurringSip>>

    @Query("SELECT * FROM recurring_sips ORDER BY nextRun ASC")
    suspend fun getAllNow(): List<RecurringSip>

    @Query("SELECT * FROM recurring_sips WHERE uid = :uid")
    suspend fun getById(uid: Int): RecurringSip?

    @Insert
    suspend fun insert(vararg recurringSip: RecurringSip)

    @Insert
    suspend fun insertAll(recurringSips: List<RecurringSip>)

    @Update(entity = RecurringSip::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg recurringSip: RecurringSip)

    @Query("DELETE FROM recurring_sips WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("DELETE FROM recurring_sips")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg recurringSip: RecurringSip) {
        deleteAll()
        insert(*recurringSip)
    }
}
