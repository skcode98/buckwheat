package com.danilkinkin.buckwheat.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.entities.CommonSplitRule
import com.danilkinkin.buckwheat.data.entities.SpendAssignment
import com.danilkinkin.buckwheat.data.entities.SpendAssignmentStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date

const val FAMILY_BUDGET_SHEET = "familyBudget"

@Composable
fun FamilyBudgetSheet(viewModel: FamilyBudgetViewModel = hiltViewModel()) {
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val isHead by viewModel.isHead.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle(initialValue = null)
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val householdRows by viewModel.householdRows.collectAsStateWithLifecycle()
    val householdDetailVisible by viewModel.householdDetailVisibleToAll.collectAsStateWithLifecycle()
    val assignmentsViewModel: SpendAssignmentsViewModel = hiltViewModel()
    val assignments by assignmentsViewModel.assignments.collectAsStateWithLifecycle()
    val myPending by assignmentsViewModel.me.collectAsStateWithLifecycle()

    if (session == null) {
        Message(stringResource(R.string.family_budget_not_enrolled))
        return
    }

    val current = budget
    if (current == null) {
        Message(stringResource(R.string.family_budget_no_pool))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(isHead) }

        item { SummarySection(viewModel) }

        item {
            MoneyRow(stringResource(R.string.family_budget_pool), current.total)
            MoneyRow(stringResource(R.string.family_budget_household_tier), current.householdTier)
            MoneyRow(stringResource(R.string.family_budget_household_spent), current.householdSpent)
            MoneyRow(stringResource(R.string.family_budget_left), current.remaining)
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
            )
        }

        item {
            Text(
                text = stringResource(R.string.family_budget_requests),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        val waitingOnMe = assignments.filter { it.targetMemberId == myPending && !it.assignmentStatus.isResolved }
        if (waitingOnMe.isNotEmpty()) {
            items(waitingOnMe, key = { "mine-${it.id}" }) { assignment ->
                AssignmentRow(
                    assignment = assignment,
                    title = stringResource(
                        R.string.family_budget_request_from,
                        viewModel.displayName(assignment.createdByMemberId),
                    ),
                    accept = { assignmentsViewModel.answer(assignment, true) },
                    reject = { assignmentsViewModel.answer(assignment, false) },
                    canAnswer = assignmentsViewModel.canAnswer(assignment),
                )
            }
        } else {
            item { Text(stringResource(R.string.family_budget_no_requests)) }
        }

        val mine = assignments.filter { it.createdByMemberId == myPending }
        if (mine.isNotEmpty()) {
            items(mine, key = { "sent-${it.id}" }) { assignment ->
                AssignmentRow(
                    assignment = assignment,
                    title = stringResource(
                        R.string.family_budget_request_to,
                        viewModel.displayName(assignment.targetMemberId),
                    ),
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

    LaunchedEffect(Unit) { viewModel.loadSummary() }

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

@Composable
private fun Message(text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
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
private fun MoneyRow(label: String, amount: BigDecimal) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = amount.plainString(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MemberAllocationRow(name: String, tag: MemberTag?, allocation: MemberAllocation) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(text = allocation.remaining.plainString(), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = stringResource(
                R.string.family_budget_allocation_detail,
                allocation.allocation.plainString(),
                allocation.spent.plainString(),
                allocation.shareOfHousehold.plainString(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        // Only ever the member's own positive labels. A person is not shown the app's opinion of them.
        tag?.takeIf { it.isSelfVisible }?.let { shown ->
            AssistChip(
                onClick = {},
                label = { Text(stringResource(tagLabel(shown))) },
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
            MoneyRow(stringResource(R.string.family_budget_household_spent), householdSpent)

            if (showDetail) {
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.family_budget_household_empty))
                }
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = row.comment.ifBlank { stringResource(R.string.family_budget_no_comment) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(text = row.value.plainString(), style = MaterialTheme.typography.bodyMedium)
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
                    assignment.amount.plainString(),
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

    var target by remember { mutableStateOf(memberIds.firstOrNull().orEmpty()) }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
) {
    if (!isHead) return

    val saveProblem by viewModel.saveProblem.collectAsStateWithLifecycle()

    var poolText by remember { mutableStateOf(total.plainString()) }
    var householdText by remember { mutableStateOf(householdTier.plainString()) }
    var household by remember { mutableStateOf(CommonSplitRule.EQUAL) }
    var allocations by remember { mutableStateOf(memberIds.associateWith { "" }) }

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