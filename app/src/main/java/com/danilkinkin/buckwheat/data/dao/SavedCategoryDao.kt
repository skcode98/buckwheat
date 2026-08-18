package com.danilkinkin.buckwheat.data.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.SavedCategory

@Dao
interface SavedCategoryDao {
    @Query("SELECT * FROM saved_categories ORDER BY name ASC")
    fun getAll(): Flow<List<SavedCategory>>

    @Query("SELECT * FROM saved_categories WHERE id = :id")
    suspend fun getById(id: Int): SavedCategory?

    @Query("SELECT * FROM saved_categories")
    suspend fun getAllNow(): List<SavedCategory>

    @Query("SELECT * FROM saved_categories WHERE name = :name")
    suspend fun getByName(name: String): SavedCategory?

    @Query("SELECT EXISTS(SELECT 1 FROM saved_categories WHERE name = :name)")
    suspend fun existsByName(name: String): Boolean

    @Insert
    suspend fun insert(category: SavedCategory): Long

    @Insert
    suspend fun insertAll(categories: List<SavedCategory>)

    @Update
    suspend fun update(category: SavedCategory)

    @Query("DELETE FROM saved_categories WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM saved_categories")
    suspend fun deleteAll()
}
