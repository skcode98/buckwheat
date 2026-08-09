/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import com.danilkinkin.trackinvest.data.entities.Investment
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvCodecTest {
    private fun epochOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun investment(
        date: Long = epochOf(2024, 3, 15),
        type: String = "SIP",
        amount: BigDecimal = BigDecimal("1000.50"),
        note: String = "",
        tags: List<String> = emptyList(),
        account: String? = null,
    ) = Investment(
        date = date,
        type = type,
        amount = amount,
        note = note,
        tags = tags,
        account = account,
    )

    @Test
    fun `header row is written`() {
        val csv = investmentsToCsv(listOf(investment()))
        assertTrue(csv.startsWith("Date,Type,Amount,Account,Note,Tags"))
    }

    @Test
    fun `round trip preserves all ledger fields`() {
        val original = listOf(
            investment(
                date = epochOf(2024, 3, 15),
                type = "FD",
                amount = BigDecimal("25000.75"),
                note = "FD renew",
                tags = listOf("bank", "tax"),
                account = "Main Portfolio",
            ),
            investment(
                date = epochOf(2024, 1, 1),
                type = "SIP",
                amount = BigDecimal("5000"),
                tags = listOf("mutual"),
                account = null,
            ),
        )

        val restored = csvToInvestments(investmentsToCsv(original))

        assertEquals(2, restored.size)
        val fd = restored.first { it.type == "FD" }
        assertEquals(epochOf(2024, 3, 15), fd.date)
        assertEquals(BigDecimal("25000.75"), fd.amount)
        assertEquals("FD renew", fd.note)
        assertEquals(listOf("bank", "tax"), fd.tags)
        assertEquals("Main Portfolio", fd.account)

        val sip = restored.first { it.type == "SIP" }
        assertEquals(BigDecimal("5000"), sip.amount)
        assertEquals(listOf("mutual"), sip.tags)
        assertNull(sip.account)
    }

    @Test
    fun `note with comma quotes and newline round trips`() {
        val note = "lorem, \"ipsum\"\ndolor"
        val restored = csvToInvestments(investmentsToCsv(listOf(investment(note = note))))
        assertEquals(note, restored.single().note)
    }

    @Test
    fun `export sorts by date descending`() {
        val old = investment(date = epochOf(2023, 1, 1), type = "A")
        val recent = investment(date = epochOf(2024, 6, 1), type = "B")
        val csv = investmentsToCsv(listOf(old, recent))
        val restored = csvToInvestments(csv)
        assertEquals(listOf("B", "A"), restored.map { it.type })
    }

    @Test
    fun `import skips the header row`() {
        val csv = "Date,Type,Amount,Account,Note,Tags\n" +
            "2024-03-15,SIP,1000,Main Portfolio,sip note,long"
        val restored = csvToInvestments(csv)
        assertEquals(1, restored.size)
        assertEquals("SIP", restored.single().type)
        assertEquals(epochOf(2024, 3, 15), restored.single().date)
    }

    @Test
    fun `invalid rows are skipped`() {
        val csv = "Date,Type,Amount,Account,Note,Tags\n" +
            "not-a-date,SIP,1000,,\n" +
            "2024-03-15,SIP,abc,,\n" +
            "2024-03-15,SIP,500,,\n"
        val restored = csvToInvestments(csv)
        assertEquals(1, restored.size)
        assertEquals(BigDecimal("500"), restored.single().amount)
    }

    @Test
    fun `blank type defaults to Cash`() {
        val csv = "Date,Type,Amount,Account,Note,Tags\n" +
            "2024-03-15,,500,,\n"
        val restored = csvToInvestments(csv)
        assertEquals("Cash", restored.single().type)
    }

    @Test
    fun `tags split on commas and trim whitespace`() {
        val csv = "Date,Type,Amount,Account,Note,Tags\n" +
            "2024-03-15,SIP,500,,,\"one, two ,three\"\n"
        val restored = csvToInvestments(csv)
        assertEquals(listOf("one", "two", "three"), restored.single().tags)
    }

    @Test
    fun `date format is ISO yyyy-MM-dd`() {
        assertEquals("2024-03-15", formatInvestmentDate(epochOf(2024, 3, 15)))
    }

    @Test
    fun `splitTags ignores blanks`() {
        assertTrue(splitTags("").isEmpty())
        assertTrue(splitTags("  ,  ,").isEmpty())
        assertEquals(listOf("a", "b"), splitTags("a,b"))
    }

    @Test
    fun `empty ledger exports header only`() {
        val csv = investmentsToCsv(emptyList())
        assertTrue(csv.startsWith("Date,Type,Amount,Account,Note,Tags"))
        assertFalse(csvToInvestments(csv).isNotEmpty())
    }
}
