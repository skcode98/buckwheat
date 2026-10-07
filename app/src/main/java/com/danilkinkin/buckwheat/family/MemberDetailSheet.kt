package com.danilkinkin.buckwheat.family

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.history.DayCard
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.toLocalDate
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun MemberDetailSheet(
    memberId: String,
    viewModel: FamilyViewModel = hiltViewModel(),
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    onClose: () -> Unit,
) {
    val members by viewModel.members.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val currency by spendsViewModel.currency.collectAsStateWithLifecycle()

    val member = members.firstOrNull { it.id == memberId }
    val memberName = member?.displayName.orEmpty()
    val grouped: Map<LocalDate, List<FamilyTransaction>> = rows
        .filter { it.memberId == memberId }
        .groupBy { it.date.toLocalDate() }
        .toSortedMap()

    val context = LocalContext.current

    Surface(Modifier.fillMaxSize()) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.cancel),
                    )
                }
                val memberTotal = grouped.entries.fold(BigDecimal.ZERO) { acc, (_, txs) ->
                    acc + txs.fold(BigDecimal.ZERO) { sum, tx -> sum + tx.value }
                }
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = memberName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = numberFormat(context, memberTotal, currency),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            if (grouped.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.family_nothing_spent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 24.dp,
                        end = 24.dp,
                    ),
                ) {
                    grouped.forEach { (day, txs) ->
                        item(key = day) {
                            DayCard(
                                day = day,
                                transactions = txs.map { it.asTransaction() },
                                dayTotal = txs.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.value },
                                firstTransactionIndex = 0,
                                currency = currency,
                                readOnly = true,
                                memberNames = emptyMap(),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun FamilyTransaction.asTransaction(): Transaction =
    Transaction(
        id = id,
        type = type,
        value = value,
        date = date,
        comment = comment,
        category = category,
        memberId = memberId,
        syncSeq = syncSeq,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        version = version,
    )