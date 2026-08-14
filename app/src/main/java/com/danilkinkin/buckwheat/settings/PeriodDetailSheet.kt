package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.Divider
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.toTransaction
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate

@Composable
fun PeriodDetailSheet(
    archivesViewModel: ArchivesViewModel = hiltViewModel(),
    categoriesManagementViewModel: CategoriesManagementViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val context = LocalContext.current
    val period by archivesViewModel.selectedPeriod.observeAsState(null)
    val transactions by archivesViewModel.selectedPeriodTransactions.observeAsState(emptyList())
    val allCategories by categoriesManagementViewModel.allCategories.observeAsState(emptyList())
    val categoryEmojis = remember(allCategories) {
        allCategories.associate { it.name to it.emoji }
    }

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    Surface(
        modifier = Modifier.padding(top = localBottomSheetScrollState.topPadding),
        color = Color(0xFFF5F5F5),
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
                val selectedPeriod = period
                if (selectedPeriod != null) {
                    val spends = transactions.filter { it.type == TransactionType.SPENT }
                    val summarySpends = spends.map { it.toTransaction() }
                    val currency = ExtendCurrency.getInstance(selectedPeriod.currencyCode)

                    item {
                        PeriodSummaryCard(
                            summary = buildPeriodSummary(
                                startDate = selectedPeriod.startDate,
                                finishDate = selectedPeriod.finishDate,
                                actualFinishDate = selectedPeriod.actualFinishDate,
                                budget = selectedPeriod.budget,
                                spends = summarySpends,
                            ),
                            spends = summarySpends,
                            currency = currency,
                            categoryEmojis = categoryEmojis,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
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
