/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import com.danilkinkin.trackinvest.data.entities.Investment
import com.danilkinkin.trackinvest.data.entities.RecurringSip
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min

const val MAX_SIP_RUNS = 24

data class SipRunResult(
    val investments: List<Investment>,
    val nextRun: Long,
)

fun nextMonthlyRun(start: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long {
    val startDate = Instant.ofEpochMilli(start).atZone(zoneId).toLocalDate()
    return advanceMonth(start, startDate.dayOfMonth, zoneId)
}

fun advanceMonth(current: Long, intendedDay: Int, zoneId: ZoneId = ZoneId.systemDefault()): Long {
    val nextMonth = Instant.ofEpochMilli(current).atZone(zoneId).toLocalDate().plusMonths(1)
    val day = min(intendedDay, nextMonth.lengthOfMonth())
    return nextMonth.withDayOfMonth(day).atStartOfDay(zoneId).toInstant().toEpochMilli()
}

fun processDueSip(sip: RecurringSip, today: Long, zoneId: ZoneId = ZoneId.systemDefault()): SipRunResult {
    val todayDate = Instant.ofEpochMilli(today).atZone(zoneId).toLocalDate()
    val intendedDay = Instant.ofEpochMilli(sip.nextRun).atZone(zoneId).toLocalDate().dayOfMonth
    val investments = mutableListOf<Investment>()
    var nextRun = sip.nextRun
    var safety = 0
    while (safety < MAX_SIP_RUNS) {
        val nextDate = Instant.ofEpochMilli(nextRun).atZone(zoneId).toLocalDate()
        if (nextDate.isAfter(todayDate)) break
        investments += Investment(
            date = nextRun,
            type = sip.type,
            amount = sip.amount,
            note = if (sip.note.isBlank()) "" else "${sip.note} (Auto)",
            tags = sip.tags,
            account = sip.account,
            isMonthlyContrib = true,
        )
        nextRun = advanceMonth(nextRun, intendedDay, zoneId)
        safety++
    }
    return SipRunResult(investments, nextRun)
}
