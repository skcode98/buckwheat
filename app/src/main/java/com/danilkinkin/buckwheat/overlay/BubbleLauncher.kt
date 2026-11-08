package com.danilkinkin.buckwheat.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetNotifications

// Single entry point for showing/hiding the floating bubble from anywhere (Settings,
// Keyboard screen, voice widget callback). Callers must have already verified
// Settings.canDrawOverlays(context); show() is safe to call repeatedly — the service
// treats a second ACTION_SHOW as "ensure visible".
object BubbleLauncher {

    fun show(context: Context) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(VoiceWidgetNotifications.EXTRA_REQUEST_MIC_PERMISSION, true)
                    putExtra(EXTRA_SHOW_AFTER_MIC_PERMISSION, true)
                }
            )
            return
        }

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

    const val EXTRA_SHOW_AFTER_MIC_PERMISSION =
        "com.danilkinkin.buckwheat.overlay.SHOW_AFTER_MIC_PERMISSION"
}
