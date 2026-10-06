package com.danilkinkin.buckwheat.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal
import java.util.Date

/**
 * Every write goes through the hand-written upsert below. `@Insert(REPLACE)` and `@Upsert` are
 * both wrong here: a column left out of the SET list silently stops syncing.
 */
@Dao
interface FamilyTransactionDao {

    @Query("SELECT * FROM `family_transactions` WHERE `date` BETWEEN :startDate AND :endDate")
    fun getAllInPeriod(startDate: Date, endDate: Date): List<FamilyTransaction>

    @Query("SELECT * FROM `family_transactions`")
    fun getAllNow(): List<FamilyTransaction>

    @Query("SELECT * FROM `family_transactions` WHERE id = :id")
    fun getById(id: String): FamilyTransaction?

    @Query(
        """
        INSERT INTO `family_transactions` (
            `id`, `type`, `value`, `date`, `comment`, `category`, `member_id`,
            `sync_seq`, `updated_at`, `deleted_at`, `version`
        ) VALUES (
            :id, :type, :value, :date, :comment, :category, :memberId,
            :syncSeq, :updatedAt, :deletedAt, :version
        )
        ON CONFLICT(`id`) DO UPDATE SET
            `type` = excluded.`type`,
            `value` = excluded.`value`,
            `date` = excluded.`date`,
            `comment` = excluded.`comment`,
            `category` = excluded.`category`,
            `member_id` = excluded.`member_id`,
            `sync_seq` = excluded.`sync_seq`,
            `updated_at` = excluded.`updated_at`,
            `deleted_at` = excluded.`deleted_at`,
            `version` = excluded.`version`
        """,
    )
    suspend fun upsertOne(
        id: String,
        type: TransactionType,
        value: BigDecimal,
        date: Date,
        comment: String,
        category: String?,
        memberId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    )

    @RoomTransaction
    suspend fun insert(vararg transaction: FamilyTransaction) {
        transaction.forEach { upsertOne(it.id, it.type, it.value, it.date, it.comment, it.category, it.memberId, it.syncSeq, it.updatedAt, it.deletedAt, it.version) }
    }

    @Query("DELETE FROM `family_transactions` WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE `family_transactions` SET `category` = :category WHERE id = :id")
    suspend fun updateCategory(id: String, category: String?)

    @Query("DELETE FROM `family_transactions`")
    suspend fun deleteAll()

    @Query("UPDATE `family_transactions` SET `member_id` = :memberId WHERE id = :id")
    suspend fun updateMemberId(id: String, memberId: String)

    @Query("DELETE FROM `family_transactions` WHERE `member_id` IS NOT NULL AND `member_id` != :memberId")
    suspend fun deleteRowsWhereMemberDiffersFrom(memberId: String): Int

    @Query("UPDATE `family_transactions` SET `member_id` = :memberId WHERE `member_id` IS NULL")
    suspend fun attributeNullMembersTo(memberId: String): Int
}
