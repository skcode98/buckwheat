package com.danilkinkin.buckwheat.history

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorOnEditor
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.isToday
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.abs
import kotlin.math.absoluteValue

val DayCardContainerColor: Color
    @Composable
    @ReadOnlyComposable
    get() = combineColors(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.surfaceVariant,
        0.3f,
    )

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DayCard(
    day: LocalDate,
    transactions: List<Transaction>,
    dayTotal: BigDecimal,
    firstTransactionIndex: Int,
    modifier: Modifier = Modifier,
    currency: ExtendCurrency,
    categoryEmojis: Map<String, String> = emptyMap(),
    readOnly: Boolean = false,
    onEdit: (Transaction) -> Unit = {},
    onDelete: (Transaction) -> Unit = {},
    onCopy: (Transaction) -> Unit = {},
    onSwipeEdit: (Transaction) -> Unit = {},
    onSwipeDelete: (Transaction) -> Unit = {},
    onTriedSwipe: () -> Unit = {},
    showTutorial: (Int) -> Boolean = { false },
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = DayCardContainerColor,
    ) {
        Column {
            DayCardHeader(day = day, dayTotal = dayTotal, currency = currency)
            transactions.forEachIndexed { index, transaction ->
                val category = remember(transaction, categoryEmojis) {
                    categoryLabelFor(context, transaction, categoryEmojis)
                }

                if (readOnly) {
                    TimelineRowContent(
                        transaction = transaction,
                        currency = currency,
                        category = category,
                    )
                } else {
                    SwipeActions(
                        startActionsConfig = SwipeActionsConfig(
                            threshold = 0.4f,
                            background = MaterialTheme.colorScheme.tertiaryContainer,
                            backgroundActive = MaterialTheme.colorScheme.tertiary,
                            iconTint = MaterialTheme.colorScheme.onTertiary,
                            icon = painterResource(R.drawable.ic_edit),
                            stayDismissed = false,
                            onDismiss = { onSwipeEdit(transaction) },
                        ),
                        endActionsConfig = SwipeActionsConfig(
                            threshold = 0.4f,
                            background = MaterialTheme.colorScheme.errorContainer,
                            backgroundActive = MaterialTheme.colorScheme.error,
                            iconTint = MaterialTheme.colorScheme.onError,
                            icon = painterResource(R.drawable.ic_delete_forever),
                            stayDismissed = true,
                            onDismiss = { onSwipeDelete(transaction) },
                        ),
                        onTried = onTriedSwipe,
                        showTutorial = showTutorial(firstTransactionIndex + index),
                    ) { state ->
                        SwipeRowSheet(state = state) {
                            SpentItemActions(
                                transaction = transaction,
                                currency = currency,
                                categoryEmojis = categoryEmojis,
                                onEdit = { onEdit(transaction) },
                                onDelete = { onDelete(transaction) },
                                onCopy = { onCopy(transaction) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DayCardHeader(
    day: LocalDate,
    dayTotal: BigDecimal,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current
    val label = if (isToday(day.toDate())) {
        stringResource(R.string.today)
    } else {
        val locale = LocalConfiguration.current.locales[0]
        val weekday = DateTimeFormatter.ofPattern("EEE", locale).format(day)
        val datePart = prettyDate(day.toDate(), forceShowDate = true, showTime = false)
        "$weekday, $datePart"
    }

    Column(Modifier.padding(top = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.total_per_day),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = numberFormat(context, dayTotal, currency = currency),
                style = MaterialTheme.typography.titleMedium,
                color = colorOnEditor,
                maxLines = 1,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun SwipeRowSheet(
    state: DismissState,
    content: @Composable () -> Unit,
) {
    val size = with(LocalDensity.current) {
        java.lang.Float.max(
            java.lang.Float.min(
                16.dp.toPx(),
                abs(state.offset.value),
            ), 0f
        ).toDp()
    }
    val animateCorners by remember {
        derivedStateOf {
            state.offset.value.absoluteValue > 30
        }
    }
    val startCorners by animateDpAsState(
        targetValue = when {
            state.dismissDirection == DismissDirection.StartToEnd &&
                    animateCorners -> 8.dp
            else -> 0.dp
        },
    )
    val endCorners by animateDpAsState(
        targetValue = when {
            state.dismissDirection == DismissDirection.EndToStart &&
                    animateCorners -> 8.dp
            else -> 0.dp
        },
    )

    Box(Modifier.height(IntrinsicSize.Min)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = min(size / 4f, 4.dp))
                .clip(RoundedCornerShape(size)),
            color = DayCardContainerColor,
            shape = RoundedCornerShape(
                topStart = startCorners,
                bottomStart = startCorners,
                topEnd = endCorners,
                bottomEnd = endCorners,
            ),
        ) {
        }
        Box(Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Preview(name = "Day card", widthDp = 360)
@Composable
private fun PreviewDayCard() {
    BuckwheatTheme {
        DayCard(
            day = LocalDate.now().minusDays(1),
            transactions = listOf(
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal("1240"),
                    date = Date(System.currentTimeMillis() - 3 * 3600 * 1000),
                    comment = "Lunch at a cafe",
                ),
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal("380"),
                    date = Date(System.currentTimeMillis() - 10 * 3600 * 1000),
                    comment = "Bus ticket",
                ),
            ),
            dayTotal = BigDecimal("1620"),
            firstTransactionIndex = 0,
            currency = ExtendCurrency.none(),
            readOnly = true,
        )
    }
}
