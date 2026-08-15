package com.danilkinkin.buckwheat.analytics.categoriesChart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

// Per-item sweep angles in degrees for the donut. Empty input yields an empty list (nothing to
// draw); tiny slices are padded up to a minimum sweep angle so they stay visible, and the padding
// is redistributed across the larger slices. Pure so it is unit-testable.
internal fun donutItemAngles(items: List<TagUsage>): List<Float> {
    if (items.isEmpty()) return emptyList()

    val minSweepAngle = 28f
    val total = items.map { it.amount }.reduce { acc, next -> acc + next }
    if (total == BigDecimal.ZERO) {
        // Equal slices keep the ring visible even when nothing was actually spent.
        return List(items.size) { 360f / items.size }
    }

    var itemAngles = items.map {
        it.amount
            .divide(total, 5, RoundingMode.HALF_DOWN)
            .multiply(360.toBigDecimal())
            .toFloat()
    }

    val shareAngle = itemAngles
        .filter { it < minSweepAngle }
        .map { minSweepAngle - it }
        .fold(0f) { acc, next -> acc + next }
    val splitItems = itemAngles.filter { it > minSweepAngle }.toMutableList()

    return itemAngles.map { angle ->
        when {
            angle < minSweepAngle -> minSweepAngle
            angle > minSweepAngle -> angle - shareAngle / splitItems.size
            else -> angle
        }
    }
}

@Composable
fun DonutChart(
    modifier: Modifier = Modifier,
    items: List<TagUsage>,
    chartPadding: PaddingValues = PaddingValues(0.dp),
) {
    val localDensity = LocalDensity.current

    val layoutDirection = when (LocalConfiguration.current.layoutDirection) {
        0 -> LayoutDirection.Rtl
        1 -> LayoutDirection.Ltr
        else -> LayoutDirection.Rtl
    }
    val topOffset = with(localDensity) { chartPadding.calculateTopPadding().toPx() }
    val bottomOffset = with(localDensity) { chartPadding.calculateBottomPadding().toPx() }
    val startOffset =
        with(localDensity) { chartPadding.calculateStartPadding(layoutDirection).toPx() }
    val endOffset = with(localDensity) { chartPadding.calculateEndPadding(layoutDirection).toPx() }

    Canvas(modifier = modifier) {
        if (items.isEmpty()) return@Canvas

        val width = this.size.width
        val height = this.size.height
        val heightWithPaddings = height - topOffset - bottomOffset
        val widthWithPaddings = width - startOffset - endOffset

        val itemAngles = donutItemAngles(items)
        var offset = 0f

        val gap = 0f
        val halfGap = gap / 2f
        val strokeWidth = 28f
        val halfStrokeWidth = strokeWidth / 2f
        val offsetAngle = -90f

        items.forEachIndexed { index, tag ->
            val sweepAngle = itemAngles[index]

            drawArc(
                tag.color?.main ?: Color.Black,
                startAngle = offset + halfGap + offsetAngle,
                sweepAngle = sweepAngle - gap,
                useCenter = false,
                topLeft = Offset(startOffset + halfStrokeWidth, topOffset + halfStrokeWidth),
                size = Size(widthWithPaddings - strokeWidth, heightWithPaddings - strokeWidth),
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Butt
                ),
            )

            offset += sweepAngle
        }
    }
}