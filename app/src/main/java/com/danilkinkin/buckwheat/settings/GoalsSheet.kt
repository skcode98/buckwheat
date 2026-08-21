package com.danilkinkin.buckwheat.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.danilkinkin.buckwheat.ui.colorSuccess
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.Month
import java.time.format.TextStyle
import java.util.*
import java.util.concurrent.TimeUnit

const val GOALS_SHEET = "goals"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsSheet(
    currency: ExtendCurrency = ExtendCurrency.none(),
    viewModel: GoalsViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.goalCompletedEvents.collect {
            appViewModel.confettiController.spawn(
                count = 80 to 120,
                ejectAngle = 120,
                ejectForceCoefficient = 6f,
                lifetime = 2000L to 5000L,
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.allocationErrors.collect { message ->
            appViewModel.showSnackbar(message)
        }
    }

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    var showCreateForm by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("") }
    var deadlineMillis by remember { mutableStateOf<Long?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    var showDatePickerDialog by remember { mutableStateOf(false) }
    var showAllocateDialog by remember { mutableStateOf<Long?>(null) }
    var editingGoalId by remember { mutableStateOf<Long?>(null) }
    var editNameText by remember { mutableStateOf("") }
    var editTargetText by remember { mutableStateOf("") }
    var editDeadlineMillis by remember { mutableStateOf<Long?>(null) }
    var showEditDatePickerDialog by remember { mutableStateOf(false) }
    var allocateAmount by remember { mutableStateOf("") }

    fun createGoal() {
        val target = targetText.toBigDecimalOrNull()
        if (target != null && nameText.isNotBlank()) {
            val deadline = deadlineMillis?.let { Date(it) }
            viewModel.addGoal(nameText, target, deadline)
            nameText = ""
            targetText = ""
            deadlineMillis = null
            showCreateForm = false
        }
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

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = navigationBarHeight),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (goals.isEmpty() && !showCreateForm) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_balance_wallet),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.goals_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                items(goals, key = { it.id }) { goal ->
                    GoalCard(
                        goal = goal,
                        currency = currency,
                        onAllocate = { showAllocateDialog = goal.id },
                        onDelete = { viewModel.deleteGoal(goal.id) },
                        onEdit = {
                            editingGoalId = goal.id
                            editNameText = goal.name
                            editTargetText = goal.targetAmount.toPlainString()
                            editDeadlineMillis = goal.deadline?.time
                        },
                        modifier = Modifier.animateItem(),
                    )
                }

                item(key = "create_form") {
                    AnimatedVisibility(
                        visible = showCreateForm,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        CreateGoalCard(
                            nameText = nameText,
                            onNameChange = { nameText = it },
                            targetText = targetText,
                            onTargetChange = { targetText = it },
                            deadlineText = deadlineMillis?.let { dateFormat.format(Date(it)) }
                                ?: stringResource(R.string.goal_deadline_hint),
                            hasDeadline = deadlineMillis != null,
                            onDeadlineClick = { showDatePickerDialog = true },
                            onClearDeadline = { deadlineMillis = null },
                            onConfirm = { createGoal() },
                            onCancel = {
                                showCreateForm = false
                                nameText = ""
                                targetText = ""
                                deadlineMillis = null
                            },
                        )
                    }
                }

                item(key = "add_button") {
                    AnimatedVisibility(
                        visible = !showCreateForm,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        AddCardButton(
                            title = stringResource(R.string.goals_title),
                            onClick = { showCreateForm = true },
                        )
                    }
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

    if (showDatePickerDialog) {
        val initCal = deadlineMillis?.let {
            Calendar.getInstance().apply { timeInMillis = it }
        } ?: Calendar.getInstance()
        val initMonth = initCal.get(Calendar.MONTH)
        val initYear = initCal.get(Calendar.YEAR)

        var selectedMonth by remember { mutableIntStateOf(initMonth) }
        var selectedYear by remember { mutableIntStateOf(initYear) }
        var monthExpanded by remember { mutableStateOf(false) }
        var yearExpanded by remember { mutableStateOf(false) }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val monthNames = Month.entries.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
        val years = (currentYear..currentYear + 10).toList()

        AlertDialog(
            onDismissRequest = { showDatePickerDialog = false },
            title = { Text(stringResource(R.string.change_date)) },
            text = {
                Column {
                    ExposedDropdownMenuBox(
                        expanded = monthExpanded,
                        onExpandedChange = { monthExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = monthNames[selectedMonth],
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.goal_deadline_hint)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = monthExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = monthExpanded,
                            onDismissRequest = { monthExpanded = false },
                        ) {
                            monthNames.forEachIndexed { index, name ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        selectedMonth = index
                                        monthExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = yearExpanded,
                        onExpandedChange = { yearExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedYear.toString(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Year") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = yearExpanded,
                            onDismissRequest = { yearExpanded = false },
                        ) {
                            years.forEach { year ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(year.toString()) },
                                    onClick = {
                                        selectedYear = year
                                        yearExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cal = Calendar.getInstance().apply {
                        set(selectedYear, selectedMonth, 1, 23, 59, 59)
                        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                        set(Calendar.MILLISECOND, 999)
                    }
                    deadlineMillis = cal.timeInMillis
                    showDatePickerDialog = false
                }) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (editingGoalId != null) {
        AlertDialog(
            onDismissRequest = {
                editingGoalId = null
                editNameText = ""
                editTargetText = ""
                editDeadlineMillis = null
            },
            title = { Text(stringResource(R.string.history_actions_edit)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editNameText,
                        onValueChange = { editNameText = it },
                        label = { Text(stringResource(R.string.goal_name_hint)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editTargetText,
                        onValueChange = {
                            editTargetText = it.filter { c -> c.isDigit() || c == '.' }
                        },
                        label = { Text(stringResource(R.string.goal_target_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = editDeadlineMillis?.let { dateFormat.format(Date(it)) }
                                ?: stringResource(R.string.goal_deadline_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showEditDatePickerDialog = true }
                                .padding(vertical = 8.dp),
                        )
                        if (editDeadlineMillis != null) {
                            IconButton(onClick = { editDeadlineMillis = null }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.cancel),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = editTargetText.toBigDecimalOrNull()
                        val goalId = editingGoalId
                        if (target != null && goalId != null && editNameText.isNotBlank()) {
                            viewModel.updateGoal(
                                goalId,
                                editNameText,
                                target,
                                editDeadlineMillis?.let { Date(it) },
                            )
                            editingGoalId = null
                            editNameText = ""
                            editTargetText = ""
                            editDeadlineMillis = null
                        }
                    },
                ) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        editingGoalId = null
                        editNameText = ""
                        editTargetText = ""
                        editDeadlineMillis = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showEditDatePickerDialog && editingGoalId != null) {
        val initCal = editDeadlineMillis?.let {
            Calendar.getInstance().apply { timeInMillis = it }
        } ?: Calendar.getInstance()
        val initMonth = initCal.get(Calendar.MONTH)
        val initYear = initCal.get(Calendar.YEAR)

        var selectedMonth by remember { mutableIntStateOf(initMonth) }
        var selectedYear by remember { mutableIntStateOf(initYear) }
        var monthExpanded by remember { mutableStateOf(false) }
        var yearExpanded by remember { mutableStateOf(false) }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val monthNames = Month.entries.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
        val years = (currentYear..currentYear + 10).toList()

        AlertDialog(
            onDismissRequest = { showEditDatePickerDialog = false },
            title = { Text(stringResource(R.string.change_date)) },
            text = {
                Column {
                    ExposedDropdownMenuBox(
                        expanded = monthExpanded,
                        onExpandedChange = { monthExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = monthNames[selectedMonth],
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.goal_deadline_hint)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = monthExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = monthExpanded,
                            onDismissRequest = { monthExpanded = false },
                        ) {
                            monthNames.forEachIndexed { index, name ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        selectedMonth = index
                                        monthExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = yearExpanded,
                        onExpandedChange = { yearExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedYear.toString(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Year") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = yearExpanded,
                            onDismissRequest = { yearExpanded = false },
                        ) {
                            years.forEach { year ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(year.toString()) },
                                    onClick = {
                                        selectedYear = year
                                        yearExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cal = Calendar.getInstance().apply {
                        set(selectedYear, selectedMonth, 1, 23, 59, 59)
                        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                        set(Calendar.MILLISECOND, 999)
                    }
                    editDeadlineMillis = cal.timeInMillis
                    showEditDatePickerDialog = false
                }) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDatePickerDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun GoalCard(
    goal: SavingsGoal,
    currency: ExtendCurrency,
    onAllocate: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val progress = if (goal.targetAmount > BigDecimal.ZERO) {
        goal.currentAmount
            .divide(goal.targetAmount, 2, RoundingMode.HALF_EVEN)
            .toFloat()
            .coerceIn(0f, 1f)
    } else 0f

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    val ringColor = when {
        goal.completed -> colorSuccess
        progress >= 1f -> colorSuccess
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoalRing(
                progress = progress,
                color = ringColor,
                modifier = Modifier.size(64.dp),
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = goal.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (goal.completed) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.goal_completed),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorSuccess,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(
                        R.string.goal_progress,
                        numberFormat(context, goal.currentAmount, currency).trim(),
                        numberFormat(context, goal.targetAmount, currency).trim(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )

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

                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${stringResource(R.string.goal_deadline_format, dateFormat.format(deadline))} · $deadlineLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = deadlineColor,
                    )
                    if (!goal.completed && daysRemaining > 0) {
                        val remaining = goal.targetAmount - goal.currentAmount
                        if (remaining > BigDecimal.ZERO) {
                            val weeksRemaining = TimeUnit.MILLISECONDS.toDays(deadlineTime - now).toDouble() / 7.0
                            val perWeek = if (weeksRemaining > 0) {
                                remaining.divide(
                                    BigDecimal.valueOf(weeksRemaining),
                                    2,
                                    RoundingMode.HALF_UP,
                                )
                            } else remaining
                            Text(
                                text = stringResource(R.string.goal_per_week, numberFormat(context, perWeek, currency).trim()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row {
                    if (!goal.completed) {
                        OutlinedButton(
                            onClick = onAllocate,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.goal_allocate),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_forever),
                            contentDescription = stringResource(R.string.goal_delete_desc),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                    }
                    if (!goal.completed) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit),
                                contentDescription = stringResource(R.string.history_actions_edit),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val strokeWidth = 6.dp
    val sweepAngle = progress * 360f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth()) {
            val stroke = strokeWidth.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (sweepAngle > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CreateGoalCard(
    nameText: String,
    onNameChange: (String) -> Unit,
    targetText: String,
    onTargetChange: (String) -> Unit,
    deadlineText: String,
    hasDeadline: Boolean,
    onDeadlineClick: () -> Unit,
    onClearDeadline: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = onNameChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.goal_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = targetText,
                    onValueChange = onTargetChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.goal_target_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = deadlineText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasDeadline) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onDeadlineClick),
                )
                if (hasDeadline) {
                    IconButton(
                        onClick = onClearDeadline,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.goals_title))
                }
            }
        }
    }
}

@Composable
private fun AddCardButton(
    title: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}
