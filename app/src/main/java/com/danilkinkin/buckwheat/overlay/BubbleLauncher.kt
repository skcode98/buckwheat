package com.danilkinkin.buckwheat.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

// Single entry point for showing/hiding the floating bubble from anywhere (Settings,
// Keyboard screen, voice widget callback). Callers must have already verified
// Settings.canDrawOverlays(context); show() is safe to call repeatedly — the service
// treats a second ACTION_SHOW as "ensure visible".
object BubbleLauncher {

    fun show(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BubbleOverlayService::class.java).apply {
                action = BubbleOverlayService.ACTION_SHOW
            },
        )
    }

    fun hide(context: Context) {
        context.startService(
            Intent(context, BubbleOverlayService::class.java).apply {
                action = BubbleOverlayService.ACTION_HIDE
            },
        )
    }
}
