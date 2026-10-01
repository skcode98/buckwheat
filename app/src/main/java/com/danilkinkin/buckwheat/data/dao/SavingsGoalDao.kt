package com.danilkinkin.buckwheat.data.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.SavingsGoal

@Dao
interface SavingsGoalDao {
    @Query("SELECT * FROM savings_goals ORDER BY created_at DESC")
    fun getAll(): Flow<List<SavingsGoal>>

    @Query("SELECT * FROM savings_goals WHERE id = :id")
    suspend fun getById(id: String): SavingsGoal?

    @Query("SELECT * FROM savings_goals")
    suspend fun getAllNow(): List<SavingsGoal>

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
        INSERT INTO `savings_goals` (
            `id`, `name`, `target_amount`, `current_amount`, `deadline`, `created_at`, `completed`,
            `family_id`, `sync_seq`, `updated_at`, `deleted_at`, `version`
        ) VALUES (
            :id, :name, :targetAmount, :currentAmount, :deadline, :createdAt, :completed,
            :familyId, :syncSeq, :updatedAt, :deletedAt, :version
        )
        ON CONFLICT(`id`) DO UPDATE SET
            `name` = excluded.`name`,
            `target_amount` = excluded.`target_amount`,
            `current_amount` = excluded.`current_amount`,
            `deadline` = excluded.`deadline`,
            `created_at` = excluded.`created_at`,
            `completed` = excluded.`completed`,
            `family_id` = excluded.`family_id`,
            `sync_seq` = excluded.`sync_seq`,
            `updated_at` = excluded.`updated_at`,
            `deleted_at` = excluded.`deleted_at`,
            `version` = excluded.`version`
        """
    )
    suspend fun upsertOne(
        id: String,
        name: String,
        targetAmount: java.math.BigDecimal,
        currentAmount: java.math.BigDecimal,
        deadline: java.util.Date?,
        createdAt: java.util.Date,
        completed: Boolean,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    )

    @RoomTransaction
    suspend fun insert(goal: SavingsGoal) {
        upsertOne(
            id = goal.id,
            name = goal.name,
            targetAmount = goal.targetAmount,
            currentAmount = goal.currentAmount,
            deadline = goal.deadline,
            createdAt = goal.createdAt,
            completed = goal.completed,
            familyId = goal.familyId,
            syncSeq = goal.syncSeq,
            updatedAt = goal.updatedAt,
            deletedAt = goal.deletedAt,
            version = goal.version,
        )
    }

    @Insert
    suspend fun insertAll(goals: List<SavingsGoal>)

    @Update
    suspend fun update(goal: SavingsGoal)

    @Delete
    suspend fun delete(goal: SavingsGoal)

    @Query("DELETE FROM savings_goals WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM savings_goals")
    suspend fun deleteAll()
}
