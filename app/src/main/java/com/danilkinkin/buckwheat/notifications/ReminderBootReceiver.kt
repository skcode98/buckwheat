package com.danilkinkin.buckwheat.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.danilkinkin.buckwheat.di.reminderEnabledStoreKey
import com.danilkinkin.buckwheat.di.reminderHourStoreKey
import com.danilkinkin.buckwheat.di.reminderMinuteStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
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
            } finally {
                pendingResult.finish()
            }
        }
    }
}
