package com.danilkinkin.buckwheat.analytics

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorBad
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import java.math.BigDecimal
import java.util.Date

data class PeriodComparison(
    val currentSpent: BigDecimal,
    val previousSpent: BigDecimal,
) {
    val delta: BigDecimal
        get() = currentSpent - previousSpent

    // Percentage change vs the previous period, or null when there is nothing to compare against.
    val percentChange: BigDecimal?
        get() = if (previousSpent.signum() == 0) {
            null
        } else {
            (currentSpent - previousSpent) * 100.toBigDecimal() / previousSpent
        }
}

// The most recent finished period that started before the current one, so the comparison is
// always "same offset from the period start" rather than against the full previous period.
// CSV-imported month buckets are excluded (mirrors SpendsTrendCard.previousPeriodBefore),
// so the card never compares against archive artifacts.
fun findPreviousPeriod(periods: List<BudgetPeriod>, currentStart: Date): BudgetPeriod? =
    periods
        .filter { !it.isImported && it.finishDate.before(currentStart) }
        .maxByOrNull { it.finishDate }

// Sum of the previous period's spends that happened within the same number of elapsed days as
// the current period has progressed, so early in a period the card doesn't look like a huge
// improvement just because the full previous period hasn't been spent yet.
fun previousSpentAtSameElapsedDays(
    archivedTransactions: List<ArchivedTransaction>,
    periodId: Int,
    previousStart: Date,
    elapsedDays: Int,
): BigDecimal {
    val elapsed = elapsedDays.coerceAtLeast(1)
    val cutoff = previousStart
        .toLocalDate()
        .plusDays((elapsed - 1).toLong())
        .toDate()

    return archivedTransactions
        .filter {
            it.periodId == periodId &&
                it.type == TransactionType.SPENT &&
                !it.date.after(cutoff)
        }
        .fold(BigDecimal.ZERO) { acc, tx -> acc + tx.value }
}

fun formatPercent(value: BigDecimal): String {
    val scaled = value
        .setScale(1, java.math.RoundingMode.HALF_EVEN)
        .stripTrailingZeros()
    val sign = if (scaled.signum() < 0) "-" else "+"
    return "$sign${scaled.abs().toPlainString()}%"
}

@Composable
fun CompareToLastPeriodCard(
    modifier: Modifier = Modifier,
    currentSpent: BigDecimal,
    archivedTransactions: List<ArchivedTransaction>,
    previousPeriod: BudgetPeriod?,
    elapsedDays: Int,
    currency: ExtendCurrency,
) {
    if (previousPeriod == null) return

    val context = LocalContext.current

    val previousSpent = remember(previousPeriod, archivedTransactions, elapsedDays) {
        previousSpentAtSameElapsedDays(
            archivedTransactions = archivedTransactions,
            periodId = previousPeriod.id,
            previousStart = previousPeriod.startDate,
            elapsedDays = elapsedDays,
        )
    }
    val comparison = remember(currentSpent, previousSpent) {
        PeriodComparison(currentSpent, previousSpent)
    }
    val deltaColor = when {
        comparison.delta.signum() > 0 -> colorBad
        comparison.delta.signum() < 0 -> colorGood
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val deltaText = remember(comparison.delta, currency) {
        val sign = if (comparison.delta.signum() < 0) "-" else "+"
        "$sign${numberFormat(context, comparison.delta.abs(), currency)}"
    }
    val percentText = remember(comparison.percentChange) {
        comparison.percentChange?.let { formatPercent(it) }
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
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.compare_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.compare_this_period),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = numberFormat(context, comparison.currentSpent, currency),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.compare_previous_period),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = numberFormat(context, comparison.previousSpent, currency),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (percentText == null) deltaText else "$deltaText  ($percentText)",
                style = MaterialTheme.typography.titleMedium,
                color = deltaColor,
            )
        }
    }
}

@Preview(name = "Comparison", widthDp = 360)
@Preview(name = "Comparison (Dark mode)", widthDp = 360, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewComparison() {
    BuckwheatTheme {
        CompareToLastPeriodCard(
            modifier = Modifier.fillMaxWidth(),
            currentSpent = BigDecimal(150),
            archivedTransactions = listOf(
                ArchivedTransaction(
                    periodId = 1,
                    type = TransactionType.SPENT,
                    value = BigDecimal(100),
                    date = Date(),
                    comment = "previous spend",
                ),
            ),
            previousPeriod = BudgetPeriod(
                budget = BigDecimal(500),
                startDate = Date(),
                finishDate = Date(),
                actualFinishDate = null,
                currencyCode = "USD",
                totalSpent = BigDecimal.ZERO,
            ).also { it.id = 1 },
            elapsedDays = 3,
            currency = ExtendCurrency.getInstance("USD"),
        )
    }
}
