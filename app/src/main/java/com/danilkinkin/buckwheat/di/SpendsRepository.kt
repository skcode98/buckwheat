package com.danilkinkin.buckwheat.di

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.map
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.RestedBudgetDistributionMethod
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.util.DAY
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.errorForReport
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.isSameDay
import com.danilkinkin.buckwheat.util.roundToDay
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDateTime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import java.lang.Long.min
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.Date
import javax.inject.Inject

val currencyStoreKey = stringPreferencesKey("currency")
val restedBudgetDistributionMethodStoreKey = stringPreferencesKey("restedBudgetDistributionMethod")
val hideOverspendingWarnStoreKey = booleanPreferencesKey("hideOverspendingWarn")

val budgetStoreKey = stringPreferencesKey("budget")
val spentStoreKey = stringPreferencesKey("spent")
val dailyBudgetStoreKey = stringPreferencesKey("dailyBudget")
val spentFromDailyBudgetStoreKey = stringPreferencesKey("spentFromDailyBudget")
val lastChangeDailyBudgetDateStoreKey = longPreferencesKey("lastChangeDailyBudgetDate")
val lastRecurringAppliedDateStoreKey = longPreferencesKey("lastRecurringAppliedDate")
val startPeriodDateStoreKey = longPreferencesKey("startPeriodDate")
val finishPeriodDateStoreKey = longPreferencesKey("finishPeriodDate")
val finishPeriodActualDateStoreKey = longPreferencesKey("finishPeriodActualDate")

class SpendsRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val transactionDao: TransactionDao,
    private val savedTagDao: SavedTagDao,
    private val budgetPeriodDao: BudgetPeriodDao,
    private val getCurrentDateUseCase: GetCurrentDateUseCase,
) {
    fun getAllTransactions(): LiveData<List<Transaction>> = transactionDao.getAll()
    fun getAllArchivedTransactions(): LiveData<List<ArchivedTransaction>> = budgetPeriodDao.getAllArchived()
    fun getAllSpends(): LiveData<List<Transaction>> = transactionDao.getAll(TransactionType.SPENT)
    fun getTransactionsInRange(startDate: Date, endDate: Date): LiveData<List<Transaction>> =
        transactionDao.getAll(startDate.time, endDate.time)
    fun getSpendsInRange(startDate: Date, endDate: Date): LiveData<List<Transaction>> =
        transactionDao.getAll(TransactionType.SPENT, startDate.time, endDate.time)

    fun getAllTags(): LiveData<List<String>> {
        val merged = MediatorLiveData<List<String>>()
        val transactionSource = transactionDao.getAll().map { transactions ->
            transactions
                .asSequence()
                .filter { transaction -> transaction.comment.isNotEmpty() }
                .groupBy { it.comment }
                .map { it.key to it.value.size }
                .sortedBy { -it.second }
                .map { it.first }
                .distinct()
                .toList()
        }
        val savedSource = savedTagDao.getAll().map { tags -> tags.map { it.name } }

        var lastTransactionTags: List<String> = emptyList()
        var lastSavedTags: List<String> = emptyList()

        merged.addSource(transactionSource) { tags ->
            lastTransactionTags = tags
            merged.value = (lastTransactionTags + lastSavedTags).distinct()
        }
        merged.addSource(savedSource) { tags ->
            lastSavedTags = tags
            merged.value = (lastTransactionTags + lastSavedTags).distinct()
        }

        return merged
    }

    fun getBudget() = context.budgetDataStore.data.map {
        (it[budgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN)
    }

    fun getSpent() = context.budgetDataStore.data.map {
        (it[spentStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN)
    }

    fun getDailyBudget() = context.budgetDataStore.data.map {
        (it[dailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN)
    }

    fun getSpentFromDailyBudget() = context.budgetDataStore.data.map {
        (it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN)
    }

    fun getStartPeriodDate() = context.budgetDataStore.data.map {
        it[startPeriodDateStoreKey]?.let { value -> Date(value) } ?: getCurrentDateUseCase()
    }

    fun getFinishPeriodDate() = context.budgetDataStore.data.map {
        it[finishPeriodDateStoreKey]?.let { value -> Date(value) }
    }

    fun getFinishPeriodActualDate() = context.budgetDataStore.data.map {
        it[finishPeriodActualDateStoreKey]?.let { value -> Date(value) }
    }

    fun getLastChangeDailyBudgetDate() = context.budgetDataStore.data.map {
        it[lastChangeDailyBudgetDateStoreKey]?.let { value -> Date(value) }
    }

    fun getLastRecurringAppliedDate() = context.budgetDataStore.data.map {
        it[lastRecurringAppliedDateStoreKey]?.let { value -> Date(value) }
    }

    // Records that the daily-budget redistribution prompt was handled for today, without
    // folding spentFromDailyBudget (used when the user dismisses the ASK sheet).
    suspend fun markDailyBudgetDistributionHandled() {
        context.budgetDataStore.edit {
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
        }
    }

    suspend fun setLastRecurringAppliedDate(date: Date) {
        context.budgetDataStore.edit {
            it[lastRecurringAppliedDateStoreKey] = roundToDay(date).time
        }
    }

    fun getCurrency() = context.budgetDataStore.data.map {
        it[currencyStoreKey]?.let { value ->
            ExtendCurrency.getInstance(value)
        } ?: ExtendCurrency(value = null, type = ExtendCurrency.Type.NONE)
    }

    fun getRestedBudgetDistributionMethod() = context.budgetDataStore.data.map { it ->
        it[restedBudgetDistributionMethodStoreKey]?.let {
            RestedBudgetDistributionMethod.valueOf(it)
        } ?: RestedBudgetDistributionMethod.ASK
    }

    fun getHideOverspendingWarn() = context.budgetDataStore.data.map {
        it[hideOverspendingWarnStoreKey] ?: false
    }


    suspend fun changeDisplayCurrency(currency: ExtendCurrency) {
        context.budgetDataStore.edit {
            it[currencyStoreKey] = currency.value ?: ""
        }
    }

    suspend fun changeRestedBudgetDistributionMethod(method: RestedBudgetDistributionMethod) {
        context.budgetDataStore.edit {
            it[restedBudgetDistributionMethodStoreKey] = method.toString()
        }
    }

    suspend fun hideOverspendingWarn(hide: Boolean) {
        context.budgetDataStore.edit {
            it[hideOverspendingWarnStoreKey] = hide
        }
    }

    suspend fun setBudget(newBudget: BigDecimal, newFinishDate: Date) {
        val oldSpent = getSpent().firstOrNull() ?: BigDecimal.ZERO
        val hasStoredTransactions = transactionDao.getAll().asFlow().first()
            .any { it.type == TransactionType.SPENT }
        if (oldSpent > BigDecimal.ZERO || hasStoredTransactions) {
            archiveCurrentPeriod()
        }

        context.budgetDataStore.edit {
            it[budgetStoreKey] = newBudget.toString()
            it[spentStoreKey] = BigDecimal.ZERO.toString()
            it[dailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[spentFromDailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[startPeriodDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[finishPeriodDateStoreKey] = Date(roundToDay(newFinishDate).time + DAY - 1000).time
            it.remove(finishPeriodActualDateStoreKey)

            Log.d(
                "SpendsRepository",
                "Set budget ["
                        + "budget: ${it[budgetStoreKey]} "
                        + "start date: ${Date(it[startPeriodDateStoreKey]!!)} "
                        + "finish date: ${Date(it[finishPeriodDateStoreKey]!!)}"
                        + "]"
            )
        }

        transactionDao.deleteAllAndInsert(
            Transaction(
                TransactionType.INCOME,
                newBudget,
                getCurrentDateUseCase(),
            )
        )

        setDailyBudget(whatBudgetForDay())

        hideOverspendingWarn(false)
    }

    private suspend fun archiveCurrentPeriod() {
        val transactions = transactionDao.getAll().asFlow().firstOrNull() ?: emptyList()
        if (transactions.isEmpty()) return

        val startDate = getStartPeriodDate().firstOrNull()
            ?: transactions.minOf { it.date }
        val finishDate = getFinishPeriodDate().firstOrNull()
            ?: transactions.maxOf { it.date }

        // Only archive transactions that belong to this period. Out-of-period rows
        // (e.g. CSV imports for the next period, recurring backfill before the start)
        // must stay in the active table — they are scope-guarded and must not be
        // archived into a past period.
        val inPeriod = transactions.filter {
            !it.date.before(startDate) && !it.date.after(finishDate)
        }
        val spends = inPeriod.filter { it.type == TransactionType.SPENT }

        if (inPeriod.isEmpty()) return

        val oldBudget = getBudget().firstOrNull() ?: BigDecimal.ZERO
        val actualFinishDate = getFinishPeriodActualDate().firstOrNull()
        val currencyCode = currentCurrencyCode()

        val totalSpent = spends.map { it.value }.fold(BigDecimal.ZERO) { acc, v -> acc + v }

        val periodId = budgetPeriodDao.insert(
            BudgetPeriod(
                budget = oldBudget,
                startDate = startDate,
                finishDate = finishDate,
                actualFinishDate = actualFinishDate,
                currencyCode = currencyCode,
                totalSpent = totalSpent,
            )
        )

        budgetPeriodDao.insertArchivedTransactions(
            inPeriod.map { tx ->
                ArchivedTransaction(
                    periodId = periodId.toInt(),
                    type = tx.type,
                    value = tx.value,
                    date = tx.date,
                    comment = tx.comment,
                )
            }
        )

        Log.d(
            "SpendsRepository",
            "Archived period #$periodId with ${inPeriod.size} transactions "
                    + "(${transactions.size - inPeriod.size} out-of-period kept)"
        )
    }

    suspend fun changeBudget(newBudget: BigDecimal, newFinishDate: Date) {
        context.budgetDataStore.edit {
            it[budgetStoreKey] = newBudget.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[finishPeriodDateStoreKey] = Date(roundToDay(newFinishDate).time + DAY - 1000).time
            it.remove(finishPeriodActualDateStoreKey)

            Log.d(
                "SpendsRepository",
                "Change budget ["
                        + "budget: ${it[budgetStoreKey]} "
                        + "start date: ${Date(it[startPeriodDateStoreKey]!!)} "
                        + "finish date: ${Date(it[finishPeriodDateStoreKey]!!)}"
                        + "]"
            )
        }

        transactionDao.getAll(TransactionType.INCOME).asFlow().first().firstOrNull()
            ?.let { incomeTransaction ->
                transactionDao.update(incomeTransaction.copy(value = newBudget))
            }

        updateDailyBudget(whatBudgetForDay())
    }

    suspend fun finishBudget(finishDate: Date) {
        context.budgetDataStore.edit {
            it[finishPeriodActualDateStoreKey] = finishDate.time

            Log.d(
                "SpendsRepository",
                "Finish budget ["
                        + "budget: ${it[budgetStoreKey]} "
                        + "start date: ${Date(it[startPeriodDateStoreKey]!!)} "
                        + "actual finish date: ${Date(it[finishPeriodActualDateStoreKey]!!)}"
                        + "finish date: ${Date(it[finishPeriodDateStoreKey]!!)}"
                        + "]"
            )
        }
    }

    suspend fun updateDailyBudget(newDailyBudget: BigDecimal) {
        context.budgetDataStore.edit {
            it[dailyBudgetStoreKey] = newDailyBudget.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time

            Log.d(
                "SpendsRepository",
                "Update daily budget ["
                        + "daily budget: ${it[dailyBudgetStoreKey]} "
                        + "spent: ${it[spentStoreKey]}"
                        + "]"
            )
        }


        transactionDao.getAll(TransactionType.SET_DAILY_BUDGET).asFlow().first().lastOrNull()
            ?.let { setDailyBudgetTransaction ->
                transactionDao.update(setDailyBudgetTransaction.copy(value = newDailyBudget))
            }
    }

    suspend fun setDailyBudget(newDailyBudget: BigDecimal) {
        context.budgetDataStore.edit {
            val spent: BigDecimal = it[spentStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
            val spentFromDailyBudget: BigDecimal =
                it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO

            it[dailyBudgetStoreKey] = newDailyBudget.toString()
            it[spentStoreKey] = (spent + spentFromDailyBudget).toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[spentFromDailyBudgetStoreKey] = BigDecimal.ZERO.toString()

            Log.d(
                "SpendsRepository",
                "Set daily budget ["
                        + "daily budget: ${it[dailyBudgetStoreKey]} "
                        + "spent: ${it[spentStoreKey]}"
                        + "]"
            )
        }

        transactionDao.insert(
            Transaction(
                TransactionType.SET_DAILY_BUDGET,
                newDailyBudget,
                getCurrentDateUseCase(),
            )
        )
    }

    suspend fun whatBudgetForDay(
        excludeCurrentDay: Boolean = false,
        applyTodaySpends: Boolean = false,
        notCommittedSpent: BigDecimal = BigDecimal.ZERO
    ): BigDecimal {
        val budget = getBudget().first()
        val spent = getSpent().first()
        val dailyBudget = getDailyBudget().first()
        val spentFromDailyBudget = getSpentFromDailyBudget().first()
        val finishPeriodDate =
            getFinishPeriodDate().first() ?: return BigDecimal.ZERO

        val restDays =
            countDays(finishPeriodDate, getCurrentDateUseCase()) - if (excludeCurrentDay) 1 else 0
        var restBudget = budget - spent

        restBudget -= notCommittedSpent

        if (applyTodaySpends) {
            restBudget -= spentFromDailyBudget
        } else if (excludeCurrentDay) {
            restBudget -= dailyBudget
        }

        val whatBudgetForDay = restBudget
            .divide(
                restDays.toBigDecimal().coerceAtLeast(BigDecimal(1)),
                2,
                RoundingMode.HALF_EVEN
            )

        Log.d(
            "SpendsRepository",
            "Check what budget for day ["
                    + "date: ${getCurrentDateUseCase()} "
                    + "what budget for day: $whatBudgetForDay "
                    + "excludeCurrentDay: $excludeCurrentDay "
                    + "applyTodaySpends: $applyTodaySpends "
                    + "notCommittedSpent: $notCommittedSpent "
                    + "budget: $budget "
                    + "spent: $spent "
                    + "daily budget: $dailyBudget "
                    + "spent from daily budget: $spentFromDailyBudget "
                    + "rest budget: $restBudget "
                    + "rest days: $restDays"
                    + "]"
        )

        return whatBudgetForDay
    }

    suspend fun howMuchBudgetRest(): BigDecimal {
        val budget = getBudget().first()
        val spent = getSpent().first()
        val spentFromDailyBudget = getSpentFromDailyBudget().first()

        return budget - spent - spentFromDailyBudget
    }

    suspend fun howMuchNotSpent(
        excludeSkippedPart: Boolean = false,
    ): BigDecimal {
        val budget = getBudget().first()
        val spent = getSpent().first()
        val dailyBudget = getDailyBudget().first()
        val spentFromDailyBudget = getSpentFromDailyBudget().first()
        val finishPeriodDate =
            getFinishPeriodDate().first() ?: return BigDecimal.ZERO
        val lastChangeDailyBudgetDate =
            getLastChangeDailyBudgetDate().first() ?: getStartPeriodDate().first()


        val restDays = countDays(finishPeriodDate, getCurrentDateUseCase()).coerceAtLeast(0)
        val skippedDays = countDays(
            Date(min(getCurrentDateUseCase().time, finishPeriodDate.time)),
            lastChangeDailyBudgetDate
        ) - 1

        var restBudget = budget - spent

        val howMuchNotSpent = if (restDays == 0) {
            restBudget - spentFromDailyBudget
        } else if (excludeSkippedPart) {
            restBudget
                .minus(dailyBudget * skippedDays.toBigDecimal())
                .divide(
                    (restDays).coerceAtLeast(1).toBigDecimal(),
                    2,
                    RoundingMode.HALF_EVEN,
                )
                .multiply((skippedDays).coerceAtLeast(0).toBigDecimal())
                .plus(dailyBudget - spentFromDailyBudget)
        } else {
            restBudget
                .minus(dailyBudget)
                .divide(
                    (restDays + skippedDays - 1).coerceAtLeast(1).toBigDecimal(),
                    2,
                    RoundingMode.HALF_EVEN,
                )
                .multiply((skippedDays).coerceAtLeast(0).toBigDecimal())
                .plus(dailyBudget - spentFromDailyBudget)
        }

        Log.d(
            "SpendsRepository",
            "How much not spent check ["
                    + "how much not spent: $howMuchNotSpent "
                    + "rest budget: $restBudget "
                    + "restDays: $restDays "
                    + "skippedDays: $skippedDays "
                    + "lastChangeDailyBudgetDate: $lastChangeDailyBudgetDate "
                    + "getCurrentDateUseCase: ${getCurrentDateUseCase()} "
                    + "dailyBudget: $dailyBudget "
                    + "spentFromDailyBudget: $spentFromDailyBudget "
                    + "]"
        )

        return howMuchNotSpent
    }

    suspend fun nextDayBudget(
        excludeSkippedPart: Boolean = false,
    ): BigDecimal {
        val budget = getBudget().first()
        val spent = getSpent().first()
        val dailyBudget = getDailyBudget().first()
        val spentFromDailyBudget = getSpentFromDailyBudget().first()
        val finishPeriodDate =
            getFinishPeriodDate().first() ?: return BigDecimal.ZERO
        val lastChangeDailyBudgetDate =
            getLastChangeDailyBudgetDate().first() ?: getStartPeriodDate().first()


        val restDays = countDays(finishPeriodDate, getCurrentDateUseCase()).coerceAtLeast(0)
        val skippedDays = countDays(
            Date(min(getCurrentDateUseCase().time, finishPeriodDate.time)),
            lastChangeDailyBudgetDate
        ) - 1

        var restBudget = budget - spent

        val nextDailyBudget = if (restDays == 0) {
            restBudget - spentFromDailyBudget
        } else if (excludeSkippedPart) {
            restBudget
                .minus(dailyBudget * skippedDays.toBigDecimal())
                .divide(
                    (restDays).coerceAtLeast(1).toBigDecimal(),
                    2,
                    RoundingMode.HALF_EVEN,
                )
        } else {
            restBudget
                .minus(dailyBudget)
                .divide(
                    (restDays + skippedDays - 1).coerceAtLeast(1).toBigDecimal(),
                    2,
                    RoundingMode.HALF_EVEN,
                )
        }

        Log.d(
            "SpendsRepository",
            "Next day budget ["
                    + "next daily budget: $nextDailyBudget "
                    + "rest budget: $restBudget "
                    + "restDays: $restDays "
                    + "skippedDays: $skippedDays "
                    + "lastChangeDailyBudgetDate: $lastChangeDailyBudgetDate "
                    + "getCurrentDateUseCase: ${getCurrentDateUseCase()} "
                    + "dailyBudget: $dailyBudget "
                    + "spentFromDailyBudget: $spentFromDailyBudget "
                    + "]"
        )

        return nextDailyBudget
    }

    suspend fun addSpent(newTransaction: Transaction) {
        this.transactionDao.insert(newTransaction)

        context.budgetDataStore.edit {
            val startPeriodDate = it[startPeriodDateStoreKey]
                ?.let { value -> Date(value) } ?: return@edit
            val finishPeriodDate = it[finishPeriodDateStoreKey]
                ?.let { value -> Date(value) } ?: return@edit

            if (newTransaction.date.before(startPeriodDate) || newTransaction.date.after(finishPeriodDate)) {
                return@edit
            }

            val dailyBudget = it[dailyBudgetStoreKey]?.toBigDecimal() ?: return@edit
            val spent = it[spentStoreKey]?.toBigDecimal() ?: return@edit
            val spentFromDailyBudget = it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: return@edit

            try {
                if (isSameDay(newTransaction.date, getCurrentDateUseCase())) {
                    it[spentFromDailyBudgetStoreKey] =
                        (spentFromDailyBudget + newTransaction.value).toString()
                } else {
                    val restDays = countDays(finishPeriodDate, getCurrentDateUseCase())
                        .coerceAtLeast(1)
                    val spreadDeltaSpentPerRestDays = newTransaction.value
                        .divide(
                            restDays.toBigDecimal(),
                            2,
                            RoundingMode.HALF_EVEN,
                        )

                    Log.d(
                        "SpendsRepository",
                        "Add spent for previous day ["
                                + "spent: $spent "
                                + "dailyBudget: $dailyBudget "
                                + "spreadDeltaSpentPerRestDays: $spreadDeltaSpentPerRestDays "
                                + "spentDate: ${newTransaction.date} "
                                + "getCurrentDateUseCase: ${getCurrentDateUseCase()} "
                                + "countDays: $restDays "
                                + "]"
                    )

                    it[dailyBudgetStoreKey] = (dailyBudget - spreadDeltaSpentPerRestDays).toString()
                    it[spentStoreKey] = (spent + newTransaction.value).toString()
                }
            } catch (e: Exception) {
                context.errorForReport = e.stackTraceToString()
            }
        }
    }

    suspend fun importTransactions(transactions: List<Transaction>) {
        // Idempotency: skip rows that already exist (same type, value, date, comment) and
        // dedupe repeated rows within the file itself, so re-importing the same CSV never
        // creates duplicate transactions. Already-archived rows (from a previous import)
        // are part of the existing set too.
        val existingKeys = buildSet {
            transactionDao.getAll().asFlow().first().forEach { tx ->
                add("${tx.type}|${tx.value}|${tx.date.time}|${tx.comment}")
            }
            budgetPeriodDao.getAllArchivedNow().forEach { tx ->
                add("${tx.type}|${tx.value}|${tx.date.time}|${tx.comment}")
            }
        }

        val unique = LinkedHashMap<String, Transaction>()
        transactions.forEach { tx ->
            val key = "${tx.type}|${tx.value}|${tx.date.time}|${tx.comment}"
            if (key !in existingKeys && key !in unique) {
                unique[key] = tx
            }
        }
        val filtered = unique.values.toList()
        if (filtered.isEmpty()) return

        val currentPeriodStart = context.budgetDataStore.data.first()[startPeriodDateStoreKey]
            ?.let { Date(it) }
        val currentPeriodFinish = context.budgetDataStore.data.first()[finishPeriodDateStoreKey]
            ?.let { Date(it) }

        if (currentPeriodStart == null || currentPeriodFinish == null) {
            filtered.forEach { addSpent(it) }
            return
        }

        val inPeriod = filtered.filter {
            !it.date.before(currentPeriodStart) && !it.date.after(currentPeriodFinish)
        }
        val outOfPeriod = filtered.filter {
            it.date.before(currentPeriodStart) || it.date.after(currentPeriodFinish)
        }

        inPeriod.forEach { addSpent(it) }
        archiveImported(outOfPeriod)
    }

    // Rows that fall outside the active budget period are archived into month buckets
    // (grouped by calendar month) or merged into an already-archived period that covers
    // their date. They must never touch the active table, so they stay out of the budget.
    private suspend fun archiveImported(outOfPeriod: List<Transaction>) {
        if (outOfPeriod.isEmpty()) return

        val existingPeriods = budgetPeriodDao.getAllNow().sortedBy { it.isImported }
        val currencyCode = currentCurrencyCode()
        val monthBucketIds = mutableMapOf<YearMonth, Int>()
        val rowsByPeriod = mutableMapOf<Int, MutableList<Transaction>>()

        outOfPeriod.forEach { tx ->
            val month = YearMonth.from(tx.date.toLocalDateTime())
            val coveringPeriod = existingPeriods.firstOrNull { period ->
                !tx.date.before(period.startDate) && !tx.date.after(period.finishDate)
            }

            val periodId = coveringPeriod?.id ?: monthBucketIds.getOrPut(month) {
                budgetPeriodDao.insert(
                    BudgetPeriod(
                        budget = BigDecimal.ZERO,
                        startDate = month.atDay(1).toDate(),
                        finishDate = Date(month.atEndOfMonth().atTime(23, 59, 59).toDate().time + 999),
                        actualFinishDate = null,
                        currencyCode = currencyCode,
                        totalSpent = BigDecimal.ZERO,
                        isImported = true,
                    )
                ).toInt()
            }

            rowsByPeriod.getOrPut(periodId) { mutableListOf() }.add(tx)
        }

        rowsByPeriod.forEach { (periodId, rows) ->
            val spentDelta = rows
                .filter { it.type == TransactionType.SPENT }
                .fold(BigDecimal.ZERO) { acc, tx -> acc + tx.value }
            val currentTotal = budgetPeriodDao.getById(periodId)?.totalSpent ?: BigDecimal.ZERO
            if (spentDelta > BigDecimal.ZERO) {
                budgetPeriodDao.updateTotalSpent(periodId, (currentTotal + spentDelta).setScale(2))
            }
            budgetPeriodDao.insertArchivedTransactions(
                rows.map { tx ->
                    ArchivedTransaction(
                        periodId = periodId,
                        type = tx.type,
                        value = tx.value,
                        date = tx.date,
                        comment = tx.comment,
                    )
                }
            )
        }
    }

    private suspend fun currentCurrencyCode(): String {
        val currency = getCurrency().firstOrNull()
        return when (currency?.type) {
            ExtendCurrency.Type.CUSTOM -> currency.value ?: ""
            ExtendCurrency.Type.NONE -> ""
            else -> currency?.value ?: ""
        }
    }

    suspend fun removeSpent(transactionForRemove: Transaction) {
        this.transactionDao.deleteById(transactionForRemove.uid)

        context.budgetDataStore.edit {
            val startPeriodDate = it[startPeriodDateStoreKey]
                ?.let { value -> Date(value) } ?: return@edit
            val finishPeriodDate = it[finishPeriodDateStoreKey]
                ?.let { value -> Date(value) } ?: return@edit

            if (transactionForRemove.date.before(startPeriodDate) || transactionForRemove.date.after(finishPeriodDate)) {
                return@edit
            }

            val lastChangeDailyBudgetDate = it[lastChangeDailyBudgetDateStoreKey]
                ?.let { value -> Date(value) }

            // The daily-budget fold (setDailyBudget) moves yesterday's spends from
            // `spentFromDailyBudget` into `spent`. So the counter a removal must touch is
            // decided by whether that fold has already run today — not merely by the
            // transaction's date. Otherwise removing a previous-day spend before the fold
            // runs would drain `spent` and leave a stale value in `spentFromDailyBudget`.
            val foldRanToday = lastChangeDailyBudgetDate !== null
                    && isSameDay(lastChangeDailyBudgetDate, getCurrentDateUseCase())
            val transactionIsToday = isSameDay(transactionForRemove.date, getCurrentDateUseCase())

            if (transactionIsToday || !foldRanToday) {
                val spentFromDailyBudget = it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: return@edit

                it[spentFromDailyBudgetStoreKey] =
                    (spentFromDailyBudget - transactionForRemove.value)
                        .coerceAtLeast(BigDecimal.ZERO)
                        .toString()
            } else {
                val finishPeriodDate = it[finishPeriodDateStoreKey]
                    ?.let { value -> Date(value) } ?: return@edit
                val dailyBudget = it[dailyBudgetStoreKey]?.toBigDecimal() ?: return@edit
                val spent = it[spentStoreKey]?.toBigDecimal() ?: return@edit

                val restDays = countDays(finishPeriodDate, getCurrentDateUseCase())
                    .coerceAtLeast(1)
                val spreadDeltaSpentPerRestDays = transactionForRemove.value
                    .divide(
                        restDays.toBigDecimal(),
                        2,
                        RoundingMode.HALF_EVEN,
                    )

                Log.d(
                    "SpendsRepository",
                    "Remove spent from previous day { "
                            + transactionForRemove
                            + " } ["
                            + "spent: $spent "
                            + "dailyBudget: $dailyBudget "
                            + "spreadDeltaSpentPerRestDays: $spreadDeltaSpentPerRestDays "
                            + "spentDate: ${transactionForRemove.date} "
                            + "getCurrentDateUseCase: ${getCurrentDateUseCase()} "
                            + "countDays: $restDays "
                            + "]"
                )

                it[dailyBudgetStoreKey] = (dailyBudget + spreadDeltaSpentPerRestDays).toString()
                it[spentStoreKey] = (spent - transactionForRemove.value)
                    .coerceAtLeast(BigDecimal.ZERO)
                    .toString()
            }
        }
    }
}