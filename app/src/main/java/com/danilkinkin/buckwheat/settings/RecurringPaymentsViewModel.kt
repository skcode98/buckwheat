package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.RecurringAutoApplyMode
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.di.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class RecurringPaymentsViewModel @Inject constructor(
    private val recurringDao: RecurringDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val templates: LiveData<List<RecurringTemplate>> = recurringDao.getAll()
    val autoApplyMode: LiveData<RecurringAutoApplyMode> =
        settingsRepository.getRecurringAutoApplyMode().asLiveData()

    fun setAutoApplyMode(mode: RecurringAutoApplyMode) {
        viewModelScope.launch {
            settingsRepository.setRecurringAutoApplyMode(mode)
        }
    }

    fun addTemplate(amount: BigDecimal, comment: String, dayOfMonth: Int) {
        if (amount <= BigDecimal.ZERO || comment.isBlank() || dayOfMonth !in 1..31) return
        viewModelScope.launch {
            recurringDao.insert(
                RecurringTemplate(
                    amount = amount,
                    comment = comment.trim(),
                    dayOfMonth = dayOfMonth,
                )
            )
        }
    }

    fun toggleEnabled(template: RecurringTemplate) {
        viewModelScope.launch {
            recurringDao.update(template.copy(enabled = !template.enabled))
        }
    }

    fun deleteTemplate(id: Int) {
        viewModelScope.launch {
            recurringDao.deleteById(id)
        }
    }
}
