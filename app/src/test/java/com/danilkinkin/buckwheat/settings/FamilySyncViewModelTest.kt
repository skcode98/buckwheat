package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.danilkinkin.buckwheat.di.FakeSessionStore
import com.danilkinkin.buckwheat.sync.FamilyApi
import com.danilkinkin.buckwheat.sync.FamilyApiFactory
import com.danilkinkin.buckwheat.sync.FamilyCredentials
import com.danilkinkin.buckwheat.sync.FamilyMember
import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.sync.FamilySyncCoordinator
import com.danilkinkin.buckwheat.sync.FamilySyncRegistrar
import com.danilkinkin.buckwheat.sync.InMemoryFamilyMembersCache
import com.danilkinkin.buckwheat.sync.LocalRecord
import com.danilkinkin.buckwheat.sync.MintedInvite
import com.danilkinkin.buckwheat.sync.SyncApply
import com.danilkinkin.buckwheat.sync.SyncClock
import com.danilkinkin.buckwheat.sync.SyncDatabase
import com.danilkinkin.buckwheat.sync.WhoAmI
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FamilySyncViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var sessionStore: FakeSessionStore
    private lateinit var api: RecordingFamilyApi
    private lateinit var database: RecordingSyncDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        WorkManager.getInstance(context)
        sessionStore = FakeSessionStore()
        api = RecordingFamilyApi()
        database = RecordingSyncDatabase()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): FamilySyncViewModel {
        // One cache shared by the registrar and the ViewModel, because in production they resolve the
        // same @Singleton. Two instances would let the registrar write a roster the ViewModel can never
        // observe, which would make the roster look permanently empty and pass a test that means nothing.
        val cache = InMemoryFamilyMembersCache()
        val coordinator = FamilySyncCoordinator(
            context = context,
            registrar = FamilySyncRegistrar(sessionStore, api, cache),
            database = database,
            clock = SyncClock { 700L },
        )
        return FamilySyncViewModel(
            coordinator,
            sessionStore,
            cache,
            context,
        )
    }

    private fun connectedStore(): FakeSessionStore = FakeSessionStore(
        FamilySession(
            baseUrl = "https://sync.example",
            token = "token-1",
            familyId = "family-1",
            memberId = "member-1",
        )
    )

    @Test
    fun theServerUrlIsPrefilledFromTheStore() = runTest(dispatcher) {
        sessionStore = connectedStore()

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals("https://sync.example", viewModel.serverUrl.value)
    }

    @Test
    fun theServerUrlIsBlankWithoutAStoredSession() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals("", viewModel.serverUrl.value)
        assertNull(viewModel.session.value)
    }

    @Test
    fun aUrlWithoutASchemeIsRejected() = runTest(dispatcher) {
        val viewModel = viewModel()
        val messages = watch(viewModel)
        viewModel.onServerUrlChange("sync.example")
        viewModel.onDisplayNameChange("Ada")

        viewModel.enrol()
        advanceUntilIdle()

        assertEquals(listOf(context.getString(com.danilkinkin.buckwheat.R.string.family_sync_error_url)), messages)
        assertTrue(api.created.isEmpty())
    }

    @Test
    fun aBlankNameIsRejected() = runTest(dispatcher) {
        val viewModel = viewModel()
        val messages = watch(viewModel)
        viewModel.onServerUrlChange("https://sync.example")

        viewModel.enrol()
        advanceUntilIdle()

        assertEquals(listOf(context.getString(com.danilkinkin.buckwheat.R.string.family_sync_error_name)), messages)
        assertTrue(api.created.isEmpty())
    }

    @Test
    fun aBlankInviteCodeIsRejected() = runTest(dispatcher) {
        val viewModel = viewModel()
        val messages = watch(viewModel)
        viewModel.onServerUrlChange("https://sync.example")
        viewModel.onDisplayNameChange("Grace")

        viewModel.join()
        advanceUntilIdle()

        assertEquals(listOf(context.getString(com.danilkinkin.buckwheat.R.string.family_sync_error_code)), messages)
        assertTrue(api.joined.isEmpty())
    }

    @Test
    fun enrollingStoresTheSession() = runTest(dispatcher) {
        val viewModel = viewModel()
        val messages = watch(viewModel)
        viewModel.onServerUrlChange("https://sync.example")
        viewModel.onDisplayNameChange("Ada")

        viewModel.enrol()
        advanceUntilIdle()

        assertEquals("family-1", viewModel.session.value?.familyId)
        assertEquals("member-1", viewModel.session.value?.memberId)
        assertEquals(listOf("Ada"), api.created)
        assertEquals(listOf(context.getString(com.danilkinkin.buckwheat.R.string.family_sync_created)), messages)
    }

    @Test
    fun joiningStoresTheSessionAndClearsTheCode() = runTest(dispatcher) {
        val viewModel = viewModel()
        val messages = watch(viewModel)
        viewModel.onServerUrlChange("https://sync.example")
        viewModel.onDisplayNameChange("Grace")
        viewModel.onInviteCodeChange("  CODE-1  ")

        viewModel.join()
        advanceUntilIdle()

        assertEquals(listOf("CODE-1" to "Grace"), api.joined)
        assertEquals("family-1", viewModel.session.value?.familyId)
        assertEquals("", viewModel.inviteCode.value)
        assertEquals(listOf(context.getString(com.danilkinkin.buckwheat.R.string.family_sync_joined)), messages)
    }

    @Test
    fun mintingAnInviteStoresTheCode() = runTest(dispatcher) {
        sessionStore = connectedStore()
        val viewModel = viewModel()
        val messages = watch(viewModel)
        advanceUntilIdle()

        viewModel.mintInvite()
        advanceUntilIdle()

        assertEquals("CODE-1", viewModel.mintedInvite.value)
        assertTrue(messages.isEmpty())

        viewModel.clearMintedInvite()
        assertNull(viewModel.mintedInvite.value)
    }

    @Test
    fun mintingAnInviteWithoutASessionIsRefused() = runTest(dispatcher) {
        val viewModel = viewModel()
        val messages = watch(viewModel)
        advanceUntilIdle()

        viewModel.mintInvite()
        advanceUntilIdle()

        assertNull(viewModel.mintedInvite.value)
        assertEquals(
            listOf(context.getString(com.danilkinkin.buckwheat.R.string.family_sync_error_no_session)),
            messages,
        )
    }

    @Test
    fun signingOutForgetsTheFamily() = runTest(dispatcher) {
        sessionStore = connectedStore()
        val viewModel = viewModel()
        val messages = watch(viewModel)
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        assertTrue(sessionStore.cleared)
        assertNull(sessionStore.session)
        assertEquals(listOf(context.getString(com.danilkinkin.buckwheat.R.string.family_sync_signed_out)), messages)
    }

    @Test
    fun aServerFailureIsShownToTheUser() = runTest(dispatcher) {
        api.failure = IOException("family HTTP 404 family_not_found")
        val viewModel = viewModel()
        val messages = watch(viewModel)
        viewModel.onServerUrlChange("https://sync.example")
        viewModel.onDisplayNameChange("Ada")

        viewModel.enrol()
        advanceUntilIdle()

        assertEquals(listOf("family HTTP 404 family_not_found"), messages)
        assertNull(viewModel.session.value)
    }

    @Test
    fun aFailureWithoutAMessageFallsBackToAGenericOne() = runTest(dispatcher) {
        api.failure = IOException()
        val viewModel = viewModel()
        val messages = watch(viewModel)
        viewModel.onServerUrlChange("https://sync.example")
        viewModel.onDisplayNameChange("Ada")

        viewModel.enrol()
        advanceUntilIdle()

        assertEquals(listOf(context.getString(com.danilkinkin.buckwheat.R.string.family_sync_error_generic)), messages)
    }

    @Test
    fun theBusyFlagIsRaisedWhileEnrolling() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val viewModel = viewModel()
        watch(viewModel)
        viewModel.onServerUrlChange("https://sync.example")
        viewModel.onDisplayNameChange("Ada")

        viewModel.enrol()
        runCurrent()

        assertTrue(viewModel.busy.value)
        assertTrue(api.created.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(false, viewModel.busy.value)
        assertEquals(listOf("Ada"), api.created)
    }

    private fun TestScope.watch(viewModel: FamilySyncViewModel): List<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch { viewModel.session.collect() }
        backgroundScope.launch { viewModel.messages.collect { messages += it } }
        runCurrent()
        return messages
    }

    private class RecordingFamilyApi : FamilyApiFactory {
        val created = mutableListOf<String>()
        val joined = mutableListOf<Pair<String, String>>()
        var failure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null

        override fun create(baseUrl: String): FamilyApi = object : FamilyApi {
            override suspend fun createFamily(displayName: String): FamilyCredentials {
                failure?.let { throw it }
                gate?.await()
                created.add(displayName)
                return FamilyCredentials(token = "token-1", familyId = "family-1", memberId = "member-1")
            }

            override suspend fun joinFamily(code: String, displayName: String): FamilyCredentials {
                failure?.let { throw it }
                joined.add(code to displayName)
                return FamilyCredentials(token = "token-1", familyId = "family-1", memberId = "member-1")
            }

            override suspend fun whoami(token: String) =
                WhoAmI(memberId = "member-1", familyId = "family-1", displayName = "Ada")

            override suspend fun mintInvite(token: String) =
                MintedInvite(code = "CODE-1", expiresAt = "2030-01-01T00:00:00Z")

            override suspend fun members(token: String): List<FamilyMember> {
                failure?.let { throw it }
                return listOf(
                    FamilyMember(
                        id = "member-1",
                        displayName = "Ada",
                        isOwner = true,
                        joinedAt = "2026-01-01T00:00:00Z",
                    )
                )
            }
        }
    }

    private class RecordingSyncDatabase : SyncDatabase {
        override suspend fun readCursor(): Long = 0L

        override suspend fun dirtyRecords(): List<LocalRecord> = emptyList()

        override suspend fun loadRecords(): List<LocalRecord> = emptyList()

        override suspend fun apply(apply: SyncApply) = Unit

        override suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long) = Unit

        override suspend fun reset() = Unit
    }
}
