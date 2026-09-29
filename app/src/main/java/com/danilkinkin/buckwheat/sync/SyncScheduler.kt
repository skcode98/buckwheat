package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
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

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_NAME,
            ExistingWorkPolicy.KEEP,
            oneShotRequest(),
        )
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
