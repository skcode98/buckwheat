package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(
    tableName = "period_limits",
    indices = [Index(value = ["period_id", "member_id"], unique = true), Index("family_id")],
)
data class PeriodLimit(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    @ColumnInfo(name = "period_id")
    val periodId: String,

    @ColumnInfo(name = "member_id")
    val memberId: String,

    @ColumnInfo(name = "family_id")
    val familyId: String? = null,

    @ColumnInfo(name = "limit_value")
    val limitValue: BigDecimal,

    @ColumnInfo(name = "sync_seq", defaultValue = "0")
    val syncSeq: Long = 0L,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "version", defaultValue = "1")
    val version: Int = 1,
)
