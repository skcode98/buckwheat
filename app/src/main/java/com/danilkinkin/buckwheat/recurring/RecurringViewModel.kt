package com.danilkinkin.buckwheat.recurring

import androidx.lifecycle.ViewModel
import com.danilkinkin.buckwheat.di.RecurringRepository
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.math.BigDecimal

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringRepository: RecurringRepository,
) : ViewModel() {

    fun getTemplates(): List<RecurringTemplate> = recurringRepository.getAll()

    fun addTemplate(amount: BigDecimal, comment: String, dayOfMonth: Int) {
        recurringRepository.add(amount, comment, dayOfMonth)
    }

    fun deleteTemplate(id: Int) {
        recurringRepository.delete(id)
    }

    fun toggleTemplate(template: RecurringTemplate) {
        recurringRepository.update(template.copy(enabled = !template.enabled))
    }
}
