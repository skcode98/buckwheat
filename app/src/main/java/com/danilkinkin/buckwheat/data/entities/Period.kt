package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(tableName = "periods")
data class Period(
    @ColumnInfo(name = "start_date")
    val startDate: Date,

    @ColumnInfo(name = "finish_date")
    val finishDate: Date,

    @ColumnInfo(name = "actual_finish_date")
    val actualFinishDate: Date? = null,

    @ColumnInfo(name = "budget")
    val budget: BigDecimal,

    @ColumnInfo(name = "note", defaultValue = "")
    val note: String = "",
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
}
