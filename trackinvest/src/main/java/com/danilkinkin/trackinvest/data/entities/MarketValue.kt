/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "market_values")
data class MarketValue(
    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "value")
    val value: BigDecimal,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}
