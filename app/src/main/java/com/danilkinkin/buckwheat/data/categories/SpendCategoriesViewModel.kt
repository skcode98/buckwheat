package com.danilkinkin.buckwheat.data.categories

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Triggers the shared background category assignment pass and surfaces its running state plus the
// number of spends still missing a category (active + archived). The actual work runs in
// CategoryAssignmentScheduler on an application-scoped coroutine, so it continues even when this
// ViewModel is gone.
@HiltViewModel
class SpendCategoriesViewModel @Inject constructor(
    private val scheduler: CategoryAssignmentScheduler,
    transactionDao: TransactionDao,
    budgetPeriodDao: BudgetPeriodDao,
) : ViewModel() {
    val isCategorizing: LiveData<Boolean> = scheduler.isRunning.asLiveData()

    val uncategorizedCount: LiveData<Int> = MediatorLiveData<Int>().apply {
        value = 0

        var active = 0
        var archived = 0
        fun recalc() {
            value = active + archived
        }

        addSource(transactionDao.getUncategorizedCount()) { count ->
            active = count ?: 0
            recalc()
        }
        addSource(budgetPeriodDao.getArchivedUncategorizedCount()) { count ->
            archived = count ?: 0
            recalc()
        }
    }

    fun categorizeUncategorized() = scheduler.schedule()
}
