package com.danilkinkin.buckwheat.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.BuildConfig
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.analytics.rememberImportCSV
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import com.danilkinkin.buckwheat.goals.GOALS_SHEET
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.base.TextRow
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.editor.dateTimeEdit.TimePickerDialog
import com.danilkinkin.buckwheat.notifications.NotificationType
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.wallet.rememberExportCSV
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.util.Currency
import java.util.Date

const val SETTINGS_SHEET = "settings"

@Composable
fun Settings(
    onClose: () -> Unit = {},
    onTriedWidget: () -> Unit = {},
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight = maxOf(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = navigationBarHeight)
            ) {
                BudgetPeriodSection()
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                CurrencySection()
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                CategoriesSection()
                TagsSection()
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                GoalsSection()
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ImportExportSection(activityResultRegistryOwner = activityResultRegistryOwner)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                RecurringSection()
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SyncSection()
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                NotificationSettingsSection()
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                GeneralSettingsSection(onTriedWidget = onTriedWidget)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun BudgetPeriodSection(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val startDate by spendsViewModel.startPeriodDate.observeAsState()
    val finishDate by spendsViewModel.finishPeriodDate.observeAsState()
    val budget by spendsViewModel.budget.observeAsState()
    val currency by spendsViewModel.currency.observeAsState()
    var showDialog by remember { mutableStateOf(false) }

    SectionHeader("Budget Period")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { showDialog = true },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${budget?.setScale(2) ?: "—"} ${currency?.value ?: ""}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${com.danilkinkin.buckwheat.util.prettyDate(startDate ?: java.util.Date(), false)} — ${com.danilkinkin.buckwheat.util.prettyDate(finishDate ?: java.util.Date(), false)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }

    if (showDialog) {
        BudgetSettingsDialog(
            spendsViewModel = spendsViewModel,
            initialBudget = budget ?: BigDecimal.ZERO,
            initialNeeds = spendsViewModel.needsBudget.value ?: BigDecimal.ZERO,
            initialStartDate = startDate ?: Date(),
            initialFinishDate = finishDate ?: Date(),
            onDismiss = { showDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetSettingsDialog(
    spendsViewModel: SpendsViewModel,
    initialBudget: BigDecimal,
    initialNeeds: BigDecimal,
    initialStartDate: Date,
    initialFinishDate: Date,
    onDismiss: () -> Unit,
) {
    var overallText by remember(initialBudget) {
        mutableStateOf(
            if (initialBudget > BigDecimal.ZERO) initialBudget.stripTrailingZeros().toPlainString()
            else ""
        )
    }
    var needsText by remember(initialNeeds) {
        mutableStateOf(
            if (initialNeeds > BigDecimal.ZERO) initialNeeds.stripTrailingZeros().toPlainString()
            else ""
        )
    }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var finishDate by remember { mutableStateOf(initialFinishDate) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showFinishPicker by remember { mutableStateOf(false) }

    val overall = overallText.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val needs = needsText.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val wants = (overall - needs).coerceAtLeast(BigDecimal.ZERO)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Budget Period") },
        text = {
            Column {
                OutlinedTextField(
                    value = overallText,
                    onValueChange = { overallText = it },
                    label = { Text("Overall budget") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = needsText,
                    onValueChange = { needsText = it },
                    label = { Text("Needs budget (rent, utilities, debt)") },
                    placeholder = { Text("max ${overall.toPlainString()}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Wants budget: ${wants.setScale(2).toPlainString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { showStartPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Start: ${com.danilkinkin.buckwheat.util.prettyDate(startDate, false)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(
                    onClick = { showFinishPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Finish: ${com.danilkinkin.buckwheat.util.prettyDate(finishDate, false)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (overall > BigDecimal.ZERO && finishDate.after(startDate)) {
                        val wants = overall - needs
                        spendsViewModel.changeBudgetsAndStartDate(needs, wants, finishDate, startDate)
                        onDismiss()
                    }
                },
                enabled = overall > BigDecimal.ZERO && finishDate.after(startDate),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (showStartPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = startDate.toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        startDate = Date.from(Instant.ofEpochMilli(millis))
                    }
                    showStartPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showFinishPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = finishDate.toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showFinishPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        finishDate = Date.from(Instant.ofEpochMilli(millis))
                    }
                    showFinishPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun CurrencySection(
    appViewModel: AppViewModel = hiltViewModel(),
    spendsViewModel: SpendsViewModel = hiltViewModel(),
) {
    val currency by spendsViewModel.currency.observeAsState()
    var showDialog by remember { mutableStateOf(false) }

    SectionHeader("Currency")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { showDialog = true },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Display currency",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = currency?.value ?: stringResource(R.string.currency_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
            }
        }
    }

    if (showDialog) {
        CurrencySettingsDialog(
            spendsViewModel = spendsViewModel,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun CurrencySettingsDialog(
    spendsViewModel: SpendsViewModel,
    onDismiss: () -> Unit,
) {
    val currentCurrency = spendsViewModel.currency.value ?: ExtendCurrency.none()
    var selected by remember { mutableStateOf(currentCurrency) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf(
        if (currentCurrency.type == ExtendCurrency.Type.CUSTOM) currentCurrency.value ?: "" else ""
    ) }

    val currencyOptions = listOf(
        "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF", "CNY",
        "INR", "BRL", "KRW", "MXN", "SEK", "NOK", "DKK", "NZD",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Currency") },
        text = {
            Column {
                currencyOptions.forEach { code ->
                    val isSelected = selected.type == ExtendCurrency.Type.FROM_LIST && selected.value == code
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = ExtendCurrency(
                                    type = ExtendCurrency.Type.FROM_LIST,
                                    value = code,
                                )
                                spendsViewModel.changeDisplayCurrency(selected)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$code (${Currency.getInstance(code).symbol})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                TextButton(
                    onClick = {
                        if (selected.type == ExtendCurrency.Type.CUSTOM) {
                            spendsViewModel.changeDisplayCurrency(selected)
                            onDismiss()
                        } else {
                            showCustomDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Custom currency...")
                }
                TextButton(
                    onClick = {
                        selected = ExtendCurrency.none()
                        spendsViewModel.changeDisplayCurrency(selected)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("None", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Custom Currency") },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    label = { Text("Currency symbol/text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (customText.isNotBlank()) {
                        selected = ExtendCurrency(
                            type = ExtendCurrency.Type.CUSTOM,
                            value = customText.trim(),
                        )
                        spendsViewModel.changeDisplayCurrency(selected)
                        showCustomDialog = false
                        onDismiss()
                    }
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CategoriesSection(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    SectionHeader("Categories")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { appViewModel.openSheet(com.danilkinkin.buckwheat.data.PathState(CATEGORIES_MANAGER_SHEET)) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Manage categories",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Open",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun TagsSection(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    SectionHeader("Tags")
    PersistTagsSwitcher()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { appViewModel.openSheet(com.danilkinkin.buckwheat.data.PathState(TAGS_MANAGER_SHEET)) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Manage tags",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Open",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun GoalsSection(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    SectionHeader("Goals")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { appViewModel.openSheet(com.danilkinkin.buckwheat.data.PathState(GOALS_SHEET)) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Manage goals",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Open",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ImportExportSection(
    appViewModel: AppViewModel = hiltViewModel(),
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
) {
    val importCSVLaunch = rememberImportCSV(
        appViewModel = appViewModel,
        activityResultRegistryOwner = activityResultRegistryOwner,
    )
    val exportCSVLaunch = rememberExportCSV(
        appViewModel = appViewModel,
        activityResultRegistryOwner = activityResultRegistryOwner,
    )

    SectionHeader("CSV Data")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            ButtonRow(
                icon = painterResource(R.drawable.ic_file_upload),
                text = stringResource(R.string.import_csv_title),
                onClick = { importCSVLaunch() },
            )
            ButtonRow(
                icon = painterResource(R.drawable.ic_file_download),
                text = stringResource(R.string.export_to_csv),
                onClick = { exportCSVLaunch() },
            )
            ButtonRow(
                icon = painterResource(R.drawable.ic_clock),
                text = "View all transactions",
                onClick = {
                    appViewModel.openSheet(com.danilkinkin.buckwheat.data.PathState(com.danilkinkin.buckwheat.analytics.VIEWER_HISTORY_SHEET))
                },
            )
        }
    }
}

@Composable
private fun RecurringSection(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    SectionHeader("Recurring")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { appViewModel.openSheet(com.danilkinkin.buckwheat.data.PathState(com.danilkinkin.buckwheat.recurring.RECURRING_SHEET)) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.recurring_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Auto-add recurring expenses each month",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Manage",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun SyncSection(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    SectionHeader("TrackInvest Sync")
    SyncSwitcher(appViewModel = appViewModel)
}

private data class NotificationCardData(
    val type: NotificationType,
    val icon: Int,
    val enabled: androidx.lifecycle.LiveData<Boolean>,
    val onToggle: (Boolean) -> Unit,
    val description: String,
)

@Composable
private fun NotificationSettingsSection(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    SectionHeader("Notifications")

    ReminderSwitcher(appViewModel = appViewModel)

    val cards = remember {
        listOf(
            NotificationCardData(
                type = NotificationType.DAILY_SPEND_OVERVIEW,
                icon = R.drawable.ic_clock,
                enabled = appViewModel.dailySpendOverviewEnabled,
                onToggle = { appViewModel.setDailySpendOverviewEnabled(it) },
                description = "Receive a summary of today's spending each evening",
            ),
            NotificationCardData(
                type = NotificationType.WEEKLY_OVERVIEW,
                icon = R.drawable.ic_calendar,
                enabled = appViewModel.weeklyOverviewEnabled,
                onToggle = { appViewModel.setWeeklyOverviewEnabled(it) },
                description = "Get a weekly spending recap every Sunday",
            ),
            NotificationCardData(
                type = NotificationType.MONTHLY_EXPORT,
                icon = R.drawable.ic_file_download,
                enabled = appViewModel.monthlyExportEnabled,
                onToggle = { appViewModel.setMonthlyExportEnabled(it) },
                description = "Receive a CSV export at the end of each budget period",
            ),
            NotificationCardData(
                type = NotificationType.MONTHLY_OVERVIEW,
                icon = R.drawable.ic_analytics,
                enabled = appViewModel.monthlyOverviewEnabled,
                onToggle = { appViewModel.setMonthlyOverviewEnabled(it) },
                description = "Get a monthly spending breakdown on the last day",
            ),
            NotificationCardData(
                type = NotificationType.FACTS_INSIGHTS,
                icon = R.drawable.ic_info,
                enabled = appViewModel.factsInsightsEnabled,
                onToggle = { appViewModel.setFactsInsightsEnabled(it) },
                description = "Receive interesting stats and tips about your spending",
            ),
            NotificationCardData(
                type = NotificationType.GOALS_REMINDER,
                icon = R.drawable.ic_balance_wallet,
                enabled = appViewModel.goalsReminderEnabled,
                onToggle = { appViewModel.setGoalsReminderEnabled(it) },
                description = "Get reminded about your saving goals and progress",
            ),
        )
    }

    cards.forEach { card ->
        NotificationOverviewCard(
            appViewModel = appViewModel,
            card = card,
        )
    }
}

@Composable
private fun NotificationOverviewCard(
    appViewModel: AppViewModel,
    card: NotificationCardData,
) {
    val checked by card.enabled.observeAsState(false)
    val hour by appViewModel.getNotificationHour(card.type).observeAsState(card.type.defaultHour)
    val minute by appViewModel.getNotificationMinute(card.type).observeAsState(card.type.defaultMinute)
    var showTimePicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { card.onToggle(!checked) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(card.icon),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.type.getChannelName(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = card.type.getScheduleDescription(hour ?: card.type.defaultHour, minute ?: card.type.defaultMinute),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        ),
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (checked) {
                    Text(
                        modifier = Modifier.clickable { showTimePicker = true },
                        text = String.format("%02d:%02d", hour ?: card.type.defaultHour, minute ?: card.type.defaultMinute),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Switch(
                    checked = checked,
                    onCheckedChange = card.onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Text(
                text = card.description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initTime = LocalTime.of(
                hour ?: card.type.defaultHour,
                minute ?: card.type.defaultMinute,
            ),
            onSelect = { h, m, _ ->
                appViewModel.setNotificationTime(card.type, h, m)
                showTimePicker = false
            },
            onClose = { showTimePicker = false },
        )
    }
}

@Composable
private fun GeneralSettingsSection(
    onTriedWidget: () -> Unit = {},
) {
    SectionHeader("General")
    ThemeSwitcher()
    LangSwitcher()
    TryWidget(onTried = onTriedWidget)
    TextRow(
        text = stringResource(R.string.version, BuildConfig.VERSION_NAME),
    )
    About(Modifier.padding(start = 16.dp, end = 16.dp))
}

@Composable
fun PersistTagsSwitcher(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val persistTags by appViewModel.persistTags.observeAsState(false)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { appViewModel.setPersistTags(!persistTags) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.persist_tags_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = persistTags,
                    onCheckedChange = { appViewModel.setPersistTags(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Text(
                text = stringResource(R.string.persist_tags_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
fun ReminderSwitcher(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val reminderEnabled by appViewModel.reminderEnabled.observeAsState(false)
    val reminderHour by appViewModel.reminderHour.observeAsState(20)
    val reminderMinute by appViewModel.reminderMinute.observeAsState(0)
    var showTimePicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            appViewModel.setReminderEnabled(true)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable {
                if (!reminderEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    appViewModel.setReminderEnabled(!reminderEnabled)
                }
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.reminder_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (reminderEnabled) {
                    Text(
                        modifier = Modifier.clickable { showTimePicker = true },
                        text = String.format(
                            "%02d:%02d",
                            reminderHour,
                            reminderMinute,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Switch(
                    checked = reminderEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            appViewModel.setReminderEnabled(enabled)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Text(
                text = stringResource(R.string.reminder_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initTime = LocalTime.of(reminderHour, reminderMinute),
            onSelect = { hour, minute, _ ->
                appViewModel.setReminderTime(hour, minute)
                showTimePicker = false
            },
            onClose = { showTimePicker = false },
        )
    }
}

@Composable
fun SyncSwitcher(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val syncEnabled by appViewModel.syncEnabled.observeAsState(false)
    val syncHour by appViewModel.syncHour.observeAsState(22)
    val syncMinute by appViewModel.syncMinute.observeAsState(0)
    var showTimePicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable {
                appViewModel.setSyncEnabled(!syncEnabled)
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.sync_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (syncEnabled) {
                    Text(
                        modifier = Modifier.clickable { showTimePicker = true },
                        text = String.format(
                            "%02d:%02d",
                            syncHour,
                            syncMinute,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Switch(
                    checked = syncEnabled,
                    onCheckedChange = { appViewModel.setSyncEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Text(
                text = stringResource(R.string.sync_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initTime = LocalTime.of(syncHour, syncMinute),
            onSelect = { hour, minute, _ ->
                appViewModel.setSyncTime(hour, minute)
                showTimePicker = false
            },
            onClose = { showTimePicker = false },
        )
    }
}

@Preview(name = "Default")
@Composable
private fun PreviewDefault() {
    BuckwheatTheme {
        Settings()
    }
}

@Preview(name = "Night mode", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewNightMode() {
    BuckwheatTheme {
        Settings()
    }
}
