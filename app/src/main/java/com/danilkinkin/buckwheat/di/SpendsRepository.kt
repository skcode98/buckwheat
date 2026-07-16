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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import java.lang.Long.min
import java.math.BigDecimal
import java.math.RoundingMode
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
    fun getAllSpends(): LiveData<List<Transaction>> = transactionDao.getAll(TransactionType.SPENT)

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
        (it[budgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2)
    }

    fun getSpent() = context.budgetDataStore.data.map {
        (it[spentStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2)
    }

    fun getDailyBudget() = context.budgetDataStore.data.map {
        (it[dailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2)
    }

    fun getSpentFromDailyBudget() = context.budgetDataStore.data.map {
        (it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO).setScale(2)
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
        if (oldSpent > BigDecimal.ZERO) {
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

        transactionDao.deleteAll()
        transactionDao.insert(
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
        val oldBudget = getBudget().firstOrNull() ?: BigDecimal.ZERO
        val oldSpent = getSpent().firstOrNull() ?: BigDecimal.ZERO
        val startDate = getStartPeriodDate().firstOrNull()
            ?: return
        val finishDate = getFinishPeriodDate().firstOrNull()
            ?: return
        val actualFinishDate = getFinishPeriodActualDate().firstOrNull()
        val currency = getCurrency().firstOrNull()
        val currencyCode = when (currency?.type) {
            com.danilkinkin.buckwheat.data.ExtendCurrency.Type.CUSTOM -> currency.value ?: ""
            com.danilkinkin.buckwheat.data.ExtendCurrency.Type.NONE -> ""
            else -> currency?.value ?: ""
        }

        val transactions = transactionDao.getAll().asFlow().firstOrNull() ?: emptyList()
        val spends = transactions.filter { it.type == TransactionType.SPENT }

        if (spends.isEmpty()) return

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
            transactions.map { tx ->
                ArchivedTransaction(
                    periodId = periodId.toInt(),
                    type = tx.type,
                    value = tx.value,
                    date = tx.date,
                    comment = tx.comment,
                )
            }
        )

        Log.d("SpendsRepository", "Archived period #$periodId with ${transactions.size} transactions")
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

        val incomeTransaction = transactionDao.getAll(TransactionType.INCOME).asFlow().first().first()

        transactionDao.update(incomeTransaction.copy(value = newBudget))

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


        val setDailyBudgetTransaction = transactionDao.getAll(TransactionType.SET_DAILY_BUDGET).asFlow().first().last()
        transactionDao.update(setDailyBudgetTransaction.copy(value = newDailyBudget))
    }

    suspend fun setDailyBudget(newDailyBudget: BigDecimal) {
        context.budgetDataStore.edit {
            val spent: BigDecimal = it[spentStoreKey]?.toBigDecimal()!!
            val spentFromDailyBudget: BigDecimal =
                it[spentFromDailyBudgetStoreKey]?.toBigDecimal()!!

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
            getFinishPeriodDate().first() ?: throw Exception("Finish period date is null")


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
            getFinishPeriodDate().first() ?: throw Exception("Finish period date is null")
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
            getFinishPeriodDate().first() ?: throw Exception("Finish period date is null")
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
            try {
                if (isSameDay(newTransaction.date, getCurrentDateUseCase())) {
                    val spentFromDailyBudget = it[spentFromDailyBudgetStoreKey]?.toBigDecimal()!!
                    it[spentFromDailyBudgetStoreKey] =
                        (spentFromDailyBudget + newTransaction.value).toString()
                } else {
                    val finishPeriodDate =
                        it[finishPeriodDateStoreKey]?.let { value -> Date(value) }!!
                    val dailyBudget = it[dailyBudgetStoreKey]?.toBigDecimal()!!
                    val spent = it[spentStoreKey]?.toBigDecimal()!!

                    val spreadDeltaSpentPerRestDays = newTransaction.value
                        .divide(
                            countDays(finishPeriodDate, getCurrentDateUseCase()).toBigDecimal(),
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
                                + "countDays: ${
                            countDays(
                                finishPeriodDate,
                                getCurrentDateUseCase()
                            )
                        } "
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
        transactions.forEach { addSpent(it) }
    }

    suspend fun removeSpent(transactionForRemove: Transaction) {
        this.transactionDao.deleteById(transactionForRemove.uid)

        context.budgetDataStore.edit {
            if (isSameDay(transactionForRemove.date, getCurrentDateUseCase())) {
                val spentFromDailyBudget = it[spentFromDailyBudgetStoreKey]?.toBigDecimal()!!

                it[spentFromDailyBudgetStoreKey] =
                    (spentFromDailyBudget - transactionForRemove.value).toString()
            } else {
                val finishPeriodDate = it[finishPeriodDateStoreKey]?.let { value -> Date(value) }!!
                val dailyBudget = it[dailyBudgetStoreKey]?.toBigDecimal()!!
                val spent = it[spentStoreKey]?.toBigDecimal()!!

                val restDays = countDays(finishPeriodDate, getCurrentDateUseCase())
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
                it[spentStoreKey] = (spent - transactionForRemove.value).toString()
            }
        }
    }
}