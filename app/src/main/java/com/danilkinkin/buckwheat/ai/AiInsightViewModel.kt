package com.danilkinkin.buckwheat.ai

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.analytics.findPreviousPeriod
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.categoryTotals
import com.danilkinkin.buckwheat.di.GetCurrentDateUseCase
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.util.toLocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

sealed interface AiInsightUiState {
    data object Idle : AiInsightUiState
    data object Loading : AiInsightUiState

    // The offline engine always produces a report; when AI is configured it tries to upgrade the
    // narrative in the background (aiLoading) and swaps in the AI text on success.
    data class Report(
        val data: MonthOverviewData,
        val text: String,
        val isAi: Boolean,
        val aiFailure: String?,
        val aiLoading: Boolean,
    ) : AiInsightUiState

    data class Error(val message: String) : AiInsightUiState
}

@HiltViewModel
class AiInsightViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spendsRepository: SpendsRepository,
    private val getCurrentDateUseCase: GetCurrentDateUseCase,
) : ViewModel() {
    private val _state = MutableLiveData<AiInsightUiState>(AiInsightUiState.Idle)
    val state: LiveData<AiInsightUiState> = _state

    fun generate() {
        val current = _state.value
        if (current is AiInsightUiState.Loading) return
        if (current is AiInsightUiState.Report && current.aiLoading) return
        viewModelScope.launch {
            _state.value = AiInsightUiState.Loading
            val data = runCatching { buildOverviewData() }.getOrNull()
            if (data == null) {
                _state.value = AiInsightUiState.Error("Could not load period data")
                return@launch
            }
            val offlineText = buildOfflineReport(
                summary = data.summary,
                spends = data.spends.map { WindowSpend(it.date, it.value, it.category) },
            )
            val offlineReport = AiInsightUiState.Report(
                data = data,
                text = offlineText,
                isAi = false,
                aiFailure = null,
                aiLoading = true,
            )
            _state.value = offlineReport
            val ai = runCatching { generateAiInsight(context, data.summary) }.getOrNull()
            _state.value = when (ai) {
                is AiInsightResult.Success -> offlineReport.copy(
                    text = ai.report,
                    isAi = true,
                    aiFailure = null,
                    aiLoading = false,
                )
                is AiInsightResult.Failure -> offlineReport.copy(
                    aiFailure = ai.message,
                    aiLoading = false,
                )
                AiInsightResult.NotConfigured -> offlineReport.copy(aiLoading = false)
                null -> offlineReport.copy(
                    aiFailure = "unknown error",
                    aiLoading = false,
                )
            }
        }
    }

    private suspend fun buildOverviewData(): MonthOverviewData {
        val today = getCurrentDateUseCase().toLocalDate()
        val startPeriodDate = spendsRepository.getStartPeriodDate().first()
        val finishPeriodDate = spendsRepository.getFinishPeriodActualDate().first()
            ?: spendsRepository.getFinishPeriodDate().first()
            ?: getCurrentDateUseCase()
        val spent = spendsRepository.getSpent().first()
        val spends = spendsRepository.getAllSpends().asFlow().first()
        val transactions = spendsRepository.getAllTransactions().asFlow().first()
        val periods = spendsRepository.getAllBudgetPeriods().asFlow().first()
        val dailyBudget = spendsRepository.getDailyBudget().first()
        val previousPeriodTotal = findPreviousPeriod(periods, startPeriodDate)?.totalSpent
        val biggest = spends.maxByOrNull { it.value }
        val currencyCode = spendsRepository.getCurrency().first().value ?: ""

        val categories = categoryTotals(spends).map { (key, amount) ->
            CategorySpendInsight(
                name = when (key) {
                    is CategoryKey.BuiltIn -> key.category.name
                    is CategoryKey.Custom -> key.name
                },
                amount = amount,
                percent = if (spent > BigDecimal.ZERO) {
                    amount.multiply(BigDecimal(100))
                        .divide(spent, 0, RoundingMode.HALF_EVEN)
                        .toInt()
                } else {
                    0
                },
            )
        }

        val summary = SpendInsightSummary(
            currencyCode = currencyCode,
            budget = spendsRepository.getBudget().first(),
            spent = spent,
            startDate = startPeriodDate.toLocalDate(),
            endDate = finishPeriodDate.toLocalDate(),
            today = today,
            transactionCount = spends.size,
            categories = categories,
            biggestSpend = biggest?.value,
            biggestSpendComment = biggest?.comment,
            overspendDays = overspendDayCount(
                spends = spends.map { WindowSpend(it.date, it.value, it.category) },
                start = startPeriodDate.toLocalDate(),
                finish = finishPeriodDate.toLocalDate(),
                today = today,
                dailyBudget = dailyBudget,
            ),
            previousPeriodTotal = previousPeriodTotal,
            dailyBudget = dailyBudget,
        )

        return MonthOverviewData(
            summary = summary,
            spends = spends,
            transactions = transactions,
            periods = periods,
            startDate = startPeriodDate,
            finishDate = finishPeriodDate,
            currency = ExtendCurrency.getInstance(currencyCode.takeIf { it.isNotBlank() }),
        )
    }
}
