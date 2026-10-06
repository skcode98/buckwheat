package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.di.FakeRecurringDao
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

// recurring_templates edits no longer enqueue pending mutations; the writes must still land
// without touching sync columns.
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
    fun `adding a template inserts it without marking`() = runTest(dispatcher) {
        viewModel().addTemplate(BigDecimal("25"), "  rent  ", 5)

        val stored = recurringDao.getAllNow().single()
        assertEquals("rent", stored.comment)
        assertTrue(marker.upserts.isEmpty())
    }

    @Test
    fun `adding an invalid template marks nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.addTemplate(BigDecimal.ZERO, "rent", 5)
        viewModel.addTemplate(BigDecimal("10"), "  ", 5)
        viewModel.addTemplate(BigDecimal("10"), "rent", 32)

        assertTrue(recurringDao.getAllNow().isEmpty())
        assertTrue(marker.upserts.isEmpty())
    }

    @Test
    fun `toggling a template flips enabled without marking`() = runTest(dispatcher) {
        seed(template("rec-1"))

        viewModel().toggleEnabled(template("rec-1"))

        assertTrue(marker.upserts.isEmpty())
        assertFalse(recurringDao.getAllNow().single().enabled)
    }

    @Test
    fun `updating a template keeps its family metadata without marking`() = runTest(dispatcher) {
        seed(template("rec-1", familyId = "family-1", syncSeq = 6L))

        viewModel().updateTemplate(template("rec-1"), BigDecimal("30"), "  utilities ", 12)

        assertTrue(marker.upserts.isEmpty())
        val stored = recurringDao.getAllNow().single()
        assertEquals(BigDecimal("30"), stored.amount)
        assertEquals("utilities", stored.comment)
        assertEquals(12, stored.dayOfMonth)
        assertEquals("family-1", stored.familyId)
        assertEquals(6L, stored.syncSeq)
    }

    @Test
    fun `deleting a synced template drops the row without a tombstone`() = runTest(dispatcher) {
        seed(template("rec-1", familyId = "family-1", syncSeq = 8L))

        viewModel().deleteTemplate("rec-1")

        assertTrue(marker.deletes.isEmpty())
        assertTrue(recurringDao.getAllNow().isEmpty())
    }

    @Test
    fun `deleting a never-synced template queues no tombstone`() = runTest(dispatcher) {
        seed(template("rec-1"))

        viewModel().deleteTemplate("rec-1")

        assertTrue(marker.deletes.isEmpty())
    }

    @Test
    fun `deleting an unknown template marks nothing`() = runTest(dispatcher) {
        viewModel().deleteTemplate("missing")

        assertTrue(marker.upserts.isEmpty())
        assertTrue(marker.deletes.isEmpty())
    }
}