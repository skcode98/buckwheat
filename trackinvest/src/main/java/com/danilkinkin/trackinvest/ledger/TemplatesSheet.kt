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
import androidx.compose.material3.Text
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
import com.danilkinkin.trackinvest.data.entities.Template
import com.danilkinkin.trackinvest.util.formatAmount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesSheet(
    currencySymbol: String,
    onDismiss: () -> Unit,
    onQuickLogged: () -> Unit,
) {
    val viewModel: RecurringViewModel = hiltViewModel()
    val templates by viewModel.templates.observeAsState(emptyList())
    val coroutineScope = rememberCoroutineScope()
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
            Text(
                text = stringResource(R.string.templates_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(8.dp))

            if (templates.isEmpty()) {
                Text(
                    text = stringResource(R.string.templates_empty),
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.templates_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                templates.forEach { template ->
                    TemplateRow(
                        template = template,
                        currencySymbol = currencySymbol,
                        onQuickLog = {
                            coroutineScope.launch {
                                viewModel.quickLog(template)
                                onQuickLogged()
                            }
                        },
                        onDelete = {
                            coroutineScope.launch { viewModel.deleteTemplate(template) }
                        },
                    )

                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { showEditor = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.template_add))
            }
        }
    }

    if (showEditor) {
        TemplateEditorSheet(
            onSave = { template ->
                coroutineScope.launch { viewModel.saveTemplate(template) }
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun TemplateRow(
    template: Template,
    currencySymbol: String,
    onQuickLog: () -> Unit,
    onDelete: () -> Unit,
) {
    val secondary = listOfNotNull(
        template.type,
        template.account,
        template.tags.joinToString(", ").ifBlank { null },
    ).joinToString(" Â· ")

    Surface(
        onClick = onQuickLog,
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
                    text = template.note.ifBlank { template.type },
                    style = MaterialTheme.typography.titleSmall,
                )

                if (secondary.isNotBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = formatAmount(template.amount, currencySymbol),
                style = MaterialTheme.typography.titleMedium,
            )

            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.template_delete),
                )
            }
        }
    }
}
