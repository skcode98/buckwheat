package com.danilkinkin.buckwheat.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.danilkinkin.buckwheat.base.TextRow
import com.danilkinkin.buckwheat.di.reminderEnabledStoreKey
import com.danilkinkin.buckwheat.di.reminderHourStoreKey
import com.danilkinkin.buckwheat.di.reminderMinuteStoreKey
import com.danilkinkin.buckwheat.editor.dateTimeEdit.TimePickerDialog
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_HOUR
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.notifications.DailyBudgetReminderScheduler
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.combineColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun DailyBudgetReminderSetting() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(DAILY_REMINDER_DEFAULT_HOUR) }
    var minute by remember { mutableIntStateOf(DAILY_REMINDER_DEFAULT_MINUTE) }
    var showTimePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore.data.first()
        enabled = prefs[reminderEnabledStoreKey] ?: false
        hour = prefs[reminderHourStoreKey] ?: DAILY_REMINDER_DEFAULT_HOUR
        minute = prefs[reminderMinuteStoreKey] ?: DAILY_REMINDER_DEFAULT_MINUTE
    }

    fun enableReminder() {
        enabled = true
        coroutineScope.launch {
            context.settingsDataStore.edit { it[reminderEnabledStoreKey] = true }
        }
        DailyBudgetReminderScheduler.schedule(context, hour, minute)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableReminder()
        }
    }

    fun onToggleReminder() {
        if (enabled) {
            enabled = false
            DailyBudgetReminderScheduler.cancel(context)
            coroutineScope.launch {
                context.settingsDataStore.edit { it[reminderEnabledStoreKey] = false }
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
                enableReminder()
            }
        }
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
            text = stringResource(R.string.daily_reminder_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { onToggleReminder() },
        )
    }

    if (enabled) {
        TextRow(
            icon = painterResource(R.drawable.ic_clock),
            iconTint = iconTint,
            text = stringResource(R.string.daily_reminder_time),
            endCaption = String.format("%02d:%02d", hour, minute),
            modifier = Modifier.clickable { showTimePicker = true },
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
                        it[reminderHourStoreKey] = selectedHour
                        it[reminderMinuteStoreKey] = selectedMinute
                    }
                }
                DailyBudgetReminderScheduler.schedule(context, selectedHour, selectedMinute)
            },
            onClose = { showTimePicker = false },
        )
    }
}
