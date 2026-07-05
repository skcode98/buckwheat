package com.danilkinkin.buckwheat.goals

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.entities.Goal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

val goalsStoreKey = stringPreferencesKey("goals")

@Singleton
class GoalsRepository @Inject constructor(
    @ApplicationContext val context: Context,
) {
    fun getAllGoals(): Flow<List<Goal>> = context.budgetDataStore.data.map { prefs ->
        val raw = prefs[goalsStoreKey] ?: "[]"
        val jsonArray = JSONArray(raw)
        (0 until jsonArray.length()).map { i ->
            Goal.fromJson(jsonArray.getJSONObject(i))
        }
    }

    suspend fun addGoal(goal: Goal) {
        context.budgetDataStore.edit { prefs ->
            val raw = prefs[goalsStoreKey] ?: "[]"
            val jsonArray = JSONArray(raw)
            jsonArray.put(goal.toJson())
            prefs[goalsStoreKey] = jsonArray.toString()
        }
    }

    suspend fun updateGoal(goal: Goal) {
        context.budgetDataStore.edit { prefs ->
            val raw = prefs[goalsStoreKey] ?: "[]"
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("id") == goal.id) {
                    jsonArray.put(i, goal.toJson())
                    break
                }
            }
            prefs[goalsStoreKey] = jsonArray.toString()
        }
    }

    suspend fun deleteGoal(goalId: String) {
        context.budgetDataStore.edit { prefs ->
            val raw = prefs[goalsStoreKey] ?: "[]"
            val jsonArray = JSONArray(raw)
            val newArray = JSONArray()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("id") != goalId) {
                    newArray.put(obj)
                }
            }
            prefs[goalsStoreKey] = newArray.toString()
        }
    }
}
