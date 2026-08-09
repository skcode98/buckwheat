/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.danilkinkin.trackinvest.data.entities.RecurringSip
import com.danilkinkin.trackinvest.data.entities.Template
import com.danilkinkin.trackinvest.di.RecurringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringRepository: RecurringRepository,
) : ViewModel() {
    val sips: LiveData<List<RecurringSip>> = recurringRepository.sips()

    val templates: LiveData<List<Template>> = recurringRepository.templates()

    suspend fun saveSip(sip: RecurringSip) = recurringRepository.saveSip(sip)

    suspend fun deleteSip(sip: RecurringSip) = recurringRepository.deleteSip(sip)

    suspend fun saveTemplate(template: Template) = recurringRepository.saveTemplate(template)

    suspend fun deleteTemplate(template: Template) = recurringRepository.deleteTemplate(template)

    suspend fun quickLog(template: Template) = recurringRepository.quickLog(template)

    suspend fun processDueSips(): Int = recurringRepository.processDueSips()
}
