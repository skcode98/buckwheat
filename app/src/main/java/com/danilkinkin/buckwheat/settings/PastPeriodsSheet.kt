package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import java.math.BigDecimal
import java.math.RoundingMode

const val PAST_PERIODS_SHEET = "pastPeriods"
const val PERIOD_DETAIL_SHEET = "periodDetail"

@Composable
fun PastPeriodsSheet(
    appViewModel: AppViewModel = hiltViewModel(),
    archivesViewModel: ArchivesViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val periods by archivesViewModel.periods.observeAsState(emptyList())
    val context = LocalContext.current

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
                    text = stringResource(R.string.past_periods_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            if (periods.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.past_periods_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = navigationBarHeight),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(periods, key = { it.id }) { period ->
                        PastPeriodCard(
                            period = period,
                            currency = ExtendCurrency.getInstance(period.currencyCode),
                            onClick = {
                                archivesViewModel.selectPeriod(period.id)
                                appViewModel.openSheet(PathState(PERIOD_DETAIL_SHEET))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PastPeriodCard(
    period: BudgetPeriod,
    currency: ExtendCurrency,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    val restBudget = period.budget - period.totalSpent
    val restPercent = if (period.budget > BigDecimal.ZERO) {
        restBudget.multiply(BigDecimal(100)).divide(period.budget, 0, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ZERO
    }
    val indicatorColor = when {
        restPercent < BigDecimal.ZERO -> MaterialTheme.colorScheme.error
        restPercent < BigDecimal(20) -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    R.string.past_periods_date_range,
                    prettyDate(period.startDate, showTime = false, forceShowDate = true),
                    prettyDate(period.finishDate, showTime = false, forceShowDate = true),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (period.isImported) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.past_periods_imported),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = numberFormat(context, period.totalSpent, currency = currency),
                        style = MaterialTheme.typography.titleMedium,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.whole_budget),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            text = numberFormat(context, period.budget, currency = currency),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.spent_budget),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            text = numberFormat(context, period.totalSpent, currency = currency),
                            style = MaterialTheme.typography.bodyLarge,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (period.totalSpent.toFloat() / period.budget.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = indicatorColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (restBudget >= BigDecimal.ZERO) {
                        stringResource(R.string.rest_budget_percent, restPercent.toInt())
                    } else {
                        stringResource(
                            R.string.over_budget_amount,
                            numberFormat(context, -restBudget, currency = currency),
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = indicatorColor,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewPastPeriods() {
    BuckwheatTheme {
        PastPeriodsSheet()
    }
}
