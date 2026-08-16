package com.danilkinkin.buckwheat.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object OnTrackAlertScheduler {
    const val ACTION_ON_TRACK_ALERT = "com.danilkinkin.buckwheat.ON_TRACK_ALERT"
    const val NOTIFICATION_ID = 102
    private const val REQUEST_CODE = 102
    private const val WINDOW_MILLIS = 10 * 60 * 1000L

    // Uses setWindow (not setRepeating) because on modern Android setRepeating is inexact with
    // a huge window (~18h), which could defer the alert far past its configured time. setWindow
    // bounds the delay to WINDOW_MILLIS past the trigger without requiring SCHEDULE_EXACT_ALARM.
    // Because setWindow is one-shot, the receiver re-arms the next day's alert after each fire.
    fun schedule(
        context: Context,
        hour: Int = DAILY_REMINDER_DEFAULT_HOUR,
        minute: Int = DAILY_REMINDER_DEFAULT_MINUTE,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
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
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, OnTrackAlertReceiver::class.java).apply {
            action = ACTION_ON_TRACK_ALERT
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}