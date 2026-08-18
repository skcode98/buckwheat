package com.danilkinkin.buckwheat.data.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_templates ORDER BY day_of_month ASC")
    fun getAll(): Flow<List<RecurringTemplate>>

    @Query("SELECT * FROM recurring_templates WHERE enabled = 1 AND day_of_month = :day")
    suspend fun getDueOnDay(day: Int): List<RecurringTemplate>

    @Query("SELECT * FROM recurring_templates")
    suspend fun getAllNow(): List<RecurringTemplate>

    @Insert
    suspend fun insert(template: RecurringTemplate): Long

    @Insert
    suspend fun insertAll(templates: List<RecurringTemplate>)

    @Update
    suspend fun update(template: RecurringTemplate)

    @Delete
    suspend fun delete(template: RecurringTemplate)

    @Query("DELETE FROM recurring_templates WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM recurring_templates")
    suspend fun deleteAll()
}
