package com.danilkinkin.buckwheat.wallet

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.errorForReport
import kotlinx.coroutines.launch
import org.apache.commons.csv.CSVFormat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

@Composable
fun rememberImportCSV(
    appViewModel: AppViewModel = hiltViewModel(),
    spendsViewModel: SpendsViewModel = hiltViewModel(),
): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val snackBarImportSuccess = stringResource(R.string.import_csv_success)
    val snackBarImportFailed = stringResource(R.string.import_csv_failed)
    val snackBarImportEmpty = stringResource(R.string.import_csv_empty)

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                val reader = BufferedReader(InputStreamReader(stream))

                val parser = CSVFormat.Builder.create()
                    .setHeader("amount", "comment", "commit_time")
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .build()
                    .parse(reader)

                val transactions = mutableListOf<Transaction>()
                val dateFormatters = listOf(
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
                )

                for (record in parser) {
                    val amountStr = record["amount"] ?: continue
                    val comment = record["comment"] ?: ""
                    val dateStr = record["commit_time"] ?: continue

                    val amount = try {
                        BigDecimal(amountStr.replace(",", "."))
                    } catch (e: NumberFormatException) {
                        continue
                    }

                    val date = parseDate(dateStr, dateFormatters)
                    if (date == null) continue

                    transactions.add(
                        Transaction(
                            type = TransactionType.SPENT,
                            value = amount,
                            date = date,
                            comment = comment,
                        )
                    )
                }

                reader.close()
                stream?.close()

                if (transactions.isEmpty()) {
                    appViewModel.showSnackbar(snackBarImportEmpty)
                    return@launch
                }

                spendsViewModel.importTransactions(transactions)
                appViewModel.showSnackbar(snackBarImportSuccess)
            } catch (e: Exception) {
                context.errorForReport = e.stackTraceToString()
                appViewModel.showSnackbar(snackBarImportFailed)
            }
        }
    }

    return {
        openDocumentLauncher.launch(arrayOf("text/csv", "text/*", "*/*"))
    }
}

private fun parseDate(
    dateStr: String,
    formatters: List<DateTimeFormatter>,
): Date? {
    for (formatter in formatters) {
        try {
            val localDateTime = LocalDateTime.parse(dateStr.trim(), formatter)
            return Date.from(localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant())
        } catch (_: DateTimeParseException) {
        }
    }
    return null
}
