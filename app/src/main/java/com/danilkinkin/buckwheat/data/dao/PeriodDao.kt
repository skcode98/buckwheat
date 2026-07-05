package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.Period

@Dao
interface PeriodDao {
    @Query("SELECT * FROM periods ORDER BY start_date DESC")
    fun getAll(): LiveData<List<Period>>

    @Query("SELECT * FROM periods WHERE id = :id")
    fun getById(id: Long): Period?

    @Insert
    fun insert(period: Period): Long

    @Update
    fun update(period: Period)

    @Delete
    fun delete(period: Period)

    @Query("DELETE FROM periods")
    fun deleteAll()
}
