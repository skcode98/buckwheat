package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
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
) : ViewModel() {
    val periods: StateFlow<List<BudgetPeriod>> = budgetPeriodDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPeriodId = MutableStateFlow<Int?>(null)

    fun selectPeriod(periodId: Int) {
        _selectedPeriodId.value = periodId
    }

    val selectedPeriod: StateFlow<BudgetPeriod?> = combine(periods, _selectedPeriodId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedPeriodTransactions: StateFlow<List<ArchivedTransaction>> = _selectedPeriodId.flatMapLatest { id ->
        if (id != null) budgetPeriodDao.getTransactionsForPeriod(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updatePeriodDates(periodId: Int, startDate: Date, finishDate: Date) = viewModelScope.launch {
        budgetPeriodDao.updateDates(periodId, startDate, finishDate)
    }

    fun updatePeriodBudget(periodId: Int, budget: BigDecimal) = viewModelScope.launch {
        budgetPeriodDao.updateBudget(periodId, budget)
    }

    fun deletePeriod(periodId: Int) = viewModelScope.launch {
        if (_selectedPeriodId.value == periodId) {
            _selectedPeriodId.value = null
        }
        budgetPeriodDao.deleteById(periodId)
    }
}
