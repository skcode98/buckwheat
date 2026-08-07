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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorMax
import com.danilkinkin.buckwheat.ui.colorMin
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.getWeek
import com.danilkinkin.buckwheat.util.harmonize
import com.danilkinkin.buckwheat.util.isZero
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyWeekDay
import com.danilkinkin.buckwheat.util.roundToDay
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date

// Average daily spend for each weekday across the elapsed part of the period
// (startDate..min(today, finishDate)). Returns 7 values indexed Mon=0..Sun=6
// (DayOfWeek.value - 1). Weekdays that haven't occurred yet are zero.
fun weekdayAverageSpend(
    spends: List<Transaction>,
    startDate: Date,
    finishDate: Date,
    today: Date = Date(),
): List<BigDecimal> {
    val start = roundToDay(startDate).toLocalDate()
    val end = roundToDay(if (today.before(finishDate)) today else finishDate).toLocalDate()
    if (end.isBefore(start)) return List(7) { BigDecimal.ZERO.setScale(2) }

    val dayCounts = IntArray(7)
    val totals = MutableList(7) { BigDecimal.ZERO }

    var cursor = start
    while (!cursor.isAfter(end)) {
        dayCounts[cursor.dayOfWeek.value - 1]++
        cursor = cursor.plusDays(1)
    }

    spends.forEach { tx ->
        val day = roundToDay(tx.date).toLocalDate()
        if (!day.isBefore(start) && !day.isAfter(end)) {
            totals[day.dayOfWeek.value - 1] += tx.value
        }
    }

    return List(7) { index ->
        if (dayCounts[index] == 0) {
            BigDecimal.ZERO.setScale(2)
        } else {
            totals[index].divide(BigDecimal(dayCounts[index]), 2, RoundingMode.HALF_EVEN)
        }
    }
}

@Composable
fun SpendsWeekdayCard(
    modifier: Modifier = Modifier,
    spends: List<Transaction>,
    startDate: Date,
    finishDate: Date,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current

    val averages = remember(spends, startDate, finishDate) {
        weekdayAverageSpend(spends, startDate, finishDate)
    }
    val week = getWeek()
    val values = remember(week, averages) {
        week.map { averages[it.value - 1] }
    }
    val maxValue = values.maxOrNull() ?: BigDecimal.ZERO
    // Reset the highlighted day when the period data changes so a selection from a
    // previous period never lingers on the new breakdown.
    var selectedWeekday by remember(spends, startDate, finishDate) {
        mutableStateOf<DayOfWeek?>(null)
    }

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.weekday_breakdown_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            val maxColor = toPalette(harmonize(colorMax)).main
            val minColor = toPalette(harmonize(colorMin)).main
            val barColors = remember(values, maxValue, maxColor, minColor) {
                values.map { value ->
                    val fraction = if (maxValue.isZero()) {
                        0f
                    } else {
                        value.divide(maxValue, 4, RoundingMode.HALF_EVEN).toFloat()
                    }
                    combineColors(minColor, maxColor, fraction)
                }
            }

            WeekdayBars(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                values = values,
                maxValue = maxValue,
                barColors = barColors,
                selectedIndex = week.indexOf(selectedWeekday),
                highlightColor = maxColor,
                onBarTap = { index -> selectedWeekday = week[index] },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Text(
                        text = prettyWeekDay(day),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val day = selectedWeekday
            if (day != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${prettyWeekDay(day)} · ${numberFormat(context, averages[day.value - 1], currency)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun WeekdayBars(
    modifier: Modifier = Modifier,
    values: List<BigDecimal>,
    maxValue: BigDecimal,
    barColors: List<Color>,
    selectedIndex: Int?,
    highlightColor: Color,
    onBarTap: (Int) -> Unit,
) {
    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(values.size) {
                detectTapGestures { offset ->
                    if (values.isNotEmpty()) {
                        val index = (offset.x / (size.width / values.size))
                            .toInt()
                            .coerceIn(0, values.size - 1)
                        onBarTap(index)
                    }
                }
            }
    ) {
        if (values.isEmpty()) return@Canvas

        val slotWidth = size.width / values.size
        val barWidth = slotWidth * 0.6f
        val barHeightMin = size.height * 0.01f

        values.forEachIndexed { index, value ->
            val fraction = if (maxValue.isZero()) {
                0f
            } else {
                value.divide(maxValue, 4, RoundingMode.HALF_EVEN).toFloat()
            }
            val barHeight = (size.height * fraction).coerceAtLeast(barHeightMin)

            drawRoundRect(
                color = if (index == selectedIndex) highlightColor else barColors[index],
                topLeft = Offset(
                    x = index * slotWidth + (slotWidth - barWidth) / 2f,
                    y = size.height - barHeight,
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    BuckwheatTheme {
        SpendsWeekdayCard(
            modifier = Modifier.fillMaxWidth(),
            spends = listOf(
                Transaction(type = TransactionType.SPENT, value = BigDecimal(120), date = LocalDate.now().minusDays(1).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(45), date = LocalDate.now().minusDays(2).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(200), date = LocalDate.now().minusDays(3).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(30), date = LocalDate.now().minusDays(6).toDate()),
            ),
            startDate = LocalDate.now().minusDays(10).toDate(),
            finishDate = LocalDate.now().plusDays(20).toDate(),
            currency = ExtendCurrency.none(),
        )
    }
}

@Preview(name = "Night mode", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewNightMode() {
    BuckwheatTheme {
        SpendsWeekdayCard(
            modifier = Modifier.fillMaxWidth(),
            spends = listOf(
                Transaction(type = TransactionType.SPENT, value = BigDecimal(120), date = LocalDate.now().minusDays(1).toDate()),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(200), date = LocalDate.now().minusDays(3).toDate()),
            ),
            startDate = LocalDate.now().minusDays(10).toDate(),
            finishDate = LocalDate.now().plusDays(20).toDate(),
            currency = ExtendCurrency.none(),
        )
    }
}
