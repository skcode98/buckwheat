package com.danilkinkin.buckwheat.analytics.categoriesChart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CATEGORY_CAP_NEAR_PERCENT
import com.danilkinkin.buckwheat.data.categories.categoryBatteryFraction
import com.danilkinkin.buckwheat.data.categories.categoryCapPercent
import com.danilkinkin.buckwheat.util.HarmonizedColorPalette
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal

private val batteryAmber = Color(0xFFE6A23C)

// A compact category budget pill drawn as a battery. It wraps its content (like the plain
// TagAmount chips) so it flows with them instead of spanning the full row. The filled portion
// shows the budget already used (up to the cap) and the unfilled portion what remains; the
// fill turns amber at 80% and red once the cap is reached.
@Composable
fun CategoryBatteryChip(
    modifier: Modifier = Modifier,
    name: String,
    emoji: String = "",
    amount: BigDecimal,
    currency: ExtendCurrency,
    cap: BigDecimal,
    palette: HarmonizedColorPalette? = null,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val percent = categoryCapPercent(amount, cap)
    val fraction = categoryBatteryFraction(amount, cap)
    val percentLabel = stringResource(R.string.category_battery_percent, percent)
    val amountText = numberFormat(context, amount, currency)

    val baseColor = palette?.main ?: MaterialTheme.colorScheme.primary
    val fillColor = when {
        percent >= 100 -> MaterialTheme.colorScheme.error
        percent >= CATEGORY_CAP_NEAR_PERCENT -> batteryAmber
        else -> baseColor
    }
    val fillTextColor = when {
        percent >= 100 -> MaterialTheme.colorScheme.onError
        percent >= CATEGORY_CAP_NEAR_PERCENT -> Color(0xFF1A1A1A)
        else -> palette?.onSurface ?: MaterialTheme.colorScheme.onPrimary
    }
    val trackTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(50)

    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = shape,
        color = Color.Transparent,
        modifier = modifier.height(32.dp),
    ) {
        Box {
            // Battery body; the track text is drawn first, then a fill layer clipped to the
            // left `fraction` re-renders the same content in a contrast color.
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(shape)
                    .background(baseColor.copy(alpha = 0.15f))
                    .padding(start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BatteryTextContent(
                    textColor = trackTextColor,
                    emoji = emoji,
                    name = name,
                    amount = amountText,
                    percent = percentLabel,
                )
            }
            // Fill layer covering the left `fraction` of the pill.
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        val drawScope = this
                        clipRect(right = size.width * fraction) {
                            drawScope.drawContent()
                        }
                    },
            ) {
                Box(Modifier.matchParentSize().background(fillColor))
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .padding(start = 12.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BatteryTextContent(
                        textColor = fillTextColor,
                        emoji = emoji,
                        name = name,
                        amount = amountText,
                        percent = percentLabel,
                    )
                }
            }
            // Battery terminal (nub), sitting in the body's end padding and colored like the
            // current charge level.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .width(4.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(fillColor),
            )
        }
    }
}

@Composable
private fun RowScope.BatteryTextContent(
    textColor: Color,
    emoji: String,
    name: String,
    amount: String,
    percent: String,
) {
    if (emoji.isNotBlank()) {
        Text(
            text = emoji,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(6.dp))
    }
    Text(
        text = name,
        color = textColor,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.widthIn(max = 120.dp),
    )
    Spacer(Modifier.width(8.dp))
    Text(
        text = amount,
        color = textColor,
        softWrap = false,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.width(8.dp))
    Text(
        text = percent,
        color = textColor,
        softWrap = false,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}
