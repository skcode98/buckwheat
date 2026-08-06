package com.danilkinkin.buckwheat.analytics.categoriesChart

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.categories.categoryFor
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.isNightMode
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.harmonize
import com.danilkinkin.buckwheat.util.harmonizeWithColor
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.util.Date

// Analytics breakdown of every record in the period across the predefined spend categories.
// Records without a persisted (AI) category are classified offline by keyword, so the card
// always renders; when the AI pass is running a thin progress bar is shown.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpendCategoriesCard(
    modifier: Modifier = Modifier,
    spends: List<Transaction>,
    currency: ExtendCurrency,
    isCategorizing: Boolean = false,
) {
    val context = LocalContext.current
    val isNightMode = isNightMode()
    val title = stringResource(R.string.categories_title)
    val refining = stringResource(R.string.categories_refining)

    val colors = baseColors.map {
        toPalette(
            color = harmonizeWithColor(
                designColor = it,
                sourceColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
    val restColor = toPalette(
        color = harmonize(
            designColor = Color(0xFF222222),
            sourceColor = MaterialTheme.colorScheme.primary,
        ),
    ).copy(
        main = if (isNightMode) Color(0xFFF0F0F0) else Color(0xFF222222),
        onSurface = if (isNightMode) Color(0xFF1A1A1A) else Color(0xFFF4F4F4),
    )

    val categories by remember(spends) {
        mutableStateOf(
            SpendCategory.entries
                .mapNotNull { category ->
                    val total = spends
                        .filter { categoryFor(it) == category }
                        .map { it.value }
                        .fold(BigDecimal.ZERO) { acc, next -> acc + next }
                    if (total <= BigDecimal.ZERO) return@mapNotNull null
                    TagUsage(
                        name = context.getString(category.labelRes),
                        amount = total,
                        color = if (category == SpendCategory.OTHER) {
                            restColor
                        } else {
                            colors[category.ordinal % colors.size]
                        },
                        isSpecial = category == SpendCategory.OTHER,
                    )
                }
                .sortedBy { it.amount }
                .reversed(),
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
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                if (isCategorizing) {
                    Text(
                        text = refining,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            if (isCategorizing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            DonutChart(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .size(64.dp),
                items = categories,
            )
            if (categories.isEmpty()) {
                Box(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.not_enough_data_for_tags_chart),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            } else {
                FlowRow(Modifier.padding(4.dp, 4.dp)) {
                    categories.forEach { category ->
                        TagAmount(
                            modifier = Modifier.padding(4.dp, 4.dp),
                            value = category.name,
                            amount = category.amount,
                            palette = category.color,
                            isSpecial = category.isSpecial,
                            currency = currency,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Categories", widthDp = 360)
@Preview(name = "Categories (Dark mode)", widthDp = 360, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewCategories() {
    val comments = listOf(
        "lunch", "bus ticket", "dinner", "movie", "medicine", "rent",
        "flight", "tea", "metro", "groceries", "uber", "netflix",
    )
    BuckwheatTheme {
        SpendCategoriesCard(
            modifier = Modifier.fillMaxWidth(),
            currency = ExtendCurrency.getInstance("EUR"),
            spends = comments.mapIndexed { index, it ->
                Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal(50 + index),
                    date = Date(),
                    comment = it,
                )
            },
        )
    }
}
