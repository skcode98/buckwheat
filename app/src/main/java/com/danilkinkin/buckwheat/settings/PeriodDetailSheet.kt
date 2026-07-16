package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.analytics.MinMaxSpentCard
import com.danilkinkin.buckwheat.analytics.SpendsCountCard
import com.danilkinkin.buckwheat.analytics.WholeBudgetCard
import com.danilkinkin.buckwheat.base.Divider
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal
import java.util.Date

@Composable
fun PeriodDetailSheet(
    archivesViewModel: ArchivesViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val context = LocalContext.current
    val period by archivesViewModel.selectedPeriod.observeAsState(null)
    val transactions by archivesViewModel.selectedPeriodTransactions.observeAsState(emptyList())

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
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
                if (period != null) {
                    val spends = transactions.filter { it.type == TransactionType.SPENT }
                    val currency = ExtendCurrency.getInstance(period!!.currencyCode)

                    item {
                        WholeBudgetCard(
                            modifier = Modifier.height(IntrinsicSize.Min),
                            budget = period!!.budget,
                            currency = currency,
                            startDate = period!!.startDate,
                            finishDate = period!!.finishDate,
                            actualFinishDate = period!!.actualFinishDate,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (spends.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                            ) {
                                SpentAndRestCard(
                                    modifier = Modifier.weight(1f),
                                    spent = period!!.totalSpent,
                                    budget = period!!.budget,
                                    currency = currency,
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                DaysCountCard(
                                    startDate = period!!.startDate,
                                    finishDate = period!!.actualFinishDate ?: period!!.finishDate,
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                            ) {
                                MinMaxSpentCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    isMin = true,
                                    spends = spends.map { it.toTransaction() },
                                    currency = currency,
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                MinMaxSpentCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    isMin = false,
                                    spends = spends.map { it.toTransaction() },
                                    currency = currency,
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        item {
                            SpendsCountCard(
                                modifier = Modifier.fillMaxWidth(),
                                count = spends.size,
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    items(transactions) { tx ->
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
private fun SpentAndRestCard(
    modifier: Modifier = Modifier,
    spent: BigDecimal,
    budget: BigDecimal,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.spent_budget),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                text = com.danilkinkin.buckwheat.util.numberFormat(context, spent, currency = currency),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.rest_budget),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                text = com.danilkinkin.buckwheat.util.numberFormat(
                    context,
                    budget - spent,
                    currency = currency,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun DaysCountCard(
    startDate: Date,
    finishDate: Date,
) {
    val days = com.danilkinkin.buckwheat.util.countDays(finishDate, startDate)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = days.toString(),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = MaterialTheme.typography.titleLarge.fontSize
                ),
            )
            Text(
                text = stringResource(R.string.days_left),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
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
                text = com.danilkinkin.buckwheat.util.prettyDate(transaction.date),
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
            text = com.danilkinkin.buckwheat.util.numberFormat(
                context,
                transaction.value,
                currency = currency,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun ArchivedTransaction.toTransaction() =
    com.danilkinkin.buckwheat.data.entities.Transaction(
        type = this.type,
        value = this.value,
        date = this.date,
        comment = this.comment,
    ).also { it.uid = this.uid }
