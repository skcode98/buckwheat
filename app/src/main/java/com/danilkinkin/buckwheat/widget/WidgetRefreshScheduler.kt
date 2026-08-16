package com.danilkinkin.buckwheat.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

// Re-requests widget data once a day, shortly after midnight, so placed widgets pick up the
// new day without the app being opened (e.g. the voice widget's chart series advances and its
// "left today" resets once today's spend is 0). Deliberately a re-read + re-render only: the
// daily budget fold lives in SpendsViewModel.runChangeDayAction (ASK mode needs a UI dialog,
// and the fold is mutex-guarded against double-charging recurring payments), so that logic is
// NOT replicated here.
//
// Uses setWindow (not setRepeating) because on modern Android setRepeating is inexact with a
// huge window (~18h), which could defer the refresh far past midnight. setWindow bounds the
// delay to WINDOW_MILLIS past the trigger without requiring SCHEDULE_EXACT_ALARM. Because
// setWindow is one-shot, the receiver re-arms the next day's alarm after each fire.
object WidgetRefreshScheduler {
    const val ACTION_REFRESH = "com.danilkinkin.buckwheat.WIDGET_REFRESH"

    private const val REQUEST_CODE = 200
    private const val REFRESH_HOUR = 0
    private const val REFRESH_MINUTE = 5
    private const val WINDOW_MILLIS = 10 * 60 * 1000L

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = nextTriggerAtMillis()

        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            WINDOW_MILLIS,
            buildPendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun nextTriggerAtMillis(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, REFRESH_HOUR)
            set(Calendar.MINUTE, REFRESH_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = ACTION_REFRESH
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
