package com.danilkinkin.buckwheat.keyboard

import android.content.Context
import com.danilkinkin.buckwheat.ai.AiRouterResult
import com.danilkinkin.buckwheat.ai.callAi
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Outcome of the AI parse attempt. NotConfigured means no API key is saved, so the caller
// silently uses the offline parser. Failure carries a human-readable reason so the user can
// diagnose why the AI path did not power the record (wrong key, rate limit, bad model, etc.).
sealed class VoiceAiResult {
    data class Success(val results: List<VoiceInputResult>) : VoiceAiResult()
    data class Failure(val message: String) : VoiceAiResult()
    object NotConfigured : VoiceAiResult()
}

// Structured voice-AI parsing through the shared provider router (which tries the configured
// providers in fallback order with bounded timeouts). Every failure is reported through
// VoiceAiResult.Failure so the caller can fall back to the offline VoiceInputParser and tell the
// user why AI did not run. When the AI Intelligence master toggle is off — or no valid provider
// key is saved — the caller sees NotConfigured, same as having no API key, and silently uses the
// offline parser.
suspend fun parseVoiceInputWithAi(context: Context, transcript: String): VoiceAiResult =
    when (val result = callAi(
        context = context,
        systemPrompt = "You extract spending records from a user voice transcript. " +
            "Reply with ONLY a JSON array and nothing else, e.g. " +
            "[{\"amount\":\"150\",\"comment\":\"tea\",\"date\":\"today\"}," +
            "{\"amount\":\"45\",\"comment\":\"bus\",\"date\":\"yesterday\"}]. " +
            "If the transcript contains one record, return an array with " +
            "one object. amount must be a plain numeric string. comment must " +
            "be a concise description. date must be ISO-8601, 'today', " +
            "'yesterday', 'tomorrow', or null.",
        userPrompt = "Transcript: $transcript",
    )) {
        is AiRouterResult.Success -> {
            val results = parseVoiceAiContents(result.text)
            if (results.isEmpty()) {
                VoiceAiResult.Failure("response contained no amount")
            } else {
                VoiceAiResult.Success(results)
            }
        }
        is AiRouterResult.Failure -> VoiceAiResult.Failure(result.message)
        AiRouterResult.NotConfigured -> VoiceAiResult.NotConfigured
    }

// Validates an AI provider URL: must parse and use http/https with a non-empty host. Pure so it
// is unit-testable.
internal fun isValidAiProviderUrl(raw: String): Boolean {
    val parsed = runCatching { URL(raw.trim()) }.getOrNull() ?: return false
    return (parsed.protocol == "http" || parsed.protocol == "https") &&
        parsed.host.isNotBlank()
}

// Extracts the JSON payload from a model reply that may be wrapped in markdown fences or prose.
internal fun extractJsonContent(raw: String): String? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start == -1 || end <= start) return null
    return raw.substring(start, end + 1)
}

// Extracts the first JSON object or array from a reply that may be wrapped in markdown fences
// or prose. Unlike extractJsonContent it tolerates array replies (leading '[' / trailing ']').
internal fun extractJsonArrayOrObject(raw: String): String? {
    val start = raw.indexOfFirst { it == '[' || it == '{' }
    if (start == -1) return null
    val endChar = if (raw[start] == '[') ']' else '}'
    val end = raw.lastIndexOf(endChar)
    if (end <= start) return null
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

// Parses the model's reply into zero or more spending records. Tries a JSON array first
// (the batch format), then a single JSON object, then the offline splitter so plain sentences
// like "user spent 150 rupees on tea and 45 on a bus" still produce records.
internal fun parseVoiceAiContents(raw: String): List<VoiceInputResult> {
    val jsonContent = extractJsonArrayOrObject(raw) ?: return parseVoiceInputs(raw)
    try {
        val trimmed = jsonContent.trim()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            val records = buildList {
                for (i in 0 until array.length()) {
                    val element = array.optJSONObject(i) ?: continue
                    val amount = element.optStringIgnoreCase("amount")
                    if (amount.isNotEmpty()) {
                        add(
                            VoiceInputResult(
                                amount = amount,
                                comment = element.optStringIgnoreCase("comment"),
                                date = parseVoiceAiDate(element.optStringIgnoreCase("date")),
                            )
                        )
                    }
                }
            }
            if (records.isNotEmpty()) return records
        } else {
            val jsonObject = JSONObject(trimmed)
            val amount = jsonObject.optStringIgnoreCase("amount")
            if (amount.isNotEmpty()) {
                return listOf(
                    VoiceInputResult(
                        amount = amount,
                        comment = jsonObject.optStringIgnoreCase("comment"),
                        date = parseVoiceAiDate(jsonObject.optStringIgnoreCase("date")),
                    )
                )
            }
        }
    } catch (_: Exception) {
    }
    return parseVoiceInputs(raw)
}

// Parses a single record from the model's reply (backward-compatible entry point that
// delegates to the batch parser and returns the first record, if any).
internal fun parseVoiceAiContent(raw: String): VoiceInputResult? =
    parseVoiceAiContents(raw).firstOrNull()

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
