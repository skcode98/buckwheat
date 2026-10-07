package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.di.FakeSessionStore
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.sync.FamilyApi
import com.danilkinkin.buckwheat.sync.FamilyApiFactory
import com.danilkinkin.buckwheat.sync.FamilyCredentials
import com.danilkinkin.buckwheat.sync.FamilyMember
import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.sync.FamilySyncCoordinator
import com.danilkinkin.buckwheat.sync.FamilySyncRegistrar
import com.danilkinkin.buckwheat.sync.InMemoryFamilyMembersCache
import com.danilkinkin.buckwheat.sync.LocalRecord
import com.danilkinkin.buckwheat.sync.SyncApply
import com.danilkinkin.buckwheat.sync.SyncClock
import com.danilkinkin.buckwheat.sync.SyncDatabase
import com.danilkinkin.buckwheat.sync.WhoAmI
import com.danilkinkin.buckwheat.sync.syncBaseUrlStoreKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TIMEOUT_MS = 10_000L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FamilySyncServerUrlTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var sessionStore: FakeSessionStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        sessionStore = FakeSessionStore()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { context.settingsDataStore.edit { it.clear() } }
    }

    private fun connectedSession(): FamilySession = FamilySession(
        baseUrl = "https://sync.example",
        token = "token-1",
        familyId = "family-1",
        memberId = "member-1",
        joinCode = "",
    )

    private fun viewModel(): FamilySyncViewModel {
        val coordinator = FamilySyncCoordinator(
            context = context,
            registrar = FamilySyncRegistrar(sessionStore, StaticFamilyApi, InMemoryFamilyMembersCache()),
            database = NoSyncDatabase,
            clock = SyncClock { 700L },
        )
        return FamilySyncViewModel(coordinator, sessionStore, context)
    }

    @Test
    fun aValidationMessageSurvivesAMissingCollector() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onServerUrlChange("https://sync.example")

        viewModel.enrol()
        advanceUntilIdle()

        val messages = mutableListOf<String>()
        backgroundScope.launch { viewModel.messages.collect { messages += it } }
        advanceUntilIdle()

        assertEquals(listOf(context.getString(R.string.family_sync_error_name)), messages)
    }

    @Test
    fun theServerUrlIsPrefilledFromTheStoredKeyAfterLeaving() {
        runBlocking { context.settingsDataStore.edit { it[syncBaseUrlStoreKey] = "https://left.example" } }

        val viewModel = viewModel()

        val url = runBlocking {
            withTimeout(TIMEOUT_MS) { viewModel.serverUrl.first { it.isNotBlank() } }
        }

        assertEquals("https://left.example", url)
    }

    @Test
    fun theServerUrlIsStoredWhileTyping() = runTest(dispatcher) {
        sessionStore = FakeSessionStore(connectedSession())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChange("https://moved.example")
        advanceUntilIdle()

        assertEquals("https://moved.example", sessionStore.session?.baseUrl)
    }

    private object StaticFamilyApi : FamilyApiFactory {
        override fun create(baseUrl: String): FamilyApi = object : FamilyApi {
            override suspend fun createFamily(displayName: String) =
                FamilyCredentials(token = "token-1", familyId = "family-1", memberId = "member-1", joinCode = "")

            override suspend fun joinFamily(code: String, displayName: String) =
                FamilyCredentials(token = "token-1", familyId = "family-1", memberId = "member-1", joinCode = "")

            override suspend fun whoami(token: String) =
                WhoAmI(memberId = "member-1", familyId = "family-1", displayName = "Ada")

            override suspend fun members(token: String): List<FamilyMember> = emptyList()
        }
    }

    private object NoSyncDatabase : SyncDatabase {
        override suspend fun readCursor(): Long = 0L

        override suspend fun dirtyRecords(): List<LocalRecord> = emptyList()

        override suspend fun loadRecords(): List<LocalRecord> = emptyList()

        override suspend fun apply(apply: SyncApply) = Unit

        override suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long) = Unit

        override suspend fun reset() = Unit
    }
}