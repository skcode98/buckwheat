/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import com.danilkinkin.trackinvest.data.entities.RecurringSip
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringSipsTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun date(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun sip(nextRun: Long, note: String = "SIP", amount: String = "5000"): RecurringSip =
        RecurringSip(
            type = "SIP",
            amount = amount.toBigDecimal(),
            note = note,
            nextRun = nextRun,
        )

    private fun runDates(result: SipRunResult): List<LocalDate> =
        result.investments.map { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }

    @Test
    fun nextMonthlyRun_clampsToMonthEnd() {
        val start = date(2026, 1, 31)
        val next = nextMonthlyRun(start, zone)
        assertEquals(date(2026, 2, 28), next)
    }

    @Test
    fun nextMonthlyRun_preservesRegularDay() {
        val start = date(2026, 1, 15)
        val next = nextMonthlyRun(start, zone)
        assertEquals(date(2026, 2, 15), next)
    }

    @Test
    fun advanceMonth_preservesIntendedDayThroughShortMonth() {
        val february = advanceMonth(date(2026, 1, 31), 31, zone)
        assertEquals(date(2026, 2, 28), february)
        val march = advanceMonth(february, 31, zone)
        assertEquals(date(2026, 3, 31), march)
        val april = advanceMonth(march, 31, zone)
        assertEquals(date(2026, 4, 30), april)
    }

    @Test
    fun processDueSip_generatesAllDueOccurrences() {
        val result = processDueSip(sip(nextRun = date(2026, 1, 15)), today = date(2026, 3, 20), zone)
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 3, 15),
            ),
            runDates(result),
        )
        assertEquals(date(2026, 4, 15), result.nextRun)
    }

    @Test
    fun processDueSip_appendsAutoMarkerToNote() {
        val result = processDueSip(sip(nextRun = date(2026, 1, 15), note = "HDFC"), today = date(2026, 2, 1), zone)
        assertEquals("HDFC (Auto)", result.investments.single().note)
    }

    @Test
    fun processDueSip_keepsBlankNote() {
        val result = processDueSip(sip(nextRun = date(2026, 1, 15), note = ""), today = date(2026, 2, 1), zone)
        assertEquals("", result.investments.single().note)
    }

    @Test
    fun processDueSip_marksGeneratedInvestmentsAsMonthlyContrib() {
        val result = processDueSip(sip(nextRun = date(2026, 1, 15)), today = date(2026, 2, 1), zone)
        assertTrue(result.investments.all { it.isMonthlyContrib })
    }

    @Test
    fun processDueSip_preservesTagsAndAccount() {
        val sip = RecurringSip(
            type = "FD",
            amount = BigDecimal("10000"),
            note = "Ladder",
            tags = listOf("tax-free", "5yr"),
            account = "SBI",
            nextRun = date(2026, 1, 10),
        )
        val result = processDueSip(sip, today = date(2026, 2, 1), zone)
        val generated = result.investments.single()
        assertEquals("FD", generated.type)
        assertEquals(BigDecimal("10000"), generated.amount)
        assertEquals(listOf("tax-free", "5yr"), generated.tags)
        assertEquals("SBI", generated.account)
    }

    @Test
    fun processDueSip_noDueWhenNextRunInFuture() {
        val result = processDueSip(sip(nextRun = date(2026, 3, 15)), today = date(2026, 3, 1), zone)
        assertTrue(result.investments.isEmpty())
        assertEquals(date(2026, 3, 15), result.nextRun)
    }

    @Test
    fun processDueSip_includesOccurrenceOnToday() {
        val result = processDueSip(sip(nextRun = date(2026, 3, 15)), today = date(2026, 3, 15), zone)
        assertEquals(1, result.investments.size)
        assertEquals(date(2026, 4, 15), result.nextRun)
    }

    @Test
    fun processDueSip_capsAtMaxRuns() {
        val result = processDueSip(sip(nextRun = date(2023, 1, 15)), today = date(2026, 3, 20), zone)
        assertEquals(MAX_SIP_RUNS, result.investments.size)
    }

    @Test
    fun processDueSip_keepsIntendedDayOfMonthAcrossMonths() {
        val result = processDueSip(sip(nextRun = date(2026, 1, 31)), today = date(2026, 5, 1), zone)
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
            ),
            runDates(result),
        )
        assertEquals(date(2026, 5, 31), result.nextRun)
        assertFalse(result.investments.any { it.date == date(2026, 5, 1) })
    }
}
