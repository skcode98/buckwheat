package com.danilkinkin.buckwheat.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.di.DEFAULT_VOICE_AI_PROVIDER_URL
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
    private val _freeModels = MutableLiveData<List<FreeModel>>(emptyList())
    val freeModels: LiveData<List<FreeModel>> = _freeModels

    init { refreshFreeModels() }

    fun refreshFreeModels() {
        viewModelScope.launch {
            _freeModels.value = loadFreeModels(context)
        }
    }
}

/**
 * Fetches the currently published free models from the configured OpenRouter endpoint
 * in real time. The OpenRouter models listing is public (~400 models, no auth key) and
 * is fetched live so the dropdown always reflects what's available right now.
 *
 * OpenRouter's listing exposes **no latency field**, so "fastest first" is approximated by
 * sorting on `context_length` ascending — smaller models tend to be the faster/low-latency
 * free options. Free models are detected by the `:free` id suffix plus zero prompt/completion
 * pricing. Returns an empty list on any network/parse failure so the sheet falls back to the
 * existing model text and never hard-crashes on a flaky connection.
 */
suspend fun loadFreeModels(context: Context): List<FreeModel> = withContext(Dispatchers.IO) {
    val providerUrl = context.settingsDataStore.data.first()[voiceAiProviderUrlStoreKey]
        .orEmpty()
        .ifBlank { DEFAULT_VOICE_AI_PROVIDER_URL }

    // The models endpoint shares the OpenRouter base; for non-OpenRouter providers
    // (or a custom URL without the chat/completions suffix) there's no public models
    // endpoint to hit, so we just return an empty list and let the user type a model.
    val base = providerUrl
        .removeSuffix("/chat/completions")
        .trimEnd('/')
    if (!base.contains("openrouter.ai")) return@withContext emptyList()

    val modelsUrl = "$base/models"
    var connection: HttpURLConnection? = null
    try {
        val conn = (URL(modelsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        connection = conn

        if (conn.responseCode !in 200..299) {
            Log.d("VoiceAiSettings", "Free models fetch failed: HTTP ${conn.responseCode}")
            return@withContext emptyList()
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val data = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()

        val result = buildList {
            for (i in 0 until data.length()) {
                val m = data.optJSONObject(i) ?: continue
                val id = m.optString("id")
                if (!id.endsWith(":free")) continue

                val pricing = m.optJSONObject("pricing")
                val promptPrice = pricing?.optString("prompt", "1") ?: "1"
                val completionPrice = pricing?.optString("completion", "1") ?: "1"
                if (promptPrice != "0" || completionPrice != "0") continue

                val name = m.optString("name", id)
                val contextLength = m.optLong("context_length")
                add(FreeModel(id = id, name = name, contextLength = contextLength))
            }
        }
        // No latency in the listing -> sort by context_length ascending as a faster-model proxy.
        return@withContext result.sortedBy { it.contextLength }
    } catch (e: Exception) {
        Log.d("VoiceAiSettings", "Free models fetch failed", e)
        return@withContext emptyList()
    } finally {
        connection?.disconnect()
    }
}
