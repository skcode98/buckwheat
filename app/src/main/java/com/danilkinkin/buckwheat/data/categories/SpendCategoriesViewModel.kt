package com.danilkinkin.buckwheat.data.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
    val isCategorizing: StateFlow<Boolean> = scheduler.isRunning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val uncategorizedCount: StateFlow<Int> = combine(
        transactionDao.getUncategorizedCount(),
        budgetPeriodDao.getArchivedUncategorizedCount(),
    ) { active, archived ->
        (active ?: 0) + (archived ?: 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun categorizeUncategorized() = scheduler.schedule()
}
