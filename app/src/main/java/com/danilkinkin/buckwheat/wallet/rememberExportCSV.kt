package com.danilkinkin.buckwheat.wallet

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.errorForReport
import com.danilkinkin.buckwheat.util.toLocalDate
import com.danilkinkin.buckwheat.util.toLocalDateTime
import kotlinx.coroutines.launch
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun rememberExportCSV(
    appViewModel: AppViewModel = hiltViewModel(),
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
): () -> Unit {
    if (activityResultRegistryOwner === null) return {}

    var createHistoryFileLauncher: ManagedActivityResultLauncher<String, Uri?>? = null

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val startPeriodDate by spendsViewModel.startPeriodDate.observeAsState()
    val finishPeriodDate by spendsViewModel.finishPeriodDate.observeAsState()

    val snackBarExportToCSVSuccess = stringResource(R.string.export_to_csv_success)
    val snackBarExportToCSVFailed = stringResource(R.string.export_to_csv_failed)

    val yearFormatter = DateTimeFormatter.ofPattern("yyyy")

    val fileName = if (startPeriodDate != null && finishPeriodDate != null) {
        val fromDate = startPeriodDate!!.toLocalDate()
        val toDate = LocalDate.now().coerceAtMost(finishPeriodDate!!.toLocalDate())

        val from = if (
            yearFormatter.format(fromDate) == yearFormatter.format(toDate)
        ) {
            DateTimeFormatter.ofPattern("dd-MM").format(fromDate)
        } else {
            DateTimeFormatter.ofPattern("dd-MM-yyyy").format(fromDate)
        }
        val to = DateTimeFormatter.ofPattern("dd-MM-yyyy").format(toDate)
        stringResource(R.string.export_to_csv_file_name, from, to)
    } else {
        stringResource(R.string.export_to_csv_file_name, "?", "?")
    }

    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner
    ) {
        createHistoryFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->
            if (uri === null) {
                coroutineScope.launch {
                    appViewModel.showSnackbar(snackBarExportToCSVFailed)
                }

                return@rememberLauncherForActivityResult
            }

            coroutineScope.launch {
                val stream = context.contentResolver.openOutputStream(uri)

                val printer = CSVPrinter(
                    stream?.writer(),
                    CSVFormat.Builder.create().setHeader("amount", "comment", "commit_time")
                        .build()
                )
                val dateFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

                (spendsViewModel.periodSpends.value ?: emptyList()).forEach { spent ->
                    printer.printRecord(
                        spent.value,
                        spent.comment,
                        spent.date.toLocalDateTime().format(dateFormatter),
                    )
                }

                printer.flush()
                printer.close()
                stream?.close()

                appViewModel.showSnackbar(snackBarExportToCSVSuccess)
            }
        }
    }

    return {
        try {
            createHistoryFileLauncher?.launch("$fileName.csv")
        } catch (e: Exception) {
            context.errorForReport = e.stackTraceToString()

            appViewModel.showSnackbar(snackBarExportToCSVFailed)
        }
    }
}
