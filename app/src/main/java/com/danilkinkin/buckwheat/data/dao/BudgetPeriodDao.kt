package com.danilkinkin.buckwheat.data.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Transaction as RoomTransaction
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import java.math.BigDecimal
import java.util.Date

@Dao
interface BudgetPeriodDao {
    @Query("SELECT * FROM budget_periods ORDER BY start_date DESC")
    fun getAll(): Flow<List<BudgetPeriod>>

    @Query("SELECT * FROM budget_periods WHERE id = :id")
    suspend fun getById(id: String): BudgetPeriod?

    @Query("SELECT * FROM budget_periods")
    suspend fun getAllNow(): List<BudgetPeriod>

    /**
     * A real conflict-resolving upsert, written by hand because neither Room annotation does this job.
     *
     * `@Insert(onConflict = REPLACE)` is WRONG: SQLite's REPLACE deletes the conflicting row and
     * inserts a new one, and `archived_transactions.period_id` is ON DELETE CASCADE off this table,
     * so re-applying a budget period destroyed every archived transaction belonging to it.
     *
     * `@Upsert` is ALSO WRONG, and worse: on conflict Room updates only the primary key column and
     * leaves every other column at its old value, so `family_id`, `sync_seq`, `updated_at` and
     * `version` were silently frozen. A pull that returned a changed `family_id` or `version`
     * applied nothing, and `enrolAll` stamped nothing.
     *
     * `ON CONFLICT(id) DO UPDATE SET` updates the row in place (so the cascade never fires) and
     * assigns every non-key column from `excluded`. Any column dropped from the SET list silently
     * stops syncing — keep this list in step with the entity.
     */
    @Query(
        """
        INSERT INTO `budget_periods` (
            `id`, `budget`, `start_date`, `finish_date`, `actual_finish_date`, `currency_code`,
            `total_spent`, `is_imported`, `family_id`, `sync_seq`, `updated_at`, `deleted_at`, `version`
        ) VALUES (
            :id, :budget, :startDate, :finishDate, :actualFinishDate, :currencyCode,
            :totalSpent, :isImported, :familyId, :syncSeq, :updatedAt, :deletedAt, :version
        )
        ON CONFLICT(`id`) DO UPDATE SET
            `budget` = excluded.`budget`,
            `start_date` = excluded.`start_date`,
            `finish_date` = excluded.`finish_date`,
            `actual_finish_date` = excluded.`actual_finish_date`,
            `currency_code` = excluded.`currency_code`,
            `total_spent` = excluded.`total_spent`,
            `is_imported` = excluded.`is_imported`,
            `family_id` = excluded.`family_id`,
            `sync_seq` = excluded.`sync_seq`,
            `updated_at` = excluded.`updated_at`,
            `deleted_at` = excluded.`deleted_at`,
            `version` = excluded.`version`
        """
    )
    suspend fun upsertPeriod(
        id: String,
        budget: java.math.BigDecimal,
        startDate: java.util.Date,
        finishDate: java.util.Date,
        actualFinishDate: java.util.Date?,
        currencyCode: String,
        totalSpent: java.math.BigDecimal,
        isImported: Boolean,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    )

    @RoomTransaction
    suspend fun insert(period: BudgetPeriod) {
        upsertPeriod(
            id = period.id,
            budget = period.budget,
            startDate = period.startDate,
            finishDate = period.finishDate,
            actualFinishDate = period.actualFinishDate,
            currencyCode = period.currencyCode,
            totalSpent = period.totalSpent,
            isImported = period.isImported,
            familyId = period.familyId,
            syncSeq = period.syncSeq,
            updatedAt = period.updatedAt,
            deletedAt = period.deletedAt,
            version = period.version,
        )
    }

    @Insert
    suspend fun insertAll(periods: List<BudgetPeriod>)

    @Query("DELETE FROM budget_periods")
    suspend fun deleteAll()

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId ORDER BY date ASC")
    fun getTransactionsForPeriod(periodId: String): Flow<List<ArchivedTransaction>>

    @Query("SELECT * FROM archived_transactions WHERE period_id = :periodId AND type = 'SPENT' ORDER BY date ASC")
    fun getSpendsForPeriod(periodId: String): Flow<List<ArchivedTransaction>>

    @Query("SELECT * FROM archived_transactions")
    suspend fun getAllArchivedNow(): List<ArchivedTransaction>

    @Query("SELECT * FROM archived_transactions WHERE id = :id")
    suspend fun getArchivedById(id: String): ArchivedTransaction?

    @Query("SELECT COUNT(*) FROM archived_transactions WHERE type = 'SPENT' AND (category IS NULL OR category = '')")
    fun getArchivedUncategorizedCount(): Flow<Int>

    @Query("SELECT * FROM archived_transactions ORDER BY date DESC")
    fun getAllArchived(): Flow<List<ArchivedTransaction>>

    @Query("UPDATE budget_periods SET total_spent = :totalSpent WHERE id = :periodId")
    suspend fun updateTotalSpent(periodId: String, totalSpent: BigDecimal)

    @Query("UPDATE budget_periods SET budget = :budget WHERE id = :id")
    suspend fun updateBudget(id: String, budget: BigDecimal)

    @Query("UPDATE budget_periods SET start_date = :startDate, finish_date = :finishDate WHERE id = :id")
    suspend fun updateDates(id: String, startDate: Date, finishDate: Date)

    @Query("DELETE FROM budget_periods WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE archived_transactions SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: String, category: String?)

    /**
     * The per-row upsert behind [insertArchivedTransactions]. Same reasoning as the `budget_periods`
     * upsert above, and it matters twice over here: `REPLACE` on the parent period cascades this
     * table away, and `@Upsert` would freeze `family_id`/`member_id`/`sync_seq`/`updated_at`/`version`
     * on every row a pull tried to update. See [insert] for the full explanation.
     */
    @Query(
        """
        INSERT INTO `archived_transactions` (
            `id`, `period_id`, `type`, `value`, `date`, `comment`, `category`,
            `member_id`, `family_id`, `sync_seq`, `updated_at`, `deleted_at`, `version`, `bucket`, `assignment_id`, `assigned_by_member_id`
        ) VALUES (
            :id, :periodId, :type, :value, :date, :comment, :category,
            :memberId, :familyId, :syncSeq, :updatedAt, :deletedAt, :version, :bucket,
            :assignmentId, :assignedByMemberId
        )
        ON CONFLICT(`id`) DO UPDATE SET
            `period_id` = excluded.`period_id`,
            `type` = excluded.`type`,
            `value` = excluded.`value`,
            `date` = excluded.`date`,
            `comment` = excluded.`comment`,
            `category` = excluded.`category`,
            `member_id` = excluded.`member_id`,
            `family_id` = excluded.`family_id`,
            `sync_seq` = excluded.`sync_seq`,
            `updated_at` = excluded.`updated_at`,
            `deleted_at` = excluded.`deleted_at`,
            `version` = excluded.`version`,
            `bucket` = excluded.`bucket`,
            `assignment_id` = excluded.`assignment_id`,
            `assigned_by_member_id` = excluded.`assigned_by_member_id`
        """
    )
    suspend fun upsertArchivedTransaction(
        id: String,
        periodId: String,
        type: com.danilkinkin.buckwheat.data.entities.TransactionType,
        value: java.math.BigDecimal,
        date: java.util.Date,
        comment: String,
        category: String?,
        memberId: String?,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
        bucket: String,
        assignmentId: String?,
        assignedByMemberId: String?,
    )

    /**
     * Kept as the batch entry point so callers do not change. Delegates to [upsertArchivedTransaction]
     * because a `@Query` binds by named parameter and cannot take a whole entity; see
     * [upsertArchivedTransaction] for why this is neither `@Upsert` nor `REPLACE`.
     */
    @RoomTransaction
    suspend fun insertArchivedTransactions(transactions: List<ArchivedTransaction>) {
        transactions.forEach {
            upsertArchivedTransaction(
                id = it.id,
                periodId = it.periodId,
                type = it.type,
                value = it.value,
                date = it.date,
                comment = it.comment,
                category = it.category,
                memberId = it.memberId,
                familyId = it.familyId,
                syncSeq = it.syncSeq,
                updatedAt = it.updatedAt,
                deletedAt = it.deletedAt,
                version = it.version,
                bucket = it.bucket,
                assignmentId = it.assignmentId,
                assignedByMemberId = it.assignedByMemberId,
            )
        }
    }

    @Query("DELETE FROM archived_transactions WHERE id = :id")
    suspend fun deleteArchivedById(id: String)
}
