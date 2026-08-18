package com.danilkinkin.buckwheat.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.numberFormat

const val RECURRING_CHARGE_CONFIRM_SHEET = "recurringChargeConfirm"

// One-tap confirmation for recurring payments queued in ASK mode. Recording happens only
// via the Add button; any other dismissal (back, swipe on older systems) drops the queue
// for that day, mirroring the "skip" behavior of the budget-distribution ASK sheet.
@Composable
fun RecurringChargeConfirmSheet(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val context = LocalContext.current
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val pending by spendsViewModel.pendingRecurringCharges.collectAsStateWithLifecycle()
    val currency by spendsViewModel.currency.collectAsStateWithLifecycle()
    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    DisposableEffect(Unit) {
        onDispose {
            spendsViewModel.skipRecurringCharges()
        }
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = navigationBarHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.recurring_charge_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.recurring_charge_confirm_description),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
            }
            pending.forEach { transaction ->
                PendingChargeRow(
                    transaction = transaction,
                    currency = currency,
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        spendsViewModel.skipRecurringCharges()
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.recurring_charge_confirm_skip))
                }
                Button(
                    onClick = {
                        spendsViewModel.confirmRecurringCharges()
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.recurring_charge_confirm_add))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PendingChargeRow(
    transaction: Transaction,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = numberFormat(context, transaction.value, currency),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = transaction.comment,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = DateFormat.getDateFormat(context).format(transaction.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewRecurringChargeConfirm() {
    BuckwheatTheme {
        RecurringChargeConfirmSheet()
    }
}
