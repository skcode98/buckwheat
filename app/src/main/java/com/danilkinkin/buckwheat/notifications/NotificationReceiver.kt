package com.danilkinkin.buckwheat.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val type = NotificationType.entries.firstOrNull { it.action == action } ?: return

        when (type) {
            NotificationType.MONTHLY_EXPORT,
            NotificationType.MONTHLY_OVERVIEW -> {
                rescheduleMonthly(context, type)
            }
            else -> {}
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val detailIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_NOTIFICATION_TYPE, type.name)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            type.requestCode,
            detailIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, type.channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(type.getNotificationTitle())
            .setContentText(type.getNotificationText())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(type.requestCode, notification)
    }

    private fun rescheduleMonthly(context: Context, type: NotificationType) {
        val nextCalendar = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.MONTH, 1)
            set(java.util.Calendar.DAY_OF_MONTH, getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
            set(java.util.Calendar.HOUR_OF_DAY, 18)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val alarmIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = type.action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            type.requestCode,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        alarmManager.setExactAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            nextCalendar.timeInMillis,
            pendingIntent,
        )
    }
}
