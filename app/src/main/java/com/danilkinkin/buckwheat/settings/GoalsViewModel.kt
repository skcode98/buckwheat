package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.SavingsGoalDao
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.SpendsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {
    val goals: LiveData<List<SavingsGoal>> = savingsGoalDao.getAll()

    fun addGoal(name: String, targetAmount: BigDecimal) {
        if (name.isBlank() || targetAmount <= BigDecimal.ZERO) return
        viewModelScope.launch {
            savingsGoalDao.insert(
                SavingsGoal(
                    name = name.trim(),
                    targetAmount = targetAmount,
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
                if (budgetRest < amount) return@withLock
                val newAmount = goal.currentAmount + amount
                val completed = newAmount >= goal.targetAmount
                savingsGoalDao.update(
                    goal.copy(currentAmount = newAmount, completed = completed)
                )
                spendsRepository.addSpent(
                    Transaction(
                        type = TransactionType.SPENT,
                        value = amount,
                        date = Date(),
                        comment = "\u2192 ${goal.name}",
                    )
                )
            }
        }
    }

    fun deleteGoal(id: Long) {
        viewModelScope.launch {
            savingsGoalDao.deleteById(id)
        }
    }
}
