package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.SavedCategory

@Dao
interface SavedCategoryDao {
    @Query("SELECT * FROM saved_categories ORDER BY name ASC")
    fun getAll(): LiveData<List<SavedCategory>>

    @Query("SELECT * FROM saved_categories WHERE id = :id")
    suspend fun getById(id: Int): SavedCategory?

    @Query("SELECT * FROM saved_categories WHERE name = :name")
    suspend fun getByName(name: String): SavedCategory?

    @Query("SELECT EXISTS(SELECT 1 FROM saved_categories WHERE name = :name)")
    suspend fun existsByName(name: String): Boolean

    @Insert
    suspend fun insert(category: SavedCategory): Long

    @Update
    suspend fun update(category: SavedCategory)

    @Query("DELETE FROM saved_categories WHERE id = :id")
    suspend fun deleteById(id: Int)
}
