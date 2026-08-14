package com.danilkinkin.buckwheat.data.categories

import android.content.Context
import android.util.Log
import com.danilkinkin.buckwheat.ai.AiRouterResult
import com.danilkinkin.buckwheat.ai.callAi
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.keyboard.extractJsonContent
import com.danilkinkin.buckwheat.keyboard.extractModelContent
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import java.util.regex.Pattern

private const val CATEGORY_BATCH_SIZE = 60

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

// The offline keyword category to PERSIST on a transaction, or null when there is no
// confident keyword match (offlineClassify would return OTHER). Unlike offlineClassify this
// never returns OTHER, so blank/junk comments stay uncategorized and can still be refined
// by the AI pass (or left to the display fallback). Pure so it is trivially unit-testable.
fun offlineCategoryOrNull(comment: String): SpendCategory? =
    offlineClassify(comment).takeIf { it != SpendCategory.OTHER }

// The category a transaction is displayed under in analytics: the persisted AI category when
// present, otherwise the offline keyword guess. Pure so it is trivially unit-testable.
fun categoryFor(transaction: Transaction): SpendCategory =
    SpendCategory.fromStored(transaction.category) ?: offlineClassify(transaction.comment)

// Display key for the analytics category breakdown. Persisted built-in categories and offline
// keyword guesses resolve to the enum; any other non-blank persisted value is a user-created
// custom category and keeps its raw name so it gets its own slice instead of falling back to
// the keyword/OTHER bucket. Pure so it is trivially unit-testable.
sealed interface CategoryKey {
    data class BuiltIn(val category: SpendCategory) : CategoryKey
    data class Custom(val name: String) : CategoryKey
}

fun categoryKey(transaction: Transaction): CategoryKey {
    val stored = transaction.category?.takeIf { it.isNotBlank() }
    return if (stored != null) {
        SpendCategory.fromStored(stored)
            ?.let { CategoryKey.BuiltIn(it) }
            ?: CategoryKey.Custom(stored)
    } else {
        CategoryKey.BuiltIn(offlineClassify(transaction.comment))
    }
}

// Aggregates a period's spends into (categoryKey, total) pairs, dropping non-positive totals.
// Pure so it is unit-testable; the composable maps keys to localized labels and colors.
fun categoryTotals(spends: List<Transaction>): List<Pair<CategoryKey, BigDecimal>> {
    val totals = linkedMapOf<CategoryKey, BigDecimal>()
    spends.forEach { transaction ->
        val key = categoryKey(transaction)
        totals[key] = (totals[key] ?: BigDecimal.ZERO) + transaction.value
    }
    return totals.filterValues { it > BigDecimal.ZERO }.toList()
}

// Whether a transaction is displayed under the given category key in the analytics breakdown.
// The filter backing the category drill-down. Pure so it is trivially unit-testable.
fun transactionMatchesCategory(transaction: Transaction, key: CategoryKey): Boolean =
    categoryKey(transaction) == key

// Batch-assigns categories to uncategorized spends through the shared provider router (same
// engine as Voice AI and the monthly insight). Returns an empty map when the AI Intelligence
// master toggle is off, no valid provider key is saved, the call fails, or nothing could be
// parsed — the offline keyword classifier then stands in.
suspend fun categorizeSpendsWithAi(
    context: Context,
    spends: List<Transaction>,
): Map<Int, SpendCategory> {
    if (spends.isEmpty()) return emptyMap()

    val categoryKeys = SpendCategory.entries.joinToString(", ") { it.name }
    val result = mutableMapOf<Int, SpendCategory>()

    spends.chunked(CATEGORY_BATCH_SIZE).forEach { batch ->
        val records = batch.mapIndexed { index, transaction ->
            val amount = transaction.value.stripTrailingZeros().toPlainString()
            "\"$index\": \"${transaction.comment.trim().ifEmpty { "no comment" }} ($amount)\""
        }.joinToString(separator = "\n")
        val systemPrompt =
            "You divide spending records into exactly one of the predefined categories " +
                "$categoryKeys. Reply with ONLY one JSON object mapping each record index to its " +
                "category, like {\"0\":\"FOOD\",\"1\":\"TRANSPORT\"}. Every index must appear " +
                "exactly once and only these category names are allowed."
        val userPrompt = "Records:\n{$records}"

        when (val ai = callAi(context = context, systemPrompt = systemPrompt, userPrompt = userPrompt)) {
            is AiRouterResult.Success -> {
                // Remap the model's local record index back to the transaction uid so the caller
                // can persist the assignment. Records the model skipped fall back to the offline
                // classifier.
                parseCategoryResponse(ai.text).forEach { (index, category) ->
                    batch.getOrNull(index)?.let { result[it.uid] = category }
                }
            }
            is AiRouterResult.Failure -> {
                Log.d("SpendCategorizer", "AI categorize failed: ${ai.message}")
            }
            AiRouterResult.NotConfigured -> Unit
        }
    }

    return result
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
