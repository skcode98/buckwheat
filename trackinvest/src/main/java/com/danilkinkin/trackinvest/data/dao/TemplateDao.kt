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
import com.danilkinkin.trackinvest.data.entities.Template

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates")
    fun getAll(): LiveData<List<Template>>

    @Query("SELECT * FROM templates")
    suspend fun getAllNow(): List<Template>

    @Query("SELECT * FROM templates WHERE uid = :uid")
    suspend fun getById(uid: Int): Template?

    @Insert
    suspend fun insert(vararg template: Template)

    @Insert
    suspend fun insertAll(templates: List<Template>)

    @Update(entity = Template::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg template: Template)

    @Query("DELETE FROM templates WHERE uid = :uid")
    suspend fun deleteById(uid: Int)

    @Query("DELETE FROM templates")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg template: Template) {
        deleteAll()
        insert(*template)
    }
}
