package com.danilkinkin.buckwheat.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.util.countDaysToToday
import com.danilkinkin.buckwheat.util.isToday
import com.danilkinkin.buckwheat.util.roundToDay
import java.util.Calendar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject

enum class RestedBudgetDistributionMethod { REST, ADD_TODAY, ASK }

@HiltViewModel
class SpendsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val spendsRepository: SpendsRepository,
    private val recurringDao: RecurringDao,
) : ViewModel() {
    var tags = spendsRepository.getAllTags()
    var transactions = spendsRepository.getAllTransactions()
    var spends = spendsRepository.getAllSpends()
    var budget = spendsRepository.getBudget().asLiveData()
    var spent = spendsRepository.getSpent().asLiveData()
    var dailyBudget = spendsRepository.getDailyBudget().asLiveData()
    var spentFromDailyBudget = spendsRepository.getSpentFromDailyBudget().asLiveData()
    var startPeriodDate = spendsRepository.getStartPeriodDate().asLiveData()
    var finishPeriodDate = spendsRepository.getFinishPeriodDate().asLiveData()
    var finishPeriodActualDate = spendsRepository.getFinishPeriodActualDate().asLiveData()
    var lastChangeDailyBudgetDate = spendsRepository.getLastChangeDailyBudgetDate().asLiveData()

    val periodSpends: LiveData<List<Transaction>> = MediatorLiveData<List<Transaction>>().apply {
        value = emptyList()

        var lastSpends: List<Transaction> = emptyList()
        var lastStart: Date? = null
        var lastFinish: Date? = null

        addSource(spends) { list ->
            lastSpends = list
            if (lastStart != null && lastFinish != null) {
                value = filterByPeriod(list, lastStart, lastFinish)
            }
        }
        addSource(startPeriodDate) { date ->
            lastStart = date
            if (lastFinish != null) {
                value = filterByPeriod(lastSpends, date, lastFinish)
            }
        }
        addSource(finishPeriodDate) { date ->
            lastFinish = date
            if (lastStart != null) {
                value = filterByPeriod(lastSpends, lastStart, date)
            }
        }
    }

    val periodTransactions: LiveData<List<Transaction>> = MediatorLiveData<List<Transaction>>().apply {
        value = emptyList()

        var lastTransactions: List<Transaction> = emptyList()
        var lastStart: Date? = null
        var lastFinish: Date? = null

        addSource(transactions) { list ->
            lastTransactions = list
            if (lastStart != null && lastFinish != null) {
                value = filterByPeriod(list, lastStart, lastFinish)
            }
        }
        addSource(startPeriodDate) { date ->
            lastStart = date
            if (lastFinish != null) {
                value = filterByPeriod(lastTransactions, date, lastFinish)
            }
        }
        addSource(finishPeriodDate) { date ->
            lastFinish = date
            if (lastStart != null) {
                value = filterByPeriod(lastTransactions, lastStart, date)
            }
        }
    }

    var currency = spendsRepository.getCurrency().asLiveData()
    var restedBudgetDistributionMethod =
        spendsRepository.getRestedBudgetDistributionMethod().asLiveData()
    var hideOverspendingWarn = spendsRepository.getHideOverspendingWarn().asLiveData()

    var restBudget: LiveData<BigDecimal> = MediatorLiveData<BigDecimal>().apply {
        // Emit nothing until every source has produced its first value, so the widget
        // never flashes an intermediate (wrong) "rest" while the DataStore flows stream in.
        var budgetReady = false
        var spentReady = false
        var spentFromDailyBudgetReady = false
        var lastBudget: BigDecimal = BigDecimal.ZERO
        var lastSpent: BigDecimal = BigDecimal.ZERO
        var lastSpentFromDailyBudget: BigDecimal = BigDecimal.ZERO

        fun update() {
            if (budgetReady && spentReady && spentFromDailyBudgetReady) {
                value = lastBudget - lastSpent - lastSpentFromDailyBudget
            }
        }

        addSource(budget) { b ->
            lastBudget = b
            budgetReady = true
            update()
        }
        addSource(spent) { s ->
            lastSpent = s
            spentReady = true
            update()
        }
        addSource(spentFromDailyBudget) { s ->
            lastSpentFromDailyBudget = s
            spentFromDailyBudgetReady = true
            update()
        }
    }

    var requireDistributionRestedBudget = MutableLiveData(false)
    var requireSetBudget = MutableLiveData(false)
    var periodFinished = MutableLiveData(false)
    var lastRemovedTransaction: MutableLiveData<Transaction> = MutableLiveData()

    init {
        viewModelScope.launch {
            requireSetBudget.value =
                spendsRepository.getLastChangeDailyBudgetDate().first() == null
        }
        runChangeDayAction()
        runScheduledDetectChangeDayTask()
    }

    // Budget handling

    fun setBudget(newBudget: BigDecimal, newFinishDate: Date) {
        viewModelScope.launch {
            try {
                spendsRepository.setBudget(newBudget, newFinishDate)
            } catch (e: Exception) {
                return@launch
            }

            requireSetBudget.value = false
            periodFinished.value = false
        }
    }

    fun changeBudget(newBudget: BigDecimal, newFinishDate: Date) {
        viewModelScope.launch {
            spendsRepository.changeBudget(newBudget, newFinishDate)

            requireSetBudget.value = false
            periodFinished.value = false
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

    fun undoRemoveSpent() {
        viewModelScope.launch {
            lastRemovedTransaction.value?.let {
                spendsRepository.addSpent(it)
            }
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

    // Need to be refactored

    fun howMuchBudgetRest(): LiveData<BigDecimal> = restBudget

    // Background tasks
    private val changeDayMutex = Mutex()

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

                // Bug fix https://github.com/danilkinkin/buckwheat/issues/28
                if (dailyBudget - spentFromDailyBudget > BigDecimal.ZERO) {
                    hideOverspendingWarn(false)
                }
            }
        }
    }

    // Applies recurring payments for every day since the last application, so payments
    // are never skipped when the app is closed on the due day. Self-guarded by a dedicated
    // DataStore key, independent of the budget-distribution date.
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

        var cursor = roundToDay(lastApplied)
        var guard = 0
        val maxBackfillDays = 366
        while (cursor.time < today.time && guard < maxBackfillDays) {
            val calendar = Calendar.getInstance().apply { time = cursor }
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            val dueTemplates = recurringDao.getDueOnDay(dayOfMonth)
            if (dueTemplates.isNotEmpty()) {
                dueTemplates.forEach { template ->
                    spendsRepository.addSpent(
                        Transaction(
                            type = TransactionType.SPENT,
                            value = template.amount,
                            date = Date(cursor.time),
                            comment = template.comment,
                        )
                    )
                }
            }
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            cursor = calendar.time
            guard++
        }

        spendsRepository.setLastRecurringAppliedDate(today)
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
