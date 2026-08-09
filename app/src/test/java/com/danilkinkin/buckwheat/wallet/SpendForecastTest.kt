package com.danilkinkin.buckwheat.wallet

import com.danilkinkin.buckwheat.util.toDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class SpendForecastTest {
    private val startDate = LocalDate.of(2026, 8, 1).toDate()
    private val finishDate = LocalDate.of(2026, 8, 31).toDate()

    @Test
    fun projectsEndOfPeriodSpend() {
        val today = LocalDate.of(2026, 8, 10).toDate()

        val forecast = forecastEndOfPeriodSpend(
            budget = BigDecimal("2000"),
            spent = BigDecimal("600"),
            startDate = startDate,
            finishDate = finishDate,
            today = today,
        )

        assertNotNull(forecast)
        assertEquals(BigDecimal("1860.00"), forecast!!.projectedTotal)
        assertEquals(BigDecimal("93.0"), forecast.projectedPercent)
    }

    @Test
    fun projectsOverspending() {
        val today = LocalDate.of(2026, 8, 6).toDate()

        val forecast = forecastEndOfPeriodSpend(
            budget = BigDecimal("1000"),
            spent = BigDecimal("800"),
            startDate = startDate,
            finishDate = finishDate,
            today = today,
        )

        assertNotNull(forecast)
        assertEquals(BigDecimal("413.3"), forecast!!.projectedPercent)
        assertEquals(BigDecimal("4133.33"), forecast.projectedTotal)
    }

    @Test
    fun noBudgetYieldsNull() {
        val today = LocalDate.of(2026, 8, 10).toDate()

        assertNull(
            forecastEndOfPeriodSpend(
                budget = BigDecimal.ZERO,
                spent = BigDecimal("600"),
                startDate = startDate,
                finishDate = finishDate,
                today = today,
            )
        )
    }

    @Test
    fun noSpentYieldsNull() {
        val today = LocalDate.of(2026, 8, 10).toDate()

        assertNull(
            forecastEndOfPeriodSpend(
                budget = BigDecimal("2000"),
                spent = BigDecimal.ZERO,
                startDate = startDate,
                finishDate = finishDate,
                today = today,
            )
        )
    }

    @Test
    fun beforePeriodStartYieldsNull() {
        val today = LocalDate.of(2026, 7, 30).toDate()

        assertNull(
            forecastEndOfPeriodSpend(
                budget = BigDecimal("2000"),
                spent = BigDecimal("600"),
                startDate = startDate,
                finishDate = finishDate,
                today = today,
            )
        )
    }

    @Test
    fun pastFinishClampsToPeriodEnd() {
        val today = LocalDate.of(2026, 9, 15).toDate()

        val forecast = forecastEndOfPeriodSpend(
            budget = BigDecimal("2000"),
            spent = BigDecimal("620"),
            startDate = startDate,
            finishDate = finishDate,
            today = today,
        )

        assertNotNull(forecast)
        // 620 over the full 31 days
        assertEquals(BigDecimal("620.00"), forecast!!.projectedTotal)
    }
}
