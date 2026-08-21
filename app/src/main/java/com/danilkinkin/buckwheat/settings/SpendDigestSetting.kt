package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.danilkinkin.buckwheat.notifications.SpendDigestFrequency
import com.danilkinkin.buckwheat.notifications.SpendDigestScheduler
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.combineColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SpendDigestSetting() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var frequency by remember { mutableStateOf(SpendDigestFrequency.WEEKLY) }
    var showFrequencyPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore.data.first()
        frequency = runCatching {
            SpendDigestFrequency.valueOf(prefs[spendDigestFrequencyStoreKey] ?: "")
        }.getOrDefault(SpendDigestFrequency.WEEKLY)
    }

    fun reschedule(hour: Int, minute: Int) {
        SpendDigestScheduler.schedule(context, hour, minute, frequency)
    }

    fun persistFrequencyAndReschedule(freq: SpendDigestFrequency) {
        frequency = freq
        showFrequencyPicker = false
        coroutineScope.launch {
            context.settingsDataStore.edit {
                it[spendDigestFrequencyStoreKey] = freq.name
            }
            val prefs = context.settingsDataStore.data.first()
            val h = prefs[spendDigestHourStoreKey] ?: SPEND_DIGEST_DEFAULT_HOUR
            val m = prefs[spendDigestMinuteStoreKey] ?: SPEND_DIGEST_DEFAULT_MINUTE
            reschedule(h, m)
        }
    }

    NotificationToggleSetting(
        iconRes = R.drawable.ic_notifications,
        titleRes = R.string.spend_digest_setting_title,
        enabledKey = spendDigestEnabledStoreKey,
        timeLabelRes = R.string.spend_digest_time,
        hourKey = spendDigestHourStoreKey,
        minuteKey = spendDigestMinuteStoreKey,
        defaultHour = SPEND_DIGEST_DEFAULT_HOUR,
        defaultMinute = SPEND_DIGEST_DEFAULT_MINUTE,
        schedule = { _, h, m -> reschedule(h, m) },
        cancel = { SpendDigestScheduler.cancel(it) },
        extraContent = {
            val iconTint = contentColorFor(
                combineColors(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.surfaceVariant,
                    angle = 0.3F,
                )
            )
            Column {
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
            }
        },
    )

    if (showFrequencyPicker) {
        AlertDialog(
            onDismissRequest = { showFrequencyPicker = false },
            title = { Text(stringResource(R.string.spend_digest_frequency)) },
            text = {
                Column {
                    CheckedRow(
                        checked = frequency == SpendDigestFrequency.WEEKLY,
                        onValueChange = { persistFrequencyAndReschedule(SpendDigestFrequency.WEEKLY) },
                        text = stringResource(R.string.spend_digest_frequency_weekly),
                        description = stringResource(R.string.spend_digest_frequency_weekly_description),
                    )
                    CheckedRow(
                        checked = frequency == SpendDigestFrequency.MONTHLY,
                        onValueChange = { persistFrequencyAndReschedule(SpendDigestFrequency.MONTHLY) },
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
}
