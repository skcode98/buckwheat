package com.danilkinkin.buckwheat.interleaved

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class AnalyzeSpendingPatternsTest {

    private fun dateOf(day: LocalDate): Date =
        Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())

    private fun spend(value: String, day: LocalDate, category: String): Transaction =
        Transaction(TransactionType.SPENT, BigDecimal(value), dateOf(day), category = category)

    private fun monthlySeries(months: List<Int>, year: Int = 2026) =
        months.map { LocalDate.of(year, it, 15) }

    // --- suggestFrequency ---

    @Test
    fun `monthly cadence is monthly`() {
        assertEquals(
            CategoryFrequency.MONTHLY,
            suggestFrequency(monthlySeries(listOf(1, 2, 3, 4, 5, 6))),
        )
    }

    @Test
    fun `quarterly cadence is quarterly`() {
        assertEquals(
            CategoryFrequency.QUARTERLY,
            suggestFrequency(monthlySeries(listOf(1, 4, 7, 10))),
        )
    }

    @Test
    fun `annual cadence is annual`() {
        assertEquals(
            CategoryFrequency.ANNUAL,
            suggestFrequency(
                listOf(LocalDate.of(2024, 6, 15), LocalDate.of(2025, 6, 15))
            ),
        )
    }

    @Test
    fun `too few or too close occurrences stay null`() {
        assertNull(suggestFrequency(emptyList()))
        assertNull(suggestFrequency(listOf(LocalDate.of(2026, 1, 1))))
        assertNull(suggestFrequency(monthlySeries(listOf(1, 1)))) // single gap of 0 days
    }

    @Test
    fun `sporadic occurrences without a cadence stay null`() {
        assertNull(
            suggestFrequency(
                listOf(
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 15),
                    LocalDate.of(2026, 2, 1),
                    LocalDate.of(2026, 2, 15),
                    LocalDate.of(2026, 3, 1),
                )
            )
        )
    }

    // --- medianAmount ---

    @Test
    fun `median of odd count is the middle value`() {
        assertEquals(BigDecimal("100.00"), medianAmount(listOf(BigDecimal("50"), BigDecimal("100"), BigDecimal("200"))))
    }

    @Test
    fun `median of even count averages the middle two`() {
        assertEquals(
            BigDecimal("150.00"),
            medianAmount(listOf(BigDecimal("100"), BigDecimal("100"), BigDecimal("200"), BigDecimal("200"))),
        )
    }

    @Test
    fun `median of empty input is zero`() {
        assertEquals(BigDecimal("0.00"), medianAmount(emptyList()))
    }

    // --- analyzeSpendingPatterns ---

    @Test
    fun `groups recurring spends by category with median amounts`() {
        val rents = listOf("4000", "2000", "4000", "2000", "4000", "2000")
        val spends = buildList {
            rents.zip(monthlySeries(listOf(1, 2, 3, 4, 5, 6))).forEach { (value, day) ->
                add(spend(value, day, "RENT"))
            }
            addAll(monthlySeries(listOf(1, 2, 3, 4, 5, 6)).map { spend("300", it, "INTERNET") })
        }

        val suggestions = analyzeSpendingPatterns(spends)

        assertEquals(2, suggestions.size)
        assertEquals("INTERNET", suggestions[0].name)
        assertEquals(CategoryFrequency.MONTHLY, suggestions[0].frequency)
        assertEquals(BigDecimal("300.00"), suggestions[0].amount)
        assertEquals("RENT", suggestions[1].name)
        // median of [2000,2000,2000,4000,4000,4000] = 3000
        assertEquals(BigDecimal("3000.00"), suggestions[1].amount)
    }

    @Test
    fun `ignores uncategorized and one-off spends`() {
        val spends = buildList {
            addAll(monthlySeries(listOf(1, 2, 3, 4, 5, 6)).map { spend("300", it, "INTERNET") })
            add(Transaction(TransactionType.SPENT, BigDecimal("50"), dateOf(LocalDate.of(2026, 1, 1))))
            add(spend("5000", LocalDate.of(2026, 1, 5), "SHOPPING"))
        }

        val suggestions = analyzeSpendingPatterns(spends)

        assertEquals(1, suggestions.size)
        assertEquals("INTERNET", suggestions.single().name)
    }
}
