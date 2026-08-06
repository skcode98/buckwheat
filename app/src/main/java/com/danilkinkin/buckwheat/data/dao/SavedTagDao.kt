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

    @Query("SELECT * FROM saved_tags")
    suspend fun getAllNow(): List<SavedTag>

    @Query("SELECT * FROM saved_tags WHERE name = :name")
    suspend fun getByName(name: String): SavedTag?

    @Query("SELECT EXISTS(SELECT 1 FROM saved_tags WHERE name = :name)")
    suspend fun existsByName(name: String): Boolean

    @Insert
    suspend fun insert(tag: SavedTag): Long

    @Insert
    suspend fun insertAll(tags: List<SavedTag>)

    @Update
    suspend fun update(tag: SavedTag)

    @Query("DELETE FROM saved_tags WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM saved_tags")
    suspend fun deleteAll()
}
