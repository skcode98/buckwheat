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
import com.danilkinkin.trackinvest.data.entities.Category

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories")
    fun getAll(): LiveData<List<Category>>

    @Query("SELECT * FROM categories")
    suspend fun getAllNow(): List<Category>

    @Query("SELECT * FROM categories WHERE name = :name")
    suspend fun getByName(name: String): Category?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg category: Category)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<Category>)

    @Update(entity = Category::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg category: Category)

    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg category: Category) {
        deleteAll()
        insert(*category)
    }
}
