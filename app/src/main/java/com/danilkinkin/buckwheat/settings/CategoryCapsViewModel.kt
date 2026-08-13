package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.autoAssignCategoryCaps
import com.danilkinkin.buckwheat.data.categories.categoryTotals
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.toTransaction
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.budgetStoreKey
import com.danilkinkin.buckwheat.di.finishPeriodDateStoreKey
import com.danilkinkin.buckwheat.di.startPeriodDateStoreKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class CategoryCapsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val transactionDao: TransactionDao,
    private val budgetPeriodDao: BudgetPeriodDao,
) : ViewModel() {
    val caps: LiveData<Map<String, BigDecimal>> =
        settingsRepository.getCategoryCaps().asLiveData()

    // `amount == null` or non-positive clears the cap for the category.
    fun setCap(name: String, amount: BigDecimal?) {
        viewModelScope.launch {
            val current = settingsRepository.getCategoryCaps().first()
            settingsRepository.setCategoryCaps(
                if (amount == null || amount <= BigDecimal.ZERO) current - name
                else current + (name to amount)
            )
        }
    }

    // Divides the current budget across all categories by their typical monthly spend
    // (averaged over the current period + archived periods); with no history yet the budget
    // is split evenly. Replaces every cap so the user starts from the allocation and can
    // reassign per category afterwards.
    fun autoAssignBudget(categories: List<String>) {
        viewModelScope.launch {
            val prefs = context.budgetDataStore.data.first()
            val budget = prefs[budgetStoreKey]?.toBigDecimalOrNull() ?: return@launch
            if (budget <= BigDecimal.ZERO) return@launch

            val start = prefs[startPeriodDateStoreKey]?.let { Date(it) }
            val finish = prefs[finishPeriodDateStoreKey]?.let { Date(it) }

            val currentSpends = transactionDao.getAllNow()
                .filter { it.type == TransactionType.SPENT }
                .filter { tx ->
                    start == null || finish == null ||
                        (!tx.date.before(start) && !tx.date.after(finish))
                }

            val periods = mutableListOf<List<Pair<CategoryKey, BigDecimal>>>()
            periods += categoryTotals(currentSpends)
            budgetPeriodDao.getAllArchivedNow()
                .filter { it.type == TransactionType.SPENT }
                .groupBy { it.periodId }
                .values
                .forEach { group -> periods += categoryTotals(group.map { it.toTransaction() }) }

            settingsRepository.setCategoryCaps(
                autoAssignCategoryCaps(budget, categories, periods)
            )
        }
    }
}
