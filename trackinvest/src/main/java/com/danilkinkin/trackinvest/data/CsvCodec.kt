/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import com.danilkinkin.trackinvest.data.entities.Investment
import java.io.StringWriter
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord

private val csvDateFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

fun formatInvestmentDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(csvDateFormat)

fun parseInvestmentDate(value: String): Long? = runCatching {
    LocalDate.parse(value.trim(), csvDateFormat)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrNull()

fun splitTags(value: String): List<String> =
    value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

fun investmentsToCsv(investments: List<Investment>): String {
    val writer = StringWriter()
    CSVPrinter(writer, CSVFormat.DEFAULT).use { printer ->
        printer.printRecord("Date", "Type", "Amount", "Account", "Note", "Tags")
        investments
            .sortedByDescending { it.date }
            .forEach { investment ->
                printer.printRecord(
                    formatInvestmentDate(investment.date),
                    investment.type,
                    investment.amount.toPlainString(),
                    investment.account.orEmpty(),
                    investment.note,
                    investment.tags.joinToString(","),
                )
            }
    }
    return writer.toString()
}

fun csvToInvestments(csv: String): List<Investment> {
    val records = CSVParser.parse(csv, CSVFormat.DEFAULT).use { it.records }
    val investments = mutableListOf<Investment>()
    records.forEach { record ->
        if (record.size() < 3) return@forEach
        val date = parseInvestmentDate(record.get(0)) ?: return@forEach
        val amount = record.get(2).trim().toBigDecimalOrNull() ?: return@forEach
        investments += buildInvestment(record, date, amount)
    }
    return investments
}

private fun buildInvestment(
    record: CSVRecord,
    date: Long,
    amount: BigDecimal,
): Investment = Investment(
    date = date,
    type = record.get(1).trim().ifBlank { "Cash" },
    amount = amount,
    account = record.getOrNull(3)?.trim()?.ifBlank { null },
    note = record.getOrNull(4) ?: "",
    tags = splitTags(record.getOrNull(5) ?: ""),
)

private fun CSVRecord.getOrNull(index: Int): String? =
    if (isSet(index)) get(index) else null
