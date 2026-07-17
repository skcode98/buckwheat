package com.danilkinkin.buckwheat.data.dao

import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.Storage

@Dao
interface StorageDao {
    @Query("SELECT * FROM storage WHERE name = :name")
    suspend fun get(name: String): Storage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(vararg storage: Storage)

    @Delete
    suspend fun delete(storage: Storage)

    @Query("DELETE FROM storage")
    suspend fun deleteAll()
}