package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import com.danilkinkin.buckwheat.sync.SyncStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val pendingMutationDao: PendingMutationDao,
    sessionStore: FamilySessionStore,
) : ViewModel() {

    val enrolled: StateFlow<Boolean> = sessionStore.session()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val lastSyncedAt: StateFlow<Long> = syncStateStore.lastSyncedAt()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val lastError: StateFlow<String?> = syncStateStore.lastError()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _pendingCount = MutableStateFlow(0)

    /**
     * How many local writes are still queued. Non-zero while offline, and it is the honest answer to
     * "is my spending safe yet?", which a last-synced timestamp alone cannot give: a device can show
     * a green tick from yesterday while holding unsent writes from today.
     */
    val pendingCount: StateFlow<Int> = _pendingCount

    fun refreshPendingCount() {
        viewModelScope.launch {
            _pendingCount.value = runCatching { pendingMutationDao.count() }.getOrDefault(0)
        }
    }

    fun status(now: Long = System.currentTimeMillis()): SyncStatus =
        syncStatus(lastSyncedAt.value, lastError.value, now, SYNC_STALE_AFTER_MS)
}
