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

// Structured voice-AI parsing. Network access is bounded (10s connect / 20s read), the socket
// is always disconnected, and every failure returns null so the caller can fall back to the
// offline VoiceInputParser.
suspend fun parseVoiceInputWithAiFallback(context: Context, transcript: String): VoiceInputResult? =
    withContext(Dispatchers.IO) {
        val prefs = context.settingsDataStore.data.first()
        val apiKey = prefs[voiceAiApiKeyStoreKey].orEmpty()
        if (apiKey.isBlank()) return@withContext null

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
                                    "Return ONLY one JSON object with fields amount, comment, date. " +
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
                Log.d("VoiceAI", "AI parse failed with code ${conn.responseCode}")
                return@withContext null
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val content = extractJsonContent(responseText) ?: return@withContext null
            val jsonObject = runCatching { JSONObject(content) }.getOrNull()
                ?: return@withContext null

            val amount = jsonObject.optString("amount", "").trim()
            val comment = jsonObject.optString("comment", "").trim()
            val date = parseVoiceAiDate(jsonObject.optString("date", ""))

            if (amount.isEmpty()) return@withContext null
            VoiceInputResult(amount, comment, date)
        } catch (e: Exception) {
            Log.d("VoiceAI", "AI parse failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

// Extracts the JSON payload from a model reply that may be wrapped in markdown fences or prose.
private fun extractJsonContent(raw: String): String? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start == -1 || end <= start) return null
    return raw.substring(start, end + 1)
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
