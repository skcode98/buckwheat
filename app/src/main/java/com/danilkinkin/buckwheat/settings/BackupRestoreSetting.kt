package com.danilkinkin.buckwheat.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
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

    var isExporting by remember { mutableStateOf(false) }

    val launchRestore = restoreBackupFlow(
        appViewModel = appViewModel,
        backupRestoreViewModel = backupRestoreViewModel,
    )

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            isExporting = true
            try {
                val json = backupRestoreViewModel.exportBackup()
                val output = context.contentResolver.openOutputStream(uri)
                if (output == null) {
                    appViewModel.showSnackbar(snackBarBackupFailed)
                    isExporting = false
                    return@launch
                }
                output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                appViewModel.showSnackbar(snackBarBackupSuccess)
            } catch (e: Exception) {
                context.errorForReport = e.stackTraceToString()
                appViewModel.showSnackbar(snackBarBackupFailed)
            } finally {
                isExporting = false
            }
        }
    }

    TextRow(
        icon = painterResource(R.drawable.ic_file_upload),
        text = stringResource(R.string.backup_data),
        endIcon = if (isExporting) null else painterResource(R.drawable.ic_arrow_right),
        endContent = if (isExporting) {
            { CircularProgressIndicator(strokeWidth = 2.dp) }
        } else null,
        modifier = Modifier.clickable(enabled = !isExporting) {
            val fileName = "buckwheat-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(LocalDate.now())}.json"
            createBackupLauncher.launch(fileName)
        },
    )
    TextRow(
        icon = painterResource(R.drawable.ic_file_download),
        text = stringResource(R.string.restore_backup),
        endIcon = painterResource(R.drawable.ic_arrow_right),
        modifier = Modifier.clickable { launchRestore() },
    )
}
