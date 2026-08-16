package com.danilkinkin.buckwheat.history

import android.content.Context
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.categories.categoryKey
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorOnEditor
import com.danilkinkin.buckwheat.util.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

@Composable
fun SpentItem(
    transaction: Transaction,
    currency: ExtendCurrency,
    modifier: Modifier = Modifier,
    categoryEmojis: Map<String, String> = emptyMap(),
) {
    val context = LocalContext.current
    val categoryLabel = remember(transaction, categoryEmojis) {
        categoryLabelFor(context, transaction, categoryEmojis)
    }
    Column(Modifier.padding(bottom = 18.dp)) {
        Row(modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .padding( start = 32.dp, top = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = numberFormat(context = context, transaction.value, currency = currency),
                    style = MaterialTheme.typography.headlineMedium,
                    fontSize = MaterialTheme.typography.headlineMedium.fontSize,
                    color = colorOnEditor,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier,
                )
            }
            Box {
                Text(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            start = 16.dp,
                            top = 16.dp,
                            end = 32.dp,
                        ),
                    text = prettyDate(transaction.date, shortMonth = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorOnEditor,
                    softWrap = false,
                )
            }
        }
        if (categoryLabel != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 4.dp, end = 32.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                CategoryLabelPill(
                    emoji = categoryLabel.first,
                    name = categoryLabel.second,
                )
            }
        }
        if (transaction.comment.isNotEmpty()) {
            Text(
                modifier = Modifier.padding(horizontal = 32.dp),
                text = transaction.comment,
                style = MaterialTheme.typography.bodyMedium,
                color = colorOnEditor.copy(alpha = 0.7f),
                softWrap = true,
            )
        }
    }
}

// The category shown on the history row: the persisted AI category when present, otherwise
// the offline keyword guess. Custom categories fall back to their saved emoji (or a generic
// one when none is saved).
private fun categoryLabelFor(
    context: Context,
    transaction: Transaction,
    categoryEmojis: Map<String, String>,
): Pair<String, String>? = when (val key = categoryKey(transaction)) {
    is CategoryKey.BuiltIn -> key.category.emoji to context.getString(key.category.labelRes)
    is CategoryKey.Custom -> SpendCategory.emojiFor(key.name, categoryEmojis[key.name]) to key.name
}

@Composable
private fun CategoryLabelPill(
    emoji: String,
    name: String,
) {
    Surface(
        modifier = Modifier,
        shape = RoundedCornerShape(50),
        color = colorOnEditor.copy(alpha = 0.08f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (emoji.isNotBlank()) {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = colorOnEditor.copy(alpha = 0.7f),
            )
        }
    }
}

@Preview(name = "Default")
@Composable
private fun PreviewDefault() {
    BuckwheatTheme {
        SpentItem(
            Transaction(
                type = TransactionType.SPENT,
                value = BigDecimal(12340),
                date = Date(),
            ),
            ExtendCurrency.none()
        )
    }
}

@Preview(name = "Night mode", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewNightMode() {
    BuckwheatTheme {
        SpentItem(
            Transaction(
                type = TransactionType.SPENT,
                value = BigDecimal(12340),
                date = LocalDateTime.now().minusMonths(2).toLocalDate().toDate(),
                comment = "Comment for spent",
            ),
            ExtendCurrency.none()
        )
    }
}

@Preview(name = "With big spent and long comment (Night mode)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun PreviewWithBigSpentAndLongCommentNightMode() {
    BuckwheatTheme {
        SpentItem(
            Transaction(
                type = TransactionType.SPENT,
                value = BigDecimal(123456789009876543),
                date = Date(),
                comment = "Very loooong comment for veryyy loooooooooooooooooong spent. And yet row for more length",
            ),
            ExtendCurrency.none()
        )
    }
}

@Preview(name = "Small screen", widthDp = 220)
@Composable
private fun PreviewSmallScreen() {
    BuckwheatTheme {
        SpentItem(
            Transaction(
                type = TransactionType.SPENT,
                value = BigDecimal(12340),
                date = Date(),
            ),
            ExtendCurrency.none()
        )
    }
}
