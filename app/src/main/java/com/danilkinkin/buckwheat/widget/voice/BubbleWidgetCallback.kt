package com.danilkinkin.buckwheat.widget.voice

import android.content.Context
import android.provider.Settings
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.danilkinkin.buckwheat.overlay.BubbleLauncher

// Voice widget's bubble button: summons the floating voice bubble overlay. Same
// while-in-use FGS-start exemption as VoiceWidgetMicCallback (tap on a visible widget).
// If the overlay permission is missing or revoked the bubble cannot appear — silently
// no-op; the Settings master switch is where permission is granted.
class BubbleWidgetCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        if (!Settings.canDrawOverlays(context)) return
        BubbleLauncher.show(context)
    }
}
