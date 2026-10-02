package com.danilkinkin.buckwheat.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.PeriodLimit
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

@Dao
interface PeriodLimitDao {
    @Query("SELECT * FROM period_limits WHERE period_id = :periodId ORDER BY member_id")
    fun observeForPeriod(periodId: String): Flow<List<PeriodLimit>>

    @Query("SELECT * FROM period_limits WHERE period_id = :periodId")
    suspend fun getForPeriod(periodId: String): List<PeriodLimit>

    @Query("SELECT * FROM period_limits WHERE period_id = :periodId AND member_id = :memberId")
    fun observeForMember(periodId: String, memberId: String): Flow<PeriodLimit?>

    @Query("SELECT * FROM period_limits WHERE id = :id")
    suspend fun getById(id: String): PeriodLimit?

    @Query("SELECT * FROM period_limits")
    suspend fun getAllNow(): List<PeriodLimit>

    /**
     * Hand-written for the same reason as every other synced upsert here: `@Insert(REPLACE)` cascades
     * and `@Upsert` freezes the sync columns. A column dropped from the SET list silently stops
     * syncing.
     */
    @Query(
        """
        INSERT INTO `period_limits` (
            `id`, `period_id`, `member_id`, `limit_value`, `family_id`,
            `sync_seq`, `updated_at`, `deleted_at`, `version`
        ) VALUES (
            :id, :periodId, :memberId, :limitValue, :familyId,
            :syncSeq, :updatedAt, :deletedAt, :version
        )
        ON CONFLICT(`id`) DO UPDATE SET
            `period_id` = excluded.`period_id`,
            `member_id` = excluded.`member_id`,
            `limit_value` = excluded.`limit_value`,
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
        memberId: String,
        limitValue: BigDecimal,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    )

    suspend fun upsert(limit: PeriodLimit) {
        upsertOne(
            id = limit.id,
            periodId = limit.periodId,
            memberId = limit.memberId,
            limitValue = limit.limitValue,
            familyId = limit.familyId,
            syncSeq = limit.syncSeq,
            updatedAt = limit.updatedAt,
            deletedAt = limit.deletedAt,
            version = limit.version,
        )
    }

    /**
     * Replaces a member's slice for a period in one statement, so re-allocating can never briefly
     * show two rows for the same member or, worse, none.
     */
    @Query("DELETE FROM period_limits WHERE period_id = :periodId AND member_id = :memberId")
    suspend fun deleteForMember(periodId: String, memberId: String)

    @Insert
    suspend fun insertAll(limits: List<PeriodLimit>)

    @Update
    suspend fun update(limit: PeriodLimit)

    @Query("DELETE FROM period_limits WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM period_limits WHERE period_id = :periodId")
    suspend fun deleteForPeriod(periodId: String)

    @Query("DELETE FROM period_limits")
    suspend fun deleteAll()
}