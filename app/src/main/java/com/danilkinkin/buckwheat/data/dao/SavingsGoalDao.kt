package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.SavingsGoal

@Dao
interface SavingsGoalDao {
    @Query("SELECT * FROM savings_goals ORDER BY created_at DESC")
    fun getAll(): LiveData<List<SavingsGoal>>

    @Query("SELECT * FROM savings_goals WHERE id = :id")
    suspend fun getById(id: Long): SavingsGoal?

    @Query("SELECT * FROM savings_goals")
    suspend fun getAllNow(): List<SavingsGoal>

    @Insert
    suspend fun insert(goal: SavingsGoal): Long

    @Insert
    suspend fun insertAll(goals: List<SavingsGoal>)

    @Update
    suspend fun update(goal: SavingsGoal)

    @Delete
    suspend fun delete(goal: SavingsGoal)

    @Query("DELETE FROM savings_goals WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM savings_goals")
    suspend fun deleteAll()
}
