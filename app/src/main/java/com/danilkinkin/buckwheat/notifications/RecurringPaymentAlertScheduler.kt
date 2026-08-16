package com.danilkinkin.buckwheat.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_HOUR
import com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_MINUTE
import java.util.Calendar

object RecurringPaymentAlertScheduler {
    const val ACTION_RECURRING_ALERT = "com.danilkinkin.buckwheat.RECURRING_PAYMENT_ALERT"
    const val NOTIFICATION_ID = 103
    private const val REQUEST_CODE = 103
    private const val WINDOW_MILLIS = 10 * 60 * 1000L

    // One-shot setWindow alarm (same approach as the daily budget reminder) so the alert fires
    // within ~10 minutes of the configured time on modern Android without SCHEDULE_EXACT_ALARM.
    // The receiver re-arms the next day's alarm after each fire.
    fun schedule(
        context: Context,
        hour: Int = RECURRING_ALERT_DEFAULT_HOUR,
        minute: Int = RECURRING_ALERT_DEFAULT_MINUTE,
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
        val intent = Intent(context, RecurringPaymentAlertReceiver::class.java).apply {
            action = ACTION_RECURRING_ALERT
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
