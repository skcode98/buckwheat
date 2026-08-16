package com.danilkinkin.buckwheat.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
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

val DayDividerContainerColor: Color
    @Composable
    get() = combineColors(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.surfaceVariant,
        0.3f,
    )

@Composable
fun HistoryDateDivider(
    day: LocalDate,
    dayTotal: BigDecimal,
    currency: ExtendCurrency,
    modifier: Modifier = Modifier,
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

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DayDividerContainerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
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
    }
}

@Composable
fun HistoryDaySpacer(
    modifier: Modifier = Modifier,
) {
    Spacer(modifier = modifier.height(8.dp))
}

@Composable
fun HistoryItemSpacer(
    modifier: Modifier = Modifier,
) {
    Spacer(modifier = modifier.height(2.dp))
}
