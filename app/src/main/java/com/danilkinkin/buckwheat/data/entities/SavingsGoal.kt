package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    val name: String,
    @ColumnInfo(name = "target_amount")
    val targetAmount: BigDecimal,
    @ColumnInfo(name = "current_amount")
    val currentAmount: BigDecimal = BigDecimal.ZERO,
    val deadline: Date? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),
    val completed: Boolean = false,
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
)
