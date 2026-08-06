package com.danilkinkin.buckwheat.di

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.danilkinkin.buckwheat.data.dao.SavingsGoalDao
import com.danilkinkin.buckwheat.data.entities.SavingsGoal

class FakeSavingsGoalDao : SavingsGoalDao {
    private val goals = mutableListOf<SavingsGoal>()

    override fun getAll(): LiveData<List<SavingsGoal>> {
        return MutableLiveData(goals)
    }

    override suspend fun getById(id: Long): SavingsGoal? {
        return goals.firstOrNull { it.id == id }
    }

    override suspend fun getAllNow(): List<SavingsGoal> {
        return goals.toList()
    }

    override suspend fun insert(goal: SavingsGoal): Long {
        goals.add(goal)
        return goal.id
    }

    override suspend fun insertAll(goals: List<SavingsGoal>) {
        this.goals.addAll(goals)
    }

    override suspend fun update(goal: SavingsGoal) {
        val index = goals.indexOfFirst { it.id == goal.id }
        if (index >= 0) {
            goals[index] = goal
        }
    }

    override suspend fun delete(goal: SavingsGoal) {
        goals.removeIf { it.id == goal.id }
    }

    override suspend fun deleteById(id: Long) {
        goals.removeIf { it.id == id }
    }

    override suspend fun deleteAll() {
        goals.clear()
    }
}
