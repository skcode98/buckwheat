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
import com.danilkinkin.trackinvest.data.entities.Goal

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals")
    fun getAll(): LiveData<List<Goal>>

    @Query("SELECT * FROM goals")
    suspend fun getAllNow(): List<Goal>

    @Query("SELECT * FROM goals WHERE uid = :uid")
    suspend fun getById(uid: Int): Goal?

    @Insert
    suspend fun insert(vararg goal: Goal)

    @Insert
    suspend fun insertAll(goals: List<Goal>)

    @Update(entity = Goal::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg goal: Goal)

    @Query("DELETE FROM goals WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("DELETE FROM goals")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg goal: Goal) {
        deleteAll()
        insert(*goal)
    }
}
