package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.SavedTag

@Dao
interface SavedTagDao {
    @Query("SELECT * FROM saved_tags ORDER BY name ASC")
    fun getAll(): LiveData<List<SavedTag>>

    @Query("SELECT * FROM saved_tags WHERE id = :id")
    suspend fun getById(id: Int): SavedTag?

    @Insert
    suspend fun insert(tag: SavedTag): Long

    @Update
    suspend fun update(tag: SavedTag)

    @Query("DELETE FROM saved_tags WHERE id = :id")
    suspend fun deleteById(id: Int)
}
