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
import com.danilkinkin.trackinvest.data.entities.Milestone

@Dao
interface MilestoneDao {
    @Query("SELECT * FROM milestones ORDER BY value ASC")
    fun getAll(): LiveData<List<Milestone>>

    @Query("SELECT * FROM milestones ORDER BY value ASC")
    suspend fun getAllNow(): List<Milestone>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg milestone: Milestone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(milestones: List<Milestone>)

    @Update(entity = Milestone::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg milestone: Milestone)

    @Query("DELETE FROM milestones WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("DELETE FROM milestones")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg milestone: Milestone) {
        deleteAll()
        insert(*milestone)
    }
}
