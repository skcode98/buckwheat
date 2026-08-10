package com.danilkinkin.buckwheat.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.CheckedRow
import com.danilkinkin.buckwheat.base.TextRow
import com.danilkinkin.buckwheat.di.SPEND_DIGEST_DEFAULT_HOUR
import com.danilkinkin.buckwheat.di.SPEND_DIGEST_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.di.spendDigestEnabledStoreKey
import com.danilkinkin.buckwheat.di.spendDigestFrequencyStoreKey
import com.danilkinkin.buckwheat.di.spendDigestHourStoreKey
import com.danilkinkin.buckwheat.di.spendDigestMinuteStoreKey
import com.danilkinkin.buckwheat.editor.dateTimeEdit.TimePickerDialog
import com.danilkinkin.buckwheat.notifications.SpendDigestFrequency
import com.danilkinkin.buckwheat.notifications.SpendDigestScheduler
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.combineColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun SpendDigestSetting() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var frequency by remember { mutableStateOf(SpendDigestFrequency.WEEKLY) }
    var hour by remember { mutableIntStateOf(SPEND_DIGEST_DEFAULT_HOUR) }
    var minute by remember { mutableIntStateOf(SPEND_DIGEST_DEFAULT_MINUTE) }
    var showFrequencyPicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore.data.first()
        enabled = prefs[spendDigestEnabledStoreKey] ?: false
        frequency = runCatching {
            SpendDigestFrequency.valueOf(prefs[spendDigestFrequencyStoreKey] ?: "")
        }.getOrDefault(SpendDigestFrequency.WEEKLY)
        hour = prefs[spendDigestHourStoreKey] ?: SPEND_DIGEST_DEFAULT_HOUR
        minute = prefs[spendDigestMinuteStoreKey] ?: SPEND_DIGEST_DEFAULT_MINUTE
    }

    fun schedule() {
        SpendDigestScheduler.schedule(context, hour, minute, frequency)
    }

    fun enableDigest() {
        enabled = true
        coroutineScope.launch {
            context.settingsDataStore.edit { it[spendDigestEnabledStoreKey] = true }
        }
        schedule()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableDigest()
        }
    }

    fun onToggleDigest() {
        if (enabled) {
            enabled = false
            SpendDigestScheduler.cancel(context)
            coroutineScope.launch {
                context.settingsDataStore.edit { it[spendDigestEnabledStoreKey] = false }
            }
        } else {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                enableDigest()
            }
        }
    }

    fun onSelectFrequency(selected: SpendDigestFrequency) {
        frequency = selected
        showFrequencyPicker = false
        coroutineScope.launch {
            context.settingsDataStore.edit {
                it[spendDigestFrequencyStoreKey] = selected.name
            }
        }
        schedule()
    }

    val iconTint = contentColorFor(
        combineColors(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.surfaceVariant,
            angle = 0.3F,
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_notifications),
                tint = iconTint,
                contentDescription = null,
            )
        }
        Text(
            text = stringResource(R.string.spend_digest_setting_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { onToggleDigest() },
        )
    }

    if (enabled) {
        TextRow(
            icon = painterResource(R.drawable.ic_autorenew),
            iconTint = iconTint,
            text = stringResource(R.string.spend_digest_frequency),
            endCaption = if (frequency == SpendDigestFrequency.WEEKLY) {
                stringResource(R.string.spend_digest_frequency_weekly)
            } else {
                stringResource(R.string.spend_digest_frequency_monthly)
            },
            modifier = Modifier.clickable { showFrequencyPicker = true },
        )
        TextRow(
            icon = painterResource(R.drawable.ic_clock),
            iconTint = iconTint,
            text = stringResource(R.string.spend_digest_time),
            endCaption = String.format("%02d:%02d", hour, minute),
            modifier = Modifier.clickable { showTimePicker = true },
        )
    }

    if (showFrequencyPicker) {
        AlertDialog(
            onDismissRequest = { showFrequencyPicker = false },
            title = { Text(stringResource(R.string.spend_digest_frequency)) },
            text = {
                Column {
                    CheckedRow(
                        checked = frequency == SpendDigestFrequency.WEEKLY,
                        onValueChange = { onSelectFrequency(SpendDigestFrequency.WEEKLY) },
                        text = stringResource(R.string.spend_digest_frequency_weekly),
                        description = stringResource(R.string.spend_digest_frequency_weekly_description),
                    )
                    CheckedRow(
                        checked = frequency == SpendDigestFrequency.MONTHLY,
                        onValueChange = { onSelectFrequency(SpendDigestFrequency.MONTHLY) },
                        text = stringResource(R.string.spend_digest_frequency_monthly),
                        description = stringResource(R.string.spend_digest_frequency_monthly_description),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFrequencyPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showTimePicker) {
        TimePickerDialog(
            initTime = LocalTime.of(hour, minute),
            onSelect = { selectedHour, selectedMinute, _ ->
                hour = selectedHour
                minute = selectedMinute
                showTimePicker = false
                coroutineScope.launch {
                    context.settingsDataStore.edit {
                        it[spendDigestHourStoreKey] = selectedHour
                        it[spendDigestMinuteStoreKey] = selectedMinute
                    }
                }
                schedule()
            },
            onClose = { showTimePicker = false },
        )
    }
}
