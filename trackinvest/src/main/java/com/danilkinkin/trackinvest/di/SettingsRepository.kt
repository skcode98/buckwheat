/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.trackinvestDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trackinvest_preferences",
)

private val monthlyInvestmentTargetKey = doublePreferencesKey("monthly_investment_target")
private val fireTargetMonthlyKey = doublePreferencesKey("fire_target_monthly")
private val currencyKey = stringPreferencesKey("currency")
private val currencySymbolKey = stringPreferencesKey("currency_symbol")
private val privacyModeKey = booleanPreferencesKey("privacy_mode")
private val themeKey = stringPreferencesKey("theme")
private val accountsKey = stringSetPreferencesKey("accounts")
private val activeAccountFilterKey = stringPreferencesKey("active_account_filter")
private val fyStartMonthKey = intPreferencesKey("fy_start_month")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun getMonthlyInvestmentTarget(): Flow<Double> = context.trackinvestDataStore.data.map {
        it[monthlyInvestmentTargetKey] ?: 0.0
    }

    fun getFireTargetMonthly(): Flow<Double> = context.trackinvestDataStore.data.map {
        it[fireTargetMonthlyKey] ?: 0.0
    }

    fun getCurrency(): Flow<String> = context.trackinvestDataStore.data.map {
        it[currencyKey] ?: "INR"
    }

    fun getCurrencySymbol(): Flow<String> = context.trackinvestDataStore.data.map {
        it[currencySymbolKey] ?: "₹"
    }

    fun isPrivacyMode(): Flow<Boolean> = context.trackinvestDataStore.data.map {
        it[privacyModeKey] ?: false
    }

    fun getTheme(): Flow<String> = context.trackinvestDataStore.data.map {
        it[themeKey] ?: "indigo"
    }

    fun getAccounts(): Flow<List<String>> = context.trackinvestDataStore.data.map {
        it[accountsKey]?.toList()?.takeIf { list -> list.isNotEmpty() } ?: listOf("Main Portfolio")
    }

    fun getActiveAccountFilter(): Flow<String?> = context.trackinvestDataStore.data.map {
        it[activeAccountFilterKey]
    }

    fun getFyStartMonth(): Flow<Int> = context.trackinvestDataStore.data.map {
        it[fyStartMonthKey] ?: 3
    }

    suspend fun setMonthlyInvestmentTarget(target: Double) {
        context.trackinvestDataStore.edit {
            it[monthlyInvestmentTargetKey] = target
        }
    }

    suspend fun setFireTargetMonthly(target: Double) {
        context.trackinvestDataStore.edit {
            it[fireTargetMonthlyKey] = target
        }
    }

    suspend fun setCurrency(currency: String, symbol: String) {
        context.trackinvestDataStore.edit {
            it[currencyKey] = currency
            it[currencySymbolKey] = symbol
        }
    }

    suspend fun setPrivacyMode(enabled: Boolean) {
        context.trackinvestDataStore.edit {
            it[privacyModeKey] = enabled
        }
    }

    suspend fun setTheme(theme: String) {
        context.trackinvestDataStore.edit {
            it[themeKey] = theme
        }
    }

    suspend fun setAccounts(accounts: List<String>) {
        context.trackinvestDataStore.edit {
            it[accountsKey] = accounts.toSet()
        }
    }

    suspend fun setActiveAccountFilter(account: String?) {
        context.trackinvestDataStore.edit {
            if (account.isNullOrBlank()) {
                it.remove(activeAccountFilterKey)
            } else {
                it[activeAccountFilterKey] = account
            }
        }
    }

    suspend fun setFyStartMonth(month: Int) {
        context.trackinvestDataStore.edit {
            it[fyStartMonthKey] = month
        }
    }
}
