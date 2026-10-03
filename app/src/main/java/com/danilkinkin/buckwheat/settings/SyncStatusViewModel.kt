package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import com.danilkinkin.buckwheat.sync.SyncScheduler
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
 *
 * Everything here is a `StateFlow` that something else actually owns. The engine writes the persisted
 * state and WorkManager owns the run's progress; this class observes both rather than keeping a
 * parallel copy that can disagree with them.
 *
 * An earlier version kept its own `syncing` flag, set it on the tap and cleared it from a coroutine
 * polling for a change in the stored error string. It was wrong in three ways at once: it missed a
 * repeated identical failure because the string had not changed, it left the word "Syncing…" on screen
 * forever whenever a slow run outlasted its timeout, and it reported "Sync finished" next to a red
 * FAILED chip because nothing ever cleared it. There is nothing left to reconcile.
 */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val syncStateStore: SyncStateStore,
    private val pendingMutationDao: PendingMutationDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
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

    /**
     * Whether a sync is queued or running, straight from WorkManager.
     *
     * Both the one-shot and the periodic work count. Checking only the one-shot would let a tap
     * during a scheduled run start a second worker, which is the contention the dedup exists to avoid.
     *
     * Observed rather than tracked, so it cannot get stuck: if the process dies mid-sync this reads
     * the same truth on the next launch instead of inheriting a stale flag.
     */
    val syncing: StateFlow<Boolean> =
        SyncScheduler.runningState(appContext)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun refreshPendingCount() {
        viewModelScope.launch {
            val before = runCatching { pendingMutationDao.count() }.getOrDefault(0)
            _pendingCount.value = before
        }
    }

    /**
     * Enqueues a sync and refreshes the queue afterwards.
     *
     * The refresh is launched rather than awaited because the interesting moment is *after* the worker
     * drains the queue; reading it at tap time reports the count from before the sync, which is the
     * same stale-number bug as reading a snapshot of anything else.
     */
    fun syncNow() {
        viewModelScope.launch {
            SyncScheduler.syncNow(appContext)
            // Poll once the run has actually finished, so the count means something.
            viewModelScope.launch {
                while (syncing.value) kotlinx.coroutines.delay(SYNC_SETTLE_POLL_MS)
                refreshPendingCount()
            }
        }
    }

    companion object {
        /**
         * How often to re-read the queue while a sync is in flight. Short enough to be nearly
         * immediate, long enough not to hammer the database. Unlike the timeout this replaces there is
         * no upper bound and nothing to flake: it simply keeps reading until WorkManager says the
         * work is done.
         */
        const val SYNC_SETTLE_POLL_MS: Long = 500L
    }
}