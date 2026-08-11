package com.danilkinkin.buckwheat.interleaved

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class InterleavedBudgetTest {

    private fun date(year: Int, month: Int, day: Int): Date =
        Date.from(LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant())

    private fun category(
        name: String = "FOOD",
        amount: String = "3000",
        frequency: CategoryFrequency = CategoryFrequency.MONTHLY,
        anchor: LocalDate = LocalDate.of(2026, 9, 1),
    ) = InterleavedCategory(name, BigDecimal(amount), frequency, anchor.toEpochDay())

    // --- windowFor ---

    @Test
    fun `monthly window starts at anchor for the current month`() {
        val windows = windowFor(category(), LocalDate.of(2026, 9, 15))
        assertNotNull(windows)
        assertEquals(LocalDate.of(2026, 9, 1), windows!!.first)
        assertEquals(LocalDate.of(2026, 10, 1), windows.second)
    }

    @Test
    fun `monthly window rolls forward past the anchor month`() {
        val windows = windowFor(category(), LocalDate.of(2026, 11, 20))
        assertNotNull(windows)
        assertEquals(LocalDate.of(2026, 11, 1), windows!!.first)
        assertEquals(LocalDate.of(2026, 12, 1), windows.second)
    }

    @Test
    fun `anchor mid-month keeps the anchor day in later windows`() {
        val cat = category(anchor = LocalDate.of(2026, 9, 15))
        val windows = windowFor(cat, LocalDate.of(2026, 12, 20))
        assertNotNull(windows)
        assertEquals(LocalDate.of(2026, 12, 15), windows!!.first)
        assertEquals(LocalDate.of(2027, 1, 15), windows.second)
    }

    @Test
    fun `month-end anchor clamps via java time`() {
        val cat = category(anchor = LocalDate.of(2026, 1, 31))
        val windows = windowFor(cat, LocalDate.of(2026, 3, 15))
        assertNotNull(windows)
        assertEquals(LocalDate.of(2026, 2, 28), windows!!.first)
        assertEquals(LocalDate.of(2026, 3, 28), windows.second)
    }

    @Test
    fun `leap-year anchor clamps in non-leap years`() {
        val cat = category(
            frequency = CategoryFrequency.ANNUAL,
            anchor = LocalDate.of(2024, 2, 29),
        )
        val windows = windowFor(cat, LocalDate.of(2025, 8, 1))
        assertNotNull(windows)
        assertEquals(LocalDate.of(2025, 2, 28), windows!!.first)
        assertEquals(LocalDate.of(2026, 2, 28), windows.second)
    }

    @Test
    fun `today exactly at a window start belongs to the new window`() {
        val windows = windowFor(category(), LocalDate.of(2026, 10, 1))
        assertNotNull(windows)
        assertEquals(LocalDate.of(2026, 10, 1), windows!!.first)
        assertEquals(LocalDate.of(2026, 11, 1), windows.second)
    }

    @Test
    fun `today before the anchor falls in the first window`() {
        val windows = windowFor(category(), LocalDate.of(2026, 8, 15))
        assertNotNull(windows)
        assertEquals(LocalDate.of(2026, 9, 1), windows!!.first)
        assertEquals(LocalDate.of(2026, 10, 1), windows.second)
    }

    @Test
    fun `quarterly window spans three calendar months`() {
        val cat = category(frequency = CategoryFrequency.QUARTERLY)
        val windows = windowFor(cat, LocalDate.of(2027, 1, 20))
        assertNotNull(windows)
        assertEquals(LocalDate.of(2026, 12, 1), windows!!.first)
        assertEquals(LocalDate.of(2027, 3, 1), windows.second)
    }

    @Test
    fun `daily frequency has no window`() {
        assertNull(windowFor(category(frequency = CategoryFrequency.DAILY), LocalDate.of(2026, 9, 15)))
    }

    // --- hasRolled ---

    @Test
    fun `recorded start of the current window has not rolled`() {
        val cat = category()
        val today = LocalDate.of(2026, 9, 15)
        val current = windowFor(cat, today)!!.first
        assertFalse(hasRolled(cat, today, current.toEpochDay()))
    }

    @Test
    fun `recorded start of a previous window has rolled`() {
        val cat = category()
        val today = LocalDate.of(2026, 11, 20)
        assertTrue(hasRolled(cat, today, LocalDate.of(2026, 10, 1).toEpochDay()))
    }

    @Test
    fun `sentinel recorded start forces a reset`() {
        assertTrue(hasRolled(category(), LocalDate.of(2026, 9, 15), Long.MIN_VALUE))
    }

    @Test
    fun `daily frequency never rolls`() {
        val cat = category(frequency = CategoryFrequency.DAILY)
        assertFalse(hasRolled(cat, LocalDate.of(2026, 9, 15), Long.MIN_VALUE))
    }

    // --- windowSpent ---

    @Test
    fun `window spend sums matching in-window transactions`() {
        val cat = category()
        val transactions = listOf(
            WindowSpend(date(2026, 9, 5), BigDecimal("100"), "FOOD"),
            WindowSpend(date(2026, 9, 30), BigDecimal("250.50"), "FOOD"),
            WindowSpend(date(2026, 8, 31), BigDecimal("999"), "FOOD"),
        )
        assertEquals(BigDecimal("350.50"), windowSpent(transactions, cat, LocalDate.of(2026, 9, 15)))
    }

    @Test
    fun `transaction exactly on the window end belongs to the next window`() {
        val cat = category()
        val transactions = listOf(WindowSpend(date(2026, 10, 1), BigDecimal("100"), "FOOD"))
        assertEquals(BigDecimal.ZERO, windowSpent(transactions, cat, LocalDate.of(2026, 9, 15)))
    }

    @Test
    fun `future backfilled transaction is excluded`() {
        val cat = category()
        val transactions = listOf(WindowSpend(date(2026, 12, 10), BigDecimal("100"), "FOOD"))
        assertEquals(BigDecimal.ZERO, windowSpent(transactions, cat, LocalDate.of(2026, 9, 15)))
    }

    @Test
    fun `category matching is case-insensitive and nulls are excluded`() {
        val cat = category()
        val transactions = listOf(
            WindowSpend(date(2026, 9, 5), BigDecimal("100"), "food"),
            WindowSpend(date(2026, 9, 5), BigDecimal("50"), null),
            WindowSpend(date(2026, 9, 5), BigDecimal("70"), "TRAVEL"),
        )
        assertEquals(BigDecimal("100"), windowSpent(transactions, cat, LocalDate.of(2026, 9, 15)))
    }

    @Test
    fun `daily frequency reports zero window spend`() {
        val cat = category(frequency = CategoryFrequency.DAILY)
        val transactions = listOf(WindowSpend(date(2026, 9, 5), BigDecimal("100"), "FOOD"))
        assertEquals(BigDecimal.ZERO, windowSpent(transactions, cat, LocalDate.of(2026, 9, 15)))
    }

    // --- monthlyEquivalent ---

    @Test
    fun `monthly equivalent divides by the window length in months`() {
        assertEquals(BigDecimal("3000.00"), monthlyEquivalent(category(amount = "3000")))
        assertEquals(
            BigDecimal("1000.00"),
            monthlyEquivalent(category(amount = "3000", frequency = CategoryFrequency.QUARTERLY)),
        )
        assertEquals(
            BigDecimal("250.00"),
            monthlyEquivalent(category(amount = "3000", frequency = CategoryFrequency.ANNUAL)),
        )
    }

    @Test
    fun `daily monthly equivalent returns the cap amount`() {
        assertEquals(
            BigDecimal("3000"),
            monthlyEquivalent(category(amount = "3000", frequency = CategoryFrequency.DAILY)),
        )
    }

    // --- daysLeftInWindow ---

    @Test
    fun `days left includes today`() {
        val cat = category()
        assertEquals(30, daysLeftInWindow(cat, LocalDate.of(2026, 9, 1)))
        assertEquals(16, daysLeftInWindow(cat, LocalDate.of(2026, 9, 15)))
        assertEquals(1, daysLeftInWindow(cat, LocalDate.of(2026, 9, 30)))
    }

    @Test
    fun `days left at a window boundary counts the new window`() {
        // Oct 1 / Nov 1 are the next window starts, so the count restarts each window.
        val cat = category()
        assertEquals(31, daysLeftInWindow(cat, LocalDate.of(2026, 10, 1)))
        assertEquals(30, daysLeftInWindow(cat, LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun `daily frequency has zero days left`() {
        val cat = category(frequency = CategoryFrequency.DAILY)
        assertEquals(0, daysLeftInWindow(cat, LocalDate.of(2026, 9, 15)))
    }

    // --- projectedExhaustionDate ---

    @Test
    fun `spent the whole amount on day one exhausts today`() {
        val cat = category(amount = "100")
        assertEquals(
            LocalDate.of(2026, 9, 1),
            projectedExhaustionDate(cat, LocalDate.of(2026, 9, 1), BigDecimal("100")),
        )
    }

    @Test
    fun `half the amount on day one exhausts tomorrow`() {
        val cat = category(amount = "100")
        assertEquals(
            LocalDate.of(2026, 9, 2),
            projectedExhaustionDate(cat, LocalDate.of(2026, 9, 1), BigDecimal("50")),
        )
    }

    @Test
    fun `no exhaustion when nothing is spent`() {
        assertNull(projectedExhaustionDate(category(), LocalDate.of(2026, 9, 1), BigDecimal.ZERO))
    }

    @Test
    fun `no exhaustion when the window ends first`() {
        val cat = category(amount = "1000")
        // 50 rupees over 15 days = 3.33/day; 1000 / 3.33 = 300 days > the 30-day window.
        assertNull(projectedExhaustionDate(cat, LocalDate.of(2026, 9, 15), BigDecimal("50")))
    }

    @Test
    fun `daily frequency has no exhaustion date`() {
        assertNull(
            projectedExhaustionDate(
                category(frequency = CategoryFrequency.DAILY),
                LocalDate.of(2026, 9, 1),
                BigDecimal("50"),
            )
        )
    }
}
