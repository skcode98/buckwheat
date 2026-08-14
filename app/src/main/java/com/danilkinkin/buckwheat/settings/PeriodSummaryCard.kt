package com.danilkinkin.buckwheat.settings

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.analytics.dailySpendTotals
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorBad
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.ui.colorMax
import com.danilkinkin.buckwheat.ui.colorMin
import com.danilkinkin.buckwheat.ui.colorNotGood
import com.danilkinkin.buckwheat.util.HarmonizedColorPalette
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.harmonize
import com.danilkinkin.buckwheat.util.harmonizeWithColor
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.smoothPath
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Date

private val categoryProgressColors = listOf(
    Color(0xFFFF8A80),
    Color(0xFFFFB74D),
    Color(0xFF00A896),
    Color(0xFFFDD835),
    Color(0xFFAED581),
)

@Composable
fun PeriodSummaryCard(
    summary: PeriodSummary,
    currency: ExtendCurrency,
    categoryEmojis: Map<String, String> = emptyMap(),
    spends: List<Transaction> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val totalDays = countDays(summary.endDate, summary.startDate).coerceAtLeast(0)
    val avgDaily = if (totalDays > 0) {
        summary.totalSpent.divide(BigDecimal(totalDays), 2, RoundingMode.HALF_EVEN)
    } else {
        BigDecimal.ZERO
    }

    val dailyTotals = remember(spends, summary.startDate, summary.endDate) {
        dailySpendTotals(spends, summary.startDate, summary.endDate)
    }
    val peakDay = dailyTotals
        .filter { it > BigDecimal.ZERO }
        .maxOrNull()
    val peakDayIndex = if (peakDay != null) dailyTotals.indexOf(peakDay) else null
    val peakDayDate = peakDayIndex?.let {
        summary.startDate.toLocalDate().plusDays(it.toLong()).toDate()
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFEEEEEE),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            SummaryHeader(summary = summary)

            Spacer(Modifier.height(16.dp))

            SpentBanner(
                summary = summary,
                currency = currency,
            )

            Spacer(Modifier.height(12.dp))

            ExpenditureChartCard(
                spends = spends,
                startDate = summary.startDate,
                endDate = summary.endDate,
                budget = summary.budget,
                currency = currency,
                peakDayTotal = peakDay,
                peakDayDate = peakDayDate,
                avgDaily = avgDaily,
            )

            Spacer(Modifier.height(16.dp))

            MetricGrid(
                summary = summary,
                currency = currency,
                avgDaily = avgDaily,
                peakDay = summary.biggestDay,
            )

            if (summary.categories.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.categories_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF1C1B1F),
                )
                Spacer(Modifier.height(12.dp))
                summary.categories.forEachIndexed { index, category ->
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
                    val fraction = if (summary.totalSpent > BigDecimal.ZERO) {
                        (category.total.toFloat() / summary.totalSpent.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    CategoryRow(
                        name = name,
                        emoji = emoji,
                        amount = numberFormat(context, category.total, currency = currency),
                        fraction = fraction,
                        color = categoryProgressColors[index % categoryProgressColors.size],
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryHeader(summary: PeriodSummary) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.period_summary_card_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF1C1B1F),
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFFE0E0E0),
                contentColor = Color(0xFF1C1B1F),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_calendar),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            R.string.past_periods_date_range,
                            prettyDate(summary.startDate, showTime = false, forceShowDate = true),
                            prettyDate(summary.endDate, showTime = false, forceShowDate = true),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        IconButton(
            onClick = {},
            modifier = Modifier.size(40.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = Color(0xFFD6D6D6),
                contentColor = Color(0xFF1C1B1F),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpentBanner(
    summary: PeriodSummary,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current
    val hasBudget = summary.budget > BigDecimal.ZERO
    val utilization = if (summary.budget > BigDecimal.ZERO) {
        (summary.totalSpent.toFloat() / summary.budget.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFFFFF),
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.spent_budget),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF757575),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = numberFormat(context, summary.totalSpent, currency = currency),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFF1C1B1F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (hasBudget) {
                    val percent = summary.spentPercent?.let { "$it%" } ?: "-"
                    Text(
                        text = stringResource(R.string.period_summary_utilized, percent),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF757575),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.period_summary_no_budget),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF757575),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            CircularProgressIndicator(
                progress = utilization,
                modifier = Modifier.size(56.dp),
                color = if (hasBudget && summary.spentPercent != null && summary.spentPercent > 100) {
                    Color(0xFFFF8A80)
                } else {
                    Color(0xFFFFB74D)
                },
                trackColor = Color(0xFFE0E0E0),
                strokeWidth = 4.dp,
            )
        }
    }
}

@Composable
private fun ExpenditureChartCard(
    spends: List<Transaction>,
    startDate: Date,
    endDate: Date,
    budget: BigDecimal,
    currency: ExtendCurrency,
    peakDayTotal: BigDecimal?,
    peakDayDate: Date?,
    avgDaily: BigDecimal,
) {
    val context = LocalContext.current
    val dailyTotals = remember(spends, startDate, endDate) {
        dailySpendTotals(spends, startDate, endDate)
    }
    val totalSpent = remember(dailyTotals) {
        dailyTotals.fold(BigDecimal.ZERO) { acc, value -> acc + value }
    }

    var selectedDay by remember { mutableStateOf<Int?>(null) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFFFFF),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.period_summary_expenditure),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF757575),
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = numberFormat(context, totalSpent, currency = currency),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF1C1B1F),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (peakDayTotal != null && peakDayDate != null && peakDayTotal > BigDecimal.ZERO) {
                        Text(
                            text = stringResource(
                                R.string.period_summary_peak_spend,
                                numberFormat(context, peakDayTotal, currency = currency),
                                prettyDate(peakDayDate, showTime = false, forceShowDate = true, shortMonth = true),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF1C1B1F),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF5F5F5),
            ) {
                ExpenditureAreaChart(
                    modifier = Modifier.fillMaxSize(),
                    dailyTotals = dailyTotals,
                    selectedDay = selectedDay,
                    maxColor = toPalette(harmonize(colorMax)).main,
                    minColor = toPalette(harmonize(colorMin)).main,
                    lineColor = MaterialTheme.colorScheme.primary,
                    cardColor = Color(0xFFF5F5F5),
                    budget = budget,
                    budgetLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    onDayTap = { selectedDay = it },
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    val day = selectedDay
                    if (day != null && day in dailyTotals.indices) {
                        val detailDate = remember(startDate, day) {
                            startDate.toLocalDate().plusDays(day.toLong()).toDate()
                        }
                        Text(
                            text = "${prettyDate(detailDate, showTime = false, forceShowDate = true, shortMonth = true)} · ${numberFormat(context, dailyTotals[day], currency)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.period_summary_tap_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF757575),
                        )
                    }
                    if (budget > BigDecimal.ZERO) {
                        Text(
                            text = stringResource(R.string.period_summary_no_budget),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF1C1B1F),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.avg_per_day,
                        numberFormat(context, avgDaily, currency = currency),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF757575),
                )
            }
        }
    }
}

@Composable
private fun MetricGrid(
    summary: PeriodSummary,
    currency: ExtendCurrency,
    avgDaily: BigDecimal,
    peakDay: PeriodSummaryDay?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.period_summary_started),
                value = prettyDate(
                    summary.startDate,
                    showTime = false,
                    forceShowDate = true,
                    shortMonth = true,
                ),
                icon = "📅",
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.period_summary_ended),
                value = prettyDate(
                    summary.endDate,
                    showTime = false,
                    forceShowDate = true,
                    shortMonth = true,
                ),
                icon = "🏁",
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.period_summary_spends_count),
                value = summary.spendsCount.toString(),
                icon = "🧾",
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.period_summary_no_spend_days),
                value = summary.noSpendDays.toString(),
                icon = "🚫",
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.period_summary_biggest_day),
                value = if (peakDay != null) {
                    numberFormat(LocalContext.current, peakDay.total, currency = currency)
                } else {
                    "-"
                },
                caption = peakDay?.let {
                    prettyDate(
                        it.date.toDate(),
                        showTime = false,
                        forceShowDate = true,
                        shortMonth = true,
                    )
                },
                icon = "🏆",
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.period_summary_avg_day),
                value = if (avgDaily > BigDecimal.ZERO) {
                    numberFormat(LocalContext.current, avgDaily, currency = currency)
                } else {
                    "-"
                },
                icon = "📊",
            )
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: String,
    caption: String? = null,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFFFFF),
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = icon,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF757575),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF1C1B1F),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF757575),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    name: String,
    emoji: String,
    amount: String,
    fraction: Float,
    color: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = Color(0xFFFFFFFF),
            contentColor = Color(0xFF1C1B1F),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1C1B1F),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = amount,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF1C1B1F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = color,
                trackColor = Color(0xFFE0E0E0),
            )
        }
    }
}

// The whole-period expenditure as an interactive area-line chart: the trend line + gradient
// area fill the card as the background, the max/min days get colored markers, tapping a point
// reveals that day's total, and the title/budget/avg text is overlaid on top.
@Composable
private fun ExpenditureAreaChart(
    modifier: Modifier = Modifier,
    dailyTotals: List<BigDecimal>,
    selectedDay: Int?,
    maxColor: Color,
    minColor: Color,
    lineColor: Color,
    cardColor: Color,
    budget: BigDecimal,
    budgetLineColor: Color,
    onDayTap: (Int) -> Unit,
) {
    Canvas(
        modifier = modifier
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

        val topInset = 8.dp.toPx()
        val bottomInset = 8.dp.toPx()
        val chartBottom = size.height - bottomInset
        val chartHeight = (size.height - topInset - bottomInset).coerceAtLeast(0f)
        val slotWidth = size.width / dailyTotals.size
        val maxDaily = dailyTotals.maxOrNull() ?: BigDecimal.ZERO
        val maxIndex = if (maxDaily > BigDecimal.ZERO) dailyTotals.indexOf(maxDaily) else null
        // Highlight the lowest real spend day, never a zero-spend gap in the month.
        val lowestSpentDay = dailyTotals.filter { it > BigDecimal.ZERO }.minOrNull()
        val minIndex = if (lowestSpentDay != null) dailyTotals.indexOf(lowestSpentDay) else null

        fun yFor(value: BigDecimal): Float {
            val fraction = if (maxDaily == BigDecimal.ZERO) {
                0f
            } else {
                value.divide(maxDaily, 4, RoundingMode.HALF_EVEN).toFloat()
            }
            return chartBottom - chartHeight * fraction
        }

        fun drawMarker(index: Int?, color: Color) {
            if (index == null) return
            val center = Offset((index + 0.5f) * slotWidth, yFor(dailyTotals[index]))
            drawCircle(color = cardColor, radius = 6.dp.toPx(), center = center)
            drawCircle(color = color, radius = 3.dp.toPx(), center = center)
        }

        // Smooth curve points.
        val curvePoints = dailyTotals.mapIndexed { index, value ->
            Offset((index + 0.5f) * slotWidth, yFor(value))
        }
        val curvePath = smoothPath(curvePoints)

        // Gradient area under the smooth curve.
        val areaPath = Path().apply {
            addPath(curvePath)
            lineTo((dailyTotals.size - 0.5f) * slotWidth, chartBottom)
            lineTo(slotWidth / 2f, chartBottom)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                0f to lineColor.copy(alpha = 0.28f),
                1f to lineColor.copy(alpha = 0.02f),
                startY = topInset,
                endY = chartBottom,
            ),
        )

        // Smooth trend line.
        drawPath(
            path = curvePath,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx()),
        )

        // Full-budget reference line, drawn only when it fits inside the chart.
        if (budget > BigDecimal.ZERO && maxDaily > BigDecimal.ZERO && budget <= maxDaily) {
            val budgetY = yFor(budget)
            drawLine(
                color = budgetLineColor,
                start = Offset(0f, budgetY),
                end = Offset(size.width, budgetY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
        }

        drawMarker(maxIndex, maxColor)
        drawMarker(minIndex, minColor)

        // Tapped day gets a guide line + a filled highlight point on top.
        val selected = selectedDay
        if (selected != null && selected in dailyTotals.indices) {
            val cx = (selected + 0.5f) * slotWidth
            val cy = yFor(dailyTotals[selected])
            drawLine(
                color = lineColor.copy(alpha = 0.25f),
                start = Offset(cx, chartBottom),
                end = Offset(cx, topInset),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(color = cardColor, radius = 8.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = lineColor, radius = 5.dp.toPx(), center = Offset(cx, cy))
        }
    }
}

@Preview(name = "Summary", widthDp = 360)
@Preview(name = "Summary (Dark mode)", widthDp = 360, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewSummary() {
    BuckwheatTheme {
        val previewSpends = listOf(
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
            Transaction(
                type = TransactionType.SPENT,
                value = BigDecimal("200"),
                date = LocalDate.of(2026, 7, 12).toDate(),
                comment = "coffee",
                category = "FOOD",
            ),
        )
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
                    spends = previewSpends,
                ),
                spends = previewSpends,
                currency = ExtendCurrency.getInstance("INR"),
            )
        }
    }
}
