package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import java.math.BigDecimal

const val GOALS_SHEET = "goals"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsSheet(
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val goals by viewModel.goals.observeAsState(emptyList())

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    var nameText by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("") }
    var showAllocateDialog by remember { mutableStateOf<Long?>(null) }
    var allocateAmount by remember { mutableStateOf("") }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.goals_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.goal_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.goal_target_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val target = targetText.toBigDecimalOrNull()
                            if (target != null && nameText.isNotBlank()) {
                                viewModel.addGoal(nameText, target)
                                nameText = ""
                                targetText = ""
                            }
                        }
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        val target = targetText.toBigDecimalOrNull()
                        if (target != null && nameText.isNotBlank()) {
                            viewModel.addGoal(nameText, target)
                            nameText = ""
                            targetText = ""
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = navigationBarHeight),
                contentPadding = PaddingValues(horizontal = 24.dp),
            ) {
                items(goals) { goal ->
                    GoalRow(
                        goal = goal,
                        onAllocate = { showAllocateDialog = goal.id },
                        onDelete = { viewModel.deleteGoal(goal.id) },
                    )
                }
            }
        }
    }

    if (showAllocateDialog != null) {
        AlertDialog(
            onDismissRequest = {
                showAllocateDialog = null
                allocateAmount = ""
            },
            title = { Text(stringResource(R.string.goal_allocate_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.goal_allocate_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = allocateAmount,
                        onValueChange = { allocateAmount = it },
                        placeholder = { Text(stringResource(R.string.goal_target_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val amount = allocateAmount.toBigDecimalOrNull()
                        val goalId = showAllocateDialog
                        if (amount != null && goalId != null) {
                            viewModel.allocateToGoal(goalId, amount)
                            showAllocateDialog = null
                            allocateAmount = ""
                        }
                    },
                ) {
                    Text(stringResource(R.string.goal_allocate))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAllocateDialog = null
                        allocateAmount = ""
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun GoalRow(
    goal: SavingsGoal,
    onAllocate: () -> Unit,
    onDelete: () -> Unit,
) {
    val progress = if (goal.targetAmount > BigDecimal.ZERO) {
        (goal.currentAmount / goal.targetAmount).toFloat().coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = goal.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (goal.completed) {
                Text(
                    text = stringResource(R.string.goal_completed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.goal_progress, goal.currentAmount, goal.targetAmount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            if (!goal.completed) {
                TextButton(onClick = onAllocate) {
                    Text(stringResource(R.string.goal_allocate))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_forever),
                    contentDescription = stringResource(R.string.goal_delete_desc),
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewGoals() {
    BuckwheatTheme {
        GoalsSheet()
    }
}
