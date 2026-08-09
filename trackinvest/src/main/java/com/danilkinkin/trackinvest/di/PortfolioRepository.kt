/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.di

import com.danilkinkin.trackinvest.data.PortfolioSummary
import com.danilkinkin.trackinvest.data.computePortfolioSummary
import com.danilkinkin.trackinvest.data.dao.CategoryDao
import com.danilkinkin.trackinvest.data.dao.CategoryDetailDao
import com.danilkinkin.trackinvest.data.dao.InvestmentDao
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import androidx.lifecycle.asFlow

@Singleton
class PortfolioRepository @Inject constructor(
    private val investmentDao: InvestmentDao,
    private val categoryDao: CategoryDao,
    private val categoryDetailDao: CategoryDetailDao,
    private val settingsRepository: SettingsRepository,
) {
    fun summary(): Flow<PortfolioSummary> = combine(
        investmentDao.getAll().asFlow(),
        categoryDao.getAll().asFlow(),
        categoryDetailDao.getAll().asFlow(),
        settingsRepository.getFyStartMonth(),
    ) { investments, categories, categoryDetails, fyStartMonth ->
        computePortfolioSummary(
            investments = investments,
            categories = categories,
            categoryDetails = categoryDetails,
            today = LocalDate.now(ZoneId.systemDefault()),
            fyStartMonth = fyStartMonth,
        )
    }.flowOn(Dispatchers.Default)
}
