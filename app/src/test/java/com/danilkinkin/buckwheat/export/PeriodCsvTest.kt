package com.danilkinkin.buckwheat.export

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

class PeriodCsvTest {
    private val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")

    private fun spent(value: String, comment: String, date: LocalDateTime): Transaction =
        Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(value),
            date = Date.from(date.atZone(ZoneId.systemDefault()).toInstant()),
            comment = comment,
        )

    @Test
    fun buildPeriodCsvWritesHeaderAndRows() {
        val csv = buildPeriodCsv(
            listOf(
                spent("12.50", "lunch", LocalDateTime.of(2026, 8, 3, 12, 30)),
                spent("3.00", "bus", LocalDateTime.of(2026, 8, 4, 9, 5)),
            ),
            formatter,
        )
        val lines = csv.trim().lines()
        assertEquals("amount,comment,commit_time", lines[0])
        assertEquals("12.50,lunch,03-08-2026 12:30", lines[1])
        assertEquals("3.00,bus,04-08-2026 09:05", lines[2])
    }

    @Test
    fun buildPeriodCsvEscapesCommasAndQuotesInComment() {
        val csv = buildPeriodCsv(
            listOf(spent("5.00", "coffee, \"latte\"", LocalDateTime.of(2026, 8, 5, 8, 0))),
            formatter,
        )
        assertEquals("amount,comment,commit_time\r\n5.00,\"coffee, \"\"latte\"\"\",05-08-2026 08:00\r\n", csv)
    }

    @Test
    fun buildPeriodCsvEmptySpendsProducesOnlyHeader() {
        val csv = buildPeriodCsv(emptyList(), formatter)
        assertEquals("amount,comment,commit_time\r\n", csv)
    }

    @Test
    fun buildAutoExportFileNameSameYearDropsYearFromStart() {
        val name = buildAutoExportFileName(
            "Spends (from %1\$s to %2\$s)",
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 31),
        )
        assertEquals("Spends (from 01-08 to 31-08-2026)", name)
    }

    @Test
    fun buildAutoExportFileNameCrossYearIncludesYearOnStart() {
        val name = buildAutoExportFileName(
            "Spends (from %1\$s to %2\$s)",
            LocalDate.of(2025, 12, 26),
            LocalDate.of(2026, 1, 2),
        )
        assertEquals("Spends (from 26-12-2025 to 02-01-2026)", name)
    }
}