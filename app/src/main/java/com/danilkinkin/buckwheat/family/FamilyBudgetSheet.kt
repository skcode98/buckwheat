package com.danilkinkin.buckwheat.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import java.math.BigDecimal
import java.math.RoundingMode

const val FAMILY_BUDGET_SHEET = "familyBudget"

@Composable
fun FamilyBudgetSheet(viewModel: FamilyBudgetViewModel = hiltViewModel()) {
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val isHead by viewModel.isHead.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle(initialValue = null)

    if (session == null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.family_budget_not_enrolled),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val current = budget
    if (current == null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.family_budget_no_period),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.family_budget_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        item {
            MoneyRow(
                label = stringResource(R.string.family_budget_pool),
                amount = current.total,
            )
            MoneyRow(
                label = stringResource(R.string.family_budget_household_tier),
                amount = current.householdTier,
            )
            MoneyRow(
                label = stringResource(R.string.family_budget_household_spent),
                amount = current.householdSpent,
            )
            MoneyRow(
                label = stringResource(R.string.family_budget_left),
                amount = current.remaining,
            )
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
                allocation = allocation,
            )
        }

        if (current.allocations.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.family_budget_no_allocations),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
private fun MemberAllocationRow(name: String, allocation: MemberAllocation) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
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
    }
}

/**
 * The head's split editor.
 *
 * Only rendered for the owner, and it refuses to save a partial split rather than rounding the
 * difference into the last member: silently absorbing a few hundred into somebody's slice is how a
 * member ends up quietly over budget with nothing on screen explaining it.
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
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = householdText,
            onValueChange = { householdText = it },
            label = { Text(stringResource(R.string.family_budget_household_tier)) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        memberIds.forEach { memberId ->
            OutlinedTextField(
                value = allocations[memberId].orEmpty(),
                onValueChange = { entered ->
                    allocations = allocations + (memberId to entered)
                },
                label = { Text(viewModel.displayName(memberId)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
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

private fun parseAmount(text: String): BigDecimal? =
    text.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { BigDecimal(it).setScale(2, RoundingMode.HALF_EVEN) }.getOrNull()
    }

private fun BigDecimal.plainString(): String = setScale(2, RoundingMode.HALF_EVEN).toPlainString()