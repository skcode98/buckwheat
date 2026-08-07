package com.danilkinkin.buckwheat.widget.voice

import android.app.PendingIntent
import android.appwidget.AppWidgetHost
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Regression guard for the "can't show content" defect. Glance runs provideGlance as a
// WorkManager CoroutineWorker (see GlanceAppWidget.update -> SessionManager), so the test
// process must initialize WorkManager for the worker to run; otherwise composition never
// executes and the defect is invisible. WorkManagerTestInitHelper provides a synchronous
// config so the worker runs inline within widget.update().
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VoiceWidgetRenderTest {

    // Strict widget: rethrow any Glance composition error instead of painting Glance's
    // default "can't show content" error layout. This turns a false-green test into a
    // real assertion that VoiceWidgetContent composes cleanly end to end.
    private class StrictVoiceWidget : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) {
            provideContent {
                GlanceTheme {
                    VoiceWidgetContent()
                }
            }
        }

        override fun onCompositionError(
            context: Context,
            glanceId: GlanceId,
            appWidgetId: Int,
            throwable: Throwable,
        ) {
            throw throwable
        }
    }

    @Before
    fun initializeWorkManager() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Install a synchronous WorkManager config so Glance's provideGlance worker runs
        // inline within widget.update(). Guards against "already initialized" on re-runs.
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        } catch (e: IllegalStateException) {
            // WorkManager already initialized (auto-init / prior test); reuse it.
        }
    }

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

    @Test
    fun rendersWithoutCompositionException() = runTest {
        val widget = StrictVoiceWidget()

        // Mirrors what the AppWidgetManager does when the launcher first paints the widget:
        // allocate an id and ask Glance to compose its content for that id.
        val appWidgetId = AppWidgetHost(context, 1).allocateAppWidgetId()
        val glanceId: GlanceId = AppWidgetId(appWidgetId)

        // If provideGlance throws (e.g. WorkManager not initialized, or any composition
        // error), onCompositionError rethrows -> this line fails the test with the real stack.
        widget.update(context, glanceId)
    }
}
