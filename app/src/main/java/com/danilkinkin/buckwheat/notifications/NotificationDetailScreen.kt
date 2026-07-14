package com.danilkinkin.buckwheat.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.Goal
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.goals.GoalsViewModel
import com.danilkinkin.buckwheat.ui.colorEditor
import com.danilkinkin.buckwheat.util.isSameDay
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

const val EXTRA_NOTIFICATION_TYPE = "notification_type"

@Composable
fun NotificationDetailScreen(
    type: NotificationType,
    onClose: () -> Unit,
    spendsViewModel: SpendsViewModel = viewModel(),
    goalsViewModel: GoalsViewModel = viewModel(),
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
                .statusBarsPadding(),
        ) {
            Header(type = type, onClose = onClose)

            when (type) {
                NotificationType.DAILY_SPEND_OVERVIEW -> DailyOverviewContent(spendsViewModel)
                NotificationType.WEEKLY_OVERVIEW -> WeeklyOverviewContent(spendsViewModel)
                NotificationType.MONTHLY_EXPORT -> MonthlyExportContent(spendsViewModel)
                NotificationType.MONTHLY_OVERVIEW -> MonthlyOverviewContent(spendsViewModel)
                NotificationType.FACTS_INSIGHTS -> FactsInsightsContent(spendsViewModel)
                NotificationType.GOALS_REMINDER -> GoalsReminderContent(goalsViewModel, spendsViewModel)
            }
        }
    }
}

@Composable
private fun Header(type: NotificationType, onClose: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(getIconForType(type)),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = type.getChannelName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = type.getNotificationTitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Close",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun DailyOverviewContent(spendsViewModel: SpendsViewModel) {
    val spends by spendsViewModel.spends.observeAsState(emptyList())
    val dailyBudget by spendsViewModel.dailyBudget.observeAsState(BigDecimal.ZERO)
    val spentFromDaily by spendsViewModel.spentFromDailyBudget.observeAsState(BigDecimal.ZERO)
    val currency by spendsViewModel.currency.observeAsState()
    val budget by spendsViewModel.budget.observeAsState(BigDecimal.ZERO)
    val categories by spendsViewModel.categories.observeAsState(emptyList())

    val today = LocalDate.now()
    val todayTxs = remember(spends) {
        spends.filter { isSameDay(it.date, Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant())) }
    }
    val todayTotal = remember(todayTxs) {
        todayTxs.filter { it.type == TransactionType.SPENT }.sumOf { it.value }
    }
    val remaining = dailyBudget - spentFromDaily
    val currencySymbol = currency?.value ?: "$"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatCard(
                label = "Today's Spending",
                value = "$currencySymbol$todayTotal",
                color = MaterialTheme.colorScheme.error,
            )
        }
        item {
            StatCard(
                label = "Daily Budget",
                value = "$currencySymbol$dailyBudget",
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            StatCard(
                label = if (remaining >= BigDecimal.ZERO) "Remaining Today" else "Overspent",
                value = "$currencySymbol${remaining.abs()}",
                color = if (remaining >= BigDecimal.ZERO) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
            )
        }
        item {
            StatCard(
                label = "Period Budget Remaining",
                value = "$currencySymbol${budget - todayTotal.coerceAtMost(budget)}",
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (todayTxs.isNotEmpty()) {
            item {
                Text(
                    text = "Today's Transactions",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(todayTxs.take(10)) { tx ->
                val catName = categories.find { it.id == tx.categoryId }?.name ?: ""
                TransactionRow(tx, currencySymbol, catName)
            }
        }
    }
}

@Composable
private fun WeeklyOverviewContent(spendsViewModel: SpendsViewModel) {
    val spends by spendsViewModel.spends.observeAsState(emptyList())
    val dailyBudget by spendsViewModel.dailyBudget.observeAsState(BigDecimal.ZERO)
    val currency by spendsViewModel.currency.observeAsState()

    val today = LocalDate.now()
    val startOfWeek = today.with(DayOfWeek.MONDAY)
    val weekTxs = remember(spends) {
        spends.filter {
            val d = it.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            !d.isBefore(startOfWeek) && !d.isAfter(today)
        }
    }
    val weekTotal = remember(weekTxs) {
        weekTxs.filter { it.type == TransactionType.SPENT }.sumOf { it.value }
    }
    val dailyTotals = remember(weekTxs) {
        (0 until ChronoUnit.DAYS.between(startOfWeek, today).toInt() + 1).map { offset ->
            val day = startOfWeek.plusDays(offset.toLong())
            val total = weekTxs.filter {
                isSameDay(it.date, Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()))
                    && it.type == TransactionType.SPENT
            }.sumOf { it.value }
            day to total
        }
    }
    val currencySymbol = currency?.value ?: "$"
    val weekBudget = dailyBudget * dailyTotals.size.toBigDecimal()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatCard(
                label = "Week Total",
                value = "$currencySymbol$weekTotal",
                color = MaterialTheme.colorScheme.error,
                large = true,
            )
        }
        item {
            StatCard(
                label = "Week Budget",
                value = "$currencySymbol$weekBudget",
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            Text(
                text = "Daily Breakdown",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        items(dailyTotals) { (day, total) ->
            val dayName = day.dayOfWeek.name.take(3).lowercase()
                .replaceFirstChar { it.uppercase() }
            val isOver = total > dailyBudget
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOver) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$dayName ${day.dayOfMonth}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "$currencySymbol$total",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (isOver) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthlyExportContent(spendsViewModel: SpendsViewModel) {
    val spends by spendsViewModel.spends.observeAsState(emptyList())
    val budget by spendsViewModel.budget.observeAsState(BigDecimal.ZERO)
    val spent by spendsViewModel.spent.observeAsState(BigDecimal.ZERO)
    val currency by spendsViewModel.currency.observeAsState()
    val currencySymbol = currency?.value ?: "$"
    val remaining = budget - spent

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatCard("Period Budget", "$currencySymbol$budget", MaterialTheme.colorScheme.primary, large = true) }
        item { StatCard("Total Spent", "$currencySymbol$spent", MaterialTheme.colorScheme.error) }
        item {
            StatCard(
                label = "Remaining",
                value = "$currencySymbol$remaining",
                color = if (remaining >= BigDecimal.ZERO) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorEditor),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file_download),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Export is available in Settings",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthlyOverviewContent(spendsViewModel: SpendsViewModel) {
    val spends by spendsViewModel.spends.observeAsState(emptyList())
    val budget by spendsViewModel.budget.observeAsState(BigDecimal.ZERO)
    val spent by spendsViewModel.spent.observeAsState(BigDecimal.ZERO)
    val dailyBudget by spendsViewModel.dailyBudget.observeAsState(BigDecimal.ZERO)
    val currency by spendsViewModel.currency.observeAsState()
    val categories by spendsViewModel.categories.observeAsState(emptyList())
    val currencySymbol = currency?.value ?: "$"
    val remaining = budget - spent

    val categorySpends = remember(spends, categories) {
        categories.map { cat ->
            val total = spends.filter {
                it.type == TransactionType.SPENT && it.categoryId == cat.id
            }.sumOf { it.value }
            cat.name to total
        }.filter { it.second > BigDecimal.ZERO }
            .sortedByDescending { it.second }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatCard("Period Total", "$currencySymbol$spent", MaterialTheme.colorScheme.error, large = true) }
        item { StatCard("Budget", "$currencySymbol$budget", MaterialTheme.colorScheme.primary) }
        item { StatCard("Daily Budget", "$currencySymbol$dailyBudget", MaterialTheme.colorScheme.secondary) }
        item {
            StatCard(
                label = "Remaining",
                value = "$currencySymbol$remaining",
                color = if (remaining >= BigDecimal.ZERO) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
            )
        }
        if (categorySpends.isNotEmpty()) {
            item {
                Text(
                    text = "Category Breakdown",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(categorySpends) { (name, total) ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$currencySymbol$total",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FactsInsightsContent(spendsViewModel: SpendsViewModel) {
    val spends by spendsViewModel.spends.observeAsState(emptyList())
    val dailyBudget by spendsViewModel.dailyBudget.observeAsState(BigDecimal.ZERO)
    val currency by spendsViewModel.currency.observeAsState()
    val currencySymbol = currency?.value ?: "$"

    val spentTxs = remember(spends) { spends.filter { it.type == TransactionType.SPENT } }

    val streak = remember(spentTxs, dailyBudget) {
        if (dailyBudget <= BigDecimal.ZERO) 0 else {
            val sorted = spentTxs.sortedByDescending { it.date.time }
            if (sorted.isEmpty()) return@remember 0
            val cal = java.util.Calendar.getInstance()
            val today = com.danilkinkin.buckwheat.util.roundToDay(cal.time)
            var streak = 0
            for (tx in sorted) {
                val txDay = com.danilkinkin.buckwheat.util.roundToDay(tx.date)
                if (txDay == today || txDay.before(today)) {
                    if (txDay.time == today.time || txDay.time == today.time - 86400000L * streak) {
                        streak++
                    } else break
                }
            }
            streak
        }
    }

    val totalSpent = remember(spentTxs) { spentTxs.sumOf { it.value } }
    val avgPerDay = remember(spentTxs, totalSpent) {
        if (spentTxs.isEmpty()) BigDecimal.ZERO
        else totalSpent / spentTxs.size.toBigDecimal()
    }

    val topCategory = remember(spends) {
        spends.filter { it.type == TransactionType.SPENT && it.categoryId != null }
            .groupBy { it.categoryId }
            .maxByOrNull { it.value.sumOf { tx -> tx.value } }
            ?.value?.firstOrNull()?.let {
                spendsViewModel.categories.value?.find { cat -> cat.id == it.categoryId }?.name
            } ?: "Uncategorized"
    }

    val topCategoryTotal = remember(spends) {
        spends.filter { it.type == TransactionType.SPENT && it.categoryId != null }
            .groupBy { it.categoryId }
            .maxByOrNull { it.value.sumOf { tx -> tx.value } }
            ?.value?.sumOf { it.value } ?: BigDecimal.ZERO
    }

    val today = LocalDate.now()
    val dayOfWeekCounts = remember(spentTxs) {
        spentTxs.groupBy {
            it.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().dayOfWeek
        }.mapValues { it.value.size }
    }
    val busiestDay = dayOfWeekCounts.maxByOrNull { it.value }?.key?.name?.take(3)
        ?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "N/A"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatCard("Current Streak", "$streak days", MaterialTheme.colorScheme.primary, large = true) }
        item { StatCard("Total Spent", "$currencySymbol$totalSpent", MaterialTheme.colorScheme.error) }
        item { StatCard("Avg per Transaction", "$currencySymbol$avgPerDay", MaterialTheme.colorScheme.secondary) }
        item { StatCard("Top Category", topCategory, MaterialTheme.colorScheme.tertiary) }
        item { StatCard("Top Category Total", "$currencySymbol$topCategoryTotal", MaterialTheme.colorScheme.tertiary) }
        item { StatCard("Busiest Day", busiestDay, MaterialTheme.colorScheme.primary) }
        item {
            StatCard(
                "Total Transactions",
                "${spentTxs.size}",
                MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun GoalsReminderContent(
    goalsViewModel: GoalsViewModel,
    spendsViewModel: SpendsViewModel,
) {
    val goals by goalsViewModel.goals.collectAsStateWithLifecycle(initialValue = emptyList())
    val currency by spendsViewModel.currency.observeAsState()
    val currencySymbol = currency?.value ?: "$"

    val activeGoals = remember(goals) { goals.filter { !it.completed } }
    val completedGoals = remember(goals) { goals.filter { it.completed } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatCard(
                "Active Goals",
                "${activeGoals.size}",
                MaterialTheme.colorScheme.primary,
                large = true,
            )
        }
        if (activeGoals.isNotEmpty()) {
            item {
                Text(
                    text = "In Progress",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(activeGoals) { goal -> GoalCard(goal, currencySymbol) }
        }
        if (completedGoals.isNotEmpty()) {
            item {
                Text(
                    text = "Completed",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(completedGoals) { goal -> GoalCard(goal, currencySymbol) }
        }
    }
}

@Composable
private fun GoalCard(goal: Goal, currencySymbol: String) {
    val progress = if (goal.targetAmount > BigDecimal.ZERO)
        (goal.savedAmount / goal.targetAmount * BigDecimal(100)).setScale(0, RoundingMode.DOWN).toInt()
    else 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = goal.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (goal.completed) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { goal.progress() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (goal.completed) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$currencySymbol${goal.savedAmount} / $currencySymbol${goal.targetAmount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    large: Boolean = false,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = if (large) MaterialTheme.typography.headlineMedium
                    else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

@Composable
private fun TransactionRow(tx: Transaction, currencySymbol: String, categoryName: String = "") {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = tx.comment.ifEmpty { "Spend" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = categoryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Text(
                text = "-$currencySymbol${tx.value}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun getIconForType(type: NotificationType): Int = when (type) {
    NotificationType.DAILY_SPEND_OVERVIEW -> R.drawable.ic_clock
    NotificationType.WEEKLY_OVERVIEW -> R.drawable.ic_calendar
    NotificationType.MONTHLY_EXPORT -> R.drawable.ic_file_download
    NotificationType.MONTHLY_OVERVIEW -> R.drawable.ic_analytics
    NotificationType.FACTS_INSIGHTS -> R.drawable.ic_info
    NotificationType.GOALS_REMINDER -> R.drawable.ic_balance_wallet
}
