package com.danilkinkin.buckwheat.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.prettyYearMonth
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.time.ZoneId
import java.util.Currency

const val MONTH_OVER_MONTH_SHEET = "monthOverMonth"

data class MonthSpending(
    val yearMonth: YearMonth,
    val total: BigDecimal,
)

@Composable
fun MonthOverMonth(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val transactions by spendsViewModel.transactions.observeAsState(emptyList())
    val currency by spendsViewModel.currency.observeAsState()

    val spendsByMonth = remember(transactions) {
        transactions
            .filter { it.type == TransactionType.SPENT }
            .groupBy {
                YearMonth.from(it.date.toInstant().atZone(ZoneId.systemDefault()))
            }
            .map { (ym, list) ->
                MonthSpending(ym, list.fold(BigDecimal.ZERO) { acc, t -> acc + t.value })
            }
            .sortedBy { it.yearMonth }
    }

    val currencySymbol = remember(currency) {
        when (currency?.type) {
            ExtendCurrency.Type.FROM_LIST -> Currency.getInstance(currency!!.value).symbol
            ExtendCurrency.Type.CUSTOM -> currency!!.value ?: ""
            else -> ""
        }
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { onClose() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.weight(1F))
                Text(
                    text = stringResource(R.string.month_over_month_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1F))
                Spacer(Modifier.width(48.dp))
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                if (spendsByMonth.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_spends),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                } else {
                    val maxTotal = spendsByMonth.maxOf { it.total }
                    spendsByMonth.forEachIndexed { index, month ->
                        MonthCard(
                            month = month,
                            previousTotal = if (index > 0) spendsByMonth[index - 1].total else null,
                            maxTotal = maxTotal,
                            currencySymbol = currencySymbol,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun MonthCard(
    month: MonthSpending,
    previousTotal: BigDecimal?,
    maxTotal: BigDecimal,
    currencySymbol: String,
) {
    val barRatio = if (maxTotal > BigDecimal.ZERO) {
        (month.total / maxTotal).toFloat()
    } else 0f

    val diff = if (previousTotal != null && previousTotal > BigDecimal.ZERO) {
        ((month.total - previousTotal) / previousTotal * BigDecimal(100))
            .setScale(1, RoundingMode.HALF_EVEN)
    } else null

    val isUp = diff != null && diff > BigDecimal.ZERO
    val isDown = diff != null && diff < BigDecimal.ZERO
    val isEven = diff != null && diff == BigDecimal.ZERO

    val barColor = when {
        isUp -> Color(0xFF4CAF50)
        isDown -> Color(0xFFF44336)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = prettyYearMonth(month.yearMonth),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (diff != null) {
                        val arrowIcon = when {
                            isUp -> R.drawable.ic_arrow_up
                            isDown -> R.drawable.ic_arrow_down
                            else -> R.drawable.ic_remove
                        }
                        val arrowColor = when {
                            isUp -> Color(0xFF4CAF50)
                            isDown -> Color(0xFFF44336)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(
                            painter = painterResource(arrowIcon),
                            contentDescription = null,
                            tint = arrowColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isEven) "0%" else "${diff.toPlainString()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = arrowColor,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = "$currencySymbol${formatAmount(month.total)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            ) {
                val bkgColor = barColor.copy(alpha = 0.2f)
                drawRoundRect(
                    color = bkgColor,
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(8f, 8f),
                )
                drawRoundRect(
                    color = barColor,
                    size = Size(size.width * barRatio, size.height),
                    cornerRadius = CornerRadius(8f, 8f),
                )
            }
        }
    }
}

private fun formatAmount(amount: BigDecimal): String {
    return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString()
}
