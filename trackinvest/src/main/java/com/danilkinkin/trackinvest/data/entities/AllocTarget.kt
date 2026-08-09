/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alloc_targets")
data class AllocTarget(
    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "percent")
    val percent: Double,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}
