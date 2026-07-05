package com.danilkinkin.buckwheat.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.recurring.RecurringManager
import com.danilkinkin.buckwheat.sync.SyncManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = runBlocking {
                context.settingsDataStore.data.first()
            }
            val reminderEnabled = prefs[com.danilkinkin.buckwheat.di.reminderEnabledStoreKey] ?: false
            if (reminderEnabled) {
                val hour = prefs[com.danilkinkin.buckwheat.di.reminderHourStoreKey] ?: 20
                val minute = prefs[com.danilkinkin.buckwheat.di.reminderMinuteStoreKey] ?: 0
                ReminderManager.schedule(context, hour, minute)
            }
            val syncEnabled = prefs[com.danilkinkin.buckwheat.di.syncEnabledStoreKey] ?: false
            if (syncEnabled) {
                val hour = prefs[com.danilkinkin.buckwheat.di.syncHourStoreKey] ?: 22
                val minute = prefs[com.danilkinkin.buckwheat.di.syncMinuteStoreKey] ?: 0
                SyncManager.schedule(context, hour, minute)
            }

            RecurringManager.schedule(context)
        }
    }
}
