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
    indices = [Index("period_id")]
)
data class ArchivedTransaction(
    @ColumnInfo(name = "period_id") val periodId: Int,
    @ColumnInfo(name = "type") val type: TransactionType,
    @ColumnInfo(name = "value") val value: BigDecimal,
    @ColumnInfo(name = "date") val date: Date,
    @ColumnInfo(name = "comment") val comment: String,
) {
    @PrimaryKey(autoGenerate = true) var uid: Int = 0
}
