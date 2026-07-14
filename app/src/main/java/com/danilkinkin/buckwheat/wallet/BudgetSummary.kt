package com.danilkinkin.buckwheat.wallet

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.analytics.RestAndSpentBudgetCard
import com.danilkinkin.buckwheat.analytics.WholeBudgetCard
import com.danilkinkin.buckwheat.data.ExtendCurrency
import java.math.BigDecimal
import java.util.Date

@Composable
fun BudgetSummary(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    onEdit: () -> Unit = {},
) {
    val currency by spendsViewModel.currency.observeAsState(ExtendCurrency.none())
    val wholeBudget = spendsViewModel.budget.value ?: BigDecimal.ZERO

    Column(Modifier.padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 16.dp)) {
        RestAndSpentBudgetCard(
            modifier = Modifier,
            bigVariant = true
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            WholeBudgetCard(
                modifier = Modifier.weight(1f),
                bigVariant = false,
                budget = wholeBudget,
                currency = currency,
                startDate = spendsViewModel.startPeriodDate.value ?: Date(),
                finishDate = spendsViewModel.finishPeriodDate.value ?: Date(),
            )
            DaysLeftCard(
                startDate = spendsViewModel.startPeriodDate.value ?: Date(),
                finishDate = spendsViewModel.finishPeriodDate.value ?: Date(),
            )
        }
        EditButton(onClick = { onEdit() })
    }
}
