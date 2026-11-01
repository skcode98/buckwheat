package com.danilkinkin.buckwheat.data

import com.danilkinkin.buckwheat.util.DAY
import com.danilkinkin.buckwheat.util.roundToDay
import java.util.Date

internal fun normalizePeriodStart(start: Date, finish: Date, today: Date): Date {
    val finishDay = roundToDay(finish).time
    val bounded = minOf(roundToDay(start).time, roundToDay(today).time)
    return Date(if (bounded < finishDay) bounded else finishDay - DAY)
}
