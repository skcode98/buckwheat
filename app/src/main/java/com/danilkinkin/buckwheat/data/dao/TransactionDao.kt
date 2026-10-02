package com.danilkinkin.buckwheat.data.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date ASC")
    fun getAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE type = :type ORDER BY date ASC")
    fun getAll(type: TransactionType): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE type = :type AND date >= :startDate AND date <= :endDate ORDER BY date ASC")
    fun getAll(type: TransactionType, startDate: Long, endDate: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC")
    fun getAll(startDate: Long, endDate: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): Transaction?

    @Query("SELECT * FROM transactions")
    suspend fun getAllNow(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE type = :type AND date >= :startDate AND date <= :endDate ORDER BY date ASC")
    suspend fun getAllNow(type: TransactionType, startDate: Long, endDate: Long): List<Transaction>

    /**
     * Money that belongs to the household rather than to a person.
     *
     * Matched on the bucket column and not on `member_id IS NULL`, because a row created before
     * enrolment also has a null member and is not household money. Filtering on the column that
     * actually carries the meaning is the difference between a correct total and one that quietly
     * includes the other device's un-attributed history.
     */
    @Query(
        "SELECT * FROM transactions WHERE type = 'SPENT' AND bucket = 'HOUSEHOLD' AND date >= :startDate AND date <= :endDate ORDER BY date ASC"
    )
    fun getHouseholdSpends(startDate: Long, endDate: Long): Flow<List<Transaction>>

    @Query(
        "SELECT * FROM transactions WHERE type = 'SPENT' AND bucket = 'HOUSEHOLD' AND date >= :startDate AND date <= :endDate ORDER BY date ASC"
    )
    suspend fun getHouseholdSpendsNow(startDate: Long, endDate: Long): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE type = 'SPENT' AND (category IS NULL OR category = '')")
    fun getUncategorizedCount(): Flow<Int>

    /**
     * A real conflict-resolving upsert, written by hand because neither Room annotation does this job.
     *
     * `@Insert(onConflict = REPLACE)` is WRONG: SQLite's REPLACE deletes the conflicting row and
     * inserts a new one, and `archived_transactions.period_id` is ON DELETE CASCADE off
     * `budget_periods.id`, so re-applying a pulled period wiped the archived history behind it.
     *
     * `@Upsert` is ALSO WRONG, and worse: on conflict Room updates only the primary key column and
     * leaves every other column at its old value, so `family_id`, `member_id`, `sync_seq`,
     * `updated_at` and `version` were silently frozen. A server pull that returned a changed
     * `family_id` or `version` applied nothing, and `enrolAll` stamped nothing.
     *
     * `ON CONFLICT(id) DO UPDATE SET` updates the row in place (so nothing cascades) and assigns
     * every non-key column from `excluded`, which is what a pull actually needs. Any column dropped
     * from the SET list silently stops syncing — keep this list in step with the entity.
     */
    @Query(
        """
        INSERT INTO `transactions` (
            `id`, `type`, `value`, `date`, `comment`, `category`,
            `member_id`, `family_id`, `sync_seq`, `updated_at`, `deleted_at`, `version`, `bucket`, `assignment_id`, `assigned_by_member_id`
        ) VALUES (
            :id, :type, :value, :date, :comment, :category,
            :memberId, :familyId, :syncSeq, :updatedAt, :deletedAt, :version, :bucket,
            :assignmentId, :assignedByMemberId
        )
        ON CONFLICT(`id`) DO UPDATE SET
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
    suspend fun upsertOne(
        id: String,
        type: TransactionType,
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
     * Kept as the batch entry point so callers do not change. Delegates to [upsertOne] because a
     * `@Query` binds by named parameter and cannot take a whole entity; see [upsertOne] for why
     * this is not `@Upsert` or `REPLACE`.
     */
    @RoomTransaction
    suspend fun insert(vararg transaction: Transaction) {
        transaction.forEach {
            upsertOne(
                id = it.id,
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

    @Insert
    suspend fun insertAll(transactions: List<Transaction>)

    @Update(entity = Transaction::class, onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(vararg transaction: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE transactions SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: String, category: String?)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @RoomTransaction
    suspend fun deleteAllAndInsert(vararg transaction: Transaction) {
        deleteAll()
        insert(*transaction)
    }
}
