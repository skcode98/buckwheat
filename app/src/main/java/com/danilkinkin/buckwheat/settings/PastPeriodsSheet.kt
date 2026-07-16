package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.clickable
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
                ) {
                    items(periods) { period ->
                        PastPeriodRow(
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
private fun PastPeriodRow(
    period: BudgetPeriod,
    currency: ExtendCurrency,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = com.danilkinkin.buckwheat.util.prettyDate(
                        period.startDate,
                        pattern = "dd MMM yyyy"
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.past_periods_date_range,
                        com.danilkinkin.buckwheat.util.prettyDate(period.startDate, pattern = "dd MMM"),
                        com.danilkinkin.buckwheat.util.prettyDate(period.finishDate, pattern = "dd MMM yyyy"),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = numberFormat(context, period.totalSpent, currency = currency),
                    style = MaterialTheme.typography.titleMedium,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
                Text(
                    text = numberFormat(context, period.budget, currency = currency),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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
