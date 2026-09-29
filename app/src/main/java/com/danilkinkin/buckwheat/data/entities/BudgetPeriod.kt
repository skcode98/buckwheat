package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(tableName = "budget_periods")
data class BudgetPeriod(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    @ColumnInfo(name = "budget") val budget: BigDecimal,
    @ColumnInfo(name = "start_date") val startDate: Date,
    @ColumnInfo(name = "finish_date") val finishDate: Date,
    @ColumnInfo(name = "actual_finish_date") val actualFinishDate: Date?,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "total_spent") val totalSpent: BigDecimal,
    // True for month buckets created from out-of-period CSV imports (no budget involved).
    @ColumnInfo(name = "is_imported", defaultValue = "0") val isImported: Boolean = false,

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
