package com.danilkinkin.buckwheat.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.danilkinkin.buckwheat.util.DAY
import com.danilkinkin.buckwheat.util.roundToDay
import java.util.Date

// One-shot setWindow alarm that fires right after the current budget period ends and triggers
// the silent CSV auto-export. Independent from PeriodFinishScheduler (its own request code and
// receiver); a later schedule() call for the same request code replaces the previous alarm.
object AutoExportScheduler {
    const val ACTION_AUTO_EXPORT = "com.danilkinkin.buckwheat.AUTO_EXPORT"
    const val NOTIFICATION_ID = 107
    private const val REQUEST_CODE = 107
    private const val WINDOW_MILLIS = 10 * 60 * 1000L

    // The repository persists the finish date as 23:59:59.999 of the finish day; apply the
    // same adjustment so the alarm fires exactly when the period ends.
    fun periodEnd(finishDate: Date): Date =
        Date(roundToDay(finishDate).time + DAY - 1000)

    fun schedule(context: Context, finishDate: Date) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            periodEnd(finishDate).time,
            WINDOW_MILLIS,
            buildPendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AutoExportReceiver::class.java).apply {
            action = ACTION_AUTO_EXPORT
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}