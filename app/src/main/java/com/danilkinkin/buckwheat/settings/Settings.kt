package com.danilkinkin.buckwheat.settings

import android.Manifest
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.BuildConfig
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.base.TextRow
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.editor.dateTimeEdit.TimePickerDialog
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import java.time.LocalTime

const val SETTINGS_SHEET = "settings"

@Composable
fun Settings(onTriedWidget: () -> Unit = {}) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = navigationBarHeight)
            ) {
                ThemeSwitcher()
                PersistTagsSwitcher()
                ReminderSwitcher()
                SyncSwitcher()
                LangSwitcher()
                TryWidget(onTried = {
                    onTriedWidget()
                })
                TextRow(
                    text = stringResource(R.string.version, BuildConfig.VERSION_NAME),
                )
                About(Modifier.padding(start = 16.dp, end = 16.dp))
            }
        }
    }
}

@Composable
fun PersistTagsSwitcher(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val persistTags by appViewModel.persistTags.observeAsState(false)

    Surface(
        modifier = Modifier.clickable { appViewModel.setPersistTags(!persistTags) },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.persist_tags_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = persistTags,
                    onCheckedChange = { appViewModel.setPersistTags(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Text(
                text = stringResource(R.string.persist_tags_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
fun ReminderSwitcher(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val reminderEnabled by appViewModel.reminderEnabled.observeAsState(false)
    val reminderHour by appViewModel.reminderHour.observeAsState(20)
    val reminderMinute by appViewModel.reminderMinute.observeAsState(0)
    var showTimePicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            appViewModel.setReminderEnabled(true)
        }
    }

    Surface(
        modifier = Modifier.clickable {
            if (!reminderEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                appViewModel.setReminderEnabled(!reminderEnabled)
            }
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.reminder_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (reminderEnabled) {
                    Text(
                        modifier = Modifier.clickable { showTimePicker = true },
                        text = String.format(
                            "%02d:%02d",
                            reminderHour,
                            reminderMinute,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Switch(
                    checked = reminderEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            appViewModel.setReminderEnabled(enabled)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Text(
                text = stringResource(R.string.reminder_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initTime = LocalTime.of(reminderHour, reminderMinute),
            onSelect = { hour, minute, _ ->
                appViewModel.setReminderTime(hour, minute)
                showTimePicker = false
            },
            onClose = { showTimePicker = false },
        )
    }
}

@Composable
fun SyncSwitcher(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val syncEnabled by appViewModel.syncEnabled.observeAsState(false)
    val syncHour by appViewModel.syncHour.observeAsState(22)
    val syncMinute by appViewModel.syncMinute.observeAsState(0)
    var showTimePicker by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.clickable {
            appViewModel.setSyncEnabled(!syncEnabled)
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.sync_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (syncEnabled) {
                    Text(
                        modifier = Modifier.clickable { showTimePicker = true },
                        text = String.format(
                            "%02d:%02d",
                            syncHour,
                            syncMinute,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Switch(
                    checked = syncEnabled,
                    onCheckedChange = { appViewModel.setSyncEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Text(
                text = stringResource(R.string.sync_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initTime = LocalTime.of(syncHour, syncMinute),
            onSelect = { hour, minute, _ ->
                appViewModel.setSyncTime(hour, minute)
                showTimePicker = false
            },
            onClose = { showTimePicker = false },
        )
    }
}

@Preview(name = "Default")
@Composable
private fun PreviewDefault() {
    BuckwheatTheme {
        Settings()
    }
}

@Preview(name = "Night mode", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewNightMode() {
    BuckwheatTheme {
        Settings()
    }
}
