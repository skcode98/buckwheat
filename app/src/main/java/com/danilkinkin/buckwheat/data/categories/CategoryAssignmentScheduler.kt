package com.danilkinkin.buckwheat.data.categories

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// Runs category assignment (offline keywords, then the AI model) on an application-scoped
// background coroutine so the work keeps running even after the screen or activity that
// triggered it is gone — the app never blocks while waiting on the AI provider.
//
// schedule() is cheap and safe to call from any add/import path: concurrent calls are
// coalesced (no parallel runs), and a dirty flag makes a running pass re-scan once more
// when new uncategorized rows appeared mid-run, so nothing is left unassigned.
@Singleton
class CategoryAssignmentScheduler @Inject constructor(
    private val categoryAssigner: CategoryAssigner,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    fun schedule() {
        dirty.set(true)
        if (!running.compareAndSet(false, true)) return
        _isRunning.value = true
        scope.launch {
            try {
                do {
                    dirty.set(false)
                    categoryAssigner.assignToUncategorized()
                } while (dirty.get())
            } catch (e: Exception) {
                Log.d("CategoryAssignment", "background categorization failed", e)
            } finally {
                running.set(false)
                if (dirty.get()) {
                    // Work landed while we were finishing up — run once more instead of
                    // leaving the row uncategorized until the next trigger.
                    schedule()
                } else {
                    _isRunning.value = false
                }
            }
        }
    }
}
