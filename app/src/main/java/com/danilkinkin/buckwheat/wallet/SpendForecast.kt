package com.danilkinkin.buckwheat.wallet

import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.roundToDay
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date

data class SpendForecast(
    val projectedPercent: BigDecimal,
    val projectedTotal: BigDecimal,
)

fun forecastEndOfPeriodSpend(
    budget: BigDecimal,
    spent: BigDecimal,
    startDate: Date,
    finishDate: Date,
    today: Date,
): SpendForecast? {
    if (budget <= BigDecimal.ZERO || spent <= BigDecimal.ZERO) return null

    val elapsedEnd = if (today.before(finishDate)) roundToDay(today) else roundToDay(finishDate)
    val elapsedDays = countDays(elapsedEnd, startDate).toLong()
    val totalDays = countDays(finishDate, startDate).toLong()

    if (elapsedDays <= 0 || totalDays <= 0) return null

    val dailyAverage = spent.divide(elapsedDays.toBigDecimal(), 4, RoundingMode.HALF_UP)
    val projectedTotal = dailyAverage
        .multiply(totalDays.toBigDecimal())
        .setScale(2, RoundingMode.HALF_UP)
    val projectedPercent = projectedTotal
        .multiply(BigDecimal(100))
        .divide(budget, 1, RoundingMode.HALF_UP)

    return SpendForecast(
        projectedPercent = projectedPercent,
        projectedTotal = projectedTotal,
    )
}
