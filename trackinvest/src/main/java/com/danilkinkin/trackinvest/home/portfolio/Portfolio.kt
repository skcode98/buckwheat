/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.home.portfolio

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.trackinvest.R
import com.danilkinkin.trackinvest.data.PortfolioSummary
import com.danilkinkin.trackinvest.data.TypeValuation
import com.danilkinkin.trackinvest.data.formatInvestmentDate
import com.danilkinkin.trackinvest.home.dashboard.DonutChart
import com.danilkinkin.trackinvest.util.formatAmount
import java.math.BigDecimal

private val SuccessColor = Color(0xFF2E6C29)

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
fun Portfolio() {
    val viewModel: PortfolioViewModel = hiltViewModel()
    val summary by viewModel.summary.observeAsState()
    val currencySymbol by viewModel.currencySymbol.observeAsState("₹")

    if (summary == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val data = summary!!
    if (data.typeTotals.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.portfolio_empty),
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.portfolio_empty_hint),
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
        NetWorthCard(
            summary = data,
            currencySymbol = currencySymbol,
        )

        AssetGridCard(
            summary = data,
            currencySymbol = currencySymbol,
        )

        if (data.maturities.isNotEmpty()) {
            MaturityCard(summary = data)
        }
    }
}

@Composable
private fun PortfolioCard(content: @Composable ColumnScope.() -> Unit) {
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
private fun NetWorthCard(
    summary: PortfolioSummary,
    currencySymbol: String,
) {
    val totals = summary.typeTotals.sortedByDescending { it.total }

    PortfolioCard {
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

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DonutChart(
                segments = totals.mapIndexed { index, type ->
                    AllocationColors[index % AllocationColors.size] to type.total.toDouble()
                },
                modifier = Modifier.size(180.dp),
                center = {
                    Text(
                        text = formatAmount(summary.netWorth, currencySymbol),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                },
            )
        }

        AllocationBar(
            segments = totals.mapIndexed { index, type ->
                AllocationColors[index % AllocationColors.size] to type.total.toDouble()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
        )

        AllocationLegend(
            totals = totals,
            netWorthValue = summary.netWorth.toDouble(),
            currencySymbol = currencySymbol,
        )
    }
}

@Composable
private fun AllocationBar(
    segments: List<Pair<Color, Double>>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val total = segments.sumOf { it.second }
        if (total <= 0.0) return@Canvas
        var startX = 0f
        segments.forEach { (color, value) ->
            val segmentWidth = size.width * (value / total).toFloat()
            drawRect(
                color = color,
                topLeft = Offset(startX, 0f),
                size = Size(segmentWidth, size.height),
            )
            startX += segmentWidth
        }
    }
}

@Composable
private fun AllocationLegend(
    totals: List<TypeValuation>,
    netWorthValue: Double,
    currencySymbol: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        totals.forEachIndexed { index, type ->
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
                Surface(
                    color = AllocationColors[index % AllocationColors.size],
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.size(10.dp),
                ) {}

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
    }
}

@Composable
private fun AssetGridCard(
    summary: PortfolioSummary,
    currencySymbol: String,
) {
    val totals = summary.typeTotals.sortedByDescending { it.total }

    PortfolioCard {
        Text(
            text = stringResource(R.string.portfolio_assets),
            style = MaterialTheme.typography.titleMedium,
        )

        totals.chunked(2).forEach { rowTypes ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowTypes.forEach { type ->
                    TypeCard(
                        type = type,
                        currencySymbol = currencySymbol,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTypes.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TypeCard(
    type: TypeValuation,
    currencySymbol: String,
    modifier: Modifier = Modifier,
) {
    val profit = type.total - type.invested

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = type.type,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = formatAmount(type.total, currencySymbol),
                style = MaterialTheme.typography.bodyLarge,
            )

            if (profit != BigDecimal.ZERO) {
                Text(
                    text = if (profit > BigDecimal.ZERO) {
                        "+${formatAmount(profit, currencySymbol)}"
                    } else {
                        formatAmount(profit, currencySymbol)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (profit > BigDecimal.ZERO) SuccessColor else MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = type.lastDate?.let { stringResource(R.string.portfolio_last, formatInvestmentDate(it)) }
                    ?: stringResource(R.string.portfolio_no_entries),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MaturityCard(summary: PortfolioSummary) {
    PortfolioCard {
        Text(
            text = stringResource(R.string.dashboard_maturities),
            style = MaterialTheme.typography.titleMedium,
        )

        summary.maturities.forEach { entry ->
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
