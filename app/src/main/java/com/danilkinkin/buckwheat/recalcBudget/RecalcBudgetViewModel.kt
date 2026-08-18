package com.danilkinkin.buckwheat.recalcBudget

import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.util.countDaysToToday
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

@HiltViewModel
class RecalcBudgetViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val spendsRepository: SpendsRepository,
) : ViewModel() {
    val newDailyBudgetIfSplitPerDay: MutableStateFlow<BigDecimal> = MutableStateFlow(BigDecimal.ZERO)
    val newDailyBudgetIfAddToday: MutableStateFlow<BigDecimal> = MutableStateFlow(BigDecimal.ZERO)
    val howMuchNotSpent: MutableStateFlow<BigDecimal> = MutableStateFlow(BigDecimal.ZERO)
    val nextDayBudget: MutableStateFlow<BigDecimal> = MutableStateFlow(BigDecimal.ZERO)
    val isLastDay: MutableStateFlow<Boolean> = MutableStateFlow(false)

    fun calculate() = viewModelScope.launch {
        isLastDay.value = spendsRepository.getFinishPeriodDate().first()
            ?.let { countDaysToToday(it) == 1 }
            ?: false

        calculateSplitOnRestDays()
        calculateAddToToday()
    }

    private fun calculateSplitOnRestDays() = viewModelScope.launch {
        val whatBudgetForDay = spendsRepository.whatBudgetForDay(applyTodaySpends = true)

        newDailyBudgetIfSplitPerDay.value = whatBudgetForDay.setScale(0, RoundingMode.HALF_EVEN)
    }

    private fun calculateAddToToday() = viewModelScope.launch {
        val budgetPerDayAdd = spendsRepository.howMuchNotSpent(
            excludeSkippedPart = true,
        )

        howMuchNotSpent.value = spendsRepository.howMuchSaved()
        nextDayBudget.value = spendsRepository.nextDayBudget()
        newDailyBudgetIfAddToday.value = budgetPerDayAdd.setScale(0, RoundingMode.HALF_EVEN)
    }
}