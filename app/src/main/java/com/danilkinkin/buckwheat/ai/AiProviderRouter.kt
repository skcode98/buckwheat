package com.danilkinkin.buckwheat.ai

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import com.danilkinkin.buckwheat.di.aiIntelligenceEnabled
import com.danilkinkin.buckwheat.di.normalizeVoiceAiModel
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

private const val AI_CONNECT_TIMEOUT_MS = 10_000
private const val AI_READ_TIMEOUT_MS = 30_000
private const val AI_MAX_RETRIES = 2
private const val AI_MAX_TOKENS = 6000
private const val AI_MAX_BACKOFF_MS = 8_000L

// Fully resolved settings for one provider, ready to POST.
data class AiProviderConfig(
    val provider: AiProvider,
    val apiKey: String,
    val url: String,
    val model: String,
)

sealed class AiRouterResult {
    data class Success(val text: String, val provider: AiProvider) : AiRouterResult()
    data class Failure(val message: String) : AiRouterResult()
    object NotConfigured : AiRouterResult()
}

// Resolves which providers are usable from a DataStore snapshot: every provider whose saved key is
// present AND structurally valid, in the configured fallback order (user-reorderable in settings,
// FALLBACK_ORDER by default). The legacy single-provider settings are honoured as the OpenRouter
// entry so existing users keep working until they save the per-provider fields. Blank URL/model fall
// back to the provider's defaults. Pure so it is unit-testable.
fun resolveProviderConfigs(prefs: Preferences): List<AiProviderConfig> {
    val legacyModel = normalizeVoiceAiModel(prefs[voiceAiModelStoreKey])
    val result = mutableListOf<AiProviderConfig>()
    for (provider in resolveProviderOrder(prefs)) {
        var apiKey = prefs[aiApiKeyStoreKey(provider)].orEmpty()
        if (apiKey.isBlank() && provider == AiProvider.OPENROUTER) {
            apiKey = prefs[voiceAiApiKeyStoreKey].orEmpty()
        }
        apiKey = apiKey.trim()
        if (apiKey.isBlank() || !provider.isValidKey(apiKey)) continue

        var url = prefs[aiProviderUrlStoreKey(provider)].orEmpty()
        if (url.isBlank() && provider == AiProvider.OPENROUTER) {
            url = prefs[voiceAiProviderUrlStoreKey].orEmpty()
        }
        url = url.trim().ifBlank { provider.defaultUrl }

        var model = prefs[aiModelStoreKey(provider)].orEmpty()
        if (model.isBlank() && provider == AiProvider.OPENROUTER && !legacyModel.isNullOrBlank()) {
            model = legacyModel
        }
        model = model.trim().ifBlank { provider.defaultModel }

        result += AiProviderConfig(provider, apiKey, url, model)
    }
    return result
}

// Runs one prompt through the configured providers in shared fallback order: the first provider
// that returns a non-empty reply wins. When the AI Intelligence master toggle is off or no valid
// provider key is saved the caller gets NotConfigured and uses its offline fallback; when every
// provider failed the aggregated reason is returned as Failure.
suspend fun callAi(
    context: Context,
    systemPrompt: String,
    userPrompt: String,
): AiRouterResult = withContext(Dispatchers.IO) {
    val prefs = context.settingsDataStore.data.first()
    if (!aiIntelligenceEnabled(prefs)) return@withContext AiRouterResult.NotConfigured

    val configs = resolveProviderConfigs(prefs)
    if (configs.isEmpty()) return@withContext AiRouterResult.NotConfigured

    val failures = mutableListOf<String>()
    for (config in configs) {
        val attempt = attemptProvider(config, systemPrompt, userPrompt)
        val text = attempt.text?.trim()
        if (!text.isNullOrEmpty()) {
            return@withContext AiRouterResult.Success(text, config.provider)
        }
        failures += "${config.provider.displayName}: ${attempt.error ?: "empty reply"}"
    }
    AiRouterResult.Failure(failures.joinToString(" | "))
}

// Sends a minimal prompt to exactly the given provider so the user can verify a key/URL/model
// combination from the settings sheet before relying on it. Never throws; the echoed reply is
// sanitized for display.
suspend fun testProviderConnection(config: AiProviderConfig): AiRouterResult =
    withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext AiRouterResult.Failure("API key is required.")
        }
        if (!isValidAiProviderUrl(config.url)) {
            return@withContext AiRouterResult.Failure(
                "Provider URL must start with http:// or https://"
            )
        }
        val attempt = attemptProvider(config, "", "Reply with exactly the word: ok")
        val text = attempt.text?.trim()
        if (text.isNullOrEmpty()) {
            AiRouterResult.Failure(attempt.error ?: "no response")
        } else {
            AiRouterResult.Success(
                cleanAiOutput(text).take(80).ifBlank { "connected" },
                config.provider,
            )
        }
    }

private class ProviderAttempt(
    val text: String?,
    val error: String?,
    val backoffMs: Long? = null,
    val retryable: Boolean = false,
)

private suspend fun attemptProvider(
    config: AiProviderConfig,
    systemPrompt: String,
    userPrompt: String,
): ProviderAttempt {
    var lastAttempt = ProviderAttempt(null, "no response")
    for (attempt in 0..AI_MAX_RETRIES) {
        val result = postProvider(config, systemPrompt, userPrompt)
        if (result.text != null) return result
        lastAttempt = result
        if (!result.retryable || attempt == AI_MAX_RETRIES) break
        val backoff = (result.backoffMs ?: (1_000L shl attempt))
            .coerceIn(500L, AI_MAX_BACKOFF_MS)
        delay(backoff)
    }
    return lastAttempt
}

private fun postProvider(
    config: AiProviderConfig,
    systemPrompt: String,
    userPrompt: String,
): ProviderAttempt {
    val url = config.url.trim().replace("{model}", config.model)
    var connection: HttpURLConnection? = null
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        connection = conn
        conn.requestMethod = "POST"
        conn.connectTimeout = AI_CONNECT_TIMEOUT_MS
        conn.readTimeout = AI_READ_TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        if (config.provider == AiProvider.GEMINI) {
            conn.setRequestProperty("x-goog-api-key", config.apiKey)
        } else {
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.setRequestProperty("HTTP-Referer", "https://github.com/skcode98/buckwheat")
            conn.setRequestProperty("X-Title", "Buckwheat")
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(buildRequestBody(config, systemPrompt, userPrompt))
            writer.flush()
        }

        val code = conn.responseCode
        if (code in 200..299) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val text = extractProviderText(responseText, config.provider)
            return if (text.isBlank()) {
                ProviderAttempt(null, "empty reply")
            } else {
                ProviderAttempt(text, null)
            }
        }

        val errorBody = runCatching {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty().trim().take(200)
        val suffix = if (errorBody.isNotEmpty()) " — $errorBody" else ""
        val backoffMs = if (code == 429) {
            conn.getHeaderField("Retry-After")?.toLongOrNull()?.let { it * 1000L }
        } else {
            null
        }
        Log.d("AiProviderRouter", "${config.provider.displayName} HTTP $code$suffix")
        return ProviderAttempt(
            text = null,
            error = "HTTP $code$suffix",
            backoffMs = backoffMs,
            retryable = code == 429 || code in 500..599,
        )
    } catch (e: Exception) {
        Log.d("AiProviderRouter", "${config.provider.displayName} request failed", e)
        return ProviderAttempt(null, e.message ?: e.javaClass.simpleName)
    } finally {
        connection?.disconnect()
    }
}

internal fun buildRequestBody(
    config: AiProviderConfig,
    systemPrompt: String,
    userPrompt: String,
): String {
    val system = systemPrompt.trim()
    val user = userPrompt.trim()
    return if (config.provider == AiProvider.GEMINI) {
        // Gemini uses a single generate-content payload; the conversation is flattened into one
        // text part and the model is substituted into the URL by the caller.
        val combined = listOf(system, user).filter { it.isNotEmpty() }.joinToString("\n\n")
        JSONObject()
            .put(
                "contents",
                JSONArray()
                    .put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", combined)),
                        )
                    ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0)
                    .put("maxOutputTokens", AI_MAX_TOKENS)
                    .put("topK", 1)
                    .put("topP", 0.95),
            )
            .toString()
    } else {
        val messages = JSONArray()
        if (system.isNotEmpty()) {
            messages.put(JSONObject().put("role", "system").put("content", system))
        }
        if (user.isNotEmpty()) {
            messages.put(JSONObject().put("role", "user").put("content", user))
        }
        JSONObject()
            .put("model", config.model)
            .put("temperature", 0)
            .put("max_tokens", AI_MAX_TOKENS)
            .put("messages", messages)
            .toString()
    }
}

// Pulls the model reply out of the provider envelope. Chat-completions providers answer with
// choices[0].message.content (string or, for multimodal models, an array of {type,text} parts);
// Gemini with candidates[0].content.parts[] where each part is {text,...}. Missing or malformed
// fields yield an empty string so the router falls through to the next provider.
internal fun extractProviderText(responseText: String, provider: AiProvider): String =
    runCatching {
        val json = JSONObject(responseText)
        if (provider == AiProvider.GEMINI) {
            extractGeminiText(json)
        } else {
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.let { message -> extractMessageContent(message) }
                .orEmpty()
        }
    }.getOrNull().orEmpty().trim()

private fun extractGeminiText(json: JSONObject): String {
    val parts =
        json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: return ""
    return buildString {
        for (i in 0 until parts.length()) {
            append(parts.optJSONObject(i)?.optString("text", "").orEmpty())
        }
    }
}

private fun extractMessageContent(message: JSONObject): String {
    val content = message.opt("content") ?: return ""
    if (content is JSONArray) {
        return buildString {
            for (i in 0 until content.length()) {
                append(content.optJSONObject(i)?.optString("text", "").orEmpty())
            }
        }
    }
    return content.toString()
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
