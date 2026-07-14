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
}
