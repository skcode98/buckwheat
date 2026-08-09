/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.home.dashboard

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomeSheet(
    initialSalary: Double,
    initialRegime: String,
    onSave: (Double, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var salaryText by remember {
        mutableStateOf(
            if (initialSalary > 0) {
                if (initialSalary % 1.0 == 0.0) {
                    initialSalary.toLong().toString()
                } else {
                    initialSalary.toString()
                }
            } else {
                ""
            },
        )
    }
    var regime by remember { mutableStateOf(initialRegime) }
    val salary = salaryText.trim().toDoubleOrNull()

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
                text = stringResource(R.string.dashboard_income),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = salaryText,
                onValueChange = { salaryText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dashboard_salary)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = salaryText.isNotBlank() && salary == null,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.dashboard_regime),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(8.dp))

            Row {
                FilterChip(
                    selected = regime == "new",
                    onClick = { regime = "new" },
                    label = { Text(stringResource(R.string.dashboard_regime_new)) },
                )

                Spacer(Modifier.width(8.dp))

                FilterChip(
                    selected = regime == "old",
                    onClick = { regime = "old" },
                    label = { Text(stringResource(R.string.dashboard_regime_old)) },
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    onSave(salary ?: 0.0, regime)
                    onDismiss()
                },
                enabled = salaryText.isBlank() || salary != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.investment_save))
            }
        }
    }
}
