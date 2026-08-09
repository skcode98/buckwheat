/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.home.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.trackinvest.R
import com.danilkinkin.trackinvest.data.DashboardUiState
import com.danilkinkin.trackinvest.data.DashboardViewModel
import com.danilkinkin.trackinvest.data.NET_WORTH_HISTORY_MONTHS
import com.danilkinkin.trackinvest.data.PortfolioSummary
import com.danilkinkin.trackinvest.data.TypeValuation
import com.danilkinkin.trackinvest.data.calculateStrictTax
import com.danilkinkin.trackinvest.data.formatInvestmentDate
import com.danilkinkin.trackinvest.data.projectionPoints
import com.danilkinkin.trackinvest.data.entities.Investment
import com.danilkinkin.trackinvest.util.formatAmount
import java.math.BigDecimal
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val SuccessColor = Color(0xFF2E6C29)

private val WarningColor = Color(0xFF825500)

private val AllocationColors = listOf(
    Color(0xFF415F91),
    Color(0xFF6750A4),
    Color(0xFF2E6C29),
    Color(0xFF825500),
    Color(0xFFBF360C),
    Color(0xFF006A6A),
    Color(0xFF7A5900),
    Color(0xFF8D6E63),
    Color(0xFF5B638C),
    Color(0xFF00696D),
)

@Composable
fun Dashboard() {
    val viewModel: DashboardViewModel = hiltViewModel()
    val state by viewModel.state.observeAsState()
    val uiState = state

    var range by rememberSaveable { mutableStateOf(6) }
    var projectionMonths by rememberSaveable { mutableStateOf(12) }
    var showIncomeSheet by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    if (uiState == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val summary = uiState.summary
    if (summary.typeTotals.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.dashboard_empty),
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.dashboard_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard(
            summary = summary,
            currencySymbol = uiState.currencySymbol,
            range = range,
            onRangeChange = { range = it },
        )

        ProjectionCard(
            summary = summary,
            currencySymbol = uiState.currencySymbol,
            months = projectionMonths,
            onMonthsChange = { projectionMonths = it },
        )

        MonthlyCard(
            summary = summary,
            currencySymbol = uiState.currencySymbol,
            monthlyTarget = uiState.monthlyTarget,
        )

        TrendCard(
            summary = summary,
            currencySymbol = uiState.currencySymbol,
        )

        AllocationCard(
            summary = summary,
            currencySymbol = uiState.currencySymbol,
        )

        Tax80cCard(
            state = uiState,
            onEditIncome = { showIncomeSheet = true },
        )

        if (summary.maturities.isNotEmpty()) {
            MaturityCard(summary = summary)
        }

        if (summary.recentActivity.isNotEmpty()) {
            RecentActivityCard(
                investments = summary.recentActivity,
                currencySymbol = uiState.currencySymbol,
            )
        }
    }

    if (showIncomeSheet) {
        IncomeSheet(
            initialSalary = uiState.salary,
            initialRegime = uiState.regime,
            onSave = { salary, regime ->
                coroutineScope.launch {
                    viewModel.setSalary(salary)
                    viewModel.setRegime(regime)
                }
            },
            onDismiss = { showIncomeSheet = false },
        )
    }
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun HeroCard(
    summary: PortfolioSummary,
    currencySymbol: String,
    range: Int,
    onRangeChange: (Int) -> Unit,
) {
    val history = summary.netWorthHistory.takeLast(range)
    val ranges = listOf(3, 6, 12, NET_WORTH_HISTORY_MONTHS)

    DashboardCard {
        Text(
            text = stringResource(R.string.dashboard_net_worth),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = formatAmount(summary.netWorth, currencySymbol),
            style = MaterialTheme.typography.headlineMedium,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Stat(
                label = stringResource(R.string.dashboard_invested),
                value = formatAmount(summary.totalInvested, currencySymbol),
            )

            Spacer(Modifier.width(24.dp))

            Stat(
                label = stringResource(R.string.dashboard_interest),
                value = formatAmount(summary.interestEarned, currencySymbol),
                valueColor = if (summary.interestEarned >= BigDecimal.ZERO) SuccessColor else MaterialTheme.colorScheme.error,
            )
        }

        Row {
            Text(
                text = stringResource(R.string.dashboard_this_month),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.width(4.dp))

            Text(
                text = formatAmount(summary.thisMonthInvested, currencySymbol),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.width(16.dp))

            Text(
                text = stringResource(R.string.dashboard_last_month),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.width(4.dp))

            Text(
                text = formatAmount(summary.lastMonthInvested, currencySymbol),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ranges.forEach { option ->
                FilterChip(
                    selected = range == option,
                    onClick = { onRangeChange(option) },
                    label = {
                        Text(
                            text = when (option) {
                                3 -> stringResource(R.string.dashboard_range_3m)
                                6 -> stringResource(R.string.dashboard_range_6m)
                                12 -> stringResource(R.string.dashboard_range_1y)
                                else -> stringResource(R.string.dashboard_range_max)
                            },
                        )
                    },
                )
            }
        }

        LineChart(
            values = history.map { it.value.toDouble() },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        )

        ChartLabelsRow(history.map { it.label })
    }
}

@Composable
private fun ProjectionCard(
    summary: PortfolioSummary,
    currencySymbol: String,
    months: Int,
    onMonthsChange: (Int) -> Unit,
) {
    val points = remember(summary, months) {
        projectionPoints(summary.today, months, summary.netWorth, summary.avgMonthly)
    }
    val projected = points.lastOrNull()?.value ?: summary.netWorth

    DashboardCard {
        Text(
            text = stringResource(R.string.dashboard_projection),
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            text = formatAmount(projected, currencySymbol),
            style = MaterialTheme.typography.headlineSmall,
        )

        Slider(
            value = months.toFloat(),
            onValueChange = { onMonthsChange(it.roundToInt()) },
            valueRange = 1f..60f,
            steps = 58,
        )

        Text(
            text = stringResource(R.string.dashboard_projection_months, months),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BarChart(
            values = points.map { it.value.toDouble() },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        )

        ChartLabelsRow(points.map { it.label })
    }
}

@Composable
private fun MonthlyCard(
    summary: PortfolioSummary,
    currencySymbol: String,
    monthlyTarget: Double,
) {
    val progress = if (monthlyTarget > 0.0) {
        (summary.thisMonthInvested.toDouble() / monthlyTarget).toFloat()
    } else {
        0f
    }

    DashboardCard {
        Text(
            text = stringResource(R.string.dashboard_monthly),
            style = MaterialTheme.typography.titleMedium,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = progress,
                modifier = Modifier.size(96.dp),
                center = {
                    Text(
                        text = formatAmount(summary.thisMonthInvested, currencySymbol),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                },
            )

            Spacer(Modifier.width(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Stat(
                    label = stringResource(R.string.dashboard_this_month),
                    value = formatAmount(summary.thisMonthInvested, currencySymbol),
                )

                Stat(
                    label = stringResource(R.string.dashboard_target),
                    value = formatAmount(BigDecimal(monthlyTarget), currencySymbol),
                )

                Stat(
                    label = stringResource(R.string.dashboard_last_month),
                    value = formatAmount(summary.lastMonthInvested, currencySymbol),
                )
            }
        }
    }
}

@Composable
private fun TrendCard(
    summary: PortfolioSummary,
    currencySymbol: String,
) {
    val last12 = summary.monthlyHistory.takeLast(12)

    DashboardCard {
        Text(
            text = stringResource(R.string.dashboard_trend),
            style = MaterialTheme.typography.titleMedium,
        )

        BarChart(
            values = last12.map { it.value.toDouble() },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            color = MaterialTheme.colorScheme.secondary,
        )

        ChartLabelsRow(last12.map { it.label })

        Text(
            text = stringResource(R.string.dashboard_trend_total, formatAmount(summary.yearInvested, currencySymbol)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AllocationCard(
    summary: PortfolioSummary,
    currencySymbol: String,
) {
    val totals = summary.typeTotals.sortedByDescending { it.total }
    val netWorthValue = summary.netWorth.toDouble()

    DashboardCard {
        Text(
            text = stringResource(R.string.dashboard_allocation),
            style = MaterialTheme.typography.titleMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DonutChart(
                segments = totals.mapIndexed { index, type ->
                    AllocationColors[index % AllocationColors.size] to type.total.toDouble()
                },
                modifier = Modifier.size(140.dp),
                center = {
                    Text(
                        text = formatAmount(summary.netWorth, currencySymbol),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                },
            )

            Spacer(Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                totals.forEachIndexed { index, type ->
                    AllocationRow(
                        color = AllocationColors[index % AllocationColors.size],
                        type = type,
                        netWorthValue = netWorthValue,
                        currencySymbol = currencySymbol,
                    )
                }
            }
        }
    }
}

@Composable
private fun AllocationRow(
    color: Color,
    type: TypeValuation,
    netWorthValue: Double,
    currencySymbol: String,
) {
    val percent = if (netWorthValue > 0.0) {
        (type.total.toDouble() * 100.0 / netWorthValue).let {
            if (it % 1.0 == 0.0) it.toInt().toString() else String.format("%.1f", it)
        }
    } else {
        "0"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .padding(0.dp),
        ) {
            Surface(
                color = color,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.size(10.dp),
            ) {}
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = type.type,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = "$percent% · ${formatAmount(type.total, currencySymbol)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Tax80cCard(
    state: DashboardUiState,
    onEditIncome: () -> Unit,
) {
    val tax80c = state.summary.tax80c.toDouble()
    val tax = remember(state.salary, state.regime, tax80c) {
        calculateStrictTax(state.salary, state.regime, tax80c)
    }
    val liabilityColor = when {
        tax.status.isNotBlank() -> SuccessColor
        tax.liability > BigDecimal.ZERO -> WarningColor
        else -> SuccessColor
    }

    DashboardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.dashboard_80c),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )

            TextButton(onClick = onEditIncome) {
                Text(stringResource(R.string.dashboard_edit_income))
            }
        }

        if (state.salary <= 0.0) {
            Text(
                text = stringResource(R.string.dashboard_80c_setup),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row {
                Stat(
                    label = stringResource(R.string.dashboard_80c_invested),
                    value = formatAmount(state.summary.tax80c, state.currencySymbol),
                )

                Spacer(Modifier.width(24.dp))

                Stat(
                    label = stringResource(R.string.dashboard_80c_liability),
                    value = if (tax.status.isNotBlank()) {
                        tax.status
                    } else {
                        formatAmount(tax.liability, state.currencySymbol)
                    },
                    valueColor = liabilityColor,
                )
            }

            Text(
                text = stringResource(
                    if (state.regime == "new") R.string.dashboard_80c_regime_new else R.string.dashboard_80c_regime_old,
                    formatAmount(BigDecimal(state.salary), state.currencySymbol),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MaturityCard(summary: PortfolioSummary) {
    DashboardCard {
        Text(
            text = stringResource(R.string.dashboard_maturities),
            style = MaterialTheme.typography.titleMedium,
        )

        summary.maturities.take(5).forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.investment.note.ifBlank { entry.investment.type },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = formatInvestmentDate(entry.investment.maturityDate ?: entry.investment.date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = when {
                        entry.daysLeft < 0 -> stringResource(R.string.dashboard_days_ago, -entry.daysLeft)
                        entry.daysLeft == 0L -> stringResource(R.string.dashboard_today)
                        else -> stringResource(R.string.dashboard_days_left, entry.daysLeft)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RecentActivityCard(
    investments: List<Investment>,
    currencySymbol: String,
) {
    DashboardCard {
        Text(
            text = stringResource(R.string.dashboard_recent_activity),
            style = MaterialTheme.typography.titleMedium,
        )

        investments.forEach { investment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = investment.note.ifBlank { investment.type },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = "${formatInvestmentDate(investment.date)} · ${investment.type}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = formatAmount(investment.amount, currencySymbol),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
        )
    }
}
