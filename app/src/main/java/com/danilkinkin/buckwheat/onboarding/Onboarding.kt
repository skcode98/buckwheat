package com.danilkinkin.buckwheat.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.DescriptionButton
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.errorForReport
import com.danilkinkin.buckwheat.settings.BackupRestoreViewModel
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import kotlinx.coroutines.launch

const val ON_BOARDING_SHEET = "onBoarding"

@Composable
fun Onboarding(
    onSetBudget: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight = LocalWindowInsets.current.calculateBottomPadding()
        .coerceAtLeast(16.dp)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val appViewModel: AppViewModel = hiltViewModel()
    val backupRestoreViewModel: BackupRestoreViewModel = hiltViewModel()

    val snackBarRestoreSuccess = stringResource(R.string.restore_success)
    val snackBarRestoreFailed = stringResource(R.string.restore_failed)
    val snackBarRestoreInvalid = stringResource(R.string.restore_invalid)

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingRestoreJson by remember { mutableStateOf<String?>(null) }

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
                                if (restored) {
                                    appViewModel.showSnackbar(snackBarRestoreSuccess)
                                    onClose()
                                } else {
                                    appViewModel.showSnackbar(snackBarRestoreInvalid)
                                }
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

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = navigationBarHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.hello),
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                NumberedRow(
                    number = 1,
                    title = stringResource(R.string.help_set_budget_title),
                    subtitle = stringResource(R.string.help_set_budget_description),
                )
                NumberedRow(
                    number = 2,
                    title = stringResource(R.string.help_record_spends_title),
                    subtitle = stringResource(R.string.help_record_spends_description),
                )
                NumberedRow(
                    number = 3,
                    title = stringResource(R.string.help_good_luck_title),
                    subtitle = stringResource(R.string.help_good_luck_description),
                )
            }
            Spacer(Modifier.height(48.dp))
            DescriptionButton(
                title = { Text(stringResource(R.string.set_period_title)) },
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
                onClick = {
                    onSetBudget()
                    onClose()
                },
            )
            Spacer(Modifier.height(12.dp))
            DescriptionButton(
                title = { Text(stringResource(R.string.onboarding_restore_title)) },
                description = { Text(stringResource(R.string.onboarding_restore_description)) },
                icon = painterResource(R.drawable.ic_file_download),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
                onClick = {
                    openBackupLauncher.launch(
                        arrayOf("application/json", "application/octet-stream", "text/*", "*/*")
                    )
                },
            )
        }
    }
}

@Preview
@Composable
private fun PreviewDefault() {
    BuckwheatTheme {
        Onboarding()
    }
}
