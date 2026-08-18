package com.danilkinkin.buckwheat.di

import android.content.Context
import android.util.Log
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.categoryCapBucket
import com.danilkinkin.buckwheat.data.categories.categoryKey
import com.danilkinkin.buckwheat.data.categories.highestNewlyReachedCapBucket
import com.danilkinkin.buckwheat.notifications.CategoryCapNotifier
import com.danilkinkin.buckwheat.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryCapTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val transactionDao: TransactionDao,
) {
    suspend fun checkCategoryCapAlert(newTransaction: Transaction) {
        if (newTransaction.type != TransactionType.SPENT) return
        val prefs = context.budgetDataStore.data.first()
        val start = prefs[startPeriodDateStoreKey]?.let { Date(it) } ?: return
        val finish = prefs[finishPeriodDateStoreKey]?.let { Date(it) } ?: return
        if (newTransaction.date.before(start) || newTransaction.date.after(finish)) return

        val key = categoryKey(newTransaction)
        val categoryName = categoryNameOf(key)
        val caps = settingsRepository.getCategoryCaps().first()
        val cap = caps[categoryName] ?: return
        val total = periodCategoryTotal(start, finish, key)

        val newBucket = categoryCapBucket(total, cap)
        val notified = settingsRepository.getCategoryCapNotified()
        val newlyReached = highestNewlyReachedCapBucket(notified[categoryName] ?: 0, newBucket)
        if (newlyReached == 0) return

        val currency = prefs[currencyStoreKey]?.let { ExtendCurrency.getInstance(it) }
            ?: ExtendCurrency.none()
        CategoryCapNotifier.notify(context, key, newlyReached, total, cap, currency)
        settingsRepository.setCategoryCapNotified(notified + (categoryName to newlyReached))
    }

    suspend fun resyncCategoryCapNotified(removed: Transaction) {
        if (removed.type != TransactionType.SPENT) return
        val prefs = context.budgetDataStore.data.first()
        val start = prefs[startPeriodDateStoreKey]?.let { Date(it) } ?: return
        val finish = prefs[finishPeriodDateStoreKey]?.let { Date(it) } ?: return
        if (removed.date.before(start) || removed.date.after(finish)) return

        val key = categoryKey(removed)
        val categoryName = categoryNameOf(key)
        val caps = settingsRepository.getCategoryCaps().first()
        val cap = caps[categoryName] ?: return
        val total = periodCategoryTotal(start, finish, key)
        val currentBucket = categoryCapBucket(total, cap)
        val notified = settingsRepository.getCategoryCapNotified()
        val storedBucket = notified[categoryName] ?: 0
        if (currentBucket >= storedBucket) return

        val updated = notified.toMutableMap()
        if (currentBucket == 0) {
            updated.remove(categoryName)
        } else {
            updated[categoryName] = currentBucket
        }
        settingsRepository.setCategoryCapNotified(updated)
    }

    suspend fun clearCategoryCapNotifiedNow() {
        settingsRepository.clearCategoryCapNotified()
    }

    private suspend fun periodCategoryTotal(start: Date, finish: Date, key: CategoryKey): BigDecimal =
        transactionDao.getAllNow(TransactionType.SPENT, start.time, finish.time)
            .filter { categoryKey(it) == key }
            .fold(BigDecimal.ZERO) { acc, tx -> acc + tx.value }

    private fun categoryNameOf(key: CategoryKey): String = when (key) {
        is CategoryKey.BuiltIn -> key.category.name
        is CategoryKey.Custom -> key.name
    }
}