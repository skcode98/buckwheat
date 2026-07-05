package com.danilkinkin.buckwheat.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.dao.CategorySpentTotal
import com.danilkinkin.buckwheat.data.entities.Category
import com.danilkinkin.buckwheat.util.isSameDay
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

@Composable
fun CategoryOverview(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
) {
    val categories by spendsViewModel.categories.observeAsState(emptyList())
    val tagCategoryMappings by spendsViewModel.tagCategoryMappings.observeAsState(emptyList())
    val allTransactions by spendsViewModel.transactions.observeAsState(emptyList())
    val currency = spendsViewModel.currency.observeAsState(initial = com.danilkinkin.buckwheat.data.ExtendCurrency.none())

    if (categories.isEmpty()) return

    val now = LocalDate.now()
    val startOfMonth = now.withDayOfMonth(1)
    val startOfNextMonth = startOfMonth.plusMonths(1)
    val startMs = startOfMonth.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val endMs = startOfNextMonth.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val thisMonthTransactions = remember(allTransactions, startMs, endMs) {
        allTransactions.filter { tx ->
            tx.date.time in startMs until endMs
        }
    }

    val thisMonthCategoryTotals = remember(thisMonthTransactions) {
        val spentTxs = thisMonthTransactions.filter { it.categoryId != null }
        spentTxs.groupBy { it.categoryId!! }
            .mapValues { (_, txs) -> txs.sumOf { it.value.toDouble() }.let { BigDecimal.valueOf(it) } }
    }

    val mappingsByCategory = remember(tagCategoryMappings) {
        tagCategoryMappings.groupBy { it.categoryId }
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Categories",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))

        categories.forEach { category ->
            val spent = thisMonthCategoryTotals[category.id] ?: BigDecimal.ZERO
            val limit = category.monthlyLimit
            val progress = if (limit > BigDecimal.ZERO)
                (spent / limit).toFloat().coerceIn(0f, 1f) else 0f
            val overBudget = limit > BigDecimal.ZERO && spent > limit
            val tags = mappingsByCategory[category.id]?.map { it.tagName } ?: emptyList()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(category.color)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${spent.setScale(2).toPlainString()} ${currency.value ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (overBudget) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                    )
                    if (limit > BigDecimal.ZERO) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "/ ${limit.setScale(2).toPlainString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (limit > BigDecimal.ZERO) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (overBudget) MaterialTheme.colorScheme.error
                            else Color(category.color),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = tags.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
