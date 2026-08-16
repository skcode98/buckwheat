package com.danilkinkin.buckwheat.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.danilkinkin.buckwheat.util.DAY
import com.danilkinkin.buckwheat.util.roundToDay
import java.util.Date

object PeriodFinishScheduler {
    const val ACTION_PERIOD_FINISH = "com.danilkinkin.buckwheat.PERIOD_FINISH"
    const val NOTIFICATION_ID = 106
    private const val REQUEST_CODE = 106
    private const val WINDOW_MILLIS = 10 * 60 * 1000L

    // The repository persists the finish date as 23:59:59.999 of the finish day; apply the
    // same adjustment so the alarm fires exactly when the period ends.
    fun periodEnd(finishDate: Date): Date =
        Date(roundToDay(finishDate).time + DAY - 1000)

    // One-shot setWindow alarm for the end of the current period. A later schedule() call for
    // the same request code silently replaces the previous alarm (setBudget/changeBudget).
    fun schedule(context: Context, finishDate: Date) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            periodEnd(finishDate).time,
            WINDOW_MILLIS,
            buildPendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PeriodFinishReceiver::class.java).apply {
            action = ACTION_PERIOD_FINISH
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
