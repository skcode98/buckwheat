/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "recurring_sips")
data class RecurringSip(
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "amount")
    val amount: BigDecimal,

    @ColumnInfo(name = "note", defaultValue = "")
    val note: String = "",

    @ColumnInfo(name = "tags")
    val tags: List<String> = emptyList(),

    @ColumnInfo(name = "account")
    val account: String? = null,

    @ColumnInfo(name = "nextRun")
    val nextRun: Long,

    @ColumnInfo(name = "isActive", defaultValue = "1")
    val isActive: Boolean = true,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}
