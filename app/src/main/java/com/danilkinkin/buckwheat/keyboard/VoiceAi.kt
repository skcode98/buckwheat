package com.danilkinkin.buckwheat.keyboard

import android.content.Context
import android.util.Log
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000

// Outcome of the AI parse attempt. NotConfigured means no API key is saved, so the caller
// silently uses the offline parser. Failure carries a human-readable reason so the user can
// diagnose why the AI path did not power the record (wrong key, rate limit, bad model, etc.).
sealed class VoiceAiResult {
    data class Success(val result: VoiceInputResult) : VoiceAiResult()
    data class Failure(val message: String) : VoiceAiResult()
    object NotConfigured : VoiceAiResult()
}

// Structured voice-AI parsing. Network access is bounded (10s connect / 20s read), the socket
// is always disconnected, and every failure is reported through VoiceAiResult.Failure so the
// caller can fall back to the offline VoiceInputParser and tell the user why AI did not run.
suspend fun parseVoiceInputWithAi(context: Context, transcript: String): VoiceAiResult =
    withContext(Dispatchers.IO) {
        val prefs = context.settingsDataStore.data.first()
        val apiKey = prefs[voiceAiApiKeyStoreKey].orEmpty()
        if (apiKey.isBlank()) return@withContext VoiceAiResult.NotConfigured

        val providerUrl = prefs[voiceAiProviderUrlStoreKey].orEmpty().ifBlank {
            "https://openrouter.ai/api/v1/chat/completions"
        }
        val model = prefs[voiceAiModelStoreKey].orEmpty().ifBlank {
            "google/gemma-3n-e4b-it:free"
        }

        // Built via JSONObject so the transcript (which may contain quotes/newlines) is
        // escaped correctly instead of being interpolated into a hand-built string.
        val requestBody = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put(
                "messages",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "You extract a spending record from a user voice transcript. " +
                                    "Reply with ONLY one JSON object and nothing else: " +
                                    "{\"amount\":\"150\",\"comment\":\"tea\",\"date\":\"today\"}. " +
                                    "amount must be a plain numeric string. comment must be a concise " +
                                    "description. date must be ISO-8601, 'today', 'yesterday', " +
                                    "'tomorrow', or null."
                            )
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "Transcript: $transcript")
                    )
            )
            .toString()

        var connection: HttpURLConnection? = null
        try {
            val url = URL(providerUrl)
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            if (conn.responseCode !in 200..299) {
                // Include a truncated response body when available: providers report useful
                // reasons (invalid key, insufficient credits, unknown model) in it.
                val errorBody = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty().trim().take(200)
                val suffix = if (errorBody.isNotEmpty()) " — $errorBody" else ""
                Log.d("VoiceAI", "AI parse failed with code ${conn.responseCode}")
                return@withContext VoiceAiResult.Failure("HTTP ${conn.responseCode}$suffix")
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }

            // The provider (OpenAI / OpenRouter) wraps the model reply in a chat-completions
            // envelope, so the JSON the model produced lives at choices[0].message.content.
            // Text-only models may also reply with a plain sentence instead of strict JSON, so
            // fall back to the offline parser on the reply when no JSON amount is found.
            val modelContent = extractModelContent(responseText)
            val result = parseVoiceAiContent(modelContent)
                ?: return@withContext VoiceAiResult.Failure("response contained no amount")
            VoiceAiResult.Success(result)
        } catch (e: Exception) {
            Log.d("VoiceAI", "AI parse failed", e)
            VoiceAiResult.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

// Extracts the JSON payload from a model reply that may be wrapped in markdown fences or prose.
internal fun extractJsonContent(raw: String): String? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start == -1 || end <= start) return null
    return raw.substring(start, end + 1)
}

// Returns the actual model reply from a chat-completions envelope
// (choices[0].message.content). If the response is not an envelope, returns it unchanged so
// the offline parser / JSON extractor can still work on the raw text.
internal fun extractModelContent(responseText: String): String {
    return runCatching {
        JSONObject(responseText)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?.trim()
    }.getOrNull().orEmpty().ifEmpty { responseText }
}

// Reads a field from a JSON object regardless of key casing, since text-only models often
// return "Amount" / "amount" / "AMOUNT" inconsistently.
private fun JSONObject.optStringIgnoreCase(key: String, defaultValue: String = ""): String {
    if (has(key)) return optString(key, defaultValue).trim()
    val it = keys()
    while (it.hasNext()) {
        val k = it.next().toString()
        if (k.equals(key, ignoreCase = true)) return optString(k, defaultValue).trim()
    }
    return defaultValue
}

// Parses the model's reply into a spending record. Tries strict JSON first (amount is
// mandatory; missing/invalid amount falls through), then the offline parser so plain
// sentences like "user spent 150 rupees on tea" still produce a record.
internal fun parseVoiceAiContent(raw: String): VoiceInputResult? {
    val jsonContent = extractJsonContent(raw)
    if (jsonContent != null) {
        val jsonObject = runCatching { JSONObject(jsonContent) }.getOrNull()
        if (jsonObject != null) {
            val amount = jsonObject.optStringIgnoreCase("amount")
            val result = runCatching {
                VoiceInputResult(
                    amount = amount,
                    comment = jsonObject.optStringIgnoreCase("comment"),
                    date = parseVoiceAiDate(jsonObject.optStringIgnoreCase("date")),
                )
            }.getOrNull()
            if (result != null && result.amount.isNotEmpty()) return result
        }
    }
    return parseVoiceInput(raw)
}

private val VOICE_AI_DATE_TIME_FORMATS = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
)

private val VOICE_AI_DATE_FORMATS = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE,
)

// Parses the date the AI model returned. Supports ISO-8601 with/without time offset,
// "yyyy-MM-dd[ HH:mm[:ss]]" and the relative words today/yesterday/tomorrow. Falls back to
// "now" whenever the value is empty or unparseable.
fun parseVoiceAiDate(value: String, now: Date = Date()): Date {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return now

    val lower = trimmed.lowercase(Locale.ROOT)
    val relative = Calendar.getInstance().apply { time = now }
    when (lower) {
        "today", "now" -> return now
        "yesterday" -> {
            relative.add(Calendar.DAY_OF_YEAR, -1)
            return relative.time
        }
        "tomorrow" -> {
            relative.add(Calendar.DAY_OF_YEAR, 1)
            return relative.time
        }
    }

    try {
        return Date.from(OffsetDateTime.parse(trimmed).toInstant())
    } catch (_: Exception) {
    }
    for (formatter in VOICE_AI_DATE_TIME_FORMATS) {
        try {
            return Date.from(
                LocalDateTime.parse(trimmed, formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            )
        } catch (_: Exception) {
        }
    }
    for (formatter in VOICE_AI_DATE_FORMATS) {
        try {
            return Date.from(
                LocalDate.parse(trimmed, formatter)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
            )
        } catch (_: Exception) {
        }
    }
    return now
}
