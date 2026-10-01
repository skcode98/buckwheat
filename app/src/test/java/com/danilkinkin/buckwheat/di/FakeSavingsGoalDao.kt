package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.dao.SavingsGoalDao
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeSavingsGoalDao : SavingsGoalDao {
    private val goals = mutableListOf<SavingsGoal>()

    override fun getAll(): Flow<List<SavingsGoal>> {
        return flow { emit(goals.toList()) }
    }

    override suspend fun getById(id: String): SavingsGoal? {
        return goals.firstOrNull { it.id == id }
    }

    override suspend fun getAllNow(): List<SavingsGoal> {
        return goals.toList()
    }

    override suspend fun upsertOne(
        id: String,
        name: String,
        targetAmount: BigDecimal,
        currentAmount: BigDecimal,
        deadline: Date?,
        createdAt: Date,
        completed: Boolean,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    ) {
        val goal = SavingsGoal(
            id = id,
            name = name,
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            deadline = deadline,
            createdAt = createdAt,
            completed = completed,
            familyId = familyId,
            syncSeq = syncSeq,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            version = version,
        )
        val index = goals.indexOfFirst { it.id == id }
        if (index >= 0) goals[index] = goal else goals.add(goal)
    }

    override suspend fun insert(goal: SavingsGoal) {
        upsertOne(
            id = goal.id,
            name = goal.name,
            targetAmount = goal.targetAmount,
            currentAmount = goal.currentAmount,
            deadline = goal.deadline,
            createdAt = goal.createdAt,
            completed = goal.completed,
            familyId = goal.familyId,
            syncSeq = goal.syncSeq,
            updatedAt = goal.updatedAt,
            deletedAt = goal.deletedAt,
            version = goal.version,
        )
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

    override suspend fun deleteById(id: String) {
        goals.removeIf { it.id == id }
    }

    override suspend fun deleteAll() {
        goals.clear()
    }
}
