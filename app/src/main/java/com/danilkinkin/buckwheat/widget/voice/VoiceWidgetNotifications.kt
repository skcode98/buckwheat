package com.danilkinkin.buckwheat.widget.voice

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R

object VoiceWidgetNotifications {

    const val CHANNEL_ID = "voice_widget"
    const val NOTIFICATION_ID_LISTENING = 400
    const val NOTIFICATION_ID_RESULT = 301
    const val EXTRA_REQUEST_MIC_PERMISSION = "request_mic_permission"

    // Shown while the foreground service is actively recording. Never alerts, so a
    // silent tap on the widget doesn't beep just for opening the microphone.
    fun listening(context: Context, text: String = context.getString(R.string.voice_listening)): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.voice_widget_channel_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateListening(context: Context, text: String) {
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID_LISTENING,
            listening(context, text),
        )
    }

    fun post(context: Context, title: String, text: String, contentIntent: PendingIntent? = null) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_RESULT, builder.build())
    }

    // Opens the app (with a flag to request the mic permission) so the user can grant it once.
    fun postPermissionNeeded(context: Context) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_REQUEST_MIC_PERMISSION, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            context,
            context.getString(R.string.voice_widget_permission_needed),
            context.getString(R.string.voice_widget_permission_needed_text),
            pendingIntent,
        )
    }
}
