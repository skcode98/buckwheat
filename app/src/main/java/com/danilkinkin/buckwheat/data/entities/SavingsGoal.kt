package com.danilkinkin.buckwheat.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    val name: String,
    val targetAmount: BigDecimal,
    val currentAmount: BigDecimal = BigDecimal.ZERO,
    val deadline: Date? = null,
    val createdAt: Date = Date(),
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
)
