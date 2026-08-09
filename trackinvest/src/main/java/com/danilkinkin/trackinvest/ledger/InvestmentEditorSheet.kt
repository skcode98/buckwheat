/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.danilkinkin.trackinvest.data.entities.Investment
import com.danilkinkin.trackinvest.data.formatInvestmentDate
import com.danilkinkin.trackinvest.data.splitTags
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private val INVESTMENT_TYPES = listOf("FD", "PPF", "PF", "SIP", "Liquid", "Home", "Cash", "Stocks")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestmentEditorSheet(
    investment: Investment?,
    onSave: (Investment) -> Unit,
    onDelete: (Investment) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var type by remember(investment) { mutableStateOf(investment?.type ?: "Cash") }
    var amountText by remember(investment) { mutableStateOf(investment?.amount?.toPlainString() ?: "") }
    var date by remember(investment) {
        mutableStateOf(
            investment?.date
                ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
    }
    var account by remember(investment) { mutableStateOf(investment?.account.orEmpty()) }
    var note by remember(investment) { mutableStateOf(investment?.note ?: "") }
    var tagsText by remember(investment) { mutableStateOf(investment?.tags?.joinToString(", ") ?: "") }
    var isMonthly by remember(investment) { mutableStateOf(investment?.isMonthlyContrib ?: false) }
    var showDatePicker by remember(investment) { mutableStateOf(false) }

    val amount = amountText.trim().toBigDecimalOrNull()

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
                    if (investment == null) R.string.investment_log else R.string.investment_edit,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.investment_type),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(8.dp))

            FlowRow {
                INVESTMENT_TYPES.forEach { option ->
                    FilterChip(
                        selected = type == option,
                        onClick = { type = option },
                        label = { Text(option) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }

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
                Text(formatInvestmentDate(date))
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

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.investment_monthly_contrib),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = isMonthly,
                    onCheckedChange = { isMonthly = it },
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (investment != null) {
                    OutlinedButton(
                        onClick = { onDelete(investment) },
                    ) {
                        Text(
                            text = stringResource(R.string.investment_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                }

                Button(
                    onClick = {
                        val savedType = type.trim().ifBlank { "Cash" }
                        val amountValue = amount ?: return@Button
                        val updated = investment?.copy(
                            date = date,
                            type = savedType,
                            amount = amountValue,
                            account = account.trim().ifBlank { null },
                            note = note.trim(),
                            tags = splitTags(tagsText),
                            isMonthlyContrib = isMonthly,
                        )?.also { it.uid = investment.uid }
                            ?: Investment(
                                date = date,
                                type = savedType,
                                amount = amountValue,
                                account = account.trim().ifBlank { null },
                                note = note.trim(),
                                tags = splitTags(tagsText),
                                isMonthlyContrib = isMonthly,
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
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = date)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val local = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            date = local.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
