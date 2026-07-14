package com.danilkinkin.buckwheat.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.entities.Category
import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.ImportResult
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.util.countDaysToToday
import com.danilkinkin.buckwheat.util.isToday
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RestedBudgetDistributionMethod { REST, ADD_TODAY, ASK }

@HiltViewModel
class SpendsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val spendsRepository: SpendsRepository,
) : ViewModel() {
    var tags = spendsRepository.getAllTags()
    var tagsWithCount = spendsRepository.getAllTagsWithCount()
    var categories = spendsRepository.getAllCategories()
    var tagCategoryMappings = spendsRepository.getAllMappingsWithCategory()
    var transactions = spendsRepository.getAllTransactions()
    var spends = spendsRepository.getAllSpends()

    // Search and filter state
    val searchQuery = MutableStateFlow("")
    val selectedTagFilter = MutableStateFlow<String?>(null)
    val recurringApplied = MutableLiveData<List<Transaction>>(emptyList())
    var budget = spendsRepository.getBudget().asLiveData()
    var needsBudget = spendsRepository.getNeedsBudget()
    var wantsBudget = spendsRepository.getWantsBudget()
    var spent = spendsRepository.getSpent().asLiveData()
    var dailyBudget = spendsRepository.getDailyBudget().asLiveData()
    var spentFromDailyBudget = spendsRepository.getSpentFromDailyBudget().asLiveData()
    var startPeriodDate = spendsRepository.getStartPeriodDate().asLiveData()
    var finishPeriodDate = spendsRepository.getFinishPeriodDate().asLiveData()
    var finishPeriodActualDate = spendsRepository.getFinishPeriodActualDate().asLiveData()
    var lastChangeDailyBudgetDate = spendsRepository.getLastChangeDailyBudgetDate().asLiveData()

    var currency = spendsRepository.getCurrency().asLiveData()
    var restedBudgetDistributionMethod =
        spendsRepository.getRestedBudgetDistributionMethod().asLiveData()
    var hideOverspendingWarn = spendsRepository.getHideOverspendingWarn().asLiveData()

    var requireDistributionRestedBudget = MutableLiveData(false)
    var requireSetBudget = MutableLiveData(false)
    var periodFinished = MutableLiveData(false)
    var addSpentError = MutableLiveData<String?>(null)
    var lastRemovedTransaction: MutableLiveData<Transaction> = MutableLiveData()

    // Selection mode for batch operations
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode
    private val _selectedTransactionIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedTransactionIds: StateFlow<Set<Int>> = _selectedTransactionIds

    init {
        runChangeDayAction()
        runScheduledDetectChangeDayTask()
        checkRecurringTemplates()
    }

    // Budget handling

    fun setBudget(newBudget: BigDecimal, newFinishDate: Date) {
        viewModelScope.launch {
            spendsRepository.setBudget(newBudget, newFinishDate)

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

    fun setBudgets(needsBudget: BigDecimal, wantsBudget: BigDecimal, newFinishDate: Date) {
        viewModelScope.launch {
            spendsRepository.setBudgets(needsBudget, wantsBudget, newFinishDate)
            requireSetBudget.value = false
            periodFinished.value = false
        }
    }

    fun changeBudgets(needsBudget: BigDecimal, wantsBudget: BigDecimal, newFinishDate: Date) {
        viewModelScope.launch {
            spendsRepository.changeBudgets(needsBudget, wantsBudget, newFinishDate)
            requireSetBudget.value = false
            periodFinished.value = false
        }
    }

    fun changeBudgetsAndStartDate(
        needsBudget: BigDecimal,
        wantsBudget: BigDecimal,
        newFinishDate: Date,
        newStartDate: Date,
    ) {
        viewModelScope.launch {
            spendsRepository.changeBudgets(needsBudget, wantsBudget, newFinishDate)
            spendsRepository.setStartPeriodDate(newStartDate)
            requireSetBudget.value = false
            periodFinished.value = false
        }
    }

    fun finishBudget() {
        viewModelScope.launch {
            spendsRepository.finishBudget(Date())

            requireSetBudget.value = false
            periodFinished.value = true
        }
    }

    fun setStartPeriodDate(newStartDate: Date) {
        viewModelScope.launch {
            spendsRepository.setStartPeriodDate(newStartDate)
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
            try {
                spendsRepository.addSpent(transactionForAdd)
                addSpentError.value = null
            } catch (e: Exception) {
                addSpentError.value = e.message ?: "Failed to add spend"
            }
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

    // Batch selection

    fun toggleSelectionMode() {
        _selectionMode.value = !_selectionMode.value
        if (!_selectionMode.value) {
            _selectedTransactionIds.value = emptySet()
        }
    }

    fun toggleTransactionSelection(uid: Int) {
        val current = _selectedTransactionIds.value.toMutableSet()
        if (current.contains(uid)) current.remove(uid) else current.add(uid)
        _selectedTransactionIds.value = current
        if (current.isEmpty()) _selectionMode.value = false
    }

    fun clearSelection() {
        _selectedTransactionIds.value = emptySet()
        _selectionMode.value = false
    }

    fun selectTransactions(uids: Set<Int>) {
        _selectedTransactionIds.value = uids
        if (uids.isNotEmpty()) _selectionMode.value = true
    }

    fun deleteSelectedTransactions() {
        viewModelScope.launch {
            val ids = _selectedTransactionIds.value
            if (ids.isEmpty()) return@launch
            val txs = spendsRepository.getAllTransactionsSuspend()
                .filter { it.uid in ids && it.type == TransactionType.SPENT }
            spendsRepository.deleteTransactions(txs)
            _selectedTransactionIds.value = emptySet()
            _selectionMode.value = false
        }
    }

    // Period history

    val allPeriods = spendsRepository.getAllPeriods()

    fun importTransactions(transactions: List<Transaction>, onResult: ((ImportResult) -> Unit)? = null) {
        viewModelScope.launch {
            val result = spendsRepository.importTransactions(transactions)
            onResult?.invoke(result)
        }
    }

    fun deletePeriod(id: Long) {
        viewModelScope.launch { spendsRepository.deletePeriod(id) }
    }

    fun updatePeriodNote(id: Long, note: String) {
        viewModelScope.launch { spendsRepository.updatePeriodNote(id, note) }
    }

    // Tags management

    fun addKnownTag(tag: String) {
        viewModelScope.launch {
            spendsRepository.addKnownTag(tag)
        }
    }

    fun deleteTag(tag: String) {
        viewModelScope.launch {
            spendsRepository.deleteTag(tag)
        }
    }

    fun renameTag(oldTag: String, newTag: String) {
        viewModelScope.launch {
            spendsRepository.renameTag(oldTag, newTag)
        }
    }

    // Category management

    fun addCategory(name: String, color: Long, monthlyLimit: BigDecimal = BigDecimal.ZERO) {
        viewModelScope.launch {
            spendsRepository.addCategory(name, color, monthlyLimit)
        }
    }

    fun renameCategory(id: Long, newName: String) {
        viewModelScope.launch {
            spendsRepository.renameCategory(id, newName)
        }
    }

    fun updateCategory(id: Long, name: String, color: Long, monthlyLimit: BigDecimal) {
        viewModelScope.launch {
            spendsRepository.updateCategory(id, name, color, monthlyLimit)
        }
    }

    fun deleteCategory(id: Long) {
        viewModelScope.launch {
            spendsRepository.deleteCategory(id)
        }
    }

    fun setTagCategory(tagName: String, categoryId: Long?) {
        viewModelScope.launch {
            spendsRepository.setTagCategory(tagName, categoryId)
        }
    }

    // Category limits

    fun setCategoryLimit(categoryId: Long, limit: BigDecimal) {
        viewModelScope.launch {
            spendsRepository.setCategoryLimit(categoryId, limit)
        }
    }

    fun checkRecurringTemplates() {
        viewModelScope.launch {
            val applied = spendsRepository.checkAndApplyRecurringTemplates()
            if (applied.isNotEmpty()) {
                recurringApplied.value = applied
            }
        }
    }

    fun dismissRecurringResult() {
        recurringApplied.value = emptyList()
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSelectedTagFilter(tag: String?) {
        selectedTagFilter.value = tag
    }

    // Spending streak

    suspend fun computeStreak(): Int {
        val txs = spends.value ?: return 0
        return spendsRepository.computeStreak(txs)
    }

    // Data backup / restore

    fun exportBackup(destPath: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = spendsRepository.exportBackup(destPath)
            onResult(ok)
        }
    }

    fun importBackup(sourcePath: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = spendsRepository.importBackup(sourcePath)
            onResult(ok)
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

    fun howMuchBudgetRest(): LiveData<BigDecimal> {
        val data = MutableLiveData<BigDecimal>()

        viewModelScope.launch {
            data.value = spendsRepository.howMuchBudgetRest()
        }

        return data
    }

    // Background tasks
    private fun runChangeDayAction() {
        viewModelScope.launch {
            val lastChangeDailyBudgetDate = spendsRepository.getLastChangeDailyBudgetDate().first()
            val finishPeriodDate = spendsRepository.getFinishPeriodDate().first()
            val finishPeriodActualDate = spendsRepository.getFinishPeriodActualDate().first()
            val dailyBudget = spendsRepository.getDailyBudget().first()
            val spentFromDailyBudget = spendsRepository.getSpentFromDailyBudget().first()
            val restedBudgetDistributionMethod =
                spendsRepository.getRestedBudgetDistributionMethod().first()

            val finishDayNotReached = if (finishPeriodActualDate == null) {
                finishPeriodDate != null
                        && countDaysToToday(finishPeriodDate) > 0
            } else {
                countDaysToToday(finishPeriodActualDate) > 0
            }

            val finishTimeReached = if (finishPeriodActualDate == null) {
                finishPeriodDate != null
                        && finishPeriodDate.time <= Date().time
            } else {
                finishPeriodActualDate.time <= Date().time
            }

            when {
                lastChangeDailyBudgetDate != null
                        && !isToday(lastChangeDailyBudgetDate)
                        && finishDayNotReached -> {
                    if (dailyBudget - spentFromDailyBudget > BigDecimal.ZERO) {
                        when (restedBudgetDistributionMethod) {
                            RestedBudgetDistributionMethod.ASK -> {
                                requireDistributionRestedBudget.value = true
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

                lastChangeDailyBudgetDate == null -> {
                    requireSetBudget.value = true
                }

                finishTimeReached -> {
                    periodFinished.value = true
                }
            }

            // Bug fix https://github.com/danilkinkin/buckwheat/issues/28
            if (dailyBudget - spentFromDailyBudget > BigDecimal.ZERO) {
                hideOverspendingWarn(false)
            }
        }
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
