/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import androidx.lifecycle.ViewModel
import com.danilkinkin.trackinvest.di.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
) : ViewModel() {
    suspend fun exportCsv(): String = ledgerRepository.exportCsv()

    suspend fun importCsv(csv: String): Int = ledgerRepository.importCsv(csv)
}
