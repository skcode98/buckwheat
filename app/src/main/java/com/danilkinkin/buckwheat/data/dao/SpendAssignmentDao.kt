package com.danilkinkin.buckwheat.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.SpendAssignment
import com.danilkinkin.buckwheat.data.entities.SpendAssignmentStatus
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.flow.Flow

@Dao
interface SpendAssignmentDao {
    @Query("SELECT * FROM spend_assignments ORDER BY date DESC, id ASC")
    fun observeAll(): Flow<List<SpendAssignment>>

    /** The requests waiting on one person, which is the only list that needs a badge. */
    @Query("SELECT * FROM spend_assignments WHERE target_member_id = :memberId AND status = 'PENDING' ORDER BY date DESC")
    fun observePendingFor(memberId: String): Flow<List<SpendAssignment>>

    @Query("SELECT * FROM spend_assignments WHERE target_member_id = :memberId AND status = 'PENDING'")
    suspend fun getPendingFor(memberId: String): List<SpendAssignment>

    @Query("SELECT * FROM spend_assignments WHERE id = :id")
    fun observeById(id: String): Flow<SpendAssignment?>

    @Query("SELECT * FROM spend_assignments WHERE id = :id")
    suspend fun getById(id: String): SpendAssignment?

    @Query("SELECT * FROM spend_assignments")
    suspend fun getAllNow(): List<SpendAssignment>

    @Query("SELECT * FROM spend_assignments WHERE status = :status")
    suspend fun getByStatus(status: String): List<SpendAssignment>

    /**
     * Hand-written for the reason every synced upsert here is: `@Insert(REPLACE)` deletes and
     * reinserts, `@Upsert` freezes `family_id`, `sync_seq`, `updated_at` and `version` so a pull that
     * changed them applies nothing. A resolution pulled from the target's other device depends on
     * `status` and `resolved_at` actually being written. A column dropped from the SET list silently
     * stops syncing.
     */
    @Query(
        """
        INSERT INTO `spend_assignments` (
            `id`, `period_id`, `target_member_id`, `created_by_member_id`, `amount`, `category`,
            `comment`, `date`, `status`, `resolved_at`, `family_id`,
            `sync_seq`, `updated_at`, `deleted_at`, `version`
        ) VALUES (
            :id, :periodId, :targetMemberId, :createdByMemberId, :amount, :category,
            :comment, :date, :status, :resolvedAt, :familyId,
            :syncSeq, :updatedAt, :deletedAt, :version
        )
        ON CONFLICT(`id`) DO UPDATE SET
            `period_id` = excluded.`period_id`,
            `target_member_id` = excluded.`target_member_id`,
            `created_by_member_id` = excluded.`created_by_member_id`,
            `amount` = excluded.`amount`,
            `category` = excluded.`category`,
            `comment` = excluded.`comment`,
            `date` = excluded.`date`,
            `status` = excluded.`status`,
            `resolved_at` = excluded.`resolved_at`,
            `family_id` = excluded.`family_id`,
            `sync_seq` = excluded.`sync_seq`,
            `updated_at` = excluded.`updated_at`,
            `deleted_at` = excluded.`deleted_at`,
            `version` = excluded.`version`
        """
    )
    suspend fun upsertOne(
        id: String,
        periodId: String,
        targetMemberId: String,
        createdByMemberId: String,
        amount: BigDecimal,
        category: String?,
        comment: String,
        date: Date,
        status: String,
        resolvedAt: Date?,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    )

    suspend fun upsert(assignment: SpendAssignment) {
        upsertOne(
            id = assignment.id,
            periodId = assignment.periodId,
            targetMemberId = assignment.targetMemberId,
            createdByMemberId = assignment.createdByMemberId,
            amount = assignment.amount,
            category = assignment.category,
            comment = assignment.comment,
            date = assignment.date,
            status = assignment.status,
            resolvedAt = assignment.resolvedAt,
            familyId = assignment.familyId,
            syncSeq = assignment.syncSeq,
            updatedAt = assignment.updatedAt,
            deletedAt = assignment.deletedAt,
            version = assignment.version,
        )
    }

    @Insert
    suspend fun insertAll(assignments: List<SpendAssignment>)

    @Update
    suspend fun update(assignment: SpendAssignment)

    @Query("DELETE FROM spend_assignments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM spend_assignments WHERE period_id = :periodId")
    suspend fun deleteForPeriod(periodId: String)

    @Query("DELETE FROM spend_assignments")
    suspend fun deleteAll()
}

/**
 * Kept next to the DAO so the literal cannot drift from the column default.
 *
 * A plain `val` and not a `const val`: an enum's `.name` is resolved at runtime, so it is not a
 * compile-time constant, and the queries above deliberately spell the literal out because a bound
 * parameter is not allowed in a `WHERE` clause that has to match the column default.
 */
val PENDING_ASSIGNMENT_STATUS: String = SpendAssignmentStatus.PENDING.name
