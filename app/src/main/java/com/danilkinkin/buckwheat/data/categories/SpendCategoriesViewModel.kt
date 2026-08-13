package com.danilkinkin.buckwheat.data.categories

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Triggers the shared background category assignment pass and surfaces its running state so
// the analytics screen can show progress. The actual work runs in CategoryAssignmentScheduler
// on an application-scoped coroutine, so it continues even when this ViewModel is gone.
@HiltViewModel
class SpendCategoriesViewModel @Inject constructor(
    private val scheduler: CategoryAssignmentScheduler,
) : ViewModel() {
    val isCategorizing: LiveData<Boolean> = scheduler.isRunning.asLiveData()

    fun categorizeUncategorized() = scheduler.schedule()
}
