package com.danilkinkin.buckwheat.ai

import android.content.Context
import com.danilkinkin.buckwheat.di.DEFAULT_VOICE_AI_MODEL
import com.danilkinkin.buckwheat.di.DEFAULT_VOICE_AI_PROVIDER_URL
import com.danilkinkin.buckwheat.di.aiIntelligenceEnabled
import com.danilkinkin.buckwheat.di.normalizeVoiceAiModel
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.keyboard.extractModelContent
import com.danilkinkin.buckwheat.interleaved.WindowSpend
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000

// One category's share of the period spend, as fed to the AI report.
data class CategorySpendInsight(
    val name: String,
    val amount: BigDecimal,
    val percent: Int,
)

// Aggregated, anonymous view of the current budget period that the AI report is built from.
// No comments/identifiable data are sent — only totals, dates and category sums.
data class SpendInsightSummary(
    val currencyCode: String,
    val budget: BigDecimal,
    val spent: BigDecimal,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val today: LocalDate,
    val transactionCount: Int,
    val categories: List<CategorySpendInsight>,
    val biggestSpend: BigDecimal?,
    val biggestSpendComment: String?,
    val overspendDays: Int,
    val previousPeriodTotal: BigDecimal?,
)

sealed class AiInsightResult {
    data class Success(val report: String) : AiInsightResult()
    data class Failure(val message: String) : AiInsightResult()
    object NotConfigured : AiInsightResult()
}

private val INSIGHT_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy")

// Formats an amount the same way in the prompt and in tests: deterministic, locale-independent.
internal fun insightAmount(value: BigDecimal): String =
    value.setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()

internal fun buildAiInsightSystemPrompt(): String =
    "You are a personal finance analyst for the Buckwheat budget app. Analyze the user's " +
        "spending for their budget period and reply with a concise, friendly analysis in the " +
        "language the user writes in. Structure your answer as: 1) one overview sentence on how " +
        "the period is going, 2) 3-5 bullet observations (start each line with '• '), calling out " +
        "category concentration, spending pace, overspending days and the biggest expense, " +
        "3) a short 'Watch out for' note only if something needs attention, and 4) one practical " +
        "tip. Keep it under 120 words. Do not use markdown headers or code blocks."

internal fun buildAiInsightUserPrompt(summary: SpendInsightSummary): String {
    val remaining = (summary.budget - summary.spent).coerceAtLeast(BigDecimal.ZERO)
    val elapsedDays = ChronoUnit.DAYS.between(summary.startDate, summary.today)
        .toInt()
        .coerceAtLeast(1)
    val average = if (summary.spent > BigDecimal.ZERO) {
        summary.spent.divide(elapsedDays.toBigDecimal(), 2, RoundingMode.HALF_EVEN)
    } else {
        BigDecimal.ZERO
    }
    val categoryLines = if (summary.categories.isEmpty()) {
        "  none"
    } else {
        summary.categories.joinToString("\n") { category ->
            "  - ${category.name}: ${insightAmount(category.amount)} (${category.percent}%)"
        }
    }
    return buildString {
        append("Budget period: ${summary.startDate.format(INSIGHT_DATE_FORMAT)} to ")
        append("${summary.endDate.format(INSIGHT_DATE_FORMAT)}")
        append("\nToday: ${summary.today.format(INSIGHT_DATE_FORMAT)}")
        append("\nCurrency: ${summary.currencyCode.ifBlank { "(none)" }}")
        append("\nBudget: ${insightAmount(summary.budget)}")
        append("\nSpent so far: ${insightAmount(summary.spent)}")
        append("\nRemaining: ${insightAmount(remaining)}")
        append("\nElapsed: $elapsedDays days")
        append("\nAverage spend per day: ${insightAmount(average)}")
        append("\nTransactions: ${summary.transactionCount}")
        append("\nOverspending days: ${summary.overspendDays}")
        append(
            "\nPrevious period spent: ${
                summary.previousPeriodTotal?.let { insightAmount(it) } ?: "none"
            }"
        )
        append(
            "\nBiggest expense: ${
                if (summary.biggestSpendComment.isNullOrBlank()) {
                    summary.biggestSpend?.let { insightAmount(it) } ?: "none"
                } else {
                    "${summary.biggestSpendComment} (${summary.biggestSpend?.let { insightAmount(it) } ?: "0"})"
                }
            }"
        )
        append("\nCategory breakdown:\n")
        append(categoryLines)
    }
}

// Cleans the model's report for display: strips markdown code fences, an echoed "Report:" label
// and excess blank lines. Pure so it is unit-testable.
internal fun parseAiInsightReport(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    var text = trimmed
    text = MARKDOWN_FENCE.replace(text, "").trim()
    text = text.replaceFirst(Regex("^[Rr]eport:?\\s*"), "").trim()
    text = text.replace(Regex("\\n{3,}"), "\n\n").trim()
    return text
}

private val MARKDOWN_FENCE = Regex("```[a-zA-Z]*\\s*")

// Counts distinct days (within [start, end], capped at today) whose total spend exceeded the
// daily budget. Zero/negative daily budgets disable the metric.
internal fun overspendDayCount(
    spends: List<WindowSpend>,
    start: LocalDate,
    finish: LocalDate,
    today: LocalDate,
    dailyBudget: BigDecimal,
): Int {
    if (dailyBudget <= BigDecimal.ZERO) return 0
    val end = minOf(finish, today)
    if (end.isBefore(start)) return 0
    return spends
        .mapNotNull { spend ->
            spend.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                .takeIf { day -> !day.isBefore(start) && !day.isAfter(end) }
                ?.let { day -> day to spend.value }
        }
        .groupBy({ it.first }, { it.second })
        .count { (_, values) -> values.fold(BigDecimal.ZERO) { acc, value -> acc + value } > dailyBudget }
}

// Generates an AI spending analysis for the current period. Network access is bounded (10s
// connect / 20s read), the socket is always disconnected, and failures carry a human-readable
// reason. When the AI Intelligence master toggle is off or no API key is saved the caller sees
// NotConfigured and renders the setup hint instead.
suspend fun generateAiInsight(
    context: Context,
    summary: SpendInsightSummary,
): AiInsightResult = withContext(Dispatchers.IO) {
    val prefs = context.settingsDataStore.data.first()
    val apiKey = prefs[voiceAiApiKeyStoreKey].orEmpty()
    if (!aiIntelligenceEnabled(prefs) || apiKey.isBlank()) {
        return@withContext AiInsightResult.NotConfigured
    }

    val providerUrl = prefs[voiceAiProviderUrlStoreKey].orEmpty().ifBlank {
        DEFAULT_VOICE_AI_PROVIDER_URL
    }
    val model = normalizeVoiceAiModel(prefs[voiceAiModelStoreKey])
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_VOICE_AI_MODEL

    val requestBody = JSONObject()
        .put("model", model)
        .put("temperature", 0)
        .put(
            "messages",
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", buildAiInsightSystemPrompt())
                )
                .put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", buildAiInsightUserPrompt(summary))
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
            val errorBody = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty().trim().take(200)
            val suffix = if (errorBody.isNotEmpty()) " — $errorBody" else ""
            return@withContext AiInsightResult.Failure("HTTP ${conn.responseCode}$suffix")
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val report = parseAiInsightReport(extractModelContent(responseText))
        if (report.isEmpty()) {
            return@withContext AiInsightResult.Failure("response contained no analysis")
        }
        AiInsightResult.Success(report)
    } catch (e: Exception) {
        AiInsightResult.Failure(e.message ?: e.javaClass.simpleName)
    } finally {
        connection?.disconnect()
    }
}
