package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.SavingsGoalDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.notifications.GoalProgressNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val savingsGoalDao: SavingsGoalDao,
    private val spendsRepository: SpendsRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val goals: StateFlow<List<SavingsGoal>> = savingsGoalDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _goalCompletedEvents = MutableSharedFlow<SavingsGoal>(extraBufferCapacity = 1)
    val goalCompletedEvents: SharedFlow<SavingsGoal> = _goalCompletedEvents

    private val _allocationErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val allocationErrors: SharedFlow<String> = _allocationErrors

    fun addGoal(name: String, targetAmount: BigDecimal, deadline: Date? = null) {
        if (name.isBlank() || targetAmount <= BigDecimal.ZERO) return
        viewModelScope.launch {
            savingsGoalDao.insert(
                SavingsGoal(
                    name = name.trim(),
                    targetAmount = targetAmount,
                    deadline = deadline,
                )
            )
        }
    }

    // Serializes allocateToGoal so a check-then-act race can't double-spend the budget:
    // two concurrent allocations previously both read `budgetRest`, both passed the
    // `budgetRest < amount` check, and together exceeded the rest. All checks and writes
    // for a goal allocation now happen inside a single lock.
    private val allocationMutex = Mutex()

    fun allocateToGoal(goalId: Long, amount: BigDecimal) {
        if (amount <= BigDecimal.ZERO) return
        viewModelScope.launch {
            allocationMutex.withLock {
                val goal = savingsGoalDao.getById(goalId) ?: return@withLock
                val budgetRest = spendsRepository.howMuchBudgetRest()
                if (budgetRest < amount) {
                    _allocationErrors.tryEmit(appContext.getString(com.danilkinkin.buckwheat.R.string.goal_allocate_insufficient_budget))
                    return@withLock
                }
                val newAmount = goal.currentAmount + amount
                val completed = newAmount >= goal.targetAmount
                val updatedGoal = goal.copy(currentAmount = newAmount, completed = completed)
                savingsGoalDao.update(updatedGoal)
                if (completed && !goal.completed) {
                    _goalCompletedEvents.tryEmit(updatedGoal)
                }
                spendsRepository.addSpent(
                    Transaction(
                        type = TransactionType.SPENT,
                        value = amount,
                        date = Date(),
                        comment = "\u2192 ${goal.name}",
                    )
                )
                notifyMilestone(goal.id, updatedGoal)
            }
        }
    }

    // Posts a progress nudge when the allocation crossed a milestone that has not been
    // announced yet for this goal. Only the highest newly reached milestone is announced,
    // and the bucket is persisted so the same milestone never notifies twice.
    private suspend fun notifyMilestone(goalId: Long, goal: SavingsGoal) {
        val notified = settingsRepository.getGoalNotifiedMilestones()
        val lastBucket = notified[goalId] ?: 0
        val newBucket = goalMilestoneBucket(goalProgressPercent(goal))
        val milestone = highestNewlyCrossedMilestone(lastBucket, newBucket) ?: return
        val currency = spendsRepository.getCurrency().first()
        GoalProgressNotifier.notify(
            appContext,
            buildGoalNudgeMessage(appContext, goal, milestone, currency),
        )
        settingsRepository.setGoalNotifiedMilestones(notified + (goalId to newBucket))
    }

    fun deleteGoal(id: Long) {
        viewModelScope.launch {
            savingsGoalDao.deleteById(id)
            val notified = settingsRepository.getGoalNotifiedMilestones()
            if (notified.containsKey(id)) {
                settingsRepository.setGoalNotifiedMilestones(notified - id)
            }
        }
    }

    fun updateGoal(id: Long, name: String, targetAmount: BigDecimal, deadline: Date?) {
        if (name.isBlank() || targetAmount <= BigDecimal.ZERO) return
        viewModelScope.launch {
            val goal = savingsGoalDao.getById(id) ?: return@launch
            val completed = goal.currentAmount >= targetAmount
            savingsGoalDao.update(
                goal.copy(
                    name = name.trim(),
                    targetAmount = targetAmount,
                    deadline = deadline,
                    completed = completed,
                )
            )
        }
    }
}
