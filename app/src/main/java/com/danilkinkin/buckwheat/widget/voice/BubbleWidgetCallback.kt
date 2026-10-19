package com.danilkinkin.buckwheat.widget.voice

import android.content.Context
import android.provider.Settings
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.danilkinkin.buckwheat.overlay.BubbleLauncher
import com.danilkinkin.buckwheat.overlay.bubbleSettings
import kotlinx.coroutines.flow.first

// Voice widget's bubble button: summons the floating voice bubble overlay. Same
// while-in-use FGS-start exemption as VoiceWidgetMicCallback (tap on a visible widget).
// Gated on the Settings master kill-switch AND the overlay permission; if either is
// missing the bubble cannot appear — silently no-op. The Settings master switch is where
// the feature is enabled and the permission is granted.
class BubbleWidgetCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        if (!bubbleSettings(context).isEnabled().first()) return
        if (!Settings.canDrawOverlays(context)) return
        BubbleLauncher.show(context)
    }
}
