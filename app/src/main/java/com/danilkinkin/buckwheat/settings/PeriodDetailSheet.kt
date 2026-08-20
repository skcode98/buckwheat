package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.Divider
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.toTransaction
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.wallet.FINISH_DATE_SELECTOR_SHEET
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun PeriodDetailSheet(
    appViewModel: AppViewModel = hiltViewModel(),
    archivesViewModel: ArchivesViewModel = hiltViewModel(),
    categoriesManagementViewModel: CategoriesManagementViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val period by archivesViewModel.selectedPeriod.collectAsStateWithLifecycle()
    val transactions by archivesViewModel.selectedPeriodTransactions.collectAsStateWithLifecycle()
    val allCategories by categoriesManagementViewModel.allCategories.collectAsStateWithLifecycle()
    val categoryEmojis = remember(allCategories) {
        allCategories.associate { it.name to it.emoji }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    period?.let { p ->
        val currency = ExtendCurrency.getInstance(p.currencyCode)

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.past_periods_delete_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.past_periods_delete_message,
                            prettyDate(p.startDate, showTime = false, forceShowDate = true),
                            prettyDate(p.finishDate, showTime = false, forceShowDate = true),
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            archivesViewModel.deletePeriod(p.id)
                            showDeleteDialog = false
                            appViewModel.closeSheet(PERIOD_DETAIL_SHEET)
                            appViewModel.showSnackbar(context.getString(R.string.past_periods_deleted))
                        }
                    ) {
                        Text(stringResource(R.string.history_actions_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        Surface(
            modifier = Modifier.padding(top = localBottomSheetScrollState.topPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.period_detail_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = navigationBarHeight),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    val spends = transactions.filter { it.type == TransactionType.SPENT }
                    val summarySpends = spends.map { it.toTransaction() }

                    item {
                        PeriodSummaryCard(
                            summary = buildPeriodSummary(
                                startDate = p.startDate,
                                finishDate = p.finishDate,
                                actualFinishDate = p.actualFinishDate,
                                budget = p.budget,
                                spends = summarySpends,
                            ),
                            spends = summarySpends,
                            currency = currency,
                            categoryEmojis = categoryEmojis,
                            onEditDates = {
                                appViewModel.openSheet(
                                    PathState(
                                        name = FINISH_DATE_SELECTOR_SHEET,
                                        args = mapOf(
                                            "initialStartDate" to p.startDate,
                                            "initialDate" to p.finishDate,
                                            "disableBeforeDate" to null,
                                            "disableAfterDate" to null,
                                        ),
                                        callback = { result ->
                                            if (!result.containsKey("finishDate")) return@PathState
                                            val finishDate = result["finishDate"] as Date
                                            val startDate = result["startDate"] as Date
                                            coroutineScope.launch {
                                                archivesViewModel.updatePeriodDates(
                                                    p.id,
                                                    startDate,
                                                    finishDate,
                                                )
                                                appViewModel.showSnackbar(
                                                    context.getString(R.string.period_dates_updated)
                                                )
                                            }
                                        }
                                    )
                                )
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PeriodBudgetEditor(
                            budget = p.budget,
                            currency = currency,
                            onSave = { newBudget ->
                                coroutineScope.launch {
                                    archivesViewModel.updatePeriodBudget(p.id, newBudget)
                                    appViewModel.showSnackbar(
                                        context.getString(R.string.period_budget_updated)
                                    )
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_forever),
                                contentDescription = null,
                                modifier = Modifier.height(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.past_periods_delete_period))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    items(transactions, key = { it.uid }) { tx ->
                        ArchivedTransactionItem(
                            transaction = tx,
                            currency = currency,
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodBudgetEditor(
    budget: BigDecimal,
    currency: ExtendCurrency,
    onSave: (BigDecimal) -> Unit,
) {
    val context = LocalContext.current
    var budgetText by remember(budget) {
        mutableStateOf(if (budget > BigDecimal.ZERO) budget.toPlainString() else "")
    }
    var isEditing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.period_budget_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (!isEditing) {
                    Text(
                        text = if (budget > BigDecimal.ZERO) {
                            numberFormat(context, budget, currency = currency)
                        } else {
                            stringResource(R.string.period_budget_not_set)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (!isEditing) {
                TextButton(onClick = { isEditing = true }) {
                    Text(stringResource(R.string.edit))
                }
            }
        }
        if (isEditing) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.period_budget_label)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            budgetText.trim().toBigDecimalOrNull()
                                ?.takeIf { it > BigDecimal.ZERO }
                                ?.let {
                                    onSave(it)
                                    isEditing = false
                                }
                        }
                    ),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { isEditing = false },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun ArchivedTransactionItem(
    transaction: ArchivedTransaction,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = prettyDate(transaction.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (transaction.comment.isNotEmpty()) {
                Text(
                    text = transaction.comment,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            text = numberFormat(
                context,
                transaction.value,
                currency = currency,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
