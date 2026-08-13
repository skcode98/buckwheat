package com.danilkinkin.buckwheat.ai

import com.danilkinkin.buckwheat.ai.WindowSpend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class AiInsightTest {

    private fun date(year: Int, month: Int, day: Int): Date =
        Date.from(LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant())

    private fun summary(
        budget: String = "10000",
        spent: String = "6000",
        categories: List<CategorySpendInsight> = listOf(
            CategorySpendInsight("FOOD", BigDecimal("3000"), 50),
            CategorySpendInsight("TRANSPORT", BigDecimal("1000"), 16),
        ),
        overspendDays: Int = 2,
    ) = SpendInsightSummary(
        currencyCode = "INR",
        budget = BigDecimal(budget),
        spent = BigDecimal(spent),
        startDate = LocalDate.of(2026, 8, 1),
        endDate = LocalDate.of(2026, 8, 31),
        today = LocalDate.of(2026, 8, 15),
        transactionCount = 12,
        categories = categories,
        biggestSpend = BigDecimal("2500"),
        biggestSpendComment = "rent",
        overspendDays = overspendDays,
        previousPeriodTotal = BigDecimal("8500"),
    )

    // --- parseAiInsightReport ---

    @Test
    fun `strips markdown code fences from the report`() {
        val raw = "```\nOverview looks fine.\n\n• Food is the biggest bucket.\n```"
        val parsed = parseAiInsightReport(raw)
        assertFalse(parsed.contains("```"))
        assertTrue(parsed.contains("• Food is the biggest bucket."))
        assertEquals(
            "Overview looks fine.\n\n• Food is the biggest bucket.",
            parsed,
        )
    }

    @Test
    fun `strips fenced json flavour and keeps plain text`() {
        val raw = "```json\nSomething costs a lot.\n```"
        assertEquals("Something costs a lot.", parseAiInsightReport(raw))
    }

    @Test
    fun `removes an echoed report label`() {
        assertEquals("Watch your food budget.", parseAiInsightReport("Report: Watch your food budget."))
    }

    @Test
    fun `keeps plain text unchanged`() {
        val raw = "You are on track. • Keep going."
        assertEquals(raw, parseAiInsightReport(raw))
    }

    @Test
    fun `collapses excess blank lines`() {
        assertEquals("A\n\nB", parseAiInsightReport("A\n\n\n\n\nB"))
    }

    // --- buildAiInsightUserPrompt ---

    @Test
    fun `user prompt contains the key facts`() {
        val prompt = buildAiInsightUserPrompt(summary())
        assertTrue(prompt.contains("Budget: 10000"))
        assertTrue(prompt.contains("Spent so far: 6000"))
        assertTrue(prompt.contains("Remaining: 4000"))
        assertTrue(prompt.contains("INR"))
        assertTrue(prompt.contains("1 Aug 2026 to 31 Aug 2026"))
        assertTrue(prompt.contains("Previous period spent: 8500"))
        assertTrue(prompt.contains("FOOD: 3000 (50%)"))
        assertTrue(prompt.contains("rent (2500)"))
        assertTrue(prompt.contains("Overspending days: 2"))
    }

    @Test
    fun `user prompt renders no categories and no biggest spend gracefully`() {
        val prompt = buildAiInsightUserPrompt(
            summary(categories = emptyList(), spent = "0", overspendDays = 0)
        )
        assertTrue(prompt.contains("Category breakdown:\n  none"))
        assertTrue(prompt.contains("Overspending days: 0"))
        assertTrue(prompt.contains("Remaining: 10000"))
    }

    // --- overspendDayCount ---

    @Test
    fun `counts distinct days whose total exceeded the daily budget`() {
        val spends = listOf(
            WindowSpend(date(2026, 8, 3), BigDecimal("120"), "FOOD"),
            WindowSpend(date(2026, 8, 3), BigDecimal("80"), "TRANSPORT"),
            WindowSpend(date(2026, 8, 4), BigDecimal("60"), "FOOD"),
        )
        assertEquals(
            1,
            overspendDayCount(
                spends,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 15),
                BigDecimal("150"),
            ),
        )
    }

    @Test
    fun `ignores spends outside the window and after today`() {
        val spends = listOf(
            WindowSpend(date(2026, 7, 31), BigDecimal("5000"), "FOOD"),
            WindowSpend(date(2026, 8, 20), BigDecimal("5000"), "FOOD"),
        )
        assertEquals(
            0,
            overspendDayCount(
                spends,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 15),
                BigDecimal("150"),
            ),
        )
    }

    @Test
    fun `returns zero when daily budget is not set`() {
        assertEquals(
            0,
            overspendDayCount(
                listOf(WindowSpend(date(2026, 8, 3), BigDecimal("500"), "FOOD")),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 15),
                BigDecimal.ZERO,
            ),
        )
    }
}
