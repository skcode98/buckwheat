package com.danilkinkin.buckwheat.widget.voice

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.keyboard.VoiceAiResult
import com.danilkinkin.buckwheat.keyboard.parseVoiceInputWithAi
import com.danilkinkin.buckwheat.keyboard.parseVoiceInputs
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.parseAmountToBigDecimal
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

// Foreground (microphone) service started by a tap on the voice widget's mic button. Runs the
// whole "listen -> parse -> commit" flow without opening the app. Starting a microphone-type
// foreground service from widget interaction is exempt from Android's background-start
// restrictions, so this is a plain onStartCommand service.
@AndroidEntryPoint
class VoiceWidgetCommitService : Service() {

    @Inject
    lateinit var databaseRepository: SpendsRepository

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var handling = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (handling) return START_NOT_STICKY
        handling = true

        val context = applicationContext

        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            VoiceWidgetNotifications.postPermissionNeeded(context)
            stopSelfAndExit()
            return START_NOT_STICKY
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            VoiceWidgetNotifications.post(
                context,
                context.getString(R.string.voice_input),
                context.getString(R.string.voice_unavailable),
            )
            stopSelfAndExit()
            return START_NOT_STICKY
        }

        startForeground(
            VoiceWidgetNotifications.NOTIFICATION_ID_LISTENING,
            VoiceWidgetNotifications.listening(context),
        )

        scope.launch {
            val finishDate = runCatching { databaseRepository.getFinishPeriodDate().first() }.getOrNull()
            if (finishDate == null) {
                setVoiceFeedbackState(context, VoiceFeedbackState.IDLE)
                VoiceWidgetNotifications.post(
                    context,
                    context.getString(R.string.voice_widget_no_budget),
                    context.getString(R.string.voice_widget_no_budget_text),
                )
                stopSelfAndExit()
                return@launch
            }

            val outcome = recognize(context)
            val transcript = outcome.transcript
            if (transcript != null) {
                VoiceWidgetNotifications.updateListening(
                    context,
                    context.getString(R.string.voice_processing),
                )
                setVoiceFeedbackState(context, VoiceFeedbackState.PROCESSING)
                commit(context, transcript)
            } else {
                setVoiceFeedbackState(context, VoiceFeedbackState.IDLE)
                VoiceWidgetNotifications.post(
                    context,
                    context.getString(R.string.voice_widget_failed),
                    errorMessage(context, outcome.errorCode),
                )
            }
            stopSelfAndExit()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private class RecognitionOutcome(val transcript: String?, val errorCode: Int)

    // Holds the recognition session open until the recognizer delivers a result or an error.
    // The callback runs on the main thread, so this is only ever invoked from Dispatchers.Main.
    private suspend fun recognize(context: Context): RecognitionOutcome =
        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val transcript = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    recognizer.destroy()
                    if (continuation.isActive) {
                        continuation.resume(RecognitionOutcome(transcript, SpeechRecognizer.ERROR_NO_MATCH))
                    }
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (continuation.isActive) {
                        continuation.resume(RecognitionOutcome(null, error))
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
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

    private fun errorMessage(context: Context, errorCode: Int): String =
        context.getString(
            when (errorCode) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> R.string.voice_no_speech

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
                else -> R.string.voice_couldnt_understand
            }
        )

    // AI-first parsing with silent fallback to the offline parser (mirrors the in-app flow).
    private suspend fun parseTranscript(context: Context, transcript: String): List<com.danilkinkin.buckwheat.keyboard.VoiceInputResult> {
        val aiResult = parseVoiceInputWithAi(context, transcript)
        return when (aiResult) {
            is VoiceAiResult.Success -> aiResult.results
            else -> parseVoiceInputs(transcript)
        }
    }

    private suspend fun commit(context: Context, transcript: String) {
        val results = parseTranscript(context, transcript)
        val transactions = voiceResultsToTransactions(results)

        if (transactions.isEmpty()) {
            setVoiceFeedbackState(context, VoiceFeedbackState.IDLE)
            VoiceWidgetNotifications.post(
                context,
                context.getString(R.string.voice_widget_failed),
                context.getString(R.string.voice_couldnt_understand),
            )
            return
        }

        transactions.forEach { databaseRepository.addSpent(it) }
        VoiceWidgetReceiver.requestUpdateData(context)

        val currency = runCatching { databaseRepository.getCurrency().first() }.getOrNull()
            ?: ExtendCurrency.none()
        val text = if (transactions.size == 1) {
            val transaction = transactions.first()
            val formatted = numberFormat(context, transaction.value, currency)
            if (transaction.comment.isBlank()) {
                context.getString(R.string.voice_widget_added, formatted)
            } else {
                context.getString(R.string.voice_widget_added_comment, formatted, transaction.comment)
            }
        } else {
            context.getString(R.string.voice_widget_added_many, transactions.size)
        }
        VoiceWidgetNotifications.post(
            context,
            context.getString(R.string.voice_widget_result_title),
            text,
        )
        setVoiceFeedbackState(context, VoiceFeedbackState.ADDED, text)
    }

    private fun stopSelfAndExit() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}

// Maps parsed voice results to commit-ready expense transactions. Entries whose amount cannot
// be parsed or is not positive are skipped so a single bad phrase never blocks the whole batch.
internal fun voiceResultsToTransactions(
    results: List<com.danilkinkin.buckwheat.keyboard.VoiceInputResult>,
): List<Transaction> = results.mapNotNull { result ->
    val trimmed = result.amount.trim()
    // The amount parser strips non-numeric characters, so a spoken negative would silently
    // turn into a positive spend. The widget commits without a confirmation step, so reject
    // negatives explicitly here.
    if (trimmed.startsWith("-")) return@mapNotNull null
    val amount = parseAmountToBigDecimal(trimmed) ?: return@mapNotNull null
    if (amount.signum() <= 0) return@mapNotNull null
    Transaction(
        type = TransactionType.SPENT,
        value = amount,
        date = result.date,
        comment = result.comment,
    )
}
