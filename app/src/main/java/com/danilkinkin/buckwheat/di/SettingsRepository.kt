package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_HOUR
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetDesign
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject

val debugStoreKey = booleanPreferencesKey("debug")
val showSpentCardByDefaultStoreKey = booleanPreferencesKey("showSpentCardByDefault")
val voiceAiApiKeyStoreKey = stringPreferencesKey("voiceAiApiKey")
val voiceAiProviderUrlStoreKey = stringPreferencesKey("voiceAiProviderUrl")
val voiceAiModelStoreKey = stringPreferencesKey("voiceAiModel")
val voiceWidgetDesignStoreKey = stringPreferencesKey("voiceWidgetDesign")
val reminderEnabledStoreKey = booleanPreferencesKey("reminderEnabled")
val reminderHourStoreKey = intPreferencesKey("reminderHour")
val reminderMinuteStoreKey = intPreferencesKey("reminderMinute")
val overspendNotifyEnabledStoreKey = booleanPreferencesKey("overspendNotifyEnabled")
val onTrackAlertEnabledStoreKey = booleanPreferencesKey("onTrackAlertEnabled")
val onTrackAlertHourStoreKey = intPreferencesKey("onTrackAlertHour")
val onTrackAlertMinuteStoreKey = intPreferencesKey("onTrackAlertMinute")

const val DEFAULT_VOICE_AI_PROVIDER_URL = "https://openrouter.ai/api/v1/chat/completions"
const val DEFAULT_VOICE_AI_MODEL = "openai/gpt-oss-20b:free"

private const val LEGACY_VOICE_AI_MODEL = "nvidia/nemotron-3-ultra-550b-a55b:free"

// The legacy default was a 550B ultra model whose free tier is heavily rate-limited (HTTP 429).
// Map it to the current default so already-saved settings upgrade automatically.
fun normalizeVoiceAiModel(saved: String?): String? =
    if (saved == LEGACY_VOICE_AI_MODEL) DEFAULT_VOICE_AI_MODEL else saved

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

    fun getReminderMinute() = context.settingsDataStore.data.map {
        it[reminderMinuteStoreKey] ?: DAILY_REMINDER_DEFAULT_MINUTE
    }

    fun isOnTrackAlertEnabled() = context.settingsDataStore.data.map {
        it[onTrackAlertEnabledStoreKey] ?: false
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

    suspend fun setOnTrackAlertTime(hour: Int, minute: Int) {
        context.settingsDataStore.edit {
            it[onTrackAlertHourStoreKey] = hour
            it[onTrackAlertMinuteStoreKey] = minute
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