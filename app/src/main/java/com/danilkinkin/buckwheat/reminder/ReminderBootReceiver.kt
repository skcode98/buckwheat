package com.danilkinkin.buckwheat.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.intPreferencesKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.di.dailySpendOverviewStoreKey
import com.danilkinkin.buckwheat.di.weeklyOverviewStoreKey
import com.danilkinkin.buckwheat.di.monthlyExportStoreKey
import com.danilkinkin.buckwheat.di.monthlyOverviewStoreKey
import com.danilkinkin.buckwheat.di.factsInsightsStoreKey
import com.danilkinkin.buckwheat.di.goalsReminderStoreKey
import com.danilkinkin.buckwheat.notifications.NotificationScheduler
import com.danilkinkin.buckwheat.notifications.NotificationType
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

            val notificationTypes = listOf(
                NotificationType.DAILY_SPEND_OVERVIEW to dailySpendOverviewStoreKey,
                NotificationType.WEEKLY_OVERVIEW to weeklyOverviewStoreKey,
                NotificationType.MONTHLY_EXPORT to monthlyExportStoreKey,
                NotificationType.MONTHLY_OVERVIEW to monthlyOverviewStoreKey,
                NotificationType.FACTS_INSIGHTS to factsInsightsStoreKey,
                NotificationType.GOALS_REMINDER to goalsReminderStoreKey,
            )
            notificationTypes.forEach { (type, key) ->
                if (prefs[key] ?: false) {
                    val hour = prefs[intPreferencesKey("${type.name}_hour")] ?: type.defaultHour
                    val minute = prefs[intPreferencesKey("${type.name}_minute")] ?: type.defaultMinute
                    when (type) {
                        NotificationType.DAILY_SPEND_OVERVIEW ->
                            NotificationScheduler.scheduleDailyOverview(context, hour, minute)
                        NotificationType.WEEKLY_OVERVIEW ->
                            NotificationScheduler.scheduleWeeklyOverview(context, hour, minute)
                        NotificationType.MONTHLY_EXPORT ->
                            NotificationScheduler.scheduleMonthlyExport(context, hour, minute)
                        NotificationType.MONTHLY_OVERVIEW ->
                            NotificationScheduler.scheduleMonthlyOverview(context, hour, minute)
                        NotificationType.FACTS_INSIGHTS ->
                            NotificationScheduler.scheduleFactsInsights(context, hour, minute)
                        NotificationType.GOALS_REMINDER ->
                            NotificationScheduler.scheduleGoalsReminder(context, hour, minute)
                    }
                }
            }

            RecurringManager.schedule(context)
        }
    }
}
