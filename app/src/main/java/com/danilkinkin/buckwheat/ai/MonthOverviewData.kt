package com.danilkinkin.buckwheat.ai

import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.Transaction
import java.util.Date

// Everything the monthly report sheet needs to render the at-a-glance overview: the pure stats
// for the narrative + the raw period data the existing analytics chart composables are fed from.
data class MonthOverviewData(
    val summary: SpendInsightSummary,
    val spends: List<Transaction>,
    val transactions: List<Transaction>,
    val periods: List<BudgetPeriod>,
    val startDate: Date,
    val finishDate: Date,
    val currency: ExtendCurrency,
)
