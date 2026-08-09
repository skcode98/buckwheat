/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.ledger

import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.trackinvest.R
import com.danilkinkin.trackinvest.backup.rememberExportCsv
import com.danilkinkin.trackinvest.backup.rememberImportCsv
import com.danilkinkin.trackinvest.data.LedgerViewModel
import com.danilkinkin.trackinvest.data.entities.Investment
import com.danilkinkin.trackinvest.data.formatInvestmentDate
import com.danilkinkin.trackinvest.util.formatAmount
import kotlinx.coroutines.launch

private data class EditorState(val investment: Investment?)

@Composable
fun Ledger(activityResultRegistryOwner: ActivityResultRegistryOwner?) {
    val ledgerViewModel: LedgerViewModel = hiltViewModel()
    val investments by ledgerViewModel.investments.observeAsState(emptyList())
    val currencySymbol by ledgerViewModel.currencySymbol.observeAsState("₹")
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var feedback by remember { mutableStateOf<String?>(null) }
    var editorState by remember { mutableStateOf<EditorState?>(null) }

    val exportCsv = rememberExportCsv(
        ledgerViewModel = ledgerViewModel,
        activityResultRegistryOwner = activityResultRegistryOwner,
        onExported = { feedback = context.getString(R.string.csv_exported) },
        onFailed = { feedback = context.getString(R.string.csv_export_failed) },
    )

    val importCsv = rememberImportCsv(
        ledgerViewModel = ledgerViewModel,
        activityResultRegistryOwner = activityResultRegistryOwner,
        onImported = { count ->
            feedback = if (count > 0) {
                context.getString(R.string.csv_imported, count)
            } else {
                context.getString(R.string.csv_import_empty)
            }
        },
        onFailed = { feedback = context.getString(R.string.csv_import_failed) },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tab_ledger),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )

                TextButton(onClick = exportCsv) {
                    Text(stringResource(R.string.export_csv))
                }

                TextButton(onClick = importCsv) {
                    Text(stringResource(R.string.import_csv))
                }
            }

            feedback?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (investments.isEmpty()) {
                EmptyLedger()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(investments, key = { it.uid }) { investment ->
                        InvestmentRow(
                            investment = investment,
                            currencySymbol = currencySymbol,
                            onClick = { editorState = EditorState(investment) },
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { editorState = EditorState(null) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.investment_add),
            )
        }
    }

    editorState?.let { state ->
        InvestmentEditorSheet(
            investment = state.investment,
            onSave = { investment ->
                coroutineScope.launch { ledgerViewModel.saveInvestment(investment) }
                editorState = null
            },
            onDelete = { investment ->
                coroutineScope.launch { ledgerViewModel.deleteInvestment(investment) }
                editorState = null
            },
            onDismiss = { editorState = null },
        )
    }
}

@Composable
private fun EmptyLedger() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.investments_empty),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.investments_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InvestmentRow(
    investment: Investment,
    currencySymbol: String,
    onClick: () -> Unit,
) {
    val secondary = listOfNotNull(
        investment.account,
        investment.tags.joinToString(", ").ifBlank { null },
    ).joinToString(" · ")

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = investment.type,
                        style = MaterialTheme.typography.titleSmall,
                    )

                    if (investment.isMonthlyContrib) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.investment_monthly_short),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Text(
                    text = formatInvestmentDate(investment.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (investment.note.isNotBlank()) {
                    Text(
                        text = investment.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

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
                text = formatAmount(investment.amount, currencySymbol),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
