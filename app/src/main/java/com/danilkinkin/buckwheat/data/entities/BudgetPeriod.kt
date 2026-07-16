package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(tableName = "budget_periods")
data class BudgetPeriod(
    @ColumnInfo(name = "budget") val budget: BigDecimal,
    @ColumnInfo(name = "start_date") val startDate: Date,
    @ColumnInfo(name = "finish_date") val finishDate: Date,
    @ColumnInfo(name = "actual_finish_date") val actualFinishDate: Date?,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "total_spent") val totalSpent: BigDecimal,
) {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
}
