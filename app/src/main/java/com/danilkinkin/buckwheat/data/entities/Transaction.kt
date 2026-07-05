package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.*

enum class TransactionType {
    SET_DAILY_BUDGET,
    INCOME,
    SPENT
}

enum class SpendType {
    NEEDS,
    WANTS
}

@Entity(tableName = "transactions")
data class Transaction(
    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "value")
    val value: BigDecimal,

    @ColumnInfo(name = "date")
    val date: Date,

    @ColumnInfo(name = "comment", defaultValue = "")
    val comment: String = "",

    @ColumnInfo(name = "spend_type", defaultValue = "WANTS")
    val spendType: SpendType = SpendType.WANTS,

    @ColumnInfo(name = "category_id")
    val categoryId: Long? = null,
) {
    @PrimaryKey(autoGenerate = true) var uid: Int = 0
}