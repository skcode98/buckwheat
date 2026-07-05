package com.danilkinkin.buckwheat.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal

@Composable
fun CategorySpendingLimitsCard(
    spends: List<Transaction>,
    currency: ExtendCurrency,
    spendsViewModel: SpendsViewModel,
) {
    val categories by spendsViewModel.categories.observeAsState(emptyList())
    val mappings by spendsViewModel.tagCategoryMappings.observeAsState(emptyList())
    val untaggedTagName = ""

    val categoriesWithLimits = categories.filter { it.monthlyLimit > BigDecimal.ZERO }
    if (categoriesWithLimits.isEmpty()) return

    val spendPerCategory = mutableMapOf<Long, BigDecimal>()
    val tagToCategory = mappings.associate { it.tagName to it.categoryId }

    for (tx in spends) {
        if (tx.type != TransactionType.SPENT) continue
        val tag = tx.comment.ifBlank { untaggedTagName }
        val catId = tagToCategory[tag] ?: -1L
        if (catId in categoriesWithLimits.map { it.id }) {
            spendPerCategory[catId] = (spendPerCategory[catId] ?: BigDecimal.ZERO) + tx.value
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Category spending limits",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            categoriesWithLimits.forEach { cat ->
                val spent = spendPerCategory[cat.id] ?: BigDecimal.ZERO
                val limit = cat.monthlyLimit
                val progress = if (limit > BigDecimal.ZERO)
                    (spent / limit).toFloat().coerceIn(0f, 1f)
                else 0f
                val isOver = spent > limit
                val catColor = Color(cat.color)

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = cat.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${spent.setScale(2)} / ${limit.setScale(2)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOver) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = if (isOver) MaterialTheme.colorScheme.error
                        else catColor,
                    trackColor = catColor.copy(alpha = 0.2f),
                    strokeCap = StrokeCap.Round,
                )
            }
        }
    }
}
