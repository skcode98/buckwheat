/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import com.danilkinkin.trackinvest.data.entities.CategoryDetail

@Dao
interface CategoryDetailDao {
    @Query("SELECT * FROM category_details")
    fun getAll(): LiveData<List<CategoryDetail>>

    @Query("SELECT * FROM category_details WHERE category = :category")
    suspend fun getForCategory(category: String): List<CategoryDetail>

    @Query("SELECT * FROM category_details")
    suspend fun getAllNow(): List<CategoryDetail>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg categoryDetail: CategoryDetail)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categoryDetails: List<CategoryDetail>)

    @Update(entity = CategoryDetail::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg categoryDetail: CategoryDetail)

    @Query("DELETE FROM category_details WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("DELETE FROM category_details WHERE category = :category")
    suspend fun deleteForCategory(category: String)

    @Query("DELETE FROM category_details")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg categoryDetail: CategoryDetail) {
        deleteAll()
        insert(*categoryDetail)
    }
}
