package com.danilkinkin.buckwheat.keyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.TUTORS
import com.danilkinkin.buckwheat.editor.EditMode
import com.danilkinkin.buckwheat.editor.EditStage
import com.danilkinkin.buckwheat.editor.EditorViewModel
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorButton
import com.danilkinkin.buckwheat.util.getFloatDivider
import com.danilkinkin.buckwheat.util.join
import com.danilkinkin.buckwheat.util.parseAmountToBigDecimal
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.tryConvertStringToNumber
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.util.Date
import java.util.Locale

val BUTTON_GAP = 6.dp

enum class KeyboardAction { PUT_NUMBER, SET_DOT, REMOVE_LAST }

private data class VoicePending(
    val amount: BigDecimal,
    val amountString: String,
    val comment: String,
    val date: Date,
)

@Composable
fun Keyboard(
    modifier: Modifier = Modifier,
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    editorViewModel: EditorViewModel = hiltViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mode by editorViewModel.mode.observeAsState(EditMode.ADD)
    val currentRawSpent by editorViewModel.rawSpentValue.observeAsState("")
    var debugProgress by remember { mutableStateOf(0) }

    var isListening by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    // Last AI failure reason (null when the AI parser succeeded or no key is configured).
    // Shown in the confirmation dialog and as the status when the offline parser also fails.
    var voiceAiError by remember { mutableStateOf<String?>(null) }
    // Monotonic session id: each time a recognition session starts it is bumped, so a
    // result (or AI response) that arrives after the user started a newer session can be
    // detected as stale and discarded instead of committing a second transaction.
    var voiceSession by remember { mutableStateOf(0L) }
    // Voice records are not committed automatically anymore: the parsed records are
    // parked here and a preview dialog lets the user confirm, modify, or skip them.
    // The list normally has one element; batch transcripts ("tea 20, lunch 150") may
    // yield several.
    var voicePending by remember { mutableStateOf<List<VoicePending>?>(null) }

    val commitVoiceRecords: (List<VoicePending>) -> Unit = { records ->
        if (mode == EditMode.EDIT) {
            // Voice input while editing must replace the edited transaction
            // (mirroring the Apply button), not append a new spend and leave
            // the original behind. Only the first parsed record is applied.
            val edited = editorViewModel.editedTransaction
            if (edited != null) {
                val first = records.first()
                spendsViewModel.removeSpent(edited, silent = true)
                spendsViewModel.addSpent(
                    edited.copy(
                        value = first.amount,
                        date = first.date,
                        comment = first.comment.trim(),
                        category = editorViewModel.currentCategory.value,
                    )
                )
            }
        } else {
            records.forEach { record ->
                spendsViewModel.addSpent(
                    Transaction(
                        type = TransactionType.SPENT,
                        value = record.amount,
                        date = record.date,
                        comment = record.comment,
                        category = editorViewModel.currentCategory.value,
                    )
                )
            }
            appViewModel.activateTutorial(TUTORS.OPEN_HISTORY)
        }
        editorViewModel.resetEditingSpent()
    }

    val skipVoiceRecord: () -> Unit = {
        // Discard the voice result without touching the database. When the user was
        // already editing a transaction, restore its original values instead of
        // leaving the voice-parsed amount in the editor.
        val edited = editorViewModel.editedTransaction
        if (mode == EditMode.EDIT && edited != null) {
            editorViewModel.startEditingSpent(edited)
        } else {
            editorViewModel.resetEditingSpent()
        }
    }

    val recognitionAvailable = remember {
        SpeechRecognizer.isRecognitionAvailable(context)
    }
    val speechRecognizer = remember {
        if (recognitionAvailable) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    val strVoiceInput = stringResource(R.string.voice_input)
    val strVoiceListening = stringResource(R.string.voice_listening)
    val strVoiceProcessing = stringResource(R.string.voice_processing)

    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceStatus = null
            isListening = true
            voiceSession += 1
            speechRecognizer?.startListening(speechIntent)
        } else {
            voiceStatus = context.getString(R.string.voice_permission_denied)
        }
    }

    DisposableEffect(speechRecognizer) {
        val recognizer = speechRecognizer
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                if (isProcessing) return
                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )
                if (matches.isNullOrEmpty()) {
                    voiceStatus = context.getString(R.string.voice_no_speech)
                    return
                }
                val text = matches[0]
                val session = voiceSession
                isProcessing = true
                voiceStatus = context.getString(R.string.voice_processing)
                coroutineScope.launch {
                    try {
                        var parsed: List<VoiceInputResult> = emptyList()
                        var aiError: String? = null
                        when (val ai = parseVoiceInputWithAi(context, text)) {
                            is VoiceAiResult.Success -> parsed = ai.results
                            is VoiceAiResult.Failure -> {
                                Log.w(
                                    "VoiceAI",
                                    "In-app AI parse failed for \"$text\": ${ai.message}",
                                )
                                aiError = context.getString(R.string.voice_ai_error_prefix) +
                                    ai.message
                                parsed = parseVoiceInputs(text)
                            }
                            VoiceAiResult.NotConfigured -> parsed = parseVoiceInputs(text)
                        }
                        // Discard the result if the user started a newer recognition session
                        // while the AI call was still in flight.
                        if (session != voiceSession) return@launch

                        val records = parsed.mapNotNull { result ->
                            val amount = result.amount.let(::parseAmountToBigDecimal)
                            if (amount == null || amount.signum() == 0) null
                            else VoicePending(
                                amount = amount,
                                amountString = amount.stripTrailingZeros().toPlainString(),
                                comment = result.comment,
                                date = result.date,
                            )
                        }
                        if (records.isEmpty()) {
                            // Prefer surfacing the AI failure over a generic "couldn't
                            // understand" so the user can diagnose the AI path.
                            voiceStatus = aiError
                                ?: context.getString(R.string.voice_couldnt_understand)
                            return@launch
                        }

                        // Pre-fill the editor with the first record so the "Modify" action
                        // (and a single-record commit) has something to work with.
                        val first = records.first()

                        voiceStatus = null
                        voiceAiError = aiError
                        editorViewModel.rawSpentValue.value = first.amountString
                        editorViewModel.currentComment.value = first.comment
                        editorViewModel.currentDate = first.date

                        if (editorViewModel.stage.value === EditStage.IDLE) {
                            editorViewModel.startCreatingSpent()
                        }
                        editorViewModel.modifyEditingSpent(first.amount)

                        voicePending = records
                    } catch (e: Exception) {
                        Log.d("VoiceAI", "Failed to commit voice input", e)
                        if (session == voiceSession) {
                            voiceStatus = context.getString(R.string.voice_couldnt_understand)
                        }
                    } finally {
                        if (session == voiceSession) {
                            isProcessing = false
                        }
                    }
                }
            }

            override fun onError(error: Int) {
                isListening = false
                voiceStatus = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        context.getString(R.string.voice_no_speech)

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        context.getString(R.string.voice_permission_denied)

                    else -> context.getString(R.string.voice_recognition_failed)
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

        onDispose {
            recognizer?.destroy()
        }
    }

    val dispatch = rememberAppKeyboardDispatcher { action, value ->
        var isMutate = true
        var newValue = editorViewModel.rawSpentValue.value ?: ""

        when (action) {
            KeyboardAction.PUT_NUMBER -> {
                newValue += value
            }

            KeyboardAction.SET_DOT -> {
                newValue += "."
            }

            KeyboardAction.REMOVE_LAST -> {
                newValue = newValue.dropLast(1)
                Log.d("mode", mode.toString())
                Log.d("newValue", "'${newValue}'")

                if (newValue == "") {
                    if (mode === EditMode.ADD) runBlocking {
                        editorViewModel.resetEditingSpent()

                        isMutate = false
                    }
                }
            }
        }

        if (isMutate) runBlocking {
            editorViewModel.rawSpentValue.value =
                tryConvertStringToNumber(newValue).join(third = false)

            if (editorViewModel.stage.value === EditStage.IDLE) editorViewModel.startCreatingSpent()
            editorViewModel.modifyEditingSpent(editorViewModel.rawSpentValue.value!!.toBigDecimal())
        } else if (newValue == "") {
            editorViewModel.rawSpentValue.value = newValue
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) MaterialTheme.colorScheme.primaryContainer
                        else colorButton
                    )
                    .clickable {
                        voiceStatus = null
                        voiceAiError = null
                        // Guard against double-starting the recognizer: a tap while already
                        // listening (or while a result is still being committed) would call
                        // startListening() again and crash the recognition session.
                        if (isListening || isProcessing) return@clickable
                        if (!recognitionAvailable || speechRecognizer == null) {
                            voiceStatus = context.getString(R.string.voice_unavailable)
                            return@clickable
                        }
                        if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            isListening = true
                            voiceSession += 1
                            speechRecognizer.startListening(speechIntent)
                        } else {
                            permissionLauncher.launch(
                                Manifest.permission.RECORD_AUDIO
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = strVoiceInput,
                    tint = if (isListening) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (isListening) {
                Text(
                    text = strVoiceListening,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            voiceStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == strVoiceProcessing) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.weight(1F))
        }
        Row(
            Modifier
                .fillMaxSize()
                .weight(1F)
        ) {
            for (i in 7..9) {
                KeyboardButton(
                    modifier = Modifier
                        .weight(1F)
                        .padding(BUTTON_GAP),
                    type = KeyboardButtonType.DEFAULT,
                    text = i.toString(),
                    onClick = {
                        dispatch(KeyboardAction.PUT_NUMBER, i)
                        debugProgress = 0
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                )
            }
            KeyboardButton(
                modifier = Modifier
                    .weight(1F)
                    .padding(BUTTON_GAP),
                type = KeyboardButtonType.SECONDARY,
                icon = painterResource(R.drawable.ic_backspace),
                onClick = {
                    dispatch(KeyboardAction.REMOVE_LAST, null)
                    debugProgress = 0
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onLongClick = {
                    debugProgress = 0
                    if (mode === EditMode.ADD) {
                        editorViewModel.resetEditingSpent()
                    } else {
                        editorViewModel.rawSpentValue.value =
                            tryConvertStringToNumber("0").join(third = false)

                        if (editorViewModel.stage.value === EditStage.IDLE) editorViewModel.startCreatingSpent()
                        editorViewModel.modifyEditingSpent(editorViewModel.rawSpentValue.value!!.toBigDecimal())
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .weight(3F)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .weight(3F)
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .weight(1F)
                ) {
                    for (i in 4..6) {
                        KeyboardButton(
                            modifier = Modifier
                                .weight(1F)
                                .padding(BUTTON_GAP),
                            type = KeyboardButtonType.DEFAULT,
                            text = i.toString(),
                            onClick = {
                                dispatch(KeyboardAction.PUT_NUMBER, i)
                                debugProgress = 0
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxSize()
                        .weight(1F)
                ) {
                    for (i in 1..3) {
                        KeyboardButton(
                            modifier = Modifier
                                .weight(1F)
                                .padding(BUTTON_GAP),
                            type = KeyboardButtonType.DEFAULT,
                            text = i.toString(),
                            onClick = {
                                dispatch(KeyboardAction.PUT_NUMBER, i)
                                debugProgress = 0
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxSize()
                        .weight(1F)
                ) {
                    KeyboardButton(
                        modifier = Modifier
                            .weight(2F)
                            .padding(BUTTON_GAP),
                        type = KeyboardButtonType.DEFAULT,
                        text = "0",
                        onClick = {
                            dispatch(KeyboardAction.PUT_NUMBER, 0)
                            debugProgress += 1
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                    )
                    KeyboardButton(
                        modifier = Modifier
                            .weight(1F)
                            .padding(BUTTON_GAP),
                        type = KeyboardButtonType.DEFAULT,
                        text = getFloatDivider(),
                        onClick = {
                            dispatch(KeyboardAction.SET_DOT, null)
                            debugProgress = if (debugProgress == 8) -1 else 0
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .weight(1F)
            ) {
                val fixedSpent = tryConvertStringToNumber(currentRawSpent).join(third = false)

                AnimatedContent(
                    label = "Delete or Apply",
                    targetState = (fixedSpent == "0" || fixedSpent == "0." || fixedSpent == "0.0") && mode === EditMode.EDIT,
                    transitionSpec = {
                        if (targetState && !initialState) {
                            fadeIn(
                                tween(durationMillis = 250)
                            ) togetherWith fadeOut(
                                tween(durationMillis = 250)
                            )
                        } else {
                            fadeIn(
                                tween(durationMillis = 250)
                            ) togetherWith fadeOut(
                                tween(durationMillis = 250)
                            )
                        }.using(
                            SizeTransform(clip = false)
                        )
                    }
                ) { targetIsDelete ->
                    if (targetIsDelete) {
                        KeyboardButton(
                            modifier = Modifier
                                .weight(1F)
                                .padding(BUTTON_GAP),
                            type = KeyboardButtonType.DELETE,
                            icon = painterResource(R.drawable.ic_delete_forever),
                            onClick = {
                                editorViewModel.editedTransaction?.let {
                                    spendsViewModel.removeSpent(
                                        it
                                    )
                                }
                                editorViewModel.resetEditingSpent()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        )
                    } else {
                        KeyboardButton(
                            modifier = Modifier
                                .weight(1F)
                                .padding(BUTTON_GAP),
                            type = KeyboardButtonType.PRIMARY,
                            icon = painterResource(R.drawable.ic_apply),
                            onClick = {
                                if (debugProgress == -1) {
                                    editorViewModel.resetEditingSpent()

                                    appViewModel.setIsDebug(!appViewModel.isDebug.value!!)

                                    coroutineScope.launch {
                                        appViewModel.showSnackbar(
                                            "Debug ${
                                                if (appViewModel.isDebug.value!!) {
                                                    "ON"
                                                } else {
                                                    "OFF"
                                                }
                                            }"
                                        )
                                    }

                                    return@KeyboardButton
                                }

                                debugProgress = 0

                                runBlocking {
                                    if (editorViewModel.canCommitEditingSpent()) {
                                        if (mode == EditMode.EDIT) {
                                            val newVersionOfSpent =
                                                editorViewModel.editedTransaction!!.copy(
                                                    value = editorViewModel.currentSpent,
                                                    date = editorViewModel.currentDate,
                                                    comment = (editorViewModel.currentComment.value
                                                        ?: "").trim(),
                                                    category = editorViewModel.currentCategory.value
                                                )

                                            spendsViewModel.removeSpent(
                                                editorViewModel.editedTransaction!!,
                                                silent = true
                                            )
                                            spendsViewModel.addSpent(newVersionOfSpent)
                                        } else {
                                            spendsViewModel.addSpent(
                                                Transaction(
                                                    type = TransactionType.SPENT,
                                                    value = editorViewModel.currentSpent,
                                                    date = editorViewModel.currentDate,
                                                    comment = (editorViewModel.currentComment.value
                                                        ?: "").trim(),
                                                    category = editorViewModel.currentCategory.value
                                                )
                                            )
                                            appViewModel.activateTutorial(TUTORS.OPEN_HISTORY)
                                        }

                                        editorViewModel.resetEditingSpent()
                                    }
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        )
                    }
                }
            }
        }
    }

    voicePending?.let { records ->
        val isBatch = records.size > 1
        AlertDialog(
            onDismissRequest = {
                voicePending = null
                skipVoiceRecord()
            },
            title = {
                Text(
                    stringResource(
                        if (isBatch) R.string.voice_confirm_batch_title
                        else R.string.voice_confirm_title
                    )
                )
            },
            text = {
                Column {
                    if (isBatch) {
                        records.forEach { record ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = record.amountString,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = buildString {
                                        if (record.comment.isNotEmpty()) {
                                            append(record.comment)
                                            append(" · ")
                                        }
                                        append(
                                            prettyDate(
                                                record.date,
                                                forceShowDate = true,
                                                forceHideDate = false,
                                            )
                                        )
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                    } else {
                        val pending = records.first()
                        Text(
                            text = pending.amountString,
                            style = MaterialTheme.typography.headlineLarge,
                        )
                        if (pending.comment.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = pending.comment,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.voice_confirm_date_label) +
                                ": " + prettyDate(pending.date, forceShowDate = true),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    voiceAiError?.let { error ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        voicePending = null
                        commitVoiceRecords(records)
                    }
                ) {
                    Text(
                        stringResource(
                            if (isBatch) R.string.voice_confirm_add_all
                            else R.string.voice_confirm_confirm
                        )
                    )
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            voicePending = null
                            skipVoiceRecord()
                        }
                    ) {
                        Text(stringResource(R.string.voice_confirm_skip))
                    }
                    TextButton(
                        onClick = { voicePending = null }
                    ) {
                        Text(stringResource(R.string.voice_confirm_modify))
                    }
                }
            },
        )
    }
}

@Preview
@Composable
fun KeyboardPreview() {
    BuckwheatTheme {
        Keyboard()
    }
}
