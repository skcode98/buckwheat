package com.danilkinkin.buckwheat.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.SpendType
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal

@Composable
fun NeedsWantsBudgetRow(
    needsBudget: LiveData<BigDecimal>,
    wantsBudget: LiveData<BigDecimal>,
    spends: List<Transaction>,
    currency: ExtendCurrency,
) {
    val needsBudgetValue by needsBudget.observeAsState(BigDecimal.ZERO)
    val wantsBudgetValue by wantsBudget.observeAsState(BigDecimal.ZERO)

    val needsSpent = spends.filter {
        it.type == TransactionType.SPENT && it.spendType == SpendType.NEEDS
    }.sumOf { it.value.toDouble() }.let { BigDecimal.valueOf(it) }

    val wantsSpent = spends.filter {
        it.type == TransactionType.SPENT && it.spendType == SpendType.WANTS
    }.sumOf { it.value.toDouble() }.let { BigDecimal.valueOf(it) }

    val hasNeeds = needsBudgetValue > BigDecimal.ZERO
    val hasWants = wantsBudgetValue > BigDecimal.ZERO
    if (!hasNeeds && !hasWants) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasNeeds) {
            NeedsWantsCard(
                label = "Needs",
                spent = needsSpent,
                budget = needsBudgetValue,
                currency = currency,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }
        if (hasWants) {
            NeedsWantsCard(
                label = "Wants",
                spent = wantsSpent,
                budget = wantsBudgetValue,
                currency = currency,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NeedsWantsCard(
    label: String,
    spent: BigDecimal,
    budget: BigDecimal,
    currency: ExtendCurrency,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val progress = if (budget > BigDecimal.ZERO)
        (spent / budget).toFloat().coerceIn(0f, 1f)
    else 0f
    val isOver = spent > budget

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = numberFormat(context, spent, currency),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isOver) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "of ${numberFormat(context, budget, currency)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = if (isOver) MaterialTheme.colorScheme.error
                    else color,
                trackColor = color.copy(alpha = 0.2f),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}
