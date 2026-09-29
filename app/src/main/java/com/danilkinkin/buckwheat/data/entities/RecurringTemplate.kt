package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "recurring_templates")
data class RecurringTemplate(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    val amount: BigDecimal,
    val comment: String,
    @ColumnInfo(name = "day_of_month")
    val dayOfMonth: Int,
    val enabled: Boolean = true,

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
