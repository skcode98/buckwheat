package com.danilkinkin.buckwheat.goals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalEditor(
    onDismiss: () -> Unit,
    onSave: (name: String, targetAmount: BigDecimal, deadline: Long?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var deadlineMillis by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_goal_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.goal_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text(stringResource(R.string.goal_target_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showDatePicker = true },
                ) {
                    Text(
                        if (deadlineMillis != null) {
                            "Deadline: ${java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(deadlineMillis!!))}"
                        } else {
                            stringResource(R.string.add_deadline)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = targetText.toBigDecimalOrNull()
                    if (name.isNotBlank() && target != null && target > BigDecimal.ZERO) {
                        onSave(name.trim(), target, deadlineMillis)
                    }
                },
                enabled = name.isNotBlank() && targetText.toBigDecimalOrNull() != null
                        && targetText.toBigDecimalOrNull()!! > BigDecimal.ZERO,
            ) {
                Text(stringResource(R.string.accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = deadlineMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        deadlineMillis = datePickerState.selectedDateMillis
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
