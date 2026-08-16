package com.danilkinkin.buckwheat.history

import androidx.compose.foundation.layout.*import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.transactionMatchesCategory
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.toTransaction
import com.danilkinkin.buckwheat.di.TUTORIAL_STAGE
import com.danilkinkin.buckwheat.di.TUTORS
import com.danilkinkin.buckwheat.editor.EditorViewModel
import com.danilkinkin.buckwheat.analytics.WholeBudgetCard
import com.danilkinkin.buckwheat.settings.CategoriesManagementViewModel
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

data class RowEntity(
    val key: String,
    var contentHash: String? = null,
    val day: LocalDate,
    val transactions: List<Transaction>,
    val firstTransactionIndex: Int = 0,
    var dayTotal: BigDecimal? = null,
)

@Composable
fun History(
    modifier: Modifier = Modifier,
    spendsViewModel: SpendsViewModel = viewModel(),
    appViewModel: AppViewModel = viewModel(),
    editorViewModel: EditorViewModel = viewModel(),
    readOnly: Boolean = false,
    onClose: () -> Unit = {},
    searchQuery: String = "",
    onlyDay: LocalDate? = null,
    onlyCategoryKey: CategoryKey? = null,
) {
    val scrollState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val categoriesViewModel: CategoriesManagementViewModel = hiltViewModel()
    val allCategories by categoriesViewModel.allCategories.observeAsState(emptyList())
    val categoryEmojis = remember(allCategories) {
        allCategories.associate { it.name to it.emoji }
    }

    var historyList by remember { mutableStateOf<List<RowEntity>>(emptyList()) }
    val budget = spendsViewModel.budget.observeAsState(initial = BigDecimal.ZERO)
    val currency = spendsViewModel.currency.observeAsState(initial = ExtendCurrency.none())
    val startPeriodDate = spendsViewModel.startPeriodDate.observeAsState(initial = Date())
    val finishPeriodDate = spendsViewModel.finishPeriodDate.observeAsState(initial = Date())
    val scrollToBottom = remember { mutableStateOf(true) }
    val tutorial by appViewModel.getTutorialStage(TUTORS.SWIPE_EDIT_SPENT).observeAsState(TUTORIAL_STAGE.NONE)
    var isUserTrySwipe by remember { mutableStateOf(false) }

    val periodSpends by spendsViewModel.periodSpends.observeAsState(emptyList())
    val archivedTransactions by spendsViewModel.archivedTransactions.observeAsState(emptyList())

    LaunchedEffect(searchQuery, onlyDay, onlyCategoryKey, periodSpends, archivedTransactions) {
        historyList = composeHistoryRows(
            periodSpends,
            archivedTransactions,
            searchQuery,
            onlyDay,
            onlyCategoryKey,
        )
    }

    DisposableEffect(Unit) {
        appViewModel.lockSwipeable.value = false
        scrollToBottom.value = true

        onDispose {
            appViewModel.lockSwipeable.value = false

            if (historyList.isNotEmpty() && isUserTrySwipe) {
                appViewModel.passTutorial(TUTORS.SWIPE_EDIT_SPENT)
            }

        }
    }

    val fapScale by animateFloatAsState(
        targetValue = if (appViewModel.lockSwipeable.value) 1f else 0f,
        animationSpec = TweenSpec(250),
    )

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                state = scrollState,
                userScrollEnabled = true,
            ) {
                item("spacer-2") {
                    Spacer(modifier = Modifier.height(2.dp))
                }

                item("end-checker") {
                    DisposableEffect(Unit) {
                        appViewModel.lockSwipeable.value = false

                        onDispose {
                            appViewModel.lockSwipeable.value = true
                        }
                    }
                }

                item("spacer") {
                    Spacer(modifier = Modifier.height(18.dp))
                }

                historyList.reversed().forEachIndexed { rowIndex, row ->
                    item("date-${row.key}") {
                        HistoryDateDivider(
                            day = row.day,
                            dayTotal = row.dayTotal ?: BigDecimal.ZERO,
                            currency = currency.value,
                        )
                    }

                    row.transactions.forEachIndexed { index, transaction ->
                        item("spent-${transaction.uid}") {
                            SpentItem(
                                transaction = transaction,
                                currency = currency.value,
                                categoryEmojis = categoryEmojis,
                                readOnly = readOnly,
                                onEdit = {
                                    editorViewModel.startEditingSpent(transaction)
                                    onClose()
                                },
                                onDelete = {
                                    spendsViewModel.removeSpent(transaction)
                                },
                                onCopy = {
                                    clipboard.setText(
                                        AnnotatedString(
                                            buildSpendCopyText(
                                                amount = numberFormat(
                                                    context = context,
                                                    transaction.value,
                                                    currency = currency.value,
                                                ),
                                                comment = transaction.comment,
                                            )
                                        )
                                    )
                                    appViewModel.showSnackbar(
                                        context.getString(R.string.history_actions_copied)
                                    )
                                },
                                onSwipeEdit = {
                                    editorViewModel.startEditingSpent(transaction)
                                    onClose()
                                },
                                onSwipeDelete = {
                                    spendsViewModel.removeSpent(transaction)
                                },
                                onTriedSwipe = { isUserTrySwipe = true },
                                showTutorial = row.firstTransactionIndex + index == 2 && tutorial === TUTORIAL_STAGE.READY_TO_SHOW,
                            )
                        }
                        if (index < row.transactions.lastIndex) {
                            item("item-spacer-${transaction.uid}") {
                                HistoryItemSpacer()
                            }
                        }
                    }

                    item("total-${row.key}") {
                        TotalPerDay(
                            dayTotal = row.dayTotal ?: BigDecimal.ZERO,
                            currency = currency.value,
                        )
                    }

                    if (rowIndex < historyList.lastIndex) {
                        item("day-spacer-${row.key}") {
                            HistoryDaySpacer()
                        }
                    }
                }

                if (!readOnly) {
                    item("budget-info") {
                        WholeBudgetCard(
                            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
                            budget = budget.value,
                            currency = currency.value,
                            startDate = startPeriodDate.value,
                            finishDate = finishPeriodDate.value,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(
                                    LocalWindowInsets.current.calculateTopPadding()
                                )
                        )
                    }
                }
            }

            if (historyList.isEmpty()) {
                NoSpends(Modifier.weight(1f))
            }
        }

        if (!readOnly) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                FloatingActionButton(
                    modifier = Modifier
                        .padding(end = 24.dp, bottom = 32.dp)
                        .scale(fapScale),
                    onClick = {
                        coroutineScope.launch {
                            val lastItem = scrollState.layoutInfo.totalItemsCount - 1
                            if (lastItem >= 0) {
                                scrollState.animateScrollToItem(lastItem)
                            }
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_down),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )

                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewDefault() {
    BuckwheatTheme {
        History()
    }
}

internal fun composeHistoryRows(
    periodSpends: List<Transaction>,
    archivedTransactions: List<ArchivedTransaction>,
    searchQuery: String,
    onlyDay: LocalDate? = null,
    onlyCategoryKey: CategoryKey? = null,
): List<RowEntity> {
    val searching = searchQuery.isNotBlank()

    val entries = buildList {
        periodSpends.forEach { tx ->
            add(
                HistoryEntry(
                    "spent-${tx.uid}",
                    "spent-${tx.uid}-${tx.value}-${tx.comment}-${tx.date.time}",
                    tx.date,
                    tx.value,
                    tx.comment,
                    tx,
                )
            )
        }
        if (searching) {
            archivedTransactions.forEach { tx ->
                add(
                    HistoryEntry(
                        "spent-archived-${tx.uid}",
                        "spent-archived-${tx.uid}-${tx.value}-${tx.comment}-${tx.date.time}",
                        tx.date,
                        tx.value,
                        tx.comment,
                        tx.toTransaction(),
                    )
                )
            }
        }
    }.filter { entry ->
        (!searching || entry.comment.contains(searchQuery, ignoreCase = true)) &&
            (onlyDay == null || entry.date.toLocalDate().isEqual(onlyDay)) &&
            (onlyCategoryKey == null || transactionMatchesCategory(entry.transaction, onlyCategoryKey))
    }.sortedBy { it.date }

    if (entries.isEmpty()) return emptyList()

    val grouped = entries.groupBy { it.date.toLocalDate() }
    var firstTransactionIndex = 0
    return grouped.keys.sorted().map { day ->
        val dayEntries = grouped.getValue(day)
        val card = RowEntity(
            key = "day-$day",
            contentHash = "day-$day-" + dayEntries.joinToString("|") { it.contentHash },
            day = day,
            transactions = dayEntries.map { it.transaction },
            firstTransactionIndex = firstTransactionIndex,
            dayTotal = dayEntries.sumOf { it.value },
        )
        firstTransactionIndex += dayEntries.size
        card
    }.reversed()
}

private data class HistoryEntry(
    val key: String,
    val contentHash: String,
    val date: Date,
    val value: BigDecimal,
    val comment: String,
    val transaction: Transaction,
)
