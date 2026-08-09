/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.home.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val n = values.size
        val minValue = values.minOrNull() ?: return@Canvas
        val maxValue = values.maxOrNull() ?: return@Canvas
        val span = (maxValue - minValue).coerceAtLeast(0.01)
        val width = size.width
        val height = size.height
        val topPadding = 4.dp.toPx()
        val bottomPadding = 4.dp.toPx()
        val xs = List(n) { i -> width * i / (n - 1) }
        val ys = List(n) { i ->
            topPadding + (height - topPadding - bottomPadding) *
                (1.0f - ((values[i] - minValue).toFloat() / span.toFloat()))
        }

        val line = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 0 until n - 1) {
                val xMid = (xs[i] + xs[i + 1]) / 2f
                cubicTo(xMid, ys[i], xMid, ys[i + 1], xs[i + 1], ys[i + 1])
            }
        }

        val fill = Path().apply {
            addPath(line)
            lineTo(xs.last(), height)
            lineTo(xs.first(), height)
            close()
        }

        drawPath(fill, color.copy(alpha = 0.18f))
        drawPath(
            path = line,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun BarChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val n = values.size
        val maxValue = values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val slot = size.width / n
        val barWidth = slot * 0.6f
        val height = size.height

        values.forEachIndexed { i, value ->
            val barHeight = if (value > 0.0) {
                (height * (value / maxValue).toFloat()).coerceAtLeast(2.dp.toPx())
            } else {
                0f
            }
            if (barHeight > 0f) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(slot * i + (slot - barWidth) / 2f, height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
    }
}

@Composable
fun ChartLabelsRow(
    labels: List<String>,
    modifier: Modifier = Modifier,
    maxLabels: Int = 3,
) {
    val shown = when {
        labels.isEmpty() -> emptyList()
        labels.size <= maxLabels -> labels
        else -> listOf(labels.first(), labels[labels.size / 2], labels.last())
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        shown.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DonutChart(
    segments: List<Pair<Color, Double>>,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.22f
            val total = segments.sumOf { it.second }
            if (total <= 0.0) {
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(strokeWidth),
                )
                return@Canvas
            }
            var startAngle = -90f
            segments.forEach { (color, value) ->
                val sweep = (value / total * 360f).toFloat()
                val gap = if (segments.size > 1) 1.5f else 0f
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = (sweep - gap).coerceAtLeast(0f),
                    useCenter = false,
                    style = Stroke(strokeWidth),
                )
                startAngle += sweep
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            center()
        }
    }
}

@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    center: @Composable () -> Unit,
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.12f
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
            val p = progress.coerceIn(0f, 1f)
            if (p > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * p,
                    useCenter = false,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            center()
        }
    }
}
