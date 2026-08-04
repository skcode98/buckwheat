package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject

val debugStoreKey = booleanPreferencesKey("debug")
val showSpentCardByDefaultStoreKey = booleanPreferencesKey("showSpentCardByDefault")
val voiceAiApiKeyStoreKey = stringPreferencesKey("voiceAiApiKey")
val voiceAiProviderUrlStoreKey = stringPreferencesKey("voiceAiProviderUrl")
val voiceAiModelStoreKey = stringPreferencesKey("voiceAiModel")

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
        it[voiceAiProviderUrlStoreKey] ?: "https://openrouter.ai/api/v1/chat/completions"
    }
    fun getVoiceAiModel() = context.settingsDataStore.data.map {
        it[voiceAiModelStoreKey] ?: "google/gemma-3n-e4b-it:free"
    }
    fun getTutorialStage(name: TUTORS) = context.settingsDataStore.data.map {
        it[name.key]?.let { value ->
            TUTORIAL_STAGE.valueOf(value)
        } ?: TUTORIAL_STAGE.NONE
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