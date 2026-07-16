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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
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
import com.danilkinkin.buckwheat.util.tryConvertStringToNumber
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.util.Date
import java.util.Locale

val BUTTON_GAP = 6.dp

enum class KeyboardAction { PUT_NUMBER, SET_DOT, REMOVE_LAST }

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
    var voiceStatus by remember { mutableStateOf<String?>(null) }

    val speechRecognizer = remember {
        SpeechRecognizer.createSpeechRecognizer(context)
    }

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
            speechRecognizer.startListening(speechIntent)
        }
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )
                if (matches.isNullOrEmpty()) {
                    voiceStatus = "No speech heard"
                    return
                }
                val text = matches[0]
                val parsed = parseVoiceInput(text)
                if (parsed == null || parsed.amount == "0") {
                    voiceStatus = "Couldn't understand"
                    return
                }
                voiceStatus = null
                editorViewModel.rawSpentValue.value = parsed.amount
                editorViewModel.currentComment.value = parsed.comment
                editorViewModel.currentDate = parsed.date

                runBlocking {
                    if (editorViewModel.stage.value === EditStage.IDLE) {
                        editorViewModel.startCreatingSpent()
                    }
                    editorViewModel.modifyEditingSpent(
                        parsed.amount.toBigDecimal()
                    )

                    if (editorViewModel.canCommitEditingSpent()) {
                        spendsViewModel.addSpent(
                            Transaction(
                                type = TransactionType.SPENT,
                                value = BigDecimal(parsed.amount),
                                date = parsed.date,
                                comment = parsed.comment,
                            )
                        )
                        appViewModel.activateTutorial(TUTORS.OPEN_HISTORY)
                        editorViewModel.resetEditingSpent()
                    }
                }
            }

            override fun onError(error: Int) {
                isListening = false
                voiceStatus = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                    else -> "Recognition failed"
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
            speechRecognizer.destroy()
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
                        if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            isListening = true
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
                    contentDescription = "Voice input",
                    tint = if (isListening) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (isListening) {
                Text(
                    text = "Listening...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            voiceStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
                                                        ?: "").trim()
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
                                                        ?: "").trim()
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
}

@Preview
@Composable
fun KeyboardPreview() {
    BuckwheatTheme {
        Keyboard()
    }
}
