package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.sync.ConflictNotice
import com.danilkinkin.buckwheat.sync.SyncStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncConflictsViewModel @Inject constructor(
    private val syncStateStore: SyncStateStore,
) : ViewModel() {
    val conflicts: StateFlow<List<ConflictNotice>> = syncStateStore.conflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun dismiss() = viewModelScope.launch {
        syncStateStore.replaceConflicts(emptyList())
    }
}
