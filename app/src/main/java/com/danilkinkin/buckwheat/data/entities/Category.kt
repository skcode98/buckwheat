package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "categories")
data class Category(
    val name: String,
    val color: Long = 0xFF6200EE,
    @ColumnInfo(defaultValue = "0") val monthlyLimit: BigDecimal = BigDecimal.ZERO,
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
)
