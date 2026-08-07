package com.danilkinkin.buckwheat.widget.voice

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Guards the voice widget's foreground-service tap path. Glance composition itself is
// verified on-device (emulator E2E): Robolectric's widget.update() does not run
// provideGlance, so a render test here would be a false-green (it silently passed while
// the on-device widget threw "Error: No set" from LocalContentColor).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceWidgetRenderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun foregroundServicePendingIntentBuilds() {
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            Intent(context, VoiceWidgetCommitService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        assertNotNull(pendingIntent)
    }
}
