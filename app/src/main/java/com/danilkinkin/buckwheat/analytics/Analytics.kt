package com.danilkinkin.buckwheat.analytics

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.analytics.CategorySpendingLimitsCard
import com.danilkinkin.buckwheat.analytics.categoriesChart.CategoriesChartCard
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.wallet.DaysLeftCard
import com.danilkinkin.buckwheat.wallet.rememberExportCSV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale

const val ANALYTICS_SHEET = "finishPeriod"

data class Size(val width: Dp, val height: Dp)

fun parseCsvDate(input: String): Date? {
    val trimmed = input.trim()
    val patterns = listOf(
        DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT),
        DateTimeFormatter.ofPattern("M/d/yy h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("M/d/yyyy h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("d/M/yy H:mm"),
        DateTimeFormatter.ofPattern("d/M/yyyy H:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
    )
    for (fmt in patterns) {
        try {
            return Date.from(
                LocalDateTime.parse(trimmed, fmt)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            )
        } catch (_: DateTimeParseException) {
        }
    }
    return null
}

@Composable
fun rememberImportCSV(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
): () -> Unit {
    if (activityResultRegistryOwner === null) return {}

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarSuccess = stringResource(R.string.import_csv_success)
    val snackbarFailed = stringResource(R.string.import_csv_failed)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            try {
                val transactions = withContext(Dispatchers.IO) {
                    val reader = BufferedReader(
                        InputStreamReader(context.contentResolver.openInputStream(uri))
                    )
                    val parser = CSVParser(
                        reader,
                        CSVFormat.DEFAULT.builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .setTrim(true)
                            .build()
                    )

                    parser.mapNotNull { record ->
                        val amountStr = record.get("amount") ?: return@mapNotNull null
                        val comment = record.get("comment") ?: ""
                        val commitTime = record.get("commit_time") ?: return@mapNotNull null

                        val value = amountStr.toBigDecimalOrNull() ?: return@mapNotNull null
                        val date = parseCsvDate(commitTime) ?: return@mapNotNull null

                        Transaction(
                            type = TransactionType.SPENT,
                            value = value,
                            date = date,
                            comment = comment,
                        )
                    }
                }
                if (transactions.isNotEmpty()) {
                    spendsViewModel.importTransactions(transactions) { result ->
                        val msg = if (result.skipped > 0) {
                            "Inserted ${result.inserted}, skipped ${result.skipped} duplicate(s)"
                        } else {
                            "$snackbarSuccess (${result.inserted})"
                        }
                        appViewModel.showSnackbar(msg)
                    }
                } else {
                    appViewModel.showSnackbar(snackbarFailed)
                }
                } catch (e: Exception) {
                appViewModel.showSnackbar(snackbarFailed)
            }
        }
    }

    return { launcher.launch(arrayOf("text/*", "*/*")) }
}

@Composable
fun Analytics(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
    onCreateNewPeriod: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val periodFinished by spendsViewModel.periodFinished.observeAsState(false)
    val allTransactions by spendsViewModel.transactions.observeAsState(emptyList())
    val allSpends by spendsViewModel.spends.observeAsState(emptyList())
    val wholeBudget = spendsViewModel.budget.value ?: BigDecimal.ZERO
    val scrollState = rememberScrollState()

    val finishPeriodActualDate by spendsViewModel.finishPeriodActualDate.observeAsState(null)

    val allPeriods by spendsViewModel.allPeriods.observeAsState(emptyList())
    val selectedPeriodId = remember { mutableStateOf<Long?>(null) }

    val afterMigrationToTransactions =
        remember(allTransactions) { mutableStateOf(allTransactions.none { it.type == TransactionType.INCOME }) }

    val clickedDaySpends = remember { mutableStateOf<List<SpendingDay>?>(null) }

    val navigationBarHeight =
        LocalWindowInsets.current.calculateBottomPadding().coerceAtLeast(16.dp)

    val selectedPeriod = selectedPeriodId.value?.let { id ->
        allPeriods.find { it.id == id }
    }

    val effectiveStartDate = selectedPeriod?.startDate
        ?: spendsViewModel.startPeriodDate.value ?: Date()
    val effectiveFinishDate = selectedPeriod?.finishDate
        ?: spendsViewModel.finishPeriodDate.value ?: Date()
    val effectiveActualFinishDate = selectedPeriod?.actualFinishDate
        ?: finishPeriodActualDate
    val effectiveBudget = selectedPeriod?.budget
        ?: wholeBudget

    val filteredTransactions = remember(allTransactions, selectedPeriod) {
        if (selectedPeriod != null) {
            allTransactions.filter { t ->
                t.date.time >= selectedPeriod.startDate.time &&
                        t.date.time <= selectedPeriod.finishDate.time
            }
        } else {
            allTransactions
        }
    }

    val filteredSpends = remember(allSpends, selectedPeriod) {
        if (selectedPeriod != null) {
            allSpends.filter { t ->
                t.date.time >= selectedPeriod.startDate.time &&
                        t.date.time <= selectedPeriod.finishDate.time
            }
        } else {
            allSpends
        }
    }

    val importCSVLaunch = rememberImportCSV(
        spendsViewModel = spendsViewModel,
        appViewModel = appViewModel,
        activityResultRegistryOwner = activityResultRegistryOwner,
    )

    Surface(Modifier.fillMaxSize()) {
        Column {
            if (!periodFinished && selectedPeriod == null) {
                MiddlePeriodAnalyticsHeader(
                    onClose = onClose,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (periodFinished && selectedPeriod == null) {
                        FinishedPeriodHeader(
                            scrollState = scrollState,
                            hasSpends = filteredSpends.isNotEmpty(),
                        )
                    }

                    if (allPeriods.isNotEmpty()) {
                        PeriodSelector(
                            periods = allPeriods,
                            selectedPeriodId = selectedPeriodId.value,
                            onSelect = { selectedPeriodId.value = it },
                            onClear = { selectedPeriodId.value = null },
                            onDelete = { spendsViewModel.deletePeriod(it) },
                            onUpdateNote = { id, note -> spendsViewModel.updatePeriodNote(id, note) },
                        )
                    }

                    Column(Modifier.fillMaxWidth()) {
                        WholeBudgetCard(
                            budget = effectiveBudget,
                            currency = spendsViewModel.currency.value ?: ExtendCurrency.none(),
                            startDate = effectiveStartDate,
                            finishDate = effectiveFinishDate,
                            actualFinishDate = effectiveActualFinishDate,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        NeedsWantsBudgetRow(
                            needsBudget = spendsViewModel.needsBudget,
                            wantsBudget = spendsViewModel.wantsBudget,
                            spends = filteredSpends,
                            currency = spendsViewModel.currency.value ?: ExtendCurrency.none(),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (filteredSpends.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                            ) {
                                RestAndSpentBudgetCard(modifier = Modifier.weight(1f))
                                Spacer(modifier = Modifier.width(16.dp))
                                if (periodFinished && selectedPeriod == null) {
                                    FillCircleStub()
                                } else {
                                    DaysLeftCard(
                                        startDate = effectiveStartDate,
                                        finishDate = effectiveFinishDate,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                            ) {
                                MinMaxSpentCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    isMin = true,
                                    spends = filteredSpends,
                                    currency = spendsViewModel.currency.value ?: ExtendCurrency.none(),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                MinMaxSpentCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    isMin = false,
                                    spends = filteredSpends,
                                    currency = spendsViewModel.currency.value ?: ExtendCurrency.none(),
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            CategoriesChartCard(
                                modifier = Modifier.fillMaxWidth(),
                                spends = filteredSpends,
                                currency = spendsViewModel.currency.value ?: ExtendCurrency.none(),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            CategorySpendingLimitsCard(
                                spends = filteredSpends,
                                currency = spendsViewModel.currency.value ?: ExtendCurrency.none(),
                                spendsViewModel = spendsViewModel,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            key("calendar_${effectiveStartDate.time}_${(effectiveActualFinishDate ?: effectiveFinishDate).time}") {
                                SpendsCalendar(
                                    modifier = Modifier.fillMaxWidth(),
                                    budget = effectiveBudget,
                                    transactions = filteredTransactions,
                                    startDate = effectiveStartDate,
                                    finishDate = effectiveFinishDate,
                                    actualFinishDate = effectiveActualFinishDate,
                                    currency = spendsViewModel.currency.value ?: ExtendCurrency.none(),
                                    onDayClick = { day ->
                                        clickedDaySpends.value = listOf(day)
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }

                if (filteredSpends.isNotEmpty()) {
                    val exportCSVLaunch = rememberExportCSV(
                        activityResultRegistryOwner = activityResultRegistryOwner,
                        spends = filteredSpends,
                    )

                    ButtonRow(
                        icon = painterResource(R.drawable.ic_file_download),
                        text = stringResource(R.string.export_to_csv),
                        onClick = { exportCSVLaunch() },
                    )

                    ButtonRow(
                        icon = painterResource(R.drawable.ic_file_upload),
                        text = stringResource(R.string.import_csv_title),
                        onClick = { importCSVLaunch() },
                    )

                    ButtonRow(
                        icon = painterResource(R.drawable.ic_analytics),
                        text = stringResource(R.string.month_over_month_title),
                        onClick = {
                            appViewModel.openSheet(PathState(MONTH_OVER_MONTH_SHEET))
                        },
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                CategoryOverview(
                    spendsViewModel = spendsViewModel,
                    transactions = filteredTransactions,
                    currency = spendsViewModel.currency.value ?: ExtendCurrency.none(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (filteredSpends.isEmpty()) {
                    ButtonRow(
                        icon = painterResource(R.drawable.ic_file_upload),
                        text = stringResource(R.string.import_csv_title),
                        onClick = { importCSVLaunch() },
                    )
                }

                if (periodFinished && selectedPeriod == null) {
                    Spacer(
                        Modifier
                            .height(60.dp + navigationBarHeight)
                            .fillMaxWidth()
                    )
                }
            }
        }


        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = navigationBarHeight),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (periodFinished && selectedPeriod == null) {
                Button(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(60.dp)
                    .padding(horizontal = 16.dp),
                    onClick = {
                        onCreateNewPeriod()
                        onClose()
                    }) {
                    Text(
                        text = stringResource(R.string.new_period_title),
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

    clickedDaySpends.value?.let { days ->
        val day = days.firstOrNull() ?: return@let
        AlertDialog(
            onDismissRequest = { clickedDaySpends.value = null },
            title = {
                Text(prettyDate(day.date, showTime = false))
            },
            text = {
                Column {
                    Text(
                        text = "Total: ${day.spending.setScale(2).toPlainString()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Budget: ${day.budget.setScale(2).toPlainString()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    day.spends.forEach { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = t.comment.ifEmpty { "—" },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = t.value.setScale(2).toPlainString(),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { clickedDaySpends.value = null }) {
                    Text(stringResource(R.string.accept))
                }
            },
        )
    }
}

@Composable
private fun PeriodSelector(
    periods: List<Period>,
    selectedPeriodId: Long?,
    onSelect: (Long) -> Unit,
    onClear: () -> Unit,
    onDelete: (Long) -> Unit = {},
    onUpdateNote: (Long, String) -> Unit = { _, _ -> },
) {
    var deleteConfirmId by remember { mutableStateOf<Long?>(null) }
    var editingNoteId by remember { mutableStateOf<Long?>(null) }
    var noteText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                onClick = { onClear() },
                label = {
                    Text(
                        text = "Current",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                selected = selectedPeriodId == null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            periods.take(5).forEach { period ->
                FilterChip(
                    onClick = { onSelect(period.id) },
                    label = {
                        Text(
                            text = period.note.ifBlank { prettyDate(period.startDate, showTime = false) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    selected = selectedPeriodId == period.id,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
        if (selectedPeriodId != null) {
            val p = periods.find { it.id == selectedPeriodId }
            if (p != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = p.note.ifBlank { prettyDate(p.startDate, showTime = false) },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "${prettyDate(p.startDate, showTime = false)} - ${prettyDate(p.finishDate, showTime = false)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    Row {
                        IconButton(onClick = {
                            noteText = p.note
                            editingNoteId = p.id
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit),
                                contentDescription = "Edit note",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(onClick = { deleteConfirmId = p.id }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_forever),
                                contentDescription = "Delete",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(onClick = { onClear() }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    deleteConfirmId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("Delete period") },
            text = { Text("Delete this period from history? This does not affect transactions.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(id)
                    onClear()
                    deleteConfirmId = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    editingNoteId?.let { id ->
        AlertDialog(
            onDismissRequest = { editingNoteId = null },
            title = { Text("Period label") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Label") },
                    singleLine = true,
                    placeholder = { Text("e.g. Summer Vacation 2026") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateNote(id, noteText.trim())
                    editingNoteId = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingNoteId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Preview
@Composable
private fun Preview() {
    BuckwheatTheme {
        Analytics()
    }
}
