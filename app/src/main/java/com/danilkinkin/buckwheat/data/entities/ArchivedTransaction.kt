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
    // Predefined spend category (SpendCategory.name) assigned by the offline/AI classifier and
    // shown in analytics for archived periods. Null until categorized; display falls back to
    // offline keyword matching (SpendCategorizer.categoryFor).
    @ColumnInfo(name = "category") val category: String? = null,
) {
    @PrimaryKey(autoGenerate = true) var uid: Int = 0
}

fun ArchivedTransaction.toTransaction(): Transaction =
    Transaction(
        type = this.type,
        value = this.value,
        date = this.date,
        comment = this.comment,
        category = this.category,
    ).also { it.uid = this.uid }
