package com.danilkinkin.buckwheat.wallet

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.errorForReport
import com.danilkinkin.buckwheat.export.buildAutoExportFileName
import com.danilkinkin.buckwheat.export.buildPeriodCsv
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.launch
import java.time.LocalDate

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

    val startPeriodDate by spendsViewModel.startPeriodDate.collectAsStateWithLifecycle()
    val finishPeriodDate by spendsViewModel.finishPeriodDate.collectAsStateWithLifecycle()

    val snackBarExportToCSVSuccess = stringResource(R.string.export_to_csv_success)
    val snackBarExportToCSVFailed = stringResource(R.string.export_to_csv_failed)

    val fileNamePattern = stringResource(R.string.export_to_csv_file_name)

    val fileName = if (startPeriodDate != null && finishPeriodDate != null) {
        val fromDate = startPeriodDate!!.toLocalDate()
        val toDate = LocalDate.now().coerceAtMost(finishPeriodDate!!.toLocalDate())

        buildAutoExportFileName(fileNamePattern, fromDate, toDate)
    } else {
        fileNamePattern.format("?", "?")
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
                val csv = buildPeriodCsv(
                    spendsViewModel.periodSpends.value ?: emptyList(),
                )

                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.writer().use { it.write(csv) }
                }

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