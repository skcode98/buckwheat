package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.lifecycle.asFlow
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val PERIODIC_NAME = "family-sync-periodic"
    const val ONE_SHOT_NAME = "family-sync-now"

    const val PERIODIC_INTERVAL_HOURS = 6L

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest(),
        )
    }

    /**
 * Enqueues a one-shot sync.
 *
 * `KEEP` drops the request when work is already queued, which is correct -- two syncs at once would
 * contend on the same rows -- but the operation reports success even when it drops the request, so its
 * return value cannot be used to tell a person their tap did nothing. Hence no return value at all:
 * progress is read from [runningState] instead, which is the same truth the button's label comes from.
 */
fun syncNow(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        ONE_SHOT_NAME,
        ExistingWorkPolicy.KEEP,
        oneShotRequest(),
    )
}

/**
 * Whether any sync -- one-shot or scheduled -- is queued or running.
 *
 * Both names are checked. Looking only at the one-shot would let a tap during a periodic run start a
 * second worker, which is exactly the contention the dedup exists to prevent.
 *
 * Observed from WorkManager rather than tracked locally, so it cannot disagree with what is actually
 * running and cannot get stuck true after a crash.
 */
fun runningState(context: Context): Flow<Boolean> =
    workManagerFlows(context)
        .map { flows -> flows.any { infos -> infos.any { !it.state.isFinished } } }
        .distinctUntilChanged()

/**
 * Live work info for both sync names.
 *
 * `getWorkInfosForUniqueWorkLiveData` rather than the blocking `getWorkInfosForUniqueWork(...).get()`:
 * that call waits on WorkManager's task executor, and from a click handler it waits on the main thread
 * for a query that a running worker may hold a write lock on.
 */
private fun workManagerFlows(context: Context): Flow<List<List<WorkInfo>>> {
    val workManager: WorkManager = WorkManager.getInstance(context)
    return combine<List<WorkInfo>, List<WorkInfo>, List<List<WorkInfo>>>(
        workManager.getWorkInfosForUniqueWorkLiveData(ONE_SHOT_NAME).asFlow(),
        workManager.getWorkInfosForUniqueWorkLiveData(PERIODIC_NAME).asFlow(),
    ) { oneShot, periodic -> listOf(oneShot, periodic) }
}

internal fun periodicRequest(): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
        .setConstraints(networkRequired())
        .build()

    internal fun oneShotRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkRequired())
            .build()

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_NAME)
        workManager.cancelUniqueWork(ONE_SHOT_NAME)
    }

    private fun networkRequired() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
