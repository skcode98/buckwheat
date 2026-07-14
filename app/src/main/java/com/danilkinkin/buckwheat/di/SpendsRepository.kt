package com.danilkinkin.buckwheat.di

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.LiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.RestedBudgetDistributionMethod
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.dao.TagWithCategory
import com.danilkinkin.buckwheat.data.entities.Category
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.TagCategory
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.util.DAY
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.dao.CategoryDao
import com.danilkinkin.buckwheat.data.dao.PeriodDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.errorForReport
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.isSameDay
import com.danilkinkin.buckwheat.util.roundToDay
import com.danilkinkin.buckwheat.util.toLocalDate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.lang.Long.min
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
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
val knownTagsStoreKey = stringPreferencesKey("knownTags")
val needsBudgetStoreKey = stringPreferencesKey("needsBudget")
val wantsBudgetStoreKey = stringPreferencesKey("wantsBudget")
val needsDailyBudgetStoreKey = stringPreferencesKey("needsDailyBudget")
val wantsDailyBudgetStoreKey = stringPreferencesKey("wantsDailyBudget")
val spentFromNeedsDailyBudgetStoreKey = stringPreferencesKey("spentFromNeedsDailyBudget")
val spentFromWantsDailyBudgetStoreKey = stringPreferencesKey("spentFromWantsDailyBudget")

data class ImportResult(val inserted: Int, val skipped: Int)

class SpendsRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val transactionDao: TransactionDao,
    private val periodDao: PeriodDao,
    private val recurringDao: RecurringDao,
    private val categoryDao: CategoryDao,
    private val getCurrentDateUseCase: GetCurrentDateUseCase,
    private val settingsRepository: SettingsRepository,
) {
    fun getAllTransactions(): LiveData<List<Transaction>> = transactionDao.getAll()

    suspend fun getAllTransactionsSuspend(): List<Transaction> = transactionDao.getAllSuspend()
    fun getAllSpends(): LiveData<List<Transaction>> = transactionDao.getAll(TransactionType.SPENT)
    suspend fun getAllSpendsSuspend(): List<Transaction> = transactionDao.getAllSuspend().filter { it.type == TransactionType.SPENT }

    fun getAllPeriods(): LiveData<List<Period>> = periodDao.getAll()

    fun getAllSpendsByDateRange(startDateMs: Long, endDateMs: Long): LiveData<List<Transaction>> =
        transactionDao.getAll(TransactionType.SPENT, startDateMs, endDateMs)

    suspend fun deletePeriod(id: Long) {
        periodDao.getById(id)?.let { periodDao.delete(it) }
    }

    suspend fun updatePeriodNote(id: Long, note: String) {
        periodDao.getById(id)?.let {
            periodDao.update(it.copy(note = note))
        }
    }

    suspend fun saveCurrentPeriod() {
        val budget = getBudget().first()
        if (budget <= BigDecimal.ZERO) return

        val startDate = getStartPeriodDate().first()
        val finishDate = getFinishPeriodDate().first() ?: return
        val actualFinishDate = getFinishPeriodActualDate().first()

        periodDao.insert(
            Period(
                startDate = startDate,
                finishDate = finishDate,
                actualFinishDate = actualFinishDate,
                budget = budget,
            )
        )
    }

    suspend fun addKnownTag(tag: String) {
        if (tag.isBlank()) return
        context.budgetDataStore.edit {
            val existingKnown = it[knownTagsStoreKey]
                ?.split("|")
                ?.filter { s -> s.isNotEmpty() }
                ?: emptyList()
            val merged = (existingKnown + tag).distinct()
            it[knownTagsStoreKey] = merged.joinToString("|")
        }
    }

    suspend fun deleteTag(tag: String) {
        context.budgetDataStore.edit {
            val existingKnown = it[knownTagsStoreKey]
                ?.split("|")
                ?.filter { s -> s.isNotEmpty() && s != tag }
                ?: emptyList()
            it[knownTagsStoreKey] = existingKnown.joinToString("|")
        }
        transactionDao.deleteByComment(tag)
    }

    suspend fun renameTag(oldTag: String, newTag: String) {
        if (newTag.isBlank()) return
        transactionDao.renameByComment(oldTag, newTag)
        context.budgetDataStore.edit {
            val existingKnown = it[knownTagsStoreKey]
                ?.split("|")
                ?.filter { s -> s.isNotEmpty() }
                ?: emptyList()
            val updated = existingKnown.map { if (it == oldTag) newTag else it }.distinct()
            it[knownTagsStoreKey] = updated.joinToString("|")
        }
    }

    suspend fun importTransactions(transactions: List<Transaction>): ImportResult {
        val existing = transactionDao.getAllSuspend()
        val (duplicates, newOnes) = transactions.partition { t ->
            existing.any { e ->
                e.comment == t.comment
                        && e.value.compareTo(t.value) == 0
                        && e.date.time / 1000 == t.date.time / 1000
            }
        }

        if (newOnes.isNotEmpty()) {
            transactionDao.insert(*newOnes.toTypedArray())

            newOnes.forEach { t ->
                if (t.comment.isNotEmpty()) {
                    val persistTagsEnabled = context.settingsDataStore.data.first()[persistTagsStoreKey] ?: false
                    if (persistTagsEnabled) {
                        context.budgetDataStore.edit {
                            val existingKnown = it[knownTagsStoreKey]
                                ?.split("|")
                                ?.filter { s -> s.isNotEmpty() }
                                ?: emptyList()
                            val merged = (existingKnown + t.comment).distinct()
                            it[knownTagsStoreKey] = merged.joinToString("|")
                        }
                    }
                }
            }
        }

        return ImportResult(inserted = newOnes.size, skipped = duplicates.size)
    }

    fun getAllTags(): LiveData<List<String>> {
        val dbTagsFlow = transactionDao.getAll().asFlow().map { transactions ->
            transactions
                .asSequence()
                .filter { it.comment.isNotEmpty() }
                .groupBy { it.comment }
                .map { it.key to it.value.size }
                .sortedBy { -it.second }
                .map { it.first }
                .distinct()
                .toList()
        }

        val knownTagsFlow = context.budgetDataStore.data.map { prefs ->
            prefs[knownTagsStoreKey]?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        }

        val persistTagsEnabledFlow = context.settingsDataStore.data.map { prefs ->
            prefs[persistTagsStoreKey] ?: false
        }

        return combine(dbTagsFlow, knownTagsFlow, persistTagsEnabledFlow) { dbTags, knownTags, persistEnabled ->
            if (persistEnabled) {
                (knownTags + dbTags).distinct()
            } else {
                dbTags
            }
        }.asLiveData()
    }

    fun getAllTagsWithCount(): LiveData<List<Pair<String, Int>>> {
        val dbTagsFlow = transactionDao.getAll().asFlow().map { transactions ->
            transactions
                .asSequence()
                .filter { it.comment.isNotEmpty() }
                .groupBy { it.comment }
                .map { it.key to it.value.size }
                .sortedBy { -it.second }
                .toList()
        }

        val knownTagsFlow = context.budgetDataStore.data.map { prefs ->
            prefs[knownTagsStoreKey]?.split("|")?.filter { it.isNotEmpty() } ?: emptyList()
        }

        val persistTagsEnabledFlow = context.settingsDataStore.data.map { prefs ->
            prefs[persistTagsStoreKey] ?: false
        }

        return combine(dbTagsFlow, knownTagsFlow, persistTagsEnabledFlow) { dbTags, knownTags, persistEnabled ->
            if (persistEnabled) {
                val knownPairs = knownTags.map { it to (dbTags.find { p -> p.first == it }?.second ?: 0) }
                (knownPairs + dbTags)
                    .groupBy { it.first }
                    .map { (tag, pairs) -> tag to pairs.maxOf { it.second } }
                    .sortedBy { -it.second }
            } else {
                dbTags
            }
        }.asLiveData()
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
        val persistTagsEnabled = context.settingsDataStore.data.first()[persistTagsStoreKey] ?: false

        saveCurrentPeriod()

        context.budgetDataStore.edit {
            if (!persistTagsEnabled) {
                it.remove(knownTagsStoreKey)
            }

            it[budgetStoreKey] = newBudget.toString()
            it[spentStoreKey] = BigDecimal.ZERO.toString()
            it[dailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[spentFromDailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[startPeriodDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[finishPeriodDateStoreKey] = Date(roundToDay(newFinishDate).time + DAY - 1000).time
            it.remove(finishPeriodActualDateStoreKey)
            it[hideOverspendingWarnStoreKey] = false

            Log.d(
                "SpendsRepository",
                "Set budget ["
                        +                 "budget: ${it[budgetStoreKey]} "
                        + "start date: ${it[startPeriodDateStoreKey]?.let { Date(it) }} "
                        + "finish date: ${it[finishPeriodDateStoreKey]?.let { Date(it) }}"
                        + "]"
            )
        }

        transactionDao.insert(
            Transaction(
                TransactionType.INCOME,
                newBudget,
                getCurrentDateUseCase(),
            )
        )

        setDailyBudget(whatBudgetForDay())
    }

    suspend fun setBudgets(needsBudget: BigDecimal, wantsBudget: BigDecimal, newFinishDate: Date) {
        val total = needsBudget + wantsBudget
        val persistTagsEnabled = context.settingsDataStore.data.first()[persistTagsStoreKey] ?: false

        saveCurrentPeriod()

        context.budgetDataStore.edit {
            if (!persistTagsEnabled) {
                it.remove(knownTagsStoreKey)
            }

            it[budgetStoreKey] = total.toString()
            it[needsBudgetStoreKey] = needsBudget.toString()
            it[wantsBudgetStoreKey] = wantsBudget.toString()
            it[spentStoreKey] = BigDecimal.ZERO.toString()
            it[dailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[needsDailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[wantsDailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[spentFromDailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[spentFromNeedsDailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[spentFromWantsDailyBudgetStoreKey] = BigDecimal.ZERO.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[startPeriodDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[finishPeriodDateStoreKey] = Date(roundToDay(newFinishDate).time + DAY - 1000).time
            it.remove(finishPeriodActualDateStoreKey)
            it[hideOverspendingWarnStoreKey] = false
        }

        transactionDao.insert(
            Transaction(
                TransactionType.INCOME,
                total,
                getCurrentDateUseCase(),
            )
        )

        setDailyBudget(whatBudgetForDay())
    }

    suspend fun changeBudgets(needsBudget: BigDecimal, wantsBudget: BigDecimal, newFinishDate: Date) {
        val total = needsBudget + wantsBudget
        context.budgetDataStore.edit {
            it[budgetStoreKey] = total.toString()
            it[needsBudgetStoreKey] = needsBudget.toString()
            it[wantsBudgetStoreKey] = wantsBudget.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[finishPeriodDateStoreKey] = Date(roundToDay(newFinishDate).time + DAY - 1000).time
            it.remove(finishPeriodActualDateStoreKey)
        }

        val incomeTransaction = transactionDao.getAll(TransactionType.INCOME).asFlow().first().firstOrNull()
        if (incomeTransaction != null) {
            transactionDao.update(incomeTransaction.copy(value = total))
        }
        updateDailyBudget(whatBudgetForDay())
    }

    suspend fun setStartPeriodDate(newStartDate: Date) {
        val oldStart = getStartPeriodDate().first()
        context.budgetDataStore.edit {
            it[startPeriodDateStoreKey] = roundToDay(newStartDate).time
        }
        updateDailyBudget(whatBudgetForDay())
    }

    fun getNeedsBudget(): LiveData<BigDecimal> = context.budgetDataStore.data.map {
        it[needsBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
    }.asLiveData()

    fun getWantsBudget(): LiveData<BigDecimal> = context.budgetDataStore.data.map {
        it[wantsBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
    }.asLiveData()

    suspend fun getNeedsBudgetSuspend(): BigDecimal =
        context.budgetDataStore.data.first()[needsBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO

    suspend fun getWantsBudgetSuspend(): BigDecimal =
        context.budgetDataStore.data.first()[wantsBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO

    suspend fun changeBudget(newBudget: BigDecimal, newFinishDate: Date) {
        context.budgetDataStore.edit {
            it[budgetStoreKey] = newBudget.toString()
            it[lastChangeDailyBudgetDateStoreKey] = roundToDay(getCurrentDateUseCase()).time
            it[finishPeriodDateStoreKey] = Date(roundToDay(newFinishDate).time + DAY - 1000).time
            it.remove(finishPeriodActualDateStoreKey)
        }

        val incomeTransaction = transactionDao.getAll(TransactionType.INCOME).asFlow().first().firstOrNull()
        if (incomeTransaction != null) {
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
                        + "start date: ${it[startPeriodDateStoreKey]?.let { Date(it) }} "
                        + "actual finish date: ${it[finishPeriodActualDateStoreKey]?.let { Date(it) }}"
                        + "finish date: ${it[finishPeriodDateStoreKey]?.let { Date(it) }}"
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


        val setDailyBudgetTransaction = transactionDao.getAll(TransactionType.SET_DAILY_BUDGET).asFlow().first().lastOrNull()
        if (setDailyBudgetTransaction != null) {
            transactionDao.update(setDailyBudgetTransaction.copy(value = newDailyBudget))
        }
    }

    suspend fun setDailyBudget(newDailyBudget: BigDecimal) {
        context.budgetDataStore.edit {
            val spent: BigDecimal =
                it[spentStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
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

    // Category management

    fun getAllCategories(): LiveData<List<Category>> = categoryDao.getAll()

    fun getAllMappingsWithCategory(): LiveData<List<TagWithCategory>> = categoryDao.getMappingsWithCategory()

    suspend fun addCategory(name: String, color: Long, monthlyLimit: BigDecimal = BigDecimal.ZERO): Long =
        categoryDao.insert(Category(name = name, color = color, monthlyLimit = monthlyLimit))

    suspend fun renameCategory(id: Long, newName: String) {
        categoryDao.getById(id)?.let { categoryDao.update(it.copy(name = newName)) }
    }

    suspend fun updateCategory(id: Long, name: String, color: Long, monthlyLimit: BigDecimal) {
        val existing = categoryDao.getById(id)
        if (existing != null) {
            categoryDao.update(existing.copy(name = name, color = color, monthlyLimit = monthlyLimit))
        }
    }

    suspend fun deleteCategory(id: Long) {
        categoryDao.deleteMappingsByCategory(id)
        categoryDao.deleteById(id)
    }

    suspend fun getAllCategoriesSuspend(): List<Category> = categoryDao.getAllSuspend()

    suspend fun getTagCategoryMapping(tagName: String): TagCategory? = categoryDao.getMappingByTag(tagName)

    suspend fun setTagCategory(tagName: String, categoryId: Long?) {
        categoryDao.deleteMappingByTag(tagName)
        if (categoryId != null) {
            categoryDao.insertMapping(TagCategory(tagName = tagName, categoryId = categoryId))
        }
    }

    suspend fun getAllMappingsWithCategorySuspend(): List<TagWithCategory> = categoryDao.getMappingsWithCategorySuspend()

    // Budget limits per category

    suspend fun setCategoryLimit(categoryId: Long, limit: BigDecimal) {
        categoryDao.getById(categoryId)?.let { cat ->
            categoryDao.update(cat.copy(monthlyLimit = limit))
        }
    }

    suspend fun getCategoryLimit(categoryId: Long): BigDecimal =
        categoryDao.getById(categoryId)?.monthlyLimit ?: BigDecimal.ZERO

    suspend fun checkAndApplyRecurringTemplates(): List<Transaction> {
        val today = getCurrentDateUseCase()
        val calendar = Calendar.getInstance().apply { time = today }
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val dueTemplates = recurringDao.getDueOnDay(dayOfMonth)

        if (dueTemplates.isEmpty()) return emptyList()

        val existing = transactionDao.getAllSuspend()
        val applied = mutableListOf<Transaction>()

        dueTemplates.forEach { template ->
            val alreadyExists = existing.any { e ->
                e.type == TransactionType.SPENT
                        && e.comment == template.comment
                        && e.value.compareTo(template.amount) == 0
                        && isSameDay(e.date, today)
            }
            if (!alreadyExists) {
                val tx = Transaction(
                    type = TransactionType.SPENT,
                    value = template.amount,
                    date = today,
                    comment = template.comment,
                )
                transactionDao.insert(tx)
                applied.add(tx)
            }
        }

        return applied
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

        if (newTransaction.comment.isNotEmpty()) {
            val persistTagsEnabled = context.settingsDataStore.data.first()[persistTagsStoreKey] ?: false
            if (persistTagsEnabled) {
                context.budgetDataStore.edit {
                    val existingKnown = it[knownTagsStoreKey]
                        ?.split("|")
                        ?.filter { s -> s.isNotEmpty() }
                        ?: emptyList()
                    val merged = (existingKnown + newTransaction.comment).distinct()
                    it[knownTagsStoreKey] = merged.joinToString("|")
                }
            }
        }

        context.budgetDataStore.edit {
            if (isSameDay(newTransaction.date, getCurrentDateUseCase())) {
                val spentFromDailyBudget =
                    it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
                it[spentFromDailyBudgetStoreKey] =
                    (spentFromDailyBudget + newTransaction.value).toString()
            } else {
                val finishPeriodDate =
                    it[finishPeriodDateStoreKey]?.let { value -> Date(value) } ?: getCurrentDateUseCase()
                val dailyBudget =
                    it[dailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
                val spent =
                    it[spentStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO

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
        }
    }

    suspend fun computeStreak(txs: List<Transaction>): Int {
        val dailyBudget = context.budgetDataStore.data.first()[dailyBudgetStoreKey]?.toBigDecimal()
            ?: return 0
        if (dailyBudget <= BigDecimal.ZERO) return 0

        val sorted = txs.filter { it.type == TransactionType.SPENT }
            .sortedByDescending { it.date.time }
        if (sorted.isEmpty()) return 0

        val calendar = Calendar.getInstance()
        val today = roundToDay(calendar.time)

        var streak = 0
        var currentDay = today
        var dayIdx = 0

        while (true) {
            val dayTransactions = mutableListOf<Transaction>()
            while (dayIdx < sorted.size &&
                isSameDay(sorted[dayIdx].date, currentDay)
            ) {
                dayTransactions.add(sorted[dayIdx])
                dayIdx++
            }

            val dayTotal = dayTransactions.sumOf { it.value.toDouble() }
                .let { BigDecimal.valueOf(it) }

            if (dayTotal <= dailyBudget) {
                streak++
                calendar.time = currentDay
                calendar.add(Calendar.DAY_OF_MONTH, -1)
                currentDay = roundToDay(calendar.time)
            } else {
                break
            }

            if (dayIdx >= sorted.size) break
        }

        return streak
    }

    fun exportBackup(destPath: String): Boolean {
        return try {
            val dbFile = context.getDatabasePath("buckwheat-db")
            val dbDir = dbFile.parentFile
            val destDir = File(destPath).parentFile
            if (destDir != null && !destDir.exists()) destDir.mkdirs()

            copyFile(dbFile, File(destPath))

            val walFile = File(dbFile.absolutePath + "-wal")
            if (walFile.exists()) {
                copyFile(walFile, File(destPath + "-wal"))
            }
            val shmFile = File(dbFile.absolutePath + "-shm")
            if (shmFile.exists()) {
                copyFile(shmFile, File(destPath + "-shm"))
            }
            true
        } catch (e: Exception) {
            Log.e("SpendsRepository", "Export backup failed", e)
            false
        }
    }

    fun importBackup(sourcePath: String): Boolean {
        return try {
            val dbFile = context.getDatabasePath("buckwheat-db")
            val srcFile = File(sourcePath)

            copyFile(srcFile, dbFile)

            val srcWal = File(sourcePath + "-wal")
            if (srcWal.exists()) {
                copyFile(srcWal, File(dbFile.absolutePath + "-wal"))
            }
            val srcShm = File(sourcePath + "-shm")
            if (srcShm.exists()) {
                copyFile(srcShm, File(dbFile.absolutePath + "-shm"))
            }
            true
        } catch (e: Exception) {
            Log.e("SpendsRepository", "Import backup failed", e)
            false
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
            }
        }
    }

    suspend fun deleteTransactions(transactions: List<Transaction>) {
        for (tx in transactions) {
            removeSpent(tx)
        }
    }

    suspend fun removeSpent(transactionForRemove: Transaction) {
        this.transactionDao.deleteById(transactionForRemove.uid)

        context.budgetDataStore.edit {
            if (isSameDay(transactionForRemove.date, getCurrentDateUseCase())) {
                val spentFromDailyBudget =
                    it[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO

                it[spentFromDailyBudgetStoreKey] =
                    (spentFromDailyBudget - transactionForRemove.value).toString()
            } else {
                val finishPeriodDate = it[finishPeriodDateStoreKey]?.let { value -> Date(value) }
                    ?: getCurrentDateUseCase()
                val dailyBudget =
                    it[dailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
                val spent =
                    it[spentStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO

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