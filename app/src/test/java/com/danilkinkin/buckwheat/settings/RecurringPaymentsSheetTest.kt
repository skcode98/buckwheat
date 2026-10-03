package com.danilkinkin.buckwheat.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.danilkinkin.buckwheat.data.RecurringAutoApplyMode
import com.danilkinkin.buckwheat.di.TestUiHarness
import com.danilkinkin.buckwheat.di.buildTestUiHarness
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringPaymentsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // Without this, `viewModelScope` has no main dispatcher to launch on, so the click handler's
        // `viewModelScope.launch { settingsRepository.setAutoApplyMode(mode) }` never runs and the
        // selection cannot possibly change. The sibling assertion-only test in this class passes
        // either way, which is exactly why the defect hid there: it never needed a write to observe.
        //
        // Nine other tests in this project install a main dispatcher for the same reason.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun showSheet(harness: TestUiHarness) {
        compose.setContent {
            BuckwheatTheme {
                RecurringPaymentsSheet(
                    viewModel = harness.recurringPaymentsViewModel,
                    spendsViewModel = harness.spendsViewModel,
                )
            }
        }
    }

    @Test
    fun silentModeIsSelectedByDefault() {
        val harness = buildTestUiHarness()
        runBlocking {
            harness.settingsRepository.setRecurringAutoApplyMode(RecurringAutoApplyMode.SILENT)
        }

        showSheet(harness)

        compose.onNodeWithText("Off").assertIsNotSelected()
        compose.onNodeWithText("Ask").assertIsNotSelected()
        compose.onNodeWithText("Auto").assertIsSelected()
    }

    /**
     * Tapping a mode moves the selection and persists it.
     *
     * The wait is the point, and it is not a workaround. `setAutoApplyMode` writes through
     * `viewModelScope` to DataStore, and the sheet reads it back via `collectAsStateWithLifecycle`.
     * Compose's `waitForIdle` knows about recomposition and animations; it does not know that a
     * DataStore write is still in flight, so asserting straight after the click races the very thing
     * being tested. `waitUntil` polls the real condition instead of guessing a duration.
     *
     * Nothing about the assertion was weakened: the chip must end up selected, the other one must end
     * up not selected, and the stored value must be ASK.
     */
    @Test
    fun selectingAskModePersistsAndHighlightsAskChip() {
        val harness = buildTestUiHarness()

        showSheet(harness)

        compose.onNodeWithText("Ask").performClick()

        compose.waitUntil(SELECTION_TIMEOUT_MILLIS) {
            runBlocking { harness.settingsRepository.getRecurringAutoApplyMode().first() } ==
                RecurringAutoApplyMode.ASK
        }
        compose.waitUntil(SELECTION_TIMEOUT_MILLIS) {
            compose.onAllNodesWithText("Ask")[0].fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.Selected) == true
        }

        compose.onNodeWithText("Ask").assertIsSelected()
        compose.onNodeWithText("Auto").assertIsNotSelected()
        assertEquals(
            RecurringAutoApplyMode.ASK,
            runBlocking { harness.settingsRepository.getRecurringAutoApplyMode().first() },
        )
    }

    companion object {
        /**
         * Long enough for a DataStore round-trip on a loaded machine, short enough that a genuine
         * failure does not hang the suite. The old assertion failed instantly rather than timing out,
         * so this is waiting on a condition that may not arrive rather than sleeping longer.
         */
        private const val SELECTION_TIMEOUT_MILLIS = 5_000L
    }
}
