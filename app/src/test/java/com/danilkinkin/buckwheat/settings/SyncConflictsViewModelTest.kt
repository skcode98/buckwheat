package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.sync.ConflictNotice
import com.danilkinkin.buckwheat.sync.SyncStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSyncStateStore : SyncStateStore {
        private val state = MutableStateFlow<List<ConflictNotice>>(emptyList())
        val replaced = mutableListOf<List<ConflictNotice>>()
        var storedCursor = 0L
        var storedLastSyncedAt = 0L
        var storedLastError: String? = null
        var clearCount = 0

        override fun cursor(): Flow<Long> = MutableStateFlow(storedCursor)

        override suspend fun readCursor(): Long = storedCursor

        override suspend fun writeCursor(cursor: Long) {
            storedCursor = cursor
        }

        override fun conflicts(): Flow<List<ConflictNotice>> = state

        override suspend fun readConflicts(): List<ConflictNotice> = state.value

        override suspend fun replaceConflicts(conflicts: List<ConflictNotice>) {
            replaced += conflicts
            state.value = conflicts
        }

        override fun lastSyncedAt(): Flow<Long> = MutableStateFlow(storedLastSyncedAt)

        override fun lastError(): Flow<String?> = MutableStateFlow(storedLastError)

        override suspend fun markSynced(at: Long) {
            storedLastSyncedAt = at
            storedLastError = null
        }

        override suspend fun markFailed(reason: String?) {
            storedLastError = reason
        }

        override suspend fun clear() {
            clearCount++
        }

        override suspend fun isFamilyReHome22Done(): Boolean = false

        override suspend fun markFamilyReHome22Done() = Unit
    }

    private suspend fun TestScope.activeConflicts(
        viewModel: SyncConflictsViewModel,
    ): List<ConflictNotice> {
        val job = launch { viewModel.conflicts.collect() }
        testScheduler.advanceUntilIdle()
        return viewModel.conflicts.value.also { job.cancel() }
    }

    @Test
    fun `starts empty when nothing stored`() = runTest(dispatcher) {
        val viewModel = SyncConflictsViewModel(FakeSyncStateStore())

        assertEquals(emptyList<ConflictNotice>(), activeConflicts(viewModel))
    }

    @Test
    fun `exposes stored conflicts in stored order`() = runTest(dispatcher) {
        val store = FakeSyncStateStore()
        val stored = listOf(
            ConflictNotice("transactions", "transaction-1", "member-2"),
            ConflictNotice("saved_tags", "tag-1", "member-3"),
        )
        store.replaceConflicts(stored)
        val viewModel = SyncConflictsViewModel(store)

        assertEquals(stored, activeConflicts(viewModel))
    }

    @Test
    fun `dismiss updates the exposed conflicts`() = runTest(dispatcher) {
        val store = FakeSyncStateStore()
        store.replaceConflicts(listOf(ConflictNotice("budget_periods", "period-1", "member-2")))
        val viewModel = SyncConflictsViewModel(store)
        assertEquals(1, activeConflicts(viewModel).size)

        viewModel.dismiss()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<ConflictNotice>(), activeConflicts(viewModel))
    }

    @Test
    fun `dismiss clears conflicts and keeps the cursor`() = runTest(dispatcher) {
        val store = FakeSyncStateStore()
        store.storedCursor = 42L
        store.replaceConflicts(listOf(ConflictNotice("savings_goals", "goal-1", "member-2")))
        val viewModel = SyncConflictsViewModel(store)

        viewModel.dismiss()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<ConflictNotice>(), store.readConflicts())
        assertEquals(emptyList<ConflictNotice>(), store.replaced.last())
        assertEquals(0, store.clearCount)
        assertEquals(42L, store.storedCursor)
    }
}
