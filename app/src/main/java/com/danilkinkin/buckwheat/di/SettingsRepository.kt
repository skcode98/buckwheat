package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.notifications.NotificationType
import com.danilkinkin.buckwheat.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject

val debugStoreKey = booleanPreferencesKey("debug")
val showSpentCardByDefaultStoreKey = booleanPreferencesKey("showSpentCardByDefault")
val persistTagsStoreKey = booleanPreferencesKey("persistTags")
val reminderEnabledStoreKey = booleanPreferencesKey("reminderEnabled")
val reminderHourStoreKey = intPreferencesKey("reminderHour")
val reminderMinuteStoreKey = intPreferencesKey("reminderMinute")
val syncEnabledStoreKey = booleanPreferencesKey("syncEnabled")
val syncHourStoreKey = intPreferencesKey("syncHour")
val syncMinuteStoreKey = intPreferencesKey("syncMinute")
val dailySpendOverviewStoreKey = booleanPreferencesKey("dailySpendOverview")
val weeklyOverviewStoreKey = booleanPreferencesKey("weeklyOverview")
val monthlyExportStoreKey = booleanPreferencesKey("monthlyExport")
val monthlyOverviewStoreKey = booleanPreferencesKey("monthlyOverview")
val factsInsightsStoreKey = booleanPreferencesKey("factsInsights")
val goalsReminderStoreKey = booleanPreferencesKey("goalsReminder")

enum class TUTORIAL_STAGE {
    NONE,
    READY_TO_SHOW,
    PASSED
}

enum class TUTORS(val key: Preferences.Key<String>) {
    SWIPE_EDIT_SPENT(stringPreferencesKey("tutorialSwipePassed")),
    OPEN_WALLET(stringPreferencesKey("tutorialOpenWalletPassed")),
    OPEN_HISTORY(stringPreferencesKey("tutorialOpenHistoryPassed")),
}

class SettingsRepository @Inject constructor(
    @ApplicationContext val context: Context,
){
    fun isDebug() = context.settingsDataStore.data.map { it[debugStoreKey] ?: false }
    fun isShowSpentCardByDefault() = context.settingsDataStore.data.map {
        it[showSpentCardByDefaultStoreKey] ?: false
    }
    fun isPersistTags() = context.settingsDataStore.data.map {
        it[persistTagsStoreKey] ?: false
    }
    fun isReminderEnabled() = context.settingsDataStore.data.map {
        it[reminderEnabledStoreKey] ?: false
    }
    fun getReminderHour() = context.settingsDataStore.data.map {
        it[reminderHourStoreKey] ?: 20
    }
    fun getReminderMinute() = context.settingsDataStore.data.map {
        it[reminderMinuteStoreKey] ?: 0
    }
    fun getTutorialStage(name: TUTORS) = context.settingsDataStore.data.map {
        it[name.key]?.let { value ->
            TUTORIAL_STAGE.valueOf(value)
        } ?: TUTORIAL_STAGE.NONE
    }

    fun isSyncEnabled() = context.settingsDataStore.data.map {
        it[syncEnabledStoreKey] ?: false
    }
    fun getSyncHour() = context.settingsDataStore.data.map {
        it[syncHourStoreKey] ?: 22
    }
    fun getSyncMinute() = context.settingsDataStore.data.map {
        it[syncMinuteStoreKey] ?: 0
    }

    suspend fun switchSyncEnabled(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[syncEnabledStoreKey] = enabled
        }
    }
    suspend fun setSyncTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[syncHourStoreKey] = hour
            it[syncMinuteStoreKey] = minute
        }
    }

    suspend fun switchDebug(isDebug: Boolean) {
        context.settingsDataStore.edit {
            it[debugStoreKey] = isDebug
        }
    }

    suspend fun switchShowSpentCardByDefault(isShow: Boolean) {
        context.settingsDataStore.edit {
            it[showSpentCardByDefaultStoreKey] = isShow
        }
    }

    suspend fun switchPersistTags(isPersist: Boolean) {
        context.settingsDataStore.edit {
            it[persistTagsStoreKey] = isPersist
        }
    }

    suspend fun switchReminderEnabled(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[reminderEnabledStoreKey] = enabled
        }
    }

    suspend fun setReminderTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[reminderHourStoreKey] = hour
            it[reminderMinuteStoreKey] = minute
        }
    }

    suspend fun setNotificationTime(type: NotificationType, hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[intPreferencesKey("${type.name}_hour")] = hour
            it[intPreferencesKey("${type.name}_minute")] = minute
        }
    }

    fun getNotificationHour(type: NotificationType) = context.settingsDataStore.data.map {
        it[intPreferencesKey("${type.name}_hour")] ?: type.defaultHour
    }

    fun getNotificationMinute(type: NotificationType) = context.settingsDataStore.data.map {
        it[intPreferencesKey("${type.name}_minute")] ?: type.defaultMinute
    }

    fun isDailySpendOverviewEnabled() = context.settingsDataStore.data.map {
        it[dailySpendOverviewStoreKey] ?: false
    }
    fun isWeeklyOverviewEnabled() = context.settingsDataStore.data.map {
        it[weeklyOverviewStoreKey] ?: false
    }
    fun isMonthlyExportEnabled() = context.settingsDataStore.data.map {
        it[monthlyExportStoreKey] ?: false
    }
    fun isMonthlyOverviewEnabled() = context.settingsDataStore.data.map {
        it[monthlyOverviewStoreKey] ?: false
    }
    fun isFactsInsightsEnabled() = context.settingsDataStore.data.map {
        it[factsInsightsStoreKey] ?: false
    }
    fun isGoalsReminderEnabled() = context.settingsDataStore.data.map {
        it[goalsReminderStoreKey] ?: false
    }

    suspend fun switchDailySpendOverview(enabled: Boolean) {
        context.settingsDataStore.edit { it[dailySpendOverviewStoreKey] = enabled }
    }
    suspend fun switchWeeklyOverview(enabled: Boolean) {
        context.settingsDataStore.edit { it[weeklyOverviewStoreKey] = enabled }
    }
    suspend fun switchMonthlyExport(enabled: Boolean) {
        context.settingsDataStore.edit { it[monthlyExportStoreKey] = enabled }
    }
    suspend fun switchMonthlyOverview(enabled: Boolean) {
        context.settingsDataStore.edit { it[monthlyOverviewStoreKey] = enabled }
    }
    suspend fun switchFactsInsights(enabled: Boolean) {
        context.settingsDataStore.edit { it[factsInsightsStoreKey] = enabled }
    }
    suspend fun switchGoalsReminder(enabled: Boolean) {
        context.settingsDataStore.edit { it[goalsReminderStoreKey] = enabled }
    }

    suspend fun activateTutorial(name: TUTORS) {
        context.settingsDataStore.edit {
            if (it[name.key] == TUTORIAL_STAGE.PASSED.name) return@edit

            it[name.key] = TUTORIAL_STAGE.READY_TO_SHOW.name
        }
    }

    suspend fun passTutorial(name: TUTORS) {
        context.settingsDataStore.edit {
            it[name.key] = TUTORIAL_STAGE.PASSED.name
        }
    }
}