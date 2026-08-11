package com.danilkinkin.buckwheat.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.analytics.categoriesChart.CapProgressBar
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.di.InterleavedProgress
import com.danilkinkin.buckwheat.editor.category.categoryDisplayName
import com.danilkinkin.buckwheat.interleaved.projectedExhaustionDate
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoUnit

// Progress of every scheduled (interleaved) category budget inside its current window.
// Rendered only when at least one schedule exists; hidden entirely otherwise.
@Composable
fun InterleavedBudgetCard(
    modifier: Modifier = Modifier,
    progress: List<InterleavedProgress>,
    currency: ExtendCurrency,
) {
    if (progress.isEmpty()) return

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = combineColors(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceVariant,
                0.3f,
            ),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp, 16.dp)) {
            Text(
                text = stringResource(R.string.interleaved_budgets_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            progress.forEach { item ->
                InterleavedBudgetRow(item = item, currency = currency)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun InterleavedBudgetRow(
    item: InterleavedProgress,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current
    val cap = item.category.amount
    val rangeStart = prettyDate(item.windowStart.toDate(), "dd MMM", simplifyIfToday = false)
    val rangeEnd = prettyDate(
        item.windowEnd.minusDays(1).toDate(),
        "dd MMM",
        simplifyIfToday = false,
    )

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = SpendCategory.emojiFor(item.category.name, null),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = categoryDisplayName(item.category.name),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.interleaved_window_range, rangeStart, rangeEnd),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.interleaved_spent_of_cap,
                numberFormat(context, item.spent, currency),
                numberFormat(context, cap, currency),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        CapProgressBar(
            progress = item.spent,
            cap = cap,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = velocityText(item, currency),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// "at Rs45/day -> runs out on 12 Sep" while on pace, "at Rs45/day — on track" when the
// window ends first, "Nothing spent yet" when there's no spend.
@Composable
private fun velocityText(item: InterleavedProgress, currency: ExtendCurrency): String {
    val context = LocalContext.current
    if (item.spent <= BigDecimal.ZERO) {
        return stringResource(R.string.interleaved_velocity_no_spend)
    }
    val elapsedDays = ChronoUnit.DAYS.between(item.windowStart, item.today)
        .toInt()
        .coerceAtLeast(1)
    val pace = item.spent.divide(elapsedDays.toBigDecimal(), 2, RoundingMode.HALF_EVEN)
    val paceText = numberFormat(context, pace, currency)
    val exhaustion = projectedExhaustionDate(item.category, item.today, item.spent)
    return if (exhaustion != null) {
        stringResource(
            R.string.interleaved_velocity_runs_out,
            paceText,
            prettyDate(exhaustion.toDate(), "dd MMM", simplifyIfToday = false),
        )
    } else {
        stringResource(R.string.interleaved_velocity_on_track, paceText)
    }
}
