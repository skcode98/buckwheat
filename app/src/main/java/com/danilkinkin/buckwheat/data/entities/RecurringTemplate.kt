package com.danilkinkin.buckwheat.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "recurring_templates")
data class RecurringTemplate(
    val amount: BigDecimal,
    val comment: String,
    val dayOfMonth: Int,
    val enabled: Boolean = true,
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
)
