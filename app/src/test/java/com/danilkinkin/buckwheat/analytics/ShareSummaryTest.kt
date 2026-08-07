package com.danilkinkin.buckwheat.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ShareSummaryTest {

    private val labels = ShareSummaryLabels(
        budget = "Budget",
        spent = "Spent",
        remaining = "Remaining",
        dailyAverage = "Daily average",
        transactions = "Transactions",
        categories = "Categories",
    )

    private val formatMoney: (BigDecimal) -> String = { "$$it" }

    @Test
    fun `summary contains period, totals, average and count`() {
        val text = buildShareSummary(
            periodLabel = "01.08.2026 — 31.08.2026",
            budget = BigDecimal(1000),
            spent = BigDecimal(450),
            dailyAverage = BigDecimal("15.50"),
            transactionsCount = 30,
            categories = emptyList(),
            labels = labels,
            formatMoney = formatMoney,
        )

        assertTrue(text.contains("01.08.2026 — 31.08.2026"))
        assertTrue(text.contains("Budget: $1000"))
        assertTrue(text.contains("Spent: $450"))
        assertTrue(text.contains("Remaining: $550"))
        assertTrue(text.contains("Daily average: $15.50"))
        assertTrue(text.contains("Transactions: 30"))
    }

    @Test
    fun `remaining is clamped to zero when overspent`() {
        val text = buildShareSummary(
            periodLabel = "p",
            budget = BigDecimal(100),
            spent = BigDecimal(150),
            dailyAverage = BigDecimal.ZERO,
            transactionsCount = 1,
            categories = emptyList(),
            labels = labels,
            formatMoney = formatMoney,
        )

        assertTrue(text.contains("Remaining: $0"))
        assertFalse(text.contains("Remaining: $-"))
    }

    @Test
    fun `category breakdown is appended when present`() {
        val text = buildShareSummary(
            periodLabel = "p",
            budget = BigDecimal(100),
            spent = BigDecimal(60),
            dailyAverage = BigDecimal.ZERO,
            transactionsCount = 3,
            categories = listOf(
                ShareCategoryLine("🍔 Food", BigDecimal(40)),
                ShareCategoryLine("🚕 Transport", BigDecimal(20)),
            ),
            labels = labels,
            formatMoney = formatMoney,
        )

        assertTrue(text.contains("Categories:"))
        assertTrue(text.contains("• 🍔 Food — $40"))
        assertTrue(text.contains("• 🚕 Transport — $20"))
    }

    @Test
    fun `categories section is omitted when empty`() {
        val text = buildShareSummary(
            periodLabel = "p",
            budget = BigDecimal(100),
            spent = BigDecimal(60),
            dailyAverage = BigDecimal.ZERO,
            transactionsCount = 3,
            categories = emptyList(),
            labels = labels,
            formatMoney = formatMoney,
        )

        assertFalse(text.contains("Categories"))
    }
}
