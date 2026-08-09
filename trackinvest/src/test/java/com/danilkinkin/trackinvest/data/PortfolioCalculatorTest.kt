/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import com.danilkinkin.trackinvest.data.entities.Category
import com.danilkinkin.trackinvest.data.entities.CategoryDetail
import com.danilkinkin.trackinvest.data.entities.Investment
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioCalculatorTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun date(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun inv(
        date: Long,
        type: String,
        amount: String,
        interestRate: Double? = null,
        payoutType: String? = null,
        maturityDate: Long? = null,
    ): Investment = Investment(
        date = date,
        type = type,
        amount = amount.toBigDecimal(),
        interestRate = interestRate,
        payoutType = payoutType,
        maturityDate = maturityDate,
    )

    private fun assertMoney(expected: Double, actual: BigDecimal, delta: Double = 1.0) {
        assertEquals(expected, actual.toDouble(), delta)
    }

    @Test
    fun fdValuation_quarterlyPayoutCompounds() {
        val value = strictValuation(
            type = "FD",
            totalInvested = BigDecimal("10000"),
            rawInvs = listOf(inv(date(2022, 1, 1), "FD", "10000", interestRate = 8.0)),
            categoryDetails = emptyList(),
            today = LocalDate.of(2026, 1, 1),
            zoneId = zone,
        )
        assertMoney(13727.86, value.total, 0.01)
        assertMoney(3727.86, value.interest, 0.01)
    }

    @Test
    fun fdValuation_simplePayoutUsesSimpleInterest() {
        val value = strictValuation(
            type = "FD",
            totalInvested = BigDecimal("10000"),
            rawInvs = listOf(inv(date(2022, 1, 1), "FD", "10000", interestRate = 8.0, payoutType = "annual")),
            categoryDetails = emptyList(),
            today = LocalDate.of(2026, 1, 1),
            zoneId = zone,
        )
        assertMoney(13200.00, value.total, 0.01)
        assertMoney(3200.00, value.interest, 0.01)
    }

    @Test
    fun fdValuation_monthlyPayoutCompounds() {
        val value = strictValuation(
            type = "FD",
            totalInvested = BigDecimal("10000"),
            rawInvs = listOf(inv(date(2022, 1, 1), "FD", "10000", interestRate = 8.0, payoutType = "monthly")),
            categoryDetails = emptyList(),
            today = LocalDate.of(2026, 1, 1),
            zoneId = zone,
        )
        assertMoney(13756.0, value.total, 1.0)
    }

    @Test
    fun ppfValuation_usesGovernmentRate() {
        val value = strictValuation(
            type = "PPF",
            totalInvested = BigDecimal("10000"),
            rawInvs = listOf(inv(date(2022, 1, 1), "PPF", "10000")),
            categoryDetails = emptyList(),
            today = LocalDate.of(2026, 1, 1),
            zoneId = zone,
        )
        assertMoney(13273.0, value.total, 1.0)
        assertTrue(value.interest > BigDecimal.ZERO)
    }

    @Test
    fun pfValuation_usesGovernmentRate() {
        val value = strictValuation(
            type = "PF",
            totalInvested = BigDecimal("10000"),
            rawInvs = listOf(inv(date(2022, 1, 1), "PF", "10000")),
            categoryDetails = emptyList(),
            today = LocalDate.of(2026, 1, 1),
            zoneId = zone,
        )
        assertMoney(13840.0, value.total, 1.0)
    }

    @Test
    fun customRateValuation_compoundsMonthly() {
        val detail = CategoryDetail(category = "Liquid", key = "interestRate", value = "12")
        val value = strictValuation(
            type = "Liquid",
            totalInvested = BigDecimal("10000"),
            rawInvs = listOf(inv(date(2022, 1, 1), "Liquid", "10000")),
            categoryDetails = listOf(detail),
            today = LocalDate.of(2026, 1, 1),
            zoneId = zone,
        )
        assertMoney(16122.0, value.total, 1.0)
    }

    @Test
    fun customRateValuation_fallsBackToInvestedWhenNoRate() {
        val value = strictValuation(
            type = "Home",
            totalInvested = BigDecimal("10000"),
            rawInvs = listOf(inv(date(2022, 1, 1), "Home", "10000")),
            categoryDetails = emptyList(),
            today = LocalDate.of(2026, 1, 1),
            zoneId = zone,
        )
        assertEquals(BigDecimal("10000"), value.total)
        assertEquals(BigDecimal.ZERO, value.interest)
    }

    @Test
    fun sipAndStocks_fallBackToInvestedUntilNavWiredUp() {
        listOf("SIP", "Stocks").forEach { type ->
            val value = strictValuation(
                type = type,
                totalInvested = BigDecimal("25000"),
                rawInvs = listOf(inv(date(2024, 3, 1), type, "10000"), inv(date(2025, 6, 1), type, "15000")),
                categoryDetails = emptyList(),
                today = LocalDate.of(2026, 1, 1),
                zoneId = zone,
            )
            assertEquals(BigDecimal("25000"), value.total)
            assertEquals(BigDecimal.ZERO, value.interest)
        }
    }

    @Test
    fun initialBalance_accruesInterestFromBeforeEarliestInvestment() {
        val detail = CategoryDetail(category = "PPF", key = "initialBal", value = "5000")
        val categories = listOf(Category(name = "PPF", is80c = true))
        val summary = computePortfolioSummary(
            investments = listOf(inv(date(2022, 1, 1), "PPF", "10000")),
            categories = categories,
            categoryDetails = listOf(detail),
            today = LocalDate.of(2026, 1, 1),
            fyStartMonth = 3,
            zoneId = zone,
        )
        val ppf = summary.typeTotals.single()
        assertEquals(BigDecimal("15000"), ppf.invested)
        assertTrue(ppf.total > ppf.invested)
        assertEquals(BigDecimal("10000"), summary.tax80c)
    }

    @Test
    fun summary_aggregatesTotalsAndWindows() {
        val categories = listOf(
            Category(name = "FD", is80c = false),
            Category(name = "PPF", is80c = true),
            Category(name = "SIP", is80c = false),
        )
        val investments = listOf(
            inv(
                date = date(2026, 1, 5),
                type = "SIP",
                amount = "5000",
                maturityDate = date(2028, 1, 5),
            ),
            inv(date = date(2025, 12, 5), type = "SIP", amount = "4000"),
            inv(
                date = date(2025, 12, 10),
                type = "PPF",
                amount = "20000",
                maturityDate = date(2027, 12, 10),
            ),
            inv(
                date = date(2022, 1, 1),
                type = "FD",
                amount = "10000",
                maturityDate = date(2026, 3, 1),
            ),
        )

        val summary = computePortfolioSummary(
            investments = investments,
            categories = categories,
            categoryDetails = emptyList(),
            today = LocalDate.of(2026, 1, 15),
            fyStartMonth = 3,
            zoneId = zone,
        )

        assertEquals(BigDecimal("39000"), summary.totalInvested)
        assertEquals(BigDecimal("5000"), summary.thisMonthInvested)
        assertEquals(BigDecimal("4000"), summary.lastMonthInvested)
        assertEquals(BigDecimal("5000"), summary.yearInvested)
        assertEquals(BigDecimal("5000.00"), summary.avgMonthly)
        assertEquals(BigDecimal("20000"), summary.tax80c)
        assertTrue(summary.netWorth >= summary.totalInvested)

        val fd = summary.typeTotals.single { it.type == "FD" }
        assertEquals(BigDecimal("10000"), fd.total)
        assertEquals(BigDecimal.ZERO, fd.interest)

        val ppf = summary.typeTotals.single { it.type == "PPF" }
        assertTrue(ppf.total > ppf.invested)

        assertEquals(1, summary.maturities.size)
        assertEquals(45L, summary.maturities.single().daysLeft)

        assertEquals(24, summary.netWorthHistory.size)
        assertEquals(24, summary.monthlyHistory.size)
        assertEquals(summary.netWorth, summary.netWorthHistory.last().value)
        assertEquals("2026-01-05", formatInvestmentDate(summary.recentActivity.first().date))
    }

    @Test
    fun monthlyInvestedPoints_sumsPerMonthNewestLast() {
        val investments = listOf(
            inv(date = date(2026, 1, 5), type = "SIP", amount = "5000"),
            inv(date = date(2025, 12, 5), type = "SIP", amount = "4000"),
            inv(date = date(2025, 12, 10), type = "PPF", amount = "20000"),
        )
        val points = monthlyInvestedPoints(
            today = LocalDate.of(2026, 1, 15),
            rangeMonths = 3,
            investments = investments,
            zoneId = zone,
        )
        assertEquals(
            listOf(BigDecimal.ZERO, BigDecimal("24000"), BigDecimal("5000")),
            points.map { it.value },
        )
        assertEquals(listOf("Nov 2025", "Dec 2025", "Jan 2026"), points.map { it.label })
    }

    @Test
    fun netWorthPoints_reconstructsHistoryFromCurrentNetWorth() {
        val monthly = listOf(
            ChartPoint("Nov 2025", BigDecimal.ZERO),
            ChartPoint("Dec 2025", BigDecimal("24000")),
            ChartPoint("Jan 2026", BigDecimal("5000")),
        )
        val points = netWorthPoints(currentNetWorth = BigDecimal("40000"), monthlySeries = monthly)
        assertEquals(listOf("Nov 2025", "Dec 2025", "Jan 2026"), points.map { it.label })
        assertEquals(
            listOf(BigDecimal("11000"), BigDecimal("35000"), BigDecimal("40000")),
            points.map { it.value },
        )
    }

    @Test
    fun projectionPoints_growsByAverageMonthly() {
        val points = projectionPoints(
            today = LocalDate.of(2026, 1, 15),
            months = 3,
            currentNetWorth = BigDecimal("10000"),
            avgMonthly = BigDecimal("2000"),
        )
        assertEquals(listOf("Jan", "Feb", "Mar"), points.map { it.label })
        assertEquals(
            listOf(BigDecimal("12000"), BigDecimal("14000"), BigDecimal("16000")),
            points.map { it.value },
        )
    }

    @Test
    fun isCurrentFY_handlesAprilToMarchWindow() {
        val today = LocalDate.of(2026, 1, 15)
        assertTrue(isCurrentFY(date(2026, 1, 15), today, 3, zone))
        assertTrue(isCurrentFY(date(2025, 4, 1), today, 3, zone))
        assertTrue(isCurrentFY(date(2026, 3, 31), today, 3, zone))
        assertTrue(isCurrentFY(date(2025, 12, 10), today, 3, zone))
        assertFalse(isCurrentFY(date(2025, 3, 31), today, 3, zone))
        assertFalse(isCurrentFY(date(2026, 4, 1), today, 3, zone))
    }

    @Test
    fun strictTax_newRegimeRebateUnderTwelveLakh() {
        val result = calculateStrictTax(salary = 1_000_000.0, regime = "new", tax80c = 0.0)
        assertEquals(BigDecimal.ZERO, result.liability)
        assertEquals("Tax Free (Rebate Limit)", result.status)
    }

    @Test
    fun strictTax_newRegimeAboveTwelveLakhWithCess() {
        val result = calculateStrictTax(salary = 1_600_000.0, regime = "new", tax80c = 0.0)
        assertEquals(BigDecimal("163800.00"), result.liability)
        assertEquals("", result.status)
    }

    @Test
    fun strictTax_zeroSalaryPromptsSetup() {
        val result = calculateStrictTax(salary = 0.0, regime = "new", tax80c = 0.0)
        assertEquals(BigDecimal.ZERO, result.liability)
        assertEquals("Setup Income in Settings", result.status)
    }

    @Test
    fun strictTax_negativeSalaryTreatedAsZero() {
        val result = calculateStrictTax(salary = -1000.0, regime = "new", tax80c = 0.0)
        assertEquals("Setup Income in Settings", result.status)
    }

    @Test
    fun strictTax_oldRegimeApplies80cDeduction() {
        val result = calculateStrictTax(salary = 1_000_000.0, regime = "old", tax80c = 150_000.0)
        assertEquals(BigDecimal("75400.00"), result.liability)
    }

    @Test
    fun strictTax_oldRegimeCaps80cAtOnePointFiveLakh() {
        val result = calculateStrictTax(salary = 800_000.0, regime = "old", tax80c = 200_000.0)
        assertEquals(BigDecimal("33800.00"), result.liability)
    }

    @Test
    fun strictTax_oldRegimeRebateUnderFiveLakh() {
        val result = calculateStrictTax(salary = 400_000.0, regime = "old", tax80c = 0.0)
        assertEquals(BigDecimal.ZERO, result.liability)
        assertEquals("Tax Free (Rebate 87A)", result.status)
    }
}
