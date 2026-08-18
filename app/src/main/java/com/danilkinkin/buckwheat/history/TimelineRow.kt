package com.danilkinkin.buckwheat.history

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

internal fun categoryLabelFor(
    context: Context,
    transaction: Transaction,
    categoryEmojis: Map<String, String>,
): Pair<String, String>? = when (val key = categoryKey(transaction)) {
    is CategoryKey.BuiltIn -> key.category.emoji to context.getString(key.category.labelRes)
    is CategoryKey.Custom -> SpendCategory.emojiFor(key.name, categoryEmojis[key.name]) to key.name
}

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

@Composable
internal fun CategoryFlipContainer(
    emoji: String,
    categoryName: String,
    palette: HarmonizedColorPalette,
    modifier: Modifier = Modifier,
) {
    var isFlipped by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "flipRotation",
    )

    Box(
        modifier = modifier
            .size(28.dp)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .clip(RoundedCornerShape(8.dp))
            .background(palette.main.copy(alpha = 0.15f))
            .clickable { isFlipped = !isFlipped },
        contentAlignment = Alignment.Center,
    ) {
        if (rotation < 90f) {
            Text(text = emoji, fontSize = 14.sp, maxLines = 1)
        } else {
            Text(
                text = categoryName.take(2),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = palette.main,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { rotationY = 180f },
            )
        }
    }
}

@Composable
internal fun TimelineRowContent(
    transaction: Transaction,
    currency: ExtendCurrency,
    modifier: Modifier = Modifier,
    category: Pair<String, String>? = null,
) {
    val context = LocalContext.current
    val palette = timelinePaletteFor(transaction)
    val name = category?.second ?: ""
    val comment = transaction.comment
    val time = prettyDate(transaction.date, showTime = true, forceHideDate = true)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryFlipContainer(
            emoji = category?.first ?: SpendCategory.DEFAULT_EMOJI,
            categoryName = name,
            palette = palette,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = comment.ifBlank { name },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = colorOnEditor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (comment.isBlank()) time else "$name · $time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = numberFormat(context, transaction.value, currency = currency),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
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

@Preview(name = "Commentless", widthDp = 340)
@Composable
private fun PreviewCommentless() {
    BuckwheatTheme {
        TimelineRowContent(
            transaction = Transaction(
                type = TransactionType.SPENT,
                value = BigDecimal("120"),
                date = Date(),
                comment = "metro",
            ),
            currency = ExtendCurrency.none(),
        )
    }
}
