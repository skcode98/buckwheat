package com.danilkinkin.buckwheat.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.danilkinkin.buckwheat.data.entities.PendingMutation

@Dao
interface PendingMutationDao {

    @Query("SELECT * FROM `pending_mutations` ORDER BY `queued_at` ASC")
    suspend fun getAllNow(): List<PendingMutation>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(mutation: PendingMutation)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueAll(mutations: List<PendingMutation>)

    @Query("DELETE FROM `pending_mutations` WHERE `table_name` = :table AND `record_id` IN (:recordIds)")
    suspend fun deleteQueued(table: String, recordIds: List<String>)

    @Query("SELECT COUNT(*) FROM `pending_mutations` WHERE `table_name` = :table AND `record_id` = :recordId")
    suspend fun isQueued(table: String, recordId: String): Int

    @Query("UPDATE `pending_mutations` SET `is_delete` = :isDelete, `queued_at` = :queuedAt WHERE `table_name` = :table AND `record_id` = :recordId")
    suspend fun mark(table: String, recordId: String, queuedAt: Long, isDelete: Boolean): Int

    @Query("SELECT COUNT(*) FROM `pending_mutations`")
    suspend fun count(): Int

    @Query("DELETE FROM `pending_mutations`")
    suspend fun deleteAll()
}
