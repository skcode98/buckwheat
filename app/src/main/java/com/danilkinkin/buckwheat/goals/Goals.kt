package com.danilkinkin.buckwheat.goals

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.entities.Goal
import com.danilkinkin.buckwheat.util.prettyDate
import java.math.BigDecimal
import java.math.RoundingMode

const val GOALS_SHEET = "goals"

@Composable
fun Goals(
    goalsViewModel: GoalsViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val goals by goalsViewModel.goals.collectAsState()
    var showEditor by remember { mutableStateOf(false) }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { onClose() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.weight(1F))
                Text(
                    text = stringResource(R.string.goals_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1F))
                Spacer(Modifier.width(48.dp))
            }
            if (goals.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_goals),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(goals, key = { it.id }) { goal ->
                        GoalCard(
                            goal = goal,
                            onContribute = { amount ->
                                val willComplete = (goal.savedAmount + amount) >= goal.targetAmount
                                goalsViewModel.contributeToGoal(goal.id, amount)
                                if (willComplete) {
                                    appViewModel.showSnackbar("Goal \"${goal.name}\" completed!")
                                }
                            },
                            onDelete = {
                                goalsViewModel.deleteGoal(goal.id)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                FloatingActionButton(
                    onClick = { showEditor = true },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }
    }

    if (showEditor) {
        GoalEditor(
            onDismiss = { showEditor = false },
            onSave = { name, target, deadline ->
                goalsViewModel.addGoal(name, target, deadline)
                showEditor = false
            },
        )
    }
}

@Composable
private fun GoalCard(
    goal: Goal,
    onContribute: (BigDecimal) -> Unit,
    onDelete: () -> Unit,
) {
    val progress = goal.progress()
    val remaining = goal.targetAmount - goal.savedAmount
    var showContributeDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { showContributeDialog = true },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = goal.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_forever),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${formatAmount(goal.savedAmount)} / ${formatAmount(goal.targetAmount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val progressPercent = if (goal.targetAmount > BigDecimal.ZERO) {
                    (goal.savedAmount / goal.targetAmount * BigDecimal(100))
                        .setScale(0, RoundingMode.DOWN).toInt()
                } else 0
                Text(
                    text = "$progressPercent%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (goal.deadline != null) {
                    Text(
                        text = prettyDate(goal.deadline, showTime = false),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                strokeCap = StrokeCap.Round,
                trackColor = MaterialTheme.colorScheme.surface,
                color = if (goal.completed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!goal.completed && remaining > BigDecimal.ZERO) {
                    Text(
                        text = "${formatAmount(remaining)} ${stringResource(R.string.goal_remaining)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showContributeDialog && !goal.completed) {
        ContributeDialog(
            goalName = goal.name,
            onDismiss = { showContributeDialog = false },
            onContribute = { amount ->
                onContribute(amount)
                showContributeDialog = false
            },
        )
    }
}

@Composable
private fun ContributeDialog(
    goalName: String,
    onDismiss: () -> Unit,
    onContribute: (BigDecimal) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contribute_to_goal, goalName)) },
        text = {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(R.string.amount_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = amountText.toBigDecimalOrNull()
                    if (amount != null && amount > BigDecimal.ZERO) {
                        onContribute(amount)
                    }
                },
                enabled = amountText.toBigDecimalOrNull() != null
                        && (amountText.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO,
            ) {
                Text(stringResource(R.string.accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun formatAmount(amount: BigDecimal): String {
    return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString()
}
