package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.di.FakeSavedTagDao
import com.danilkinkin.buckwheat.sync.SyncTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Every saved_tags write has to enqueue a pending mutation, otherwise the edit never reaches the
// server and an incoming pull silently reverts it.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TagsManagementViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var fixture: SyncMarkingFixture
    private lateinit var savedTagDao: FakeSavedTagDao
    private lateinit var marker: FakeSyncDirtyMarker

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = SyncMarkingFixture()
        savedTagDao = FakeSavedTagDao()
        marker = FakeSyncDirtyMarker()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TagsManagementViewModel(
        savedTagDao = savedTagDao,
        spendsRepository = fixture.spendsRepository,
        syncDirtyMarker = marker,
    )

    private suspend fun seed(
        id: String,
        name: String,
        familyId: String? = null,
        syncSeq: Long = 0L,
        version: Int = 1,
    ): SavedTag = SavedTag(
        id = id,
        name = name,
        familyId = familyId,
        syncSeq = syncSeq,
        version = version,
    ).also { savedTagDao.insert(it) }

    @Test
    fun `adding a tag marks the inserted row`() = runTest(dispatcher) {
        viewModel().addTag("  groceries  ")

        assertEquals(
            savedTagDao.getAllNow().single().id,
            marker.upserted(SyncTables.SAVED_TAGS).single(),
        )
    }

    @Test
    fun `adding a duplicate tag marks nothing`() = runTest(dispatcher) {
        seed("tag-1", "groceries")

        viewModel().addTag("groceries")

        assertTrue(marker.upserted(SyncTables.SAVED_TAGS).isEmpty())
    }

    // A rebuilt SavedTag(id = …) used to be @Update-d straight onto the row, which reset
    // family_id / sync_seq / version back to their defaults — the rename then pushed as a brand
    // new record and the server rejected it forever.
    @Test
    fun `renaming a tag marks the row and preserves its sync columns`() = runTest(dispatcher) {
        seed("tag-1", "groceries", familyId = "family-1", syncSeq = 4L, version = 7)
        val viewModel = viewModel()

        viewModel.updateTag("tag-1", "food")

        assertEquals(listOf("tag-1"), marker.upserted(SyncTables.SAVED_TAGS))
        val stored = savedTagDao.getAllNow().single()
        assertEquals("food", stored.name)
        assertEquals("family-1", stored.familyId)
        assertEquals(4L, stored.syncSeq)
        assertEquals(7, stored.version)
    }

    @Test
    fun `renaming onto another tag's name marks nothing`() = runTest(dispatcher) {
        seed("tag-1", "groceries")
        seed("tag-2", "food")

        viewModel().updateTag("tag-1", "food")

        assertTrue(marker.upserted(SyncTables.SAVED_TAGS).isEmpty())
        assertEquals("groceries", savedTagDao.getAllNow().first { it.id == "tag-1" }.name)
    }

    @Test
    fun `deleting a synced tag queues a tombstone with its family metadata`() = runTest(dispatcher) {
        seed("tag-1", "groceries", familyId = "family-1", syncSeq = 9L)

        viewModel().deleteTag("tag-1")

        val tombstone = marker.deleted(SyncTables.SAVED_TAGS).single()
        assertEquals("tag-1", tombstone.recordId)
        assertEquals("family-1", tombstone.familyId)
        assertEquals(9L, tombstone.syncSeq)
        assertTrue(savedTagDao.getAllNow().isEmpty())
    }

    @Test
    fun `deleting a never-synced tag queues no tombstone`() = runTest(dispatcher) {
        seed("tag-1", "groceries")

        viewModel().deleteTag("tag-1")

        assertTrue(marker.deleted(SyncTables.SAVED_TAGS).isEmpty())
    }

    @Test
    fun `deleting an unknown tag marks nothing`() = runTest(dispatcher) {
        viewModel().deleteTag("missing")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(marker.deletes.isEmpty())
    }
}