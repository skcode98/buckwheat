/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.danilkinkin.trackinvest.di.PortfolioRepository
import com.danilkinkin.trackinvest.di.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

data class DashboardUiState(
    val summary: PortfolioSummary,
    val currencySymbol: String,
    val monthlyTarget: Double,
    val salary: Double,
    val regime: String,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val portfolioRepository: PortfolioRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val state: LiveData<DashboardUiState> = combine(
        portfolioRepository.summary(),
        settingsRepository.getCurrencySymbol(),
        settingsRepository.getMonthlyInvestmentTarget(),
        settingsRepository.getSalary(),
        settingsRepository.getRegime(),
    ) { summary, currencySymbol, monthlyTarget, salary, regime ->
        DashboardUiState(
            summary = summary,
            currencySymbol = currencySymbol,
            monthlyTarget = monthlyTarget,
            salary = salary,
            regime = regime,
        )
    }.asLiveData()

    suspend fun setSalary(salary: Double) = settingsRepository.setSalary(salary)

    suspend fun setRegime(regime: String) = settingsRepository.setRegime(regime)

    suspend fun setMonthlyTarget(target: Double) = settingsRepository.setMonthlyInvestmentTarget(target)
}
