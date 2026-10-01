package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.di.FakeRecurringDao
import com.danilkinkin.buckwheat.sync.SyncTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

// Every recurring_templates write has to enqueue a pending mutation, otherwise the change never
// reaches the server and an incoming pull silently reverts it.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringPaymentsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var fixture: SyncMarkingFixture
    private lateinit var recurringDao: FakeRecurringDao
    private lateinit var marker: FakeSyncDirtyMarker

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = SyncMarkingFixture()
        recurringDao = FakeRecurringDao()
        marker = FakeSyncDirtyMarker()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = RecurringPaymentsViewModel(
        recurringDao = recurringDao,
        settingsRepository = fixture.settingsRepository,
        syncDirtyMarker = marker,
    )

    private fun template(
        id: String,
        amount: String = "10",
        comment: String = "rent",
        dayOfMonth: Int = 1,
        enabled: Boolean = true,
        familyId: String? = null,
        syncSeq: Long = 0L,
    ) = RecurringTemplate(
        id = id,
        amount = BigDecimal(amount),
        comment = comment,
        dayOfMonth = dayOfMonth,
        enabled = enabled,
        familyId = familyId,
        syncSeq = syncSeq,
    )

    private suspend fun seed(template: RecurringTemplate) {
        recurringDao.insert(template)
    }

    @Test
    fun `adding a template marks the inserted row`() = runTest(dispatcher) {
        viewModel().addTemplate(BigDecimal("25"), "  rent  ", 5)

        val stored = recurringDao.getAllNow().single()
        assertEquals("rent", stored.comment)
        assertEquals(listOf(stored.id), marker.upserted(SyncTables.RECURRING_TEMPLATES))
    }

    @Test
    fun `adding an invalid template marks nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.addTemplate(BigDecimal.ZERO, "rent", 5)
        viewModel.addTemplate(BigDecimal("10"), "  ", 5)
        viewModel.addTemplate(BigDecimal("10"), "rent", 32)

        assertTrue(recurringDao.getAllNow().isEmpty())
        assertTrue(marker.upserted(SyncTables.RECURRING_TEMPLATES).isEmpty())
    }

    @Test
    fun `toggling a template marks the row and flips enabled`() = runTest(dispatcher) {
        seed(template("rec-1"))

        viewModel().toggleEnabled(template("rec-1"))

        assertEquals(listOf("rec-1"), marker.upserted(SyncTables.RECURRING_TEMPLATES))
        assertFalse(recurringDao.getAllNow().single().enabled)
    }

    @Test
    fun `updating a template marks the row and keeps its family metadata`() = runTest(dispatcher) {
        seed(template("rec-1", familyId = "family-1", syncSeq = 6L))

        viewModel().updateTemplate(template("rec-1"), BigDecimal("30"), "  utilities ", 12)

        assertEquals(listOf("rec-1"), marker.upserted(SyncTables.RECURRING_TEMPLATES))
        val stored = recurringDao.getAllNow().single()
        assertEquals(BigDecimal("30"), stored.amount)
        assertEquals("utilities", stored.comment)
        assertEquals(12, stored.dayOfMonth)
        assertEquals("family-1", stored.familyId)
        assertEquals(6L, stored.syncSeq)
    }

    @Test
    fun `deleting a synced template queues a tombstone with its family metadata`() = runTest(dispatcher) {
        seed(template("rec-1", familyId = "family-1", syncSeq = 8L))

        viewModel().deleteTemplate("rec-1")

        val tombstone = marker.deleted(SyncTables.RECURRING_TEMPLATES).single()
        assertEquals("rec-1", tombstone.recordId)
        assertEquals("family-1", tombstone.familyId)
        assertEquals(8L, tombstone.syncSeq)
        assertTrue(recurringDao.getAllNow().isEmpty())
    }

    @Test
    fun `deleting a never-synced template queues no tombstone`() = runTest(dispatcher) {
        seed(template("rec-1"))

        viewModel().deleteTemplate("rec-1")

        assertTrue(marker.deleted(SyncTables.RECURRING_TEMPLATES).isEmpty())
    }

    @Test
    fun `deleting an unknown template marks nothing`() = runTest(dispatcher) {
        viewModel().deleteTemplate("missing")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(marker.deletes.isEmpty())
    }
}