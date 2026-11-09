package com.danilkinkin.buckwheat.settings

import android.net.Uri
import android.util.Log
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val snackBarRestoreSuccess = stringResource(R.string.restore_success)
    val snackBarRestoreFailed = stringResource(R.string.restore_failed)
    val snackBarRestoreInvalid = stringResource(R.string.restore_invalid)

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingRestoreJson by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }

    fun handleSelectedBackup(uri: Uri?) {
        if (uri == null) {
            Log.i("BackupRestore", "Backup picker was cancelled")
            return
        }

        coroutineScope.launch {
            try {
                Log.i("BackupRestore", "Backup file selected; reading content")
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: throw IllegalStateException("Unable to open selected backup file")

                pendingRestoreJson = json
                showRestoreConfirm = true
            } catch (e: Exception) {
                Log.e("BackupRestore", "Failed to read selected backup file", e)
                context.errorForReport = e.stackTraceToString()
                appViewModel.showSnackbar(snackBarRestoreFailed)
            }
        }
    }

    var getBackupLauncher: ManagedActivityResultLauncher<String, Uri?>? = null
    var openBackupLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>? = null

    if (activityResultRegistryOwner != null) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides activityResultRegistryOwner) {
            getBackupLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent(),
                onResult = ::handleSelectedBackup,
            )
            openBackupLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
                onResult = ::handleSelectedBackup,
            )
        }
    } else {
        getBackupLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
            onResult = ::handleSelectedBackup,
        )
        openBackupLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = ::handleSelectedBackup,
        )
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
                                Log.e("BackupRestore", "Failed to restore backup", e)
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

    return {
        Log.i("BackupRestore", "Restore button clicked; launching backup picker")
        try {
            getBackupLauncher?.launch("*/*")
                ?: run {
                    Log.e("BackupRestore", "GetContent launcher was not registered")
                    appViewModel.showSnackbar(snackBarRestoreFailed)
                }
        } catch (e: Exception) {
            Log.w("BackupRestore", "GetContent picker launch failed; trying OpenDocument", e)
            try {
                openBackupLauncher?.launch(RESTORE_MIME_TYPES)
                    ?: run {
                        Log.e("BackupRestore", "OpenDocument launcher was not registered")
                        appViewModel.showSnackbar(snackBarRestoreFailed)
                    }
            } catch (fallbackException: Exception) {
                fallbackException.addSuppressed(e)
                Log.e("BackupRestore", "All backup picker launch attempts failed", fallbackException)
                context.errorForReport = fallbackException.stackTraceToString()
                appViewModel.showSnackbar(snackBarRestoreFailed)
            }
        }
    }
}
