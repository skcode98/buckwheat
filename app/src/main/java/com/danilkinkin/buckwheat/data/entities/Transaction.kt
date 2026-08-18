package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.*

enum class TransactionType {
    SET_DAILY_BUDGET,
    INCOME,
    SPENT
}

@Entity(
    tableName = "transactions",
    indices = [Index("type", "date")]
)
data class Transaction(
    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "value")
    val value: BigDecimal,

    @ColumnInfo(name = "date")
    val date: Date,

    @ColumnInfo(name = "comment", defaultValue = "")
    val comment: String = "",

    // Predefined spend category (SpendCategory.name) assigned by the AI classifier and shown
    // in analytics only. Null until the classifier runs; display falls back to offline
    // keyword matching (SpendCategorizer.categoryFor).
    @ColumnInfo(name = "category")
    val category: String? = null,
) {
    @PrimaryKey(autoGenerate = true) var uid: Int = 0
}