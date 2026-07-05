package com.danilkinkin.buckwheat.di

import androidx.room.TypeConverter
import com.danilkinkin.buckwheat.data.entities.SpendType
import java.math.BigDecimal
import java.util.*

class RoomConverters {
    @TypeConverter
    fun dateToDateStamp(input: Date): Long = input.time

    @TypeConverter
    fun dateStampToCalendar(input: Long): Date = Date(input)

    @TypeConverter
    fun bigDecimalToString(input: BigDecimal): String = input.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(input: String): BigDecimal = BigDecimal(input)

    @TypeConverter
    fun spendTypeToString(input: SpendType): String = input.name

    @TypeConverter
    fun stringToSpendType(input: String): SpendType = SpendType.valueOf(input)
}