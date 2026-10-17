package com.danilkinkin.buckwheat.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.keyboard.VoiceAiResult
import com.danilkinkin.buckwheat.keyboard.VoiceInputResult
import com.danilkinkin.buckwheat.keyboard.parseVoiceInputWithAi
import com.danilkinkin.buckwheat.keyboard.parseVoiceInputs
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetReceiver
import com.danilkinkin.buckwheat.widget.voice.voiceResultsToTransactions
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

// Foreground (microphone) service owning the floating voice bubble: adds the overlay view
// via WindowManager and runs the whole "listen -> parse -> commit" flow in-process,
// reusing the voice widget's pure helpers. Started only from explicit user actions
// (Settings, Keyboard screen, voice widget tap) while the app is visible, which is the
// same while-in-use exemption the widget's commit service relies on.
@AndroidEntryPoint
class BubbleOverlayService : Service() {

    @Inject
    lateinit var databaseRepository: SpendsRepository

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var bubbleView: ComposeView? = null
    private var bubblePending = false
    private var stopped = false
    @Volatile
    private var cachedCurrency: ExtendCurrency? = null
    private val _uiState = MutableStateFlow(BubbleUiState(BubbleFeedbackState.IDLE))
    private val uiState: StateFlow<BubbleUiState> = _uiState
    private var sessionRunning = false
    private var resetJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, ongoingNotification())

        // Pre-load the currency so the non-suspend captionAdded() can format amounts
        // without touching DataStore. Falls back to ExtendCurrency.none() until loaded.
        scope.launch {
            cachedCurrency = runCatching { databaseRepository.getCurrency().first() }.getOrNull()
        }

        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> dismissBubble()
            else -> {
                // Service recreated by the system: START_NOT_STICKY, but guard anyway.
                if (!Settings.canDrawOverlays(this)) {
                    stopSelfAndExit()
                } else if (bubbleView == null) {
                    showBubble()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        removeDismissTarget()
        removeBubbleView()
        scope.cancel()
        super.onDestroy()
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            // Permission revoked since the launcher call — never add a view without it.
            stopSelfAndExit()
            return
        }
        if (bubbleView != null || bubblePending) return

        // lastPosition() is a DataStore read (suspend), so resolve it off the current call.
        bubblePending = true
        scope.launch {
            try {
                val position = runCatching {
                    bubbleSettings(this@BubbleOverlayService).lastPosition().first()
                }.getOrNull()
                if (stopped) return@launch
                addBubbleView(position)
            } finally {
                bubblePending = false
            }
        }
    }

    private fun addBubbleView(position: Pair<Float, Float>?) {
        if (stopped || bubbleView != null) return

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                BuckwheatTheme {
                    val state by uiState.collectAsState()
                    var offsetX by remember { mutableFloatStateOf(0f) }
                    var offsetY by remember { mutableFloatStateOf(0f) }
                    val velocityTracker = remember { VelocityTracker() }

                    Box(
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        offsetX = 0f
                                        offsetY = 0f
                                        velocityTracker.resetTracking()
                                        onDragStart()
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        offsetX += dragAmount.x
                                        offsetY += dragAmount.y
                                        onDragMove(offsetX, offsetY)
                                    },
                                    onDragEnd = {
                                        onDragEnd(offsetX, offsetY, velocityTracker.calculateVelocity().y)
                                    },
                                    onDragCancel = { onDragEnd(offsetX, offsetY, velocity = 0f) },
                                )
                            },
                    ) {
                        BubbleContent(
                            state = state,
                            onTap = { onBubbleTap() },
                        )
                    }
                }
            }
        }

        val metrics = resources.displayMetrics
        val bubblePx = (BUBBLE_SIZE_DP * metrics.density).toInt()
        val marginPx = (8 * metrics.density).toInt()
        val x = position?.first?.toInt() ?: marginPx
        val y = position?.second?.toInt() ?: (metrics.heightPixels / 3)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            this.x = x
            this.y = clampY(
                currentY = y.toFloat(),
                screenHeight = metrics.heightPixels.toFloat(),
                bubbleHeight = bubblePx.toFloat(),
                topInsetPx = marginPx.toFloat(),
                bottomInsetPx = marginPx.toFloat(),
            ).toInt()
        }

        windowManager.addView(view, params)
        bubbleView = view
    }

    private fun onBubbleTap() {
        if (!acceptsTap(uiState.value.state)) return
        if (sessionRunning) return

        if (
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(
                        com.danilkinkin.buckwheat.widget.voice.VoiceWidgetNotifications.EXTRA_REQUEST_MIC_PERMISSION,
                        true,
                    )
                }
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            _uiState.value = BubbleUiState(
                BubbleFeedbackState.ERROR,
                getString(R.string.voice_unavailable),
            )
            scheduleAutoReset()
            return
        }

        sessionRunning = true
        scope.launch {
            try {
                runVoiceSession(ServiceSessionHost(), ::onSessionState)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = BubbleUiState(BubbleFeedbackState.ERROR, ServiceSessionHost().captionSaveFailed())
                scheduleAutoReset()
            } finally {
                sessionRunning = false
            }
        }
    }

    private fun onSessionState(state: BubbleUiState) {
        _uiState.value = state
        when (state.state) {
            BubbleFeedbackState.ADDED -> {
                VoiceWidgetReceiver.requestUpdateData(this)
                scheduleAutoReset()
            }
            BubbleFeedbackState.ERROR -> scheduleAutoReset()
            else -> {}
        }
    }

    private fun scheduleAutoReset() {
        val delay = autoResetDelayMs(_uiState.value.state) ?: return
        resetJob?.cancel()
        resetJob = scope.launch {
            kotlinx.coroutines.delay(delay)
            if (autoResetDelayMs(_uiState.value.state) != null) {
                _uiState.value = BubbleUiState(BubbleFeedbackState.IDLE)
            }
        }
    }

    // Real-world implementation of the fakeable session host. Parsing mirrors the widget
    // pipeline exactly: AI first, silent regex fallback.
    private inner class ServiceSessionHost : BubbleSessionHost {

        override suspend fun hasBudget(): Boolean =
            runCatching { databaseRepository.getFinishPeriodDate().first() }.getOrNull() != null

        override suspend fun recognize(): BubbleRecognitionOutcome =
            suspendCancellableCoroutine { continuation ->
                val recognizer = SpeechRecognizer.createSpeechRecognizer(this@BubbleOverlayService)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: android.os.Bundle) {
                        val transcript = results
                            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        recognizer.destroy()
                        if (continuation.isActive) {
                            continuation.resume(
                                BubbleRecognitionOutcome(transcript, SpeechRecognizer.ERROR_NO_MATCH),
                            )
                        }
                    }

                    override fun onError(error: Int) {
                        recognizer.destroy()
                        if (continuation.isActive) {
                            continuation.resume(BubbleRecognitionOutcome(null, error))
                        }
                    }

                    override fun onReadyForSpeech(params: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                recognizer.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }
                )
                continuation.invokeOnCancellation { recognizer.destroy() }
            }

        override suspend fun toTransactions(transcript: String): List<com.danilkinkin.buckwheat.data.entities.Transaction> {
            val aiResult = parseVoiceInputWithAi(this@BubbleOverlayService, transcript)
            val results: List<VoiceInputResult> = when (aiResult) {
                is VoiceAiResult.Success -> aiResult.results
                is VoiceAiResult.Failure -> parseVoiceInputs(transcript)
                VoiceAiResult.NotConfigured -> parseVoiceInputs(transcript)
            }
            return voiceResultsToTransactions(results)
        }

        override suspend fun commit(transactions: List<com.danilkinkin.buckwheat.data.entities.Transaction>) {
            transactions.forEach { databaseRepository.addSpent(it) }
        }

        override fun captionNoBudget(): String = getString(R.string.voice_widget_no_budget)

        override fun captionRecognitionError(errorCode: Int): String = getString(
            when (errorCode) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> R.string.voice_no_speech

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
                else -> R.string.voice_couldnt_understand
            },
        )

        override fun captionParseFailed(): String = getString(R.string.voice_couldnt_understand)

        override fun captionSaveFailed(): String = getString(R.string.bubble_save_failed)

        override fun captionAdded(
            transactions: List<com.danilkinkin.buckwheat.data.entities.Transaction>,
        ): String {
            val currency = cachedCurrency ?: ExtendCurrency.none()
            return if (transactions.size == 1) {
                val t = transactions.first()
                val formatted = numberFormat(this@BubbleOverlayService, t.value, currency)
                if (t.comment.isBlank()) {
                    getString(R.string.voice_widget_added, formatted)
                } else {
                    getString(R.string.voice_widget_added_comment, formatted, t.comment)
                }
            } else {
                getString(R.string.voice_widget_added_many, transactions.size)
            }
        }
    }

    private fun ongoingNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, "bubble_overlay")
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(getString(R.string.bubble_channel_name))
            .setContentText(getString(R.string.bubble_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    private fun removeBubbleView() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    private fun stopSelfAndExit() {
        stopped = true
        removeDismissTarget()
        removeBubbleView()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var dismissTargetView: ComposeView? = null

    private fun currentParams(): WindowManager.LayoutParams? =
        bubbleView?.layoutParams as? WindowManager.LayoutParams

    private fun onDragStart() {
        val params = currentParams() ?: return
        dragStartX = params.x
        dragStartY = params.y
        showDismissTarget()
    }

    private fun onDragMove(dragDx: Float, dragDy: Float) {
        val params = currentParams() ?: return
        val metrics = resources.displayMetrics
        val bubblePx = (BUBBLE_SIZE_DP * metrics.density)
        val marginPx = (8 * metrics.density)
        params.x = (dragStartX + dragDx).toInt()
        params.y = clampY(
            currentY = dragStartY + dragDy,
            screenHeight = metrics.heightPixels.toFloat(),
            bubbleHeight = bubblePx,
            topInsetPx = marginPx,
            bottomInsetPx = marginPx,
        ).toInt()
        runCatching { windowManager.updateViewLayout(bubbleView, params) }
    }

    private fun onDragEnd(dragDx: Float, dragDy: Float, velocity: Float) {
        removeDismissTarget()
        val params = currentParams() ?: return
        val metrics = resources.displayMetrics
        val bubblePx = (BUBBLE_SIZE_DP * metrics.density)
        val marginPx = (8 * metrics.density)

        if (isFlingDismiss(velocity, FLING_THRESHOLD_DP_PER_SEC * metrics.density)) {
            dismissBubble()
            return
        }

        // Dismiss-target hit test: the target is centered at screen bottom; the bubble's
        // final on-screen position is the clamped drag end (cross-window coordinates are
        // not comparable, so use the raw drag end position on screen instead).
        val endY = clampY(
            currentY = dragStartY + dragDy,
            screenHeight = metrics.heightPixels.toFloat(),
            bubbleHeight = bubblePx,
            topInsetPx = marginPx,
            bottomInsetPx = marginPx,
        )
        val centerX = dragStartX + dragDx + bubblePx / 2f
        val targetTop = metrics.heightPixels - (120 * metrics.density)
        val targetLeft = metrics.widthPixels / 2f - (60 * metrics.density)
        val targetRight = metrics.widthPixels / 2f + (60 * metrics.density)
        if (
            isOverDismissTarget(
                x = centerX,
                y = endY + bubblePx / 2f,
                targetLeft = targetLeft,
                targetTop = targetTop,
                targetRight = targetRight,
                targetBottom = metrics.heightPixels.toFloat(),
            )
        ) {
            dismissBubble()
            return
        }

        params.x = snapX(
            currentX = (dragStartX + dragDx).toFloat(),
            screenWidth = metrics.widthPixels.toFloat(),
            bubbleWidth = bubblePx,
            marginPx = marginPx,
        ).toInt()
        params.y = endY.toInt()
        runCatching { windowManager.updateViewLayout(bubbleView, params) }
        scope.launch {
            bubbleSettings(this@BubbleOverlayService).savePosition(params.x.toFloat(), params.y.toFloat())
        }
    }

    // Small X target pinned bottom-center, visible only while dragging. It is a separate
    // overlay window so the WRAP_CONTENT bubble window never receives its touches;
    // dismissal is decided from the drag's clamped end coordinates in onDragEnd. Removed
    // when the drag ends, on dismiss, and on service teardown.
    private fun showDismissTarget() {
        if (dismissTargetView != null) return
        val metrics = resources.displayMetrics
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                BuckwheatTheme {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = getString(R.string.bubble_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (16 * metrics.density).toInt()
        }
        runCatching { windowManager.addView(view, params) }
        dismissTargetView = view
    }

    private fun removeDismissTarget() {
        dismissTargetView?.let { runCatching { windowManager.removeView(it) } }
        dismissTargetView = null
    }

    private fun dismissBubble() {
        stopped = true
        scope.launch {
            runCatching {
                val params = currentParams()
                if (params != null) {
                    bubbleSettings(this@BubbleOverlayService)
                        .savePosition(params.x.toFloat(), params.y.toFloat())
                }
            }
            removeDismissTarget()
            removeBubbleView()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        const val ACTION_SHOW = "com.danilkinkin.buckwheat.overlay.SHOW"
        const val ACTION_HIDE = "com.danilkinkin.buckwheat.overlay.HIDE"
        private const val NOTIFICATION_ID = 410
        private const val FLING_THRESHOLD_DP_PER_SEC = 500f
    }
}
