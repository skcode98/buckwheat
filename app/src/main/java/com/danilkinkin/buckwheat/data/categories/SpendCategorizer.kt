package com.danilkinkin.buckwheat.data.categories

import android.content.Context
import android.util.Log
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.keyboard.extractJsonContent
import com.danilkinkin.buckwheat.keyboard.extractModelContent
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern

private const val CATEGORY_BATCH_SIZE = 60
private const val CATEGORY_CONNECT_TIMEOUT_MS = 10_000
private const val CATEGORY_READ_TIMEOUT_MS = 20_000

// Whole-word keyword match so a short keyword like "eat" never matches "great". Built once
// and immutable so it is safe to share across threads.
private val categoryPatterns: Map<SpendCategory, Pattern> = SpendCategory.entries
    .filter { it.keywords.isNotEmpty() }
    .associateWith { category ->
        Pattern.compile(
            "\\b(${category.keywords.joinToString("|") { Pattern.quote(it) }})\\b",
            Pattern.CASE_INSENSITIVE,
        )
    }

// Deterministic offline fallback used when a transaction has no persisted (AI) category.
// First category in enum order whose keyword matches the comment wins; otherwise OTHER.
fun offlineClassify(comment: String): SpendCategory {
    val lower = comment.lowercase(Locale.getDefault())
    return SpendCategory.entries.firstOrNull { category ->
        categoryPatterns[category]?.matcher(lower)?.find() == true
    } ?: SpendCategory.OTHER
}

// The category a transaction is displayed under in analytics: the persisted AI category when
// present, otherwise the offline keyword guess. Pure so it is trivially unit-testable.
fun categoryFor(transaction: Transaction): SpendCategory =
    SpendCategory.fromStored(transaction.category) ?: offlineClassify(transaction.comment)

// Batch-assigns categories to uncategorized spends via the configured OpenAI-compatible
// provider (the same settings as Voice AI). Returns an empty map when no API key is saved,
// the call fails, or nothing could be parsed — the offline keyword classifier then stands in.
suspend fun categorizeSpendsWithAi(
    context: Context,
    spends: List<Transaction>,
): Map<Int, SpendCategory> {
    if (spends.isEmpty()) return emptyMap()

    return withContext(Dispatchers.IO) {
        val prefs = context.settingsDataStore.data.first()
        val apiKey = prefs[voiceAiApiKeyStoreKey].orEmpty()
        if (apiKey.isBlank()) return@withContext emptyMap()

        val providerUrl = prefs[voiceAiProviderUrlStoreKey].orEmpty().ifBlank {
            "https://openrouter.ai/api/v1/chat/completions"
        }
        val model = prefs[voiceAiModelStoreKey].orEmpty().ifBlank {
            "google/gemma-3n-e4b-it:free"
        }

        val categoryKeys = SpendCategory.entries.joinToString(", ") { it.name }
        val result = mutableMapOf<Int, SpendCategory>()

        spends.chunked(CATEGORY_BATCH_SIZE).forEach { batch ->
            val assigned = categorizeBatch(
                providerUrl = providerUrl,
                apiKey = apiKey,
                model = model,
                batch = batch,
                categoryKeys = categoryKeys,
            )
            result.putAll(assigned)
        }

        result
    }
}

private suspend fun categorizeBatch(
    providerUrl: String,
    apiKey: String,
    model: String,
    batch: List<Transaction>,
    categoryKeys: String,
): Map<Int, SpendCategory> = withContext(Dispatchers.IO) {
    val records = batch.mapIndexed { index, transaction ->
        val amount = transaction.value.stripTrailingZeros().toPlainString()
        "\"$index\": \"${transaction.comment.trim().ifEmpty { "no comment" }} ($amount)\""
    }.joinToString(separator = "\n")

    val prompt =
        "You divide spending records into exactly one of the predefined categories " +
            "$categoryKeys. Reply with ONLY one JSON object mapping each record index to its " +
            "category, like {\"0\":\"FOOD\",\"1\":\"TRANSPORT\"}. Every index must appear " +
            "exactly once and only these category names are allowed.\n\nRecords:\n{$records}"

    val requestBody = JSONObject()
        .put("model", model)
        .put("temperature", 0)
        .put(
            "messages",
            org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt)),
        )

    val response = runCatching {
        postCategoryRequest(providerUrl, apiKey, requestBody.toString())
    }.getOrElse { error ->
        Log.d("SpendCategorizer", "AI categorize request failed", error)
        return@withContext emptyMap()
    }

    // Remap the model's local record index back to the transaction uid so the caller can
    // persist the assignment. Records the model skipped fall back to the offline classifier.
    parseCategoryResponse(response).mapNotNull { (index, category) ->
        batch.getOrNull(index)?.let { it.uid to category }
    }.toMap()
}

private fun postCategoryRequest(providerUrl: String, apiKey: String, body: String): String {
    var connection: HttpURLConnection? = null
    try {
        connection = URL(providerUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CATEGORY_CONNECT_TIMEOUT_MS
        connection.readTimeout = CATEGORY_READ_TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true

        OutputStreamWriter(connection.outputStream).use { it.write(body) }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                ?.take(200).orEmpty()
            Log.d("SpendCategorizer", "AI categorize HTTP $responseCode — $errorBody")
            return ""
        }

        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection?.disconnect()
    }
}

// Parses the model reply (plain JSON or wrapped in a chat-completions envelope) into a
// map of record index -> category. Unknown indexes/categories are skipped so the offline
// classifier covers those records instead.
internal fun parseCategoryResponse(raw: String): Map<Int, SpendCategory> {
    val content = extractModelContent(raw)
    val jsonContent = extractJsonContent(content) ?: return emptyMap()
    val jsonObject = runCatching { JSONObject(jsonContent) }.getOrNull() ?: return emptyMap()

    val result = mutableMapOf<Int, SpendCategory>()
    val keys = jsonObject.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val index = key.toIntOrNull() ?: continue
        val category = SpendCategory.fromStored(jsonObject.optString(key, "")) ?: continue
        result[index] = category
    }
    return result
}
