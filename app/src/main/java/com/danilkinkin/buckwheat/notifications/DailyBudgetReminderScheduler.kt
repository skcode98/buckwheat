package com.danilkinkin.buckwheat.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

const val DAILY_REMINDER_DEFAULT_HOUR = 20
const val DAILY_REMINDER_DEFAULT_MINUTE = 0

object DailyBudgetReminderScheduler {
    const val ACTION_DAILY_REMINDER = "com.danilkinkin.buckwheat.DAILY_BUDGET_REMINDER"
    const val NOTIFICATION_ID = 100
    private const val REQUEST_CODE = 100
    private const val WINDOW_MILLIS = 10 * 60 * 1000L

    // Uses setWindow (not setRepeating) because on modern Android setRepeating is inexact with a
    // huge window (~18h), which could defer the daily reminder far past its configured time.
    // setWindow bounds the delay to WINDOW_MILLIS past the trigger without requiring
    // SCHEDULE_EXACT_ALARM. Because setWindow is one-shot, the receiver re-arms the next day's
    // alarm after each fire.
    fun schedule(
        context: Context,
        hour: Int = DAILY_REMINDER_DEFAULT_HOUR,
        minute: Int = DAILY_REMINDER_DEFAULT_MINUTE,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            WINDOW_MILLIS,
            buildPendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyBudgetReminderReceiver::class.java).apply {
            action = ACTION_DAILY_REMINDER
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
