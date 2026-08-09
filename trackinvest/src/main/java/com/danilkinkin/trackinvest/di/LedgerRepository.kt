/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.di

import androidx.lifecycle.LiveData
import com.danilkinkin.trackinvest.data.csvToInvestments
import com.danilkinkin.trackinvest.data.dao.InvestmentDao
import com.danilkinkin.trackinvest.data.entities.Investment
import com.danilkinkin.trackinvest.data.investmentsToCsv
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LedgerRepository @Inject constructor(
    private val investmentDao: InvestmentDao,
) {
    fun investments(): LiveData<List<Investment>> = investmentDao.getAll()

    suspend fun saveInvestment(investment: Investment) {
        if (investment.uid == 0) {
            investmentDao.insert(investment)
        } else {
            investmentDao.update(investment)
        }
    }

    suspend fun deleteInvestment(investment: Investment) {
        investmentDao.deleteById(investment.uid)
    }

    suspend fun exportCsv(): String = investmentsToCsv(investmentDao.getAllNow())

    suspend fun importCsv(csv: String): Int {
        val investments = csvToInvestments(csv)
        if (investments.isEmpty()) return 0
        investmentDao.insertAll(investments)
        return investments.size
    }
}
