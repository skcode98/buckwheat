/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.home.portfolio

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.danilkinkin.trackinvest.data.PortfolioSummary
import com.danilkinkin.trackinvest.di.PortfolioRepository
import com.danilkinkin.trackinvest.di.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val portfolioRepository: PortfolioRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val summary: LiveData<PortfolioSummary> = portfolioRepository.summary().asLiveData()

    val currencySymbol: LiveData<String> = settingsRepository.getCurrencySymbol().asLiveData()
}
