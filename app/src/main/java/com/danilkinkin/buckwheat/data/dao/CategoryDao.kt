package com.danilkinkin.buckwheat.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.Category
import com.danilkinkin.buckwheat.data.entities.TagCategory

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun getAll(): LiveData<List<Category>>

    @Query("SELECT * FROM categories ORDER BY name ASC")
    suspend fun getAllSuspend(): List<Category>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Insert
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tag_categories")
    fun getAllMappings(): LiveData<List<TagCategory>>

    @Query("SELECT * FROM tag_categories")
    suspend fun getAllMappingsSuspend(): List<TagCategory>

    @Query("SELECT * FROM tag_categories WHERE tagName = :tagName")
    suspend fun getMappingByTag(tagName: String): TagCategory?

    @Insert
    suspend fun insertMapping(mapping: TagCategory)

    @Query("DELETE FROM tag_categories WHERE tagName = :tagName")
    suspend fun deleteMappingByTag(tagName: String)

    @Query("DELETE FROM tag_categories WHERE categoryId = :categoryId")
    suspend fun deleteMappingsByCategory(categoryId: Long)

    @Query("SELECT tag_categories.*, categories.name AS categoryName, categories.color AS categoryColor FROM tag_categories INNER JOIN categories ON tag_categories.categoryId = categories.id")
    fun getMappingsWithCategory(): LiveData<List<TagWithCategory>>

    @Query("SELECT tag_categories.*, categories.name AS categoryName, categories.color AS categoryColor FROM tag_categories INNER JOIN categories ON tag_categories.categoryId = categories.id")
    suspend fun getMappingsWithCategorySuspend(): List<TagWithCategory>
}

data class TagWithCategory(
    val tagName: String,
    val categoryId: Long,
    val categoryName: String,
    val categoryColor: Long,
)
