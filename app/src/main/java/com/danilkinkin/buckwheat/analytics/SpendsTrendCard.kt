package com.danilkinkin.buckwheat.analytics

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorBad
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.ui.colorMax
import com.danilkinkin.buckwheat.ui.colorMin
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.harmonize
import com.danilkinkin.buckwheat.util.isZero
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.roundToDay
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun SpendsTrendCard(
    modifier: Modifier = Modifier,
    spends: List<Transaction>,
    startDate: Date,
    finishDate: Date,
    currency: ExtendCurrency,
    periods: List<BudgetPeriod>,
) {
    val context = LocalContext.current

    val totalSpent = spends.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.value }

    val dailyTotals = remember(spends, startDate, finishDate) {
        dailySpendTotals(spends, startDate, finishDate)
    }
    val maxDaily = dailyTotals.maxOrNull() ?: BigDecimal.ZERO
    val averageDaily = remember(totalSpent, dailyTotals.size) {
        if (dailyTotals.isEmpty()) {
            BigDecimal.ZERO
        } else {
            totalSpent.divide(BigDecimal(dailyTotals.size), 2, RoundingMode.HALF_EVEN)
        }
    }
    val previousPeriod = remember(periods, startDate) {
        previousPeriodBefore(periods, startDate)
    }
    var selectedDay by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.monthly_trend_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = numberFormat(context, totalSpent, currency),
                        style = MaterialTheme.typography.displayMedium,
                        fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    )
                }

                if (previousPeriod != null && !previousPeriod.totalSpent.isZero()) {
                    val deltaPercent = totalSpent
                        .divide(previousPeriod.totalSpent, 2, RoundingMode.HALF_EVEN)
                        .multiply(BigDecimal(100))
                        .subtract(BigDecimal(100))

                    val deltaColor = when {
                        deltaPercent.signum() > 0 -> toPalette(harmonize(colorBad)).main
                        deltaPercent.signum() < 0 -> toPalette(harmonize(colorGood)).main
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.vs_previous_period),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatDeltaPercent(deltaPercent),
                            style = MaterialTheme.typography.titleMedium,
                            color = deltaColor,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val maxColor = toPalette(harmonize(colorMax))
            val minColor = toPalette(harmonize(colorMin))
            val barColors = remember(dailyTotals, maxDaily, maxColor, minColor) {
                dailyTotals.map { value ->
                    val fraction = if (maxDaily.isZero()) {
                        0f
                    } else {
                        value.divide(maxDaily, 4, RoundingMode.HALF_EVEN).toFloat()
                    }
                    combineColors(minColor.main, maxColor.main, fraction)
                }
            }

            SpendsTrendBars(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                dailyTotals = dailyTotals,
                maxDaily = maxDaily,
                barColors = barColors,
                averageDaily = averageDaily,
                selectedDay = selectedDay,
                highlightColor = maxColor.main,
                averageLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                onDayTap = { selectedDay = it },
            )

            val day = selectedDay
            if (day != null && day in dailyTotals.indices) {
                Spacer(modifier = Modifier.height(8.dp))
                val detailDate = remember(startDate, day) {
                    startDate.toLocalDate().plusDays(day.toLong()).toDate()
                }
                Text(
                    text = "${prettyDate(detailDate, showTime = false, forceShowDate = true, shortMonth = true)}" +
                        " · ${numberFormat(context, dailyTotals[day], currency)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val months = remember(startDate, finishDate) {
                monthDayCounts(startDate, finishDate)
            }
            if (months.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    months.forEach { (month, count) ->
                        Text(
                            text = shortMonthLabel(month),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(count.toFloat()),
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = prettyDate(
                        startDate,
                        showTime = false,
                        forceShowDate = true,
                        shortMonth = true,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(
                        R.string.avg_per_day,
                        numberFormat(context, averageDaily, currency),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = prettyDate(
                        finishDate,
                        showTime = false,
                        forceShowDate = true,
                        shortMonth = true,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpendsTrendBars(
    modifier: Modifier = Modifier,
    dailyTotals: List<BigDecimal>,
    maxDaily: BigDecimal,
    barColors: List<Color>,
    averageDaily: BigDecimal,
    selectedDay: Int?,
    highlightColor: Color,
    averageLineColor: Color,
    onDayTap: (Int) -> Unit,
) {
    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(dailyTotals.size) {
                detectTapGestures { offset ->
                    if (dailyTotals.isNotEmpty()) {
                        val index = (offset.x / (size.width / dailyTotals.size))
                            .toInt()
                            .coerceIn(0, dailyTotals.size - 1)
                        onDayTap(index)
                    }
                }
            }
    ) {
        if (dailyTotals.isEmpty()) return@Canvas

        val slotWidth = size.width / dailyTotals.size
        val barWidth = slotWidth * 0.6f
        val barHeightMin = size.height * 0.01f

        dailyTotals.forEachIndexed { index, value ->
            val fraction = if (maxDaily.isZero()) {
                0f
            } else {
                value.divide(maxDaily, 4, RoundingMode.HALF_EVEN).toFloat()
            }
            val barHeight = (size.height * fraction).coerceAtLeast(barHeightMin)

            drawRoundRect(
                color = if (index == selectedDay) highlightColor else barColors[index],
                topLeft = Offset(
                    x = index * slotWidth + (slotWidth - barWidth) / 2f,
                    y = size.height - barHeight,
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }

        if (!maxDaily.isZero() && averageDaily > BigDecimal.ZERO) {
            val avgFraction = averageDaily.divide(maxDaily, 4, RoundingMode.HALF_EVEN).toFloat()
            val avgY = size.height * (1f - avgFraction)
            drawLine(
                color = averageLineColor,
                start = Offset(0f, avgY),
                end = Offset(size.width, avgY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
        }
    }
}

private fun formatDeltaPercent(deltaPercent: BigDecimal): String {
    val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
    formatter.maximumFractionDigits = 1
    formatter.minimumFractionDigits = 0

    return when {
        deltaPercent.signum() > 0 -> "+${formatter.format(deltaPercent)}%"
        deltaPercent.signum() < 0 -> "-${formatter.format(deltaPercent.abs())}%"
        else -> "0%"
    }
}

@Composable
private fun shortMonthLabel(month: YearMonth): String {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = if (month.year == LocalDate.now().year) {
        "MMM"
    } else {
        "MMM ''yy"
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(month.atDay(1))
}

// Aggregates spends into one total per day-of-period (index 0 = startDate).
// Rows outside the period are ignored.
fun dailySpendTotals(
    spends: List<Transaction>,
    startDate: Date,
    finishDate: Date,
): List<BigDecimal> {
    val days = countDays(finishDate, startDate)
    if (days <= 0) return emptyList()

    val startDay = roundToDay(startDate)
    val totals = MutableList(days) { BigDecimal.ZERO }
    spends.forEach { tx ->
        if (roundToDay(tx.date).before(startDay)) return@forEach
        val dayIndex = countDays(tx.date, startDate) - 1
        if (dayIndex in 0 until days) {
            totals[dayIndex] = totals[dayIndex] + tx.value
        }
    }
    return totals
}

// Day-count per calendar month for the [startDate..finishDate] range, so month
// boundaries of a multi-month period can be labeled under the bars.
fun monthDayCounts(startDate: Date, finishDate: Date): List<Pair<YearMonth, Int>> {
    val start = startDate.toLocalDate()
    val finish = finishDate.toLocalDate()
    if (finish.isBefore(start)) return emptyList()

    val counts = LinkedHashMap<YearMonth, Int>()
    var cursor = start.withDayOfMonth(1)
    while (!cursor.isAfter(finish)) {
        val lastOfMonth = cursor.withDayOfMonth(cursor.lengthOfMonth())
        val monthStart = if (cursor.isBefore(start)) start else cursor
        val monthEnd = if (lastOfMonth.isAfter(finish)) finish else lastOfMonth
        val month = YearMonth.from(cursor)
        counts[month] = (counts[month] ?: 0) + monthEnd.dayOfMonth - monthStart.dayOfMonth + 1
        cursor = cursor.plusMonths(1)
    }
    return counts.toList()
}

// The most recently finished real budget period (not a CSV-imported month bucket)
// that ended before the current period started.
fun previousPeriodBefore(
    periods: List<BudgetPeriod>,
    currentStartDate: Date,
): BudgetPeriod? =
    periods
        .asSequence()
        .filter {
            !it.isImported &&
                !effectiveFinishDate(it).toLocalDate().isAfter(roundToDay(currentStartDate).toLocalDate())
        }
        .maxByOrNull { effectiveFinishDate(it) }

@Preview
@Composable
private fun Preview() {
    BuckwheatTheme {
        val start = LocalDate.now().minusDays(10).toDate()
        SpendsTrendCard(
            modifier = Modifier.fillMaxWidth(),
            spends = listOf(
                Transaction(type = TransactionType.SPENT, value = BigDecimal(120), date = LocalDate.now().minusDays(10).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(45), date = LocalDate.now().minusDays(7).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(200), date = LocalDate.now().minusDays(3).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(30), date = LocalDate.now().minusDays(1).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(85), date = LocalDate.now().minusDays(1).toDate()),
            ),
            startDate = start,
            finishDate = Date(),
            currency = ExtendCurrency.none(),
            periods = listOf(
                BudgetPeriod(
                    budget = BigDecimal(1000),
                    startDate = LocalDate.now().minusDays(25).toDate(),
                    finishDate = Date(LocalDate.now().minusDays(11).toDate().time + 86399999),
                    actualFinishDate = null,
                    currencyCode = "USD",
                    totalSpent = BigDecimal(900),
                )
            ),
        )
    }
}

@Preview(name = "Night mode", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewNightMode() {
    BuckwheatTheme {
        val start = LocalDate.now().minusDays(10).toDate()
        SpendsTrendCard(
            modifier = Modifier.fillMaxWidth(),
            spends = listOf(
                Transaction(type = TransactionType.SPENT, value = BigDecimal(120), date = LocalDate.now().minusDays(10).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(200), date = LocalDate.now().minusDays(3).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(85), date = LocalDate.now().minusDays(1).toDate()),
            ),
            startDate = start,
            finishDate = Date(),
            currency = ExtendCurrency.none(),
            periods = emptyList(),
        )
    }
}
