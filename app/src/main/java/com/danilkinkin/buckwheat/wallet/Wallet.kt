package com.danilkinkin.buckwheat.wallet

import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.base.Divider
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.data.RestedBudgetDistributionMethod
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.di.TUTORS
import com.danilkinkin.buckwheat.analytics.ANALYTICS_SHEET
import com.danilkinkin.buckwheat.settings.GOALS_SHEET
import com.danilkinkin.buckwheat.settings.GoalsViewModel
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*


const val WALLET_SHEET = "wallet"

@Composable
fun Wallet(
    forceChange: Boolean = false,
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
    appViewModel: AppViewModel = hiltViewModel(),
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    goalsViewModel: GoalsViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current

    var budgetCache by remember { mutableStateOf(spendsViewModel.budget.value ?: BigDecimal.ZERO) }
    val budget by spendsViewModel.budget.collectAsStateWithLifecycle()
    val spent by spendsViewModel.spent.collectAsStateWithLifecycle()
    val spentFromDailyBudget by spendsViewModel.spentFromDailyBudget.collectAsStateWithLifecycle()
    val startPeriodDate by spendsViewModel.startPeriodDate.collectAsStateWithLifecycle()
    val finishPeriodDate by spendsViewModel.finishPeriodDate.collectAsStateWithLifecycle()
    val dateToValue = remember { mutableStateOf(finishPeriodDate) }
    val startDateToValue = remember { mutableStateOf<Date?>(startPeriodDate) }
    val currency by spendsViewModel.currency.collectAsStateWithLifecycle()
    val spends by spendsViewModel.periodSpends.collectAsStateWithLifecycle()
    val restedBudgetDistributionMethod by spendsViewModel.restedBudgetDistributionMethod.collectAsStateWithLifecycle()

    val restBudget =
        (budgetCache - spent - spentFromDailyBudget)

    val openConfirmFinishBudgetDialog = remember { mutableStateOf(false) }

    if (spends === null) return

    val navigationBarHeight = LocalWindowInsets.current.calculateBottomPadding()
        .coerceAtLeast(16.dp)

    val isChange = (
            budgetCache != budget
                    || startDateToValue.value != startPeriodDate
                    || dateToValue.value != finishPeriodDate
            )

    val finishDate = finishPeriodDate
    var isEdit by remember(startPeriodDate, finishPeriodDate, forceChange) {
        mutableStateOf(
            (finishDate != null && isSameDay(
                startPeriodDate.time,
                finishDate.time
            ))
                    || forceChange
        )
    }

    val offset = with(LocalDensity.current) { 50.dp.toPx().toInt() }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            val days = dateToValue.value?.let { countDaysToToday(it) } ?: 0

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!forceChange && isEdit) {
                    IconButton(
                        onClick = { isEdit = false },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Spacer(Modifier.weight(1F))
                Text(
                    text = if (isChange || isEdit) {
                        stringResource(R.string.wallet_edit_title)
                    } else {
                        stringResource(R.string.wallet_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1F))
                if (!isEdit) {
                    IconButton(
                        onClick = { isEdit = true },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = navigationBarHeight)
            ) {
                AnimatedContent(
                    targetState = isEdit,
                    transitionSpec = {
                        if (targetState && !initialState) {
                            (slideInHorizontally(
                                tween(durationMillis = 150)
                            ) { offset } + fadeIn(
                                tween(durationMillis = 150)
                            )).togetherWith(slideOutHorizontally(
                                tween(durationMillis = 150)
                            ) { -offset } + fadeOut(
                                tween(durationMillis = 150)
                            ))
                        } else {
                            (slideInHorizontally(
                                tween(durationMillis = 150)
                            ) { -offset } + fadeIn(
                                tween(durationMillis = 150)
                            )).togetherWith(slideOutHorizontally(
                                tween(durationMillis = 150)
                            ) { offset } + fadeOut(
                                tween(durationMillis = 150)
                            ))
                        }.using(
                            SizeTransform(
                                clip = true,
                                sizeAnimationSpec = { _, _ -> tween(durationMillis = 350) }
                            )
                        )
                    }
                ) { targetIsEdit ->
                    if (targetIsEdit) {
                        BudgetConstructor(
                            forceChange = forceChange,
                            onChange = { newBudget, startDate, finishDate ->
                                budgetCache = newBudget
                                startDateToValue.value = startDate
                                dateToValue.value = finishDate
                            }
                        )
                    } else {
                        BudgetSummary(
                            onEdit = {
                                isEdit = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        )
                    }
                }
                val activeGoals by goalsViewModel.goals.collectAsStateWithLifecycle()
                GoalProgressCard(
                    goals = activeGoals,
                    currency = currency,
                    visible = !isChange && !isEdit && activeGoals.any { !it.completed },
                    onClick = { appViewModel.openSheet(PathState(GOALS_SHEET)) },
                )
                ButtonRow(
                    icon = painterResource(R.drawable.ic_directions),
                    text = stringResource(R.string.rest_label),
                    onClick = {
                        appViewModel.openSheet(PathState(DEFAULT_RECALC_BUDGET_CHOOSER))
                    },
                    endCaption = when (restedBudgetDistributionMethod) {
                        RestedBudgetDistributionMethod.ASK, null -> stringResource(
                            R.string.always_ask
                        )
                        RestedBudgetDistributionMethod.REST -> stringResource(
                            R.string.method_split_to_rest_days_title
                        )
                        RestedBudgetDistributionMethod.ADD_TODAY -> stringResource(
                            R.string.method_add_to_current_day_title
                        )
                    },
                )
                ButtonRow(
                    icon = painterResource(R.drawable.ic_currency),
                    text = stringResource(R.string.in_currency_label),
                    onClick = {
                        appViewModel.openSheet(PathState(CURRENCY_EDITOR))
                    },
                    endCaption = when (val c = currency) {
                        null -> ""
                        else -> when (c.type) {
                            ExtendCurrency.Type.FROM_LIST -> if (c.value != null) "${
                                Currency.getInstance(
                                    c.value
                                ).displayName.titleCase()
                            } (${
                                Currency.getInstance(
                                    c.value
                                ).symbol
                            })" else ""
                            ExtendCurrency.Type.CUSTOM -> c.value ?: ""
                            else -> ""
                        }
                    },
                )
                AnimatedVisibility(
                    visible = !isChange && !isEdit,
                    enter = fadeIn(
                        tween(durationMillis = 350)
                    ) + expandVertically(
                        expandFrom = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 350)
                    ),
                    exit = fadeOut(
                        tween(durationMillis = 350)
                    ) + shrinkVertically(
                        shrinkTowards = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 350)
                    ),
                ) {
                    Column {
                        if (spends?.isNotEmpty() == true) {
                            ButtonRow(
                                icon = painterResource(R.drawable.ic_analytics),
                                text = stringResource(R.string.view_analytics),
                                onClick = {
                                    appViewModel.openSheet(PathState(ANALYTICS_SHEET))
                                }
                            )
                        }

                        val exportCSVLaunch = rememberExportCSV(
                            activityResultRegistryOwner = activityResultRegistryOwner
                        )

                        ButtonRow(
                            icon = painterResource(R.drawable.ic_file_download),
                            text = stringResource(R.string.export_to_csv),
                            onClick = { exportCSVLaunch() }
                        )

                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.error
                        ) {
                            ButtonRow(
                                icon = painterResource(R.drawable.ic_close),
                                text = stringResource(R.string.finish_early),
                                onClick = {
                                    openConfirmFinishBudgetDialog.value = true
                                }
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = isChange || isEdit,
                    enter = fadeIn(
                        tween(durationMillis = 350)
                    ) + expandVertically(
                        expandFrom = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 350)
                    ),
                    exit = fadeOut(
                        tween(durationMillis = 350)
                    ) + shrinkVertically(
                        shrinkTowards = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 350)
                    ),
                ) {
                    Column {
                        Divider()
                        Total(
                            budget = budgetCache,
                            restBudget = restBudget,
                            days = days,
                            currency = currency ?: ExtendCurrency.none(),
                        )
                        Button(
                            onClick = {
                                val currentSpends = spends
                                val currentDateToValue = dateToValue.value
                                val currentCurrency = currency
                                if (currentDateToValue != null) {
                                    if (currentCurrency != null) {
                                        spendsViewModel.changeDisplayCurrency(currentCurrency)
                                    }

                                    val newStartDate = startDateToValue.value
                                        ?.takeIf { it.time > 0L }

                                    if (currentSpends?.isNotEmpty() == true && !forceChange) {
                                        spendsViewModel.changeBudget(
                                            budgetCache,
                                            currentDateToValue,
                                            newStartDate,
                                        )
                                    } else {
                                        spendsViewModel.setBudget(
                                            budgetCache,
                                            currentDateToValue,
                                            newStartDate,
                                        )
                                        appViewModel.activateTutorial(TUTORS.OPEN_WALLET)
                                    }
                                }

                                onClose()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(60.dp)
                                .padding(horizontal = 16.dp),
                            enabled = dateToValue.value?.let { countDaysToToday(it) > 0 } == true && budgetCache > BigDecimal(
                                0
                            )
                        ) {
                            Text(
                                text = if (spends?.isNotEmpty() == true && !forceChange) {
                                    stringResource(R.string.change_budget)
                                } else {
                                    stringResource(R.string.apply)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_forward),
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        }
    }

    if (openConfirmFinishBudgetDialog.value) {
        ConfirmFinishEarlyDialog(
            onConfirm = {
                spendsViewModel.finishBudget()

                onClose()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onClose = { openConfirmFinishBudgetDialog.value = false },
        )
    }
}

@Composable
private fun GoalProgressCard(
    goals: List<SavingsGoal>,
    currency: ExtendCurrency,
    visible: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(350)) + expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = tween(350),
        ),
        exit = fadeOut(tween(350)) + shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(350),
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { onClick() },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.goals_progress_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.goals_active_count,
                            goals.count { !it.completed },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                val activeGoals = goals.filter { !it.completed }.take(3)
                activeGoals.forEach { goal ->
                    GoalMiniRow(goal = goal, currency = currency)
                    if (goal != activeGoals.last()) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalMiniRow(
    goal: SavingsGoal,
    currency: ExtendCurrency,
) {
    val context = LocalContext.current
    val progress = if (goal.targetAmount > BigDecimal.ZERO) {
        goal.currentAmount
            .divide(goal.targetAmount, 2, RoundingMode.HALF_EVEN)
            .toFloat()
            .coerceIn(0f, 1f)
    } else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = goal.name,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.goal_progress,
                    numberFormat(context, goal.currentAmount, currency).trim(),
                    numberFormat(context, goal.targetAmount, currency).trim(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview
@Composable
fun PreviewWallet() {
    BuckwheatTheme {
        Wallet()
    }
}
