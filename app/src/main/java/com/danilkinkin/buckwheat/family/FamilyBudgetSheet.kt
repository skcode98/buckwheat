package com.danilkinkin.buckwheat.family

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.util.numberFormat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.settings.FAMILY_SYNC_SHEET
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.entities.CommonSplitRule
import com.danilkinkin.buckwheat.data.entities.SpendAssignment
import com.danilkinkin.buckwheat.data.entities.SpendAssignmentStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date

const val FAMILY_BUDGET_SHEET = "familyBudget"

@Composable
fun FamilyBudgetSheet(
    viewModel: FamilyBudgetViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val isHead by viewModel.isHead.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle(initialValue = null)
    val currency by viewModel.currency.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val householdRows by viewModel.householdRows.collectAsStateWithLifecycle()
    val householdDetailVisible by viewModel.householdDetailVisibleToAll.collectAsStateWithLifecycle()
    val assignmentsViewModel: SpendAssignmentsViewModel = hiltViewModel()
    val assignments by assignmentsViewModel.assignments.collectAsStateWithLifecycle()
    val myPending by assignmentsViewModel.me.collectAsStateWithLifecycle()
    val rosterKnown by viewModel.rosterKnown.collectAsStateWithLifecycle()
    val hasPool by viewModel.hasPool.collectAsStateWithLifecycle()
    val periodBounds by viewModel.periodKnown.collectAsStateWithLifecycle()

    if (session == null) {
        Message(
            text = stringResource(R.string.family_budget_not_enrolled),
            actionLabel = stringResource(R.string.family_budget_open_sync),
            onAction = { appViewModel.openSheet(PathState(FAMILY_SYNC_SHEET)) },
        )
        return
    }

val current = budget
    if (current == null) {
        // Three unrelated situations, one null, and guessing between them is what produced a head
        // staring at "the head has not set a budget yet" because their enrolment refetch had failed:
        // the app could not find out who was head, found nobody, and concluded they were not it.
        when {
            !rosterKnown -> Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Header(isHead = false)
                Text(stringResource(R.string.family_budget_role_unknown), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { viewModel.refreshRoster() }) {
                    Text(stringResource(R.string.family_budget_retry))
                }
            }

            // A pool needs a period to attach to. Offering an editor without one advertises an action
            // that cannot work, because saving would be refused for want of a period.
            !periodBounds -> Message(
                text = stringResource(R.string.family_budget_no_period),
                actionLabel = stringResource(R.string.family_budget_set_own_period),
                onAction = { appViewModel.closeSheet(FAMILY_BUDGET_SHEET) },
            )

            isHead -> LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Header(isHead = true) }
                item { Text(stringResource(R.string.family_budget_no_pool), style = MaterialTheme.typography.bodyMedium) }
                item {
                    // No pool yet, so nothing to seed from: every field starts blank and the head
                    // fills it in for the first time.
                    AllocationEditor(
                        viewModel = viewModel,
                        isHead = true,
                        memberIds = members.keys.toList(),
                        total = BigDecimal.ZERO,
                        householdTier = BigDecimal.ZERO,
                        current = EMPTY_BUDGET,
                    )
                }
            }

            else -> Message(stringResource(R.string.family_budget_wait_for_head))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(isHead) }

        item { SummarySection(viewModel) }

        item {
            MoneyRow(stringResource(R.string.family_budget_pool), current.total, currency)
            MoneyRow(stringResource(R.string.family_budget_household_tier), current.householdTier, currency)
            MoneyRow(stringResource(R.string.family_budget_household_spent), current.householdSpent, currency)
            MoneyRow(stringResource(R.string.family_budget_left), current.remaining, currency)
        }

        item {
            Text(
                text = stringResource(R.string.family_budget_members),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        items(current.allocations, key = { it.memberId }) { allocation ->
            MemberAllocationRow(
                name = viewModel.displayName(allocation.memberId),
                tag = tags[allocation.memberId],
                allocation = allocation,
                currency = currency,
            )
        }

        if (current.allocations.isEmpty()) {
            item { Text(stringResource(R.string.family_budget_no_allocations)) }
        }

        item {
            HouseholdSection(
                viewModel = viewModel,
                isHead = isHead,
                rows = householdRows,
                detailVisibleToAll = householdDetailVisible,
                householdSpent = current.householdSpent,
                currency = currency,
            )

        }

        item {
            Text(
                text = stringResource(R.string.family_budget_requests),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // Scoped to the active period and capped. Unfiltered, this list grows for ever -- a request from
        // six months ago stayed on screen and stayed actionable, offering to accept a spend that
        // belonged to a period nobody is looking at any more.
        val activePeriod = viewModel.activePeriodKey
        val forThisPeriod = if (activePeriod == null) {
            assignments
        } else {
            assignments.filter { it.periodId == activePeriod }
        }
        val waitingOnMe = forThisPeriod.filter { it.targetMemberId == myPending && !it.assignmentStatus.isResolved }
        if (waitingOnMe.isNotEmpty()) {
            items(waitingOnMe.take(RECENT_LIMIT), key = { "mine-${it.id}" }) { assignment ->
                AssignmentRow(
                    assignment = assignment,
                    title = stringResource(
                        R.string.family_budget_request_from,
                        viewModel.displayName(assignment.createdByMemberId),
                    ),
                    currency = currency,
                    accept = { assignmentsViewModel.answer(assignment, true) },
                    reject = { assignmentsViewModel.answer(assignment, false) },
                    canAnswer = assignmentsViewModel.canAnswer(assignment),
                )
            }
        } else {
            item { Text(stringResource(R.string.family_budget_no_requests)) }
        }

        val mine = forThisPeriod.filter { it.createdByMemberId == myPending }.take(RECENT_LIMIT)
        if (mine.isNotEmpty()) {
            items(mine, key = { "sent-${it.id}" }) { assignment ->
                AssignmentRow(
                    assignment = assignment,
                    title = stringResource(
                        R.string.family_budget_request_to,
                        viewModel.displayName(assignment.targetMemberId),
                    ),
                    currency = currency,
                    accept = null,
                    reject = null,
                    canAnswer = false,
                )
            }
        }

        item {
            AssignmentComposer(
                viewModel = viewModel,
                assignmentsViewModel = assignmentsViewModel,
                isHead = isHead,
                memberIds = members.keys.toList(),
            )
        }

item {
            AllocationEditor(
                viewModel = viewModel,
                isHead = isHead,
                memberIds = members.keys.toList(),
                total = current.total,
                householdTier = current.householdTier,
                current = current,
            )
        }

        item { Footer(isHead, householdDetailVisible, viewModel) }
    }
}

/**
 * The household summary.
 *
 * The offline renderer runs first and the model's version replaces it, so this is never empty. Which
 * of the two is on screen is stated, because a family being told they are overspending by a sentence
 * they did not write deserves to know who wrote it.
 */
@Composable
private fun SummarySection(viewModel: FamilyBudgetViewModel) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val loading by viewModel.summaryLoading.collectAsStateWithLifecycle()

    // Keyed on the period's identity rather than `Unit`, so reopening the sheet after a period change
    // does not show last period's prose. Deliberately NOT keyed on the pool total: that would fire a
    // model call on every keystroke in the split editor, and the offline text already re-renders live
    // from the current figures underneath.
    //
    // `.value`, not the StateFlow. A LaunchedEffect key is compared by equals, and a StateFlow
    // instance is always the same object however its contents change -- keying on it is `Unit` with
    // extra steps, which is the bug this replaced.
    val periodKey by viewModel.periodKey.collectAsStateWithLifecycle()
    LaunchedEffect(periodKey) {
        if (periodKey != null) viewModel.loadSummary()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.family_budget_summary_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val current = summary
            if (current == null) {
                Text(stringResource(R.string.family_budget_summary_loading))
            } else {
                Text(text = current.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(
                        if (current.fromModel) {
                            R.string.family_budget_summary_from_model
                        } else {
                            R.string.family_budget_summary_offline
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (loading) {
                Text(
                    text = stringResource(R.string.family_budget_summary_loading),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * A state the sheet cannot show anything useful in, with the one action that moves it forward.
 *
 * Both of these used to be a line of grey text and nothing else, so somebody who opened the family
 * budget before enrolling, or whose family has no pool yet, was told what was wrong and given no way
 * to do anything about it. A dead end that explains itself is still a dead end.
 */
@Composable
private fun Message(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun Header(isHead: Boolean) {
    Column {
        Text(
            text = stringResource(R.string.family_budget_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (isHead) {
            Text(
                text = stringResource(R.string.family_budget_you_are_head),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Footer(isHead: Boolean, detailVisibleToAll: Boolean, viewModel: FamilyBudgetViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.family_sync_transparency_notice),
            style = MaterialTheme.typography.bodySmall,
        )
        if (isHead) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        viewModel.setHouseholdDetailVisibleToAll(!detailVisibleToAll)
                    }
                ) {
                    Text(
                        stringResource(
                            if (detailVisibleToAll) {
                                R.string.family_budget_hide_household_detail
                            } else {
                                R.string.family_budget_show_household_detail
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun MoneyRow(
    label: String,
    amount: BigDecimal,
    currency: ExtendCurrency,
) {
    // A negative figure here is the difference between "a number" and "you are over". The arithmetic
    // deliberately allows negatives -- clamping to zero hides the size of the hole while still making
    // the next spend look affordable -- so the presentation has to carry the same information, or the
    // clamp is undone at the last step and the user sees a positive number for an overspent budget.
    val overspent = amount.signum() < 0
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = numberFormat(LocalContext.current, amount, currency, trimDecimalPlaces = true),
            style = MaterialTheme.typography.titleMedium,
            color = if (overspent) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun MemberAllocationRow(
    name: String,
    tag: MemberTag?,
    allocation: MemberAllocation,
    currency: ExtendCurrency,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = numberFormat(LocalContext.current, allocation.remaining, currency, trimDecimalPlaces = true),
                style = MaterialTheme.typography.bodyLarge,
                color = if (allocation.remaining.signum() < 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Text(
            text = stringResource(
                R.string.family_budget_allocation_detail,
                numberFormat(LocalContext.current, allocation.allocation, currency, trimDecimalPlaces = true),
                numberFormat(LocalContext.current, allocation.spent, currency, trimDecimalPlaces = true),
                numberFormat(LocalContext.current, allocation.shareOfHousehold, currency, trimDecimalPlaces = true),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        // Only ever the member's own positive labels. A person is not shown the app's opinion of them.
        // A plain Text rather than a chip: an onClick that does nothing still draws a ripple and reads
        // as a control, so the control is a lie about what is tappable.
        tag?.takeIf { it.isSelfVisible }?.let { shown ->
            Text(
                text = stringResource(tagLabel(shown)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The household's own expenses.
 *
 * The aggregate is always shown, because every member's remaining figure is derived from it and hiding
 * it would make that figure unexplainable. The individual entries are shown to the head always, and to
 * everyone only when the family has opted in.
 */
@Composable
private fun HouseholdSection(
    viewModel: FamilyBudgetViewModel,
    isHead: Boolean,
    rows: List<com.danilkinkin.buckwheat.data.entities.Transaction>,
    detailVisibleToAll: Boolean,
    householdSpent: BigDecimal,
    currency: ExtendCurrency,
) {
    var amountText by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }

    val showDetail = isHead || detailVisibleToAll

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.family_budget_household_title),
                style = MaterialTheme.typography.titleMedium,
            )
            MoneyRow(stringResource(R.string.family_budget_household_spent), householdSpent, currency)

            if (showDetail) {
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.family_budget_household_empty))
                }
                // Capped: an unbounded list inside a Column measures every row on every recomposition, and a
                // year of groceries is more rows than a sheet should build to show five.
                rows.takeLast(6).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.comment.ifBlank { stringResource(R.string.family_budget_no_comment) },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // Recorded by the head, so removable by the head. Without this a mistyped
                            // household expense is permanent: nothing else in the app edits a HOUSEHOLD
                            // row, and the server refuses the correction from anyone who is not the head.
                            if (isHead) {
                                TextButton(onClick = { viewModel.removeHouseholdSpend(row) }) {
                                    Text(stringResource(R.string.family_budget_remove))
                                }
                            }
                        }
                        Text(
                            text = numberFormat(LocalContext.current, row.value, currency, trimDecimalPlaces = true),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (row.value.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.family_budget_household_hidden))
            }

            if (isHead) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.family_budget_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.family_budget_comment)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val amount = parseAmount(amountText)
                        if (amount != null && viewModel.addHouseholdSpend(amount, comment, null, Date())) {
                            amountText = ""
                            comment = ""
                        }
                    },
                    enabled = parseAmount(amountText)?.signum() == 1,
                ) {
                    Text(stringResource(R.string.family_budget_add_household))
                }
            }
        }
    }
}

/**
 * One request, and the only place a person can answer one.
 *
 * The status is shown to everyone rather than only to the two involved, because the family's rule is
 * full transparency and because a rejected request disappearing would leave the head thinking it had
 * simply been lost.
 */
@Composable
private fun AssignmentRow(
    assignment: SpendAssignment,
    title: String,
    currency: ExtendCurrency,
    accept: (() -> Unit)?,
    reject: (() -> Unit)?,
    canAnswer: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    R.string.family_budget_request_detail,
                    numberFormat(LocalContext.current, assignment.amount, currency, trimDecimalPlaces = true),
                    assignment.comment.ifBlank { stringResource(R.string.family_budget_no_comment) },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.family_budget_request_status, assignmentStatusLabel(assignment.assignmentStatus)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (accept != null && reject != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = accept, enabled = canAnswer) {
                        Text(stringResource(R.string.family_budget_accept))
                    }
                    TextButton(onClick = reject, enabled = canAnswer) {
                        Text(stringResource(R.string.family_budget_reject))
                    }
                }
            }
        }
    }
}

/** The head's side: raise a request against somebody else's slice. */
@Composable
private fun AssignmentComposer(
    viewModel: FamilyBudgetViewModel,
    assignmentsViewModel: SpendAssignmentsViewModel,
    isHead: Boolean,
    memberIds: List<String>,
) {
    if (!isHead) return

    var target by remember(memberIds) { mutableStateOf(memberIds.firstOrNull().orEmpty()) }
    var amountText by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    // Read here rather than inside the click handler: stringResource is composable-only, and an
    // onClick lambda is not a composable scope.
    val amountInvalid = stringResource(R.string.family_budget_amount_invalid)
    val noTarget = stringResource(R.string.family_budget_no_target)
    val cannotAskSelf = stringResource(R.string.family_budget_cannot_ask_self)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.family_budget_ask_member),
                style = MaterialTheme.typography.titleMedium,
            )

            if (memberIds.size < 2) {
                Text(stringResource(R.string.family_budget_need_two_members))
            } else {
                // Scrollable because a large family simply does not fit, and a clipped row of member
                // chips reads as "that is everyone" rather than "there are more below".
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    memberIds.forEach { id ->
                        AssistChip(
                            onClick = { target = id },
                            label = { Text(viewModel.displayName(id)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.family_budget_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.family_budget_comment)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                note?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        val amount = parseAmount(amountText)
                        val me = assignmentsViewModel.me.value
                        val periodId = viewModel.activePeriodKey
                        when {
                            amount == null || amount.signum() <= 0 -> note = amountInvalid

                            target.isBlank() -> note = noTarget

                            target == me -> note = cannotAskSelf

                            // No active period means no split exists, so there is nothing to charge
                            // the request to. Refused rather than filed against a guessed key.
                            periodId == null -> note = noTarget

                            else -> {
                                val created = assignmentsViewModel.create(
                                    targetMemberId = target,
                                    amount = amount,
                                    comment = comment,
                                    category = null,
                                    date = Date(),
                                    periodId = periodId,
                                )
                                if (created) {
                                    amountText = ""
                                    comment = ""
                                    note = null
                                } else {
                                    note = noTarget
                                }
                            }
                        }
                    },
                    enabled = parseAmount(amountText)?.signum() == 1,
                ) {
                    Text(stringResource(R.string.family_budget_send_request))
                }
            }
        }
    }
}

/**
 * The head's split editor.
 *
 * Refuses to save a partial split rather than rounding the difference into the last member: absorbing
 * a few hundred into somebody's slice silently is how a member ends up over budget with nothing on
 * screen explaining it.
 */
@Composable
private fun AllocationEditor(
    viewModel: FamilyBudgetViewModel,
    isHead: Boolean,
    memberIds: List<String>,
    total: BigDecimal,
    householdTier: BigDecimal,
    current: FamilyBudget,
) {
    if (!isHead) return

    val saveProblem by viewModel.saveProblem.collectAsStateWithLifecycle()

    // Re-seeded when the stored pool changes. Keyed on `total`, not a bare `remember`, because a
    // bare one never re-seeds: opening the editor after the head saved showed the previous values,
    // and a field initialised blank forces the whole pool to be retyped.
    var poolText by remember(total) { mutableStateOf(total.plainString()) }
    var householdText by remember(householdTier) { mutableStateOf(householdTier.plainString()) }
    // The stored split rule, not EQUAL: opening the editor used to silently reset a family that had
    // chosen proportional, and saving then wrote EQUAL over their choice.
    var household by remember(viewModel.splitRule.value) { mutableStateOf(viewModel.splitRule.value) }
    // Keyed on `memberIds`, like the fields above: a bare `remember` never re-seeds, so a member who
    // joins while the sheet is open gets no row at all and the save is then refused for being short.
        // Seeded from the stored split: the editor's whole purpose is adjusting it. A blank map forced the
    // head to retype every figure to save a one-rupee change, which is how a feature gets used once.
    var allocations by remember(memberIds, current) {
        mutableStateOf(
            current.allocations.associate { it.memberId to it.allocation.plainString() }
        )
    }

    val typedPool = parseAmount(poolText)
    val typedHousehold = parseAmount(householdText)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.family_budget_edit_split),
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedTextField(
            value = poolText,
            onValueChange = { poolText = it },
            label = { Text(stringResource(R.string.family_budget_pool)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = householdText,
            onValueChange = { householdText = it },
            label = { Text(stringResource(R.string.family_budget_household_tier)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        memberIds.forEach { memberId ->
            OutlinedTextField(
                value = allocations[memberId].orEmpty(),
                onValueChange = { entered -> allocations = allocations + (memberId to entered) },
                label = { Text(viewModel.displayName(memberId)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { household = CommonSplitRule.EQUAL }) {
                Text(stringResource(R.string.family_budget_split_equal))
            }
            TextButton(onClick = { household = CommonSplitRule.PROPORTIONAL }) {
                Text(stringResource(R.string.family_budget_split_proportional))
            }
        }

        saveProblem?.let {
            Text(
                text = stringResource(problemMessage(it)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            // Enabled only on something worth saving. The arithmetic accepts a pool of zero -- 0 sums to
            // 0 -- and the row would then be written and synced to every device, replacing "not set up
            // yet" with a real-looking budget of nothing.
            enabled = (typedPool?.signum() ?: 0) > 0,
            onClick = {
                val pool = typedPool ?: BigDecimal.ZERO
                val householdAmount = typedHousehold ?: BigDecimal.ZERO
                val parsed = allocations.mapValues { (_, text) -> parseAmount(text) ?: BigDecimal.ZERO }
                viewModel.save(pool, householdAmount, parsed, household)
            }
        ) {
            Text(stringResource(R.string.family_budget_save))
        }
    }
}

private fun problemMessage(problem: AllocationProblem): Int = when (problem) {
    AllocationProblem.POOL_NOT_POSITIVE -> R.string.family_budget_pool_not_positive
    AllocationProblem.POOL_EXCEEDS_TOTAL -> R.string.family_budget_pool_exceeds_total
    AllocationProblem.OVER_ALLOCATED -> R.string.family_budget_over_allocated
    AllocationProblem.UNDER_ALLOCATED -> R.string.family_budget_under_allocated
    AllocationProblem.NEGATIVE_ALLOCATION -> R.string.family_budget_negative_allocation
    AllocationProblem.NO_ALLOCATION -> R.string.family_budget_no_allocation
}

private fun assignmentStatusLabel(status: SpendAssignmentStatus): Int = when (status) {
    SpendAssignmentStatus.PENDING -> R.string.family_budget_request_pending
    SpendAssignmentStatus.ACCEPTED -> R.string.family_budget_request_accepted
    SpendAssignmentStatus.REJECTED -> R.string.family_budget_request_rejected
}

private fun tagLabel(tag: MemberTag): Int = when (tag) {
    MemberTag.TOO_EARLY -> R.string.family_tag_too_early
        MemberTag.NOT_ENOUGH_DATA -> R.string.family_tag_not_enough_data
    MemberTag.SUPER_SAVER -> R.string.family_tag_super_saver
    MemberTag.ON_PLAN -> R.string.family_tag_on_plan
    MemberTag.NEAR_LIMIT -> R.string.family_tag_near_limit
    MemberTag.OVER_PLAN -> R.string.family_tag_over_plan
    MemberTag.SPENDING_UP -> R.string.family_tag_spending_up
}

private fun parseAmount(text: String): BigDecimal? =
    text.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { BigDecimal(it).setScale(2, RoundingMode.HALF_EVEN) }.getOrNull()
    }

private fun BigDecimal.plainString(): String = setScale(2, RoundingMode.HALF_EVEN).toPlainString()

/**
 * Enough rows to be useful, few enough that a LazyColumn is not measuring a year of history.
 *
 * `take`, not `takeLast`: the query orders newest-first, so taking from the end would keep the oldest
 * twenty and hide the ones most likely to need an answer.
 */
private const val RECENT_LIMIT = 20

/**
 * A pool of nothing, for the editor before one exists.
 *
 * Only read for its (empty) allocations, so nothing here is ever shown as a real figure.
 */
private val EMPTY_BUDGET = FamilyBudget(
    total = BigDecimal.ZERO,
    householdTier = BigDecimal.ZERO,
    memberTier = BigDecimal.ZERO,
    householdSpent = BigDecimal.ZERO,
    householdRemaining = BigDecimal.ZERO,
    allocations = emptyList(),
)