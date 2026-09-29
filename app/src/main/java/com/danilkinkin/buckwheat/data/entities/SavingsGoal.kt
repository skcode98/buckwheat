package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    val name: String,
    @ColumnInfo(name = "target_amount")
    val targetAmount: BigDecimal,
    @ColumnInfo(name = "current_amount")
    val currentAmount: BigDecimal = BigDecimal.ZERO,
    val deadline: Date? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),
    val completed: Boolean = false,

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
