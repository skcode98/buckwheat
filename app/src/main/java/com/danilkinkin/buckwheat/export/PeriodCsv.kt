package com.danilkinkin.buckwheat.export

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.util.toLocalDateTime
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.StringWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Renders period transactions as a CSV document. Shared by the manual export dialog and the
// silent end-of-period auto-export so both produce identical files.
fun buildPeriodCsv(
    spends: List<Transaction>,
    dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT),
): String = StringWriter().use { writer ->
    CSVPrinter(
        writer,
        CSVFormat.Builder.create().setHeader("amount", "comment", "commit_time").build(),
    ).use { printer ->
        spends.forEach { spent ->
            printer.printRecord(
                spent.value,
                spent.comment,
                spent.date.toLocalDateTime().format(dateFormatter),
            )
        }
    }
    writer.toString()
}

// File name for a period export, e.g. "Spends (from 01-08 to 31-08)". The from date drops the
// year when it falls in the same year as the finish date. `pattern` carries the localized
// format string with the %1$s/%2$s placeholders.
fun buildAutoExportFileName(
    pattern: String,
    start: LocalDate,
    finish: LocalDate,
): String {
    val from = if (start.year == finish.year) {
        DateTimeFormatter.ofPattern("dd-MM").format(start)
    } else {
        DateTimeFormatter.ofPattern("dd-MM-yyyy").format(start)
    }
    val to = DateTimeFormatter.ofPattern("dd-MM-yyyy").format(finish)
    return pattern.format(from, to)
}