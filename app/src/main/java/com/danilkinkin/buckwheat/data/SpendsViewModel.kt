package com.danilkinkin.buckwheat.data

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.notifications.PeriodFinishScheduler
import com.danilkinkin.buckwheat.patterns.PatternDataset
import com.danilkinkin.buckwheat.patterns.PatternPeriod
import com.danilkinkin.buckwheat.patterns.PatternSpend
import com.danilkinkin.buckwheat.patterns.TagSuggestion
import com.danilkinkin.buckwheat.patterns.buildTagSuggestions
import com.danilkinkin.buckwheat.patterns.forecast
import com.danilkinkin.buckwheat.util.countDaysToToday
import com.danilkinkin.buckwheat.util.isToday
import com.danilkinkin.buckwheat.util.roundToDay
import java.util.Calendar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Date
import javax.inject.Inject

enum class RestedBudgetDistributionMethod { REST, ADD_TODAY, ASK }

// Controls how due recurring payments are recorded. OFF leaves them to the daily reminder
// notification, ASK surfaces a one-tap confirm sheet before recording, SILENT records them
// automatically (the historical behavior).
enum class RecurringAutoApplyMode { OFF, ASK, SILENT }

@HiltViewModel
class SpendsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val spendsRepository: SpendsRepository,
    private val recurringDao: RecurringDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    var tags: Flow<List<String>> = spendsRepository.getAllTags()
    var transactions: Flow<List<Transaction>> = spendsRepository.getAllTransactions()
    var spends: Flow<List<Transaction>> = spendsRepository.getAllSpends()
    val archivedTransactions: StateFlow<List<com.danilkinkin.buckwheat.data.entities.ArchivedTransaction>> =
        spendsRepository.getAllArchivedTransactions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgetPeriods: StateFlow<List<com.danilkinkin.buckwheat.data.entities.BudgetPeriod>> =
        spendsRepository.getAllBudgetPeriods()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budget: StateFlow<BigDecimal> = spendsRepository.getBudget()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal.ZERO)
    val spent: StateFlow<BigDecimal> = spendsRepository.getSpent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal.ZERO)
    val dailyBudget: StateFlow<BigDecimal> = spendsRepository.getDailyBudget()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal.ZERO)
    val spentFromDailyBudget: StateFlow<BigDecimal> = spendsRepository.getSpentFromDailyBudget()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal.ZERO)
    val startPeriodDate: StateFlow<Date> = spendsRepository.getStartPeriodDate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Date())
    val finishPeriodDate: StateFlow<Date?> = spendsRepository.getFinishPeriodDate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val finishPeriodActualDate: StateFlow<Date?> = spendsRepository.getFinishPeriodActualDate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val lastChangeDailyBudgetDate: StateFlow<Date?> = spendsRepository.getLastChangeDailyBudgetDate()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val periodSpends: StateFlow<List<Transaction>> = combine(
        spends,
        startPeriodDate,
        finishPeriodDate,
    ) { list, start, finish ->
        filterByPeriod(list, start, finish)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val periodTransactions: StateFlow<List<Transaction>> = combine(
        transactions,
        startPeriodDate,
        finishPeriodDate,
    ) { list, start, finish ->
        filterByPeriod(list, start, finish)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currency: StateFlow<ExtendCurrency> = spendsRepository.getCurrency()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExtendCurrency.none())
    val restedBudgetDistributionMethod: StateFlow<RestedBudgetDistributionMethod> =
        spendsRepository.getRestedBudgetDistributionMethod()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RestedBudgetDistributionMethod.REST)
    val hideOverspendingWarn: StateFlow<Boolean> = spendsRepository.getHideOverspendingWarn()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val restBudget: StateFlow<BigDecimal> = combine(
        budget,
        spent,
        spentFromDailyBudget,
    ) { b, s, sFromDaily ->
        b - s - sFromDaily
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal.ZERO)

    val suggestedBudget: StateFlow<BigDecimal?> = combine(
        budgetPeriods,
        archivedTransactions,
        spends,
        currency,
    ) { periods, archived, currentSpends, cur ->
        if (periods.isEmpty()) return@combine null

        val today = java.time.LocalDate.now()
        val allTransactions = archived.map { tx ->
            PatternSpend(
                date = tx.date,
                value = tx.value,
                category = tx.category,
                comment = tx.comment,
            )
        } + currentSpends.filter { it.type == TransactionType.SPENT }.map { tx ->
            PatternSpend(
                date = tx.date,
                value = tx.value,
                category = tx.category,
                comment = tx.comment,
            )
        }

        val patternPeriods = periods.map { p ->
            PatternPeriod(
                start = p.startDate,
                finish = p.finishDate,
                budget = p.budget,
                totalSpent = p.totalSpent,
                isImported = p.isImported,
            )
        }

        val dataset = PatternDataset(
            spends = allTransactions,
            periods = patternPeriods,
            currencyCode = cur.value ?: "",
            today = today,
        )

        val fc = forecast(dataset, BigDecimal.ZERO)
        val avg = fc.monthlyAverage ?: return@combine null
        if (avg <= BigDecimal.ZERO) return@combine null

        val scaled = avg.setScale(0, java.math.RoundingMode.CEILING)
        val magnitude = if (scaled >= BigDecimal(1000)) {
            BigDecimal(100)
        } else if (scaled >= BigDecimal(100)) {
            BigDecimal(10)
        } else {
            BigDecimal(5)
        }
        (scaled / magnitude).setScale(0, java.math.RoundingMode.CEILING) * magnitude
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tagSuggestions: StateFlow<List<TagSuggestion>> = combine(
        spends,
        archivedTransactions,
        budgetPeriods,
    ) { currentSpends, archived, periods ->
        val allSpends = archived.map { tx ->
            PatternSpend(date = tx.date, value = tx.value, category = tx.category, comment = tx.comment)
        } + currentSpends.filter { it.type == TransactionType.SPENT }.map { tx ->
            PatternSpend(date = tx.date, value = tx.value, category = tx.category, comment = tx.comment)
        }

        val allPeriods = periods.map {
            PatternPeriod(it.startDate, it.finishDate, it.budget, it.totalSpent, it.isImported)
        }

        val today = LocalDate.now()
        val dataset = PatternDataset(
            spends = allSpends,
            periods = allPeriods,
            currencyCode = currency.value.value ?: "",
            today = today,
        )

        buildTagSuggestions(dataset)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    var requireDistributionRestedBudget = MutableStateFlow(false)
    var requireSetBudget = MutableStateFlow(false)
    var periodFinished = MutableStateFlow(false)
    var lastRemovedTransaction: MutableStateFlow<Transaction?> = MutableStateFlow(null)
    var pendingRecurringCharges: MutableStateFlow<List<Transaction>> = MutableStateFlow(emptyList())

    private val changeDayMutex = Mutex()

    init {
        viewModelScope.launch {
            requireSetBudget.value =
                spendsRepository.getLastChangeDailyBudgetDate().first() == null
        }
        runChangeDayAction()
        runScheduledDetectChangeDayTask()
    }

    // Budget handling

    fun setBudget(newBudget: BigDecimal, newFinishDate: Date, newStartDate: Date? = null) {
        viewModelScope.launch {
            try {
                spendsRepository.setBudget(newBudget, newFinishDate, newStartDate)
            } catch (e: Exception) {
                return@launch
            }

            requireSetBudget.value = false
            periodFinished.value = false
            syncPeriodFinishAlarm(newFinishDate)
        }
    }

    fun changeBudget(newBudget: BigDecimal, newFinishDate: Date, newStartDate: Date? = null) {
        viewModelScope.launch {
            spendsRepository.changeBudget(newBudget, newFinishDate, newStartDate)

            requireSetBudget.value = false
            periodFinished.value = false
            syncPeriodFinishAlarm(newFinishDate)
        }
    }

    fun importTransactions(transactions: List<Transaction>) {
        viewModelScope.launch {
            spendsRepository.importTransactions(transactions)
        }
    }

    fun finishBudget() {
        viewModelScope.launch {
            spendsRepository.finishBudget(Date())

            requireSetBudget.value = false
            periodFinished.value = true
            // The period ended now, so the scheduled end-of-period alarm is no longer needed.
            PeriodFinishScheduler.cancel(context)
        }
    }

    fun setDailyBudget(newDailyBudget: BigDecimal) {
        viewModelScope.launch {
            spendsRepository.setDailyBudget(newDailyBudget)
        }
    }

    // Spend handling

    fun addSpent(transactionForAdd: Transaction) {
        viewModelScope.launch {
            spendsRepository.addSpent(transactionForAdd)
        }
    }

    fun removeSpent(transactionForRemove: Transaction, silent: Boolean = false) {
        viewModelScope.launch {
            spendsRepository.removeSpent(transactionForRemove)

            if (!silent) {
                lastRemovedTransaction.value = transactionForRemove
            }
        }
    }

    fun undoRemoveSpent(transaction: Transaction) {
        viewModelScope.launch {
            spendsRepository.addSpent(transaction)
        }
    }

    // Other

    fun changeDisplayCurrency(currency: ExtendCurrency) {
        viewModelScope.launch {
            spendsRepository.changeDisplayCurrency(currency)
        }
    }

    fun changeRestedBudgetDistributionMethod(method: RestedBudgetDistributionMethod) {
        viewModelScope.launch {
            spendsRepository.changeRestedBudgetDistributionMethod(method)
        }
    }

    fun hideOverspendingWarn(hide: Boolean) {
        viewModelScope.launch {
            spendsRepository.hideOverspendingWarn(hide)
        }
    }

    // Re-arms the one-shot end-of-period notification for the (possibly changed) finish date.
    // Scheduling is opt-in: with the setting off the alarm is cancelled instead. The alarm is
    // always keyed to the same request code, so a new schedule silently replaces any stale one.
    private suspend fun syncPeriodFinishAlarm(finishDate: Date) {
        val enabled = settingsRepository.isPeriodFinishEnabled().first()
        if (enabled) {
            PeriodFinishScheduler.schedule(context, finishDate)
        } else {
            PeriodFinishScheduler.cancel(context)
        }
    }

    // Need to be refactored

    fun howMuchBudgetRest(): StateFlow<BigDecimal> = restBudget

    // Background tasks
    private fun runChangeDayAction() {
        viewModelScope.launch {
            // runChangeDayAction is triggered from init AND from the 5s polling loop, and it
            // suspends on DataStore reads — without a lock two overlapping runs could both
            // pass the "day changed" check and redistribute the budget / charge recurring
            // payments twice.
            changeDayMutex.withLock {
                val lastChangeDailyBudgetDate = spendsRepository.getLastChangeDailyBudgetDate().first()
                val finishPeriodDate = spendsRepository.getFinishPeriodDate().first()
                val finishPeriodActualDate = spendsRepository.getFinishPeriodActualDate().first()
                val dailyBudget = spendsRepository.getDailyBudget().first()
                val spentFromDailyBudget = spendsRepository.getSpentFromDailyBudget().first()
                val restedBudgetDistributionMethod =
                    spendsRepository.getRestedBudgetDistributionMethod().first()

                val finishDayNotReached = if (finishPeriodActualDate === null) {
                    finishPeriodDate !== null
                            && countDaysToToday(finishPeriodDate) > 0
                } else {
                    countDaysToToday(finishPeriodActualDate) > 0
                }

                val finishTimeReached = if (finishPeriodActualDate === null) {
                    finishPeriodDate !== null
                            && finishPeriodDate.time <= Date().time
                } else {
                    finishPeriodActualDate.time <= Date().time
                }

                when {
                    lastChangeDailyBudgetDate !== null
                            && !isToday(lastChangeDailyBudgetDate)
                            && finishDayNotReached -> {
                        if (dailyBudget - spentFromDailyBudget > BigDecimal.ZERO) {
                            when (restedBudgetDistributionMethod) {
                                RestedBudgetDistributionMethod.ASK -> {
                                    requireDistributionRestedBudget.value = true
                                    // One-shot per day: even if the user dismisses the sheet,
                                    // don't re-evaluate this branch every poll (which used to
                                    // freeze the daily budget and double-charge recurring payments).
                                    spendsRepository.markDailyBudgetDistributionHandled()
                                }

                                RestedBudgetDistributionMethod.REST -> {
                                    val whatBudgetForDay =
                                        spendsRepository.whatBudgetForDay(applyTodaySpends = true)
                                    setDailyBudget(whatBudgetForDay)
                                }

                                RestedBudgetDistributionMethod.ADD_TODAY -> {
                                    val notSpent = spendsRepository.howMuchNotSpent(
                                        excludeSkippedPart = true,
                                    )

                                    setDailyBudget(notSpent)
                                }
                            }
                        } else {
                            val whatBudgetForDay =
                                spendsRepository.whatBudgetForDay(applyTodaySpends = true)
                            setDailyBudget(whatBudgetForDay)
                        }
                    }

                    lastChangeDailyBudgetDate === null -> {
                        requireSetBudget.value = true
                    }

                    finishTimeReached -> {
                        periodFinished.value = true
                    }
                }

                processDueRecurringPayments()

                val newDailyBudget = spendsRepository.getDailyBudget().first()
                val newSpentFromDailyBudget = spendsRepository.getSpentFromDailyBudget().first()

                // Bug fix: hide the overspending warning again once today is back under budget
                if (newDailyBudget - newSpentFromDailyBudget > BigDecimal.ZERO) {
                    hideOverspendingWarn(false)
                }
            }
        }
    }

    // Applies recurring payments for every day since the last application, so payments
    // are never skipped when the app is closed on the due day. Self-guarded by a dedicated
    // DataStore key, independent of the budget-distribution date. The mode setting decides
    // what happens to the due payments: OFF skips recording, SILENT records them, ASK queues
    // them for the confirm sheet via pendingRecurringCharges.
    private suspend fun processDueRecurringPayments() {
        val lastApplied = spendsRepository.getLastRecurringAppliedDate().first()
        val today = roundToDay(Date())
        val finishPeriodDate = spendsRepository.getFinishPeriodDate().first()
            ?: return
        if (finishPeriodDate.time < today.time) return

        // First run: seed without charging retroactively
        if (lastApplied == null) {
            spendsRepository.setLastRecurringAppliedDate(today)
            return
        }

        val mode = settingsRepository.getRecurringAutoApplyMode().first()

        var cursor = roundToDay(lastApplied)
        var guard = 0
        val maxBackfillDays = 366
        val dueTransactions: MutableList<Transaction> = mutableListOf()
        while (cursor.time < today.time && guard < maxBackfillDays) {
            val calendar = Calendar.getInstance().apply { time = cursor }
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            val dueTemplates = recurringDao.getDueOnDay(dayOfMonth)
            if (dueTemplates.isNotEmpty()) {
                dueTemplates.forEach { template ->
                    dueTransactions += Transaction(
                        type = TransactionType.SPENT,
                        value = template.amount,
                        date = Date(cursor.time),
                        comment = template.comment,
                    )
                }
            }
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            cursor = calendar.time
            guard++
        }

        // The marker advances in every mode so each due payment is evaluated exactly once
        // per day. In ASK mode the queue survives only while the app is running; a dismissed
        // or skipped sheet simply drops the pending payments for that day.
        spendsRepository.setLastRecurringAppliedDate(today)

        if (dueTransactions.isEmpty()) return

        when (mode) {
            RecurringAutoApplyMode.SILENT -> {
                dueTransactions.forEach { spendsRepository.addSpent(it) }
            }

            RecurringAutoApplyMode.ASK -> {
                pendingRecurringCharges.value = dueTransactions
            }

            RecurringAutoApplyMode.OFF -> {
                // Left to the daily reminder notification; nothing is recorded automatically.
            }
        }
    }

    fun confirmRecurringCharges() {
        val pending = pendingRecurringCharges.value.orEmpty()
        viewModelScope.launch {
            pending.forEach { spendsRepository.addSpent(it) }
            pendingRecurringCharges.value = emptyList()
        }
    }

    fun skipRecurringCharges() {
        pendingRecurringCharges.value = emptyList()
    }

    private fun filterByPeriod(
        list: List<Transaction>,
        startDate: Date?,
        finishDate: Date?,
    ): List<Transaction> {
        if (startDate == null || finishDate == null) return list
        return list.filter { !it.date.before(startDate) && !it.date.after(finishDate) }
    }

    private fun runScheduledDetectChangeDayTask() {
        var currentDay = Date()

        viewModelScope.launch {
            while (true) {
                delay(5000L)

                if (isToday(currentDay)) continue

                currentDay = Date()
                runChangeDayAction()
            }
        }
    }
}
