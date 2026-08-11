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
import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.categoryCapBucket
import com.danilkinkin.buckwheat.data.categories.categoryKey
import com.danilkinkin.buckwheat.data.categories.highestNewlyReachedCapBucket
import com.danilkinkin.buckwheat.errorForReport
import com.danilkinkin.buckwheat.interleaved.CategoryFrequency
import com.danilkinkin.buckwheat.interleaved.InterleavedCategory
import com.danilkinkin.buckwheat.interleaved.WindowSpend
import com.danilkinkin.buckwheat.interleaved.hasRolled
import com.danilkinkin.buckwheat.interleaved.windowFor
import com.danilkinkin.buckwheat.interleaved.windowSpent
import com.danilkinkin.buckwheat.notifications.CategoryCapNotifier
import com.danilkinkin.buckwheat.notifications.OverspendingNotifier
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.isSameDay
import com.danilkinkin.buckwheat.util.roundToDay
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
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
// Transient flag: true once the daily overspend notification has been posted for the
// current day's crossing, reset when spending returns at or under the daily budget.
val overspendNotifiedStoreKey = booleanPreferencesKey("overspendNotified")

// Fire the instant overspend notification only once per crossing: when spending was at or
// under the daily budget before this transaction and went over with it, and we haven't
// already notified for this crossing.
fun shouldNotifyOverspend(
    wasOver: Boolean,
    nowOver: Boolean,
    alreadyNotified: Boolean,
): Boolean = nowOver && !wasOver && !alreadyNotified

class SpendsRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val transactionDao: TransactionDao,
    private val savedTagDao: SavedTagDao,
    private val savedCategoryDao: SavedCategoryDao,
    private val budgetPeriodDao: BudgetPeriodDao,
    private val getCurrentDateUseCase: GetCurrentDateUseCase,
) {
    fun getAllTransactions(): LiveData<List<Transaction>> = transactionDao.getAll()
    fun getAllArchivedTransactions(): LiveData<List<ArchivedTransaction>> = budgetPeriodDao.getAllArchived()
    fun getAllBudgetPeriods(): LiveData<List<BudgetPeriod>> = budgetPeriodDao.getAll()
    fun getAllSpends(): LiveData<List<Transaction>> = transactionDao.getAll(TransactionType.SPENT)
    fun getTransactionsInRange(startDate: Date, endDate: Date): LiveData<List<Transaction>> =
        transactionDao.getAll(startDate.time, endDate.time)
    fun getSpendsInRange(startDate: Date, endDate: Date): LiveData<List<Transaction>> =
        transactionDao.getAll(TransactionType.SPENT, startDate.time, endDate.time)

    fun getAllTags(): LiveData<List<String>> {
        val merged = MediatorLiveData<List<String>>()
        val transactionSource = transactionDao.getAll().map { transactions ->
            deriveTags(transactions.map { it.comment })
        }
        // Archived transactions (past periods and imported historical data) must
        // contribute their comments as well, otherwise tags restored via CSV import
        // never show up in the tag picker or the Tags Management sheet.
        val archivedSource = budgetPeriodDao.getAllArchived().map { archived ->
            deriveTags(archived.map { it.comment })
        }
        val savedSource = savedTagDao.getAll().map { tags -> tags.map { it.name } }

        var lastTransactionTags: List<String> = emptyList()
        var lastArchivedTags: List<String> = emptyList()
        var lastSavedTags: List<String> = emptyList()

        merged.addSource(transactionSource) { tags ->
            lastTransactionTags = tags
            merged.value = (lastTransactionTags + lastArchivedTags + lastSavedTags).distinct()
        }
        merged.addSource(archivedSource) { tags ->
            lastArchivedTags = tags
            merged.value = (lastTransactionTags + lastArchivedTags + lastSavedTags).distinct()
        }
        merged.addSource(savedSource) { tags ->
            lastSavedTags = tags
            merged.value = (lastTransactionTags + lastArchivedTags + lastSavedTags).distinct()
        }

        return merged
    }

    // Distinct non-null category values referenced by transactions (current period only —
    // archived records don't carry a category column) merged with the user's saved custom
    // categories. Categories that exist only on transactions (e.g. a saved category that was
    // later deleted) surface here so the management sheet can offer to re-save them.
    fun getAllCategories(): LiveData<List<String>> {
        val merged = MediatorLiveData<List<String>>()
        val transactionSource = transactionDao.getAll().map { transactions ->
            transactions.mapNotNull { it.category }.distinct()
        }
        val savedSource = savedCategoryDao.getAll().map { categories -> categories.map { it.name } }

        var lastTransactionCategories: List<String> = emptyList()
        var lastSavedCategories: List<String> = emptyList()

        merged.addSource(transactionSource) { categories ->
            lastTransactionCategories = categories
            merged.value = (lastTransactionCategories + lastSavedCategories).distinct()
        }
        merged.addSource(savedSource) { categories ->
            lastSavedCategories = categories
            merged.value = (lastTransactionCategories + lastSavedCategories).distinct()
        }

        return merged
    }

    private fun deriveTags(comments: List<String>): List<String> =
        comments
            .asSequence()
            .filter { it.isNotEmpty() }
            .groupBy { it }
            .map { it.key to it.value.size }
            .sortedBy { -it.second }
            .map { it.first }
            .distinct()
            .toList()

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
        } ?: ExtendCurrency.getInstance("INR")
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

    suspend fun setBudget(
        newBudget: BigDecimal,
        newFinishDate: Date,
        newStartDate: Date? = null,
    ) {
        val oldSpent = getSpent().firstOrNull() ?: BigDecimal.ZERO
        val hasStoredTransactions = transactionDao.getAll().asFlow().first()
            .any { it.type == TransactionType.SPENT }
        val startDate = roundToDay(newStartDate ?: getCurrentDateUseCase())
        if (oldSpent > BigDecimal.ZERO || hasStoredTransactions) {
            archiveCurrentPeriod(startDate)
        }

        context.budgetDataStore.edit {
            it[budgetStoreKey] = newBudget.toString()
            it[spentStoreKey] = BigDecimal.ZERO.toString()
            it[dailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[spentFromDailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[startPeriodDateStoreKey] = startDate.time
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
                startDate,
            )
        )

        setDailyBudget(whatBudgetForDay())

        hideOverspendingWarn(false)

        // New period: reset per-category cap crossing bookkeeping so the 80%/100% alerts
        // can fire again against the fresh period's spend totals.
        clearCategoryCapNotifiedNow()
    }

    private suspend fun archiveCurrentPeriod(newPeriodStartDate: Date) {
        val transactions = transactionDao.getAll().asFlow().firstOrNull() ?: emptyList()
        if (transactions.isEmpty()) return

        val startDate = getStartPeriodDate().firstOrNull()
            ?: transactions.minOf { it.date }
        val finishDate = getFinishPeriodDate().firstOrNull()
            ?: transactions.maxOf { it.date }
        // When a new period starts before the old period's scheduled finish,
        // cap the archived period's finish date at the new start date so
        // "vs previous" comparisons remain valid (the previous period
        // effectively ended when the new one began).
        val actualFinishDate = getFinishPeriodActualDate().firstOrNull()
        val cappedFinishDate = if (finishDate.after(newPeriodStartDate)) {
            newPeriodStartDate
        } else {
            finishDate
        }

        // Only archive transactions that belong to this period. Out-of-period rows
        // (e.g. CSV imports for the next period, recurring backfill before the start)
        // must stay in the active table — they are scope-guarded and must not be
        // archived into a past period.
        val inPeriod = transactions.filter {
            !it.date.before(startDate) && !it.date.after(cappedFinishDate)
        }
        val spends = inPeriod.filter { it.type == TransactionType.SPENT }

        if (inPeriod.isEmpty()) return

        val oldBudget = getBudget().firstOrNull() ?: BigDecimal.ZERO
        val currencyCode = currentCurrencyCode()

        val totalSpent = spends.map { it.value }.fold(BigDecimal.ZERO) { acc, v -> acc + v }

        val periodId = budgetPeriodDao.insert(
            BudgetPeriod(
                budget = oldBudget,
                startDate = startDate,
                finishDate = cappedFinishDate,
                actualFinishDate = actualFinishDate?.takeIf { !it.after(cappedFinishDate) },
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

    suspend fun changeBudget(
        newBudget: BigDecimal,
        newFinishDate: Date,
        newStartDate: Date? = null,
    ) {
        context.budgetDataStore.edit {
            it[budgetStoreKey] = newBudget.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[finishPeriodDateStoreKey] = Date(roundToDay(newFinishDate).time + DAY - 1000).time
            if (newStartDate !== null) {
                it[startPeriodDateStoreKey] = roundToDay(newStartDate).time
            }
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
            // Sync the overspend flag with the new budget: raising it clears a stale
            // notification, and after a day-change fold the fresh crossing can notify again.
            val spentFromDailyBudget = it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
            it[overspendNotifiedStoreKey] = spentFromDailyBudget > newDailyBudget

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
            it[overspendNotifiedStoreKey] = false

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

    // The amount the user actually saved over the elapsed days, i.e. the leftover that
    // carries forward to today. Equal to `howMuchNotSpent() - nextDayBudget()` for
    // skippedDays >= 1, but correct also for skippedDays == 0 (the daily-budget
    // redistribution was already marked handled for today), where that subtraction
    // spuriously turns a positive leftover negative.
    suspend fun howMuchSaved(): BigDecimal {
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

        val restBudget = budget - spent

        val howMuchSaved = if (restDays == 0) {
            restBudget - spentFromDailyBudget
        } else {
            restBudget
                .minus(dailyBudget)
                .divide(
                    (restDays + skippedDays - 1).coerceAtLeast(1).toBigDecimal(),
                    2,
                    RoundingMode.HALF_EVEN,
                )
                .multiply((skippedDays - 1).coerceAtLeast(0).toBigDecimal())
                .plus(dailyBudget - spentFromDailyBudget)
        }

        Log.d(
            "SpendsRepository",
            "How much saved check ["
                    + "how much saved: $howMuchSaved "
                    + "rest budget: $restBudget "
                    + "restDays: $restDays "
                    + "skippedDays: $skippedDays "
                    + "lastChangeDailyBudgetDate: $lastChangeDailyBudgetDate "
                    + "getCurrentDateUseCase: ${getCurrentDateUseCase()} "
                    + "dailyBudget: $dailyBudget "
                    + "spentFromDailyBudget: $spentFromDailyBudget "
                    + "]"
        )

        return howMuchSaved
    }

    suspend fun addSpent(newTransaction: Transaction) {
        this.transactionDao.insert(newTransaction)

        var notifyOverspend = false
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
                    val newSpentFromDailyBudget = spentFromDailyBudget + newTransaction.value
                    it[spentFromDailyBudgetStoreKey] = newSpentFromDailyBudget.toString()

                    // Post the instant overspend notification exactly once per crossing.
                    val alreadyNotified = it[overspendNotifiedStoreKey] ?: false
                    notifyOverspend = shouldNotifyOverspend(
                        wasOver = spentFromDailyBudget > dailyBudget,
                        nowOver = newSpentFromDailyBudget > dailyBudget,
                        alreadyNotified = alreadyNotified,
                    )
                    it[overspendNotifiedStoreKey] = newSpentFromDailyBudget > dailyBudget
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
                    it[overspendNotifiedStoreKey] = false
                }
            } catch (e: Exception) {
                context.errorForReport = e.stackTraceToString()
            }
        }

        if (notifyOverspend && (context.settingsDataStore.data.first()[overspendNotifyEnabledStoreKey] ?: false)) {
            val prefs = context.budgetDataStore.data.first()
            val dailyBudget = prefs[dailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
            val spentFromDailyBudget =
                prefs[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
            val currency = prefs[currencyStoreKey]?.let { ExtendCurrency.getInstance(it) }
                ?: ExtendCurrency.none()
            OverspendingNotifier.notify(context, dailyBudget, spentFromDailyBudget, currency)
        }

        resyncInterleavedNotified()
        checkCategoryCapAlert(newTransaction)
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
                val dailyBudget = it[dailyBudgetStoreKey]?.toBigDecimal() ?: return@edit

                it[spentFromDailyBudgetStoreKey] =
                    (spentFromDailyBudget - transactionForRemove.value)
                        .coerceAtLeast(BigDecimal.ZERO)
                        .toString()

                // Resync the overspend flag: if the removal drops us back under the daily
                // budget, a later crossing must notify again (e.g. after undo or edit).
                val newSpentFromDailyBudget =
                    it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
                it[overspendNotifiedStoreKey] = newSpentFromDailyBudget > dailyBudget
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

        resyncCategoryCapNotified(transactionForRemove)
        resyncInterleavedNotified()
    }

    // Reads a transaction's category the same way the analytics categories card does
    // (offline keyword fallback), sums the category's spend in its progress source — the
    // current interleaved window for scheduled categories, else the current budget period —
    // and posts the 80%/100% cap alert once per newly reached level.
    private suspend fun checkCategoryCapAlert(newTransaction: Transaction) {
        if (newTransaction.type != TransactionType.SPENT) return
        val prefs = context.budgetDataStore.data.first()
        val start = prefs[startPeriodDateStoreKey]?.let { Date(it) } ?: return
        val finish = prefs[finishPeriodDateStoreKey]?.let { Date(it) } ?: return
        if (newTransaction.date.before(start) || newTransaction.date.after(finish)) return

        val key = categoryKey(newTransaction)
        val categoryName = categoryNameOf(key)
        val caps = parseCategoryCaps(
            context.settingsDataStore.data.first()[categoryCapsStoreKey]
        )
        val cap = caps[categoryName] ?: return
        val scheduled = parseCategorySchedules(
            context.settingsDataStore.data.first()[categorySchedulesStoreKey]
        )[categoryName]
        val today = getCurrentDateUseCase()
        val total = categoryProgressTotal(key, categoryName, cap, scheduled, today, start, finish)

        val newBucket = categoryCapBucket(total, cap)
        val notified = parseCategoryCapNotifiedWithWindow(
            context.settingsDataStore.data.first()[categoryCapNotifiedStoreKey]
        )
        val newlyReached = highestNewlyReachedCapBucket(notified[categoryName]?.first ?: 0, newBucket)
        if (newlyReached == 0) return

        val currency = prefs[currencyStoreKey]?.let { ExtendCurrency.getInstance(it) }
            ?: ExtendCurrency.none()
        CategoryCapNotifier.notify(context, key, newlyReached, total, cap, currency)
        val windowStart = scheduled?.let { windowFor(it.copy(amount = cap), today.toLocalDate())?.first }
            ?.toEpochDay() ?: Long.MIN_VALUE
        setCategoryCapNotifiedNow(notified + (categoryName to (newlyReached to windowStart)))
    }

    // Lowers a category's announced cap level when a removal drops the spend back under it
    // (in the interleaved window for scheduled categories, else the budget period), so a
    // later crossing announces again (mirrors the overspend flag resync).
    private suspend fun resyncCategoryCapNotified(removed: Transaction) {
        if (removed.type != TransactionType.SPENT) return
        val prefs = context.budgetDataStore.data.first()
        val start = prefs[startPeriodDateStoreKey]?.let { Date(it) } ?: return
        val finish = prefs[finishPeriodDateStoreKey]?.let { Date(it) } ?: return
        if (removed.date.before(start) || removed.date.after(finish)) return

        val key = categoryKey(removed)
        val categoryName = categoryNameOf(key)
        val caps = parseCategoryCaps(
            context.settingsDataStore.data.first()[categoryCapsStoreKey]
        )
        val cap = caps[categoryName] ?: return
        val scheduled = parseCategorySchedules(
            context.settingsDataStore.data.first()[categorySchedulesStoreKey]
        )[categoryName]
        val today = getCurrentDateUseCase()
        val total = categoryProgressTotal(key, categoryName, cap, scheduled, today, start, finish)
        val currentBucket = categoryCapBucket(total, cap)
        val notified = parseCategoryCapNotifiedWithWindow(
            context.settingsDataStore.data.first()[categoryCapNotifiedStoreKey]
        )
        val storedBucket = notified[categoryName]?.first ?: 0
        if (currentBucket >= storedBucket) return

        val updated = notified.toMutableMap()
        if (currentBucket == 0) {
            updated.remove(categoryName)
        } else {
            updated[categoryName] = currentBucket to (notified[categoryName]?.second ?: Long.MIN_VALUE)
        }
        setCategoryCapNotifiedNow(updated)
    }

    private fun categoryNameOf(key: CategoryKey): String = when (key) {
        is CategoryKey.BuiltIn -> key.category.name
        is CategoryKey.Custom -> key.name
    }

    // Scheduled interleaved categories merged with their cap amounts.
    private suspend fun interleavedCategoriesNow(): Map<String, InterleavedCategory> {
        val caps = parseCategoryCaps(context.settingsDataStore.data.first()[categoryCapsStoreKey])
        val schedules = parseCategorySchedules(
            context.settingsDataStore.data.first()[categorySchedulesStoreKey]
        )
        return schedules.mapValues { (name, schedule) ->
            schedule.copy(amount = caps[name] ?: BigDecimal.ZERO)
        }
    }

    // Spend for a category in its cap progress source: the current interleaved window for
    // scheduled categories (DAILY schedule = no window -> plain-cap period behavior), else
    // the main budget period's spend total.
    private suspend fun categoryProgressTotal(
        key: CategoryKey,
        categoryName: String,
        cap: BigDecimal,
        scheduled: InterleavedCategory?,
        today: Date,
        start: Date,
        finish: Date,
    ): BigDecimal {
        if (scheduled != null) {
            val window = windowFor(scheduled.copy(amount = cap), today.toLocalDate())
            if (window != null) {
                return windowSpent(
                    transactionDao.getAll().asFlow().first()
                        .filter { it.type == TransactionType.SPENT }
                        .map { WindowSpend(it.date, it.value, it.category) },
                    scheduled.copy(amount = cap),
                    today.toLocalDate(),
                )
            }
        }
        return periodCategoryTotal(start, finish, key)
    }

    private suspend fun periodCategoryTotal(start: Date, finish: Date, key: CategoryKey): BigDecimal =
        transactionDao.getAll().asFlow().first()
            .filter { it.type == TransactionType.SPENT }
            .filter { !it.date.before(start) && !it.date.after(finish) }
            .filter { categoryKey(it) == key }
            .fold(BigDecimal.ZERO) { acc, tx -> acc + tx.value }

    // Interleaved windows roll over independently of the main budget period. On every
    // mutation, reset the announced cap bucket of any scheduled category whose window has
    // advanced, so the 80%/100% alerts can fire again for the new window.
    private suspend fun resyncInterleavedNotified() {
        val schedules = parseCategorySchedules(
            context.settingsDataStore.data.first()[categorySchedulesStoreKey]
        )
        if (schedules.isEmpty()) return
        val today = getCurrentDateUseCase().toLocalDate()
        val notified = parseCategoryCapNotifiedWithWindow(
            context.settingsDataStore.data.first()[categoryCapNotifiedStoreKey]
        )
        var changed = false
        val updated = notified.toMutableMap()
        interleavedCategoriesNow().values.forEach { category ->
            if (hasRolled(category, today, updated[category.name]?.second ?: Long.MIN_VALUE)) {
                updated.remove(category.name)
                changed = true
            }
        }
        if (changed) setCategoryCapNotifiedNow(updated)
    }

    // Clears the cap bookkeeping on a new budget period, but keeps windowed (non-DAILY)
    // scheduled entries — their progress is window-scoped, not period-scoped, so a period
    // change must not reset a window that hasn't rolled yet. DAILY schedules are plain caps.
    private suspend fun clearCategoryCapNotifiedNow() {
        val schedules = parseCategorySchedules(
            context.settingsDataStore.data.first()[categorySchedulesStoreKey]
        )
        context.settingsDataStore.edit {
            if (schedules.isEmpty()) {
                it.remove(categoryCapNotifiedStoreKey)
            } else {
                val windowed = schedules.filterValues { it.frequency != CategoryFrequency.DAILY }.keys
                val current = parseCategoryCapNotifiedWithWindow(it[categoryCapNotifiedStoreKey])
                val kept = current.filterKeys { name -> name in windowed }
                val serialized = serializeCategoryCapNotifiedWithWindow(kept)
                if (serialized.isEmpty()) {
                    it.remove(categoryCapNotifiedStoreKey)
                } else {
                    it[categoryCapNotifiedStoreKey] = serialized
                }
            }
        }
    }

    private suspend fun setCategoryCapNotifiedNow(notified: Map<String, Pair<Int, Long>>) {
        val serialized = serializeCategoryCapNotifiedWithWindow(notified)
        context.settingsDataStore.edit {
            if (serialized.isEmpty()) {
                it.remove(categoryCapNotifiedStoreKey)
            } else {
                it[categoryCapNotifiedStoreKey] = serialized
            }
        }
    }
}