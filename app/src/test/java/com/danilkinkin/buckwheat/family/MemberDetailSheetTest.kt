package com.danilkinkin.buckwheat.family

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.buildTestUiHarness
import com.danilkinkin.buckwheat.sync.FamilyMember
import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val MEMBER_ID = "me"
private val DAY = Date(1_700_000_000_000L)

private fun session(memberId: String) = FamilySession(
    baseUrl = "http://localhost",
    token = "token",
    familyId = "family",
    memberId = memberId,
    joinCode = "code",
)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemberDetailSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun listsOnlyInPeriodTransactionsOfTheRequestedMember() {
        val dao = FakeFamilyTransactionDao(
            rows = listOf(
                FamilyTransaction(
                    id = "a",
                    type = TransactionType.SPENT,
                    value = 12.toBigDecimal(),
                    date = DAY,
                    comment = "groceries",
                    memberId = "them",
                ),
                FamilyTransaction(
                    id = "b",
                    type = TransactionType.SPENT,
                    value = 99.toBigDecimal(),
                    date = Date(DAY.time - 86_400_000L),
                    comment = "dinner",
                    memberId = "them",
                ),
                FamilyTransaction(
                    id = "c",
                    type = TransactionType.SPENT,
                    value = 5.toBigDecimal(),
                    date = DAY,
                    comment = "own lunch",
                    memberId = "me",
                ),
            ),
        )
        val viewModel = FamilyViewModel(
            dao,
            sessionStore = FakeSessionStore(
                session(MEMBER_ID),
                roster = listOf(
                    FamilyMember("me", "Ren", departed = false, joinedAt = "2024-11-15"),
                    FamilyMember("them", "Them", departed = false, joinedAt = "2024-11-15"),
                ),
            ),
            spendsRepository = FakeSpendsRepository(DAY, DAY),
        )
        val harness = buildTestUiHarness()

        compose.setContent {
            BuckwheatTheme {
                MemberDetailSheet(
                    memberId = "them",
                    viewModel = viewModel,
                    spendsViewModel = harness.spendsViewModel,
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Them").assertIsDisplayed()
        compose.onNodeWithText("groceries").assertIsDisplayed()
        compose.onNodeWithText("dinner").assertDoesNotExist()
        compose.onNodeWithText("own lunch").assertDoesNotExist()
    }

    @Test
    fun showsNothingSpentWhenMemberHasNoTransactions() {
        val viewModel = FamilyViewModel(
            FakeFamilyTransactionDao(),
            sessionStore = FakeSessionStore(
                session(MEMBER_ID),
                roster = listOf(
                    FamilyMember("me", "Ren", departed = false, joinedAt = "2024-11-15"),
                    FamilyMember("them", "Them", departed = false, joinedAt = "2024-11-15"),
                ),
            ),
            spendsRepository = FakeSpendsRepository(DAY, DAY),
        )
        val harness = buildTestUiHarness()

        compose.setContent {
            BuckwheatTheme {
                MemberDetailSheet(
                    memberId = "them",
                    viewModel = viewModel,
                    spendsViewModel = harness.spendsViewModel,
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Them").assertIsDisplayed()
        compose.onNodeWithText("Nothing spent yet").assertIsDisplayed()
    }

    @Test
    fun budgetMarkersAreNeitherListedNorFoldedIntoTheMemberTotal() {
        val dao = FakeFamilyTransactionDao(
            rows = listOf(
                FamilyTransaction(
                    id = "a",
                    type = TransactionType.SPENT,
                    value = 12.toBigDecimal(),
                    date = DAY,
                    comment = "groceries",
                    memberId = "them",
                ),
                FamilyTransaction(
                    id = "income",
                    type = TransactionType.INCOME,
                    value = 88888.toBigDecimal(),
                    date = DAY,
                    comment = "budget marker",
                    memberId = "them",
                ),
                FamilyTransaction(
                    id = "daily",
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = 77777.toBigDecimal(),
                    date = DAY,
                    comment = "daily marker",
                    memberId = "them",
                ),
            ),
        )
        val viewModel = FamilyViewModel(
            dao,
            sessionStore = FakeSessionStore(
                session(MEMBER_ID),
                roster = listOf(
                    FamilyMember("me", "Ren", departed = false, joinedAt = "2024-11-15"),
                    FamilyMember("them", "Them", departed = false, joinedAt = "2024-11-15"),
                ),
            ),
            spendsRepository = FakeSpendsRepository(DAY, DAY),
        )
        val harness = buildTestUiHarness()

        compose.setContent {
            BuckwheatTheme {
                MemberDetailSheet(
                    memberId = "them",
                    viewModel = viewModel,
                    spendsViewModel = harness.spendsViewModel,
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Them").assertIsDisplayed()
        compose.onNodeWithText("groceries").assertIsDisplayed()
        compose.onNodeWithText("budget marker").assertDoesNotExist()
        compose.onNodeWithText("daily marker").assertDoesNotExist()
        // The marker values must surface nowhere: not as list rows, and not folded into the
        // member total or the day total (88888 / 77777 would put "88" / "77" on screen).
        assertTrue(compose.onAllNodesWithText("88", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("77", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun memberWithOnlyBudgetMarkersShowsNothingSpent() {
        val dao = FakeFamilyTransactionDao(
            rows = listOf(
                FamilyTransaction(
                    id = "income",
                    type = TransactionType.INCOME,
                    value = 88888.toBigDecimal(),
                    date = DAY,
                    comment = "budget marker",
                    memberId = "them",
                ),
                FamilyTransaction(
                    id = "daily",
                    type = TransactionType.SET_DAILY_BUDGET,
                    value = 77777.toBigDecimal(),
                    date = DAY,
                    comment = "daily marker",
                    memberId = "them",
                ),
            ),
        )
        val viewModel = FamilyViewModel(
            dao,
            sessionStore = FakeSessionStore(
                session(MEMBER_ID),
                roster = listOf(
                    FamilyMember("me", "Ren", departed = false, joinedAt = "2024-11-15"),
                    FamilyMember("them", "Them", departed = false, joinedAt = "2024-11-15"),
                ),
            ),
            spendsRepository = FakeSpendsRepository(DAY, DAY),
        )
        val harness = buildTestUiHarness()

        compose.setContent {
            BuckwheatTheme {
                MemberDetailSheet(
                    memberId = "them",
                    viewModel = viewModel,
                    spendsViewModel = harness.spendsViewModel,
                    onClose = {},
                )
            }
        }

        // No spends, so the day grouping (and with it memberTotal and every dayTotal) is empty:
        // a marker-only member reads as "Nothing spent yet", not as the budget figure.
        compose.onNodeWithText("Them").assertIsDisplayed()
        compose.onNodeWithText("Nothing spent yet").assertIsDisplayed()
        compose.onNodeWithText("budget marker").assertDoesNotExist()
    }
}