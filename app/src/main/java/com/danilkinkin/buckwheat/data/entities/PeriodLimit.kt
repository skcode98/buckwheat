package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

/**
 * One member's slice of the family pool for one period.
 *
 * There is deliberately no rollover: a limit belongs to the period named by [periodId] and a new
 * period starts from whatever the head sets. Carrying an unspent slice forward would make a
 * rollover rule an implicit, invisible default, and the explicit rule is one the head chose.
 *
 * The unique index on (periodId, memberId) is what makes an allocation edit an upsert instead of an
 * accumulating pile, and is the reason `upsertOne` keys on the id rather than on the pair.
 */
@Entity(
    tableName = "period_limits",
    indices = [Index("family_id"), Index(value = ["period_id", "member_id"], unique = true)],
)
data class PeriodLimit(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    @ColumnInfo(name = "period_id")
    val periodId: String,

    @ColumnInfo(name = "member_id")
    val memberId: String,

    @ColumnInfo(name = "limit_value")
    val limitValue: BigDecimal,

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