package com.danilkinkin.buckwheat.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.buildTestUiHarness
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringChargeConfirmSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun pendingCharges() = listOf(
        Transaction(
            type = TransactionType.SPENT,
            value = 12.toBigDecimal(),
            date = Date(),
            comment = "gym",
        ),
        Transaction(
            type = TransactionType.SPENT,
            value = 7.5.toBigDecimal(),
            date = Date(),
            comment = "phone",
        ),
    )

    @Test
    fun addButtonRecordsPendingChargesAndClearsQueue() {
        val harness = buildTestUiHarness()
        // addSpent() needs an active budget period before it will persist anything.
        runBlocking {
            harness.spendsRepository.setBudget(
                newBudget = 1000.toBigDecimal(),
                newFinishDate = Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000),
            )
        }
        harness.spendsViewModel.pendingRecurringCharges.value = pendingCharges()
        // Re-seed after the first composition: SpendsViewModel's init coroutines run while the
        // sheet composes and can overwrite an earlier queue injection.
        compose.setContent {
            BuckwheatTheme {
                RecurringChargeConfirmSheet(spendsViewModel = harness.spendsViewModel)
            }
        }
        compose.runOnIdle {
            harness.spendsViewModel.pendingRecurringCharges.value = pendingCharges()
        }

        compose.onNodeWithText("Add recurring payments?").assertIsDisplayed()
        compose.onNodeWithText("gym").assertIsDisplayed()
        compose.onNodeWithText("phone").assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()

        compose.onNodeWithText("Add").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            harness.spendsViewModel.pendingRecurringCharges.value?.isEmpty() == true
        }

        // confirmRecurringCharges() clears the queue before inserting, so wait for the rows
        // themselves instead of racing the insert coroutine. The inserts suspend on DataStore, so
        // pump the main looper between checks instead of only polling Compose idleness.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            harness.transactionDao.spends.count { it.type == TransactionType.SPENT } < 2
        ) {
            compose.waitForIdle()
            Thread.sleep(25)
        }

        val spends = harness.transactionDao.spends.filter { it.type == TransactionType.SPENT }
        val comments = spends.map { it.comment }
        assertTrue("gym" in comments)
        assertTrue("phone" in comments)
        assertTrue(spends.all { it.type == TransactionType.SPENT })
    }

    @Test
    fun skipButtonRecordsNothingAndClearsQueue() {
        val harness = buildTestUiHarness()
        harness.spendsViewModel.pendingRecurringCharges.value = pendingCharges()

        compose.setContent {
            BuckwheatTheme {
                RecurringChargeConfirmSheet(spendsViewModel = harness.spendsViewModel)
            }
        }
        compose.runOnIdle {
            harness.spendsViewModel.pendingRecurringCharges.value = pendingCharges()
        }

        compose.onNodeWithText("Skip").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            harness.spendsViewModel.pendingRecurringCharges.value.orEmpty().isEmpty()
        }

        assertTrue(harness.transactionDao.spends.isEmpty())
    }
}
