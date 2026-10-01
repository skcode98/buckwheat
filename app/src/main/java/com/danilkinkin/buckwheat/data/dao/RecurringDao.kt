package com.danilkinkin.buckwheat.data.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_templates ORDER BY day_of_month ASC")
    fun getAll(): Flow<List<RecurringTemplate>>

    @Query("SELECT * FROM recurring_templates WHERE enabled = 1 AND day_of_month = :day")
    suspend fun getDueOnDay(day: Int): List<RecurringTemplate>

    @Query("SELECT * FROM recurring_templates")
    suspend fun getAllNow(): List<RecurringTemplate>

    @Query("SELECT * FROM recurring_templates WHERE id = :id")
    suspend fun getById(id: String): RecurringTemplate?

    /**
     * A real conflict-resolving upsert, written by hand because neither Room annotation does this job.
     *
     * `@Insert(onConflict = REPLACE)` is WRONG here for the general reason: SQLite's REPLACE deletes
     * the conflicting row and inserts a new one, which cascades wherever this table is a foreign-key
     * parent.
     *
     * `@Upsert` is ALSO WRONG, and worse: on conflict Room updates only the primary key column and
     * leaves every other column at its old value, so `family_id`, `sync_seq`, `updated_at` and
     * `version` were silently frozen. A pull that returned a changed `family_id` or `version`
     * applied nothing, and `enrolAll` stamped nothing.
     *
     * `ON CONFLICT(id) DO UPDATE SET` updates in place and assigns every non-key column from
     * `excluded`. Any column dropped from the SET list silently stops syncing — keep this list in
     * step with the entity.
     */
    @Query(
        """
        INSERT INTO `recurring_templates` (
            `id`, `amount`, `comment`, `day_of_month`, `enabled`,
            `family_id`, `sync_seq`, `updated_at`, `deleted_at`, `version`
        ) VALUES (
            :id, :amount, :comment, :dayOfMonth, :enabled,
            :familyId, :syncSeq, :updatedAt, :deletedAt, :version
        )
        ON CONFLICT(`id`) DO UPDATE SET
            `amount` = excluded.`amount`,
            `comment` = excluded.`comment`,
            `day_of_month` = excluded.`day_of_month`,
            `enabled` = excluded.`enabled`,
            `family_id` = excluded.`family_id`,
            `sync_seq` = excluded.`sync_seq`,
            `updated_at` = excluded.`updated_at`,
            `deleted_at` = excluded.`deleted_at`,
            `version` = excluded.`version`
        """
    )
    suspend fun upsertOne(
        id: String,
        amount: java.math.BigDecimal,
        comment: String,
        dayOfMonth: Int,
        enabled: Boolean,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    )

    @RoomTransaction
    suspend fun insert(template: RecurringTemplate) {
        upsertOne(
            id = template.id,
            amount = template.amount,
            comment = template.comment,
            dayOfMonth = template.dayOfMonth,
            enabled = template.enabled,
            familyId = template.familyId,
            syncSeq = template.syncSeq,
            updatedAt = template.updatedAt,
            deletedAt = template.deletedAt,
            version = template.version,
        )
    }

    @Insert
    suspend fun insertAll(templates: List<RecurringTemplate>)

    @Update
    suspend fun update(template: RecurringTemplate)

    @Delete
    suspend fun delete(template: RecurringTemplate)

    @Query("DELETE FROM recurring_templates WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM recurring_templates")
    suspend fun deleteAll()
}
