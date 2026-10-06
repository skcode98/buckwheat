package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.di.FakeSavedCategoryDao
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

// saved_categories edits no longer enqueue pending mutations; the writes must still land without
// touching sync columns.
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
    fun `adding a category inserts it without marking`() = runTest(dispatcher) {
        viewModel().addCategory("  Pets  ", "🐶")

        val stored = savedCategoryDao.getAllNow().single()
        assertTrue(marker.upserts.isEmpty())
        assertEquals("Pets", stored.name)
        assertEquals("🐶", stored.emoji)
    }

    @Test
    fun `adding a duplicate category marks nothing`() = runTest(dispatcher) {
        seed("cat-1", "Pets")

        viewModel().addCategory("Pets")

        assertTrue(marker.upserts.isEmpty())
    }

    @Test
    fun `adding a built-in category name marks nothing`() = runTest(dispatcher) {
        viewModel().addCategory("FOOD")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(savedCategoryDao.getAllNow().isEmpty())
    }

    // A rebuilt SavedCategory(id = …) used to be @Update-d straight onto the row, which reset
    // family_id / sync_seq / version back to their defaults — the rename then pushed as a brand
    // new record and the server rejected it forever.
    @Test
    fun `renaming a category preserves its sync columns without marking`() = runTest(dispatcher) {
        seed("cat-1", "Pets", emoji = "🐶", familyId = "family-1", syncSeq = 3L, version = 9)

        viewModel().updateCategory("cat-1", "Animals", "🐾")

        assertTrue(marker.upserts.isEmpty())
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

        assertTrue(marker.upserts.isEmpty())
        assertEquals("Pets", savedCategoryDao.getAllNow().first { it.id == "cat-1" }.name)
    }

    @Test
    fun `deleting a synced category drops the row without a tombstone`() = runTest(dispatcher) {
        seed("cat-1", "Pets", familyId = "family-1", syncSeq = 11L)

        viewModel().deleteCategory("cat-1")

        assertTrue(marker.deletes.isEmpty())
        assertTrue(savedCategoryDao.getAllNow().isEmpty())
    }

    @Test
    fun `deleting a never-synced category queues no tombstone`() = runTest(dispatcher) {
        seed("cat-1", "Pets")

        viewModel().deleteCategory("cat-1")

        assertTrue(marker.deletes.isEmpty())
    }

    @Test
    fun `deleting an unknown category marks nothing`() = runTest(dispatcher) {
        viewModel().deleteCategory("missing")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(marker.deletes.isEmpty())
    }
}