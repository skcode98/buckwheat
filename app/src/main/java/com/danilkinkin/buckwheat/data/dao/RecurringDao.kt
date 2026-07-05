package com.danilkinkin.buckwheat.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_templates ORDER BY dayOfMonth ASC")
    fun getAll(): List<RecurringTemplate>

    @Query("SELECT * FROM recurring_templates WHERE enabled = 1 AND dayOfMonth = :day")
    fun getDueOnDay(day: Int): List<RecurringTemplate>

    @Insert
    fun insert(template: RecurringTemplate)

    @Update
    fun update(template: RecurringTemplate)

    @Delete
    fun delete(template: RecurringTemplate)

    @Query("DELETE FROM recurring_templates WHERE id = :id")
    fun deleteById(id: Int)
}
