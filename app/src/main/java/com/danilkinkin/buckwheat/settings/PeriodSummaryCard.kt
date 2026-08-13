package com.danilkinkin.buckwheat.settings

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.Divider
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// The single past-period summary card: period dates, budget utilization, totals, biggest/
// lowest spend, biggest day, no-spend days and the per-category breakdown. Pure rendering of
// a PeriodSummary, no business logic here.
@Composable
fun PeriodSummaryCard(
    summary: PeriodSummary,
    currency: ExtendCurrency,
    categoryEmojis: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val restBudget = summary.budget - summary.totalSpent
    val indicatorColor = when {
        restBudget < BigDecimal.ZERO -> MaterialTheme.colorScheme.error
        summary.budget > BigDecimal.ZERO &&
            restBudget.multiply(BigDecimal(100)).divide(summary.budget, 0, RoundingMode.HALF_UP)
            < BigDecimal(20) -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = combineColors(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceVariant,
                angle = 0.3f,
            ),
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.period_summary_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.past_periods_date_range,
                    prettyDate(summary.startDate, showTime = false, forceShowDate = true),
                    prettyDate(summary.endDate, showTime = false, forceShowDate = true),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.spent_budget),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Text(
                        text = numberFormat(context, summary.totalSpent, currency = currency),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (summary.budget > BigDecimal.ZERO) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.whole_budget),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            text = numberFormat(context, summary.budget, currency = currency),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (summary.budget > BigDecimal.ZERO) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = {
                        (summary.totalSpent.toFloat() / summary.budget.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = indicatorColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = if (summary.spentPercent != null) {
                            stringResource(R.string.period_summary_utilized, summary.spentPercent)
                        } else {
                            "-"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = indicatorColor,
                    )
                    Text(
                        text = if (restBudget >= BigDecimal.ZERO) {
                            stringResource(
                                R.string.period_summary_left,
                                numberFormat(context, restBudget, currency = currency),
                            )
                        } else {
                            stringResource(
                                R.string.over_budget_amount,
                                numberFormat(context, -restBudget, currency = currency),
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider()

            Spacer(Modifier.height(16.dp))
            StatRow(
                label = stringResource(R.string.period_summary_started),
                value = prettyDate(summary.startDate, showTime = false, forceShowDate = true),
                valueColor = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            StatRow(
                label = stringResource(R.string.period_summary_ended),
                value = prettyDate(summary.endDate, showTime = false, forceShowDate = true),
                valueColor = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            StatRow(
                label = stringResource(R.string.period_summary_spends_count),
                value = summary.spendsCount.toString(),
                valueColor = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            StatRow(
                label = stringResource(R.string.period_summary_no_spend_days),
                value = summary.noSpendDays.toString(),
                valueColor = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(16.dp))
            Divider()

            Spacer(Modifier.height(16.dp))
            val biggest = summary.biggestSpend
            StatRow(
                label = stringResource(R.string.max_spent),
                value = if (biggest != null) {
                    numberFormat(context, biggest.amount, currency = currency)
                } else {
                    "-"
                },
                valueColor = MaterialTheme.colorScheme.onSurface,
                caption = biggest?.let {
                    prettyDate(it.date, showTime = false, forceShowDate = true)
                },
            )
            Spacer(Modifier.height(12.dp))
            val lowest = summary.lowestSpend
            StatRow(
                label = stringResource(R.string.min_spent),
                value = if (lowest != null) {
                    numberFormat(context, lowest.amount, currency = currency)
                } else {
                    "-"
                },
                valueColor = MaterialTheme.colorScheme.onSurface,
                caption = lowest?.let {
                    prettyDate(it.date, showTime = false, forceShowDate = true)
                },
            )
            Spacer(Modifier.height(12.dp))
            val biggestDay = summary.biggestDay
            StatRow(
                label = stringResource(R.string.period_summary_biggest_day),
                value = if (biggestDay != null) {
                    numberFormat(context, biggestDay.total, currency = currency)
                } else {
                    "-"
                },
                valueColor = MaterialTheme.colorScheme.onSurface,
                caption = biggestDay?.let {
                    prettyDate(
                        it.date.toDate(),
                        showTime = false,
                        forceShowDate = true,
                        shortMonth = true,
                    )
                },
            )

            if (summary.categories.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Divider()

                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.categories_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                summary.categories.forEach { category ->
                    val (name, emoji) = when (val key = category.key) {
                        is CategoryKey.BuiltIn -> Pair(
                            stringResource(key.category.labelRes),
                            key.category.emoji,
                        )
                        is CategoryKey.Custom -> Pair(
                            key.name,
                            SpendCategory.emojiFor(key.name, categoryEmojis[key.name]),
                        )
                    }
                    StatRow(
                        label = "$emoji $name",
                        value = numberFormat(context, category.total, currency = currency),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color,
    caption: String? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(name = "Summary", widthDp = 360)
@Preview(name = "Summary (Dark mode)", widthDp = 360, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewSummary() {
    BuckwheatTheme {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            PeriodSummaryCard(
                summary = buildPeriodSummary(
                    startDate = LocalDate.of(2026, 7, 1).toDate(),
                    finishDate = LocalDate.of(2026, 7, 31).toDate(),
                    actualFinishDate = null,
                    budget = BigDecimal("15000"),
                    spends = listOf(
                        Transaction(
                            type = TransactionType.SPENT,
                            value = BigDecimal("5200"),
                            date = LocalDate.of(2026, 7, 2).toDate(),
                            comment = "rent",
                            category = "BILLS",
                        ),
                        Transaction(
                            type = TransactionType.SPENT,
                            value = BigDecimal("1200"),
                            date = LocalDate.of(2026, 7, 5).toDate(),
                            comment = "groceries",
                            category = "FOOD",
                        ),
                        Transaction(
                            type = TransactionType.SPENT,
                            value = BigDecimal("300"),
                            date = LocalDate.of(2026, 7, 5).toDate(),
                            comment = "bus ticket",
                            category = "TRANSPORT",
                        ),
                        Transaction(
                            type = TransactionType.SPENT,
                            value = BigDecimal("800"),
                            date = LocalDate.of(2026, 7, 20).toDate(),
                            comment = "movie",
                            category = "ENTERTAINMENT",
                        ),
                    ),
                ),
                currency = ExtendCurrency.getInstance("INR"),
            )
        }
    }
}
