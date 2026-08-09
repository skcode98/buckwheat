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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.trackinvest.R
import com.danilkinkin.trackinvest.data.RecurringViewModel
import com.danilkinkin.trackinvest.data.entities.RecurringSip
import com.danilkinkin.trackinvest.data.formatInvestmentDate
import com.danilkinkin.trackinvest.util.formatAmount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringSipsSheet(
    currencySymbol: String,
    onDismiss: () -> Unit,
    onProcessed: (Int) -> Unit,
) {
    val viewModel: RecurringViewModel = hiltViewModel()
    val sips by viewModel.sips.observeAsState(emptyList())
    val coroutineScope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<RecurringSip?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.recurring_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )

                TextButton(
                    onClick = {
                        coroutineScope.launch { onProcessed(viewModel.processDueSips()) }
                    },
                ) {
                    Text(stringResource(R.string.recurring_process))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (sips.isEmpty()) {
                Text(
                    text = stringResource(R.string.recurring_empty),
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.recurring_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                sips.forEach { sip ->
                    RecurringSipRow(
                        sip = sip,
                        currencySymbol = currencySymbol,
                        onEdit = {
                            editing = sip
                            showEditor = true
                        },
                        onToggleActive = {
                            coroutineScope.launch {
                                viewModel.saveSip(
                                    sip.copy(isActive = !sip.isActive).also { it.uid = sip.uid },
                                )
                            }
                        },
                        onDelete = {
                            coroutineScope.launch { viewModel.deleteSip(sip) }
                        },
                    )

                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    editing = null
                    showEditor = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.recurring_add))
            }
        }
    }

    if (showEditor) {
        RecurringSipEditorSheet(
            sip = editing,
            onSave = { sip ->
                coroutineScope.launch { viewModel.saveSip(sip) }
                showEditor = false
            },
            onDelete = { sip ->
                coroutineScope.launch { viewModel.deleteSip(sip) }
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun RecurringSipRow(
    sip: RecurringSip,
    currencySymbol: String,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = sip.note.ifBlank { sip.type },
                    style = MaterialTheme.typography.titleSmall,
                )

                Text(
                    text = listOfNotNull(
                        sip.type,
                        stringResource(R.string.recurring_next_run, formatInvestmentDate(sip.nextRun)),
                        sip.account,
                    ).joinToString(" Â· "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = formatAmount(sip.amount, currencySymbol),
                style = MaterialTheme.typography.titleMedium,
            )

            Switch(
                checked = sip.isActive,
                onCheckedChange = { onToggleActive() },
            )

            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.recurring_delete),
                )
            }
        }
    }
}
