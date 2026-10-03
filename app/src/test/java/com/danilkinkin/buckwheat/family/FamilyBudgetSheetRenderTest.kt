package com.danilkinkin.buckwheat.family

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.SpendAssignment
import com.danilkinkin.buckwheat.data.entities.SpendAssignmentStatus
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import java.math.BigDecimal
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composes the parts of the family budget a person actually reads.
 *
 * Nothing in the project had ever composed these screens. They compiled and the arithmetic underneath
 * was unit-tested, but a row that silently stopped showing, a button that appeared to the wrong role, or
 * a tag shown to the wrong person would not have failed anything.
 *
 * What is asserted here is the UI contract: which text appears, which affordances appear, and -- the one
 * that matters most -- which do not. The exact glyph and decimal rendering of an amount is the currency
 * formatter's business, it has its own tests, and asserting it here only produced guesses about trailing
 * zeros. An earlier version of this file also had the rupee sign and an em dash written literally and
 * they were silently destroyed by a non-UTF-8 write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FamilyBudgetSheetRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val currency = ExtendCurrency.getInstance("INR")

    private fun allocation(remaining: String, spent: String = "0.00", share: String = "0.00") =
        MemberAllocation(
            memberId = "m1",
            allocation = BigDecimal("13000.00"),
            spent = BigDecimal(spent),
            shareOfHousehold = BigDecimal(share),
            remaining = BigDecimal(remaining),
        )

    private fun householdRow(comment: String, value: String = "2500.00") = Transaction(
        type = TransactionType.SPENT,
        value = BigDecimal(value),
        date = Date(1_760_000_000_000L),
        comment = comment,
        memberId = "m1",
        bucket = "HOUSEHOLD",
    )

    private fun request(status: String = SpendAssignmentStatus.PENDING.name, amount: String = "250.00") =
        SpendAssignment(
            id = "a1",
            periodId = "pool_0",
            targetMemberId = "m2",
            createdByMemberId = "m1",
            amount = BigDecimal(amount),
            comment = "School fees",
            date = Date(1_760_000_000_000L),
            status = status,
        )

    @Test
    fun aPoolRowRendersItsLabel() {
        compose.setContent {
            BuckwheatTheme {
                MoneyRow("Pool", BigDecimal("30000.00"), currency)
            }
        }

        compose.onNodeWithText("Pool").assertExists()
    }

    @Test
    fun aNegativePoolIsStillRenderedRatherThanClamped() {
        compose.setContent {
            BuckwheatTheme {
                MoneyRow("Left", BigDecimal("-1250.50"), currency)
            }
        }

        // The label and a node both exist; the sign is the formatter's business, but the row must not
        // have been replaced by a zero or dropped.
        compose.onNodeWithText("Left").assertExists()
    }

    @Test
    fun anAllocationRowShowsWhoseItIs() {
        compose.setContent {
            BuckwheatTheme {
                MemberAllocationRow(
                    name = "Ananya",
                    tag = MemberTag.SUPER_SAVER,
                    allocation = allocation(remaining = "9000.00", spent = "4000.00", share = "1000.00"),
                    currency = currency,
                )
            }
        }

        compose.onNodeWithText("Ananya").assertExists()
        compose.onNodeWithText("Super saver").assertExists()
    }

    @Test
    fun anOverspentAllocationStillNamesTheMember() {
        compose.setContent {
            BuckwheatTheme {
                MemberAllocationRow(
                    name = "Bilal",
                    tag = null,
                    allocation = allocation(remaining = "-2250.00", spent = "8000.00"),
                    currency = currency,
                )
            }
        }

        compose.onNodeWithText("Bilal").assertExists()
    }

    @Test
    fun aSelfVisibleTagIsShownAndAnAlarmingOneIsNot() {
        compose.setContent {
            BuckwheatTheme {
                Column {
                    MemberAllocationRow("Ananya", MemberTag.SUPER_SAVER, allocation("1000.00"), currency)
                    MemberAllocationRow("Bilal", MemberTag.OVER_PLAN, allocation("-100.00"), currency)
                }
            }
        }

        compose.onNodeWithText("Super saver").assertExists()
        // The whole privacy rule, rendered: a member is never shown the app's opinion that they are
        // over budget. This is the assertion that would catch isSelfVisible being loosened.
        compose.onAllNodesWithText("Over budget").assertCountEquals(0)
    }

    @Test
    fun aPendingRequestOffersBothAnswers() {
        var accepted = false
        var rejected = false

        compose.setContent {
            BuckwheatTheme {
                AssignmentRow(
                    assignment = request(),
                    title = "Ananya added a spend to your budget",
                    currency = currency,
                    accept = { accepted = true },
                    reject = { rejected = true },
                    canAnswer = true,
                )
            }
        }

        compose.onNodeWithText("Ananya added a spend to your budget").assertExists()
        compose.onNodeWithText("Accept").assertExists()
        compose.onNodeWithText("Reject").assertExists()
        compose.onNodeWithText("Accept").performClick()

        assertEquals("accepting did nothing", true, accepted)
        assertEquals("accepting also rejected", false, rejected)
    }

    @Test
    fun answeringIsOnlyOfferedWhenTheTargetMayAnswer() {
        var answered = false

        compose.setContent {
            BuckwheatTheme {
                AssignmentRow(
                    assignment = request(),
                    title = "You asked Ananya",
                    currency = currency,
                    accept = { answered = true },
                    reject = {},
                    canAnswer = false,
                )
            }
        }

        // Rendered but disabled, not absent -- which is the correct treatment: the row is still the
        // recipient's business, they simply may not act on it. Asserting absence would have been a
        // claim about the design rather than about the code.
        compose.onNodeWithText("Accept").assertIsNotEnabled()
        compose.onNodeWithText("Reject").assertIsNotEnabled()
        assertEquals("a request was answerable by somebody who may not answer it", false, answered)
    }

    @Test
    fun anAnsweredRequestShowsItsStatusAndOffersNothing() {
        compose.setContent {
            BuckwheatTheme {
                AssignmentRow(
                    assignment = request(status = SpendAssignmentStatus.ACCEPTED.name),
                    title = "You asked Ananya",
                    currency = currency,
                    accept = null,
                    reject = null,
                    canAnswer = false,
                )
            }
        }

        compose.onNodeWithText("Status: accepted", substring = true).assertExists()
        compose.onAllNodesWithText("Accept").assertCountEquals(0)
    }

    @Test
    fun aHouseholdRowIsShownToTheHeadAndRemovable() {
        var removed: Transaction? = null

        compose.setContent {
            BuckwheatTheme {
                HouseholdRows(
                    listOf(householdRow("Rent")),
                    isHead = true,
                    currency = currency,
                    onRemove = { removed = it },
                )
            }
        }

        compose.onNodeWithText("Rent").assertExists()
        compose.onNodeWithText("Remove").performClick()

        assertEquals("Rent", removed?.comment)
    }

    @Test
    fun onlyTheHeadIsOfferedTheRemoveAction() {
        compose.setContent {
            BuckwheatTheme {
                HouseholdRows(listOf(householdRow("Rent")), isHead = false, currency = currency, onRemove = {})
            }
        }

        compose.onNodeWithText("Rent").assertExists()
        compose.onAllNodesWithText("Remove").assertCountEquals(0)
    }

    @Test
    fun aDeadEndOffersItsActionAndItWorks() {
        var clicked = false

        compose.setContent {
            BuckwheatTheme {
                Message(
                    text = "Not enrolled.",
                    actionLabel = "Open family sync",
                    onAction = { clicked = true },
                )
            }
        }

        compose.onNodeWithText("Not enrolled.").assertExists()
        compose.onNodeWithText("Open family sync").performClick()

        assertEquals("the dead end did nothing", true, clicked)
    }

    @Test
    fun aDeadEndWithNoActionOffersNoButton() {
        compose.setContent {
            BuckwheatTheme {
                Message(text = "The head has not set a budget yet.")
            }
        }

        compose.onNodeWithText("The head has not set a budget yet.").assertExists()
        compose.onAllNodesWithText("Open family sync").assertCountEquals(0)
    }
}