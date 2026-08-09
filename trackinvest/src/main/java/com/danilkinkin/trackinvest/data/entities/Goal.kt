/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "goals")
data class Goal(
    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "target")
    val target: BigDecimal,

    @ColumnInfo(name = "saved")
    val saved: BigDecimal = BigDecimal.ZERO,

    @ColumnInfo(name = "linkedCategory")
    val linkedCategory: String? = null,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}
