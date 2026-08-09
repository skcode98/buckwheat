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
import com.danilkinkin.trackinvest.data.entities.AllocTarget

@Dao
interface AllocTargetDao {
    @Query("SELECT * FROM alloc_targets")
    fun getAll(): LiveData<List<AllocTarget>>

    @Query("SELECT * FROM alloc_targets")
    suspend fun getAllNow(): List<AllocTarget>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg allocTarget: AllocTarget)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(allocTargets: List<AllocTarget>)

    @Update(entity = AllocTarget::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg allocTarget: AllocTarget)

    @Query("DELETE FROM alloc_targets WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("DELETE FROM alloc_targets")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg allocTarget: AllocTarget) {
        deleteAll()
        insert(*allocTarget)
    }
}
