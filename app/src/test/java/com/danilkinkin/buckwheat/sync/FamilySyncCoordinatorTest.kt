package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FamilySyncCoordinatorTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val sessionStore = com.danilkinkin.buckwheat.di.FakeSessionStore()
    private val database = RecordingSyncDatabase()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            androidx.work.Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun coordinator(now: Long = 700L) = FamilySyncCoordinator(
        context = context,
        registrar = FamilySyncRegistrar(sessionStore, StaticFamilyApiFactory, InMemoryFamilyMembersCache()),
        database = database,
        clock = SyncClock { now },
    )

    @Test
    fun enrollingReturnsTheSession() = runTest {
        val session = coordinator().enrol("https://sync.example", "Ada")

        assertEquals("https://sync.example", session.baseUrl)
        assertEquals("family-1", session.familyId)
        assertEquals("member-1", session.memberId)
    }

    @Test
    fun enrollingPushesTheExistingLocalRows() = runTest {
        coordinator().enrol("https://sync.example", "Ada")

        assertEquals(1, database.enrolments.size)
        val enrolment = database.enrolments.single()
        assertEquals("member-1", enrolment.first)
        assertEquals("family-1", enrolment.second)
        assertEquals(700L, enrolment.third)
    }

    @Test
    fun enrollingSchedulesThePeriodicSync() = runBlocking {
        coordinator().enrol("https://sync.example", "Ada")

        val info = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_NAME).get()
        assertEquals(1, info.size)
        assertEquals(WorkInfo.State.ENQUEUED, info.single().state)
    }

    @Test
    fun enrollingTriggersAnImmediateSync() = runBlocking {
        coordinator().enrol("https://sync.example", "Ada")

        val info = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_NAME).get()
        assertEquals(1, info.size)
    }

    @Test
    fun syncNowSchedulesAnImmediateSync() = runBlocking {
        coordinator().syncNow()

        val info = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_NAME).get()
        assertEquals(1, info.size)
    }

    @Test
    fun joiningPushesTheExistingLocalRows() = runTest {
        val session = coordinator().join("https://sync.example", "CODE-1", "Grace")

        assertEquals("family-1", session.familyId)
        assertEquals(1, database.enrolments.size)
    }

    @Test
    fun signingOutCancelsTheScheduledWork() = runBlocking {
        coordinator().enrol("https://sync.example", "Ada")
        coordinator().signOut()

        val periodic = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_NAME).get()
        val oneShot = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_SHOT_NAME).get()
        assertTrue(periodic.all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(oneShot.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun signingOutClearsTheSession() = runTest {
        coordinator().enrol("https://sync.example", "Ada")
        coordinator().signOut()

        assertTrue(sessionStore.cleared)
    }

    @Test
    fun signingOutDoesNotEnrolAnything() = runTest {
        coordinator().signOut()

        assertTrue(database.enrolments.isEmpty())
    }

    @Test
    fun signingOutResetsTheSyncState() = runTest {
        coordinator().enrol("https://sync.example", "Ada")
        coordinator().signOut()

        assertEquals(1, database.resets)
    }

    @Test
    fun schedulingStillRequiresANetwork() {
        assertEquals(NetworkType.CONNECTED, SyncScheduler.periodicRequest().workSpec.constraints.requiredNetworkType)
    }

    private object StaticFamilyApiFactory : FamilyApiFactory {
        override fun create(baseUrl: String): FamilyApi = object : FamilyApi {
            override suspend fun createFamily(displayName: String) =
                FamilyCredentials(token = "token-1", familyId = "family-1", memberId = "member-1", joinCode = "")

            override suspend fun joinFamily(code: String, displayName: String) =
                FamilyCredentials(token = "token-1", familyId = "family-1", memberId = "member-1", joinCode = "")

            override suspend fun whoami(token: String): WhoAmI =
                WhoAmI(memberId = "member-1", familyId = "family-1", displayName = "Ada")

            override suspend fun members(token: String): List<FamilyMember> = emptyList()
        }
    }

    private class RecordingSyncDatabase : SyncDatabase {
        val enrolments = mutableListOf<Triple<String, String, Long>>()
        var resets = 0

        override suspend fun readCursor(): Long = 0L

        override suspend fun dirtyRecords(): List<LocalRecord> = emptyList()

        override suspend fun loadRecords(): List<LocalRecord> = emptyList()

        override suspend fun apply(apply: SyncApply) = Unit

        override suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long) {
            enrolments.add(Triple(memberId, familyId, enrolledAt))
        }

        override suspend fun reset() {
            resets++
        }
    }
}
