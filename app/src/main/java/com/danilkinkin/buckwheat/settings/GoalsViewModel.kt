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

    fun allocateToGoal(goalId: Long, amount: BigDecimal) {
        if (amount <= BigDecimal.ZERO) return
        viewModelScope.launch {
            val goal = savingsGoalDao.getById(goalId) ?: return@launch
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

    fun deleteGoal(id: Long) {
        viewModelScope.launch {
            savingsGoalDao.deleteById(id)
        }
    }
}
