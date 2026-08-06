package com.danilkinkin.buckwheat.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.TextRow
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.errorForReport
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun BackupRestoreSetting(
    appViewModel: AppViewModel = hiltViewModel(),
    backupRestoreViewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val snackBarBackupSuccess = stringResource(R.string.backup_success)
    val snackBarBackupFailed = stringResource(R.string.backup_failed)
    val snackBarRestoreSuccess = stringResource(R.string.restore_success)
    val snackBarRestoreFailed = stringResource(R.string.restore_failed)
    val snackBarRestoreInvalid = stringResource(R.string.restore_invalid)

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingRestoreJson by remember { mutableStateOf<String?>(null) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            try {
                val json = backupRestoreViewModel.exportBackup()
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                }
                appViewModel.showSnackbar(snackBarBackupSuccess)
            } catch (e: Exception) {
                context.errorForReport = e.stackTraceToString()
                appViewModel.showSnackbar(snackBarBackupFailed)
            }
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: return@launch

                pendingRestoreJson = json
                showRestoreConfirm = true
            } catch (e: Exception) {
                context.errorForReport = e.stackTraceToString()
                appViewModel.showSnackbar(snackBarRestoreFailed)
            }
        }
    }

    TextRow(
        icon = painterResource(R.drawable.ic_file_upload),
        text = stringResource(R.string.backup_data),
        endIcon = painterResource(R.drawable.ic_arrow_right),
        modifier = Modifier.clickable {
            val fileName = "buckwheat-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(LocalDate.now())}.json"
            createBackupLauncher.launch(fileName)
        },
    )
    TextRow(
        icon = painterResource(R.drawable.ic_file_download),
        text = stringResource(R.string.restore_backup),
        endIcon = painterResource(R.drawable.ic_arrow_right),
        modifier = Modifier.clickable {
            openBackupLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*", "*/*"))
        },
    )

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = {
                showRestoreConfirm = false
                pendingRestoreJson = null
            },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val json = pendingRestoreJson
                        showRestoreConfirm = false
                        pendingRestoreJson = null
                        coroutineScope.launch {
                            try {
                                val restored = json != null && backupRestoreViewModel.restoreBackup(json)
                                appViewModel.showSnackbar(
                                    if (restored) snackBarRestoreSuccess else snackBarRestoreInvalid
                                )
                            } catch (e: Exception) {
                                context.errorForReport = e.stackTraceToString()
                                appViewModel.showSnackbar(snackBarRestoreFailed)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.restore_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        pendingRestoreJson = null
                    },
                ) {
                    Text(stringResource(R.string.restore_cancel))
                }
            },
        )
    }
}
