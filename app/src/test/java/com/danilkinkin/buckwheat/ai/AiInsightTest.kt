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
        previousPeriodTotal: String = "8500",
        dailyBudget: String = "150",
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
        previousPeriodTotal = BigDecimal(previousPeriodTotal),
        dailyBudget = BigDecimal(dailyBudget),
    )

    private fun spends(): List<WindowSpend> = listOf(
        WindowSpend(date(2026, 8, 3), BigDecimal("120"), "FOOD"),
        WindowSpend(date(2026, 8, 4), BigDecimal("2500"), "TRANSPORT"),
        WindowSpend(date(2026, 8, 5), BigDecimal("60"), "FOOD"),
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

    // --- buildOfflineReport ---

    @Test
    fun `offline report opens with an on-track overview`() {
        val report = buildOfflineReport(summary(), spends())
        assertTrue(report.startsWith("You've spent 6000 of a 10000 budget (60%)"))
        assertTrue(report.contains("leaving 4000"))
    }

    @Test
    fun `offline report flags over budget and adds a watch out note`() {
        val report = buildOfflineReport(summary(spent = "12000"), spends())
        assertTrue(report.contains("over budget by 2000"))
        assertTrue(report.contains("Watch out for"))
    }

    @Test
    fun `offline report mentions top category and biggest expense`() {
        val report = buildOfflineReport(summary(), spends())
        assertTrue(report.contains("FOOD is your biggest category at 3000 (50%)"))
        assertTrue(report.contains("biggest expense was 2500 (rent)"))
    }

    @Test
    fun `offline report mentions overspend days`() {
        val report = buildOfflineReport(summary(overspendDays = 2), spends())
        assertTrue(report.contains("2 days exceeded the daily budget"))
    }

    @Test
    fun `offline report compares to the previous period`() {
        val report = buildOfflineReport(summary(previousPeriodTotal = "5000"), spends())
        assertTrue(report.contains("up 20% versus the previous period"))
    }

    @Test
    fun `offline report names the peak spending day`() {
        val report = buildOfflineReport(summary(), spends())
        assertTrue(report.contains("peak spending day was 4 Aug 2026"))
    }

    @Test
    fun `offline report mentions the spending pace against the daily budget`() {
        val report = buildOfflineReport(summary(), spends())
        assertTrue(report.contains("above your 150 daily budget"))
    }

    @Test
    fun `offline report handles no spending yet`() {
        val report = buildOfflineReport(summary(spent = "0"), emptyList())
        assertTrue(report.contains("No spending yet this period"))
    }
}
