package com.danilkinkin.buckwheat.widget.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

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
        ContextCompat.startForegroundService(
            context,
            Intent(context, VoiceWidgetCommitService::class.java),
        )
    }
}