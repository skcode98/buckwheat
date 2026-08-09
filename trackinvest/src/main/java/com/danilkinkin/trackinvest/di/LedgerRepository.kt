/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.di

import com.danilkinkin.trackinvest.data.csvToInvestments
import com.danilkinkin.trackinvest.data.dao.InvestmentDao
import com.danilkinkin.trackinvest.data.investmentsToCsv
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LedgerRepository @Inject constructor(
    private val investmentDao: InvestmentDao,
) {
    suspend fun exportCsv(): String = investmentsToCsv(investmentDao.getAllNow())

    suspend fun importCsv(csv: String): Int {
        val investments = csvToInvestments(csv)
        if (investments.isEmpty()) return 0
        investmentDao.insertAll(investments)
        return investments.size
    }
}
