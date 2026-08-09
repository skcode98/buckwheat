/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.danilkinkin.trackinvest.data.entities.Investment
import com.danilkinkin.trackinvest.di.LedgerRepository
import com.danilkinkin.trackinvest.di.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val investments: LiveData<List<Investment>> = ledgerRepository.investments()

    val currencySymbol: LiveData<String> = settingsRepository.getCurrencySymbol().asLiveData()

    suspend fun saveInvestment(investment: Investment) = ledgerRepository.saveInvestment(investment)

    suspend fun deleteInvestment(investment: Investment) = ledgerRepository.deleteInvestment(investment)

    suspend fun exportCsv(): String = ledgerRepository.exportCsv()

    suspend fun importCsv(csv: String): Int = ledgerRepository.importCsv(csv)
}
