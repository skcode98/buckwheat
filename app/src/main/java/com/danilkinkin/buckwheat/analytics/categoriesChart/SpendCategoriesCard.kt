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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CATEGORY_CAP_NEAR_PERCENT
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.categories.categoryCapPercent
import com.danilkinkin.buckwheat.data.categories.categoryTotals
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
    categoryEmojis: Map<String, String> = emptyMap(),
    caps: Map<String, BigDecimal> = emptyMap(),
    onCategoryClick: ((CategoryKey) -> Unit)? = null,
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

    val categories by remember(spends, categoryEmojis) {
        mutableStateOf(
            categoryTotals(spends)
                .map { (key, total) ->
                    val usage = when (key) {
                        is CategoryKey.BuiltIn -> TagUsage(
                            name = context.getString(key.category.labelRes),
                            amount = total,
                            color = if (key.category == SpendCategory.OTHER) {
                                restColor
                            } else {
                                colors[key.category.ordinal % colors.size]
                            },
                            isSpecial = key.category == SpendCategory.OTHER,
                            emoji = key.category.emoji,
                        )
                        is CategoryKey.Custom -> TagUsage(
                            name = key.name,
                            amount = total,
                            color = colors[Math.floorMod(key.name.hashCode(), colors.size)],
                            isSpecial = false,
                            emoji = SpendCategory.emojiFor(key.name, categoryEmojis[key.name]),
                        )
                    }
                    key to usage
                }
                .sortedBy { it.second.amount }
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
                items = categories.map { it.second },
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
                    categories.forEach { (key, category) ->
                        val cap = when (key) {
                            is CategoryKey.BuiltIn -> caps[key.category.name]
                            is CategoryKey.Custom -> caps[key.name]
                        }
                        val chip: @Composable () -> Unit = {
                            TagAmount(
                                modifier = Modifier,
                                value = category.name,
                                emoji = category.emoji,
                                amount = category.amount,
                                palette = category.color,
                                isSpecial = category.isSpecial,
                                currency = currency,
                                onClick = onCategoryClick?.let { { it(key) } },
                            )
                        }
                        if (cap != null && cap > BigDecimal.ZERO) {
                            Column(Modifier.padding(4.dp, 4.dp)) {
                                chip()
                                Spacer(Modifier.height(2.dp))
                                CapProgressBar(
                                    progress = category.amount,
                                    cap = cap,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            Box(Modifier.padding(4.dp, 4.dp)) {
                                chip()
                            }
                        }
                    }
                }
            }
        }
    }
}

// Thin progress bar under a category chip when a cap is configured. Turns amber at/above
// 80% and red once the cap is reached; the label shows the percent of the cap spent.
@Composable
private fun CapProgressBar(
    progress: BigDecimal,
    cap: BigDecimal,
    modifier: Modifier = Modifier,
) {
    val percent = categoryCapPercent(progress, cap)
    val fraction = (progress / cap).toFloat().coerceIn(0f, 1f)
    val color = when {
        percent >= 100 -> MaterialTheme.colorScheme.error
        percent >= CATEGORY_CAP_NEAR_PERCENT -> Color(0xFFE6A23C)
        else -> MaterialTheme.colorScheme.primary
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .weight(1f)
                .height(4.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (percent >= 100) {
                stringResource(R.string.category_cap_reached_label)
            } else {
                stringResource(R.string.category_cap_progress, percent)
            },
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
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
