package com.danilkinkin.buckwheat.settings

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.ai.modelsEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.inject.Inject

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000

data class FreeModel(
    val id: String,
    val name: String,
    val provider: String?,
    val contextLength: Long,
)

@HiltViewModel
class VoiceAiSettingsViewModel @Inject constructor() : ViewModel() {
    private val _freeModels = MutableLiveData<List<FreeModel>>(emptyList())
    val freeModels: LiveData<List<FreeModel>> = _freeModels

    // The list is fetched lazily whenever the user opens the model dropdown, using the
    // apiKey/providerUrl the sheet is currently editing (not the last-saved values) so the
    // picks work before Save and reflect any in-progress changes.
    fun refreshFreeModels(apiKey: String, providerUrl: String) {
        viewModelScope.launch {
            _freeModels.value = loadFreeModels(apiKey, providerUrl)
        }
    }
}

// Fetches the models the configured backend exposes, in real time, so the user can pick from a
// dropdown instead of guessing ids. The backend's /v1/models listing is gated behind the same
// API key the user configured. Blank apiKey/providerUrl short-circuit to an empty list. Returns
// an empty list on any network/parse/auth failure so the sheet falls back to the existing model
// text field and never hard-crashes on a flaky connection.
val DEFAULT_MODEL_PRESETS = listOf(
    FreeModel("fast", "Fast (Groq Llama-3.3-70b)", "Alias", 0L),
    FreeModel("free", "Free (OpenRouter Llama-3.3-70b)", "Alias", 0L),
    FreeModel("latest", "Latest (Gemini 2.5 Flash)", "Alias", 0L),
    FreeModel("gemini/gemini-2.5-flash", "gemini-2.5-flash", "gemini", 0L),
    FreeModel("groq/llama-3.3-70b-versatile", "llama-3.3-70b-versatile", "groq", 0L),
    FreeModel("openrouter/meta-llama/llama-3.3-70b-instruction:free", "llama-3.3-70b-instruction:free", "openrouter", 0L),
)

// Fetches the models the configured backend exposes, in real time, so the user can pick from a
// dropdown instead of guessing ids. The backend's /v1/models listing is gated behind the same
// API key the user configured. Blank apiKey/providerUrl short-circuit to default presets. Returns
// presets on any network/parse/auth failure so the dropdown is always populated.
suspend fun loadFreeModels(
    apiKey: String,
    providerUrl: String,
): List<FreeModel> = withContext(Dispatchers.IO) {
    val key = apiKey.trim()
    val url = providerUrl.trim()
    if (url.isBlank()) return@withContext DEFAULT_MODEL_PRESETS

    val modelsUrl = modelsEndpoint(url)
    var connection: HttpURLConnection? = null
    try {
        val conn = (URL(modelsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (key.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("X-API-Key", key)
            }
        }
        connection = conn

        if (conn.responseCode in 200..299) {
            val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val fetched = parseFreeModels(body)
            if (fetched.isNotEmpty()) {
                val aliases = listOf(
                    FreeModel("fast", "Fast (Groq Llama-3.3-70b)", "Alias", 0L),
                    FreeModel("free", "Free (OpenRouter Llama-3.3-70b)", "Alias", 0L),
                    FreeModel("latest", "Latest (Gemini 2.5 Flash)", "Alias", 0L),
                )
                val existingIds = fetched.mapTo(HashSet()) { it.id }
                val newAliases = aliases.filter { it.id !in existingIds }
                return@withContext newAliases + fetched
            }
        } else {
            Log.d("VoiceAiSettings", "Models fetch failed: HTTP ${conn.responseCode}")
        }
    } catch (e: Exception) {
        Log.d("VoiceAiSettings", "Models fetch failed", e)
    } finally {
        connection?.disconnect()
    }
    return@withContext DEFAULT_MODEL_PRESETS
}

private fun parseFreeModels(body: String): List<FreeModel> =
    runCatching {
        val json = JSONObject(body)
        // OpenAI-style listing: {"data":[{"id":...}]}
        val data = json.optJSONArray("data")
        if (data != null) {
            buildList {
                for (i in 0 until data.length()) {
                    val m = data.optJSONObject(i) ?: continue
                    // OpenAI-style: {"id":"..."} or {"name":"..."}
                    val id = m.optString("id").takeIf { it.isNotBlank() }
                        ?: m.optString("name").takeIf { it.isNotBlank() }
                        ?: continue
                    add(
                        FreeModel(
                            id = id,
                            name = m.optString("name", id),
                            provider = null,
                            contextLength = m.optLong("context_window", 0L),
                        )
                    )
                }
            }
        } else {
            // HomAIe-style listing: {"models":[{"provider":"...","name":"..."}]}
            val models = json.optJSONArray("models") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until models.length()) {
                    val m = models.optJSONObject(i) ?: continue
                    // HomAIe uses "name" as the model identifier (no "id" field).
                    // Also tolerate OpenAI-style {"models":[{"id":"..."}]} for other backends.
                    val id = m.optString("id").takeIf { it.isNotBlank() }
                        ?: m.optString("name").takeIf { it.isNotBlank() }
                        ?: continue
                    val name = m.optString("name", id)
                    val provider = m.optString("provider").takeIf { it.isNotBlank() }
                    // HomAIe accepts three forms for "model": a bare name, an alias, or
                    // `provider/name`. Bare names can be ambiguous when the same model is
                    // served by more than one provider, so we prefer the disambiguated form
                    // when the backend hands us a provider. The dropdown still shows the
                    // short name; only the saved id carries the prefix.
                    val composedId = if (provider != null && !id.contains('/') && !looksLikeAlias(id)) {
                        "$provider/$name"
                    } else {
                        id
                    }
                    add(
                        FreeModel(
                            id = composedId,
                            name = name,
                            provider = provider,
                            contextLength = m.optLong("context_window", 0L),
                        )
                    )
                }
            }
        }
    }.getOrNull().orEmpty()

// Heuristic: very short ids made of letters/digits are treated as aliases (e.g. "fast",
// "cheap", "gpt-4o-mini") so we don't prepend a provider prefix and break the alias.
private fun looksLikeAlias(id: String): Boolean =
    id.length <= 12 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
