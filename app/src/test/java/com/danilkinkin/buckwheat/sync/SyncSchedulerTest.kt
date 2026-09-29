package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val configuration = androidx.work.Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun schedulingIsIdempotentAndKeepsTheOriginalRequest() = runBlockingWork {
        SyncScheduler.schedule(context)
        SyncScheduler.schedule(context)
        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_NAME).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun thePeriodicRequestNeedsANetwork() {
        assertEquals(
            NetworkType.CONNECTED,
            SyncScheduler.periodicRequest().workSpec.constraints.requiredNetworkType,
        )
    }

    @Test
    fun theOneShotRequestNeedsANetwork() {
        assertEquals(
            NetworkType.CONNECTED,
            SyncScheduler.oneShotRequest().workSpec.constraints.requiredNetworkType,
        )
    }

    @Test
    fun thePeriodicRequestRepeats() {
        val request = SyncScheduler.periodicRequest()
        assertTrue(request.workSpec.intervalDuration > 0L)
        assertEquals(
            TimeUnit.HOURS.toMillis(SyncScheduler.PERIODIC_INTERVAL_HOURS),
            request.workSpec.intervalDuration,
        )
    }

    @Test
    fun syncingNowEnqueuesASingleOneShotJob() = runBlockingWork {
        SyncScheduler.syncNow(context)
        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_NAME).get()
        assertEquals(1, infos.size)
        assertTrue(infos.single().state == WorkInfo.State.ENQUEUED)
    }

    @Test
    fun syncingNowDoesNotDuplicateWork() = runBlockingWork {
        SyncScheduler.syncNow(context)
        SyncScheduler.syncNow(context)
        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_NAME).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun cancellingRemovesTheScheduledWork() = runBlockingWork {
        SyncScheduler.schedule(context)
        SyncScheduler.syncNow(context)
        SyncScheduler.cancel(context)
        assertTrue(
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_NAME).get().all {
                it.state == WorkInfo.State.CANCELLED
            }
        )
        assertTrue(
            workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_NAME).get().all {
                it.state == WorkInfo.State.CANCELLED
            }
        )
    }

    private fun runBlockingWork(block: () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
