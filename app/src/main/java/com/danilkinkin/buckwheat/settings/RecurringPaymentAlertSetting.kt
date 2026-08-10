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
import com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_HOUR
import com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.di.recurringAlertEnabledStoreKey
import com.danilkinkin.buckwheat.di.recurringAlertHourStoreKey
import com.danilkinkin.buckwheat.di.recurringAlertMinuteStoreKey
import com.danilkinkin.buckwheat.editor.dateTimeEdit.TimePickerDialog
import com.danilkinkin.buckwheat.notifications.RecurringPaymentAlertScheduler
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.combineColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun RecurringPaymentAlertSetting() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(RECURRING_ALERT_DEFAULT_HOUR) }
    var minute by remember { mutableIntStateOf(RECURRING_ALERT_DEFAULT_MINUTE) }
    var showTimePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore.data.first()
        enabled = prefs[recurringAlertEnabledStoreKey] ?: false
        hour = prefs[recurringAlertHourStoreKey] ?: RECURRING_ALERT_DEFAULT_HOUR
        minute = prefs[recurringAlertMinuteStoreKey] ?: RECURRING_ALERT_DEFAULT_MINUTE
    }

    fun enable() {
        enabled = true
        coroutineScope.launch {
            context.settingsDataStore.edit { it[recurringAlertEnabledStoreKey] = true }
        }
        RecurringPaymentAlertScheduler.schedule(context, hour, minute)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enable()
        }
    }

    fun onToggle() {
        if (enabled) {
            enabled = false
            RecurringPaymentAlertScheduler.cancel(context)
            coroutineScope.launch {
                context.settingsDataStore.edit { it[recurringAlertEnabledStoreKey] = false }
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
                enable()
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
                painter = painterResource(R.drawable.ic_autorenew),
                tint = iconTint,
                contentDescription = null,
            )
        }
        Text(
            text = stringResource(R.string.recurring_alert_setting_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
        )
    }

    if (enabled) {
        TextRow(
            icon = painterResource(R.drawable.ic_clock),
            iconTint = iconTint,
            text = stringResource(R.string.recurring_alert_time),
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
                        it[recurringAlertHourStoreKey] = selectedHour
                        it[recurringAlertMinuteStoreKey] = selectedMinute
                    }
                }
                RecurringPaymentAlertScheduler.schedule(context, selectedHour, selectedMinute)
            },
            onClose = { showTimePicker = false },
        )
    }
}
