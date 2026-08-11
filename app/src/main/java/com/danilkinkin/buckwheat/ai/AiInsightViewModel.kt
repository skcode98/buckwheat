package com.danilkinkin.buckwheat.ai

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.analytics.findPreviousPeriod
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.categoryTotals
import com.danilkinkin.buckwheat.di.GetCurrentDateUseCase
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.interleaved.WindowSpend
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
    data class Report(val text: String) : AiInsightUiState
    data class Error(val message: String) : AiInsightUiState
    data object NotConfigured : AiInsightUiState
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
        if (_state.value is AiInsightUiState.Loading) return
        viewModelScope.launch {
            _state.value = AiInsightUiState.Loading
            val outcome = runCatching { generateAiInsight(context, buildSummary()) }
            _state.value = when (val result = outcome.getOrNull()) {
                null -> AiInsightUiState.Error(
                    outcome.exceptionOrNull()?.message ?: "unknown error"
                )
                is AiInsightResult.Success -> AiInsightUiState.Report(result.report)
                is AiInsightResult.Failure -> AiInsightUiState.Error(result.message)
                AiInsightResult.NotConfigured -> AiInsightUiState.NotConfigured
            }
        }
    }

    private suspend fun buildSummary(): SpendInsightSummary {
        val today = getCurrentDateUseCase().toLocalDate()
        val startDate = spendsRepository.getStartPeriodDate().first()
        val finishDate = (spendsRepository.getFinishPeriodActualDate().first()
            ?: spendsRepository.getFinishPeriodDate().first()
            ?: getCurrentDateUseCase())
        val spent = spendsRepository.getSpent().first()
        val spends = spendsRepository.getAllSpends().asFlow().first()
        val periods = spendsRepository.getAllBudgetPeriods().asFlow().first()
        val previousPeriodTotal = findPreviousPeriod(periods, startDate)?.totalSpent
        val biggest = spends.maxByOrNull { it.value }

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

        return SpendInsightSummary(
            currencyCode = spendsRepository.getCurrency().first().value ?: "",
            budget = spendsRepository.getBudget().first(),
            spent = spent,
            startDate = startDate.toLocalDate(),
            endDate = finishDate.toLocalDate(),
            today = today,
            transactionCount = spends.size,
            categories = categories,
            biggestSpend = biggest?.value,
            biggestSpendComment = biggest?.comment,
            overspendDays = overspendDayCount(
                spends = spends.map { WindowSpend(it.date, it.value, it.category) },
                start = startDate.toLocalDate(),
                finish = finishDate.toLocalDate(),
                today = today,
                dailyBudget = spendsRepository.getDailyBudget().first(),
            ),
            previousPeriodTotal = previousPeriodTotal,
        )
    }
}
