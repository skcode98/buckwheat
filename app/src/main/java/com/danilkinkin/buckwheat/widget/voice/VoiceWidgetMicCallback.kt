package com.danilkinkin.buckwheat.widget.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.danilkinkin.buckwheat.MainActivity

// Started by a tap on the voice widget's mic button. Composing this action in the widget
// only builds a broadcast PendingIntent (never a foreground-service one), so it can't make
// the launcher fail to render the widget. The actual foreground (microphone) service is
// started here in response to the tap, which is a while-in-use interaction and therefore
// exempt from Android's background-start restrictions.
class VoiceWidgetMicCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        // The service only notifies via POST_NOTIFICATIONS, so a denied or never-requested
        // notification permission would silently swallow the "permission needed" notice.
        // Ask for the microphone (and notifications, on API 33+) up front in the app, which
        // is always visible, instead of relying on a notification the user can't see.
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(VoiceWidgetNotifications.EXTRA_REQUEST_MIC_PERMISSION, true)
                }
            )
            return
        }

        ContextCompat.startForegroundService(
            context,
            Intent(context, VoiceWidgetCommitService::class.java),
        )
    }
}