/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.danilkinkin.trackinvest.R
import com.danilkinkin.trackinvest.data.entities.Template
import com.danilkinkin.trackinvest.data.splitTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorSheet(
    onSave: (Template) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var type by remember { mutableStateOf("Cash") }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }

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
                text = stringResource(R.string.template_log),
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

            Row {
                Button(
                    onClick = {
                        val amountValue = amount ?: return@Button
                        onSave(
                            Template(
                                type = type.trim().ifBlank { "Cash" },
                                amount = amountValue,
                                note = note.trim(),
                                tags = splitTags(tagsText),
                                account = account.trim().ifBlank { null },
                            ),
                        )
                    },
                    enabled = amount != null && type.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.investment_save))
                }
            }
        }
    }
}
