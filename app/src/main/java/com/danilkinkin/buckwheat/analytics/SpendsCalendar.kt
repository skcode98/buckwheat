package com.danilkinkin.buckwheat.analytics

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.datePicker.CELL_SIZE
import com.danilkinkin.buckwheat.base.datePicker.model.CalendarState
import com.danilkinkin.buckwheat.base.datePicker.model.CalendarUiState
import com.danilkinkin.buckwheat.base.datePicker.model.Week
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorBad
import com.danilkinkin.buckwheat.ui.colorEditor
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.ui.colorNotGood
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.getWeek
import com.danilkinkin.buckwheat.util.harmonize
import com.danilkinkin.buckwheat.util.isSameDay
import com.danilkinkin.buckwheat.util.isZero
import com.danilkinkin.buckwheat.util.prettyWeekDay
import com.danilkinkin.buckwheat.util.prettyYearMonth
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

data class SpendingDay(
    val date: Date,
    val spends: List<Transaction>,
    val budget: BigDecimal,
    val spending: BigDecimal,
)

@Composable
fun SpendsCalendar(
    modifier: Modifier = Modifier,
    budget: BigDecimal,
    transactions: List<Transaction>,
    startDate: Date,
    finishDate: Date,
    actualFinishDate: Date? = null,
    currency: ExtendCurrency,
    onDayClick: (SpendingDay) -> Unit = {},
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]

    val spendingDays = remember(transactions) {
        val days: MutableMap<LocalDate, SpendingDay> =
            emptyMap<LocalDate, SpendingDay>().toMutableMap()
        var currDay: SpendingDay? = null
        var lastBudget = BigDecimal.ZERO

        transactions.forEach {
            if (it.type == TransactionType.INCOME) {
                return@forEach
            }

            if (currDay == null || !isSameDay(currDay?.date?.time ?: 0L, it.date.time)) {
                if (currDay !== null) {
                    currDay?.let { d -> days[d.date.toLocalDate()] = d }
                }

                val isBudgetTx = it.type == TransactionType.SET_DAILY_BUDGET
                if (isBudgetTx) lastBudget = it.value

                currDay = SpendingDay(
                    date = it.date,
                    spends = if (isBudgetTx) listOf() else listOf(it),
                    spending = if (isBudgetTx) BigDecimal.ZERO else it.value,
                    budget = if (isBudgetTx) it.value else lastBudget,
                )

                return@forEach
            }

            if (it.type == TransactionType.SET_DAILY_BUDGET) {
                lastBudget = it.value
                currDay = currDay?.copy(budget = it.value)
                return@forEach
            }

            currDay = currDay?.copy(
                spending = (currDay?.spending ?: BigDecimal.ZERO) + it.value, spends = (currDay?.spends ?: emptyList()).plus(it)
            )
        }

        if (currDay != null) {
            currDay?.let { d -> days[d.date.toLocalDate()] = d }
        }

        days.toMutableMap()
    }

    val calendarState = remember(startDate, finishDate, actualFinishDate, transactions) {
        CalendarState(
            context = context,
            disableBeforeDate = startDate,
            disableAfterDate = (actualFinishDate ?: finishDate).coerceAtMost(Date()),
        )
    }

    val months = calendarState.listMonths
    val today = LocalDate.now()
    val initialMonthIdx = months.indexOfFirst { m ->
        m.yearMonth.year == today.year && m.yearMonth.month == today.month
    }.let { if (it < 0) 0 else it }
    var currentMonthIdx by remember(startDate, transactions) { mutableIntStateOf(initialMonthIdx) }
    var weekMode by remember { mutableStateOf(false) }
    var currentWeekStart by remember { mutableStateOf(today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))) }

    if (currentMonthIdx >= months.size) {
        currentMonthIdx = months.size - 1
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
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row {
                    Icon(
                        modifier = Modifier
                            .padding(top = 0.5.dp)
                            .size(14.dp),
                        painter = painterResource(R.drawable.ic_info),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.spends_calendar_hint),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.8f),
                        ),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = !weekMode,
                        onClick = { weekMode = false },
                        label = { Text("Month", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.height(28.dp),
                    )
                    FilterChip(
                        selected = weekMode,
                        onClick = { weekMode = true },
                        label = { Text("Week", style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.height(28.dp),
                    )
                }
            }

            val currentMonth = months.getOrNull(currentMonthIdx)
            val calendarUiState = calendarState.calendarUiState.value

            if (weekMode) {
                // Week view
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { currentWeekStart = currentWeekStart.minusDays(7) },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = "${currentWeekStart.month.name.take(3)} ${currentWeekStart.dayOfMonth} - ${currentWeekStart.plusDays(6).month.name.take(3)} ${currentWeekStart.plusDays(6).dayOfMonth}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(
                        onClick = { currentWeekStart = currentWeekStart.plusDays(7) },
                        enabled = currentWeekStart.plusDays(6).isBefore(calendarUiState.disabledAfter?.plusDays(1) ?: LocalDate.MAX),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_forward),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                DaysOfWeek(locale)

                Layout(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp),
                    measurePolicy = verticalGridMeasurePolicy(7),
                    content = {
                        val weekDays = (0..6).map { currentWeekStart.plusDays(it.toLong()) }
                        weekDays.forEach { day ->
                            if (!calendarUiState.isDisabledDay(day)) {
                                Day(
                                    day = day,
                                    spendingDays = spendingDays,
                                    onClick = { spendingDays[day]?.let { onDayClick(it) } },
                                )
                            } else {
                                Box(modifier = Modifier.size(CELL_SIZE))
                            }
                        }
                    }
                )

                ColorLegend()
            } else if (currentMonth != null) {
                // Month view
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (currentMonthIdx > 0) currentMonthIdx--
                        },
                        enabled = currentMonthIdx > 0,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = prettyYearMonth(currentMonth.yearMonth),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(
                        onClick = {
                            if (currentMonthIdx < months.size - 1) currentMonthIdx++
                        },
                        enabled = currentMonthIdx < months.size - 1,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_forward),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                MonthHeader(
                    modifier = Modifier.layoutId("fullWidth"),
                    yearMonth = currentMonth.yearMonth,
                )

                DaysOfWeek(locale)

                Layout(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp),
                    measurePolicy = verticalGridMeasurePolicy(7),
                    content = {
                        currentMonth.weeks.forEach { week ->
                            val beginningWeek = week.yearMonth.atDay(1).plusWeeks(week.number.toLong())
                            val currentDay =
                                beginningWeek.with(TemporalAdjusters.previousOrSame(getWeek(locale)[0]))

                            Week(
                                week = week,
                                calendarUiState = calendarUiState,
                                spendingDays = spendingDays,
                                locale = locale,
                                onDayClick = { localDate ->
                                    spendingDays[localDate]?.let { onDayClick(it) }
                                },
                            )
                        }
                    }
                )

                ColorLegend()
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ColorLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFF4CAF50), CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Under budget",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFFFFC107), CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Near limit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFFE53935), CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Over budget",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MonthHeader(modifier: Modifier = Modifier, yearMonth: YearMonth) {
    Row(modifier = modifier.height(CELL_SIZE), verticalAlignment = Alignment.Bottom) {
        Text(
            modifier = Modifier
                .padding(start = 24.dp)
                .weight(1f),
            text = prettyYearMonth(yearMonth),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun DaysOfWeek(locale: Locale) {
    val week = getWeek(locale)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp),
    ) {
        for (day in week) {
            DayOfWeekHeading(
                day = prettyWeekDay(day),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun Week(
    calendarUiState: CalendarUiState,
    week: Week,
    spendingDays: Map<LocalDate, SpendingDay>,
    locale: Locale = Locale.getDefault(),
    onDayClick: (LocalDate) -> Unit = {},
) {
    val beginningWeek = week.yearMonth.atDay(1).plusWeeks(week.number.toLong())

    for (day in 0..6) {
        val currentDay = beginningWeek.with(TemporalAdjusters.previousOrSame(getWeek(locale)[0]))
            .plusDays(day.toLong())

        if (currentDay.month == week.yearMonth.month) {
            val isDisabled = calendarUiState.isDisabledDay(currentDay)
            Day(
                modifier = Modifier,
                day = currentDay,
                spendingDays = if (isDisabled) emptyMap() else spendingDays,
                onClick = { if (!isDisabled) onDayClick(currentDay) },
            )
        } else {
            Box(
                modifier = Modifier.size(CELL_SIZE)
            )
        }
    }
}

@Composable
internal fun DayOfWeekHeading(day: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(CELL_SIZE)
            .fillMaxWidth()
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = day,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5F),
        )
    }
}

@Composable
internal fun Day(
    day: LocalDate,
    spendingDays: Map<LocalDate, SpendingDay>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val spendingDay = if (spendingDays[day] === null || (spendingDays[day]?.spending?.isZero() ?: true)) {
        null
    } else {
        spendingDays[day]
    }

    val harmonizedColor = if (spendingDay !== null) toPalette(
        harmonize(
            if (spendingDay.spending <= spendingDay.budget && spendingDay.budget > BigDecimal.ZERO) {
                combineColors(
                    listOf(
                        colorNotGood,
                        colorGood,
                    ),
                    (spendingDay.budget - spendingDay.spending).divide(
                        spendingDay.budget,
                        2,
                        RoundingMode.HALF_EVEN
                    ).coerceIn(BigDecimal.ZERO, BigDecimal.ONE).toFloat(),
                )
            } else {
                colorBad
            }, colorEditor
        )
    ) else toPalette(MaterialTheme.colorScheme.primary).copy(
        surface = combineColors(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant,
            angle = 0.3f,
        ),
        container = Color.Transparent,
        onContainer = MaterialTheme.colorScheme.onSurface,
    )

    val percent = if (spendingDay !== null) {
        spendingDay.spending.divide(spendingDay.budget.coerceAtLeast(BigDecimal(0.1)), 2, RoundingMode.HALF_EVEN).coerceIn(BigDecimal(-100), BigDecimal(100)).toFloat()
    } else {
        0f
    }
    val fillSizePercent = percent.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(CELL_SIZE)
            .widthIn(min = CELL_SIZE)
            .fillMaxWidth()
            .zIndex(if (spendingDay === null) 0f else -percent + 1000f)
            .clickable(enabled = spendingDay != null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = modifier
                .size(CELL_SIZE - 2.dp)
                .background(
                    color = combineColors(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant,
                        angle = 0.3f,
                    ).copy(0.8f),
                    shape = RoundedCornerShape(10.dp),
                )
                .border(
                    width = 2.dp,
                    color = harmonizedColor.container.copy(
                        (if (percent < 1f) 0.4f else 1f).coerceAtMost(harmonizedColor.container.alpha)
                    ),
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (spendingDay !== null && fillSizePercent > 0f) {
                Box(
                    modifier = Modifier
                        .size(CELL_SIZE * fillSizePercent)
                        .background(
                            color = harmonizedColor.container.copy(
                                if (percent > 1f) (1 - (percent.coerceIn(
                                    1f,
                                    3f
                                ) - 1) / 2) * 0.3f + 0.2f else 0.5f
                            ),
                            shape = RoundedCornerShape(
                                10.dp * percent
                                    .coerceAtLeast(0.7f)
                                    .pow(1.8f),
                            ),
                        )
                )
            }
            Text(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.Center),
                text = day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = harmonizedColor.onContainer,
            )
        }
    }
}

fun verticalGridMeasurePolicy(columns: Int) =
    MeasurePolicy { measurables, constraints ->
        val cellWidth = constraints.maxWidth / columns
        val cells = emptyList<Int>().toMutableList()
        var cellsCount = 0

        val placeables = measurables.mapIndexed { index, it ->
            cells.add(if (it.layoutId == "fullWidth") columns else 1)
            cellsCount += cells[index]

            it.measure(
                constraints.copy(
                    maxWidth = cellWidth * cells[index],
                )
            )
        }


        layout(
            constraints.maxWidth,
            cellsCount / columns * CELL_SIZE.roundToPx(),
        ) {
            var cellsOffset = 0

            placeables.forEachIndexed { index, it ->
                val cellIndex = (cells.getOrNull(index - 1) ?: 0) + cellsOffset

                cellsOffset = cellIndex
                it.place(
                    cellWidth * (cellIndex % columns),
                    CELL_SIZE.roundToPx() * (cellIndex / columns),
                    0f
                )
            }
        }
    }


@Preview(name = "Default")
@Preview(name = "Default (Dark mode)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewDefault() {
    BuckwheatTheme {
        SpendsCalendar(
            budget = BigDecimal(200),
            transactions = listOf(
                Transaction(
                    type = TransactionType.INCOME,
                    value = BigDecimal(800),
                    date = LocalDate.now().minusDays(5).toDate()
                ),
                Transaction(
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal(8),
                    date = LocalDate.now().minusDays(4).toDate()
                ),
                Transaction(
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal(10),
                    date = LocalDate.now().minusDays(2).toDate()
                ),
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(3),
                    date = LocalDate.now().minusDays(2).toDate()
                ),
                Transaction(
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal(10),
                    date = LocalDate.now().minusDays(1).toDate()
                ),
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(5),
                    date = LocalDate.now().minusDays(1).toDate()
                ),
                Transaction(
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal(15),
                    date = LocalDate.now().toDate()
                ),
                Transaction(type = TransactionType.SPENT, value = BigDecimal(8), date = Date()),
                Transaction(
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal(12),
                    date = LocalDate.now().plusDays(1).toDate()
                ),
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(6),
                    date = LocalDate.now().plusDays(1).toDate()
                ),
                Transaction(
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal(12),
                    date = LocalDate.now().plusDays(1).toDate()
                ),
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(8),
                    date = LocalDate.now().plusDays(2).toDate()
                ),
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(10),
                    date = LocalDate.now().plusDays(2).toDate()
                ),
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(12),
                    date = LocalDate.now().plusDays(2).toDate()
                ),
                Transaction(
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal(9),
                    date = LocalDate.now().plusDays(5).toDate()
                ),
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(8),
                    date = LocalDate.now().plusDays(5).toDate()
                ),
                Transaction(
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal(14),
                    date = LocalDate.now().plusDays(7).toDate()
                ),
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(82),
                    date = LocalDate.now().plusDays(11).toDate()
                ),
                Transaction(
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = BigDecimal(14),
                    date = LocalDate.now().plusDays(7).toDate()
                ),
            ),
            currency = ExtendCurrency.none(),
            startDate = LocalDate.now().minusDays(7).toDate(),
            finishDate = LocalDate.now().plusDays(27).toDate(),
        )
    }
}
