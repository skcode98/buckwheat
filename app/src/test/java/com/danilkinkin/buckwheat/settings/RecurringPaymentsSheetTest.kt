package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.danilkinkin.buckwheat.data.RecurringAutoApplyMode
import com.danilkinkin.buckwheat.di.MainDispatcherRule
import com.danilkinkin.buckwheat.di.buildTestUiHarness
import com.danilkinkin.buckwheat.di.recurringAutoApplyModeStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.datastore.preferences.core.edit
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertEquals
import org.junit.Before
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

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Before
    fun setUp() {
        runBlocking {
            ApplicationProvider.getApplicationContext<Context>().settingsDataStore.edit {
                it.remove(recurringAutoApplyModeStoreKey)
            }
        }
    }

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

        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                harness.settingsRepository.getRecurringAutoApplyMode().first() == RecurringAutoApplyMode.ASK
            }
        }

        compose.onNodeWithText("Ask").assertIsSelected()
        compose.onNodeWithText("Auto").assertIsNotSelected()
        assertEquals(
            RecurringAutoApplyMode.ASK,
            runBlocking { harness.settingsRepository.getRecurringAutoApplyMode().first() },
        )
    }
}
