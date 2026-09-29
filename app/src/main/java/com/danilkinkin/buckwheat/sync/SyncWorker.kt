package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerFactoryEntryPoint {
    fun workerFactory(): HiltWorkerFactory
}

internal const val MAX_SYNC_ATTEMPTS = 5

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = engine.sync().toWorkerResult(runAttemptCount)
}

internal fun SyncOutcome.toWorkerResult(runAttemptCount: Int): ListenableWorker.Result = when (this) {
    is SyncOutcome.NotEnrolled -> ListenableWorker.Result.success()
    is SyncOutcome.Synced -> ListenableWorker.Result.success()
    is SyncOutcome.Failed ->
        if (runAttemptCount < MAX_SYNC_ATTEMPTS) {
            ListenableWorker.Result.retry()
        } else {
            ListenableWorker.Result.failure()
        }
}
