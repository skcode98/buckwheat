package com.danilkinkin.buckwheat.settings

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*

const val GOALS_SHEET = "goals"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsSheet(
    currency: ExtendCurrency = ExtendCurrency.none(),
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    var nameText by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("") }
    var showAllocateDialog by remember { mutableStateOf<Long?>(null) }
    var allocateAmount by remember { mutableStateOf("") }

    var deadlineMillis by remember { mutableStateOf<Long?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    fun createGoal() {
        val target = targetText.toBigDecimalOrNull()
        if (target != null && nameText.isNotBlank()) {
            val deadline = deadlineMillis?.let { Date(it) }
            viewModel.addGoal(nameText, target, deadline)
            nameText = ""
            targetText = ""
            deadlineMillis = null
        }
    }

    fun showDatePicker() {
        val cal = Calendar.getInstance()
        deadlineMillis?.let { cal.timeInMillis = it }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val selected = Calendar.getInstance().apply {
                    set(year, month, day, 23, 59, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                deadlineMillis = selected.timeInMillis
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onDone = { showDatePicker() }
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { createGoal() },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val deadlineText = deadlineMillis?.let { dateFormat.format(Date(it)) }
                    ?: stringResource(R.string.goal_deadline_hint)

                Text(
                    text = deadlineText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (deadlineMillis != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showDatePicker() },
                )
                if (deadlineMillis != null) {
                    IconButton(
                        onClick = { deadlineMillis = null },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
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
                        currency = currency,
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
    currency: ExtendCurrency,
    onAllocate: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val progress = if (goal.targetAmount > BigDecimal.ZERO) {
        goal.currentAmount
            .divide(goal.targetAmount, 2, RoundingMode.HALF_EVEN)
            .toFloat()
            .coerceIn(0f, 1f)
    } else 0f

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

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
                text = stringResource(
                    R.string.goal_progress,
                    numberFormat(context, goal.currentAmount, currency).trim(),
                    numberFormat(context, goal.targetAmount, currency).trim(),
                ),
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
        goal.deadline?.let { deadline ->
            val now = System.currentTimeMillis()
            val deadlineTime = deadline.time
            val daysRemaining = ((deadlineTime - now) / (1000 * 60 * 60 * 24)).toInt()
            val deadlineColor = if (daysRemaining < 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            }
            val deadlineLabel = if (daysRemaining < 0) {
                stringResource(R.string.goal_overdue, -daysRemaining)
            } else {
                stringResource(R.string.goal_days_remaining, daysRemaining)
            }
            Text(
                text = "${stringResource(R.string.goal_deadline_format, dateFormat.format(deadline))} · $deadlineLabel",
                style = MaterialTheme.typography.labelSmall,
                color = deadlineColor,
            )
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
