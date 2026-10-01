package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.sync.SyncDirtyMarker
import com.danilkinkin.buckwheat.sync.SyncTables
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ArchivesViewModel @Inject constructor(
    private val budgetPeriodDao: BudgetPeriodDao,
    private val syncDirtyMarker: SyncDirtyMarker,
) : ViewModel() {
    val periods: StateFlow<List<BudgetPeriod>> = budgetPeriodDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPeriodId = MutableStateFlow<String?>(null)

    fun selectPeriod(periodId: String) {
        _selectedPeriodId.value = periodId
    }

    val selectedPeriod: StateFlow<BudgetPeriod?> = combine(periods, _selectedPeriodId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedPeriodTransactions: StateFlow<List<ArchivedTransaction>> = _selectedPeriodId.flatMapLatest { id ->
        if (id != null) budgetPeriodDao.getTransactionsForPeriod(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updatePeriodDates(periodId: String, startDate: Date, finishDate: Date) = viewModelScope.launch {
        budgetPeriodDao.updateDates(periodId, startDate, finishDate)
        syncDirtyMarker.markUpsert(SyncTables.BUDGET_PERIODS, periodId)
    }

    fun updatePeriodBudget(periodId: String, budget: BigDecimal) = viewModelScope.launch {
        budgetPeriodDao.updateBudget(periodId, budget)
        syncDirtyMarker.markUpsert(SyncTables.BUDGET_PERIODS, periodId)
    }

    fun deletePeriod(periodId: String) = viewModelScope.launch {
        // Read before deleting: markDelete needs the row's family metadata.
        val existing = budgetPeriodDao.getById(periodId)
        if (_selectedPeriodId.value == periodId) {
            _selectedPeriodId.value = null
        }
        budgetPeriodDao.deleteById(periodId)
        if (existing != null) {
            syncDirtyMarker.markDelete(
                SyncTables.BUDGET_PERIODS,
                existing.id,
                existing.familyId,
                existing.syncSeq,
            )
        }
    }
}
