package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_HOUR
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.notifications.SpendDigestFrequency
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.widget.category.CategoryWidgetDesign
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetDesign
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import javax.inject.Inject

val debugStoreKey = booleanPreferencesKey("debug")
val showSpentCardByDefaultStoreKey = booleanPreferencesKey("showSpentCardByDefault")
val voiceAiApiKeyStoreKey = stringPreferencesKey("voiceAiApiKey")
val voiceAiProviderUrlStoreKey = stringPreferencesKey("voiceAiProviderUrl")
val voiceAiModelStoreKey = stringPreferencesKey("voiceAiModel")
val aiIntelligenceEnabledStoreKey = booleanPreferencesKey("aiIntelligenceEnabled")
val voiceWidgetDesignStoreKey = stringPreferencesKey("voiceWidgetDesign")
val categoryWidgetDesignStoreKey = stringPreferencesKey("categoryWidgetDesign")
val roundValuesStoreKey = booleanPreferencesKey("roundValues")
val reminderEnabledStoreKey = booleanPreferencesKey("reminderEnabled")
val reminderHourStoreKey = intPreferencesKey("reminderHour")
val reminderMinuteStoreKey = intPreferencesKey("reminderMinute")
val overspendNotifyEnabledStoreKey = booleanPreferencesKey("overspendNotifyEnabled")
val onTrackAlertEnabledStoreKey = booleanPreferencesKey("onTrackAlertEnabled")
val onTrackAlertHourStoreKey = intPreferencesKey("onTrackAlertHour")
val onTrackAlertMinuteStoreKey = intPreferencesKey("onTrackAlertMinute")
val recurringAlertEnabledStoreKey = booleanPreferencesKey("recurringAlertEnabled")
val recurringAlertHourStoreKey = intPreferencesKey("recurringAlertHour")
val recurringAlertMinuteStoreKey = intPreferencesKey("recurringAlertMinute")
val spendDigestEnabledStoreKey = booleanPreferencesKey("spendDigestEnabled")
val spendDigestFrequencyStoreKey = stringPreferencesKey("spendDigestFrequency")
val spendDigestHourStoreKey = intPreferencesKey("spendDigestHour")
val spendDigestMinuteStoreKey = intPreferencesKey("spendDigestMinute")
val goalMilestonesNotifiedStoreKey = stringPreferencesKey("goalMilestonesNotified")
val categoryCapsStoreKey = stringPreferencesKey("categoryCaps")
val categoryCapNotifiedStoreKey = stringPreferencesKey("categoryCapNotified")

const val RECURRING_ALERT_DEFAULT_HOUR = 9
const val RECURRING_ALERT_DEFAULT_MINUTE = 0

const val SPEND_DIGEST_DEFAULT_HOUR = 20
const val SPEND_DIGEST_DEFAULT_MINUTE = 0

// Master gate for every AI-powered feature. Defaults to enabled; a saved `false` turns AI off
// app-wide (voice parsing and spend categorization then use their offline fallbacks).
fun aiIntelligenceEnabled(prefs: Preferences): Boolean = prefs[aiIntelligenceEnabledStoreKey] ?: true

// Category spend caps, serialized as "name:amount;name:amount". Caps are period-scoped:
// progress is measured against the current budget period's spend totals (the same numbers
// the analytics categories card shows). Zero/negative amounts mean "no cap".
fun serializeCategoryCaps(caps: Map<String, BigDecimal>): String =
    caps.entries
        .filter { it.value > BigDecimal.ZERO }
        .sortedBy { it.key }
        .joinToString(";") { "${it.key}:${it.value.toPlainString()}" }

fun parseCategoryCaps(raw: String?): Map<String, BigDecimal> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val parts = entry.split(':', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val name = parts[0].trim()
        val amount = parts[1].toBigDecimalOrNull() ?: return@mapNotNull null
        if (name.isBlank() || amount <= BigDecimal.ZERO) return@mapNotNull null
        name to amount
    }.toMap()
}

// Per-category already-announced cap level, serialized as "name:bucket;name:bucket".
// Bucket 0 = nothing announced, 1 = 80% crossing announced, 2 = 100% crossing announced.
fun serializeCategoryCapNotified(notified: Map<String, Int>): String =
    notified.entries
        .filter { it.value > 0 }
        .sortedBy { it.key }
        .joinToString(";") { "${it.key}:${it.value}" }

// Parses the plain "name:bucket" form and tolerates legacy entries that carry an interleaved
// window suffix ("name:bucket@windowStartEpochDay" — the "@..." part is ignored).
fun parseCategoryCapNotified(raw: String?): Map<String, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val parts = entry.split(':', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val name = parts[0].trim()
        val bucket = parts[1].trim().substringBefore('@').toIntOrNull() ?: return@mapNotNull null
        if (name.isBlank() || bucket <= 0) return@mapNotNull null
        name to bucket
    }.toMap()
}

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
    fun getVoiceAiApiKey() = context.settingsDataStore.data.map {
        it[voiceAiApiKeyStoreKey] ?: ""
    }
    fun getVoiceAiProviderUrl() = context.settingsDataStore.data.map {
        it[voiceAiProviderUrlStoreKey] ?: ""
    }
    fun getVoiceAiModel() = context.settingsDataStore.data.map {
        it[voiceAiModelStoreKey] ?: ""
    }
    fun getVoiceWidgetDesign() = context.settingsDataStore.data.map {
        runCatching {
            VoiceWidgetDesign.valueOf(it[voiceWidgetDesignStoreKey] ?: "")
        }.getOrDefault(VoiceWidgetDesign.PERCENT)
    }
    fun getCategoryWidgetDesign() = context.settingsDataStore.data.map {
        runCatching {
            CategoryWidgetDesign.valueOf(it[categoryWidgetDesignStoreKey] ?: "")
        }.getOrDefault(CategoryWidgetDesign.BATTERY)
    }
    fun getTutorialStage(name: TUTORS) = context.settingsDataStore.data.map {
        it[name.key]?.let { value ->
            TUTORIAL_STAGE.valueOf(value)
        } ?: TUTORIAL_STAGE.NONE
    }

    fun isReminderEnabled() = context.settingsDataStore.data.map {
        it[reminderEnabledStoreKey] ?: false
    }

    fun getReminderHour() = context.settingsDataStore.data.map {
        it[reminderHourStoreKey] ?: DAILY_REMINDER_DEFAULT_HOUR
    }

    fun isRoundValuesEnabled() = context.settingsDataStore.data.map {
        it[roundValuesStoreKey] ?: false
    }

    fun getReminderMinute() = context.settingsDataStore.data.map {
        it[reminderMinuteStoreKey] ?: DAILY_REMINDER_DEFAULT_MINUTE
    }

    fun isOnTrackAlertEnabled() = context.settingsDataStore.data.map {
        it[onTrackAlertEnabledStoreKey] ?: false
    }

    fun isRecurringAlertEnabled() = context.settingsDataStore.data.map {
        it[recurringAlertEnabledStoreKey] ?: false
    }

    fun isSpendDigestEnabled() = context.settingsDataStore.data.map {
        it[spendDigestEnabledStoreKey] ?: false
    }

    fun getSpendDigestFrequency() = context.settingsDataStore.data.map {
        runCatching {
            SpendDigestFrequency.valueOf(it[spendDigestFrequencyStoreKey] ?: "")
        }.getOrDefault(SpendDigestFrequency.WEEKLY)
    }

    fun getSpendDigestHour() = context.settingsDataStore.data.map {
        it[spendDigestHourStoreKey] ?: SPEND_DIGEST_DEFAULT_HOUR
    }

    fun getSpendDigestMinute() = context.settingsDataStore.data.map {
        it[spendDigestMinuteStoreKey] ?: SPEND_DIGEST_DEFAULT_MINUTE
    }

    fun getRecurringAlertHour() = context.settingsDataStore.data.map {
        it[recurringAlertHourStoreKey] ?: RECURRING_ALERT_DEFAULT_HOUR
    }

    fun getRecurringAlertMinute() = context.settingsDataStore.data.map {
        it[recurringAlertMinuteStoreKey] ?: RECURRING_ALERT_DEFAULT_MINUTE
    }

    fun getOnTrackAlertHour() = context.settingsDataStore.data.map {
        it[onTrackAlertHourStoreKey] ?: DAILY_REMINDER_DEFAULT_HOUR
    }

    fun getOnTrackAlertMinute() = context.settingsDataStore.data.map {
        it[onTrackAlertMinuteStoreKey] ?: DAILY_REMINDER_DEFAULT_MINUTE
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

    suspend fun switchReminderEnabled(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[reminderEnabledStoreKey] = enabled
        }
    }

    suspend fun switchRoundValues(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[roundValuesStoreKey] = enabled
        }
    }

    suspend fun setReminderTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[reminderHourStoreKey] = hour
            it[reminderMinuteStoreKey] = minute
        }
    }

    suspend fun switchOnTrackAlertEnabled(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[onTrackAlertEnabledStoreKey] = enabled
        }
    }

    suspend fun switchRecurringAlertEnabled(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[recurringAlertEnabledStoreKey] = enabled
        }
    }

    suspend fun switchSpendDigestEnabled(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[spendDigestEnabledStoreKey] = enabled
        }
    }

    suspend fun setSpendDigestFrequency(frequency: SpendDigestFrequency) {
        context.settingsDataStore.edit {
            it[spendDigestFrequencyStoreKey] = frequency.name
        }
    }

    suspend fun setSpendDigestTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[spendDigestHourStoreKey] = hour
            it[spendDigestMinuteStoreKey] = minute
        }
    }

    suspend fun setRecurringAlertTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[recurringAlertHourStoreKey] = hour
            it[recurringAlertMinuteStoreKey] = minute
        }
    }

    suspend fun setOnTrackAlertTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[onTrackAlertHourStoreKey] = hour
            it[onTrackAlertMinuteStoreKey] = minute
        }
    }

    // The per-goal last-notified milestone bucket, serialized as "goalId:bucket;goalId:bucket",
    // so a milestone nudge is posted only once per goal.
    suspend fun getGoalNotifiedMilestones(): Map<Long, Int> {
        val raw = context.settingsDataStore.data.first()[goalMilestonesNotifiedStoreKey]
            ?: return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val bucket = parts[1].toIntOrNull() ?: return@mapNotNull null
            id to bucket
        }.toMap()
    }

    suspend fun setGoalNotifiedMilestones(milestones: Map<Long, Int>) {
        val serialized = milestones.entries
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}:${it.value}" }
        context.settingsDataStore.edit {
            if (serialized.isEmpty()) {
                it.remove(goalMilestonesNotifiedStoreKey)
            } else {
                it[goalMilestonesNotifiedStoreKey] = serialized
            }
        }
    }

    // Observe the configured per-category caps (empty map when none are set).
    fun getCategoryCaps(): Flow<Map<String, BigDecimal>> =
        context.settingsDataStore.data.map { parseCategoryCaps(it[categoryCapsStoreKey]) }

    // Persist the full caps map. Any change re-arms the 80%/100% crossing notifications
    // from the new baseline (the previous announced levels no longer apply).
    suspend fun setCategoryCaps(caps: Map<String, BigDecimal>) {
        val serialized = serializeCategoryCaps(caps)
        context.settingsDataStore.edit {
            if (serialized.isEmpty()) {
                it.remove(categoryCapsStoreKey)
            } else {
                it[categoryCapsStoreKey] = serialized
            }
            it.remove(categoryCapNotifiedStoreKey)
        }
    }

    // Forget every announced cap level, so the alerts can fire again (used on a new period).
    suspend fun clearCategoryCapNotified() {
        context.settingsDataStore.edit {
            it.remove(categoryCapNotifiedStoreKey)
        }
    }

    suspend fun getCategoryCapNotified(): Map<String, Int> {
        val raw = context.settingsDataStore.data.first()[categoryCapNotifiedStoreKey]
        return parseCategoryCapNotified(raw)
    }

    suspend fun setCategoryCapNotified(notified: Map<String, Int>) {
        val serialized = serializeCategoryCapNotified(notified)
        context.settingsDataStore.edit {
            if (serialized.isEmpty()) {
                it.remove(categoryCapNotifiedStoreKey)
            } else {
                it[categoryCapNotifiedStoreKey] = serialized
            }
        }
    }

    suspend fun setVoiceAiApiKey(apiKey: String) {
        context.settingsDataStore.edit {
            if (apiKey.isBlank()) {
                it.remove(voiceAiApiKeyStoreKey)
            } else {
                it[voiceAiApiKeyStoreKey] = apiKey.trim()
            }
        }
    }

    suspend fun setVoiceAiProvider(url: String, model: String) {
        context.settingsDataStore.edit {
            if (url.isBlank()) {
                it.remove(voiceAiProviderUrlStoreKey)
            } else {
                it[voiceAiProviderUrlStoreKey] = url.trim()
            }
            if (model.isBlank()) {
                it.remove(voiceAiModelStoreKey)
            } else {
                it[voiceAiModelStoreKey] = model.trim()
            }
        }
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