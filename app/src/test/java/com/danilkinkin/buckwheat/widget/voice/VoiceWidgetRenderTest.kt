package com.danilkinkin.buckwheat.widget.voice

import android.app.PendingIntent
import android.appwidget.AppWidgetHost
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Reproduces the real render path: building the voice widget's content calls
// PendingIntent.getForegroundService for the mic button. If that (or any other
// composition step) throws, Glance triggers onCompositionError and the launcher
// shows "can't show content".
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VoiceWidgetRenderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

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

    @Test
    fun rendersWithoutCompositionException() = runTest {
        val widget = VoiceWidget()

        // Mirrors what the AppWidgetManager does when the launcher first paints the widget:
        // allocate an id and ask Glance to compose its content for that id.
        val appWidgetId = AppWidgetHost(context, 1).allocateAppWidgetId()
        val glanceId: GlanceId = AppWidgetId(appWidgetId)

        // Any composition error thrown by VoiceWidgetContent surfaces here; fail loudly
        // instead of letting it silently turn into a "can't show content" toast.
        runCatching { widget.update(context, glanceId) }
            .onSuccess { /* rendered cleanly */ }
            .onFailure { fail("VoiceWidget failed to render: ${it.stackTraceToString()}") }
    }
}