package com.danilkinkin.buckwheat.di

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
import com.danilkinkin.buckwheat.data.categories.CategoryAssignmentScheduler
import com.danilkinkin.buckwheat.data.categories.offlineCategoryOrNull
import com.danilkinkin.buckwheat.errorForReport
import com.danilkinkin.buckwheat.notifications.OverspendingNotifier
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.sync.SyncDirtyMarker
import com.danilkinkin.buckwheat.sync.SyncTables
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.isSameDay
import com.danilkinkin.buckwheat.util.roundToDay
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDateTime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
val lastAutoExportedPeriodStartStoreKey = longPreferencesKey("lastAutoExportedPeriodStart")
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

/**
 * One member's share of the spending in a window, plus a null-keyed bucket for spend that
 * predates enrolment.
 *
 * `total` is folded in Kotlin as BigDecimal because `transactions.value` is a TEXT column: a SQL
 * `SUM(CAST(value AS REAL))` returns a Double and quietly drops cent precision on real money.
 */
data class MemberSpend(
    val memberId: String?,
    val displayName: String?,
    val total: BigDecimal,
)

// The window total for a rollup, so a UI can render "320 of 1000" from the list it already has.
// Every row is present (including the null-memberId bucket), so folding the list gives exactly
// what one SQL SUM would have given.
val List<MemberSpend>.grandTotal: BigDecimal
    get() = fold(BigDecimal.ZERO) { acc, spend -> acc + spend.total }

class SpendsRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val transactionDao: TransactionDao,
    private val savedTagDao: SavedTagDao,
    private val savedCategoryDao: SavedCategoryDao,
    private val budgetPeriodDao: BudgetPeriodDao,
    private val getCurrentDateUseCase: GetCurrentDateUseCase,
    private val categoryAssignmentScheduler: CategoryAssignmentScheduler,
    private val categoryCapTracker: CategoryCapTracker,
    private val budgetCalculator: BudgetCalculator,
    private val syncDirtyMarker: SyncDirtyMarker,
) {
    // Thin delegations kept so the call sites below stay unchanged: SyncDirtyMarker advances
    // version/updated_at AND queues the pending_mutations row in one place.
    private suspend fun markUpsert(table: String, recordId: String) =
        syncDirtyMarker.markUpsert(table, recordId)

    private suspend fun markUpserts(table: String, recordIds: List<String>) =
        syncDirtyMarker.markUpserts(table, recordIds)

    private suspend fun markDeleted(table: String, recordId: String, familyId: String?, syncSeq: Long) =
        syncDirtyMarker.markDelete(table, recordId, familyId, syncSeq)

    fun getAllTransactions(): Flow<List<Transaction>> = transactionDao.getAll()
    fun getAllArchivedTransactions(): Flow<List<ArchivedTransaction>> = budgetPeriodDao.getAllArchived()
    fun getAllBudgetPeriods(): Flow<List<BudgetPeriod>> = budgetPeriodDao.getAll()
    fun getAllSpends(): Flow<List<Transaction>> = transactionDao.getAll(TransactionType.SPENT)
    fun getTransactionsInRange(startDate: Date, endDate: Date): Flow<List<Transaction>> =
        transactionDao.getAll(startDate.time, endDate.time)
    fun getSpendsInRange(startDate: Date, endDate: Date): Flow<List<Transaction>> =
        transactionDao.getAll(TransactionType.SPENT, startDate.time, endDate.time)

    fun getAllTags(): Flow<List<String>> = combine(
        transactionDao.getAll().map { deriveTags(it.map { t -> t.comment }) },
        budgetPeriodDao.getAllArchived().map { deriveTags(it.map { a -> a.comment }) },
        savedTagDao.getAll().map { tags -> tags.map { it.name } },
    ) { transactionTags, archivedTags, savedTags ->
        (transactionTags + archivedTags + savedTags).distinct()
    }

    // Distinct non-null category values referenced by transactions (current period only —
    // archived records don't carry a category column) merged with the user's saved custom
    // categories. Categories that exist only on transactions (e.g. a saved category that was
    // later deleted) surface here so the management sheet can offer to re-save them.
    fun getAllCategories(): Flow<List<String>> = combine(
        transactionDao.getAll().map { it.mapNotNull { t -> t.category }.distinct() },
        savedCategoryDao.getAll().map { categories -> categories.map { it.name } },
    ) { transactionCategories, savedCategories ->
        (transactionCategories + savedCategories).distinct()
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

    /**
     * Groups already-fetched SPENT rows by `member_id` and sums their values in Kotlin.
     *
     * Deliberately not a SQL GROUP BY: `value` is TEXT (RoomConverters), so summing it in SQLite
     * means CASTing to REAL, which returns a Double and loses cent precision. Folding BigDecimal
     * keeps the exact figure and matches the `fold(BigDecimal.ZERO)` already used in
     * `archiveCurrentPeriod` and `archiveImported`. This project has no SQL JOIN anywhere either —
     * `member_id` to display name is a Kotlin join, so the grouping is too.
     *
     * No `deleted_at` filter: the app hard-deletes rows (`deleteById`, and sync calls `remove` for
     * any record carrying `deletedAt`), the tombstones live only in the pending-mutations queue,
     * and no read query in the project filters on it. Adding one here would hide nothing and
     * disagree with every other query in the codebase.
     *
     * Rows with a null `member_id` predate enrolment. They are kept as their own null-keyed bucket
     * instead of being dropped, so the rollup still adds up to the window total.
     */
    private fun rollupSpentByMember(
        transactions: List<Transaction>,
        memberNames: Map<String, String>,
    ): List<MemberSpend> {
        val totals = LinkedHashMap<String?, BigDecimal>()
        transactions.forEach { transaction ->
            val running = totals[transaction.memberId] ?: BigDecimal.ZERO
            totals[transaction.memberId] = running + transaction.value
        }
        return totals
            .map { (memberId, total) ->
                MemberSpend(
                    memberId = memberId,
                    // The roster is a cache owned elsewhere, so a name is only as good as the
                    // caller's map; an unknown id keeps a null displayName rather than a placeholder.
                    displayName = memberId?.let { memberNames[it] },
                    total = total,
                )
            }
            // Biggest spender first, with the unattributed bucket always last.
            .sortedWith(
                compareBy<MemberSpend> { it.memberId == null }
                    .thenByDescending { it.total }
            )
    }

    /**
     * Per-member spend for a date window as a one-shot snapshot. [memberNames] is taken as a
     * parameter rather than looked up here, so this stays a pure data read over the rows it owns.
     * An empty window returns an empty list, never null, and never throws.
     */
    suspend fun spentByMember(
        startDate: Long,
        endDate: Long,
        memberNames: Map<String, String> = emptyMap(),
    ): List<MemberSpend> = rollupSpentByMember(
        transactionDao.getAllNow(TransactionType.SPENT, startDate, endDate),
        memberNames,
    )

    /**
     * Household spend for a date window, i.e. the rows whose bucket says the money was not anyone's
     * in particular.
     *
     * Folded in Kotlin for the same reason as [rollupSpentByMember]: `value` is a TEXT column, so
     * `SUM(CAST(value AS REAL))` would quietly drop cent precision on real money.
     */
    suspend fun householdSpent(startDate: Long, endDate: Long): BigDecimal =
        transactionDao.getHouseholdSpendsNow(startDate, endDate)
            .fold(BigDecimal.ZERO) { acc, it -> acc + it.value }

    /**
     * The same figure as a live flow, following the active period's own dates.
     *
     * Absorbs the period bounds the same way [spentByMemberCurrentPeriodFlow] does, including the
     * `flatMapLatest` on the Room flow, because a bucket that is read once and cached would keep
     * reporting the household total the head entered ten minutes ago.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun householdSpentCurrentPeriodFlow(): Flow<BigDecimal> = combine(
        getStartPeriodDate(),
        getFinishPeriodDate(),
    ) { start, finish -> start to finish }
        .flatMapLatest { (start, finish) ->
            if (finish == null) {
                flowOf(BigDecimal.ZERO)
            } else {
                transactionDao.getHouseholdSpends(start.time, finish.time)
                    .map { rows -> rows.fold(BigDecimal.ZERO) { acc, it -> acc + it.value } }
            }
        }

    /**
     * The same rollup as a Flow, derived from the existing `getAll(type, startDate, endDate)` Room
     * query, so a UI observes it without re-querying on every frame. No new DAO Flow query is
     * needed. [memberNames] is a Flow so a roster that loads after the first emission renames the
     * rows in place instead of the caller having to collect it again.
     */
    fun spentByMemberFlow(
        startDate: Long,
        endDate: Long,
        memberNames: Flow<Map<String, String>> = flowOf(emptyMap()),
    ): Flow<List<MemberSpend>> = combine(
        transactionDao.getAll(TransactionType.SPENT, startDate, endDate),
        memberNames,
    ) { transactions, names ->
        rollupSpentByMember(transactions, names)
    }

    /**
     * Rollup for the active budget period, reusing the `startPeriodDate`/`finishPeriodDate` bounds
     * this class already keeps in DataStore — no new state is introduced. A missing finish date
     * means no period is configured, which yields an empty list rather than a misleading one.
     */
    suspend fun spentByMemberCurrentPeriod(
        memberNames: Map<String, String> = emptyMap(),
    ): List<MemberSpend> {
        val start = getStartPeriodDate().firstOrNull() ?: return emptyList()
        val finish = getFinishPeriodDate().firstOrNull() ?: return emptyList()
        return spentByMember(start.time, finish.time, memberNames)
    }

    /**
     * Current-period rollup as a Flow. Derives the window from the two existing period-date flows and
     * then defers to [spentByMemberFlow], so the transactions Room flow stays a real source: a spend
     * added while the sheet is open re-emits, which a bare `combine` of the dates and the roster would
     * silently miss because neither of those changes when a row is inserted.
     *
     * Only the finish date can be absent. [getStartPeriodDate] falls back to today, so a null there is
     * not a state this class can be in, and a missing finish date is what "no period is configured"
     * actually looks like.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun spentByMemberCurrentPeriodFlow(
        memberNames: Flow<Map<String, String>> = flowOf(emptyMap()),
    ): Flow<List<MemberSpend>> = combine(
        getStartPeriodDate(),
        getFinishPeriodDate(),
        memberNames,
    ) { start, finish, names -> Triple(start, finish, names) }
        .flatMapLatest { (start, finish, names) ->
            if (finish == null) {
                flowOf(emptyList())
            } else {
                spentByMemberFlow(start.time, finish.time, flowOf(names))
            }
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
        } ?: ExtendCurrency.getInstance("INR")
    }

    fun getRestedBudgetDistributionMethod() = context.budgetDataStore.data.map { it ->
        it[restedBudgetDistributionMethodStoreKey]?.let { value ->
            runCatching {
                RestedBudgetDistributionMethod.valueOf(value)
            }.getOrDefault(RestedBudgetDistributionMethod.ASK)
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
        val hasStoredTransactions = transactionDao.getAll().first()
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
                        + "start date: ${it[startPeriodDateStoreKey]?.let { d -> Date(d) } ?: "null"} "
                        + "finish date: ${it[finishPeriodDateStoreKey]?.let { d -> Date(d) } ?: "null"}"
                        + "]"
            )
        }

        // Wipe only the old INCOME marker rows — out-of-period rows (recurring
        // backfill, CSV imports for a later period) survive archiveCurrentPeriod
        // and must not be deleted here or the data is lost.
        transactionDao.getAllNow()
            .filter { it.type == TransactionType.INCOME }
            .forEach {
                markDeleted(SyncTables.TRANSACTIONS, it.id, it.familyId, it.syncSeq)
                transactionDao.deleteById(it.id)
            }
        val incomeMarker = Transaction(
            type = TransactionType.INCOME,
            value = newBudget,
            date = startDate,
        )
        transactionDao.insert(incomeMarker)
        markUpsert(SyncTables.TRANSACTIONS, incomeMarker.id)

        setDailyBudget(whatBudgetForDay())

        hideOverspendingWarn(false)

        // New period: reset per-category cap crossing bookkeeping so the 80%/100% alerts
        // can fire again against the fresh period's spend totals.
        categoryCapTracker.clearCategoryCapNotifiedNow()
    }

    private suspend fun archiveCurrentPeriod(newPeriodStartDate: Date) {
        val transactions = transactionDao.getAll().firstOrNull() ?: emptyList()
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

        val period = BudgetPeriod(
            budget = oldBudget,
            startDate = startDate,
            finishDate = cappedFinishDate,
            actualFinishDate = actualFinishDate?.takeIf { !it.after(cappedFinishDate) },
            currencyCode = currencyCode,
            totalSpent = totalSpent,
        )
        budgetPeriodDao.insert(period)
        markUpsert(SyncTables.BUDGET_PERIODS, period.id)
        val periodId = period.id

        val archived = inPeriod.map { tx ->
            ArchivedTransaction(
                periodId = periodId,
                type = tx.type,
                value = tx.value,
                date = tx.date,
                comment = tx.comment,
            )
        }
        budgetPeriodDao.insertArchivedTransactions(archived)
        markUpserts(SyncTables.ARCHIVED_TRANSACTIONS, archived.map { it.id })

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
            if (newStartDate != null) {
                it[startPeriodDateStoreKey] = roundToDay(newStartDate).time
            }
            it.remove(finishPeriodActualDateStoreKey)

            Log.d(
                "SpendsRepository",
                "Change budget ["
                        + "budget: ${it[budgetStoreKey]} "
                        + "start date: ${it[startPeriodDateStoreKey]?.let { d -> Date(d) } ?: "null"} "
                        + "finish date: ${it[finishPeriodDateStoreKey]?.let { d -> Date(d) } ?: "null"}"
                        + "]"
            )
        }

        val incomeTransactions = transactionDao.getAll(TransactionType.INCOME).first()
        incomeTransactions.forEach { incomeTransaction ->
            transactionDao.update(incomeTransaction.copy(value = newBudget))
            markUpsert(SyncTables.TRANSACTIONS, incomeTransaction.id)
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
                        + "start date: ${it[startPeriodDateStoreKey]?.let { d -> Date(d) } ?: "null"} "
                        + "actual finish date: ${it[finishPeriodActualDateStoreKey]?.let { d -> Date(d) } ?: "null"}"
                        + "finish date: ${it[finishPeriodDateStoreKey]?.let { d -> Date(d) } ?: "null"}"
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


        transactionDao.getAll(TransactionType.SET_DAILY_BUDGET).first().lastOrNull()
            ?.let { setDailyBudgetTransaction ->
                transactionDao.update(setDailyBudgetTransaction.copy(value = newDailyBudget))
                markUpsert(SyncTables.TRANSACTIONS, setDailyBudgetTransaction.id)
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

        val dailyBudgetMarker = Transaction(
            type = TransactionType.SET_DAILY_BUDGET,
            value = newDailyBudget,
            date = getCurrentDateUseCase(),
        )
        transactionDao.insert(dailyBudgetMarker)
        markUpsert(SyncTables.TRANSACTIONS, dailyBudgetMarker.id)
    }

    suspend fun whatBudgetForDay(
        excludeCurrentDay: Boolean = false,
        applyTodaySpends: Boolean = false,
        notCommittedSpent: BigDecimal = BigDecimal.ZERO
    ): BigDecimal = budgetCalculator.whatBudgetForDay(excludeCurrentDay, applyTodaySpends, notCommittedSpent)

    suspend fun howMuchBudgetRest(): BigDecimal = budgetCalculator.howMuchBudgetRest()

    suspend fun howMuchNotSpent(
        excludeSkippedPart: Boolean = false,
    ): BigDecimal = budgetCalculator.howMuchNotSpent(excludeSkippedPart)

    suspend fun nextDayBudget(
        excludeSkippedPart: Boolean = false,
    ): BigDecimal = budgetCalculator.nextDayBudget(excludeSkippedPart)

    suspend fun howMuchSaved(): BigDecimal = budgetCalculator.howMuchSaved()

    suspend fun addSpent(newTransaction: Transaction) {
        this.transactionDao.insert(newTransaction)
        markUpsert(SyncTables.TRANSACTIONS, newTransaction.id)

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
                Log.e("SpendsRepository", "addSpent DataStore update failed", e)
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

        categoryCapTracker.checkCategoryCapAlert(newTransaction)

        // Auto-assign a category (offline keywords, then the AI model) to records that have
        // none. Runs in the background on an application-scoped coroutine so the app never
        // blocks — the record is already saved by the time the AI provider answers.
        if (newTransaction.category.isNullOrBlank()) {
            categoryAssignmentScheduler.schedule()
        }
    }

    suspend fun importTransactions(transactions: List<Transaction>) {
        // Idempotency: skip rows that already exist (same type, value, date, comment) and
        // dedupe repeated rows within the file itself, so re-importing the same CSV never
        // creates duplicate transactions. Already-archived rows (from a previous import)
        // are part of the existing set too.
        val existingKeys = buildSet {
            transactionDao.getAll().first().forEach { tx ->
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

        // Persist the offline keyword category on any imported row that has none, so the
        // assignment is saved with the data and never needs an AI reload later. Rows without
        // a confident keyword match stay uncategorized (display falls back to OTHER).
        val categorized = filtered.map { transaction ->
            if (transaction.category.isNullOrBlank()) {
                offlineCategoryOrNull(transaction.comment)
                    ?.let { transaction.copy(category = it.name) }
                    ?: transaction
            } else {
                transaction
            }
        }

        val currentPeriodStart = context.budgetDataStore.data.first()[startPeriodDateStoreKey]
            ?.let { Date(it) }
        val currentPeriodFinish = context.budgetDataStore.data.first()[finishPeriodDateStoreKey]
            ?.let { Date(it) }

        if (currentPeriodStart == null || currentPeriodFinish == null) {
            categorized.forEach { addSpent(it) }
            categoryAssignmentScheduler.schedule()
            return
        }

        val inPeriod = categorized.filter {
            !it.date.before(currentPeriodStart) && !it.date.after(currentPeriodFinish)
        }
        val outOfPeriod = categorized.filter {
            it.date.before(currentPeriodStart) || it.date.after(currentPeriodFinish)
        }

        inPeriod.forEach { addSpent(it) }
        archiveImported(outOfPeriod)
        // AI categories for the rows keywords couldn't place (both in-period and archived) are
        // assigned in the background — the import itself returns without waiting on the model.
        categoryAssignmentScheduler.schedule()
    }

    // Rows that fall outside the active budget period are archived into month buckets
    // (grouped by calendar month) or merged into an already-archived period that covers
    // their date. They must never touch the active table, so they stay out of the budget.
    private suspend fun archiveImported(outOfPeriod: List<Transaction>) {
        if (outOfPeriod.isEmpty()) return

        val existingPeriods = budgetPeriodDao.getAllNow().sortedBy { it.isImported }
        val currencyCode = currentCurrencyCode()
        val monthBucketIds = mutableMapOf<YearMonth, String>()
        val rowsByPeriod = mutableMapOf<String, MutableList<Transaction>>()

        outOfPeriod.forEach { tx ->
            val month = YearMonth.from(tx.date.toLocalDateTime())
            val coveringPeriod = existingPeriods.firstOrNull { period ->
                !tx.date.before(period.startDate) && !tx.date.after(period.finishDate)
            }

            val periodId = coveringPeriod?.id ?: monthBucketIds.getOrPut(month) {
                val period = BudgetPeriod(
                    budget = BigDecimal.ZERO,
                    startDate = month.atDay(1).toDate(),
                    finishDate = Date(month.atEndOfMonth().atTime(23, 59, 59).toDate().time + 999),
                    actualFinishDate = null,
                    currencyCode = currencyCode,
                    totalSpent = BigDecimal.ZERO,
                    isImported = true,
                )
                budgetPeriodDao.insert(period)
                markUpsert(SyncTables.BUDGET_PERIODS, period.id)
                period.id
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
            markUpsert(SyncTables.BUDGET_PERIODS, periodId)
            val archived = rows.map { tx ->
                ArchivedTransaction(
                    periodId = periodId,
                    type = tx.type,
                    value = tx.value,
                    date = tx.date,
                    comment = tx.comment,
                    category = tx.category,
                )
            }
            budgetPeriodDao.insertArchivedTransactions(archived)
            markUpserts(SyncTables.ARCHIVED_TRANSACTIONS, archived.map { it.id })
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
        val deletedRows = this.transactionDao.deleteById(transactionForRemove.id)
        if (deletedRows == 0) return

        markDeleted(
            SyncTables.TRANSACTIONS,
            transactionForRemove.id,
            transactionForRemove.familyId,
            transactionForRemove.syncSeq,
        )
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

        categoryCapTracker.resyncCategoryCapNotified(transactionForRemove)
    }
}