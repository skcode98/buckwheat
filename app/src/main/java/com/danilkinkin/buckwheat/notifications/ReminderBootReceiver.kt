package com.danilkinkin.buckwheat.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_HOUR
import com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.di.SPEND_DIGEST_DEFAULT_HOUR
import com.danilkinkin.buckwheat.di.SPEND_DIGEST_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.di.recurringAlertEnabledStoreKey
import com.danilkinkin.buckwheat.di.recurringAlertHourStoreKey
import com.danilkinkin.buckwheat.di.recurringAlertMinuteStoreKey
import com.danilkinkin.buckwheat.di.reminderEnabledStoreKey
import com.danilkinkin.buckwheat.di.reminderHourStoreKey
import com.danilkinkin.buckwheat.di.reminderMinuteStoreKey
import com.danilkinkin.buckwheat.di.spendDigestEnabledStoreKey
import com.danilkinkin.buckwheat.di.spendDigestFrequencyStoreKey
import com.danilkinkin.buckwheat.di.spendDigestHourStoreKey
import com.danilkinkin.buckwheat.di.spendDigestMinuteStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.widget.WidgetRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.settingsDataStore.data.first()
                val enabled = prefs[reminderEnabledStoreKey] ?: false
                if (enabled) {
                    val hour = prefs[reminderHourStoreKey] ?: DAILY_REMINDER_DEFAULT_HOUR
                    val minute = prefs[reminderMinuteStoreKey] ?: DAILY_REMINDER_DEFAULT_MINUTE
                    DailyBudgetReminderScheduler.schedule(context, hour, minute)
                }
                val recurringEnabled = prefs[recurringAlertEnabledStoreKey] ?: false
                if (recurringEnabled) {
                    val hour = prefs[recurringAlertHourStoreKey] ?: RECURRING_ALERT_DEFAULT_HOUR
                    val minute = prefs[recurringAlertMinuteStoreKey] ?: RECURRING_ALERT_DEFAULT_MINUTE
                    RecurringPaymentAlertScheduler.schedule(context, hour, minute)
                }
                val digestEnabled = prefs[spendDigestEnabledStoreKey] ?: false
                if (digestEnabled) {
                    val hour = prefs[spendDigestHourStoreKey] ?: SPEND_DIGEST_DEFAULT_HOUR
                    val minute = prefs[spendDigestMinuteStoreKey] ?: SPEND_DIGEST_DEFAULT_MINUTE
                    val frequency = runCatching {
                        SpendDigestFrequency.valueOf(
                            prefs[spendDigestFrequencyStoreKey] ?: ""
                        )
                    }.getOrDefault(SpendDigestFrequency.WEEKLY)
                    SpendDigestScheduler.schedule(context, hour, minute, frequency)
                }
                WidgetRefreshScheduler.schedule(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
