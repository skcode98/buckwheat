package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import com.danilkinkin.buckwheat.sync.SyncStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Twice the periodic interval, so a device that misses exactly one scheduled run is not yet called
 * out of date. Anything older than this is a real signal, because the next run is already due.
 */
const val SYNC_STALE_AFTER_MS: Long = 12L * 60L * 60L * 1000L

/**
 * Reads the persisted sync state for display only. The engine owns writing it; this exists so a
 * settings row can render a status without knowing anything about the sync engine's internals.
 */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val syncStateStore: SyncStateStore,
    sessionStore: FamilySessionStore,
) : ViewModel() {

    val enrolled: StateFlow<Boolean> = sessionStore.session()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val lastSyncedAt: StateFlow<Long> = syncStateStore.lastSyncedAt()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val lastError: StateFlow<String?> = syncStateStore.lastError()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun status(now: Long = System.currentTimeMillis()): SyncStatus =
        syncStatus(lastSyncedAt.value, lastError.value, now, SYNC_STALE_AFTER_MS)
}
