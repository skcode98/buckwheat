package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.di.FakeSavedCategoryDao
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

// Every saved_categories write has to enqueue a pending mutation, otherwise the edit never
// reaches the server and an incoming pull silently reverts it.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoriesManagementViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var fixture: SyncMarkingFixture
    private lateinit var savedCategoryDao: FakeSavedCategoryDao
    private lateinit var marker: FakeSyncDirtyMarker

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = SyncMarkingFixture()
        savedCategoryDao = FakeSavedCategoryDao()
        marker = FakeSyncDirtyMarker()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = CategoriesManagementViewModel(
        savedCategoryDao = savedCategoryDao,
        spendsRepository = fixture.spendsRepository,
        syncDirtyMarker = marker,
    )

    private suspend fun seed(
        id: String,
        name: String,
        emoji: String = "",
        familyId: String? = null,
        syncSeq: Long = 0L,
        version: Int = 1,
    ): SavedCategory = SavedCategory(
        id = id,
        name = name,
        emoji = emoji,
        familyId = familyId,
        syncSeq = syncSeq,
        version = version,
    ).also { savedCategoryDao.insert(it) }

    @Test
    fun `adding a category marks the inserted row`() = runTest(dispatcher) {
        viewModel().addCategory("  Pets  ", "🐶")

        val stored = savedCategoryDao.getAllNow().single()
        assertEquals(listOf(stored.id), marker.upserted(SyncTables.SAVED_CATEGORIES))
        assertEquals("Pets", stored.name)
        assertEquals("🐶", stored.emoji)
    }

    @Test
    fun `adding a duplicate category marks nothing`() = runTest(dispatcher) {
        seed("cat-1", "Pets")

        viewModel().addCategory("Pets")

        assertTrue(marker.upserted(SyncTables.SAVED_CATEGORIES).isEmpty())
    }

    @Test
    fun `adding a built-in category name marks nothing`() = runTest(dispatcher) {
        viewModel().addCategory("FOOD")

        assertTrue(marker.upserted(SyncTables.SAVED_CATEGORIES).isEmpty())
        assertTrue(savedCategoryDao.getAllNow().isEmpty())
    }

    // A rebuilt SavedCategory(id = …) used to be @Update-d straight onto the row, which reset
    // family_id / sync_seq / version back to their defaults — the rename then pushed as a brand
    // new record and the server rejected it forever.
    @Test
    fun `renaming a category marks the row and preserves its sync columns`() = runTest(dispatcher) {
        seed("cat-1", "Pets", emoji = "🐶", familyId = "family-1", syncSeq = 3L, version = 9)

        viewModel().updateCategory("cat-1", "Animals", "🐾")

        assertEquals(listOf("cat-1"), marker.upserted(SyncTables.SAVED_CATEGORIES))
        val stored = savedCategoryDao.getAllNow().single()
        assertEquals("Animals", stored.name)
        assertEquals("🐾", stored.emoji)
        assertEquals("family-1", stored.familyId)
        assertEquals(3L, stored.syncSeq)
        assertEquals(9, stored.version)
    }

    @Test
    fun `renaming onto another category's name marks nothing`() = runTest(dispatcher) {
        seed("cat-1", "Pets")
        seed("cat-2", "Animals")

        viewModel().updateCategory("cat-1", "Animals")

        assertTrue(marker.upserted(SyncTables.SAVED_CATEGORIES).isEmpty())
        assertEquals("Pets", savedCategoryDao.getAllNow().first { it.id == "cat-1" }.name)
    }

    @Test
    fun `deleting a synced category queues a tombstone with its family metadata`() = runTest(dispatcher) {
        seed("cat-1", "Pets", familyId = "family-1", syncSeq = 11L)

        viewModel().deleteCategory("cat-1")

        val tombstone = marker.deleted(SyncTables.SAVED_CATEGORIES).single()
        assertEquals("cat-1", tombstone.recordId)
        assertEquals("family-1", tombstone.familyId)
        assertEquals(11L, tombstone.syncSeq)
        assertTrue(savedCategoryDao.getAllNow().isEmpty())
    }

    @Test
    fun `deleting a never-synced category queues no tombstone`() = runTest(dispatcher) {
        seed("cat-1", "Pets")

        viewModel().deleteCategory("cat-1")

        assertTrue(marker.deleted(SyncTables.SAVED_CATEGORIES).isEmpty())
    }

    @Test
    fun `deleting an unknown category marks nothing`() = runTest(dispatcher) {
        viewModel().deleteCategory("missing")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(marker.deletes.isEmpty())
    }
}