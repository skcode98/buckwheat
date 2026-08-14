package com.danilkinkin.buckwheat.settings

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
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
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Date

// The single past-period summary card: period dates, budget utilization, totals, biggest/
// lowest spend, biggest day, no-spend days and the per-category breakdown. Pure rendering of
// a PeriodSummary, no business logic here.
private val categoryColors = listOf(
    Color(0xFFF86BAE),
    Color(0xFFAB96FF),
    Color(0xFF5FC7E7),
    Color(0xFF75E584),
    Color(0xFFFFD386),
    Color(0xFFEF7564),
    Color(0xFFF36FFF),
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

    val restBudget = summary.budget - summary.totalSpent
    val utilization = if (summary.budget > BigDecimal.ZERO) {
        (summary.totalSpent.toFloat() / summary.budget.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val heroPalette = if (summary.budget > BigDecimal.ZERO) {
        toPalette(
            harmonize(
                combineColors(
                    listOf(colorBad, colorNotGood, colorGood),
                    utilization,
                )
            )
        )
    } else {
        toPalette(
            harmonize(
                designColor = MaterialTheme.colorScheme.primary,
                sourceColor = MaterialTheme.colorScheme.primary,
            )
        )
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
            HeaderRow(summary = summary)

            Spacer(Modifier.height(20.dp))

            HeroBlock(
                summary = summary,
                currency = currency,
                heroPalette = heroPalette,
            )

            Spacer(Modifier.height(20.dp))

            ExpenditureGraphCard(
                spends = spends,
                startDate = summary.startDate,
                endDate = summary.endDate,
                budget = summary.budget,
                currency = currency,
            )

            Spacer(Modifier.height(12.dp))

            val totalDays = countDays(summary.endDate, summary.startDate).coerceAtLeast(0)
            val avgDaily = if (totalDays > 0) {
                summary.totalSpent.divide(BigDecimal(totalDays), 2, RoundingMode.HALF_EVEN)
            } else {
                BigDecimal.ZERO
            }

            Row(Modifier.fillMaxWidth()) {
                MiniStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.period_summary_started),
                    value = prettyDate(
                        summary.startDate,
                        showTime = false,
                        forceShowDate = true,
                        shortMonth = true,
                    ),
                    icon = "📅",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                MiniStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.period_summary_ended),
                    value = prettyDate(
                        summary.endDate,
                        showTime = false,
                        forceShowDate = true,
                        shortMonth = true,
                    ),
                    icon = "🏁",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                MiniStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.period_summary_spends_count),
                    value = summary.spendsCount.toString(),
                    icon = "🧾",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                MiniStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.period_summary_no_spend_days),
                    value = summary.noSpendDays.toString(),
                    icon = "🚫",
                )
                Spacer(Modifier.width(8.dp))
                val biggestDay = summary.biggestDay
                MiniStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.period_summary_biggest_day),
                    value = if (biggestDay != null) {
                        numberFormat(context, biggestDay.total, currency = currency)
                    } else {
                        "-"
                    },
                    caption = biggestDay?.let {
                        prettyDate(
                            it.date.toDate(),
                            showTime = false,
                            forceShowDate = true,
                            shortMonth = true,
                        )
                    },
                    icon = "🏆",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                MiniStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.period_summary_avg_day),
                    value = if (totalDays > 0) {
                        numberFormat(context, avgDaily, currency = currency)
                    } else {
                        "-"
                    },
                    icon = "📊",
                )
            }

            if (summary.categories.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.categories_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
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
                    val palette = when (val key = category.key) {
                        is CategoryKey.BuiltIn -> toPalette(
                            harmonizeWithColor(
                                categoryColors[key.category.ordinal % categoryColors.size],
                                MaterialTheme.colorScheme.primary,
                            )
                        )
                        is CategoryKey.Custom -> toPalette(
                            harmonizeWithColor(
                                categoryColors[
                                    Math.floorMod(key.name.hashCode(), categoryColors.size)
                                ],
                                MaterialTheme.colorScheme.primary,
                            )
                        )
                    }
                    CategoryRow(
                        name = name,
                        emoji = emoji,
                        amount = numberFormat(context, category.total, currency = currency),
                        fraction = if (summary.totalSpent > BigDecimal.ZERO) {
                            (category.total.toFloat() / summary.totalSpent.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        palette = palette,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(summary: PeriodSummary) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.period_summary_card_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_analytics),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroBlock(
    summary: PeriodSummary,
    currency: ExtendCurrency,
    heroPalette: HarmonizedColorPalette,
) {
    val context = LocalContext.current
    val hasBudget = summary.budget > BigDecimal.ZERO
    val restBudget = summary.budget - summary.totalSpent
    val percentText = if (summary.spentPercent != null) {
        "${summary.spentPercent}%"
    } else {
        "-"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = heroPalette.container,
        contentColor = heroPalette.onContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.spent_budget),
                        style = MaterialTheme.typography.labelMedium,
                        color = heroPalette.onContainer.copy(alpha = 0.6f),
                    )
                    Text(
                        text = numberFormat(context, summary.totalSpent, currency = currency),
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = heroPalette.main,
                    contentColor = heroPalette.onMain,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = percentText,
                            style = MaterialTheme.typography.titleSmall,
                            fontSize = if (percentText.length > 3) 12.sp else 16.sp,
                        )
                    }
                }
            }

            if (hasBudget) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = {
                        (summary.totalSpent.toFloat() / summary.budget.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50)),
                    color = heroPalette.main,
                    trackColor = heroPalette.onContainer.copy(alpha = 0.15f),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (summary.spentPercent != null) {
                            stringResource(R.string.period_summary_utilized, summary.spentPercent)
                        } else {
                            "-"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = heroPalette.onContainer.copy(alpha = 0.8f),
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
                        color = heroPalette.onContainer.copy(alpha = 0.6f),
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.period_summary_no_budget),
                    style = MaterialTheme.typography.labelSmall,
                    color = heroPalette.onContainer.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun MiniStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    icon: String? = null,
    accentColor: Color? = null,
    caption: String? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = CircleShape,
                        color = accentColor?.copy(alpha = 0.15f) ?: contentColor.copy(alpha = 0.12f),
                        contentColor = accentColor ?: contentColor,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = icon,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// The whole-period expenditure as an interactive area-line chart: the trend line + gradient
// area fill the card as the background, the max/min days get colored markers, tapping a point
// reveals that day's total, and the title/budget/avg text is overlaid on top.
@Composable
private fun ExpenditureGraphCard(
    spends: List<Transaction>,
    startDate: Date,
    endDate: Date,
    budget: BigDecimal,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current
    val dailyTotals = remember(spends, startDate, endDate) {
        dailySpendTotals(spends, startDate, endDate)
    }
    val totalSpent = remember(dailyTotals) {
        dailyTotals.fold(BigDecimal.ZERO) { acc, value -> acc + value }
    }
    val averageDaily = if (dailyTotals.isEmpty()) {
        BigDecimal.ZERO
    } else {
        totalSpent.divide(BigDecimal(dailyTotals.size), 2, RoundingMode.HALF_EVEN)
    }

    var selectedDay by remember { mutableStateOf<Int?>(null) }

    val lineColor = MaterialTheme.colorScheme.primary
    val maxColor = toPalette(harmonize(colorMax)).main
    val minColor = toPalette(harmonize(colorMin)).main

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box {
            ExpenditureAreaChart(
                modifier = Modifier.matchParentSize(),
                dailyTotals = dailyTotals,
                selectedDay = selectedDay,
                maxColor = maxColor,
                minColor = minColor,
                lineColor = lineColor,
                cardColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                budget = budget,
                budgetLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                onDayTap = { selectedDay = it },
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.period_summary_expenditure),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = numberFormat(context, totalSpent, currency = currency),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.weight(1f))

                val day = selectedDay
                if (day != null && day in dailyTotals.indices) {
                    val detailDate = remember(startDate, day) {
                        startDate.toLocalDate().plusDays(day.toLong()).toDate()
                    }
                    Text(
                        text = "${prettyDate(detailDate, showTime = false, forceShowDate = true, shortMonth = true)} · ${numberFormat(context, dailyTotals[day], currency)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = lineColor,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.period_summary_tap_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (budget > BigDecimal.ZERO) {
                            stringResource(
                                R.string.period_summary_budget,
                                numberFormat(context, budget, currency = currency),
                            )
                        } else {
                            stringResource(R.string.period_summary_no_budget)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(
                            R.string.avg_per_day,
                            numberFormat(context, averageDaily, currency = currency),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// Draws the whole-period trend as an area-line chart. The line + gradient fill are confined
// to a middle band so the overlaid title/footer text stays readable; the max and min (real
// spend) days get colored ring markers and a tap anywhere on the chart selects a day.
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

        val topInset = 56.dp.toPx()
        val bottomInset = 64.dp.toPx()
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
            drawCircle(color = cardColor, radius = 8.dp.toPx(), center = center)
            drawCircle(color = color, radius = 5.dp.toPx(), center = center)
        }

        // Gradient area under the trend line.
        val areaPath = Path().apply {
            val firstX = slotWidth / 2f
            moveTo(firstX, chartBottom)
            dailyTotals.forEachIndexed { index, value ->
                lineTo((index + 0.5f) * slotWidth, yFor(value))
            }
            lineTo((dailyTotals.size - 0.5f) * slotWidth, chartBottom)
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

        // Trend line.
        val linePath = Path().apply {
            dailyTotals.forEachIndexed { index, value ->
                val x = (index + 0.5f) * slotWidth
                val y = yFor(value)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = linePath,
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
            drawCircle(color = cardColor, radius = 9.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = lineColor, radius = 6.dp.toPx(), center = Offset(cx, cy))
        }
    }
}

@Composable
private fun CategoryRow(
    name: String,
    emoji: String,
    amount: String,
    fraction: Float,
    palette: HarmonizedColorPalette,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = palette.container,
            contentColor = palette.onContainer,
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
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = amount,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(50))
                        .background(palette.main),
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
