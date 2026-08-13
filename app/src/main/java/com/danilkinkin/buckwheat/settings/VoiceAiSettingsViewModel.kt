package com.danilkinkin.buckwheat.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.ai.AiProvider
import com.danilkinkin.buckwheat.ai.aiApiKeyStoreKey
import com.danilkinkin.buckwheat.ai.aiProviderUrlStoreKey
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000

data class FreeModel(
    val id: String,
    val name: String,
    val contextLength: Long,
)

@HiltViewModel
class VoiceAiSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _freeModels = MutableLiveData<Map<String, List<FreeModel>>>(emptyMap())
    val freeModels: LiveData<Map<String, List<FreeModel>>> = _freeModels

    // The lists are fetched lazily per provider whenever the user opens a model dropdown, so the
    // per-provider API key is available and the picks are never stale.
    fun refreshFreeModels(provider: AiProvider) {
        viewModelScope.launch {
            _freeModels.value =
                (_freeModels.value.orEmpty()) + (provider.id to loadFreeModels(context, provider))
        }
    }
}

/**
 * Fetches the free models currently available from the given provider, in real time. OpenRouter's
 * listing is public (no key) and exposes pricing, so "free" is exact there (:free suffix + zero
 * pricing). The other providers gate their model lists behind the same API key the user already
 * configured and don't publish pricing, so their whole list is offered — those providers run on
 * free/free-tier plans anyway. Gemini has no pricing in its listing either, so it is also offered
 * wholesale. Returns an empty list on any network/parse/auth failure so the sheet falls back to the
 * existing model text field and never hard-crashes on a flaky connection.
 */
suspend fun loadFreeModels(context: Context, provider: AiProvider): List<FreeModel> =
    withContext(Dispatchers.IO) {
        val prefs = context.settingsDataStore.data.first()

        var apiKey = prefs[aiApiKeyStoreKey(provider)].orEmpty()
        if (apiKey.isBlank() && provider == AiProvider.OPENROUTER) {
            apiKey = prefs[voiceAiApiKeyStoreKey].orEmpty()
        }
        apiKey = apiKey.trim()

        var providerUrl = prefs[aiProviderUrlStoreKey(provider)].orEmpty()
        if (providerUrl.isBlank() && provider == AiProvider.OPENROUTER) {
            providerUrl = prefs[voiceAiProviderUrlStoreKey].orEmpty()
        }

        // Derive the models-list endpoint from the configured chat URL: chat-completions providers
        // expose GET <base>/models, Gemini exposes GET <base> directly (the URL already ends in
        // "/models" once the "{model}:generateContent" action is stripped).
        val base = when (provider) {
            AiProvider.GEMINI -> providerUrl
                .removeSuffix(":generateContent")
                .substringBefore("{model}")
                .trimEnd('/')
            else -> providerUrl.removeSuffix("/chat/completions").trimEnd('/')
        }
        if (base.isBlank()) return@withContext emptyList()
        // Only OpenRouter's listing is public; the rest need the provider's own key.
        if (provider != AiProvider.OPENROUTER && apiKey.isBlank()) return@withContext emptyList()

        val modelsUrl = if (provider == AiProvider.GEMINI) base else "$base/models"
        var connection: HttpURLConnection? = null
        try {
            val conn = (URL(modelsUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                if (provider == AiProvider.GEMINI) {
                    setRequestProperty("x-goog-api-key", apiKey)
                } else if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            connection = conn

            if (conn.responseCode !in 200..299) {
                Log.d("VoiceAiSettings", "Free models fetch failed: HTTP ${conn.responseCode}")
                return@withContext emptyList()
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return@withContext parseFreeModels(provider, body)
        } catch (e: Exception) {
            Log.d("VoiceAiSettings", "Free models fetch failed", e)
            return@withContext emptyList()
        } finally {
            connection?.disconnect()
        }
    }

private fun parseFreeModels(provider: AiProvider, body: String): List<FreeModel> =
    runCatching {
        val json = JSONObject(body)
        val result = when (provider) {
            AiProvider.GEMINI -> {
                val models = json.optJSONArray("models") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until models.length()) {
                        val m = models.optJSONObject(i) ?: continue
                        val id = m.optString("name").removePrefix("models/")
                        if (id.isBlank()) continue
                        add(
                            FreeModel(
                                id = id,
                                name = m.optString("displayName", id),
                                contextLength = m.optLong("inputTokenLimit", 0L),
                            )
                        )
                    }
                }
            }
            AiProvider.OPENROUTER -> {
                val data = json.optJSONArray("data") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until data.length()) {
                        val m = data.optJSONObject(i) ?: continue
                        val id = m.optString("id")
                        if (!id.endsWith(":free")) continue

                        val pricing = m.optJSONObject("pricing")
                        val promptPrice = pricing?.optString("prompt", "1") ?: "1"
                        val completionPrice = pricing?.optString("completion", "1") ?: "1"
                        if (promptPrice != "0" || completionPrice != "0") continue

                        add(
                            FreeModel(
                                id = id,
                                name = m.optString("name", id),
                                contextLength = m.optLong("context_length"),
                            )
                        )
                    }
                }
            }
            else -> {
                val data = json.optJSONArray("data") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until data.length()) {
                        val m = data.optJSONObject(i) ?: continue
                        val id = m.optString("id")
                        if (id.isBlank()) continue
                        add(
                            FreeModel(
                                id = id,
                                name = m.optString("name", id),
                                contextLength = if (provider == AiProvider.GROQ) {
                                    m.optLong("context_window", 0L)
                                } else {
                                    0L
                                },
                            )
                        )
                    }
                }
            }
        }
        // Ascending context as a faster-model proxy where available; providers without a context
        // field keep their listing order.
        result.sortedBy { it.contextLength }
    }.getOrNull().orEmpty()
