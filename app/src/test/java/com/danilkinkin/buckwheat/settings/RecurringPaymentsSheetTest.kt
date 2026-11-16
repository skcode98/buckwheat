package com.danilkinkin.buckwheat.settings

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.danilkinkin.buckwheat.data.RecurringAutoApplyMode
import com.danilkinkin.buckwheat.di.buildTestUiHarness
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringPaymentsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showSheet() {
        val harness = buildTestUiHarness()
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
        showSheet()

        compose.onNodeWithText("Off").assertIsNotSelected()
        compose.onNodeWithText("Ask").assertIsNotSelected()
        compose.onNodeWithText("Auto").assertIsSelected()
    }

    @Test
    fun selectingAskModePersistsAndHighlightsAskChip() {
        val harness = buildTestUiHarness()

        compose.setContent {
            BuckwheatTheme {
                RecurringPaymentsSheet(
                    viewModel = harness.recurringPaymentsViewModel,
                    spendsViewModel = harness.spendsViewModel,
                )
            }
        }

        compose.onNodeWithText("Ask").performClick()

        // Saving the mode suspends on DataStore, so pump the main looper between reads instead of
        // only polling Compose idleness.
        var persistedMode: RecurringAutoApplyMode? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            persistedMode = runBlocking {
                harness.settingsRepository.getRecurringAutoApplyMode().first()
            }
            if (persistedMode == RecurringAutoApplyMode.ASK) break
            Thread.sleep(25)
        }

        compose.onNodeWithText("Ask").assertIsSelected()
        compose.onNodeWithText("Auto").assertIsNotSelected()
        assertEquals(RecurringAutoApplyMode.ASK, persistedMode)
    }
}
