package com.danilkinkin.buckwheat.di

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.withTransaction
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
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_HOUR
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.notifications.DailyBudgetReminderScheduler
import com.danilkinkin.buckwheat.notifications.OnTrackAlertScheduler
import com.danilkinkin.buckwheat.notifications.PeriodFinishScheduler
import com.danilkinkin.buckwheat.notifications.RecurringPaymentAlertScheduler
import com.danilkinkin.buckwheat.notifications.SpendDigestFrequency
import com.danilkinkin.buckwheat.notifications.SpendDigestScheduler
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.data.appLockEnabledStoreKey
import com.danilkinkin.buckwheat.data.appLockPinHashStoreKey
import com.danilkinkin.buckwheat.data.appLockBiometricEnabledStoreKey
import com.danilkinkin.buckwheat.data.appLockFailedAttemptsStoreKey
import com.danilkinkin.buckwheat.data.appLockLockoutUntilStoreKey
import com.danilkinkin.buckwheat.data.appLockBiometricIvStoreKey
import com.danilkinkin.buckwheat.data.appLockBiometricSecretStoreKey
import com.danilkinkin.buckwheat.data.appLockSmartTimeoutEnabledStoreKey
import com.danilkinkin.buckwheat.data.appLockSmartTimeoutSecondsStoreKey
import com.danilkinkin.buckwheat.data.appLockLastBackgroundTimeStoreKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DatabaseModule,
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
        // via the FK, so no explicit archived delete is needed. The whole wipe + reinsert
        // runs in a single Room transaction so a mid-restore failure rolls back cleanly
        // instead of leaving a partially-destroyed database.
        try {
            database.withTransaction {
                transactionDao.deleteAll()
                savedTagDao.deleteAll()
                savedCategoryDao.deleteAll()
                recurringDao.deleteAll()
                savingsGoalDao.deleteAll()
                budgetPeriodDao.deleteAll()

                // Insert in FK-safe order, preserving ids so archived_transactions keep their period link.
                budgetPeriodDao.insertAll(backup.budgetPeriods)
                budgetPeriodDao.insertArchivedTransactions(backup.archivedTransactions)
                transactionDao.insertAll(backup.transactions)
                savedTagDao.insertAll(backup.savedTags)
                savedCategoryDao.insertAll(backup.savedCategories)
                recurringDao.insertAll(backup.recurringTemplates)
                savingsGoalDao.insertAll(backup.savingsGoals)
            }
        } catch (e: Exception) {
            Log.e("BackupRepository", "Database restore failed, keeping previous data", e)
            return false
        }

        context.budgetDataStore.edit { prefs ->
            prefs.clear()
            prefs.applyBackupMap(backup.budgetPreferences)
        }
        context.settingsDataStore.edit { prefs ->
            prefs.clear()
            prefs.applyBackupMap(backup.settingsPreferences)
            // Never let a (possibly crafted) backup re-enable the app lock: the PIN hash is
            // stripped on export, so restoring the flags could lock the user out.
            prefs.remove(appLockEnabledStoreKey)
            prefs.remove(appLockPinHashStoreKey)
            prefs.remove(appLockBiometricEnabledStoreKey)
            prefs.remove(appLockFailedAttemptsStoreKey)
            prefs.remove(appLockLockoutUntilStoreKey)
            prefs.remove(appLockBiometricIvStoreKey)
            prefs.remove(appLockBiometricSecretStoreKey)
            prefs.remove(appLockSmartTimeoutEnabledStoreKey)
            prefs.remove(appLockSmartTimeoutSecondsStoreKey)
            prefs.remove(appLockLastBackgroundTimeStoreKey)
        }

        // The reminder alarm survives neither DataStore changes nor the DB wipe, so re-arm it
        // from the restored settings (the alarm manager is not covered by the backup itself).
        val restoredSettings = context.settingsDataStore.data.first()
        val reminderEnabled = restoredSettings[reminderEnabledStoreKey] ?: false
        val reminderHour = restoredSettings[reminderHourStoreKey] ?: DAILY_REMINDER_DEFAULT_HOUR
        val reminderMinute =
            restoredSettings[reminderMinuteStoreKey] ?: DAILY_REMINDER_DEFAULT_MINUTE
        if (reminderEnabled) {
            DailyBudgetReminderScheduler.schedule(context, reminderHour, reminderMinute)
        } else {
            DailyBudgetReminderScheduler.cancel(context)
        }

        // Same for the period-finish alarm: re-arm it from the restored finish date. A restored
        // period that already ended must not notify (the finish moment is in the past).
        val periodFinishEnabled = restoredSettings[periodFinishEnabledStoreKey] ?: false
        if (periodFinishEnabled) {
            val finishDateMillis = context.budgetDataStore.data.first()[finishPeriodDateStoreKey]
            if (finishDateMillis != null && finishDateMillis > Date().time) {
                PeriodFinishScheduler.schedule(context, Date(finishDateMillis))
            }
        } else {
            PeriodFinishScheduler.cancel(context)
        }

        // Re-arm the recurring-payment alert from restored settings.
        val recurringAlertEnabled = restoredSettings[recurringAlertEnabledStoreKey] ?: false
        if (recurringAlertEnabled) {
            val hour = restoredSettings[recurringAlertHourStoreKey] ?: RECURRING_ALERT_DEFAULT_HOUR
            val minute = restoredSettings[recurringAlertMinuteStoreKey] ?: RECURRING_ALERT_DEFAULT_MINUTE
            RecurringPaymentAlertScheduler.schedule(context, hour, minute)
        } else {
            RecurringPaymentAlertScheduler.cancel(context)
        }

        // Re-arm the on-track alert from restored settings.
        val onTrackEnabled = restoredSettings[onTrackAlertEnabledStoreKey] ?: false
        if (onTrackEnabled) {
            val hour = restoredSettings[onTrackAlertHourStoreKey] ?: DAILY_REMINDER_DEFAULT_HOUR
            val minute = restoredSettings[onTrackAlertMinuteStoreKey] ?: DAILY_REMINDER_DEFAULT_MINUTE
            OnTrackAlertScheduler.schedule(context, hour, minute)
        } else {
            OnTrackAlertScheduler.cancel(context)
        }

        // Re-arm the spend-digest alert from restored settings.
        val digestEnabled = restoredSettings[spendDigestEnabledStoreKey] ?: false
        if (digestEnabled) {
            val hour = restoredSettings[spendDigestHourStoreKey] ?: SPEND_DIGEST_DEFAULT_HOUR
            val minute = restoredSettings[spendDigestMinuteStoreKey] ?: SPEND_DIGEST_DEFAULT_MINUTE
            val frequency = runCatching {
                SpendDigestFrequency.valueOf(restoredSettings[spendDigestFrequencyStoreKey] ?: "")
            }.getOrDefault(SpendDigestFrequency.WEEKLY)
            SpendDigestScheduler.schedule(context, hour, minute, frequency)
        } else {
            SpendDigestScheduler.cancel(context)
        }

        return true
    }
}

private fun Preferences.asBackupMap(): Map<String, BackupValue> {
    val result = LinkedHashMap<String, BackupValue>()
    asMap().forEach { (key, value) ->
        // Never persist AI API keys into a backup file (plaintext secrets). Covers both the
        // legacy voiceAiApiKey key and every per-provider "ai.<provider>.apiKey" key.
        if (key.name == voiceAiApiKeyStoreKey.name || key.name.endsWith(".apiKey")) return@forEach
        // App lock is a local-only concern: never persist the PIN hash (secret) nor the lock
        // flags (a restored flag without a hash would lock the user out).
        if (key.name == appLockPinHashStoreKey.name ||
            key.name == appLockEnabledStoreKey.name ||
            key.name == appLockBiometricEnabledStoreKey.name ||
            key.name == appLockFailedAttemptsStoreKey.name ||
            key.name == appLockLockoutUntilStoreKey.name ||
            key.name == appLockBiometricIvStoreKey.name ||
            key.name == appLockBiometricSecretStoreKey.name ||
            key.name == appLockSmartTimeoutEnabledStoreKey.name ||
            key.name == appLockSmartTimeoutSecondsStoreKey.name ||
            key.name == appLockLastBackgroundTimeStoreKey.name
        ) return@forEach
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
