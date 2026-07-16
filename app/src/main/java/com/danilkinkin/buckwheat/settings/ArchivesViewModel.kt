package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ArchivesViewModel @Inject constructor(
    private val budgetPeriodDao: BudgetPeriodDao,
) : ViewModel() {
    val periods: LiveData<List<BudgetPeriod>> = budgetPeriodDao.getAll()

    private val _selectedPeriodId = MutableLiveData<Int?>()

    fun selectPeriod(periodId: Int) {
        _selectedPeriodId.value = periodId
    }

    val selectedPeriod: LiveData<BudgetPeriod?> = MediatorLiveData<BudgetPeriod?>().apply {
        var latestList: List<BudgetPeriod> = emptyList()
        var latestId: Int? = null

        addSource(periods) { list ->
            latestList = list
            value = list.firstOrNull { it.id == latestId }
        }
        addSource(_selectedPeriodId) { id ->
            latestId = id
            value = latestList.firstOrNull { it.id == id }
        }
    }

    val selectedPeriodTransactions: LiveData<List<ArchivedTransaction>> = _selectedPeriodId.switchMap { id ->
        if (id != null) budgetPeriodDao.getTransactionsForPeriod(id) else MutableLiveData(emptyList())
    }
}
