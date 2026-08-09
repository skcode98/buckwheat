/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "investments")
data class Investment(
    @ColumnInfo(name = "date")
    val date: Long,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "amount")
    val amount: BigDecimal,

    @ColumnInfo(name = "note", defaultValue = "")
    val note: String = "",

    @ColumnInfo(name = "tags")
    val tags: List<String> = emptyList(),

    @ColumnInfo(name = "subCategory")
    val subCategory: String? = null,

    @ColumnInfo(name = "broker")
    val broker: String? = null,

    @ColumnInfo(name = "growthRate")
    val growthRate: Double? = null,

    @ColumnInfo(name = "account")
    val account: String? = null,

    @ColumnInfo(name = "isMonthlyContrib", defaultValue = "0")
    val isMonthlyContrib: Boolean = false,

    @ColumnInfo(name = "payoutType")
    val payoutType: String? = null,

    @ColumnInfo(name = "investMode")
    val investMode: String? = null,

    @ColumnInfo(name = "sipDay")
    val sipDay: Int? = null,

    @ColumnInfo(name = "maturityDate")
    val maturityDate: Long? = null,

    @ColumnInfo(name = "interestRate")
    val interestRate: Double? = null,

    @ColumnInfo(name = "units")
    val units: Double? = null,

    @ColumnInfo(name = "mfCode")
    val mfCode: String? = null,

    @ColumnInfo(name = "isClosed", defaultValue = "0")
    val isClosed: Boolean = false,

    @ColumnInfo(name = "closedDate")
    val closedDate: Long? = null,

    @ColumnInfo(name = "closedReason")
    val closedReason: String? = null,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}
