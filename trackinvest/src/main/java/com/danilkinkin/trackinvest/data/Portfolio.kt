/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import com.danilkinkin.trackinvest.data.entities.Category
import com.danilkinkin.trackinvest.data.entities.CategoryDetail
import com.danilkinkin.trackinvest.data.entities.Investment
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

const val NET_WORTH_HISTORY_MONTHS = 24

const val MATURITY_WINDOW_DAYS = 90

data class TypeValuation(
    val type: String,
    val invested: BigDecimal,
    val total: BigDecimal,
    val interest: BigDecimal,
    val count: Int,
)

data class MaturityEntry(
    val investment: Investment,
    val daysLeft: Long,
)

data class ChartPoint(
    val label: String,
    val value: BigDecimal,
)

data class PortfolioSummary(
    val today: LocalDate,
    val netWorth: BigDecimal,
    val totalInvested: BigDecimal,
    val interestEarned: BigDecimal,
    val thisMonthInvested: BigDecimal,
    val lastMonthInvested: BigDecimal,
    val yearInvested: BigDecimal,
    val avgMonthly: BigDecimal,
    val tax80c: BigDecimal,
    val typeTotals: List<TypeValuation>,
    val maturities: List<MaturityEntry>,
    val netWorthHistory: List<ChartPoint>,
    val monthlyHistory: List<ChartPoint>,
    val recentActivity: List<Investment>,
    val valuationErrors: List<String>,
)

data class TaxResult(
    val liability: BigDecimal,
    val status: String,
)

private fun Double.toMoney(): BigDecimal =
    BigDecimal.valueOf(this).setScale(2, RoundingMode.HALF_UP)

private fun BigDecimal.coerceAtLeastZero(): BigDecimal =
    if (this < BigDecimal.ZERO) BigDecimal.ZERO else this

private fun investmentDate(investment: Investment, zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(investment.date).atZone(zoneId).toLocalDate()

private fun categoryValue(
    details: List<CategoryDetail>,
    type: String,
    key: String,
): String? = details.firstOrNull { it.category == type && it.key == key }?.value

private fun yearsBetween(date: LocalDate, today: LocalDate): Double =
    max(0.0, ChronoUnit.DAYS.between(date, today) / 365.25)

private fun monthLabel(year: Int, monthValue: Int): String =
    "${Month.of(monthValue).getDisplayName(TextStyle.SHORT, Locale.US)} $year"

private fun buildEffectiveInvestments(
    rawInvs: List<Investment>,
    initialBal: Double,
    zoneId: ZoneId,
): List<Investment> {
    if (initialBal <= 0.0) return rawInvs
    val earliest = rawInvs.minOfOrNull { investmentDate(it, zoneId) } ?: LocalDate.of(2023, 1, 1)
    val earlyDate = earliest.minusDays(1)
    return rawInvs + Investment(
        date = earlyDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        type = "",
        amount = initialBal.toBigDecimal(),
        note = "Initial Balance",
    )
}

private fun fdValuation(
    invs: List<Investment>,
    defaultRate: Double,
    today: LocalDate,
    zoneId: ZoneId,
): Pair<BigDecimal, BigDecimal> {
    var total = 0.0
    var interest = 0.0
    invs.forEach { inv ->
        val rate = inv.interestRate ?: defaultRate
        val payout = inv.payoutType ?: "quarterly"
        val years = yearsBetween(investmentDate(inv, zoneId), today)
        val principal = inv.amount.toDouble()
        val futureVal = when (payout) {
            "quarterly" -> principal * (1.0 + rate / 100.0 / 4.0).pow(4 * years)
            "monthly" -> principal * (1.0 + rate / 100.0 / 12.0).pow(12 * years)
            else -> principal * (1.0 + rate / 100.0 * years)
        }
        total += futureVal
        interest += futureVal - principal
    }
    return total.toMoney() to interest.toMoney()
}

private fun monthlyCompoundValuation(
    invs: List<Investment>,
    defaultRate: Double,
    today: LocalDate,
    zoneId: ZoneId,
): Pair<BigDecimal, BigDecimal> {
    var total = 0.0
    var interest = 0.0
    invs.forEach { inv ->
        val years = yearsBetween(investmentDate(inv, zoneId), today)
        val monthlyRate = (defaultRate / 100.0) / 12.0
        val months = years * 12
        val principal = inv.amount.toDouble()
        val futureVal = principal * (1.0 + monthlyRate).pow(months)
        total += futureVal
        interest += futureVal - principal
    }
    return total.toMoney() to interest.toMoney()
}

private fun customRateValuation(
    invs: List<Investment>,
    customRate: Double,
    today: LocalDate,
    zoneId: ZoneId,
    totalInvested: BigDecimal,
): Pair<BigDecimal, BigDecimal> {
    if (customRate <= 0.0) return totalInvested to BigDecimal.ZERO
    var total = 0.0
    var interest = 0.0
    invs.forEach { inv ->
        val rate = inv.interestRate ?: customRate
        val years = yearsBetween(investmentDate(inv, zoneId), today)
        val principal = inv.amount.toDouble()
        val futureVal = principal * (1.0 + rate / 100.0 / 12.0).pow(12 * years)
        total += futureVal
        interest += futureVal - principal
    }
    return total.toMoney() to interest.toMoney()
}

/**
 * Port of the web app's `calculateStrictValuation`. Per-type current value:
 * FD (quarterly/monthly/simple payout), PF/PPF (monthly compounding at the
 * government rate), SIP/Stocks (invested-amount fallback until live NAV is
 * wired up), custom-rate types (monthly compounding), otherwise invested amount.
 */
fun strictValuation(
    type: String,
    totalInvested: BigDecimal,
    rawInvs: List<Investment>,
    categoryDetails: List<CategoryDetail>,
    today: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
): TypeValuation {
    val govRates = mapOf("PPF" to 7.1, "PF" to 8.15)
    val defaultRate = categoryValue(categoryDetails, type, "interestRate")
        ?.toDoubleOrNull()
        ?: govRates[type]
        ?: 0.0
    val initialBal = categoryValue(categoryDetails, type, "initialBal")?.toDoubleOrNull() ?: 0.0
    val effectiveInvs = buildEffectiveInvestments(rawInvs, initialBal, zoneId)

    val valuation: Pair<BigDecimal, BigDecimal> = when (type) {
        "FD" -> fdValuation(effectiveInvs, defaultRate, today, zoneId)
        "PF", "PPF" -> monthlyCompoundValuation(effectiveInvs, defaultRate, today, zoneId)
        "SIP", "Stocks" -> totalInvested to BigDecimal.ZERO
        else -> customRateValuation(
            invs = effectiveInvs,
            customRate = categoryValue(categoryDetails, type, "interestRate")?.toDoubleOrNull() ?: 0.0,
            today = today,
            zoneId = zoneId,
            totalInvested = totalInvested,
        )
    }

    return TypeValuation(
        type = type,
        invested = totalInvested,
        total = valuation.first,
        interest = valuation.second,
        count = rawInvs.size,
    )
}

fun computePortfolioSummary(
    investments: List<Investment>,
    categories: List<Category>,
    categoryDetails: List<CategoryDetail>,
    today: LocalDate,
    fyStartMonth: Int,
    zoneId: ZoneId = ZoneId.systemDefault(),
): PortfolioSummary {
    val types = categories.map { it.name }.ifEmpty { investments.map { it.type }.distinct() }
    val typeTotals = mutableListOf<TypeValuation>()
    val valuationErrors = mutableListOf<String>()
    var netWorth = BigDecimal.ZERO
    var totalInvested = BigDecimal.ZERO
    var interestEarned = BigDecimal.ZERO

    types.forEach { type ->
        val valid = investments.filter {
            it.type == type &&
                !it.isClosed &&
                it.amount > BigDecimal.ZERO
        }
        val initialBal = categoryValue(categoryDetails, type, "initialBal")?.toDoubleOrNull() ?: 0.0
        val invested = valid.fold(initialBal.toBigDecimal()) { acc, inv -> acc + inv.amount }
        totalInvested += invested
        val valuation = strictValuation(type, invested, valid, categoryDetails, today, zoneId)
        if (valuation.total < BigDecimal.ZERO) {
            valuationErrors += "Negative valuation for $type"
        }
        typeTotals += TypeValuation(
            type = type,
            invested = invested,
            total = valuation.total.coerceAtLeastZero(),
            interest = valuation.interest.coerceAtLeastZero(),
            count = valid.size,
        )
        netWorth += valuation.total.coerceAtLeastZero()
        interestEarned += valuation.interest.coerceAtLeastZero()
    }

    val currentMonth = today.monthValue - 1
    val currentYear = today.year
    val lastMonth = if (currentMonth == 0) 11 else currentMonth - 1
    val lastMonthYear = if (currentMonth == 0) currentYear - 1 else currentYear

    var thisMonthTotal = BigDecimal.ZERO
    var lastMonthTotal = BigDecimal.ZERO
    var yearTotal = BigDecimal.ZERO
    var tax80c = BigDecimal.ZERO
    val maturities = mutableListOf<MaturityEntry>()

    investments.forEach { inv ->
        val date = investmentDate(inv, zoneId)
        inv.maturityDate?.let { millis ->
            val maturityDate = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
            val diffDays = ChronoUnit.DAYS.between(today, maturityDate)
            if (diffDays in -MATURITY_WINDOW_DAYS.toLong()..MATURITY_WINDOW_DAYS.toLong()) {
                maturities += MaturityEntry(inv, diffDays)
            }
        }
        if (date.year == currentYear && date.monthValue - 1 == currentMonth) {
            thisMonthTotal += inv.amount
        }
        if (date.year == lastMonthYear && date.monthValue - 1 == lastMonth) {
            lastMonthTotal += inv.amount
        }
        if (date.year == currentYear) {
            yearTotal += inv.amount
        }
        if (categories.any { it.name == inv.type && it.is80c } && isCurrentFY(inv.date, today, fyStartMonth, zoneId)) {
            tax80c += inv.amount
        }
    }

    val avgMonthly = yearTotal.divide((currentMonth + 1).toBigDecimal(), 2, RoundingMode.HALF_UP)

    val monthlyHistory = monthlyInvestedPoints(today, NET_WORTH_HISTORY_MONTHS, investments, zoneId)
    val netWorthHistory = netWorthPoints(netWorth, monthlyHistory)

    return PortfolioSummary(
        today = today,
        netWorth = netWorth,
        totalInvested = totalInvested,
        interestEarned = interestEarned,
        thisMonthInvested = thisMonthTotal,
        lastMonthInvested = lastMonthTotal,
        yearInvested = yearTotal,
        avgMonthly = avgMonthly,
        tax80c = tax80c,
        typeTotals = typeTotals.filter { it.total > BigDecimal.ZERO },
        maturities = maturities.sortedBy { it.daysLeft },
        netWorthHistory = netWorthHistory,
        monthlyHistory = monthlyHistory,
        recentActivity = investments.take(8),
        valuationErrors = valuationErrors,
    )
}

fun monthlyInvestedPoints(
    today: LocalDate,
    rangeMonths: Int,
    investments: List<Investment>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<ChartPoint> {
    val points = mutableListOf<ChartPoint>()
    for (i in rangeMonths - 1 downTo 0) {
        val month = today.minusMonths(i.toLong())
        val monthValue = investments
            .filter {
                val date = investmentDate(it, zoneId)
                date.year == month.year && date.monthValue == month.monthValue
            }
            .fold(BigDecimal.ZERO) { acc, inv -> acc + inv.amount }
        points += ChartPoint(monthLabel(month.year, month.monthValue), monthValue)
    }
    return points
}

/**
 * Port of the web `renderNWChart` reconstruction: walk back from the current
 * net worth, subtracting each month's invested amount.
 */
fun netWorthPoints(
    currentNetWorth: BigDecimal,
    monthlySeries: List<ChartPoint>,
): List<ChartPoint> {
    val result = mutableListOf<ChartPoint>()
    var temp = currentNetWorth
    for (i in monthlySeries.indices.reversed()) {
        result.add(0, ChartPoint(monthlySeries[i].label, temp))
        temp = temp - monthlySeries[i].value
    }
    return result
}

/**
 * Port of the web `renderHeroProjectionChart`: current net worth grows by the
 * average monthly invested amount each month.
 */
fun projectionPoints(
    today: LocalDate,
    months: Int,
    currentNetWorth: BigDecimal,
    avgMonthly: BigDecimal,
): List<ChartPoint> {
    val points = mutableListOf<ChartPoint>()
    for (i in 0 until months) {
        val month = today.plusMonths(i.toLong())
        val projected = currentNetWorth + avgMonthly * (i + 1).toBigDecimal()
        points += ChartPoint(
            label = Month.of(month.monthValue).getDisplayName(TextStyle.SHORT, Locale.US),
            value = projected.coerceAtLeastZero(),
        )
    }
    return points
}

fun isCurrentFY(
    dateMillis: Long,
    today: LocalDate,
    fyStartMonth: Int,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val date = Instant.ofEpochMilli(dateMillis).atZone(zoneId).toLocalDate()
    val curYear = today.year
    val curMonth = today.monthValue - 1
    val fyEndMonth = (fyStartMonth + 11) % 12
    val fyStartYear = if (curMonth >= fyStartMonth) curYear else curYear - 1
    val fyStart = LocalDate.of(fyStartYear, fyStartMonth + 1, 1)
    val fyEndYear = if (fyEndMonth < fyStartMonth) fyStartYear + 1 else fyStartYear
    val fyEndMonthDate = LocalDate.of(fyEndYear, fyEndMonth + 1, 1)
    val fyEnd = fyEndMonthDate.withDayOfMonth(fyEndMonthDate.lengthOfMonth())
    return date >= fyStart && date <= fyEnd
}

/**
 * Port of the web `calculateStrictTax` (FY 2024-25 slabs, 87A rebate, 4% cess).
 */
fun calculateStrictTax(salary: Double, regime: String, tax80c: Double): TaxResult {
    val sal = max(0.0, salary)
    if (sal == 0.0) return TaxResult(BigDecimal.ZERO, "Setup Income in Settings")
    var tax = 0.0
    if (regime == "new") {
        val taxable = max(0.0, sal - 75000.0)
        var taxBeforeRebate = 0.0
        if (taxable > 300000.0) taxBeforeRebate += min(taxable - 300000.0, 300000.0) * 0.05
        if (taxable > 600000.0) taxBeforeRebate += min(taxable - 600000.0, 300000.0) * 0.10
        if (taxable > 900000.0) taxBeforeRebate += min(taxable - 900000.0, 300000.0) * 0.15
        if (taxable > 1200000.0) taxBeforeRebate += min(taxable - 1200000.0, 300000.0) * 0.20
        if (taxable > 1500000.0) taxBeforeRebate += (taxable - 1500000.0) * 0.30
        if (taxable <= 1200000.0) return TaxResult(BigDecimal.ZERO, "Tax Free (Rebate Limit)")
        tax = taxBeforeRebate
    } else {
        val taxable = max(0.0, sal - 50000.0 - min(tax80c, 150000.0))
        var taxBeforeRebate = 0.0
        if (taxable > 250000.0) taxBeforeRebate += min(taxable - 250000.0, 250000.0) * 0.05
        if (taxable > 500000.0) taxBeforeRebate += min(taxable - 500000.0, 500000.0) * 0.20
        if (taxable > 1000000.0) taxBeforeRebate += (taxable - 1000000.0) * 0.30
        if (taxable <= 500000.0) return TaxResult(BigDecimal.ZERO, "Tax Free (Rebate 87A)")
        tax = taxBeforeRebate
    }
    tax = tax * 1.04
    return TaxResult(tax.toMoney(), "")
}
