package com.danilkinkin.buckwheat.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.entities.Goal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goalsRepository: GoalsRepository,
) : ViewModel() {
    val goals: StateFlow<List<Goal>> = goalsRepository.getAllGoals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addGoal(name: String, targetAmount: BigDecimal, deadline: Long?) {
        viewModelScope.launch {
            goalsRepository.addGoal(
                Goal(
                    name = name,
                    targetAmount = targetAmount,
                    deadline = deadline?.let { java.util.Date(it) },
                )
            )
        }
    }

    fun contributeToGoal(goalId: String, amount: BigDecimal) {
        viewModelScope.launch {
            val currentGoals = goals.value
            val goal = currentGoals.find { it.id == goalId } ?: return@launch
            val newSaved = goal.savedAmount + amount
            val completed = newSaved >= goal.targetAmount
            goalsRepository.updateGoal(
                goal.copy(
                    savedAmount = if (completed) goal.targetAmount else newSaved,
                    completed = completed,
                )
            )
        }
    }

    fun deleteGoal(goalId: String) {
        viewModelScope.launch {
            goalsRepository.deleteGoal(goalId)
        }
    }
}
