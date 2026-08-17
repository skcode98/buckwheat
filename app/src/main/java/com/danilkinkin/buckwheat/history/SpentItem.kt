package com.danilkinkin.buckwheat.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissState
import androidx.compose.material.DismissValue
import androidx.compose.material.ExperimentalMaterialApi
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorOnEditor
import com.danilkinkin.buckwheat.util.HarmonizedColorPalette
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.harmonize
import com.danilkinkin.buckwheat.util.harmonizeWithColor
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.util.Date
import kotlin.math.abs
import kotlin.math.absoluteValue

val SpentItemContainerColor: Color
    @Composable
    @ReadOnlyComposable
    get() = combineColors(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.surfaceVariant,
        0.3f,
    )

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun SpentItem(
    transaction: Transaction,
    currency: ExtendCurrency,
    categoryEmojis: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onCopy: () -> Unit = {},
    onSwipeEdit: () -> Unit = {},
    onSwipeDelete: () -> Unit = {},
    onTriedSwipe: () -> Unit = {},
    showTutorial: Boolean = false,
) {
    val context = LocalContext.current
    val category = remember(transaction, categoryEmojis) {
        categoryLabelFor(context, transaction, categoryEmojis)
    }
    val palette = timelinePaletteFor(transaction)

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = SpentItemContainerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(start = 16.dp, end = 12.dp),
        ) {
            TimelineRail(
                palette = palette,
                emoji = category?.first ?: SpendCategory.DEFAULT_EMOJI,
                isLast = true,
            )
            Box(Modifier.weight(1f)) {
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
                            onDismiss = onSwipeEdit,
                        ),
                        endActionsConfig = SwipeActionsConfig(
                            threshold = 0.4f,
                            background = MaterialTheme.colorScheme.errorContainer,
                            backgroundActive = MaterialTheme.colorScheme.error,
                            iconTint = MaterialTheme.colorScheme.onError,
                            icon = painterResource(R.drawable.ic_delete_forever),
                            stayDismissed = true,
                            onDismiss = onSwipeDelete,
                        ),
                        onTried = onTriedSwipe,
                        showTutorial = showTutorial,
                    ) { state ->
                        SpentItemSheet(state = state) {
                            SpentItemActions(
                                transaction = transaction,
                                currency = currency,
                                categoryEmojis = categoryEmojis,
                                onEdit = onEdit,
                                onDelete = onDelete,
                                onCopy = onCopy,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun SpentItemSheet(
    state: DismissState,
    content: @Composable () -> Unit,
) {
    val size = with(LocalDensity.current) {
        val offset = try { state.offset.value } catch (_: Exception) { 0f }
        java.lang.Float.max(
            java.lang.Float.min(
                16.dp.toPx(),
                abs(offset),
            ), 0f
        ).toDp()
    }

    Box(Modifier.height(IntrinsicSize.Min)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = min(size / 4f, 4.dp))
                .clip(CircleShape),
            color = SpentItemContainerColor,
            shape = CircleShape,
        ) {
        }
        Box(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Preview(name = "Spent item", widthDp = 360)
@Composable
private fun PreviewSpentItem() {
    BuckwheatTheme {
        SpentItem(
            transaction = Transaction(
                type = TransactionType.SPENT,
                value = BigDecimal("1240"),
                date = Date(System.currentTimeMillis() - 3 * 3600 * 1000),
                comment = "Lunch at a cafe",
            ),
            currency = ExtendCurrency.none(),
            onEdit = {},
            onDelete = {},
            onCopy = {},
            readOnly = true,
        )
    }
}
