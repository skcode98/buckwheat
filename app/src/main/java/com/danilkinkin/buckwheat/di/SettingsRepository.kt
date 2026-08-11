package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.interleaved.CategoryFrequency
import com.danilkinkin.buckwheat.interleaved.InterleavedCategory
import com.danilkinkin.buckwheat.interleaved.ScheduleSuggestion
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_HOUR
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.notifications.SpendDigestFrequency
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetDesign
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

val debugStoreKey = booleanPreferencesKey("debug")
val showSpentCardByDefaultStoreKey = booleanPreferencesKey("showSpentCardByDefault")
val voiceAiApiKeyStoreKey = stringPreferencesKey("voiceAiApiKey")
val voiceAiProviderUrlStoreKey = stringPreferencesKey("voiceAiProviderUrl")
val voiceAiModelStoreKey = stringPreferencesKey("voiceAiModel")
val aiIntelligenceEnabledStoreKey = booleanPreferencesKey("aiIntelligenceEnabled")
val voiceWidgetDesignStoreKey = stringPreferencesKey("voiceWidgetDesign")
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
val categorySchedulesStoreKey = stringPreferencesKey("categorySchedules")

const val DEFAULT_VOICE_AI_PROVIDER_URL = "https://openrouter.ai/api/v1/chat/completions"
const val DEFAULT_VOICE_AI_MODEL = "openai/gpt-oss-20b:free"

// Master gate for every AI-powered feature. Defaults to enabled; a saved `false` turns AI off
// app-wide (voice parsing and spend categorization then use their offline fallbacks).
fun aiIntelligenceEnabled(prefs: Preferences): Boolean = prefs[aiIntelligenceEnabledStoreKey] ?: true

const val RECURRING_ALERT_DEFAULT_HOUR = 9
const val RECURRING_ALERT_DEFAULT_MINUTE = 0

const val SPEND_DIGEST_DEFAULT_HOUR = 20
const val SPEND_DIGEST_DEFAULT_MINUTE = 0

private const val LEGACY_VOICE_AI_MODEL = "nvidia/nemotron-3-ultra-550b-a55b:free"

// The legacy default was a 550B ultra model whose free tier is heavily rate-limited (HTTP 429).
// Map it to the current default so already-saved settings upgrade automatically.
fun normalizeVoiceAiModel(saved: String?): String? =
    if (saved == LEGACY_VOICE_AI_MODEL) DEFAULT_VOICE_AI_MODEL else saved

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

fun parseCategoryCapNotified(raw: String?): Map<String, Int> =
    parseCategoryCapNotifiedWithWindow(raw).mapValues { it.value.first }

// Notified entries may additionally carry the interleaved window start they were recorded
// for: "name:bucket@windowStartEpochDay". Legacy "name:bucket" entries (no "@") default to
// Long.MIN_VALUE so the first rollover check treats them as recorded in a different window
// and resets the bucket.
fun parseCategoryCapNotifiedWithWindow(raw: String?): Map<String, Pair<Int, Long>> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val parts = entry.split(':', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val name = parts[0].trim()
        val windowPart = parts[1].trim().split('@')
        val bucket = windowPart[0].toIntOrNull() ?: return@mapNotNull null
        if (name.isBlank() || bucket <= 0) return@mapNotNull null
        val windowStart = if (windowPart.size == 2) {
            windowPart[1].toLongOrNull() ?: Long.MIN_VALUE
        } else {
            Long.MIN_VALUE
        }
        name to (bucket to windowStart)
    }.toMap()
}

// Plain buckets serialize without the "@" suffix so they stay backward compatible.
fun serializeCategoryCapNotifiedWithWindow(notified: Map<String, Pair<Int, Long>>): String =
    notified.entries
        .filter { it.value.first > 0 }
        .sortedBy { it.key }
        .joinToString(";") { (name, state) ->
            if (state.second == Long.MIN_VALUE) "$name:${state.first}"
            else "$name:${state.first}@${state.second}"
        }

// Interleaved budget schedules, serialized as "name:frequency:anchorEpochDay;...". The
// amount lives in the caps key; schedule entries only carry frequency + window anchor.
fun serializeCategorySchedules(schedules: Map<String, InterleavedCategory>): String =
    schedules.values
        .sortedBy { it.name }
        .joinToString(";") { "${it.name}:${it.frequency.name}:${it.anchorEpochDay}" }

fun parseCategorySchedules(raw: String?): Map<String, InterleavedCategory> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val parts = entry.split(':')
        if (parts.size != 3) return@mapNotNull null
        val name = parts[0].trim()
        val frequency = runCatching { CategoryFrequency.valueOf(parts[1].trim()) }.getOrNull()
            ?: return@mapNotNull null
        val anchorEpochDay = parts[2].trim().toLongOrNull() ?: return@mapNotNull null
        if (name.isBlank()) return@mapNotNull null
        name to InterleavedCategory(name, BigDecimal.ZERO, frequency, anchorEpochDay)
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
        it[voiceAiProviderUrlStoreKey] ?: DEFAULT_VOICE_AI_PROVIDER_URL
    }
    fun getVoiceAiModel() = context.settingsDataStore.data.map {
        normalizeVoiceAiModel(it[voiceAiModelStoreKey]) ?: DEFAULT_VOICE_AI_MODEL
    }
    fun getVoiceWidgetDesign() = context.settingsDataStore.data.map {
        runCatching {
            VoiceWidgetDesign.valueOf(it[voiceWidgetDesignStoreKey] ?: "")
        }.getOrDefault(VoiceWidgetDesign.PERCENT)
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

    // Observe the configured interleaved schedules (empty map when none are set).
    fun getCategorySchedules(): Flow<Map<String, InterleavedCategory>> =
        context.settingsDataStore.data.map { parseCategorySchedules(it[categorySchedulesStoreKey]) }

    // Schedules merged with their cap amounts into full interleaved categories. A category
    // with a schedule entry is an interleaved budget; without one it stays a plain cap.
    fun getInterleavedCategories(): Flow<Map<String, InterleavedCategory>> {
        val capsFlow = getCategoryCaps()
        val schedulesFlow = getCategorySchedules()
        return combine(capsFlow, schedulesFlow) { caps, schedules ->
            schedules.mapValues { (name, schedule) ->
                schedule.copy(amount = caps[name] ?: BigDecimal.ZERO)
            }
        }
    }

    // Persist caps + schedules and reset the notified state in a single edit (per the
    // single-edit rule). Frequency or anchor edits must re-arm the 80%/100% alerts.
    suspend fun setCategoryCapsAndSchedules(
        caps: Map<String, BigDecimal>,
        schedules: Map<String, InterleavedCategory>,
    ) {
        val capsSerialized = serializeCategoryCaps(caps)
        val schedulesSerialized = serializeCategorySchedules(schedules)
        context.settingsDataStore.edit {
            if (capsSerialized.isEmpty()) {
                it.remove(categoryCapsStoreKey)
            } else {
                it[categoryCapsStoreKey] = capsSerialized
            }
            if (schedulesSerialized.isEmpty()) {
                it.remove(categorySchedulesStoreKey)
            } else {
                it[categorySchedulesStoreKey] = schedulesSerialized
            }
            it.remove(categoryCapNotifiedStoreKey)
        }
    }

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

    // Merge detected recurring patterns (Phase 6 calibration) into the caps+schedules store:
    // a suggestion seeds the schedule and cap for a category that isn't scheduled yet. Any
    // existing schedule is kept as-is, so user choices always win over mined suggestions.
    // Single edit, re-arms the 80%/100% alerts.
    suspend fun applyScheduleSuggestions(
        suggestions: List<ScheduleSuggestion>,
        anchor: LocalDate,
    ) {
        if (suggestions.isEmpty()) return
        val caps = parseCategoryCaps(context.settingsDataStore.data.first()[categoryCapsStoreKey])
            .toMutableMap()
        val schedules =
            parseCategorySchedules(context.settingsDataStore.data.first()[categorySchedulesStoreKey])
                .toMutableMap()
        var changed = false
        suggestions.forEach { suggestion ->
            if (schedules[suggestion.name] == null) {
                schedules[suggestion.name] = InterleavedCategory(
                    name = suggestion.name,
                    amount = suggestion.amount,
                    frequency = suggestion.frequency,
                    anchorEpochDay = anchor.toEpochDay(),
                )
                caps[suggestion.name] = suggestion.amount
                changed = true
            }
        }
        if (changed) setCategoryCapsAndSchedules(caps, schedules)
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