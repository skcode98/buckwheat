/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.di

import androidx.room.TypeConverter
import java.math.BigDecimal

class TrackInvestConverters {
    @TypeConverter
    fun bigDecimalToString(input: BigDecimal): String = input.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(input: String): BigDecimal = BigDecimal(input)

    @TypeConverter
    fun stringListToString(input: List<String>): String = input.joinToString(",")

    @TypeConverter
    fun stringToStringList(input: String): List<String> =
        input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
