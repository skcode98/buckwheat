package com.danilkinkin.buckwheat.history

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danilkinkin.buckwheat.analytics.categoriesChart.baseColors
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.categories.categoryKey
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorOnEditor
import com.danilkinkin.buckwheat.ui.isNightMode
import com.danilkinkin.buckwheat.util.HarmonizedColorPalette
import com.danilkinkin.buckwheat.util.harmonize
import com.danilkinkin.buckwheat.util.harmonizeWithColor
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.util.Date

// The category shown on a history row: the persisted AI category when present, otherwise the
// offline keyword guess. Custom categories fall back to their saved emoji (or a generic one
// when none is saved).
internal fun categoryLabelFor(
    context: Context,
    transaction: Transaction,
    categoryEmojis: Map<String, String>,
): Pair<String, String>? = when (val key = categoryKey(transaction)) {
    is CategoryKey.BuiltIn -> key.category.emoji to context.getString(key.category.labelRes)
    is CategoryKey.Custom -> SpendCategory.emojiFor(key.name, categoryEmojis[key.name]) to key.name
}

// Same category→color mapping as the analytics cards (SpendCategoriesCard): built-ins pick
// from the harmonized base palette, "other" is neutral, custom categories hash to a palette
// color so a given category keeps the same hue across the app.
@Composable
internal fun timelinePaletteFor(transaction: Transaction): HarmonizedColorPalette {
    val isNightMode = isNightMode()
    val colors = baseColors.map {
        toPalette(
            color = harmonizeWithColor(
                designColor = it,
                sourceColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
    return when (val key = categoryKey(transaction)) {
        is CategoryKey.BuiltIn -> if (key.category == SpendCategory.OTHER) {
            toPalette(
                color = harmonize(
                    designColor = Color(0xFF222222),
                    sourceColor = MaterialTheme.colorScheme.primary,
                ),
            ).copy(
                main = if (isNightMode) Color(0xFFF0F0F0) else Color(0xFF222222),
                onSurface = if (isNightMode) Color(0xFF1A1A1A) else Color(0xFFF4F4F4),
            )
        } else {
            colors[key.category.ordinal % colors.size]
        }
        is CategoryKey.Custom -> colors[Math.floorMod(key.name.hashCode(), colors.size)]
    }
}

// The fixed colored dot (with the category emoji) and the connector that continues the
// timeline into the next row. Stays put while the row content is swiped.
@Composable
internal fun TimelineRail(
    palette: HarmonizedColorPalette,
    emoji: String,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(36.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (!isLast) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(palette.main.copy(alpha = 0.22f)),
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(28.dp)
                .background(palette.main.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emoji,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
    }
}

// The swipeable row body: comment/category label, category · time, and the amount.
@Composable
internal fun TimelineRowContent(
    transaction: Transaction,
    currency: ExtendCurrency,
    modifier: Modifier = Modifier,
    category: Pair<String, String>? = null,
) {
    val context = LocalContext.current
    val name = category?.second ?: ""
    val comment = transaction.comment
    val time = prettyDate(transaction.date, showTime = true, forceHideDate = true)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = comment.ifBlank { name },
                style = MaterialTheme.typography.bodyLarge,
                color = colorOnEditor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (comment.isBlank()) time else "$name · $time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = numberFormat(context, transaction.value, currency = currency),
            style = MaterialTheme.typography.titleMedium,
            color = colorOnEditor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(name = "With category", widthDp = 340)
@Composable
private fun PreviewWithCategory() {
    BuckwheatTheme {
        Row(Modifier.fillMaxWidth()) {
            TimelineRail(
                palette = timelinePaletteFor(
                    Transaction(
                        type = TransactionType.SPENT,
                        value = BigDecimal("500"),
                        date = Date(),
                        comment = "lunch",
                    ),
                ),
                emoji = "🍔",
                isLast = false,
            )
            TimelineRowContent(
                transaction = Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal("500"),
                    date = Date(),
                    comment = "Lunch at a cafe",
                ),
                currency = ExtendCurrency.none(),
            )
        }
    }
}

@Preview(name = "Commentless", widthDp = 340)
@Composable
private fun PreviewCommentless() {
    BuckwheatTheme {
        Row(Modifier.fillMaxWidth()) {
            TimelineRail(
                palette = timelinePaletteFor(
                    Transaction(
                        type = TransactionType.SPENT,
                        value = BigDecimal("120"),
                        date = Date(),
                        comment = "metro",
                    ),
                ),
                emoji = "🚕",
                isLast = true,
            )
            TimelineRowContent(
                transaction = Transaction(
                    type = TransactionType.SPENT,
                    value = BigDecimal("120"),
                    date = Date(),
                ),
                currency = ExtendCurrency.none(),
            )
        }
    }
}
