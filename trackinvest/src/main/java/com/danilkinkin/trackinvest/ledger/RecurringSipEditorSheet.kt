/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.ledger

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
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.danilkinkin.trackinvest.R
import com.danilkinkin.trackinvest.data.entities.RecurringSip
import com.danilkinkin.trackinvest.data.formatInvestmentDate
import com.danilkinkin.trackinvest.data.nextMonthlyRun
import com.danilkinkin.trackinvest.data.splitTags
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringSipEditorSheet(
    sip: RecurringSip?,
    onSave: (RecurringSip) -> Unit,
    onDelete: (RecurringSip) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var type by remember(sip) { mutableStateOf(sip?.type ?: "SIP") }
    var amountText by remember(sip) { mutableStateOf(sip?.amount?.toPlainString() ?: "") }
    var note by remember(sip) { mutableStateOf(sip?.note ?: "") }
    var tagsText by remember(sip) { mutableStateOf(sip?.tags?.joinToString(", ") ?: "") }
    var account by remember(sip) { mutableStateOf(sip?.account.orEmpty()) }
    var startDate by remember(sip) {
        mutableStateOf(
            sip?.nextRun
                ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
    }
    var showDatePicker by remember(sip) { mutableStateOf(false) }

    val amount = amountText.trim().toBigDecimalOrNull()
    val zoneId = ZoneId.systemDefault()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(
                    if (sip == null) R.string.recurring_log else R.string.recurring_edit,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.investment_type),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(8.dp))

            InvestmentTypeChips(type = type, onTypeChange = { type = it })

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = type,
                onValueChange = { type = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.investment_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = amountText.isNotBlank() && amount == null,
                supportingText = {
                    if (amountText.isNotBlank() && amount == null) {
                        Text(stringResource(R.string.investment_invalid_amount))
                    }
                },
            )

            Spacer(Modifier.height(16.dp))

            OutlinedButton(onClick = { showDatePicker = true }) {
                Text(formatInvestmentDate(startDate))
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.investment_account)) },
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.investment_note)) },
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.investment_tags)) },
                placeholder = { Text(stringResource(R.string.investment_tags_hint)) },
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sip != null) {
                    OutlinedButton(
                        onClick = { onDelete(sip) },
                    ) {
                        Text(
                            text = stringResource(R.string.recurring_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                }

                Button(
                    onClick = {
                        val amountValue = amount ?: return@Button
                        val savedType = type.trim().ifBlank { "SIP" }
                        val nextRun = nextMonthlyRun(startDate, zoneId)
                        val updated = sip?.copy(
                            type = savedType,
                            amount = amountValue,
                            note = note.trim(),
                            tags = splitTags(tagsText),
                            account = account.trim().ifBlank { null },
                            nextRun = nextRun,
                        )?.also { it.uid = sip.uid }
                            ?: RecurringSip(
                                type = savedType,
                                amount = amountValue,
                                note = note.trim(),
                                tags = splitTags(tagsText),
                                account = account.trim().ifBlank { null },
                                nextRun = nextRun,
                            )
                        onSave(updated)
                    },
                    enabled = amount != null && type.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.investment_save))
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = startDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val local = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            startDate = local.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
