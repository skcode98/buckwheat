package com.danilkinkin.buckwheat.screenshotimport

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.parseAmountToBigDecimal
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class ImportStage { IDLE, PROCESSING, REVIEW, DONE, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotImportSheet(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    var stage by remember { mutableStateOf(ImportStage.IDLE) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateListOf<ScreenshotSpendDraft>() }
    var currentIndex by remember { mutableIntStateOf(0) }
    var committedCount by remember { mutableIntStateOf(0) }

    fun resetToIdle() {
        stage = ImportStage.IDLE
        statusMessage = null
        errorMessage = null
        aiError = null
        drafts.clear()
        currentIndex = 0
        committedCount = 0
    }

    suspend fun handleParsed(outcome: ScreenshotParseOutcome) {
        aiError = outcome.aiError
        drafts.clear()
        drafts.addAll(outcome.drafts)
        currentIndex = 0
        committedCount = 0
        stage = if (drafts.isEmpty()) ImportStage.ERROR else ImportStage.REVIEW
        if (drafts.isEmpty()) {
            errorMessage = context.getString(R.string.screenshot_import_no_records)
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            stage = ImportStage.PROCESSING
            statusMessage = context.getString(R.string.screenshot_import_extracting)
            errorMessage = null
            try {
                val text = recognizeTextFromUri(context, uri)
                if (normalizeOcrText(text).isBlank()) {
                    stage = ImportStage.ERROR
                    errorMessage = context.getString(R.string.screenshot_import_no_text)
                } else {
                    statusMessage = context.getString(R.string.screenshot_import_parsing)
                    handleParsed(parseScreenshotSpendRecords(context, text))
                }
            } catch (e: Exception) {
                stage = ImportStage.ERROR
                errorMessage = e.message ?: context.getString(R.string.screenshot_import_ocr_failed)
            } finally {
                statusMessage = null
            }
        }
    }

    val captureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
    ) { bitmap: Bitmap? ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            stage = ImportStage.PROCESSING
            statusMessage = context.getString(R.string.screenshot_import_extracting)
            errorMessage = null
            try {
                val text = recognizeTextFromBitmap(bitmap)
                if (normalizeOcrText(text).isBlank()) {
                    stage = ImportStage.ERROR
                    errorMessage = context.getString(R.string.screenshot_import_no_text)
                } else {
                    statusMessage = context.getString(R.string.screenshot_import_parsing)
                    handleParsed(parseScreenshotSpendRecords(context, text))
                }
            } catch (e: Exception) {
                stage = ImportStage.ERROR
                errorMessage = e.message ?: context.getString(R.string.screenshot_import_ocr_failed)
            } finally {
                statusMessage = null
            }
        }
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.screenshot_import_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = navigationBarHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (stage) {
                    ImportStage.IDLE -> {
                        Text(
                            text = stringResource(R.string.screenshot_import_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        )
                        Button(
                            onClick = { pickImageLauncher.launch("image/*") },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.screenshot_import_pick))
                        }
                        OutlinedButton(
                            onClick = { captureLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.screenshot_import_capture))
                        }
                    }

                    ImportStage.PROCESSING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(statusMessage ?: stringResource(R.string.screenshot_import_processing))
                        }
                    }

                    ImportStage.REVIEW -> {
                        if (drafts.isEmpty()) {
                            stage = ImportStage.DONE
                        } else {
                            val safeIndex = currentIndex.coerceIn(0, drafts.lastIndex)
                            if (safeIndex != currentIndex) currentIndex = safeIndex
                            val draft = drafts[safeIndex]

                            Text(
                                text = stringResource(
                                    R.string.screenshot_import_progress,
                                    safeIndex + 1,
                                    drafts.size,
                                    committedCount,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            aiError?.let { err ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.screenshot_import_ai_note, err),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }

                            ScreenshotDraftEditor(
                                draft = draft,
                                onChange = { updated -> drafts[safeIndex] = updated },
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        drafts.removeAt(safeIndex)
                                        if (drafts.isEmpty()) stage = ImportStage.DONE
                                        else if (currentIndex >= drafts.size) currentIndex = drafts.lastIndex
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.screenshot_import_skip)) }

                                OutlinedButton(
                                    onClick = {
                                        drafts.removeAt(safeIndex)
                                        if (drafts.isEmpty()) stage = ImportStage.DONE
                                        else if (currentIndex >= drafts.size) currentIndex = drafts.lastIndex
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.screenshot_import_delete)) }
                            }

                            val commitEnabled = parseAmountToBigDecimal(draft.amountText)?.let { it > BigDecimal.ZERO } == true
                            Button(
                                onClick = {
                                    val amount = parseAmountToBigDecimal(drafts[safeIndex].amountText) ?: return@Button
                                    if (amount <= BigDecimal.ZERO) return@Button
                                    val current = drafts[safeIndex]
                                    spendsViewModel.addSpent(
                                        Transaction(
                                            type = TransactionType.SPENT,
                                            value = amount,
                                            date = current.date,
                                            comment = current.comment.trim(),
                                        )
                                    )
                                    committedCount += 1
                                    drafts.removeAt(safeIndex)
                                    if (drafts.isEmpty()) {
                                        stage = ImportStage.DONE
                                    } else if (currentIndex >= drafts.size) {
                                        currentIndex = drafts.lastIndex
                                    }
                                },
                                enabled = commitEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.screenshot_import_commit)) }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val insertAt = (safeIndex + 1).coerceAtMost(drafts.size)
                                        drafts.add(
                                            insertAt,
                                            ScreenshotSpendDraft(
                                                amount = BigDecimal.ZERO,
                                                amountText = "",
                                                comment = "",
                                                date = Date(),
                                            )
                                        )
                                        currentIndex = insertAt
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.screenshot_import_add_manual)) }

                                if (drafts.size > 1) {
                                    OutlinedButton(
                                        onClick = {
                                            currentIndex = (currentIndex + 1) % drafts.size
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text(stringResource(R.string.screenshot_import_next)) }
                                }
                            }
                        }
                    }

                    ImportStage.DONE -> {
                        Text(
                            text = stringResource(R.string.screenshot_import_done, committedCount),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(
                            onClick = { resetToIdle() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.screenshot_import_import_another)) }
                    }

                    ImportStage.ERROR -> {
                        errorMessage?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        aiError?.let { err ->
                            Text(
                                text = stringResource(R.string.screenshot_import_ai_note, err),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { resetToIdle() },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.screenshot_import_try_again)) }

                            if (drafts.isEmpty()) {
                                Button(
                                    onClick = {
                                        drafts.add(
                                            ScreenshotSpendDraft(
                                                amount = BigDecimal.ZERO,
                                                amountText = "",
                                                comment = "",
                                                date = Date(),
                                            )
                                        )
                                        currentIndex = 0
                                        errorMessage = null
                                        stage = ImportStage.REVIEW
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.screenshot_import_add_manual)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenshotDraftEditor(
    draft: ScreenshotSpendDraft,
    onChange: (ScreenshotSpendDraft) -> Unit,
) {
    var showDatePicker by remember(draft.id) { mutableStateOf(false) }
    var showTimePicker by remember(draft.id) { mutableStateOf(false) }

    OutlinedTextField(
        value = draft.amountText,
        onValueChange = { text ->
            val parsed = parseAmountToBigDecimal(text)
            onChange(
                draft.copy(
                    amountText = text,
                    amount = parsed ?: BigDecimal.ZERO,
                )
            )
        },
        label = { Text(stringResource(R.string.screenshot_import_amount)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        isError = draft.amountText.isNotBlank() && (parseAmountToBigDecimal(draft.amountText)?.let { it <= BigDecimal.ZERO } != false),
    )

    OutlinedTextField(
        value = draft.comment,
        onValueChange = { onChange(draft.copy(comment = it)) },
        label = { Text(stringResource(R.string.screenshot_import_comment)) },
        modifier = Modifier.fillMaxWidth(),
    )

    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { showDatePicker = true },
            modifier = Modifier.weight(1f),
        ) { Text(dateFmt.format(draft.date)) }
        OutlinedButton(
            onClick = { showTimePicker = true },
            modifier = Modifier.weight(1f),
        ) { Text(timeFmt.format(draft.date)) }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.date.time)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val selected = state.selectedDateMillis
                    if (selected != null) {
                        val calCurrent = Calendar.getInstance().apply { time = draft.date }
                        val calNew = Calendar.getInstance().apply { timeInMillis = selected }
                        calCurrent.set(Calendar.YEAR, calNew.get(Calendar.YEAR))
                        calCurrent.set(Calendar.MONTH, calNew.get(Calendar.MONTH))
                        calCurrent.set(Calendar.DAY_OF_MONTH, calNew.get(Calendar.DAY_OF_MONTH))
                        onChange(draft.copy(date = calCurrent.time))
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.accept)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val cal = Calendar.getInstance().apply { time = draft.date }
        val state = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    cal.set(Calendar.HOUR_OF_DAY, state.hour)
                    cal.set(Calendar.MINUTE, state.minute)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    onChange(draft.copy(date = cal.time))
                    showTimePicker = false
                }) { Text(stringResource(R.string.accept)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            text = { TimePicker(state = state) },
        )
    }
}
