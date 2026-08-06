package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.danilkinkin.buckwheat.backup.BACKUP_VERSION
import com.danilkinkin.buckwheat.backup.BackupData
import com.danilkinkin.buckwheat.backup.BackupValue
import com.danilkinkin.buckwheat.backup.parseBackupData
import com.danilkinkin.buckwheat.backup.toJsonString
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.dao.SavingsGoalDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: TransactionDao,
    private val savedTagDao: SavedTagDao,
    private val savedCategoryDao: SavedCategoryDao,
    private val budgetPeriodDao: BudgetPeriodDao,
    private val recurringDao: RecurringDao,
    private val savingsGoalDao: SavingsGoalDao,
) {
    suspend fun exportBackup(): String {
        val backup = BackupData(
            version = BACKUP_VERSION,
            exportedAt = System.currentTimeMillis(),
            transactions = transactionDao.getAllNow(),
            budgetPeriods = budgetPeriodDao.getAllNow(),
            archivedTransactions = budgetPeriodDao.getAllArchivedNow(),
            savedTags = savedTagDao.getAllNow(),
            savedCategories = savedCategoryDao.getAllNow(),
            recurringTemplates = recurringDao.getAllNow(),
            savingsGoals = savingsGoalDao.getAllNow(),
            budgetPreferences = context.budgetDataStore.data.first().asBackupMap(),
            settingsPreferences = context.settingsDataStore.data.first().asBackupMap(),
        )
        return backup.toJsonString()
    }

    suspend fun restoreBackup(json: String): Boolean {
        val backup = parseBackupData(json) ?: return false

        // Wipe current data first. Deleting budget_periods cascades to archived_transactions
        // via the FK, so no explicit archived delete is needed.
        transactionDao.deleteAll()
        savedTagDao.deleteAll()
        savedCategoryDao.deleteAll()
        recurringDao.deleteAll()
        savingsGoalDao.deleteAll()
        budgetPeriodDao.deleteAll()

        // Insert in FK-safe order, preserving ids so archived_transactions keep their period link.
        budgetPeriodDao.insertAll(backup.budgetPeriods)
        backup.archivedTransactions.forEach {
            budgetPeriodDao.insertArchivedTransactions(listOf(it))
        }
        transactionDao.insertAll(backup.transactions)
        savedTagDao.insertAll(backup.savedTags)
        savedCategoryDao.insertAll(backup.savedCategories)
        recurringDao.insertAll(backup.recurringTemplates)
        savingsGoalDao.insertAll(backup.savingsGoals)

        context.budgetDataStore.edit { prefs ->
            prefs.clear()
            prefs.applyBackupMap(backup.budgetPreferences)
        }
        context.settingsDataStore.edit { prefs ->
            prefs.clear()
            prefs.applyBackupMap(backup.settingsPreferences)
        }

        return true
    }
}

private fun Preferences.asBackupMap(): Map<String, BackupValue> {
    val result = LinkedHashMap<String, BackupValue>()
    asMap().forEach { (key, value) ->
        when (value) {
            is Boolean -> result[key.name] = BackupValue.Bool(value)
            is Int -> result[key.name] = BackupValue.IntValue(value)
            is Long -> result[key.name] = BackupValue.LongValue(value)
            is Float -> result[key.name] = BackupValue.FloatValue(value)
            is String -> result[key.name] = BackupValue.Str(value)
            is Set<*> -> result[key.name] = BackupValue.StrSet(value.filterIsInstance<String>().toSet())
            else -> {}
        }
    }
    return result
}

private fun MutablePreferences.applyBackupMap(map: Map<String, BackupValue>) {
    map.forEach { (name, value) ->
        when (value) {
            is BackupValue.Bool -> this[booleanPreferencesKey(name)] = value.value
            is BackupValue.IntValue -> this[intPreferencesKey(name)] = value.value
            is BackupValue.LongValue -> this[longPreferencesKey(name)] = value.value
            is BackupValue.FloatValue -> this[floatPreferencesKey(name)] = value.value
            is BackupValue.Str -> this[stringPreferencesKey(name)] = value.value
            is BackupValue.StrSet -> this[stringSetPreferencesKey(name)] = value.value
        }
    }
}
