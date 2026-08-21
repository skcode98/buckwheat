package com.danilkinkin.buckwheat.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.errorForReport
import kotlinx.coroutines.launch

private val RESTORE_MIME_TYPES = arrayOf(
    "application/json",
    "application/octet-stream",
    "text/*",
    "*/*",
)

/**
 * Shared composable that creates the full restore-from-backup flow:
 * file picker → confirm dialog with loading state → restore.
 *
 * Returns a lambda to launch the file picker. Call it from any button.
 *
 * Usage:
 * ```
 * val launchRestore = restoreBackupFlow(onRestoreSuccess = { ... })
 * // then in a clickable: launchRestore()
 * ```
 */
@Composable
fun restoreBackupFlow(
    onRestoreSuccess: () -> Unit = {},
    appViewModel: AppViewModel = hiltViewModel(),
    backupRestoreViewModel: BackupRestoreViewModel = hiltViewModel(),
): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val snackBarRestoreSuccess = stringResource(R.string.restore_success)
    val snackBarRestoreFailed = stringResource(R.string.restore_failed)
    val snackBarRestoreInvalid = stringResource(R.string.restore_invalid)

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingRestoreJson by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }

                if (json != null) {
                    pendingRestoreJson = json
                    showRestoreConfirm = true
                }
            } catch (e: Exception) {
                context.errorForReport = e.stackTraceToString()
                appViewModel.showSnackbar(snackBarRestoreFailed)
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!isRestoring) {
                    showRestoreConfirm = false
                    pendingRestoreJson = null
                }
            },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                if (isRestoring) {
                    CircularProgressIndicator()
                } else {
                    Text(stringResource(R.string.restore_confirm_message))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isRestoring,
                    onClick = {
                        val json = pendingRestoreJson
                        isRestoring = true
                        coroutineScope.launch {
                            try {
                                val restored = json != null && backupRestoreViewModel.restoreBackup(json)
                                showRestoreConfirm = false
                                pendingRestoreJson = null
                                isRestoring = false
                                if (restored) {
                                    appViewModel.showSnackbar(snackBarRestoreSuccess)
                                    onRestoreSuccess()
                                } else {
                                    appViewModel.showSnackbar(snackBarRestoreInvalid)
                                }
                            } catch (e: Exception) {
                                context.errorForReport = e.stackTraceToString()
                                appViewModel.showSnackbar(snackBarRestoreFailed)
                                isRestoring = false
                                showRestoreConfirm = false
                                pendingRestoreJson = null
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.restore_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isRestoring,
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

    return { openBackupLauncher.launch(RESTORE_MIME_TYPES) }
}
