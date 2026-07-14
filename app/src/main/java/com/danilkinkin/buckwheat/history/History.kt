package com.danilkinkin.buckwheat.history

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.DismissDirection
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.di.TUTORIAL_STAGE
import com.danilkinkin.buckwheat.di.TUTORS
import com.danilkinkin.buckwheat.editor.EditorViewModel
import com.danilkinkin.buckwheat.analytics.WholeBudgetCard
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorEditor
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.util.isSameDay
import com.danilkinkin.buckwheat.util.observeLiveData
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.abs
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun History(
    modifier: Modifier = Modifier,
    spendsViewModel: SpendsViewModel = viewModel(),
    appViewModel: AppViewModel = viewModel(),
    editorViewModel: EditorViewModel = viewModel(),
    readOnly: Boolean = false,
    onClose: () -> Unit = {}
) {
    val scrollState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var historyList by remember { mutableStateOf<List<RowEntity>>(emptyList()) }
    val budget = spendsViewModel.budget.observeAsState(initial = BigDecimal.ZERO)
    val currency = spendsViewModel.currency.observeAsState(initial = ExtendCurrency.none())
    val startPeriodDate = spendsViewModel.startPeriodDate.observeAsState(initial = Date())
    val finishPeriodDate = spendsViewModel.finishPeriodDate.observeAsState(initial = Date())
    val scrollToBottom = remember { mutableStateOf(true) }
    val tutorial by appViewModel.getTutorialStage(TUTORS.SWIPE_EDIT_SPENT).observeAsState(TUTORIAL_STAGE.NONE)
    var isUserTrySwipe by remember { mutableStateOf(false) }
    var pendingUndoTransaction by remember { mutableStateOf<Transaction?>(null) }

    val searchQuery by spendsViewModel.searchQuery.collectAsState()
    val selectedTag by spendsViewModel.selectedTagFilter.collectAsState()
    val selectionMode by spendsViewModel.selectionMode.collectAsState()
    val selectedIds by spendsViewModel.selectedTransactionIds.collectAsState()
    var allTransactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    val tags by spendsViewModel.tagsWithCount.observeAsState(emptyList())

    observeLiveData(spendsViewModel.spends) { transactions ->
        val now = LocalDate.now()
        val startOfMonth = now.withDayOfMonth(1)
        val startOfNextMonth = startOfMonth.plusMonths(1)
        val startMs = startOfMonth.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMs = startOfNextMonth.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        allTransactions = transactions.filter { tx ->
            tx.date.time in startMs until endMs
        }
    }

    val filteredTransactions by remember {
        derivedStateOf {
            allTransactions.filter { tx ->
                (searchQuery.isBlank() || tx.comment.contains(searchQuery, ignoreCase = true)) &&
                        (selectedTag == null || tx.comment == selectedTag)
            }
        }
    }

    // Rebuild list from filtered transactions
    LaunchedEffect(filteredTransactions) {
        val transactions = filteredTransactions
        val composedList = emptyList<RowEntity>().toMutableList()
        var lastSpentDate: LocalDate? = null
        var lastDayTotal: BigDecimal = BigDecimal.ZERO

        transactions.forEach { spent ->
            if (lastSpentDate === null || !isSameDay(
                    spent.date.time,
                    lastSpentDate?.toDate()?.time ?: 0L
                )
            ) {
                if (lastSpentDate !== null) {
                    composedList.add(
                        RowEntity(
                            type = RowEntityType.DayTotal,
                            key = "total-${lastSpentDate}",
                            contentHash = "total-${lastSpentDate}",
                            transaction = null,
                            day = lastSpentDate ?: LocalDate.now(),
                            dayTotal = lastDayTotal,
                        )
                    )
                }

                lastSpentDate = spent.date.toLocalDate()
                lastDayTotal = BigDecimal.ZERO

                composedList.add(
                    RowEntity(
                        type = RowEntityType.DayDivider,
                        key = "header-${lastSpentDate}",
                        contentHash = "header-${lastSpentDate}",
                        transaction = null,
                        day = lastSpentDate ?: LocalDate.now(),
                        dayTotal = null,
                    )
                )
            }

            lastDayTotal += spent.value

            composedList.add(
                RowEntity(
                    type = RowEntityType.Spent,
                    key = "spent-${spent.uid}",
                    contentHash = "spent-${spent.uid}",
                    transaction = spent,
                    day = lastSpentDate ?: LocalDate.now(),
                    dayTotal = null,
                )
            )
        }

        if (transactions.isNotEmpty() && lastSpentDate !== null) {
            composedList.add(
                RowEntity(
                    type = RowEntityType.DayTotal,
                    key = "total-${lastSpentDate}",
                    contentHash = "total-${lastSpentDate}",
                    transaction = null,
                    day = lastSpentDate ?: LocalDate.now(),
                    dayTotal = lastDayTotal,
                )
            )
        }

        historyList = composedList.toList().reversed().map { it }
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

    val animatedList = updateAnimatedItemsState(newList = historyList)

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            if (!readOnly) {
                if (selectionMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            val all = filteredTransactions.map { it.uid }.toSet()
                            spendsViewModel.selectTransactions(all)
                        }) { Text("Select all") }
                        IconButton(onClick = {
                            if (selectedIds.isNotEmpty()) {
                                spendsViewModel.deleteSelectedTransactions()
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_forever),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(onClick = { spendsViewModel.clearSelection() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = null,
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { spendsViewModel.setSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        placeholder = { Text("Search transactions...") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        trailingIcon = {
                            Row {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { spendsViewModel.setSearchQuery("") }) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_close),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                IconButton(onClick = { spendsViewModel.toggleSelectionMode() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_checkbox_outline),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { /* no-op */ }),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    if (tags.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            tags.take(10).forEach { (tag, _) ->
                                FilterChip(
                                    selected = selectedTag == tag,
                                    onClick = {
                                        spendsViewModel.setSelectedTagFilter(
                                            if (selectedTag == tag) null else tag
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                    modifier = Modifier.height(28.dp),
                                )
                            }
                        }
                    }
                }
            }
            LazyColumn(
                reverseLayout = true,
                state = scrollState
            ) {

                // Fixer, because if scroll fast end-checker dispose
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

                animatedItemsIndexed(
                    state = animatedList.value,
                    key = { rowItem -> rowItem.key },
                ) { index, row ->
                    when (row.type) {
                        RowEntityType.DayDivider -> HistoryDateDivider(row.day)
                        RowEntityType.DayTotal -> TotalPerDay(
                            spentPerDay = row.dayTotal ?: BigDecimal.ZERO,
                            currency = currency.value,
                        )
                        RowEntityType.Spent -> if (!readOnly && !selectionMode) SwipeActions(
                            startActionsConfig = SwipeActionsConfig(
                                threshold = 0.4f,
                                background = MaterialTheme.colorScheme.tertiaryContainer,
                                backgroundActive = MaterialTheme.colorScheme.tertiary,
                                iconTint = MaterialTheme.colorScheme.onTertiary,
                                icon = painterResource(R.drawable.ic_edit),
                                stayDismissed = false,
                                onDismiss = {
                                    row.transaction?.let { editorViewModel.startEditingSpent(it) }
                                    onClose()
                                }
                            ),
                            endActionsConfig = SwipeActionsConfig(
                                threshold = 0.4f,
                                background = MaterialTheme.colorScheme.errorContainer,
                                backgroundActive = MaterialTheme.colorScheme.error,
                                iconTint = MaterialTheme.colorScheme.onError,
                                icon = painterResource(R.drawable.ic_delete_forever),
                                stayDismissed = true,
                                onDismiss = {
                                    row.transaction?.let { tx ->
                                        spendsViewModel.removeSpent(tx)
                                        pendingUndoTransaction = tx
                                        appViewModel.showSnackbar(
                                            message = "Spend deleted",
                                            actionLabel = "Undo",
                                            duration = SnackbarDuration.Long,
                                            snackbarResult = { result ->
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    pendingUndoTransaction?.let { spendsViewModel.addSpent(it) }
                                                }
                                                pendingUndoTransaction = null
                                            }
                                        )
                                    }
                                }
                            ),
                            onTried = { isUserTrySwipe = true },
                            showTutorial = index == 2 && tutorial === TUTORIAL_STAGE.READY_TO_SHOW,
                        ) { state ->
                            val size = with(LocalDensity.current) {
                                java.lang.Float.max(
                                    java.lang.Float.min(
                                        16.dp.toPx(),
                                        abs(state.offset.value)
                                    ), 0f
                                ).toDp()
                            }

                            val animateCorners by remember {
                                derivedStateOf {
                                    state.offset.value.absoluteValue > 30
                                }
                            }
                            val startCorners by animateDpAsState(
                                targetValue = when {
                                    state.dismissDirection == DismissDirection.StartToEnd &&
                                            animateCorners -> 8.dp
                                    else -> 0.dp
                                }
                            )
                            val endCorners by animateDpAsState(
                                targetValue = when {
                                    state.dismissDirection == DismissDirection.EndToStart &&
                                            animateCorners -> 8.dp
                                    else -> 0.dp
                                }
                            )

                            Box(
                                modifier = Modifier.height(IntrinsicSize.Min)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            vertical = min(
                                                size / 4f,
                                                4.dp
                                            )
                                        )
                                        .clip(RoundedCornerShape(size)),
                                    color = colorEditor,
                                    shape = RoundedCornerShape(
                                        topStart = startCorners,
                                        bottomStart = startCorners,
                                        topEnd = endCorners,
                                        bottomEnd = endCorners,
                                    ),
                                ) {
                                }
                                Box(
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    val tx = row.transaction ?: return@Box
                                    SpentItem(
                                        transaction = tx,
                                        currency = currency.value,
                                        isSelected = tx.uid in selectedIds,
                                        isSelectionMode = selectionMode,
                                        onSelect = { spendsViewModel.toggleTransactionSelection(tx.uid) },
                                    )
                                }
                            }
                        } else {
                            val tx = row.transaction ?: return@animatedItemsIndexed
                            SpentItem(
                                transaction = tx,
                                currency = currency.value,
                                isSelected = tx.uid in selectedIds,
                                isSelectionMode = selectionMode,
                                onSelect = if (selectionMode) {{ spendsViewModel.toggleTransactionSelection(tx.uid) }} else null,
                            )
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
                val message = if (allTransactions.isEmpty()) "No spends yet" else "No results matching your search"
                NoSpends(Modifier.weight(1f), message = message)
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
                            scrollState.animateScrollToItem(0)
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
