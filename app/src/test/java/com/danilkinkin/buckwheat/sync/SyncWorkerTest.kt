package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncWorkerTest {
    private fun worker(engine: SyncEngine, runAttemptCount: Int = 0): SyncWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parameters = TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(
                object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ): ListenableWorker = SyncWorker(appContext, workerParameters, engine)
                }
            )
            .setRunAttemptCount(runAttemptCount)
            .build()
        return parameters
    }

    private fun engine(
        token: String? = "token-1",
        response: SyncResponse = SyncResponse(cursor = 7, accepted = emptyList(), records = emptyList(), conflicts = emptyList()),
        failure: IOException? = null,
        applied: MutableList<SyncApply> = mutableListOf(),
    ): SyncEngine = SyncEngine(
        client = object : SyncClient {
            override suspend fun sync(token: String, request: SyncRequest): SyncResponse {
                failure?.let { throw it }
                return response
            }
        },
        database = object : SyncDatabase {
            override suspend fun readCursor(): Long = 3
            override suspend fun dirtyRecords(): List<LocalRecord> = emptyList()
            override suspend fun loadRecords(): List<LocalRecord> = emptyList()
            override suspend fun apply(apply: SyncApply) {
                applied += apply
            }

            override suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long) = Unit
            override suspend fun reset() = Unit
        },
        sessionProvider = {
            token?.let {
                FamilySession(
                    baseUrl = "https://sync.example.com",
                    token = it,
                    familyId = "family-1",
                    memberId = "member-1",
                )
            }
        },
    )

    @Test
    fun notEnrolledIsASuccess() = runTest {
        val result = worker(engine(token = null)).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun aSuccessfulSyncIsASuccess() = runTest {
        val result = worker(engine()).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun aSuccessfulSyncAppliesTheResponse() = runTest {
        val applied = mutableListOf<SyncApply>()
        worker(
            engine(
                response = SyncResponse(
                    cursor = 11,
                    accepted = emptyList(),
                    records = emptyList(),
                    conflicts = listOf(ConflictNotice("transactions", "t-1", "member-2")),
                ),
                applied = applied,
            )
        ).doWork()
        assertEquals(1, applied.size)
        assertEquals(11L, applied.single().cursor)
        assertEquals(listOf(ConflictNotice("transactions", "t-1", "member-2")), applied.single().conflicts)
    }

    @Test
    fun aFailureIsRetriedOnTheFirstAttempt() = runTest {
        val result = worker(engine(failure = IOException("boom")), runAttemptCount = 0).doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun aFailureIsRetriedOnLaterAttempts() = runTest {
        val result = worker(engine(failure = IOException("boom")), runAttemptCount = 3).doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun aFailureStopsRetryingAfterTheLastAttempt() = runTest {
        val result = worker(engine(failure = IOException("boom")), runAttemptCount = MAX_SYNC_ATTEMPTS).doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }
}
