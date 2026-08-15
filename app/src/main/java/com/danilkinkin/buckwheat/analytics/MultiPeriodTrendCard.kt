package com.danilkinkin.buckwheat.analytics

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorBad
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.ui.colorNotGood
import com.danilkinkin.buckwheat.util.harmonize
import com.danilkinkin.buckwheat.util.isZero
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.smoothPath
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun MultiPeriodTrendCard(
    modifier: Modifier = Modifier,
    points: List<MultiPeriodPoint>,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current
    val maxValue = remember(points) {
        points.maxOf { maxOf(it.spent, it.budget) }
    }
    val totalSpent = remember(points) {
        points.fold(BigDecimal.ZERO) { acc, point -> acc + point.spent }
    }
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    val lineColor = MaterialTheme.colorScheme.primary
    val budgetLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

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
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.month_over_month_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = numberFormat(context, totalSpent, currency),
                        style = MaterialTheme.typography.displayMedium,
                        fontSize = MaterialTheme.typography.titleLarge.fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    LegendItem(
                        text = stringResource(R.string.month_over_month_spent),
                        color = lineColor,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LegendItem(
                        text = stringResource(R.string.month_over_month_budget),
                        color = budgetLineColor,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            MultiPeriodTrendChart(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                points = points,
                maxValue = maxValue,
                lineColor = lineColor,
                budgetLineColor = budgetLineColor,
                surfaceColor = MaterialTheme.colorScheme.surface,
                selectedIndex = selectedIndex,
                onPointTap = { selectedIndex = it },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth()) {
                points.forEach { point ->
                    Text(
                        text = point.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (point.isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (point.isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val index = selectedIndex
            if (index != null && index in points.indices) {
                Spacer(modifier = Modifier.height(8.dp))
                val point = points[index]
                val spent = numberFormat(context, point.spent, currency)
                val text = if (point.budget > BigDecimal.ZERO) {
                    val percent = point.spent
                        .divide(point.budget, 2, RoundingMode.HALF_EVEN)
                        .multiply(BigDecimal(100))
                        .toInt()
                    if (point.spent > point.budget) {
                        stringResource(
                            R.string.month_over_month_point_over,
                            point.label,
                            spent,
                            numberFormat(context, point.spent - point.budget, currency),
                        )
                    } else {
                        stringResource(
                            R.string.month_over_month_point,
                            point.label,
                            spent,
                            numberFormat(context, point.budget, currency),
                            percent,
                        )
                    }
                } else {
                    stringResource(R.string.month_over_month_point_plain, point.label, spent)
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LegendItem(text: String, color: Color) {
    Row {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MultiPeriodTrendChart(
    modifier: Modifier = Modifier,
    points: List<MultiPeriodPoint>,
    maxValue: BigDecimal,
    lineColor: Color,
    budgetLineColor: Color,
    surfaceColor: Color,
    selectedIndex: Int?,
    onPointTap: (Int) -> Unit,
) {
    val goodColor = toPalette(harmonize(colorGood)).main
    val notGoodColor = toPalette(harmonize(colorNotGood)).main
    val badColor = toPalette(harmonize(colorBad)).main

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(points.size) {
                detectTapGestures { offset ->
                    if (points.isNotEmpty()) {
                        val index = (offset.x / (size.width / points.size))
                            .toInt()
                            .coerceIn(0, points.size - 1)
                        onPointTap(index)
                    }
                }
            }
    ) {
        if (points.isEmpty()) return@Canvas

        val topInset = 12.dp.toPx()
        val bottomInset = 6.dp.toPx()
        val chartBottom = size.height - bottomInset
        val chartHeight = (size.height - topInset - bottomInset).coerceAtLeast(0f)
        val slotWidth = size.width / points.size

        fun yFor(value: BigDecimal): Float {
            val fraction = if (maxValue.isZero()) {
                0f
            } else {
                value.divide(maxValue, 4, RoundingMode.HALF_EVEN).toFloat()
            }
            return chartBottom - chartHeight * fraction
        }

        val spentPoints = points.mapIndexed { index, point ->
            Offset((index + 0.5f) * slotWidth, yFor(point.spent))
        }
        val budgetPoints = points.mapIndexed { index, point ->
            Offset((index + 0.5f) * slotWidth, yFor(point.budget))
        }

        val spentPath = smoothPath(spentPoints)
        val budgetPath = smoothPath(budgetPoints)

        // Gradient area under the spent line.
        val areaPath = Path().apply {
            addPath(spentPath)
            lineTo((points.size - 0.5f) * slotWidth, chartBottom)
            lineTo(slotWidth / 2f, chartBottom)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                0f to lineColor.copy(alpha = 0.25f),
                1f to lineColor.copy(alpha = 0.02f),
                startY = topInset,
                endY = chartBottom,
            ),
        )

        // Budget reference line (dashed).
        drawPath(
            path = budgetPath,
            color = budgetLineColor,
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
        )

        // Spent trend line (smooth).
        drawPath(
            path = spentPath,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx()),
        )

        // Dots for each period.
        points.forEachIndexed { index, point ->
            val center = spentPoints[index]
            val dotColor = when {
                point.budget <= BigDecimal.ZERO -> goodColor
                point.spent > point.budget -> badColor
                point.spent >= point.budget * BigDecimal("0.8") -> notGoodColor
                else -> goodColor
            }
            drawCircle(color = surfaceColor, radius = 6.dp.toPx(), center = center)
            drawCircle(color = dotColor, radius = 4.dp.toPx(), center = center)
        }

        // Tapped point gets a guide line + highlight.
        val selected = selectedIndex
        if (selected != null && selected in points.indices) {
            val center = spentPoints[selected]
            drawLine(
                color = lineColor.copy(alpha = 0.25f),
                start = Offset(center.x, chartBottom),
                end = Offset(center.x, topInset),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(color = surfaceColor, radius = 8.dp.toPx(), center = center)
            drawCircle(color = lineColor, radius = 5.dp.toPx(), center = center)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    BuckwheatTheme {
        MultiPeriodTrendCard(
            modifier = Modifier.fillMaxWidth(),
            points = listOf(
                MultiPeriodPoint("Jun", BigDecimal(1200), BigDecimal(1000), isCurrent = false),
                MultiPeriodPoint("Jul", BigDecimal(800), BigDecimal(1000), isCurrent = false),
                MultiPeriodPoint("Aug", BigDecimal(1400), BigDecimal(1000), isCurrent = true),
            ),
            currency = ExtendCurrency.none(),
        )
    }
}

@Preview(name = "Night mode", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewNightMode() {
    BuckwheatTheme {
        MultiPeriodTrendCard(
            modifier = Modifier.fillMaxWidth(),
            points = listOf(
                MultiPeriodPoint("Jun", BigDecimal(1200), BigDecimal(1000), isCurrent = false),
                MultiPeriodPoint("Jul", BigDecimal(800), BigDecimal(1000), isCurrent = false),
                MultiPeriodPoint("Aug", BigDecimal(1400), BigDecimal(1000), isCurrent = true),
            ),
            currency = ExtendCurrency.none(),
        )
    }
}
