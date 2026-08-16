package com.danilkinkin.buckwheat.ai

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import com.danilkinkin.buckwheat.di.aiIntelligenceEnabled
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.keyboard.isValidAiProviderUrl
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val AI_CONNECT_TIMEOUT_MS = 10_000
private const val AI_READ_TIMEOUT_MS = 30_000
private const val AI_MAX_RETRIES = 2
private const val AI_MAX_TOKENS = 6000
private const val AI_MAX_BACKOFF_MS = 8_000L

// Fully resolved settings for the app's single AI backend, ready to POST. The backend is an
// OpenAI-compatible chat-completions endpoint (like the HomAIe AI Backend) that owns provider
// routing, fallback, retries and the circuit breaker, so the app only needs URL + key + model.
data class AiBackendConfig(
    val url: String,
    val apiKey: String,
    val model: String,
)

sealed class AiRouterResult {
    data class Success(val text: String) : AiRouterResult()
    data class Failure(val message: String) : AiRouterResult()
    object NotConfigured : AiRouterResult()
}

// Resolves the single backend configuration from a DataStore snapshot. URL and API key are
// required; model is optional (when blank, the service provider uses its default model).
// Pure so it is unit-testable.
fun resolveAiBackendConfig(prefs: Preferences): AiBackendConfig? {
    val url = prefs[voiceAiProviderUrlStoreKey].orEmpty().trim()
    val apiKey = prefs[voiceAiApiKeyStoreKey].orEmpty().trim()
    val model = prefs[voiceAiModelStoreKey].orEmpty().trim()
    if (url.isBlank() || apiKey.isBlank()) return null
    return AiBackendConfig(url, apiKey, model)
}

// Derives the chat-completions URL from the configured base URL. A URL that already ends in
// "/chat/completions" is used as-is; anything else is treated as a base and gets the standard
// "/v1/chat/completions" suffix appended. Pure so it is unit-testable.
private fun aiProviderBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    return when {
        trimmed.endsWith("/chat/completions") -> trimmed.removeSuffix("/chat/completions")
        trimmed.endsWith("/models") -> trimmed.removeSuffix("/models")
        trimmed.endsWith("/v1") -> trimmed
        else -> "$trimmed/v1"
    }
}

// Derives the chat-completions URL from the configured base URL. A URL that already ends in
// "/chat/completions" is used as-is; anything else is treated as a base and gets the standard
// "/v1/chat/completions" suffix appended. Pure so it is unit-testable.
fun chatCompletionsUrl(raw: String): String = "${aiProviderBaseUrl(raw)}/chat/completions"

// Derives the models-listing URL from the configured base URL. Already a "/models" URL stays
// as-is, a chat-completions URL gets its suffix swapped for "/models", and a plain base gets
// the standard "/v1/models" suffix. Pure so it is unit-testable.
fun modelsEndpoint(raw: String): String = "${aiProviderBaseUrl(raw)}/models"

// Runs one prompt through the configured single backend. When the AI Intelligence master toggle
// is off or no valid backend is configured the caller gets NotConfigured and uses its offline
// fallback; when the backend request fails the reason is returned as Failure.
suspend fun callAi(
    context: Context,
    systemPrompt: String,
    userPrompt: String,
): AiRouterResult = withContext(Dispatchers.IO) {
    val prefs = context.settingsDataStore.data.first()
    if (!aiIntelligenceEnabled(prefs)) return@withContext AiRouterResult.NotConfigured

    val config = resolveAiBackendConfig(prefs)
    if (config == null) return@withContext AiRouterResult.NotConfigured

    val attempt = attemptBackend(config, systemPrompt, userPrompt)
    val text = attempt.text?.trim()
    if (text.isNullOrEmpty()) {
        AiRouterResult.Failure(attempt.error ?: "empty reply")
    } else {
        AiRouterResult.Success(text)
    }
}

// Sends a minimal prompt to the configured backend so the user can verify a URL/key/model
// combination from the settings sheet before relying on it. Never throws; the echoed reply is
// sanitized for display.
suspend fun testAiConnection(config: AiBackendConfig): AiRouterResult =
    withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext AiRouterResult.Failure("API key is required.")
        }
        if (!isValidAiProviderUrl(config.url)) {
            return@withContext AiRouterResult.Failure(
                "Provider URL must start with http:// or https://"
            )
        }
        val attempt = attemptBackend(config, "", "Reply with exactly the word: ok")
        val text = attempt.text?.trim()
        if (text.isNullOrEmpty()) {
            AiRouterResult.Failure(attempt.error ?: "no response")
        } else {
            AiRouterResult.Success(
                cleanAiOutput(text).take(80).ifBlank { "connected" },
            )
        }
    }

private class BackendAttempt(
    val text: String?,
    val error: String?,
    val backoffMs: Long? = null,
    val retryable: Boolean = false,
)

private suspend fun attemptBackend(
    config: AiBackendConfig,
    systemPrompt: String,
    userPrompt: String,
): BackendAttempt {
    var lastAttempt = BackendAttempt(null, "no response")
    for (attempt in 0..AI_MAX_RETRIES) {
        val result = postBackend(config, systemPrompt, userPrompt)
        if (result.text != null) return result
        lastAttempt = result
        if (!result.retryable || attempt == AI_MAX_RETRIES) break
        val backoff = (result.backoffMs ?: (1_000L shl attempt))
            .coerceIn(500L, AI_MAX_BACKOFF_MS)
        delay(backoff)
    }
    return lastAttempt
}

private fun postBackend(
    config: AiBackendConfig,
    systemPrompt: String,
    userPrompt: String,
): BackendAttempt {
    val url = chatCompletionsUrl(config.url)
    var connection: HttpURLConnection? = null
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        connection = conn
        conn.requestMethod = "POST"
        conn.connectTimeout = AI_CONNECT_TIMEOUT_MS
        conn.readTimeout = AI_READ_TIMEOUT_MS
        conn.useCaches = false
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.setRequestProperty("X-API-Key", config.apiKey)
        conn.doOutput = true
        conn.connect()

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(buildRequestBody(config, systemPrompt, userPrompt))
            writer.flush()
        }

        val code = conn.responseCode
        if (code in 200..299) {
            val responseText = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val text = extractProviderText(responseText)
            if (text.isBlank()) {
                Log.d(
                    "AiBackend",
                    "empty reply (HTTP $code): ${responseText.take(200)}",
                )
                return BackendAttempt(null, "empty reply")
            }
            return BackendAttempt(text, null)
        }

        val errorBody = runCatching {
            conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
        }.getOrNull().orEmpty().trim().take(200)
        val suffix = if (errorBody.isNotEmpty()) " — $errorBody" else ""
        val backoffMs = if (code == 429) {
            conn.getHeaderField("Retry-After")?.toLongOrNull()?.let { it * 1000L }
        } else {
            null
        }
        Log.d("AiBackend", "backend HTTP $code$suffix")
        return BackendAttempt(
            text = null,
            error = "HTTP $code$suffix",
            backoffMs = backoffMs,
            retryable = code == 429 || code in 500..599,
        )
    } catch (e: Exception) {
        Log.d("AiBackend", "backend request failed", e)
        return BackendAttempt(null, e.message ?: e.javaClass.simpleName)
    } finally {
        connection?.disconnect()
    }
}

// Builds an OpenAI-compatible chat-completions body: model, system + user messages and the
// shared generation bounds. Pure so it is unit-testable.
internal fun buildRequestBody(
    config: AiBackendConfig,
    systemPrompt: String,
    userPrompt: String,
): String {
    val system = systemPrompt.trim()
    val user = userPrompt.trim()
    val messages = JSONArray()
    if (system.isNotEmpty()) {
        messages.put(JSONObject().put("role", "system").put("content", system))
    }
    if (user.isNotEmpty()) {
        messages.put(JSONObject().put("role", "user").put("content", user))
    }
    val json = JSONObject()
    if (config.model.isNotBlank()) {
        json.put("model", config.model)
    }
    return json
        .put("temperature", 0)
        .put("max_tokens", AI_MAX_TOKENS)
        .put("messages", messages)
        .toString()
}

// Pulls the model reply out of the backend envelope. The HomAIe backend answers with a top-level
// "content" string; OpenAI-compatible chat-completions services answer with
// choices[0].message.content (string or, for multimodal models, an array of {type,text} parts).
// Missing or malformed fields yield an empty string.
//
// `content` may also be a JSON null, a JSON array of text parts (multimodal-style), or an
// arbitrary object — optString misreports all of these as literal "null" / "{}" / "[...]",
// which downstream parsers happily accept as garbage answers. We look the field up by type
// instead so each shape returns the right thing.
internal fun extractProviderText(responseText: String): String {
    val json = runCatching { JSONObject(responseText) }.getOrNull()
    if (json == null) {
        Log.d("AiBackend", "unexpected envelope: ${responseText.take(200)}")
        return ""
    }
    val direct = readContentField(json.opt("content")).trim()
    if (direct.isNotEmpty()) return direct
    val choicesContent = json.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.let { message -> readContentField(message.opt("content")) }
        .orEmpty()
    if (choicesContent.isNotEmpty()) return choicesContent.trim()
    Log.d("AiBackend", "unexpected envelope: ${responseText.take(200)}")
    return ""
}

// Reads a "content" field that may be a String, a JSONArray of text parts, JSON null, or
// something we don't recognize. Anything we can't interpret comes back as the empty string so
// the caller can fall through to the next envelope shape.
private fun readContentField(value: Any?): String = when (value) {
    null -> ""
    JSONObject.NULL -> ""
    is String -> value
    is JSONArray -> buildString {
        for (i in 0 until value.length()) {
            append(value.optJSONObject(i)?.optString("text", "").orEmpty())
        }
    }
    else -> ""
}

private val CODE_FENCE = Regex("```[\\w]*", RegexOption.IGNORE_CASE)
private val SCRIPT_TAG =
    Regex("<script\\b[^<]*(?:(?!</script>)<[^<]*)*</script>", RegexOption.IGNORE_CASE)
// NVIDIA NIM reasoning models (and some Gemini checkpoints) emit hidden thinking inside
// <think>...</think>; drop it so the app only sees the answer.
private val THINK_BLOCK = Regex("<think\\b[^>]*>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)

// Strips markdown fences, thinking blocks and stray HTML so test-connection replies stay readable.
internal fun cleanAiOutput(raw: String): String =
    raw.replace(CODE_FENCE, "")
        .replace(SCRIPT_TAG, "")
        .replace(THINK_BLOCK, "")
        .replace(Regex("</?html[^>]*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("</?body[^>]*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
        .trim()
