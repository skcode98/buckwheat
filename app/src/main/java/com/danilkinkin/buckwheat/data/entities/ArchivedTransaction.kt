package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(
    tableName = "archived_transactions",
    foreignKeys = [
        ForeignKey(
            entity = BudgetPeriod::class,
            parentColumns = ["id"],
            childColumns = ["period_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("period_id"), Index("family_id")]
)
data class ArchivedTransaction(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    @ColumnInfo(name = "period_id") val periodId: String,
    @ColumnInfo(name = "type") val type: TransactionType,
    @ColumnInfo(name = "value") val value: BigDecimal,
    @ColumnInfo(name = "date") val date: Date,
    @ColumnInfo(name = "comment") val comment: String,
    // Predefined spend category (SpendCategory.name) assigned by the offline/AI classifier and
    // shown in analytics for archived periods. Null until categorized; display falls back to
    // offline keyword matching (SpendCategorizer.categoryFor).
    @ColumnInfo(name = "category") val category: String? = null,

    @ColumnInfo(name = "member_id")
    val memberId: String? = null,

    @ColumnInfo(name = "family_id")
    val familyId: String? = null,

    @ColumnInfo(name = "sync_seq", defaultValue = "0")
    val syncSeq: Long = 0L,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "version", defaultValue = "1")
    val version: Int = 1,
)

fun ArchivedTransaction.toTransaction(): Transaction =
    Transaction(
        id = this.id,
        type = this.type,
        value = this.value,
        date = this.date,
        comment = this.comment,
        category = this.category,
        memberId = this.memberId,
        familyId = this.familyId,
        syncSeq = this.syncSeq,
        updatedAt = this.updatedAt,
        deletedAt = this.deletedAt,
        version = this.version,
    )
